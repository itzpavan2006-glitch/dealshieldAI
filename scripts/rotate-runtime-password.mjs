import crypto from 'node:crypto'
import fs from 'node:fs'
import pg from 'pg'

const host = 'aws-1-ap-south-1.pooler.supabase.com'
const projectRef = 'umdmxoegwccerfzhpruc'
const runtimeRole = 'dealshield_runtime'
const certPath = process.env.DEALSHIELD_ROTATE_CERT
const adminPassword = process.env.DEALSHIELD_ROTATE_ADMIN_PASSWORD
const runtimePassword = process.env.DEALSHIELD_ROTATE_RUNTIME_PASSWORD
if (!certPath || !adminPassword || !runtimePassword) throw new Error('Private rotation variables are missing')

const common = {
  host, port: 5432, database: 'postgres',
  ssl: { ca: fs.readFileSync(certPath, 'utf8'), rejectUnauthorized: true },
  connectionTimeoutMillis: 15000,
}
const admin = new pg.Client({ ...common, user: `postgres.${projectRef}`, password: adminPassword })
let rotated = false
try {
  await admin.connect()
  const check = await admin.query(`
    select current_user, current_database(),
           (select rolcreaterole from pg_roles where rolname = current_user) as can_create_role,
           (select rolcanlogin and not rolsuper and not rolcreaterole and not rolcreatedb and not rolbypassrls
              from pg_roles where rolname = $1) as runtime_is_restricted,
           pg_has_role($1, 'dealshield_app', 'member') as runtime_is_app_member
  `, [runtimeRole])
  const row = check.rows[0]
  if (row.current_user !== 'postgres' || row.current_database !== 'postgres') {
    throw Object.assign(new Error('Connected administrator or database does not match the approved target'), { code: 'TARGET_MISMATCH' })
  }
  if (!row.can_create_role || !row.runtime_is_restricted || !row.runtime_is_app_member) {
    throw Object.assign(new Error('Administrator permission or runtime role state is not approved'), { code: 'ROLE_STATE_MISMATCH' })
  }

  await admin.query('begin')
  try {
    await admin.query(`alter role ${runtimeRole} password ${pg.escapeLiteral(runtimePassword)}`)
    await admin.query('commit')
    rotated = true
  } catch (error) {
    await admin.query('rollback')
    throw error
  }
} catch (error) {
  console.error('Runtime password rotation failed. No password was displayed. Error code:', error.code ?? 'ROTATION_ERROR')
  process.exitCode = 1
} finally {
  await admin.end().catch(() => {})
}

if (rotated) {
  const runtime = new pg.Client({ ...common, user: `${runtimeRole}.${projectRef}`, password: runtimePassword })
  const own = crypto.randomUUID()
  const other = crypto.randomUUID()
  let transactionOpen = false
  try {
    await runtime.connect()
    const identity = await runtime.query(`
      select current_user,
             (select rolsuper or rolcreaterole or rolcreatedb or rolbypassrls
                from pg_roles where rolname = current_user) as privileged,
             pg_has_role(current_user, 'dealshield_app', 'member') as app_member
    `)
    if (identity.rows[0].current_user !== runtimeRole || identity.rows[0].privileged || !identity.rows[0].app_member) {
      throw Object.assign(new Error('Runtime identity check failed'), { code: 'RUNTIME_IDENTITY_FAILED' })
    }

    await runtime.query('begin')
    transactionOpen = true
    await runtime.query("select set_config('dealshield.buyer_id', $1, true)", [own])
    await runtime.query(`insert into dealshield.buyers (id, auth_user_id, display_name) values ($1, $1, 'Rollback-only verification')`, [own])
    const visible = await runtime.query('select id from dealshield.buyers')
    if (visible.rowCount !== 1 || visible.rows[0].id !== own) throw Object.assign(new Error('Self profile was not isolated'), { code: 'SELF_ISOLATION_FAILED' })

    await runtime.query('savepoint cross_buyer')
    let crossRejected = false
    try {
      await runtime.query(`insert into dealshield.buyers (id, auth_user_id, display_name) values ($1, $1, 'Must be rejected')`, [other])
    } catch (error) { crossRejected = error?.code === '42501' }
    await runtime.query('rollback to savepoint cross_buyer')
    if (!crossRejected) throw Object.assign(new Error('Cross-buyer insert was not rejected'), { code: 'CROSS_BUYER_NOT_REJECTED' })

    await runtime.query('savepoint subject_mismatch')
    let mismatchRejected = false
    try {
      await runtime.query(`insert into dealshield.buyers (id, auth_user_id, display_name) values ($1, $2, 'Must be rejected')`, [own, other])
    } catch (error) { mismatchRejected = error?.code === '42501' }
    await runtime.query('rollback to savepoint subject_mismatch')
    if (!mismatchRejected) throw Object.assign(new Error('Mismatched subject was not rejected'), { code: 'SUBJECT_MISMATCH_NOT_REJECTED' })

    await runtime.query('rollback')
    transactionOpen = false
    const noContext = await runtime.query('select count(*)::int as count from dealshield.buyers')
    if (noContext.rows[0].count !== 0) throw Object.assign(new Error('Rollback or context cleanup failed'), { code: 'ROLLBACK_FAILED' })
    console.log('Password rotation and rollback-only V2 verification passed.')
    console.log('Verified: restricted runtime identity, self-provisioning, cross-buyer rejection, subject-match enforcement, and zero persisted rows.')
  } catch (error) {
    console.error('Runtime verification failed. No password was displayed. Error code:', error.code ?? 'VERIFY_ERROR')
    process.exitCode = 1
  } finally {
    if (transactionOpen) await runtime.query('rollback').catch(() => {})
    await runtime.end().catch(() => {})
  }
}
