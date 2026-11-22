# Only run after explicit approval to migrate dealshield-ai-fresh.
# FLYWAY_URL, FLYWAY_USER and FLYWAY_PASSWORD must be set privately beforehand.
$ErrorActionPreference = 'Stop'
$projectRef = 'umdmxoegwccerfzhpruc'
$migrationDirectory = Join-Path $PSScriptRoot '..\backend\src\main\resources\db\migration'
$migrationFiles = @(Get-ChildItem -LiteralPath $migrationDirectory -File -Recurse)
$approvedMigrations = @{
  'V1__database_foundation.sql' = '98525CBFCD82FB5FD8DA19B01F052599BBE3FE9F95888A70D60D9BEDF3C42637'
  'V2__buyer_provisioning.sql' = 'FED68574EFCA2AC632EBC8AA7A157E3E0EE04E64313E99E7FF1BEA8814A31E2A'
}
if ($migrationFiles.Count -ne $approvedMigrations.Count) { throw 'Migration file count differs from the reviewed V1 and V2; migration stopped.' }
foreach ($migrationFile in $migrationFiles) {
  if (-not $approvedMigrations.ContainsKey($migrationFile.Name) -or (Get-FileHash -LiteralPath $migrationFile.FullName -Algorithm SHA256).Hash -ne $approvedMigrations[$migrationFile.Name]) { throw 'Migration files differ from the reviewed V1 and V2; migration stopped.' }
}
if (-not $env:FLYWAY_URL -or -not $env:FLYWAY_USER -or -not $env:FLYWAY_PASSWORD) { throw 'Set the three Flyway environment variables privately first.' }
if (-not $env:FLYWAY_URL.StartsWith('jdbc:postgresql://')) { throw 'Expected a PostgreSQL JDBC URL.' }
try { $connectionUri = [Uri]$env:FLYWAY_URL.Substring(5) } catch { throw 'Invalid JDBC URL.' }
if ($connectionUri.Scheme -ne 'postgresql' -or $connectionUri.UserInfo -or $connectionUri.Port -ne 5432 -or $connectionUri.AbsolutePath -ne '/postgres' -or $connectionUri.Fragment) { throw 'Unexpected connection details. Use the fresh project direct or session-pooler connection on port 5432, without credentials in the URL.' }
$direct = $connectionUri.Host -eq "db.$projectRef.supabase.co" -and $env:FLYWAY_USER -eq 'postgres'
$pooled = $connectionUri.Host -match '^aws-[a-z0-9-]+\.pooler\.supabase\.com$' -and $env:FLYWAY_USER -eq "postgres.$projectRef"
if (-not ($direct -or $pooled)) { throw 'Connection does not match the designated fresh project and migration username.' }
$parameters = @{}
foreach ($pair in $connectionUri.Query.TrimStart('?').Split('&')) {
  $parts = $pair.Split('=', 2)
  if ($parts.Count -ne 2) { throw 'Invalid JDBC URL parameter.' }
  $key = [Uri]::UnescapeDataString($parts[0]).ToLowerInvariant()
  if ($parameters.ContainsKey($key) -or $key -notin @('sslmode','sslrootcert')) { throw 'Only sslmode and sslrootcert parameters are allowed.' }
  $parameters[$key] = [Uri]::UnescapeDataString($parts[1])
}
if ($parameters['sslmode'] -ne 'verify-full' -or -not (Test-Path -LiteralPath $parameters['sslrootcert'] -PathType Leaf)) { throw 'A local root certificate and sslmode=verify-full are required.' }
$confirmation = Read-Host "Type $projectRef to confirm the approved live migration"
if ($confirmation -ne $projectRef) { throw 'Confirmation did not match; no migration was run.' }
# Ignore unrelated Flyway environment overrides, including alternative locations or schemas.
Get-ChildItem Env: | Where-Object { $_.Name -like 'FLYWAY_*' -and $_.Name -notin @('FLYWAY_URL','FLYWAY_USER','FLYWAY_PASSWORD') } | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
Push-Location (Join-Path $PSScriptRoot '..\backend')
try { & .\mvnw.cmd -B flyway:migrate; if ($LASTEXITCODE -ne 0) { throw 'Flyway reported a failure.' } } finally { Pop-Location }
