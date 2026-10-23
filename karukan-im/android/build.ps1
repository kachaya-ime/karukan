# ========================================================
# karukan-android build & sync script
# Location: karukan-im/android/build.ps1
# ========================================================

param (
    # Default target jniLibs directory inside app module
    [string]$DestJniLibs = "$PSScriptRoot\app\src\main\jniLibs",

    # Switch to clean build artifacts before compiling
    # Example: .\build.ps1 -Clean
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

# Save original working directory to restore upon completion/exit
$initialDir = Get-Location

# Dynamically resolve karukan workspace root directory
$androidCrateDir = $PSScriptRoot
$karukanRootDir  = (Get-Item "$androidCrateDir\..\..").FullName

try {
    # ========================================================
    # 1. Clean build artifacts if -Clean switch is provided
    # ========================================================
    if ($Clean) {
        Set-Location $karukanRootDir
        Write-Host "Cleaning previous build artifacts (cargo clean)..." -ForegroundColor Yellow
        cargo clean
    }

    # ========================================================
    # 2. Android NDK & LLVM Auto-detection
    # ========================================================
    if (-not $env:ANDROID_NDK_HOME) {
        $sdkDir = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
        $ndkBaseDir = "$sdkDir\ndk"
        if (Test-Path $ndkBaseDir) {
            $latestNdk = Get-ChildItem -Path $ndkBaseDir | Sort-Object Name -Descending | Select-Object -First 1
            if ($latestNdk) {
                $env:ANDROID_NDK_HOME = $latestNdk.FullName
            }
        }
    }

    if (-not $env:ANDROID_NDK_HOME -or -not (Test-Path $env:ANDROID_NDK_HOME)) {
        Write-Error "Android NDK not found. Please set ANDROID_NDK_HOME or ANDROID_HOME."
    }

    $ndkPath = $env:ANDROID_NDK_HOME
    $llvmBin = "$ndkPath\toolchains\llvm\prebuilt\windows-x86_64\bin"

    $env:ANDROID_NDK_ROOT = $ndkPath
    $env:NDK_ROOT         = $ndkPath
    $env:ANDROID_NDK      = $ndkPath
    $env:LIBCLANG_PATH    = $llvmBin

    # ========================================================
    # 3. Ninja & CMake Auto-detection
    # ========================================================
    $ninjaFound = $false

    if (Get-Command ninja -ErrorAction SilentlyContinue) {
        $ninjaFound = $true
    }

    if (-not $ninjaFound) {
        $sdkDir = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
        $cmakeBaseDir = "$sdkDir\cmake"
        if (Test-Path $cmakeBaseDir) {
            $latestCmake = Get-ChildItem -Path $cmakeBaseDir | Sort-Object Name -Descending | Select-Object -First 1
            if ($latestCmake) {
                $ninjaBin = "$($latestCmake.FullName)\bin"
                if (Test-Path "$ninjaBin\ninja.exe") {
                    $env:PATH = "$ninjaBin;$env:PATH"
                    $ninjaFound = $true
                }
            }
        }
    }

    if (-not $ninjaFound) {
        $vsDir = "$env:ProgramFiles\Microsoft Visual Studio"
        if (Test-Path $vsDir) {
            $ninjaFile = Get-ChildItem -Path $vsDir -Filter "ninja.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($ninjaFile) {
                $env:PATH = "$($ninjaFile.DirectoryName);$env:PATH"
                $ninjaFound = $true
            }
        }
    }

    $env:PATH = "$llvmBin;$env:PATH"
    $env:CMAKE_GENERATOR = "Ninja"

    # Remove conflicting CLANG_PATH if present
    Remove-Item Env:\CLANG_PATH -ErrorAction SilentlyContinue

    # ========================================================
    # 4. Build karukan-android
    # ========================================================
    Set-Location $karukanRootDir
    Write-Host "Starting build at workspace root: $karukanRootDir" -ForegroundColor Cyan
    Write-Host "Using Android NDK: $ndkPath`n" -ForegroundColor Gray

    # Build for Android (API 25: arm64-v8a / x86_64)
    $targetAbis = @("arm64-v8a", "x86_64")
    cargo ndk -t arm64-v8a -t x86_64 --platform 25 --link-libcxx-shared -o ./output build -p karukan-android --release

    # ========================================================
    # 5. Ensure libc++_shared.so exists & Sync .so files to target jniLibs
    # ========================================================
    $outputDir = "$karukanRootDir\output"

    $abiToTriple = @{
        "arm64-v8a"   = "aarch64-linux-android"
        "x86_64"      = "x86_64-linux-android"
        "armeabi-v7a" = "arm-linux-androideabi"
        "x86"         = "i686-linux-android"
    }

    foreach ($abi in $targetAbis) {
        $abiOutputDir = "$outputDir\$abi"
        if (-not (Test-Path $abiOutputDir)) {
            New-Item -ItemType Directory -Path $abiOutputDir -Force | Out-Null
        }

        $sharedInOutput = "$abiOutputDir\libc++_shared.so"
        if (-not (Test-Path $sharedInOutput)) {
            # Fallback: Locate libc++_shared.so from NDK
            $triple = $abiToTriple[$abi]
            $ndkSharedCandidate1 = "$ndkPath\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\$triple\libc++_shared.so"
            $ndkSharedCandidate2 = "$ndkPath\sources\cxx-stl\llvm-libc++\libs\$abi\libc++_shared.so"

            if (Test-Path $ndkSharedCandidate1) {
                Copy-Item -Path $ndkSharedCandidate1 -Destination $sharedInOutput -Force
            } elseif (Test-Path $ndkSharedCandidate2) {
                Copy-Item -Path $ndkSharedCandidate2 -Destination $sharedInOutput -Force
            } else {
                # Search NDK recursively for this ABI's libc++_shared.so
                $found = Get-ChildItem -Path $ndkPath -Filter "libc++_shared.so" -Recurse |
                    Where-Object { $_.FullName -match [regex]::Escape($abi) -or ($triple -and $_.FullName -match [regex]::Escape($triple)) } |
                    Select-Object -First 1
                if ($found) {
                    Copy-Item -Path $found.FullName -Destination $sharedInOutput -Force
                } else {
                    Write-Warning "Could not find libc++_shared.so in NDK for ABI $abi"
                }
            }
        }
    }

    if (-not (Test-Path $DestJniLibs)) {
        New-Item -ItemType Directory -Path $DestJniLibs -Force | Out-Null
    }
    Copy-Item -Path "$outputDir\*" -Destination $DestJniLibs -Recurse -Force

    Write-Host "`n[SUCCESS] Build complete! Copied .so files to: $DestJniLibs" -ForegroundColor Green
}
finally {
    # Always restore original working directory when script finishes or exits
    Set-Location $initialDir
}
