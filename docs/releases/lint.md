# Предупреждения Android Lint для 1.5

Отчёт release: **0 ошибок, 248 предупреждений**. Их наличие не означает 248 подтверждённых runtime-дефектов; полный нулевой lint baseline не заявлен.

| ID | Количество | Смысл |
| --- | ---: | --- |
| UnusedResources | 194 | Неиспользуемые ресурсы |
| UseKtx | 13 | Предложения Kotlin extensions |
| Untranslatable | 8 | Несогласованные атрибуты переводов |
| AuthLeak | 5 | Демонстрационные URI `user:password@server.invalid`, не рабочие секреты |
| PluralsCandidate | 5 | Строки, для которых предпочтительны plurals |
| CheckResult | 4 | JSON parser намеренно читает/пропускает значение |
| NewerVersionAvailable | 3 | Уведомления о версиях инструментов |
| GradleDependency | 3 | Уведомления о версиях библиотек |
| UsableSpace | 2 | Рекомендации Android StorageManager |
| ModifierParameter | 2 | Порядок параметров Compose |
| UseTomlInstead | 2 | Version catalog |
| AndroidGradlePluginVersion | 1 | Версия build-инструмента |
| SetJavaScriptEnabled | 1 | JavaScript в WebView VK-интеграции |
| InsecureBaseConfiguration | 1 | Cleartext разрешён для локальных подписок на уровне Android config |
| IconLocation | 1 | Densityless bitmap resource |
| ChromeOsAbiSupport | 1 | Нет готового x86_64 APK |
| StaticFieldLeak | 1 | Статическая ссылка на Activity в VK-интеграции |
| VectorPath | 1 | Сложный vector path |

Особого внимания требуют WebView, cleartext policy и lifecycle Activity. Ссылка очищается в `onDestroy`, но это не заменяет отдельного lifecycle-теста; разрешение HTTP ограничивается прикладным кодом, а не глобальным запретом Android. Предупреждения не подавлялись ради зелёного отчёта.
