[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$SkipBuild,
    [switch]$Launch
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradle = Join-Path $repoRoot 'gradlew.bat'
$apkRoot = Join-Path $repoRoot 'app\build\outputs\apk'
$packageName = 'io.github.garrymann.rsm'

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw 'adb was not found on PATH. Install Android SDK platform-tools or add them to PATH.'
}

if (-not (Test-Path $gradle)) {
    throw "Gradle wrapper not found: $gradle"
}

Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        Write-Host 'Building Play debug APK...' -ForegroundColor Cyan
        & $gradle ':app:assemblePlayDebug' '--console=plain' '--no-daemon'
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE."
        }
    }

    $apk = Get-ChildItem -Path $apkRoot -Recurse -Filter '*-play-debug.apk' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $apk) {
        throw "Play debug APK was not found below $apkRoot. Run without -SkipBuild."
    }

    $deviceLines = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^\S+\s+device\s*$' })
    if ($Serial) {
        $device = $deviceLines | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device\s*$" }
        if (-not $device) {
            throw "ADB device '$Serial' is not connected and authorized."
        }
    } elseif ($deviceLines.Count -eq 1) {
        $Serial = ($deviceLines[0] -split '\s+')[0]
    } elseif ($deviceLines.Count -eq 0) {
        throw 'No authorized ADB device is connected.'
    } else {
        throw 'More than one ADB device is connected. Re-run with -Serial <device-id>.'
    }

    Write-Host "Installing $($apk.FullName) on $Serial..." -ForegroundColor Cyan
    & adb -s $Serial install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "ADB install failed with exit code $LASTEXITCODE."
    }

    if ($Launch) {
        Write-Host "Launching $packageName..." -ForegroundColor Cyan
        & adb -s $Serial shell monkey -p $packageName 1 | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Could not launch $packageName."
        }
    }

    Write-Host "Installed successfully on $Serial." -ForegroundColor Green
} finally {
    Pop-Location
}
