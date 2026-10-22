<#
.SYNOPSIS
    Downloads the Geist fonts and keeps them in the project.

.DESCRIPTION
    The interface is built on the Geist type family. The fonts are served from this
    origin and not from a font service, so the content security policy can forbid
    every third party origin and the application works without a network.

    The fonts are published by Vercel under the SIL Open Font Licence 1.1.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\fetch-fonts.ps1
#>
[CmdletBinding()]
param(
    [string]$Directory = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Directory) {
    $Directory = Join-Path $root 'src\main\resources\static\fonts'
}
New-Item -ItemType Directory -Force -Path $Directory | Out-Null

# The service is asked for the files as a browser, so it answers with the woff2 ones.
$headers = @{
    'User-Agent' = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'
}

$families = @(
    @{ Name = 'Geist'; Weights = @(400, 500, 600, 700) },
    @{ Name = 'Geist Mono'; Weights = @(400, 500) }
)

$ProgressPreference = 'SilentlyContinue'
$faces = New-Object System.Collections.Generic.List[string]

foreach ($family in $families) {
    # The weight list keeps its punctuation, only the space of the family name is
    # escaped, because the service does not read an escaped colon.
    $name = $family.Name.Replace(' ', '%20')
    $weights = 'wght@' + ($family.Weights -join ';')
    $url = "https://fonts.googleapis.com/css2?family=$name`:$weights&display=swap"
    Write-Host "  the address is $url" -ForegroundColor DarkGray
    $style = (Invoke-WebRequest -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 60).Content

    # The answer holds one block per subset. Only the latin ones are kept, they cover
    # the interface and they keep the size of the package small.
    $blocks = $style -split '(?=\/\* [a-z-]+ \*\/)'
    $file = $null
    $familyName = $null
    foreach ($block in $blocks) {
        if ($block -notmatch '/\* latin \*/') {
            continue
        }
        if ($block -notmatch "font-family: '([^']+)'") { continue }
        $familyName = $Matches[1]
        if ($block -notmatch 'src: url\((https://[^)]+\.woff2)\)') { continue }
        $file = $Matches[1]
        break
    }
    if (-not $file) {
        throw "The font $($family.Name) was not found in the answer of the service."
    }

    # The files are the variable ones, so one file carries every weight.
    $name = ($familyName -replace '\s', '') + '-Variable.woff2'
    $target = Join-Path $Directory $name
    if (-not (Test-Path -LiteralPath $target)) {
        Invoke-WebRequest -Uri $file -OutFile $target -Headers $headers -UseBasicParsing -TimeoutSec 120
        Write-Host "GET   $name" -ForegroundColor DarkGray
    }
    else {
        Write-Host "SKIP  $name" -ForegroundColor DarkGray
    }

    $faces.Add(@"
@font-face {
  font-family: '$familyName';
  font-style: normal;
  font-weight: 100 900;
  font-display: swap;
  src: url('/fonts/$name') format('woff2');
}
"@)
}
# The rules are written into the token file, so the fonts travel with the design.
$tokens = Join-Path $root 'src\main\resources\static\css\tokens.css'
$content = Get-Content -LiteralPath $tokens -Raw
if ($content -notmatch '@font-face') {
    $header = "/* The type family, served from this origin. See scripts/fetch-fonts.ps1. */`r`n"
    $header += ($faces -join "`r`n") + "`r`n`r`n"
    [System.IO.File]::WriteAllText($tokens, $header + $content, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'The rules of the faces were written into tokens.css' -ForegroundColor Green
}

Write-Host ''
Write-Host "The fonts are ready in $Directory" -ForegroundColor Cyan
