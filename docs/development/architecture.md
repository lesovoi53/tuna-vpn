# Архитектура и границы ответственности

## Дерево компонентов

```text
Android UI (Compose) → ViewModel → профили / настройки / подписки
                                  ↓
                           жизненный цикл VPN
Android VpnService / HEV TUN → маршрутизатор Xray → транспорт профиля
                                  ├─ Xray outbound
                                  ├─ локальный sing-box-extended
                                  ├─ локальный Mieru
                                  └─ специализированный туннель

Routing Builder → tuna://routing/v1 → Android-импорт → те же настройки
```

Это логическая схема, а не обещание одинакового числа процессов для всех профилей. Полный пользовательский Xray JSON владеет своими DNS/маршрутами. Есть экспериментальный путь sing-box routing; общий `ProfileRouting.resolve` выбирает Xray. Сохранённые нативные options требуют отдельной проверки.

## Навигация по исходникам

База Kotlin: `android/app/src/main/java/com/wireturn/app/`.

| Область | Точки входа |
| --- | --- |
| VPN lifecycle | `HevVpnService.kt`, `TunnelService.kt`, `kernel/` |
| Настройки | `data/AppPreferences.kt`, `data/NetworkPreferences.kt`, остальные `data/` |
| Подписки и применение | `domain/ProfileManager.kt`, `SubscriptionDownload.kt`, `SubscriptionProfileRefresh.kt`, `SubscriptionProfileMerge.kt`, `data/SubscriptionImportCommit.kt` |
| Маршрутизация | `domain/ProfileRouting.kt`, `data/kernel/Network*.kt`, `kernel/XrayNetworkCompiler.kt`, `kernel/SingBoxNetworkCompiler.kt` |
| Импорт VLESS | `data/kernel/VpnConfig.kt`, `VpnEnginePolicy.kt`, `CoreCapabilities.kt`, `domain/LegacyProfileImport.kt` |
| URL-test | `domain/ConnectionGroupRunner.kt`, `GroupRuntime.kt`, `GroupProbe.kt`, `ProfileUrlTestBatch.kt` |
| Восстановление/сон | `kernel/OperationWakeLock.kt`, сервисы, политики восстановления |
| Диагностика/хранилище | классы `Diagnostic*`, `Storage*`, экспорт и retention в `domain/` и сервисах |
| UI | `ui/screens/`, `ui/activities/`, `viewmodel/` |

Имена — ориентиры; используйте поиск символов перед изменением. Старые значения enum могут оставаться ради миграций и не означают доступную функцию UI.

## Инварианты

1. VPN-сокеты должны корректно обходить собственный TUN, иначе возможна петля. Изменение DNS/bootstrap не должно размывать эту границу.
2. Маршрут выбирается с сохранением доменных правил; принудительное разрешение восстановленного MAPDNS-домена не должно передавать DNS-политику удалённому прокси.
3. Remote DNS идёт через VPN; direct DNS — напрямую; bootstrap разрешает адреса серверов. Failover не должен перескакивать между группами.
4. Отмена старого запуска не должна закрывать ресурсы новой сессии. Любое восстановление привязано к текущему поколению подключения.
5. Локальные SOCKS endpoints защищены аутентификацией. Нельзя обходить ошибку authentication отключением проверки.
6. Настройки/профили пользователя не являются тестовыми данными. Обновление подписки сохраняет локальные дополнения согласно правилам merge.
7. WakeLock ограничен операцией/временем; пользовательский постоянный режим — отдельная явная настройка. Не удалять keepalive/foreground service ради результатов энерготеста.
8. Не выполнять пачку всех пропущенных периодических URL-тестов после сна. Сохранять ограничения переключений и подтверждения отказа.

## Builder

`app.py` — оболочка/карточки/источники; `core.py` — package ID и APK; `policy.py` — схема, валидация, импорт/экспорт; `editor.py`/`dns_editor.py` — формы; `geo.py`/`completion.py` — DAT/подсказки; `routing_sources.py` — источники геоданных; `runtime_paths.py` — writable-кэш упакованного EXE.

Редактор не устанавливает APK и не подключает VPN. Он не может подтвердить доступность частного DNS, маршрута или сервера. Сетевые загрузки и UI не должны блокировать друг друга; отмена загрузки не должна оставлять частично принятый проект.

