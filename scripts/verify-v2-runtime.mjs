import crypto from 'node:crypto'
import fs from 'node:fs'
import pg from 'pg'

const required = ['DEALSHIELD_VERIFY_HOST', 'DEALSHIELD_VERIFY_CERT', 'DEALSHIELD_VERIFY_PASSWORD']
for (const name of required) if (!process.env[name]) throw new Error(`Missing ${name}`)

const client = new pg.Client({
  host: process.env.DEALSHIELD_VERIFY_HOST,
  port: 5432,
  database: 'postgres',
  user: 'dealshield_runtime.umdmxoegwccerfzhpruc',
  password: process.env.DEALSHIELD_VERIFY_PASSWORD,
  ssl: { ca: fs.readFileSync(process.env.DEALSHIELD_VERIFY_CERT, 'utf8'), rejectUnauthorized: true },
})

const own = crypto.randomUUID()
const other = crypto.randomUUID()
let transactionOpen = false
try {
  await client.connect()
  const identity = await client.query(`
    select current_user,
           (select rolsuper or rolbypassrls from pg_roles where rolname = current_user) as privileged
  `)
  if (identity.rows[0].current_user !== 'dealshield_runtime' || identity.rows[0].privileged) {
    throw new Error('Connected role is not the restricted runtime login')
  }

  await client.query('begin')
  transactionOpen = true
  await client.query("select set_config('dealshield.buyer_id', $1, true)", [own])
  await client.query(`
    insert into dealshield.buyers (id, auth_user_id, display_name)
    values ($1, $1, 'Rollback-only verification')
  `, [own])
  const visible = await client.query('select id from dealshield.buyers')
  if (visible.rowCount !== 1 || visible.rows[0].id !== own) throw new Error('Own profile was not isolated correctly')

  await client.query('savepoint cross_buyer_check')
  let crossBuyerRejected = false
  try {
    await client.query(`
      insert into dealshield.buyers (id, auth_user_id, display_name)
      values ($1, $1, 'Must be rejected')
    `, [other])
  } catch (error) {
    crossBuyerRejected = error?.code === '42501'
  }
  await client.query('rollback to savepoint cross_buyer_check')
  if (!crossBuyerRejected) throw new Error('Cross-buyer insert was not rejected by RLS')

  await client.query('savepoint mismatched_subject_check')
  let mismatchRejected = false
  try {
    await client.query(`
      insert into dealshield.buyers (id, auth_user_id, display_name)
      values ($1, $2, 'Must be rejected')
    `, [own, other])
  } catch (error) {
    mismatchRejected = error?.code === '42501'
  }
  await client.query('rollback to savepoint mismatched_subject_check')
  if (!mismatchRejected) throw new Error('Mismatched auth subject was not rejected by RLS')

  await client.query('rollback')
  transactionOpen = false
  const persisted = await client.query('select count(*)::int as count from dealshield.buyers')
  if (persisted.rows[0].count !== 0) throw new Error('Rollback-only verification unexpectedly persisted data')
  console.log('V2 runtime verification passed: self-provision allowed; cross-buyer and mismatched-subject inserts rejected; no rows persisted.')
} finally {
  if (transactionOpen) await client.query('rollback').catch(() => {})
  await client.end().catch(() => {})
}
