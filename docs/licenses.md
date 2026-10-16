# Лицензии и сторонние компоненты

Собственные Android/Python-части этого публичного снимка распространяются по [GPL-3.0](../LICENSE), если конкретный файл не имеет другой лицензии. Это не меняет условий сторонних компонентов. Авторство WireTurn, ядер, адаптеров и заимствованных фрагментов сохранено в [NOTICE](../android/NOTICE.md).

| Компонент | Лицензия / источник уведомлений |
| --- | --- |
| Основа Android WireTurn и изменения TUNA | GPL-3.0, корневой LICENSE и NOTICE |
| Xray-core | MPL-2.0; `android/app/src/main/assets/licenses/Xray-MPL-2.0.txt` |
| vless-client wrapper | MIT; `android/external/vless-client/LICENSE` |
| sing-box-extended, Mieru, qWDTT | GPL-3.0 / GPL-3.0-or-later согласно конкретному upstream |
| HEV, MasterDnsVPN, WebDAV | MIT, тексты в Android assets/licenses и подмодулях |
| Cronet-Go | GPL-3.0-or-later; собственные и Chromium third-party условия остаются применимыми |
| Chromium/NaiveProxy | BSD и отдельные third-party лицензии; `Chromium-third-party-notices.zip` в Android assets/licenses содержит 76 неизменённых source notices с путями и SHA-256 |
| gVisor | Apache-2.0 |
| WireGuard | MIT |
| RE2/J | BSD-3-Clause |
| JSch, BouncyCastle | собственные разрешительные лицензии в Android assets/licenses |
| CustomTkinter | MIT; текст в `routing-builder/licenses/` |
| darkdetect | BSD-3-Clause; текст в `routing-builder/licenses/` |
| packaging | Apache-2.0 / BSD-2-Clause; оба текста сохранены |
| Python, Tcl/Tk | условия runtime из официальной поставки; копии в `routing-builder/licenses/` |
| PyInstaller | GPL с исключением для собранных приложений; текст исключения сохранён |

Версии и source pins: [ядра и форки](development/cores-and-forks.md), `android/native-lock.json`, `go.mod`/`go.sum` и gitlinks. Внешние источники и их контрольные суммы для архива выпуска перечислены в [upstream-sources.json](releases/upstream-sources.json).

## Получение исходников

Клонирование с `--recurse-submodules` даёт закреплённые upstream checkouts. Архив **TUNA-VPN-1.5-sources.zip** в Release содержит публичное дерево TUNA и отдельные архивы основных upstream-компонентов; перед native-сборкой используйте Git-подмодули согласно инструкции. Стандартный GitHub source.zip не включает их содержимое.

Дополнительные Go/Maven-зависимости разрешаются через опубликованные lock-файлы. Неизменённые сторонние уведомления не удаляются ради переименования продукта. При изменении зависимостей сопровождающий обязан повторно проверить их условия и комплект уведомлений.

Исходники Android-основы поставляются в актуальном модифицированном виде в `android/`, вместе с исходным авторством и лицензией. Полный исторический архив WireTurn отдельно не включён: он содержит функции, которые не входят в этот выпуск TUNA.

## Границы проверки

HEV/qWDTT берутся из checksum-pinned WireTurn APK; точная побитовая воспроизводимость этих upstream-бинарников не установлена. Cronet source chain закреплён, а коллекция Chromium notices взята из соответствующего дерева, но не является доказанным минимальным списком только слинкованных объектов. Полный юридический аудит всех транзитивных Go/Maven/Chromium компонентов не заявляется.

Опциональные GeoSite/GeoIP скачиваются по запросу и не включены в APK/EXE. У каждой базы свои условия; лицензия TUNA не разрешает автоматически их дальнейшее распространение. Личные профили, ключи, подписки и пользовательские проекты не являются частью публичной поставки.
