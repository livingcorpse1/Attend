# Soaks the connection pool against the managed pooler.
#
# The pooler closes idle connections. The application has to survive that, so this
# script leaves the server alone for a while and then checks that the database is
# still reachable and that a sign in still works.
param(
    [int]$Minutes = 10,
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$deadline = (Get-Date).AddMinutes($Minutes)
$checks = 0
$failures = 0

function Test-Database {
    try {
        $response = Invoke-WebRequest -Uri "$BaseUrl/api/health" -UseBasicParsing -TimeoutSec 30
        $payload = $response.Content | ConvertFrom-Json
        return $payload.database.status -eq 'UP'
    } catch {
        Write-Host "  health call failed: $($_.Exception.Message)" -ForegroundColor Red
        return $false
    }
}

function Test-Login {
    try {
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $bootstrap = (Invoke-WebRequest -Uri "$BaseUrl/api/auth/session" -WebSession $session -UseBasicParsing -TimeoutSec 30).Content | ConvertFrom-Json
        $headers = @{ 'X-XSRF-TOKEN' = $bootstrap.csrfToken }
        $login = Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -WebSession $session -UseBasicParsing -Headers $headers -TimeoutSec 30 -ContentType 'application/json' -Body '{"email":"faculty@veriqra.local","password":"FacultyDemo!26"}'
        return $login.StatusCode -eq 200
    } catch {
        Write-Host "  login failed: $($_.Exception.Message)" -ForegroundColor Red
        return $false
    }
}

Write-Host "Soaking for $Minutes minute(s) against $BaseUrl" -ForegroundColor Cyan
$start = Get-Date

while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 60
    $checks += 1
    $ok = Test-Database
    if ($ok) { $ok = Test-Login }
    $elapsed = [int]((Get-Date) - $start).TotalMinutes
    if ($ok) {
        Write-Host "  minute $elapsed  ok" -ForegroundColor Green
    } else {
        $failures += 1
        Write-Host "  minute $elapsed  FAILED" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "$checks check(s), $failures failure(s)" -ForegroundColor Cyan
exit $(if ($failures -eq 0) { 0 } else { 1 })
