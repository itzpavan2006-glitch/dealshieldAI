# Provision only the restricted runtime LOGIN and launch the backend in this terminal.
$ErrorActionPreference = 'Stop'
$certificatePath = (Read-Host 'Full path to the Supabase root certificate').Trim().Trim('"')
if (-not (Test-Path -LiteralPath $certificatePath -PathType Leaf)) { throw 'Certificate file not found.' }
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules\pg') -PathType Container)) { throw 'The local pg setup dependency is missing.' }
Write-Host 'Enter credentials privately; do not send them in chat.'
$adminSecure = Read-Host 'Supabase database administrator password' -AsSecureString
$runtimeSecure = Read-Host 'Choose a new strong password for the restricted runtime login' -AsSecureString
if ($runtimeSecure.Length -lt 16) { $adminSecure.Dispose(); $runtimeSecure.Dispose(); throw 'Use a runtime password of at least 16 characters.' }
$adminPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($adminSecure)
$runtimePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($runtimeSecure)
try {
    $env:DEALSHIELD_CA_FILE = $certificatePath
    $env:DEALSHIELD_ADMIN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($adminPointer)
    $env:DEALSHIELD_RUNTIME_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($runtimePointer)
    & node (Join-Path $PSScriptRoot 'create-runtime-role.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime role setup did not complete. Do not rerun until the role state is checked.' }
    Remove-Item Env:DEALSHIELD_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($adminPointer); $adminPointer = [IntPtr]::Zero
    $env:DEALSHIELD_DB_URL = "jdbc:postgresql://aws-1-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=$([Uri]::EscapeDataString($certificatePath))"
    $env:DEALSHIELD_DB_USER = 'dealshield_runtime.umdmxoegwccerfzhpruc'
    $env:DEALSHIELD_DB_PASSWORD = $env:DEALSHIELD_RUNTIME_PASSWORD
    Remove-Item Env:DEALSHIELD_RUNTIME_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($runtimePointer); $runtimePointer = [IntPtr]::Zero
    $env:SPRING_PROFILES_ACTIVE = 'database'
    $env:PORT = '18180'
    Write-Host 'Starting the backend on port 18180. Keep this window open; press Ctrl+C to stop it.'
    Push-Location (Join-Path $PSScriptRoot '..\backend')
    try { & .\mvnw.cmd -B spring-boot:run; if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed.' } } finally { Pop-Location }
} finally {
    if ($adminPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($adminPointer) }
    if ($runtimePointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($runtimePointer) }
    $adminSecure.Dispose(); $runtimeSecure.Dispose()
    foreach ($name in @('DEALSHIELD_CA_FILE','DEALSHIELD_ADMIN_PASSWORD','DEALSHIELD_RUNTIME_PASSWORD','DEALSHIELD_DB_URL','DEALSHIELD_DB_USER','DEALSHIELD_DB_PASSWORD','SPRING_PROFILES_ACTIVE','PORT')) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
}
