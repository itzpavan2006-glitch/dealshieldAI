$ErrorActionPreference = 'Stop'
Write-Host 'Target: dealshield-ai-fresh (umdmxoegwccerfzhpruc). Only dealshield_runtime will be altered.'
$certificatePath = (Read-Host 'Full path to the Supabase root certificate').Trim().Trim('"')
if (-not (Test-Path -LiteralPath $certificatePath -PathType Leaf)) { throw 'Certificate file not found.' }
$adminSecure = Read-Host 'Supabase database administrator password (typing is hidden)' -AsSecureString
$runtimeSecure = Read-Host 'Choose a new dealshield_runtime password, at least 16 characters (typing is hidden)' -AsSecureString
$confirmSecure = Read-Host 'Confirm the new dealshield_runtime password (typing is hidden)' -AsSecureString
if ($adminSecure.Length -eq 0) { $adminSecure.Dispose(); $runtimeSecure.Dispose(); $confirmSecure.Dispose(); throw 'Administrator password was not entered.' }
if ($runtimeSecure.Length -lt 16) { $adminSecure.Dispose(); $runtimeSecure.Dispose(); $confirmSecure.Dispose(); throw 'The runtime password must contain at least 16 characters.' }
$adminPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($adminSecure)
$runtimePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($runtimeSecure)
$confirmPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirmSecure)
try {
    $runtimePlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($runtimePointer)
    $confirmPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($confirmPointer)
    if ($runtimePlain -cne $confirmPlain) { throw 'The two new runtime passwords do not match.' }
    $confirmPlain = $null
    $env:DEALSHIELD_ROTATE_CERT = (Resolve-Path -LiteralPath $certificatePath).Path
    $env:DEALSHIELD_ROTATE_ADMIN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($adminPointer)
    $env:DEALSHIELD_ROTATE_RUNTIME_PASSWORD = $runtimePlain
    $runtimePlain = $null
    & node (Join-Path $PSScriptRoot 'rotate-runtime-password.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'Rotation or rollback-only verification failed.' }

    $env:DEALSHIELD_DB_URL = "jdbc:postgresql://aws-1-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=$([Uri]::EscapeDataString((Resolve-Path -LiteralPath $certificatePath).Path))"
    $env:DEALSHIELD_DB_USER = 'dealshield_runtime.umdmxoegwccerfzhpruc'
    $env:DEALSHIELD_DB_PASSWORD = $env:DEALSHIELD_ROTATE_RUNTIME_PASSWORD
    $env:SPRING_PROFILES_ACTIVE = 'database'
    $env:DEALSHIELD_LIVE_RUNTIME_VERIFY = 'true'
    Push-Location (Join-Path $PSScriptRoot '..\backend')
    try {
        & .\mvnw.cmd -B '-Dtest=LiveRuntimeConnectionTest' test
        if ($LASTEXITCODE -ne 0) { throw 'Spring Boot runtime connection verification failed.' }
    } finally { Pop-Location }
} finally {
    foreach ($name in @('DEALSHIELD_ROTATE_CERT','DEALSHIELD_ROTATE_ADMIN_PASSWORD','DEALSHIELD_ROTATE_RUNTIME_PASSWORD','DEALSHIELD_DB_URL','DEALSHIELD_DB_USER','DEALSHIELD_DB_PASSWORD','SPRING_PROFILES_ACTIVE','DEALSHIELD_LIVE_RUNTIME_VERIFY')) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($adminPointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($runtimePointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($confirmPointer)
    $adminSecure.Dispose(); $runtimeSecure.Dispose(); $confirmSecure.Dispose()
}
