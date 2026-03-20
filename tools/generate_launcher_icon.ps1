Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = "Stop"

function New-RoundedRectPath {
    param(
        [float]$X,
        [float]$Y,
        [float]$Width,
        [float]$Height,
        [float]$Radius
    )
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $diameter = $Radius * 2
    $path.AddArc($X, $Y, $diameter, $diameter, 180, 90)
    $path.AddArc($X + $Width - $diameter, $Y, $diameter, $diameter, 270, 90)
    $path.AddArc($X + $Width - $diameter, $Y + $Height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($X, $Y + $Height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

function Draw-LauncherSymbol {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$CanvasSize,
        [bool]$WithBackground
    )

    $s = [single]$CanvasSize

    $Graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $Graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $Graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

    if ($WithBackground) {
        $rect = [System.Drawing.RectangleF]::new($s * 0.02, $s * 0.02, $s * 0.96, $s * 0.96)
        $path = New-RoundedRectPath -X $rect.X -Y $rect.Y -Width $rect.Width -Height $rect.Height -Radius ($s * 0.16)
        $brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 236, 79, 113))
        $Graphics.FillPath($brush, $path)
        $brush.Dispose()
        $path.Dispose()
    }

    $noteColor = [System.Drawing.Color]::White
    $noteBrush = [System.Drawing.SolidBrush]::new($noteColor)
    $notePen = New-Object System.Drawing.Pen($noteColor, ($s * 0.075))
    $notePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $notePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round

    $margin = $s * 0.34
    $contentLeft = $margin
    $contentTop = $margin * 0.9
    $contentRight = $s - $margin
    $contentBottom = $s - $margin * 0.85
    $contentWidth = $contentRight - $contentLeft
    $contentHeight = $contentBottom - $contentTop

    $stemTop = $contentTop + ($contentHeight * 0.05)
    $stemBottom = $contentTop + ($contentHeight * 0.78)

    $stemX = $contentLeft + ($contentWidth * 0.55)
    $flagEndX = $contentLeft + ($contentWidth * 0.86)
    $flagEndY = $stemTop + ($contentHeight * 0.14)
    $Graphics.DrawLine($notePen, $stemX, $stemTop, $stemX, $stemBottom)
    $Graphics.DrawLine($notePen, $stemX, $stemTop, $flagEndX, $flagEndY)

    $headSize = $contentWidth * 0.28
    $headHeight = $headSize * 0.82
    $Graphics.FillEllipse($noteBrush, $contentLeft + ($contentWidth * 0.10), $stemBottom - ($headHeight * 0.65), $headSize, $headHeight)
    $Graphics.FillEllipse($noteBrush, $contentLeft + ($contentWidth * 0.52), $stemBottom - ($headHeight * 0.35), $headSize, $headHeight)

    $notePen.Dispose()
    $noteBrush.Dispose()
}

function Save-Png {
    param(
        [string]$Path,
        [System.Drawing.Bitmap]$Bitmap
    )
    $dir = Split-Path $Path -Parent
    if (!(Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir | Out-Null
    }
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

$root = Split-Path -Parent $PSScriptRoot

$legacyMaster = New-Object System.Drawing.Bitmap 1024, 1024
$legacyGraphics = [System.Drawing.Graphics]::FromImage($legacyMaster)
$legacyGraphics.Clear([System.Drawing.Color]::Transparent)
Draw-LauncherSymbol -Graphics $legacyGraphics -CanvasSize 1024 -WithBackground $true
$legacyGraphics.Dispose()

$sizes = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}

foreach ($entry in $sizes.GetEnumerator()) {
    $targetDir = Join-Path $root "app\src\main\res\$($entry.Key)"
    if (!(Test-Path $targetDir)) {
        New-Item -ItemType Directory -Path $targetDir | Out-Null
    }

    $icon = New-Object System.Drawing.Bitmap $entry.Value, $entry.Value
    $g = [System.Drawing.Graphics]::FromImage($icon)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.DrawImage($legacyMaster, 0, 0, $entry.Value, $entry.Value)
    $g.Dispose()

    Save-Png -Path (Join-Path $targetDir "ic_launcher.png") -Bitmap $icon
    Save-Png -Path (Join-Path $targetDir "ic_launcher_round.png") -Bitmap $icon
    $icon.Dispose()
}

$legacyMaster.Dispose()

Write-Output "Launcher icon assets generated."
