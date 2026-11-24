param(
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = 'x86_64-pc-windows-msvc'
$previousLlamaStaticCrt = $env:LLAMA_STATIC_CRT
$previousMsvcRuntimeLibrary = $env:CMAKE_MSVC_RUNTIME_LIBRARY
$previousCxxFlags = $env:CXXFLAGS
$previousRustFlags = $env:RUSTFLAGS
$previousEncodedRustFlags = $env:CARGO_ENCODED_RUSTFLAGS
$previousKarukanVersion = $env:KARUKAN_VERSION

Push-Location $repositoryRoot
try {
    if ($null -eq $env:KARUKAN_VERSION) {
        $windowsManifest = Join-Path $PSScriptRoot 'Cargo.toml'
        $winVerMatch = [regex]::Match((Get-Content -LiteralPath $windowsManifest -Raw), '(?m)^version\s*=\s*"([^"]+)"')
        $winVer = if ($winVerMatch.Success) { $winVerMatch.Groups[1].Value } else { '0.1.3' }
        $gitCommit = (& git -C $repositoryRoot rev-parse --short=7 HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and !([string]::IsNullOrWhiteSpace($gitCommit))) {
            $env:KARUKAN_VERSION = "windows-v$winVer+g$gitCommit"
        } else {
            $env:KARUKAN_VERSION = "windows-v$winVer"
        }
    }

    $env:LLAMA_STATIC_CRT = '1'
    $env:CMAKE_MSVC_RUNTIME_LIBRARY = 'MultiThreaded$<$<CONFIG:Debug>:Debug>'
    $env:CXXFLAGS = (($previousCxxFlags, '/MT') -join ' ').Trim()
    if ($null -ne $previousEncodedRustFlags) {
        $separator = [char]31
        $env:CARGO_ENCODED_RUSTFLAGS = $previousEncodedRustFlags +
            $separator + '-C' + $separator + 'target-feature=+crt-static'
    }
    else {
        $env:RUSTFLAGS = (($previousRustFlags, '-C target-feature=+crt-static') -join ' ').Trim()
    }

    $cargoArguments = @('build', '-p', 'karukan-im-windows', '--target', $target)
    if ($Release) {
        $cargoArguments += '--release'
    }

    cargo @cargoArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Windows build failed with exit code $LASTEXITCODE"
    }
}
finally {
    if ($null -eq $previousLlamaStaticCrt) {
        Remove-Item Env:LLAMA_STATIC_CRT -ErrorAction SilentlyContinue
    }
    else {
        $env:LLAMA_STATIC_CRT = $previousLlamaStaticCrt
    }
    if ($null -eq $previousMsvcRuntimeLibrary) {
        Remove-Item Env:CMAKE_MSVC_RUNTIME_LIBRARY -ErrorAction SilentlyContinue
    }
    else {
        $env:CMAKE_MSVC_RUNTIME_LIBRARY = $previousMsvcRuntimeLibrary
    }
    if ($null -eq $previousCxxFlags) {
        Remove-Item Env:CXXFLAGS -ErrorAction SilentlyContinue
    }
    else {
        $env:CXXFLAGS = $previousCxxFlags
    }
    if ($null -eq $previousRustFlags) {
        Remove-Item Env:RUSTFLAGS -ErrorAction SilentlyContinue
    }
    else {
        $env:RUSTFLAGS = $previousRustFlags
    }
    if ($null -eq $previousEncodedRustFlags) {
        Remove-Item Env:CARGO_ENCODED_RUSTFLAGS -ErrorAction SilentlyContinue
    }
    else {
        $env:CARGO_ENCODED_RUSTFLAGS = $previousEncodedRustFlags
    }
    if ($null -eq $previousKarukanVersion) {
        Remove-Item Env:KARUKAN_VERSION -ErrorAction SilentlyContinue
    }
    else {
        $env:KARUKAN_VERSION = $previousKarukanVersion
    }
    Pop-Location
}
