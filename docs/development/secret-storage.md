# Локальные секреты: хранение и границы проверки

Профили и подписки остаются в том же DataStore `files/datastore/app_prefs.preferences_pb`.
Доменный формат JSON и транзакционные операции не меняются. Единственная точка
доступа `AppPreferences.internalDataStore` выдаёт расшифрованные `Preferences`
в памяти, а на диске шифруются все значения String и StringSet, включая новые
строковые ключи. Числа/флаги и имена ключей остаются открытыми.

Ключ AES-256-GCM создаётся в Android Keystore под alias `tuna.preferences.aes.v1`.
Биометрия не требуется: автоматический запуск VPN сохраняется. Каждая запись
имеет новый 96-битный nonce и 128-битный tag; AAD связывает ciphertext с версией,
именем ключа и типом String/StringSet. Ключ не экспортируется. Это защита данных
на диске, не гарантия против скомпрометированной ОС или процесса приложения.

## Миграция

DataMigration выполняется до первого чтения/изменения. Каждое строковое значение
кандидата шифруется, расшифровывается и сравнивается с исходным до атомарного commit DataStore.
Маркер версии и ciphertext записываются вместе; отдельного plaintext backup нет.
При сбое шифрования, проверки, записи или отмене исходный файл остаётся доступен.
Повторная миграция не перешифровывает уже мигрированный файл.

Для чтения и изменений существующего защищённого хранилища требуется прежний
ключ. Его отсутствие, неизвестная версия, повреждение tag или plaintext вместо
ciphertext завершаются ошибкой до выдачи данных доменному слою. Новый ключ вместо
потерянного не создаётся; автоматический reset/очистка не выполняются. Отсутствие
маркера при наличии уже зашифрованных значений также считается ошибкой. После
явного reset самого приложения wrapper сохраняет маркер и тот же ключ.

Не следует добавлять обработчик CorruptionHandler, возвращающий пустые
Preferences, или перехватывать SecretStorageException как пустой список профилей.
Исходный формат в памяти нужен optimistic/CAS проверкам импорта подписок:
шифротекст со случайным nonce нельзя использовать вместо этих снимков.

Все подписчики AppPreferences разделяют кэш одного проверенного неизменяемого
снимка: каждое обновление не запускает повторную расшифровку всех профилей для
каждого Flow. При записи неизменённые строки/StringSet сохраняют прежний
проверенный ciphertext; новые значения получают свежий nonce и отдельную
проверку расшифровки. Ошибки не кэшируются. Кэш не выдаётся вместо raw snapshot
после неудачного commit.

## Инвентаризация

| Место | Содержимое и граница |
| --- | --- |
| AppPreferences: profiles_json, subscriptions_json | URI/ключи профилей, URL/token подписки, исходные и локальные routing данные |
| active_kernel_json, active_wg_json, active_vless_json | Копии выбранного профиля; защищаются тем же wrapper |
| vless_link_history, client_socks_pass, xray_pass и legacy SOCKS keys | История ссылок и локальные пароли; защищаются автоматически как строки |
| xray_happ_source, xray_split_dns, subscription_settings_last_backup | Частные DNS URL и снимок отмены настроек; защищаются как строки |
| ProfileManager.getProfilesJson/exportProfilesToZip | Явный переносимый экспорт содержит настоящие ключи; локальное шифрование DataStore не меняет его контракт |
| HevVpnService: hev-socks5-tunnel.yaml | Runtime SOCKS credentials; нужен только активному владельцу TUN |
| SingBoxKernel: xray-vpn/config.json, singbox/config.json, mieru/launch.json | Runtime секреты транспорта и локального SOCKS |
| MasterDnsKernel: masterdns/client.toml, client_resolvers.txt | Runtime конфигурация и resolver endpoints |
| WebdavKernel: webdav.json | Runtime backend credentials; разрешения owner-only уже выставлялись |
| XrayService: policy/config staging | Runtime routing/DNS/transport; временные файлы и session ownership проверяются отдельно |
| NativeConfigCheck, RoutingConfigCheck, isolated probes | Временные private cache-файлы; штатно удаляются finally, аварийное завершение требует отдельной проверки |
| AppLogsState/PersistentDiagnosticLog | Редакция до Logcat и перед диском; opt-in журналы находятся в noBackupFilesDir; конфиги не включаются в diagnostic export |
| VkAuthWebViewManager / CookieManager | Browser-managed cookies остаются в Android WebView sandbox, исключённом из backup; wrapper DataStore их не шифрует и не удаляет |
| QwdttKernel command arguments | Некоторые native API принимают пароли через argv; sensitiveCommandFlags скрывает их в диагностике, шифрование постоянных настроек не устраняет runtime plaintext в памяти/аргументах |

`backup_rules.xml` и `data_extraction_rules.xml` исключают files/ и app_webview
для backup и переноса устройства. Android Keystore-ключи не следует переносить
отдельно или подменять. Явный профильный экспорт отличается от diagnostic export.
Native plaintext по необходимости хранится отдельно от постоянных настроек;
его lifecycle cleanup должен учитывать поколение процесса и не удалять файлы
новой сессии при завершении старой. Физическое стирание flash не гарантируется.
`PrivateRuntimeDirectory` выделяет отдельный приватный каталог запуска и убирает
его при освобождении владельца. При старте приложения очищаются известные
исторические runtime файлы и заброшенные проверки: core-check/route-check с
числовым суффиксом, profile-test/masterdns-scan и snell-health UUID. Весь cache
не очищается; неизвестные пользовательские имена сохраняются, обход symlink
при удалении тестовых каталогов не выполняется.

## Проверки

`EncryptedPreferencesTest` проверяет настоящий AES-GCM с внедряемым тестовым
ключом: roundtrip, все типы настроек, nonce/AAD, tamper, потерю ключа, версию,
отмену/ошибку транзакции, параллельные изменения, настоящий файл DataStore,
миграцию и повторное открытие без plaintext-копий. Проверка Android Keystore,
прерывания процесса при реальном обновлении и сохранения данных после ротации
APK signer требует отдельного устройства. JVM тесты не доказывают эти сценарии.

Локальный результат 18 октября 2026: 8 тестов `EncryptedPreferencesTest` и 1 тест
`AbandonedSecretChecksTest` прошли. Реальный protobuf-файл DataStore на Windows
проверен через AndroidX OkioStorage с атомарной заменой: Android FileStorage
использует Linux rename semantics и не заменяет существующий файл на Windows
JVM. Производственный Android backend остаётся штатным FileStorage. Эта
разница окружений явно ограничивает доказательство JVM-теста.
Восьмой тест подтверждает счётчики криптографических операций для нескольких
подписчиков и изменения одного поля. Полный JVM-набор с native manifest gate:
876 tests, 0 failures, 0 errors, 1 skipped (AdvancedProtocolFixtureTest).
Подробности — в [статусе реализации](security-and-dns-plan.md).
