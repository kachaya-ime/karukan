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

Push-Location $repositoryRoot
try {
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
    Pop-Location
}
