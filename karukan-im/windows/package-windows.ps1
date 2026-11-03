param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'dist')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifestPath = Join-Path $PSScriptRoot 'Cargo.toml'
$manifest = Get-Content -LiteralPath $manifestPath -Raw
$versionMatch = [regex]::Match($manifest, '(?m)^version\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) {
    throw "Could not read the Windows package version from $manifestPath"
}

$version = $versionMatch.Groups[1].Value
$target = 'x86_64-pc-windows-msvc'
$releaseDirectory = Join-Path $repositoryRoot "target\$target\release"
$packageName = "karukan-windows-x64-v$version"
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
$archivePath = Join-Path $outputPath "$packageName.zip"
$stagePath = Join-Path ([System.IO.Path]::GetTempPath()) "$packageName-$([guid]::NewGuid().ToString('N'))"

Push-Location $repositoryRoot
try {
    & (Join-Path $PSScriptRoot 'build-windows.ps1') -Release

    $requiredFiles = @(
        'install.exe',
        'karukan_im_windows.dll',
        'karukan-settings.exe'
    )
    foreach ($file in $requiredFiles) {
        $source = Join-Path $releaseDirectory $file
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Required distribution file was not built: $source"
        }
    }

    New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
    New-Item -ItemType Directory -Path $stagePath | Out-Null
    foreach ($file in $requiredFiles) {
        Copy-Item -LiteralPath (Join-Path $releaseDirectory $file) -Destination $stagePath
    }
    foreach ($licenseFile in @('LICENSE-MIT', 'LICENSE-APACHE')) {
        $source = Join-Path $repositoryRoot $licenseFile
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Required license file is missing: $source"
        }
        $licenseText = [System.IO.File]::ReadAllText($source, [System.Text.Encoding]::UTF8)
        if ($licenseFile -eq 'LICENSE-MIT') {
            $attributionPattern = '(?m)^Copyright \(c\) togatoga\r?$'
            $attributions = [regex]::Matches($licenseText, $attributionPattern)
            if ($attributions.Count -ne 1) {
                throw "Expected one togatoga copyright line in $source, found $($attributions.Count)"
            }
            $licenseText = [regex]::Replace(
                $licenseText,
                $attributionPattern,
                'Copyright (c) togatoga' + [Environment]::NewLine + 'Copyright (c) kachaya - Windows frontend',
                1
            )
        }
        $destination = Join-Path $stagePath $licenseFile
        [System.IO.File]::WriteAllText(
            $destination,
            $licenseText,
            [System.Text.UTF8Encoding]::new($false)
        )
    }

    @'
Karukan for Windows (x64)

Installation
1. Extract all files from this ZIP to a folder.
2. Open PowerShell as Administrator and change to the extracted folder.
3. Run: .\install.exe install
4. Add "Karukan Japanese Input" from Windows Settings > Time & language > Language & region > Japanese > Language options > Keyboards.

The installer registers the IME for the current user. An internet connection is required to download the system dictionary and configured conversion models.

To uninstall, run .\install.exe uninstall from an elevated PowerShell window.

License
Karukan is dual-licensed under MIT OR Apache-2.0. The full license texts are included in LICENSE-MIT and LICENSE-APACHE.
'@ | Set-Content -LiteralPath (Join-Path $stagePath 'README.txt') -Encoding UTF8

    Compress-Archive -Path (Join-Path $stagePath '*') -DestinationPath $archivePath -Force
    Write-Output "Created distribution package: $archivePath"
}
finally {
    Pop-Location
    if (Test-Path -LiteralPath $stagePath) {
        Remove-Item -LiteralPath $stagePath -Recurse -Force
    }
}
