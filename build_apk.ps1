$ErrorActionPreference = "Stop"

$toolsDir = "c:\Users\saedm\Downloads\Mobile Devices\sys-apk\android-tools"
$btDir = Join-Path $toolsDir "build-tools\28.0.3"
$aapt = Join-Path $btDir "aapt.exe"
$dxJar = Join-Path $btDir "lib\dx.jar"
$zipalign = Join-Path $btDir "zipalign.exe"
$apksignerJar = Join-Path $btDir "lib\apksigner.jar"
$androidJar = Join-Path $toolsDir "platforms\android-9\android.jar"
$keytool = "C:\Program Files\Java\jdk-26.0.1\bin\keytool.exe"

$projDir = "c:\Users\saedm\Downloads\Mobile Devices\sys-apk\DongfengShineApp"
$srcDir = Join-Path $projDir "src"
$resDir = Join-Path $projDir "res"
$assetsDir = Join-Path $projDir "assets"
$binDir = Join-Path $projDir "bin"
$classesDir = Join-Path $binDir "classes"
$manifest = Join-Path $projDir "AndroidManifest.xml"

if (Test-Path $classesDir) { Remove-Item $classesDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

Write-Host "--- Step 1: Running AAPT to generate R.java ---" -ForegroundColor Cyan
& $aapt package -f -m -J $srcDir -M $manifest -S $resDir -I $androidJar
if ($LASTEXITCODE -ne 0) { throw "AAPT generate R.java failed!" }

Write-Host "--- Step 2: Compiling Java Sources with javac (inline stringConcat, Java 8 bytecode) ---" -ForegroundColor Cyan
$stubsSrcDir = Join-Path $projDir "compile_stubs"
$stubsClassesDir = Join-Path $binDir "stubs_classes"
if (Test-Path $stubsClassesDir) { Remove-Item $stubsClassesDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stubsClassesDir | Out-Null
$stubFiles = Get-ChildItem -Path $stubsSrcDir -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
& javac --release 8 -g:none -XDstringConcat=inline -cp "$androidJar" -d $stubsClassesDir $stubFiles
if ($LASTEXITCODE -ne 0) { throw "javac stubs compilation failed!" }

$javaFiles = Get-ChildItem -Path $srcDir -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
& javac --release 8 -g:none -XDstringConcat=inline -cp "$androidJar;$stubsClassesDir;$srcDir" -d $classesDir $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed!" }

Write-Host "--- Step 3: Running DX to create classes.dex ---" -ForegroundColor Cyan
$dexFile = Join-Path $binDir "classes.dex"
if (Test-Path $dexFile) { Remove-Item $dexFile -Force }
& java -jar $dxJar --dex --min-sdk-version=21 --output=$dexFile $classesDir
if ($LASTEXITCODE -ne 0) { throw "dx dex generation failed!" }

Write-Host "--- Step 4: Packaging APK resources and assets with AAPT ---" -ForegroundColor Cyan
$unsignedApk = Join-Path $binDir "app.unsigned.apk"
if (Test-Path $unsignedApk) { Remove-Item $unsignedApk -Force }
& $aapt package -f -0 woff2 -M $manifest -S $resDir -A $assetsDir -I $androidJar -F $unsignedApk
if ($LASTEXITCODE -ne 0) { throw "AAPT package failed!" }

Write-Host "--- Step 5: Adding classes.dex to APK ---" -ForegroundColor Cyan
Push-Location $binDir
& $aapt add $unsignedApk "classes.dex"
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "AAPT add classes.dex failed!" }

Write-Host "--- Step 6: Zipalign APK ---" -ForegroundColor Cyan
$alignedApk = Join-Path $binDir "app.aligned.apk"
if (Test-Path $alignedApk) { Remove-Item $alignedApk -Force }
& $zipalign -v -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed!" }

Write-Host "--- Step 7: Signing APK with Dongfeng Platform Keys ---" -ForegroundColor Cyan
$platKey = "C:\Users\saedm\Downloads\Mobile Devices\sys-apk\shine wave\shine wave\shine wave\app\platform\platform.pk8"
$platCert = "C:\Users\saedm\Downloads\Mobile Devices\sys-apk\shine wave\shine wave\shine wave\app\platform\platform.x509.pem"

[xml]$manifestXml = Get-Content $manifest
$verName = $manifestXml.manifest.GetAttribute("versionName", "http://schemas.android.com/apk/res/android")
$releaseApkName = "Titan_launcher_v $verName.apk"

$finalApk = Join-Path $projDir $releaseApkName
if (Test-Path $finalApk) { Remove-Item $finalApk -Force }
& java -jar $apksignerJar sign --key $platKey --cert $platCert --out $finalApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed!" }

# Verify signature
& java -jar $apksignerJar verify --print-certs $finalApk

# Also copy to workspace roots and READY_FOR_CAR
Copy-Item $finalApk "c:\Users\saedm\Downloads\Mobile Devices\sys-apk\$releaseApkName" -Force
Copy-Item $finalApk "c:\Users\saedm\Downloads\Mobile Devices\sys-apk\READY_FOR_CAR\1_$releaseApkName" -Force
Copy-Item $finalApk (Join-Path $projDir "Shine_launcher.apk") -Force
Copy-Item $finalApk (Join-Path $projDir "Titan_Launcher_Car_Release.apk") -Force
Copy-Item $finalApk "c:\Users\saedm\Downloads\Mobile Devices\sys-apk\Shine_launcher.apk" -Force
Copy-Item $finalApk "c:\Users\saedm\Downloads\Mobile Devices\Shine_launcher.apk" -Force
Copy-Item $finalApk (Join-Path $projDir "ShineHub.apk") -Force
Copy-Item $finalApk (Join-Path $projDir "DongfengShineCockpit.apk") -Force
$readyForCar = "c:\Users\saedm\Downloads\Mobile Devices\sys-apk\READY_FOR_CAR\1_Shine_launcher.apk"
Copy-Item $finalApk $readyForCar -Force

Write-Host "==========================================" -ForegroundColor Green
Write-Host "BUILD SUCCESSFUL!" -ForegroundColor Green
Write-Host "Output APK: $finalApk" -ForegroundColor Green
Write-Host "Size: $((Get-Item $finalApk).Length) bytes" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
