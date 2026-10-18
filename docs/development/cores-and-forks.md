# Ядра, форки и соответствие исходникам

**Fork** означает, что компонент отличается от закреплённого upstream. Он не означает новый протокол, независимое авторство всего ядра или аудит безопасности. UI-обёртка и патч самого ядра — разные вещи.

| Компонент | Upstream / зафиксированная основа | Изменения/сборка TUNA |
| --- | --- | --- |
| Android | [WireTurn](https://github.com/spkprsnts/WireTurn), v8.3 `4fe1cf6d14a0bc5a3a8f4f254f0dbb90ebb8d879` | Compose/UI, профили, группы, DNS/маршрутизация, lifecycle и диагностика |
| Xray | XTLS/Xray-core `52a412d9e2f5`, module version в `external/vless-client/go.mod` | `native/routing/`: adaptive DNS, bootstrap, политика и XHTTP; wrapper в `external/vless-client/` |
| sing-box-extended | shtorm-7, `55faa763f986f4ca8a492d9b2719bc6330d2bef5`, v1.14.1-extended-2.7.2 | `native/singbox/source-overlay.json`, dependency-overlay, версии uTLS/Cronet; startup/DNS/Snell/TrustTunnel и совместимость |
| Mieru | enfein, v3.38.1 `a2208ee7a38fb41d843a13ecdde858e67ef2f485` | Launcher `external/mieru-adapter`, локальная SOCKS-auth в `native/mieru`; upstream-тесты аутентификации сохранены |
| WebDAV | spkprsnts, `b1af4c05eb80fd29a6f676be894fa61cd12a3dec` | `native/webdav`: overlay, локальная auth, проверки и provenance |
| MasterDnsVPN | masterking32, `7de2476f1c33e69eec35360c52810dab43c5c986` | `native/masterdns`: CLI/lifecycle adapter; upstream DNS-транспорт |
| qWDTT | SpaceNeuroX, `a296c57eaba69bb9479a24f9157856490890e47d` | Готовый бинарник из checksum-pinned WireTurn 8.3, не новая собственная сборка |
| HEV | heiher, `941c758101385d145c66210ac88991daaf27d4b6` | TUN-компонент из того же APK |

Полные Go версии/хеши — в `go.mod`, `go.sum`, `native-lock.json` и `app/src/main/assets/native/`. Таблица не заменяет эти lock-файлы. Ссылки подмодулей лежат в корневом `.gitmodules`.

## Почему важен overlay

Патчи не прячутся в изменённом checkout стороннего проекта: скрипт сопоставляет ожидаемый исходный фрагмент и применяет известное изменение. Несовпадение anchor/commit/hash должно завершать сборку ошибкой. Переход на новый upstream — самостоятельная задача: пересмотреть патчи, лицензии, контракт адаптера и повторить тесты.

Примеры: Xray разрешает восстановленный домен согласно клиентской DNS-политике; sing-box использует адаптивную стратегию DNS и исправления startup; Mieru не должен понижать требуемую SOCKS-аутентификацию. Это конкретные причины маркировать компоненты как изменённые, не гарантии отсутствия остальных проблем.

## Cronet / NaiveProxy

Naive outbound включён build tag `with_naive_outbound`. Исходная цепочка:

1. SagerNet/cronet-go source build commit `049f2909701ca088c9569f6b303bba5376a2b2ff`.
2. Static library commit `c10c03c318db6d47bec8705c8290bad34bc32b64` (upstream сообщение “Build from 049f2909”).
3. Go bindings commit `0d28acc44093df24b2526dea3d6ffefd6b0a54f0`.
4. Все закрепляют [SagerNet/naiveproxy `72a06c9fca0e2d228588c7f3074bf7efff3ff686`](https://github.com/SagerNet/naiveproxy/tree/72a06c9fca0e2d228588c7f3074bf7efff3ff686), Chromium **150.0.7871.63**.

[Upstream workflow](https://github.com/SagerNet/cronet-go/blob/c10c03c318db6d47bec8705c8290bad34bc32b64/.github/workflows/naive-build.yml) строит `cronet_static` через `go run ./cmd/build-naive --target=android/arm64 build` и `package`. Это Linux build pipeline upstream, не команда, проверенная здесь на Windows. Обычный архив cronet-go не включает submodule naiveproxy: клонируйте рекурсивно либо скачайте его точное дерево отдельно.

Сборка TUNA использует закреплённую static library. Коллекция лицензий из точного дерева NaiveProxy/Chromium сохранена отдельно вместе с происхождением; это расширенная коллекция source notices, не автоматически доказанный список только реально слинкованных файлов.

## Границы provenance

- qWDTT в upstream APK сообщает root revision WireTurn и `vcs.modified=true`. Закреплённый qWDTT gitlink и версии Go-зависимостей сопоставлены; побитовое соответствие этого prebuilt его исходникам не установлено. Флаг сам по себе не доказывает функциональное отличие.
- HEV/qWDTT hash закреплён в `native/prebuilt-provenance.json`; старый APK проверяется по `native-lock.json`.
- Локальные Xray/Mieru/WebDAV/extended overlays и adapter source hashes опубликованы. Подпись APK проверяется отдельно от native provenance.
- Не включены старый rollback-checkout sing-box-plus и экспериментальные реализации вне текущего списка компонентов. Неизвестные типы обрабатываются общими правилами валидации.
- Базовые upstream build-инструкции WireTurn доступны [в точном исходном commit](https://github.com/spkprsnts/WireTurn/tree/4fe1cf6d14a0bc5a3a8f4f254f0dbb90ebb8d879). Не обещаем, что текущий локальный Windows pipeline пересобирает эти два prebuilt-компонента.
