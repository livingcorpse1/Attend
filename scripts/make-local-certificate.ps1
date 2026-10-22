<#
.SYNOPSIS
    Creates the key store and the certificate for the local HTTPS profile.

.DESCRIPTION
    A browser only hands a page the camera and the service worker when the origin is
    secure, and "localhost" is the only insecure origin it allows. Opening the
    application on a phone over the network therefore needs HTTPS.

    This script creates a self signed key store that covers the addresses of this
    machine, and exports the certificate so it can be installed on the phone.

    The certificate carries the flag of a certificate authority, so the phone can hold
    it as a trusted certificate. Without that flag the browser keeps the origin
    untrusted even after the certificate is installed, and it then hands no camera to
    the page.

    The key store is a development convenience. It is not in source control and it is
    not a substitute for a certificate from a real authority.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\make-local-certificate.ps1
#>
[CmdletBinding()]
param(
    [string[]]$Address = @(),
    [string]$StorePassword = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$keytool = (Get-ChildItem 'C:\Program Files\Java' -Recurse -Filter 'keytool.exe' -ErrorAction SilentlyContinue |
    Select-Object -First 1).FullName
if (-not $keytool) {
    throw 'keytool was not found. Install a JDK or add one to the path.'
}

if (-not $Address -or $Address.Count -eq 0) {
    # Every address of the machine is taken, because the network hands them out by
    # lease and the phone may be on any of them. The certificate is made again when
    # the address changes, and then the phone has to take the new one.
    $Address = @(Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        Where-Object { $_.PrefixOrigin -eq 'Dhcp' -or $_.InterfaceAlias -notlike '*Ethernet 2*' } |
        Select-Object -ExpandProperty IPAddress)
}
if (-not $Address -or $Address.Count -eq 0) {
    throw 'No network address was found. Pass one with -Address.'
}

if (-not $StorePassword) {
    $StorePassword = 'veriqra-local-' + (Get-Random -Minimum 10000 -Maximum 99999)
}

$configDirectory = Join-Path $root 'config'
New-Item -ItemType Directory -Force -Path $configDirectory | Out-Null
$store = Join-Path $configDirectory 'veriqra.p12'
$certificate = Join-Path $configDirectory 'veriqra-certificate.cer'

if (Test-Path -LiteralPath $store) {
    Remove-Item -LiteralPath $store -Force
}

# Every extension is given on its own, because the tool reads one extension per flag.
# The tool writes its progress to the error stream, so the error stream is put aside for
# the call and the result is read from the exit code.
function Invoke-Tool {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $keytool @Arguments 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        throw "The tool failed: $($output -join ' ')"
    }
    return $output
}

# Every address the machine has is covered, so the phone reaches the site on any of
# them.
$subjectAltNames = (@($Address | ForEach-Object { "IP:$_" }) + @('DNS:localhost', 'IP:127.0.0.1')) -join ','

Invoke-Tool @(
    '-genkeypair', '-alias', 'veriqra', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '825',
    '-storetype', 'PKCS12', '-keystore', $store, '-storepass', $StorePassword, '-keypass', $StorePassword,
    '-dname', 'CN=veriqra.local, OU=Attendance, O=VeriQra, L=Thane, ST=Maharashtra, C=IN',
    '-ext', 'BC=ca:true',
    '-ext', 'KU=digitalSignature,keyEncipherment,keyCertSign,cRLSign',
    '-ext', 'EKU=serverAuth',
    '-ext', "SAN=$subjectAltNames"
) | Out-Null

Invoke-Tool @(
    '-exportcert', '-alias', 'veriqra', '-keystore', $store, '-storepass', $StorePassword,
    '-rfc', '-file', $certificate
) | Out-Null

# The password is not in source control. It is written next to the key store and read
# from the environment when the application starts.
$passwordFile = Join-Path $configDirectory 'store-pass.txt'
Set-Content -LiteralPath $passwordFile -Value $StorePassword -NoNewline

$envFile = Join-Path $root '.env'
if (Test-Path -LiteralPath $envFile) {
    $content = Get-Content -LiteralPath $envFile -Raw
    if ($content -notmatch 'SSL_KEY_STORE_PASSWORD') {
        $addition = "`r`n# --- Local HTTPS -------------------------------------------------------`r`n" +
            "SSL_KEY_STORE=file:./config/veriqra.p12`r`n" +
            "SSL_KEY_STORE_PASSWORD=$StorePassword`r`n" +
            "SSL_KEY_ALIAS=veriqra`r`n" +
            "HTTPS_PORT=8443`r`n"
        Set-Content -LiteralPath $envFile -Value ($content.TrimEnd() + "`r`n" + $addition) -NoNewline
    }
}

Write-Host ''
Write-Host 'The key store and the certificate are ready.' -ForegroundColor Green
foreach ($one in $Address) {
    Write-Host "  address   https://$one`:8443"
}
Write-Host "  key store $store"
Write-Host "  install   $certificate  (copy this to the phone)"
Write-Host ''
Write-Host 'The addresses are handed out by lease. When they change, run this again and' -ForegroundColor DarkGray
Write-Host 'take the new certificate on the phone.' -ForegroundColor DarkGray
Write-Host ''
Write-Host 'Start the application with:' -ForegroundColor Cyan
Write-Host "  .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=https" -ForegroundColor Cyan
