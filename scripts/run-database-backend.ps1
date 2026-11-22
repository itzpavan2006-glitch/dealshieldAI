# Start the backend after the restricted runtime login has already been created.
$ErrorActionPreference = 'Stop'
$certificatePath = (Read-Host 'Full path to the Supabase root certificate').Trim().Trim('"')
if (-not (Test-Path -LiteralPath $certificatePath -PathType Leaf)) { throw 'Certificate file not found.' }
$runtimeSecure = Read-Host 'Restricted runtime login password' -AsSecureString
$runtimePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($runtimeSecure)
$publishableSecure = Read-Host 'Supabase publishable key for dealshield-ai-fresh' -AsSecureString
$publishablePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($publishableSecure)
try {
    $env:DEALSHIELD_DB_URL = "jdbc:postgresql://aws-1-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=$([Uri]::EscapeDataString($certificatePath))"
    $env:DEALSHIELD_DB_USER = 'dealshield_runtime.umdmxoegwccerfzhpruc'
    $env:DEALSHIELD_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($runtimePointer)
    $env:DEALSHIELD_AUTH_PROJECT_URL = 'https://umdmxoegwccerfzhpruc.supabase.co'
    $env:DEALSHIELD_AUTH_PUBLISHABLE_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($publishablePointer)
    $env:DEALSHIELD_AUTH_CALLBACK_URL = 'http://localhost:5173/api/v1/auth/callback'
    $env:DEALSHIELD_AUTH_RECOVERY_CALLBACK_URL = 'http://localhost:5173/api/v1/auth/recovery'
    $env:DEALSHIELD_COOKIE_SECURE = 'false'
    $env:SPRING_PROFILES_ACTIVE = 'database'
    $env:PORT = '18180'
    Write-Host 'Compiling the current backend sources before startup.'
    Push-Location (Join-Path $PSScriptRoot '..\backend')
    try {
        & .\mvnw.cmd -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Backend compilation failed.' }
        Write-Host 'Starting the backend on port 18180. Keep this window open; press Ctrl+C to stop it.'
        & .\mvnw.cmd -B spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed.' }
    } finally { Pop-Location }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($runtimePointer); $runtimeSecure.Dispose()
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($publishablePointer); $publishableSecure.Dispose()
    foreach ($name in @('DEALSHIELD_DB_URL','DEALSHIELD_DB_USER','DEALSHIELD_DB_PASSWORD','DEALSHIELD_AUTH_PROJECT_URL','DEALSHIELD_AUTH_PUBLISHABLE_KEY','DEALSHIELD_AUTH_CALLBACK_URL','DEALSHIELD_AUTH_RECOVERY_CALLBACK_URL','DEALSHIELD_COOKIE_SECURE','SPRING_PROFILES_ACTIVE','PORT')) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
}
