# Сборка драйвера Deixis Display и помощника deixis-display.exe (Windows, Visual Studio 2022 + WDK).
# Результат — папка out\: DeixisDisplay.inf, .dll, .cat, .cer и deixis-display.exe.
# Подпись — своим сертификатом на 10 лет; его закрытый ключ удаляется сразу после подписи.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'out'
New-Item -ItemType Directory -Force $out | Out-Null

$vs = & "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath
$msbuild = Join-Path $vs 'MSBuild\Current\Bin\amd64\MSBuild.exe'
& $msbuild (Join-Path $root 'driver\DeixisDisplay.vcxproj') /p:Configuration=Release /p:Platform=x64 /m /nologo /v:minimal
if ($LASTEXITCODE -ne 0) { throw "драйвер не собрался" }

$built = Get-ChildItem -Recurse (Join-Path $root 'driver') -Filter DeixisDisplay.dll | Where-Object { $_.FullName -match 'Release' } | Select-Object -First 1
$inf = Get-ChildItem -Recurse (Join-Path $root 'driver') -Filter DeixisDisplay.inf | Where-Object { $_.FullName -match 'Release' } | Select-Object -First 1
if (-not $built -or -not $inf) { throw "нет DeixisDisplay.dll или обработанного .inf" }
Copy-Item $built.FullName, $inf.FullName $out -Force

# Помощник установки — обычная консольная программа.
$devcmd = Join-Path $vs 'Common7\Tools\VsDevCmd.bat'
$src = Join-Path $root 'installer\deixis-display.cpp'
cmd /c "call `"$devcmd`" -arch=amd64 -no_logo && cl /nologo /O2 /EHsc /MT /DUNICODE /D_UNICODE `"$src`" /Fe:`"$out\deixis-display.exe`" /Fo:`"$out\\`" setupapi.lib newdev.lib crypt32.lib"
if ($LASTEXITCODE -ne 0) { throw "помощник не собрался" }
Remove-Item "$out\*.obj" -ErrorAction SilentlyContinue

$kits = "${env:ProgramFiles(x86)}\Windows Kits\10\bin"
$inf2cat = Get-ChildItem -Recurse $kits -Filter Inf2Cat.exe | Sort-Object FullName -Descending | Select-Object -First 1
$signtool = Get-ChildItem -Recurse $kits -Filter signtool.exe | Where-Object { $_.FullName -match '\\x64\\' } | Sort-Object FullName -Descending | Select-Object -First 1

& $inf2cat.FullName /driver:$out /os:10_X64,10_RS5_X64,10_VB_X64 /uselocaltime
if ($LASTEXITCODE -ne 0) { throw "каталог драйвера не создан" }

$cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject 'CN=Deixis Display' -CertStoreLocation Cert:\CurrentUser\My -NotAfter (Get-Date).AddYears(10) -KeyExportPolicy NonExportable
try {
    Export-Certificate -Cert $cert -FilePath (Join-Path $out 'DeixisDisplay.cer') | Out-Null
    foreach ($f in @('DeixisDisplay.dll', 'DeixisDisplay.cat', 'deixis-display.exe')) {
        & $signtool.FullName sign /fd sha256 /sha1 $cert.Thumbprint /s My (Join-Path $out $f)
        if ($LASTEXITCODE -ne 0) { throw "не подписан $f" }
    }
} finally {
    # Закрытый ключ больше не нужен и не должен существовать.
    Remove-Item -Path "Cert:\CurrentUser\My\$($cert.Thumbprint)" -DeleteKey -Force
}
Get-ChildItem $out
