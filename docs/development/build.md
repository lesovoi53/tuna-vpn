# Сборка из исходников

## Что именно воспроизводится

Репозиторий содержит Android/Python-код, локальные native-адаптеры, патчи, закреплённые подмодули и зависимости. Текущий Windows-конвейер **гибридный**: Xray/sing-box/Mieru/MasterDNS/WebDAV собираются из исходников; HEV и qWDTT извлекаются из APK WireTurn 8.3 с проверкой SHA-256. Cronet использует закреплённую upstream static library. Полная побитовая воспроизводимость всех бинарников не заявлена. [Происхождение](cores-and-forks.md).

## Клонирование

```powershell
git clone --recurse-submodules https://github.com/lesovoi53/tuna-vpn.git
cd tuna-vpn
git submodule update --init --recursive
```

Проверьте конкретные commit через `git submodule status`: подмодули не должны указывать на произвольный latest. Не используйте `git submodule update --remote` при сборке релиза. Автоматический GitHub source.zip содержит gitlinks, но не сами деревья подмодулей.

## Android: инструменты

Подтверждённая среда сопровождения — Windows x64 / PowerShell 7. Иные ОС требуют адаптации native-скриптов.

- JDK 21; локальная контрольная сборка использует 21.0.12.
- Gradle wrapper **9.7.1**, версия и checksum в `android/gradle/wrapper/gradle-wrapper.properties`.
- Android SDK platform **37**, Build Tools **37.0.0**.
- Native Go сборки: Android NDK **28.2.13676358**, API 26; AGP дополнительно задаёт NDK **29.0.14206865**. Установите нужные пакеты SDK; не подменяйте версии молча.
- Go: launcher 1.26.x с автоматической загрузкой toolchain; Xray `go.mod` требует **1.27.1**. Фактические версии фиксируются в provenance.
- Python 3.12+ и Git. Интернет нужен для первого получения SDK, Gradle, Maven, Go-модулей и upstream APK.
- Достаточно места для SDK, Go/Maven cache и native исходников; одна папка исходников не определяет размер окружения.

Не копируйте в репозиторий свой `local.properties`, keystore и `.env`. Пути ниже — **примеры**, замените их на установленные инструменты.

```powershell
$env:JAVA_HOME='C:/Tools/jdk-21'
$env:ANDROID_HOME='C:/Android/sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
cd android
./build-tunaturn.ps1 -JavaHome $env:JAVA_HOME -AndroidSdk $env:ANDROID_HOME `
  -GoExe (Get-Command go).Source -PythonExe (Get-Command python).Source
```

Основной скрипт проверяет хеш upstream APK, строит native-компоненты, формирует manifests, запускает JVM/native-проверки и debug-сборку. Ошибка любой стадии означает остановку, а не готовый APK. `native/singbox/build.ps1` требует чистый закреплённый checkout; изменения делайте через overlays.

`native/masterdns/build.ps1` создаёт `external/masterdnsvpn/cmd/tuna/main.go` из `native/masterdns/main.go`; это ожидаемый локальный генерируемый файл, его не коммитят в upstream.

## Release APK

После native-сборки:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease `
  -PtargetAbis=arm64-v8a -PprebuiltNative=true `
  -I tools/synthetic-tests.init.gradle --console=plain
python tools/check_r8_models.py
python tools/check_removed_protocols.py
```

Без signing config production release будет **неподписан**. Для исторической совместимой линии используется `-PprivateRelease=true`: это `.debug` applicationId с локальным debug keystore. У другого разработчика этот ключ другой; такой APK **не обновит** нашу готовую установку. Не запрашивайте и не коммитьте чужой приватный ключ. [Политика подписи](release.md).

Готовый APK: `app/build/outputs/apk/release/`. Для повторной проверки офлайн можно добавить `--offline`, только если зависимости уже скачаны. `tools/run-synthetic-tests.ps1` — локальный convenience wrapper: обязательно передавайте `-JavaHome`, `-AndroidSdk`, а для ReleaseGate ещё `-PythonExe`, `-GoExe`; не полагайтесь на старые fallback-пути разработчика.

Native-пересборка должна предшествовать `python tools/core_manifest.py`: он проверяет соответствие бинарников и хешей исходников, а не только печатает версии. Нельзя скопировать старый `.so` после изменения его входов и назвать сборку проверенной.

## Routing Builder: запуск и EXE

Нужен Python с Tcl/Tk (обычный установщик python.org для Windows включает его).

```powershell
cd routing-builder
py -3 -m venv .build-env
./.build-env/Scripts/python.exe -m pip install -r requirements.txt pyinstaller==6.16.0
./.build-env/Scripts/python.exe app.py
```

Тесты выполняйте так, чтобы GUI-скрипты имели отдельный процесс:

```powershell
./.build-env/Scripts/python.exe -m unittest test_core test_policy test_geo test_routing_sources test_dns_pools
./.build-env/Scripts/python.exe test_dns_gui.py
./.build-env/Scripts/python.exe test_paste_gui.py
./build-exe.ps1
./dist/TunaRoutingBuilder-v2.3.1.exe --self-test --self-test-report builder-smoke.json
```

GUI-тесты открывают скрытые Tk-окна; на headless Linux нужен Xvfb. Запуск всех GUI-модулей в одном unittest discovery может выдавать отложенные Tk-callback warnings после уничтожения окон; отдельные процессы исключают смешение Tcl runtime.

EXE однофайловый: Python рядом пользователю не нужен. PyInstaller не заменяет Authenticode-подпись. Geo-кэш и пользовательские проекты не должны попадать в сборку. Для повторного выпуска снимите SHA-256, проверьте экспорт/импорт `tuna://` и запуск EXE из отдельной папки.

## Что не подтверждено этим руководством

Локальная сборка 1.5 использовала существующий cache и готовые native-бинарники с provenance. Полный чистый native build на новом Windows runner и пересборка Chromium/HEV/qWDTT в этой публикационной задаче не выполнялись. CI проверяет Python/документы; зелёный CI не означает Android device acceptance.

