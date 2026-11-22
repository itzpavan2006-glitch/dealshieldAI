$ErrorActionPreference = 'Stop'
$certificatePath = (Read-Host 'Full path to the Supabase root certificate').Trim().Trim('"')
if (-not (Test-Path -LiteralPath $certificatePath -PathType Leaf)) { throw 'Certificate file not found.' }
$runtimePassword = Read-Host 'Restricted runtime login password (typing is hidden)' -AsSecureString
if ($runtimePassword.Length -eq 0) {
    $runtimePassword.Dispose()
    throw 'No runtime password was entered. The verification was not started.'
}
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($runtimePassword)
try {
    $env:DEALSHIELD_VERIFY_HOST = 'aws-1-ap-south-1.pooler.supabase.com'
    $env:DEALSHIELD_VERIFY_CERT = (Resolve-Path -LiteralPath $certificatePath).Path
    $env:DEALSHIELD_VERIFY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    & node (Join-Path $PSScriptRoot 'verify-v2-runtime.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime verification failed.' }

    $env:DEALSHIELD_DB_URL = "jdbc:postgresql://aws-1-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=$([Uri]::EscapeDataString((Resolve-Path -LiteralPath $certificatePath).Path))"
    $env:DEALSHIELD_DB_USER = 'dealshield_runtime.umdmxoegwccerfzhpruc'
    $env:DEALSHIELD_DB_PASSWORD = $env:DEALSHIELD_VERIFY_PASSWORD
    $env:SPRING_PROFILES_ACTIVE = 'database'
    $env:DEALSHIELD_LIVE_RUNTIME_VERIFY = 'true'
    Push-Location (Join-Path $PSScriptRoot '..\backend')
    try {
        & .\mvnw.cmd -B '-Dtest=LiveRuntimeConnectionTest' test
        if ($LASTEXITCODE -ne 0) { throw 'Spring Boot runtime connection verification failed.' }
    } finally { Pop-Location }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $runtimePassword.Dispose()
    foreach ($name in @('DEALSHIELD_VERIFY_HOST','DEALSHIELD_VERIFY_CERT','DEALSHIELD_VERIFY_PASSWORD','DEALSHIELD_DB_URL','DEALSHIELD_DB_USER','DEALSHIELD_DB_PASSWORD','SPRING_PROFILES_ACTIVE','DEALSHIELD_LIVE_RUNTIME_VERIFY')) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
}
