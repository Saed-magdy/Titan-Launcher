<#
.SYNOPSIS
    Builds the Titan Launcher APK (the installable app file) from the source code.

.DESCRIPTION
    In plain words, this script turns the project folder into one installable file:
      1. AAPT reads the app's resources (icons, texts) and writes a small index file (R.java).
      2. javac turns the Java source code into Java bytecode.
      3. dx turns that bytecode into Android's format (classes.dex).
      4. AAPT packs everything (code, screens, pictures, fonts) into one APK file.
      5. zipalign tidies the file so Android can read it faster.
      6. apksigner "signs" the file. Android only installs signed apps, and only lets an app be
         updated by a file signed with the SAME key.

    Earlier versions of this script only worked on the original author's PC, because every folder
    (C:\Users\saedm\...) was typed in by hand. Now:
      - The project folder is found automatically (it is the folder this script is in).
      - The Android tools are found from the ANDROID_SDK_ROOT / ANDROID_HOME setting,
        or can be given with -SdkDir.
      - The signing key is given with -PlatformKey / -PlatformCert (or environment variables).
        Without a key, use -TestSign to sign with a throwaway test key (fine for checking that
        the build works, NOT for installing in the car as a system app).
      - The result is ONE file: bin\Titan_Launcher_v<version>.apk. Use -CopyTo to also copy it
        somewhere else (for example a USB stick folder).

.EXAMPLE
    # Real release build, signed with the car's platform key:
    .\build_apk.ps1 -SdkDir "D:\android-sdk" -PlatformKey "D:\keys\platform.pk8" -PlatformCert "D:\keys\platform.x509.pem"

.EXAMPLE
    # Just check that everything builds, with a test key:
    .\build_apk.ps1 -TestSign
#>
param(
    # Folder of the Android SDK (contains build-tools\ and platforms\).
    [string]$SdkDir = $(
        if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { $env:ANDROID_SDK_ROOT }
        elseif ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { $env:ANDROID_HOME }
        elseif (Test-Path "$PSScriptRoot\..\android-tools") { (Resolve-Path "$PSScriptRoot\..\android-tools").Path }
        else { $null }
    ),
    [string]$BuildToolsVersion = "28.0.3",
    # Car platform signing key and certificate (never commit these to git).
    # If not given here, they come from TITAN_PLATFORM_KEY / TITAN_PLATFORM_CERT, or from your
    # private build.local.ps1 file (see step 0 below).
    [string]$PlatformKey = $env:TITAN_PLATFORM_KEY,
    [string]$PlatformCert = $env:TITAN_PLATFORM_CERT,
    # Sign with a throwaway test key instead of the platform key.
    [switch]$TestSign,
    # Optional extra folders to copy the finished APK into.
    [string[]]$CopyTo = @()
)

$ErrorActionPreference = "Stop"

function Fail([string]$message) {
    Write-Host ""
    Write-Host "BUILD STOPPED: $message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# 0. Find everything we need, and explain clearly what is missing.
# ---------------------------------------------------------------------------
$projDir = $PSScriptRoot

# Private settings for THIS computer only (for example where the car's signing key is kept).
# build.local.ps1 is ignored by git, so personal folders and key locations never reach GitHub.
# To create it: copy build.local.example.ps1 to build.local.ps1 and fill in your own paths.
# Anything given on the command line or in environment variables still wins.
$localSettings = Join-Path $projDir "build.local.ps1"
if (Test-Path $localSettings) {
    $LocalPlatformKey = $null; $LocalPlatformCert = $null; $LocalSdkDir = $null
    . $localSettings
    if (-not $PlatformKey -and $LocalPlatformKey) { $PlatformKey = $LocalPlatformKey }
    if (-not $PlatformCert -and $LocalPlatformCert) { $PlatformCert = $LocalPlatformCert }
    if (-not $SdkDir -and $LocalSdkDir) { $SdkDir = $LocalSdkDir }
    Write-Host "Using private settings from build.local.ps1" -ForegroundColor DarkGray
}

if (-not $SdkDir -or -not (Test-Path $SdkDir)) {
    Fail "Android SDK folder not found. Pass -SdkDir <folder> or set the ANDROID_SDK_ROOT environment variable."
}

$btDir = Join-Path $SdkDir "build-tools\$BuildToolsVersion"
$aapt = Join-Path $btDir "aapt.exe"
$dxJar = Join-Path $btDir "lib\dx.jar"
$zipalign = Join-Path $btDir "zipalign.exe"
$apksignerJar = Join-Path $btDir "lib\apksigner.jar"
foreach ($tool in @($aapt, $dxJar, $zipalign, $apksignerJar)) {
    if (-not (Test-Path $tool)) {
        Fail "Missing build tool: $tool`n  Install it with: sdkmanager `"build-tools;$BuildToolsVersion`""
    }
}

# Android 9 (API 28) library. The SDK manager calls this folder "android-28";
# some older copies of the SDK call it "android-9", so both are accepted.
$androidJar = $null
foreach ($name in @("android-28", "android-9")) {
    $candidate = Join-Path $SdkDir "platforms\$name\android.jar"
    if (Test-Path $candidate) { $androidJar = $candidate; break }
}
if (-not $androidJar) {
    Fail "Android 9 platform (android.jar) not found under $SdkDir\platforms.`n  Install it with: sdkmanager `"platforms;android-28`""
}

foreach ($exe in @("java", "javac")) {
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) {
        Fail "'$exe' not found. Install a Java JDK (version 11 or newer) and make sure it is on PATH."
    }
}

if (-not $TestSign) {
    if (-not $PlatformKey -or -not (Test-Path $PlatformKey) -or -not $PlatformCert -or -not (Test-Path $PlatformCert)) {
        Fail "Platform signing key not found. Pass -PlatformKey and -PlatformCert (or set TITAN_PLATFORM_KEY / TITAN_PLATFORM_CERT, or put them in build.local.ps1).`n  To only check that the build works, run again with -TestSign."
    }
}

$srcDir = Join-Path $projDir "src"
$resDir = Join-Path $projDir "res"
$assetsDir = Join-Path $projDir "assets"
$manifest = Join-Path $projDir "AndroidManifest.xml"
$binDir = Join-Path $projDir "bin"
$genDir = Join-Path $binDir "gen"           # R.java goes here, so the src folder stays clean
$classesDir = Join-Path $binDir "classes"
$stubsClassesDir = Join-Path $binDir "stubs_classes"

# Remove legacy R.java in src/ if present from older builds to avoid duplicate class errors
$legacyR = Join-Path $srcDir "com\dongfeng\shinecockpit\R.java"
if (Test-Path $legacyR) { Remove-Item $legacyR -Force }

foreach ($dir in @($genDir, $classesDir, $stubsClassesDir)) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

# The version number is read from AndroidManifest.xml, the only place it is written.
[xml]$manifestXml = Get-Content $manifest
$verName = $manifestXml.manifest.GetAttribute("versionName", "http://schemas.android.com/apk/res/android")
Write-Host "Building Titan Launcher v$verName" -ForegroundColor Green

# ---------------------------------------------------------------------------
# 1. Resource index (R.java)
# ---------------------------------------------------------------------------
Write-Host "--- Step 1: Running AAPT to generate R.java ---" -ForegroundColor Cyan
& $aapt package -f -m -J $genDir -M $manifest -S $resDir -I $androidJar
if ($LASTEXITCODE -ne 0) { Fail "AAPT could not generate R.java" }

# ---------------------------------------------------------------------------
# 2. Compile Java. "-encoding UTF-8" is important: the code contains Arabic text, and
#    older Java versions on Windows would otherwise misread it.
#    compile_stubs\ holds empty stand-ins for car-maker classes that exist only on the head
#    unit; they let the code compile here and are NOT packed into the APK.
# ---------------------------------------------------------------------------
Write-Host "--- Step 2: Compiling Java sources (Java 8 bytecode) ---" -ForegroundColor Cyan
$stubsSrcDir = Join-Path $projDir "compile_stubs"
$stubFiles = if (Test-Path $stubsSrcDir) { @(Get-ChildItem -Path $stubsSrcDir -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }) } else { @() }
$cp = "$androidJar"
if ($stubFiles.Count -gt 0) {
    & javac -encoding UTF-8 --release 8 -g:none -nowarn -XDstringConcat=inline -cp "$androidJar" -d $stubsClassesDir $stubFiles
    if ($LASTEXITCODE -ne 0) { Fail "javac could not compile the stubs" }
    $cp = "$androidJar;$stubsClassesDir"
}

$javaFiles = @(Get-ChildItem -Path $srcDir -Filter "*.java" -Recurse | ForEach-Object { $_.FullName })
$javaFiles += @(Get-ChildItem -Path $genDir -Filter "*.java" -Recurse | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 8 -g:none -Xlint:-options -XDstringConcat=inline -cp $cp -d $classesDir $javaFiles
if ($LASTEXITCODE -ne 0) { Fail "javac compilation failed (see the errors above)" }

# ---------------------------------------------------------------------------
# 3. Convert to Android's format (classes.dex)
# ---------------------------------------------------------------------------
Write-Host "--- Step 3: Running DX to create classes.dex ---" -ForegroundColor Cyan
$dexFile = Join-Path $binDir "classes.dex"
if (Test-Path $dexFile) { Remove-Item $dexFile -Force }
& java -jar $dxJar --dex --min-sdk-version=21 --output=$dexFile $classesDir
if ($LASTEXITCODE -ne 0) { Fail "dx could not create classes.dex" }

# ---------------------------------------------------------------------------
# 4. Pack everything into one APK
# ---------------------------------------------------------------------------
Write-Host "--- Step 4: Packaging resources and assets with AAPT ---" -ForegroundColor Cyan
$unsignedApk = Join-Path $binDir "app.unsigned.apk"
if (Test-Path $unsignedApk) { Remove-Item $unsignedApk -Force }
& $aapt package -f -0 woff2 -0 glb -M $manifest -S $resDir -A $assetsDir -I $androidJar -F $unsignedApk
if ($LASTEXITCODE -ne 0) { Fail "AAPT could not package the APK" }

Write-Host "--- Step 5: Adding classes.dex to the APK ---" -ForegroundColor Cyan
Push-Location $binDir
& $aapt add $unsignedApk "classes.dex"
$addExit = $LASTEXITCODE
Pop-Location
if ($addExit -ne 0) { Fail "AAPT could not add classes.dex" }

# ---------------------------------------------------------------------------
# 5. Tidy (zipalign)
# ---------------------------------------------------------------------------
Write-Host "--- Step 6: Zipalign ---" -ForegroundColor Cyan
$alignedApk = Join-Path $binDir "app.aligned.apk"
if (Test-Path $alignedApk) { Remove-Item $alignedApk -Force }
& $zipalign -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { Fail "zipalign failed" }

# ---------------------------------------------------------------------------
# 6. Sign
# ---------------------------------------------------------------------------
$finalApk = Join-Path $binDir "Titan_Launcher_v${verName}.apk"
if (Test-Path $finalApk) { Remove-Item $finalApk -Force }

if ($TestSign) {
    Write-Host "--- Step 7: Signing with a TEST key (not for the car) ---" -ForegroundColor Yellow
    # A throwaway key kept in bin\ (which git ignores). Created once, reused afterwards.
    $testKeystore = Join-Path $binDir "test-only.keystore"
    if (-not (Test-Path $testKeystore)) {
        & keytool -genkeypair -keystore $testKeystore -storepass android -keypass android -alias test `
            -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Titan Launcher TEST ONLY" | Out-Null
        if ($LASTEXITCODE -ne 0) { Fail "keytool could not create the test key" }
    }
    & java -jar $apksignerJar sign --ks $testKeystore --ks-pass pass:android --ks-key-alias test --out $finalApk $alignedApk
} else {
    Write-Host "--- Step 7: Signing with the platform key ---" -ForegroundColor Cyan
    & java -jar $apksignerJar sign --key $PlatformKey --cert $PlatformCert --out $finalApk $alignedApk
}
if ($LASTEXITCODE -ne 0) { Fail "apksigner could not sign the APK" }

& java -jar $apksignerJar verify --print-certs $finalApk
if ($LASTEXITCODE -ne 0) { Fail "The signed APK did not pass verification" }

foreach ($dest in $CopyTo) {
    if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Force -Path $dest | Out-Null }
    Copy-Item $finalApk $dest -Force
    Write-Host "Copied to: $dest" -ForegroundColor Green
}

# Auto-sync build to READY_FOR_CAR as #2 (never overwrites original 1_Titan_launcher_v...)
$readyDir = Join-Path $projDir "..\READY_FOR_CAR"
if (Test-Path $readyDir) {
    Copy-Item $finalApk (Join-Path $readyDir "2_Titan_Launcher_v${verName}.apk") -Force
    Write-Host "Auto-copied to READY_FOR_CAR\2_Titan_Launcher_v${verName}.apk" -ForegroundColor Green
}
Copy-Item $finalApk (Join-Path $projDir "Titan_Launcher_v${verName}.apk") -Force

Write-Host "==========================================" -ForegroundColor Green
Write-Host "BUILD SUCCESSFUL" -ForegroundColor Green
Write-Host "Output APK: $finalApk" -ForegroundColor Green
Write-Host "Size: $((Get-Item $finalApk).Length) bytes" -ForegroundColor Green
if ($TestSign) {
    Write-Host "NOTE: signed with a TEST key. It will not install over the car's current launcher." -ForegroundColor Yellow
}
Write-Host "==========================================" -ForegroundColor Green
