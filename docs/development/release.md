# Выпуск, подпись и обновление

## Публичная версия 1.5

- Version name: `1.5`, versionCode: `26101602`.
- ABI готового файла: `arm64-v8a`; minSdk 26 (Android 8.0).
- Application ID: `app.tunaturn.android.debug` — историческое имя, сохраняющее данные совместимой линии.
- Gradle `release`, `debuggable=false`, R8; **сертификат исторический Android Debug**.
- SHA-256 сертификата: `c3c08cdde6a991b036a5b14f07680116355ac8b8e0eadf44144d82e7c514131b`.

Версия без RC не меняет происхождение сертификата. Приватный ключ в репозитории отсутствует. Не создавайте новый ключ с тем же названием в надежде обновить существующее приложение: важен сертификат, не filename/alias. Отдельный production key/package migration — будущая согласованная работа; она не выполнена скрытно при публикации.

## Пользовательская проверка

```powershell
Get-FileHash ./TUNA-VPN-1.5-arm64-v8a.apk -Algorithm SHA256
Get-FileHash ./TunaRoutingBuilder-v2.3.1.exe -Algorithm SHA256
```

Сравните с `SHA256SUMS.txt` в том же релизе. Для проверки APK сертификата установите Android Build Tools и выполните:

```text
apksigner verify --verbose --print-certs TUNA-VPN-1.5-arm64-v8a.apk
```

APK 1.5 проверен по схеме v2 (v3 в этом артефакте не используется); package/signature должны совпадать с установленным приложением для обновления. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — повод проверить источник, а не удалять данные. SHA-256 не заменяет доверие GitHub-аккаунту. EXE не имеет Authenticode-подписи; отдельная GPG-подпись файлов в этом выпуске не заявляется.

## Чек-лист сопровождающего

1. Зафиксировать scope, versionName и увеличенный versionCode. Не смешивать с массовым обновлением ядер.
2. Проверить ветку и чистый экспорт: никаких подписок, keystore, токенов, логов устройств, private Git-history.
3. Проверить submodule pins, lock-файлы, licenses/NOTICE и соответствие packaged notices исходному NOTICE.
4. При изменении native-кода пересобрать его, обновить provenance и manifests.
5. Выполнить [тесты](testing.md), собрать release, проверить R8, package/version/ABI, подпись и отсутствие удалённых библиотек.
6. Установку поверх предыдущей версии проверить на разрешённом устройстве без очистки данных. Если это не сделано именно для нового артефакта, явно отметить.
7. Скопировать APK/EXE в отдельный каталог release-assets; SHA-256 считать после окончательного копирования.
8. Создать Git-tag, соответствующий исходникам APK. Опубликовать APK, EXE, source bundle, checksums и отчёт. Стандартный source.zip GitHub не заменяет подмодули.
9. Проверить доступность публичных файлов и сверить скачанный SHA-256 с локальным. Проверить запущенный CI отдельно.
10. Не объявлять device/energy/clean-runner проверки пройденными по одному успешному build job.

В этой публикации готовый APK соответствует Kotlin/native-wrapper snapshot 1.5; packaging notices могут обновляться отдельно от runtime-кода. Результаты и непроверенные границы — [отчёт 1.5](../releases/1.5.md).
