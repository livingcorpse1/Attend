<#
.SYNOPSIS
    Starts VeriQra on the local network with HTTPS.

.DESCRIPTION
    A browser only hands a page the camera and the service worker when the origin is
    secure, and "localhost" is the only insecure origin it allows. A phone on the same
    Wi-Fi therefore needs HTTPS, and this script starts the application with the
    profile that serves it.

    The key store is created by scripts\make-local-certificate.ps1.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File run-lan.ps1
#>
[CmdletBinding()]
param(
    [string]$Address = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $Address) {
    $Address = (Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        Where-Object { $_.PrefixOrigin -eq 'Dhcp' } |
        Select-Object -First 1 -ExpandProperty IPAddress)
}

$store = Join-Path $root 'config\veriqra.p12'
$certificate = Join-Path $root 'config\veriqra-certificate.cer'

if (-not (Test-Path -LiteralPath $store)) {
    Write-Host 'The key store is missing. Creating it now.' -ForegroundColor Yellow
    & (Join-Path $root 'scripts\make-local-certificate.ps1') -Address $Address
}

Write-Host ''
Write-Host "  application  https://$Address`:8443" -ForegroundColor Green
Write-Host "  certificate  $certificate" -ForegroundColor Green
Write-Host "  install it on the phone, otherwise the browser will block the camera." -ForegroundColor DarkGray
Write-Host ''

& (Join-Path $root 'mvnw.cmd') spring-boot:run '-Dspring-boot.run.profiles=https'
