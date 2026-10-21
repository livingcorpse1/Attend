<#
.SYNOPSIS
    Downloads the ONNX models used by the VeriQra biometric pipeline.

.DESCRIPTION
    Two models are required and are NOT checked into source control because of
    their binary size and their upstream licence terms:

      * face_detection_yunet_2023mar.onnx   - face detection + 5 point landmarks
      * face_recognition_sface_2021dec.onnx - 128 dimensional face embedding

    Both are published by the OpenCV model zoo, which is distributed under the
    Apache-2.0 licence. The script verifies the SHA-256 of each download so a
    corrupted or substituted file cannot silently reach the inference pipeline.

.PARAMETER Directory
    Target directory. Defaults to the 'models' folder next to this script.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1
#>
[CmdletBinding()]
param(
    [string]$Directory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'models')
)

$ErrorActionPreference = 'Stop'

$models = @(
    @{
        Name   = 'face_detection_yunet_2023mar.onnx'
        Url    = 'https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx'
        Sha256 = '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'
    },
    @{
        Name   = 'face_recognition_sface_2021dec.onnx'
        Url    = 'https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx'
        Sha256 = '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79'
    }
)

New-Item -ItemType Directory -Force -Path $Directory | Out-Null
$ProgressPreference = 'SilentlyContinue'

foreach ($model in $models) {
    $target = Join-Path $Directory $model.Name
    if (Test-Path -LiteralPath $target) {
        $existing = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower()
        if ($existing -eq $model.Sha256) {
            Write-Host "SKIP  $($model.Name) already present and verified" -ForegroundColor DarkGray
            continue
        }
        Write-Host "STALE $($model.Name) checksum mismatch, re-downloading" -ForegroundColor Yellow
        Remove-Item -LiteralPath $target -Force
    }

    Write-Host "GET   $($model.Name)"
    $temp = "$target.download"
    try {
        Invoke-WebRequest -Uri $model.Url -OutFile $temp -UseBasicParsing -TimeoutSec 600
        $actual = (Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLower()
        if ($actual -ne $model.Sha256) {
            throw "checksum mismatch for $($model.Name): expected $($model.Sha256) got $actual"
        }
        Move-Item -LiteralPath $temp -Destination $target -Force
        $size = (Get-Item -LiteralPath $target).Length
        Write-Host "OK    $($model.Name) ($([math]::Round($size / 1MB, 2)) MB)" -ForegroundColor Green
    }
    catch {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
        Write-Host "FAIL  $($model.Name): $($_.Exception.Message)" -ForegroundColor Red
        throw
    }
}

Write-Host ""
Write-Host "Models ready in $Directory" -ForegroundColor Cyan
Write-Host "Set VERIQRA_MODELS_DIR if you want to keep them elsewhere."
