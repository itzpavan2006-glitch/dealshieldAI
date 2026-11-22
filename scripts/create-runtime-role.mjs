import fs from 'node:fs';
import pg from 'pg';

const host = 'aws-1-ap-south-1.pooler.supabase.com';
const ref = 'umdmxoegwccerfzhpruc';
const certPath = process.env.DEALSHIELD_CA_FILE;
const adminPassword = process.env.DEALSHIELD_ADMIN_PASSWORD;
const runtimePassword = process.env.DEALSHIELD_RUNTIME_PASSWORD;
if (!certPath || !adminPassword || !runtimePassword) throw new Error('Private setup variables are missing.');
const ca = fs.readFileSync(certPath, 'utf8');
const config = { host, port: 5432, database: 'postgres', ssl: { ca, rejectUnauthorized: true }, connectionTimeoutMillis: 15000 };
const admin = new pg.Client({ ...config, user: `postgres.${ref}`, password: adminPassword });
try {
  await admin.connect();
  const roles = await admin.query("select rolname from pg_roles where rolname in ('dealshield_app','dealshield_runtime')");
  if (!roles.rows.some(row => row.rolname === 'dealshield_app') || roles.rows.some(row => row.rolname === 'dealshield_runtime')) throw new Error('Expected group role missing or runtime login already exists; no changes made.');
  await admin.query('BEGIN');
  try {
    await admin.query(`CREATE ROLE dealshield_runtime LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD ${pg.escapeLiteral(runtimePassword)}`);
    await admin.query('GRANT dealshield_app TO dealshield_runtime');
    await admin.query('COMMIT');
  } catch (error) { await admin.query('ROLLBACK'); throw error; }
  console.log('Restricted runtime login created with dealshield_app membership.');
} catch (error) {
  console.error('Runtime role setup failed. No password is displayed. Error code:', error.code ?? 'SETUP_ERROR');
  process.exitCode = 1;
} finally { await admin.end().catch(() => {}); }
