@echo off
chcp 65001 >nul
rem Deixis: создаёт ключ подписи Android и копирует его в буфер обмена для секрета GitHub.
rem Ключ остаётся только на этом компьютере, в папке «Документы».

cd /d "%USERPROFILE%\Documents"

set "KT="
for /r "C:\Program Files\Eclipse Adoptium" %%f in (keytool.exe) do if exist "%%f" set "KT=%%f"
if defined KT goto have_keytool
echo.
echo Не найдена Java. Установите её командой:
echo   winget install EclipseAdoptium.Temurin.21.JDK
echo и запустите этот файл снова.
pause
exit /b 1

:have_keytool
if exist deixis-release.jks goto copy_key
echo.
echo ============================================================
echo  Сейчас нужно придумать пароль к ключу - латиницей,
echo  не короче 12 символов. Символы при вводе НЕ ВИДНЫ - это нормально.
echo  Пароль спросят два раза. Сразу запишите его.
echo ============================================================
echo.
"%KT%" -genkeypair -keystore deixis-release.jks -alias deixis -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Deixis"
if errorlevel 1 goto failed

:copy_key
certutil -f -encodehex deixis-release.jks "%TEMP%\deixis-key.txt" 0x40000001 >nul
if errorlevel 1 goto failed
clip < "%TEMP%\deixis-key.txt"
del "%TEMP%\deixis-key.txt"
echo.
echo ============================================================
echo  ГОТОВО. Ключ сохранён: %CD%\deixis-release.jks
echo  Он уже скопирован в буфер обмена.
echo.
echo  Сейчас откроется страница GitHub. Заполните:
echo    Name:   DEIXIS_KEYSTORE_BASE64
echo    Secret: Ctrl+V
echo  и нажмите Add secret. Затем ещё три секрета:
echo    DEIXIS_KEYSTORE_PASSWORD = ваш пароль
echo    DEIXIS_KEY_ALIAS         = deixis
echo    DEIXIS_KEY_PASSWORD      = тот же пароль
echo ============================================================
start "" "https://github.com/veracerkof-debug/practice-app-2/settings/secrets/actions/new"
pause
exit /b 0

:failed
echo.
echo Что-то пошло не так. Сделайте снимок этого окна и пришлите его.
pause
exit /b 1
