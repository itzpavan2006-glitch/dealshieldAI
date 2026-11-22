# Interactive setup for the approved live migration. Do not place passwords in chat.
$ErrorActionPreference = 'Stop'
Write-Host 'Use only connection details from dealshield-ai-fresh (umdmxoegwccerfzhpruc).'
$dbHost = (Read-Host 'Session pooler host from Supabase Connect (host only)').Trim()
$certificatePath = (Read-Host 'Full path to the downloaded Supabase root certificate').Trim().Trim('"')
if ($dbHost -notmatch '^aws-[a-z0-9-]+\.pooler\.supabase\.com$') { throw 'Enter the session pooler hostname only, for example aws-N-region.pooler.supabase.com.' }
if (-not (Test-Path -LiteralPath $certificatePath -PathType Leaf)) { throw 'Certificate file not found.' }
$dbPassword = Read-Host 'Database password (typing is hidden)' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($dbPassword)
try {
    $env:FLYWAY_URL = "jdbc:postgresql://${dbHost}:5432/postgres?sslmode=verify-full&sslrootcert=$([Uri]::EscapeDataString($certificatePath))"
    $env:FLYWAY_USER = 'postgres.umdmxoegwccerfzhpruc'
    $env:FLYWAY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    & (Join-Path $PSScriptRoot 'migrate-fresh.ps1')
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    Remove-Item Env:FLYWAY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:FLYWAY_USER -ErrorAction SilentlyContinue
    Remove-Item Env:FLYWAY_URL -ErrorAction SilentlyContinue
    $dbPassword.Dispose()
}
