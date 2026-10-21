<#
.SYNOPSIS
    Generates the PWA icons for VeriQra.

.DESCRIPTION
    Draws the application mark (a rounded gradient tile with a scan frame and a check
    mark) at every size the manifest needs. Run this script after changing the brand
    colour so the icons stay in sync with the stylesheet.
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'src\main\resources\static\icons')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function New-BrandIcon {
    param([int]$Size, [bool]$Maskable = $false)

    $bitmap = New-Object System.Drawing.Bitmap($Size, $Size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.Clear([System.Drawing.Color]::Transparent)

    # Background gradient: deep navy into indigo.
    $top = [System.Drawing.Color]::FromArgb(255, 17, 27, 44)
    $bottom = [System.Drawing.Color]::FromArgb(255, 60, 74, 140)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Point(0, 0)),
        (New-Object System.Drawing.Point($Size, $Size)),
        $top, $bottom)
    $graphics.FillRectangle($brush, 0, 0, $Size, $Size)
    $brush.Dispose()

    # A teal glow in the upper right corner.
    $glow = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Point([int]($Size * 0.45), 0)),
        (New-Object System.Drawing.Point($Size, [int]($Size * 0.6))),
        [System.Drawing.Color]::FromArgb(120, 51, 200, 206),
        [System.Drawing.Color]::FromArgb(0, 51, 200, 206))
    $graphics.FillRectangle($glow, 0, 0, $Size, $Size)
    $glow.Dispose()

    # Scan frame corners.
    $unit = $Size / 100.0
    $padding = if ($Maskable) { 26 * $unit } else { 18 * $unit }
    $span = $Size - 2 * $padding
    $arm = $span * 0.34
    $thickness = [Math]::Max(2.0, $span * 0.085)
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 255, 255, 255), $thickness)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round

    $left = $padding
    $right = $padding + $span
    $topY = $padding
    $bottomY = $padding + $span

    $graphics.DrawLine($pen, $left, $topY + $arm, $left, $topY)
    $graphics.DrawLine($pen, $left, $topY, $left + $arm, $topY)
    $graphics.DrawLine($pen, $right - $arm, $topY, $right, $topY)
    $graphics.DrawLine($pen, $right, $topY, $right, $topY + $arm)
    $graphics.DrawLine($pen, $left, $bottomY - $arm, $left, $bottomY)
    $graphics.DrawLine($pen, $left, $bottomY, $left + $arm, $bottomY)
    $graphics.Dispose()

    # Check mark.
    $checkPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 87, 214, 202), $thickness * 1.35)
    $checkPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $checkPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.DrawLines($checkPen, @(
        (New-Object System.Drawing.PointF([int]($Size * 0.36), [int]($Size * 0.52))),
        (New-Object System.Drawing.PointF([int]($Size * 0.46), [int]($Size * 0.63))),
        (New-Object System.Drawing.PointF([int]($Size * 0.66), [int]($Size * 0.38)))
    ))
    $graphics.Dispose()
    $pen.Dispose()
    $checkPen.Dispose()

    return $bitmap
}

$targets = @(
    @{ Name = 'icon-192.png'; Size = 192; Maskable = $false },
    @{ Name = 'icon-512.png'; Size = 512; Maskable = $false },
    @{ Name = 'icon-maskable-512.png'; Size = 512; Maskable = $true }
)

foreach ($target in $targets) {
    $bitmap = New-BrandIcon -Size $target.Size -Maskable $target.Maskable
    $path = Join-Path $OutputDirectory $target.Name
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    Write-Host "OK    $($target.Name) ($($target.Size)x$($target.Size))" -ForegroundColor Green
}

Write-Host ""
Write-Host "Icons written to $OutputDirectory" -ForegroundColor Cyan
