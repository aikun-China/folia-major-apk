# Folia 图标生成脚本：源图 -> mipmap 各密度（方形+圆形） + 自适应图标
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File d:\CloudMusic\gen_icons.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$srcPath = 'D:\CloudMusic\DM_20260930204342_001.png'
$resDir  = 'D:\CloudMusic\folia-major-apk\app\src\main\res'

$src = [System.Drawing.Bitmap]::FromFile($srcPath)
Write-Output ("SRC: {0}x{1}" -f $src.Width, $src.Height)

# 1) 扫描非空白区域（空白 = alpha<10 或近白 >=245），网格步长 2 采样后四周外扩 2px 修正
$minX = $src.Width; $minY = $src.Height; $maxX = -1; $maxY = -1
for ($y = 0; $y -lt $src.Height; $y += 2) {
    for ($x = 0; $x -lt $src.Width; $x += 2) {
        $c = $src.GetPixel($x, $y)
        if (($c.A -ge 10) -and -not (($c.R -ge 245) -and ($c.G -ge 245) -and ($c.B -ge 245))) {
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}
if ($maxX -ge 0) {
    $minX = [Math]::Max(0, $minX - 2)
    $minY = [Math]::Max(0, $minY - 2)
    $maxX = [Math]::Min($src.Width - 1, $maxX + 2)
    $maxY = [Math]::Min($src.Height - 1, $maxY + 2)
}
if ($maxX -lt 0) { $src.Dispose(); throw 'NO_CONTENT_DETECTED' }
Write-Output ("BBOX: x[{0}..{1}] y[{2}..{3}]" -f $minX, $maxX, $minY, $maxY)

# 2) 裁切为居中正方形
$cw = $maxX - $minX + 1
$ch = $maxY - $minY + 1
$side = [Math]::Max($cw, $ch)
$cx = $minX + [int](($cw - $side) / 2)
$cy = $minY + [int](($ch - $side) / 2)
if ($cx -lt 0) { $cx = 0 }
if ($cy -lt 0) { $cy = 0 }
if ($cx + $side -gt $src.Width)  { $side = $src.Width - $cx }
if ($cy + $side -gt $src.Height) { $side = $src.Height - $cy }
$srcRect = New-Object System.Drawing.Rectangle($cx, $cy, $side, $side)
Write-Output ("CROP: x={0} y={1} side={2}" -f $cx, $cy, $side)

function New-Scaled([System.Drawing.Rectangle]$sRect, [int]$size, [bool]$circle) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $gfx.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gfx.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $gfx.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gfx.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    if ($circle) {
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $p.AddEllipse(0, 0, $size, $size)
        $gfx.SetClip($p)
        $p.Dispose()
    }
    $dst = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $gfx.DrawImage($script:src, $dst, $sRect, [System.Drawing.GraphicsUnit]::Pixel)
    $gfx.Dispose()
    return $bmp
}

function Save-Png([System.Drawing.Bitmap]$bmp, [string]$path) {
    $dir = Split-Path -Parent $path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

# 3) 传统图标：方形 + 圆形
$sizes = [ordered]@{ 'mipmap-mdpi' = 48; 'mipmap-hdpi' = 72; 'mipmap-xhdpi' = 96; 'mipmap-xxhdpi' = 144; 'mipmap-xxxhdpi' = 192 }
foreach ($k in $sizes.Keys) {
    $s = $sizes[$k]
    $sq = New-Scaled $srcRect $s $false
    Save-Png $sq (Join-Path $resDir "$k\ic_launcher.png")
    $sq.Dispose()
    $rd = New-Scaled $srcRect $s $true
    Save-Png $rd (Join-Path $resDir "$k\ic_launcher_round.png")
    $rd.Dispose()
    Write-Output ("ICON: $k ${s}px OK")
}

# 4) 自适应图标 background（432px）：先填充边缘采样色，再整图缩放铺满（圆角缺口用采样色兜底）
$bgSize = 432
$bg = New-Object System.Drawing.Bitmap($bgSize, $bgSize)
$gfx = [System.Drawing.Graphics]::FromImage($bg)
$gfx.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$gfx.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$gfx.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$edge = $src.GetPixel(($cx + [int]($side / 2)), ($cy + [int]($side * 0.02)))
$gfx.Clear($edge)
$dst = New-Object System.Drawing.Rectangle(0, 0, $bgSize, $bgSize)
$gfx.DrawImage($src, $dst, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
$gfx.Dispose()
Save-Png $bg (Join-Path $resDir 'drawable-nodpi\ic_launcher_background.png')
$bg.Dispose()
Write-Output ("ADAPTIVE_BG: ${bgSize}px OK (edge=#{0:X2}{1:X2}{2:X2})" -f $edge.R, $edge.G, $edge.B)

# 5) 自适应图标 foreground（432px 透明，视觉层由 background 承载）
$fg = New-Object System.Drawing.Bitmap($bgSize, $bgSize)
Save-Png $fg (Join-Path $resDir 'drawable-nodpi\ic_launcher_foreground.png')
$fg.Dispose()
Write-Output "ADAPTIVE_FG: ${bgSize}px transparent OK"

$src.Dispose()
Write-Output 'ALL_ICONS_DONE'
