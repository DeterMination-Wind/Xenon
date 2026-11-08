# ---------------------------------------------------------------------------
# Xenon Launcher
# Copyright (C) 2026  Xenon contributors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
# ---------------------------------------------------------------------------
#
# Builds a release version locally and copies the artifacts to the workspace
# staging folder (default: ../构建/Xenon).
#
# This script never downloads anything from GitHub. It always compiles the
# current working tree, so the staged artifacts match the source that is about
# to be pushed/tagged instead of the CI output.
#
# Usage:
#   pwsh -NoProfile -File scripts/build-local-release.ps1 -Version 2.1.0
#   pwsh -NoProfile -File scripts/build-local-release.ps1 -Version 2.1.0 -SkipExe
#   pwsh -NoProfile -File scripts/build-local-release.ps1 -Version 2.1.0 -Destination D:\dist\Xenon
#
# The version may be omitted when HEAD is exactly on a release tag (vX.Y.Z).

[CmdletBinding()]
param(
    [string]$Version = '',
    [string]$Destination = '',
    [switch]$SkipExe
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $repo 'gradlew.bat')) -and
    -not (Test-Path (Join-Path $repo 'gradlew'))) {
    throw "Repository root not found from $PSScriptRoot"
}

function Resolve-Version {
    param([string]$Requested)

    $text = $Requested.Trim()
    if ($text.Length -eq 0) {
        Push-Location $repo
        try {
            $tag = (& git describe --tags --exact-match 2>$null)
            if ($LASTEXITCODE -eq 0 -and $tag) {
                $text = $tag.Trim()
            }
        } finally {
            Pop-Location
        }
    }
    if ($text.Length -eq 0) {
        throw 'Pass -Version <x.y.z>; HEAD is not on an exact release tag.'
    }
    if ($text.StartsWith('v')) {
        $text = $text.Substring(1)
    }
    if ($text -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
        throw "Invalid version '$text'; expected x.y.z"
    }
    return $text
}

function Get-JarVersion {
    param([string]$Jar)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' }
        if (-not $entry) { return '' }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            foreach ($line in ($reader.ReadToEnd() -split "`r?`n")) {
                if ($line -match '^Implementation-Version:\s*(.+)$') {
                    return $Matches[1].Trim()
                }
            }
        } finally {
            $reader.Close()
        }
    } finally {
        $zip.Dispose()
    }
    return ''
}

function Write-Sha256 {
    param([string]$File)

    $hash = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant()
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText("$File.sha256", $hash, $enc)
    return $hash
}

$Version = Resolve-Version -Requested $Version
$versionRoot = ($Version -split '\.')[0] + '.' + ($Version -split '\.')[1]
$buildNumber = ($Version -split '\.')[2]

if ($Destination.Trim().Length -eq 0) {
    $Destination = Join-Path (Split-Path -Parent $repo) '构建\Xenon'
}
$Destination = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $Destination | Out-Null

$isWindowsHost = $env:OS -eq 'Windows_NT'
$gradlew = if ($isWindowsHost) { Join-Path $repo 'gradlew.bat' } else { Join-Path $repo 'gradlew' }

$tasks = @(':Xenon:shadowJar', ':Xenon:packagePortable')
if (-not $SkipExe) {
    if (-not $isWindowsHost) {
        throw 'The Windows single-file exe can only be built on Windows; pass -SkipExe.'
    }
    $tasks += ':Xenon:packageWindowsSingleFileExe'
}

Write-Host "Building Xenon $Version from $repo" -ForegroundColor Cyan
$env:VERSION_ROOT = $versionRoot
$env:BUILD_NUMBER = $buildNumber
$env:VERSION_TYPE = 'stable'

Push-Location $repo
try {
    & $gradlew -g .gradle-user-home --console=plain @tasks
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$jar = Join-Path $repo "Xenon/build/libs/Xenon-$Version.jar"
$portable = Join-Path $repo "Xenon/build/dist/Xenon-portable-$Version.zip"
$exe = Join-Path $repo 'Xenon/build/dist/Xenon.exe'

foreach ($required in @($jar, $portable)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Expected build output is missing: $required"
    }
}
if (-not $SkipExe -and -not (Test-Path -LiteralPath $exe)) {
    throw "Expected build output is missing: $exe"
}

$jarVersion = Get-JarVersion -Jar $jar
if ($jarVersion -ne $Version) {
    throw "Jar reports version '$jarVersion', expected '$Version'"
}

$staged = @()
Copy-Item -LiteralPath $jar -Destination (Join-Path $Destination "Xenon-$Version.jar") -Force
$staged += "Xenon-$Version.jar"
Copy-Item -LiteralPath $portable -Destination (Join-Path $Destination "Xenon-portable-$Version.zip") -Force
$staged += "Xenon-portable-$Version.zip"
if (-not $SkipExe) {
    Copy-Item -LiteralPath $exe -Destination (Join-Path $Destination "Xenon-$Version.exe") -Force
    $staged += "Xenon-$Version.exe"
}

Write-Host ''
Write-Host "Staged to $Destination" -ForegroundColor Green
foreach ($name in $staged) {
    $path = Join-Path $Destination $name
    $hash = Write-Sha256 -File $path
    $size = (Get-Item -LiteralPath $path).Length
    Write-Host ("  {0,-32} {1,12:N0} B  {2}" -f $name, $size, $hash.Substring(0, 16))
}
Write-Host ''
Write-Host 'No remote asset was downloaded; everything was built from the local tree.' -ForegroundColor DarkGray
