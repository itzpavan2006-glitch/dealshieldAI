# Database

## Status

The separate Supabase project `dealshield-ai-fresh` (reference `umdmxoegwccerfzhpruc`) is provisioned. Flyway V1, the 11 application tables, RLS policies, restricted runtime login and live Spring Boot connection were verified. A live transactional cross-buyer test could not SET ROLE through the Supabase SQL connector and rolled back without leaving fixtures; cross-buyer checks passed in disposable PostgreSQL. The authenticated buyer identity mechanism and live cross-buyer test remain outstanding, so Phase 2 remains incomplete.

## Proposed migration

Flyway creates the `dealshield` schema and its own history table there, and executes V1. V1 creates the buyers, comparisons, suppliers, quotations, quotation items, files, extraction results, findings, comparison results, decisions and audit events tables. Composite foreign keys include the buyer ID so references cannot cross buyers; three numbered slots cap each comparison at three quotations. Monetary values use numeric. Constraints and indexes protect the main relationships. A private `dealshield_app` NOLOGIN group role has narrow grants, and row-level security is forced for the application tables. The migration account must have schema and role creation rights. Supabase's built-in `anon` and `authenticated` roles are assumed; the schema is not exposed to them.

The backend must obtain the buyer ID from a verified server-side identity and set `dealshield.buyer_id` with `set_config(..., true)` in the same transaction before every buyer query. The value is transaction-local and must never come from a client-supplied buyer ID. Without it, RLS denies access. The database role is trusted to set the context: someone holding its credentials could impersonate another buyer, so it must only be accessible to the trusted backend. Auth, buyer provisioning, and the transaction-scoped identity integration remain future work; the application must not serve buyer data until these are implemented and tested.

The migrator/owner account must be separate from a dedicated runtime login, which will be a non-owner member of `dealshield_app`, with no superuser or BYPASSRLS privileges. It has not yet been provisioned. `DatabaseRoleGuard` checks basic role properties on database-profile startup; it is not proof of isolation. Never run the application with the privileged migration account.

## Private credential setup

1. Open the **new** project in Supabase, click **Connect**, and select **Session pooler**. Note its host, port, database name and username on your computer. The shared session pooler works on IPv4; the direct host generally requires IPv6. Do not copy secrets into chat. See [Supabase connection guidance](https://supabase.com/docs/guides/database/connecting-to-postgres).
2. In project database settings, obtain the server root certificate and save it locally outside this repository. The JDBC URL must use `sslmode=verify-full` with `sslrootcert` pointing to that file. Verify the certificate and connection details in the Supabase dashboard.
3. Once migration execution has been explicitly approved, open a new PowerShell terminal in `dealshield-ai/backend`. Use the following prompts, entering values locally. Use the dashboard's actual session-pooler host and database in the JDBC URL, for example `jdbc:postgresql://HOST:5432/postgres?sslmode=verify-full&sslrootcert=C:/path/to/prod-supabase.cer`. Do not put the password in the URL.

```powershell
$env:FLYWAY_URL = Read-Host 'JDBC URL (without password)'
$env:FLYWAY_USER = Read-Host 'Migration database username'
$secureDbPassword = Read-Host 'Migration database password' -AsSecureString
$dbPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureDbPassword)
try { $env:FLYWAY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($dbPasswordPointer) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($dbPasswordPointer) }
```

These environment variables belong only to that terminal process and are inherited by its child processes. Don't run `Get-ChildItem Env:` or share its contents. Close the terminal when finished. If you do not know the database password, use Supabase's database settings to reset it locally; this changes the project's database credential.

## After approval

The disposable test was run against local PostgreSQL 17.12; it did not use Supabase. The user approved the live migration. To enter credentials privately, run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-approved-migration.ps1` from the project root and follow its prompts. This calls the guarded migration script, which checks the direct host or session-pooler project username, database name, port, TLS settings, root certificate, and SHA-256 of the sole reviewed migration before calling Flyway. It asks for the project reference as a final target check. The SQL itself is portable and does not hard-code a project. Flyway migrations create database objects and are not reversible automatically. Afterwards inspect Flyway history and security advisors.

The runtime login provisioning, Spring backend connectivity test, cross-buyer checks under that actual runtime role and authentication integration are still outstanding. Run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-runtime.ps1` from the project root to enter the administrator credential and a new restricted runtime password privately, create the login with membership in the existing group, and start the backend. On future starts use `run-database-backend.ps1`; this does not create or modify roles. Never rerun setup after an uncertain outcome without first inspecting the role. Normal backend startup has Flyway auto-configuration disabled, so it cannot silently migrate. Database profile variables `DEALSHIELD_DB_URL`, `DEALSHIELD_DB_USER` and `DEALSHIELD_DB_PASSWORD` are for the separate restricted runtime login, not the migrator.
