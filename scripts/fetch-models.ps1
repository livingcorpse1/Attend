<#
.SYNOPSIS
    Downloads the ONNX models used by the VeriQra biometric pipeline.

.DESCRIPTION
    Two pairs of models are supported and neither is checked into source control,
    because of their size and their licence terms.

    The permissive pair, which is the default:

      * face_detection_yunet_2026may.onnx   - face detection + 5 point landmarks
      * face_recognition_sface_2021dec.onnx - 128 dimensional face embedding

    Both are published by the OpenCV model zoo. The detection model is exported
    under the MIT licence and the recognition model under the Apache-2.0 licence, so
    they may be used for anything, including a commercial deployment.

    The strongest pair, which is installed with -Profile Strong:

      * scrfd_10g.onnx           - face detection + 5 point landmarks
      * arcface_w600k_r50.onnx   - 512 dimensional face embedding

    Both come from the InsightFace model family. The code of that project is
    published under the MIT licence, but the weights are released for non commercial
    research. A deployment that is not a college project has to settle that question
    before it uses them.

    The script verifies the SHA-256 of every download, so a corrupted or substituted
    file cannot silently reach the inference pipeline.

.PARAMETER Directory
    Target directory. Defaults to the 'models' folder next to this script.

.PARAMETER Profile
    Which pair to install. 'Default' installs the permissive pair, 'Strong' installs
    the pair of the InsightFace family.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\fetch-models.ps1 -Profile Strong
#>
[CmdletBinding()]
param(
    [string]$Directory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'models'),
    [ValidateSet('Default', 'Strong')]
    [string]$Profile = 'Default'
)

$ErrorActionPreference = 'Stop'

$permissive = @(
    @{
        Name   = 'face_detection_yunet_2026may.onnx'
        Url    = 'https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2026may.onnx'
        Sha256 = 'ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0'
    },
    @{
        Name   = 'face_recognition_sface_2021dec.onnx'
        Url    = 'https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx'
        Sha256 = '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79'
    }
)

$strong = @(
    @{
        Name   = 'scrfd_10g.onnx'
        Url    = 'https://huggingface.co/globalnebula/insightface-buffalo-l-onnx/resolve/main/scrfd_10g.onnx'
        Sha256 = '5838f7fe053675b1c7a08b633df49e7af5495cee0493c7dcf6697200b85b5b91'
    },
    @{
        Name   = 'arcface_w600k_r50.onnx'
        Url    = 'https://huggingface.co/globalnebula/insightface-buffalo-l-onnx/resolve/main/arcface_w600k_r50.onnx'
        Sha256 = '4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43'
    }
)

if ($Profile -eq 'Strong') {
    $models = $strong
    Write-Host ''
    Write-Host 'The models of the InsightFace family are published for non commercial research.' -ForegroundColor Yellow
    Write-Host 'A deployment that is not a college project has to settle that question first.' -ForegroundColor Yellow
    Write-Host ''
} else {
    $models = $permissive
}

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
        Invoke-WebRequest -Uri $model.Url -OutFile $temp -UseBasicParsing -TimeoutSec 1800
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
if ($Profile -eq 'Strong') {
    Write-Host "Set BIOMETRIC_PROFILE=scrfd-arcface in the .env file to use them." -ForegroundColor Cyan
    Write-Host "Students have to run the identity check again, because the descriptors changed." -ForegroundColor DarkGray
} else {
    Write-Host "Set VERIQRA_MODELS_DIR if you want to keep them elsewhere." -ForegroundColor Cyan
}
