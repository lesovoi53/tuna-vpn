<div align="center">

# TUNA VPN

**Android-клиент с несколькими сетевыми ядрами и собственным редактором маршрутизации для Windows.**

[![Release](https://img.shields.io/github/v/release/lesovoi53/tuna-vpn)](https://github.com/lesovoi53/tuna-vpn/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-86c77b)](docs/user/quickstart.md)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

[Скачать](https://github.com/lesovoi53/tuna-vpn/releases/latest) · [Начать пользоваться](docs/user/quickstart.md) · [Генератор правил](docs/routing-builder.md) · [Разработчикам](docs/development/README.md) · [English](README.en.md)

</div>

## Скачать

| Продукт | Готовый файл | Для чего |
| --- | --- | --- |
| **TUNA VPN 1.5** | [Android APK, arm64-v8a](https://github.com/lesovoi53/tuna-vpn/releases/download/v1.5/TUNA-VPN-1.5-arm64-v8a.apk) | Подключения, подписки, URL-test, маршрутизация и DNS |
| **TUNA Routing Builder 2.3.1** | [Windows EXE, один файл](https://github.com/lesovoi53/tuna-vpn/releases/download/v1.5/TunaRoutingBuilder-v2.3.1.exe) | Редактор правил, приложений и DNS; экспорт `tuna://` |
| Проверка файлов | [SHA256SUMS.txt](https://github.com/lesovoi53/tuna-vpn/releases/download/v1.5/SHA256SUMS.txt) | Контроль целостности скачивания |
| Исходники выпуска | [TUNA-VPN-1.5-sources.zip](https://github.com/lesovoi53/tuna-vpn/releases/download/v1.5/TUNA-VPN-1.5-sources.zip) | Публичное дерево и закреплённые upstream-архивы |

Клиент не продаёт доступ и не содержит личных VPN-серверов. Нужны свои профили или доверенная подписка. EXE — **редактор правил**, а не Windows VPN-клиент.

> APK 1.5 — release-сборка без RC и без отладки, но с историческим пакетом `app.tunaturn.android.debug` и прежним сертификатом Android Debug для совместимости обновления. Это не новый production signing key. Перед установкой прочитайте [подпись и обновление](docs/development/release.md). Windows EXE не имеет Authenticode-подписи. Не удаляйте установленное приложение ради обхода ошибки подписи.

## Возможности

- Несколько ядер: Xray, sing-box-extended, Mieru и специализированные транспорты. [Матрица протоколов и ограничения](docs/user/protocols.md).
- Импорт ссылок, файлов и подписок; понятные причины отказа отдельных записей.
- Группы с **URL-test** и защитой от частых переключений. Speedtest как пользовательский режим удалён.
- Правила для доменов, IP, GeoSite/GeoIP и Android-приложений: напрямую, через VPN или блокировать.
- До восьми DNS через VPN и восьми прямых DNS, автоматический или приоритетный выбор, DoH с необязательным bootstrap IP.
- Импорт `tuna://routing/v1`: правила подписки применяются автоматически. Добавляйте только доверенные подписки — они могут менять маршрутизацию и DNS.
- Ограниченное хранение диагностики и настройка блокировки сна. Энергопотребление зависит от протокола, радиосети, нагрузки и настроек; универсальной экономии относительно других клиентов не обещаем.

## Быстрый старт

1. Скачайте APK из Releases, проверьте контрольную сумму и установите поверх совместимой версии.
2. Добавьте профиль или подписку в приложении; проверьте результат импорта.
3. Выберите сервер, нажмите подключение и подтвердите системное разрешение VPN.
4. Для правил откройте Routing Builder, добавьте приложения/сайты/DNS и импортируйте полученную `tuna://`-ссылку в клиент либо разместите её в своей подписке.

Подробности: [первый запуск](docs/user/quickstart.md), [подписки](docs/user/subscriptions.md), [маршрутизация и DNS](docs/user/routing-and-dns.md), [диагностика](docs/user/diagnostics.md).

## Почему ядра называются форками

TUNA не выдаёт Xray, sing-box или Mieru за собственные протоколы. Поверх закреплённых upstream-версий применяются отдельные патчи DNS, запуска, локальной аутентификации и совместимости. Поэтому модифицированные компоненты обозначаются как **fork**, а не как неизменённый официальный бинарник. Отличия, авторы и версии: [ядра и происхождение](docs/development/cores-and-forks.md), [лицензии](docs/licenses.md), [NOTICE](android/NOTICE.md).

## Структура репозитория

```text
android/         Android-приложение, адаптеры, патчи ядер, тесты
routing-builder/ Python/CustomTkinter редактор и тесты
docs/user/       Руководство пользователя
docs/development/ Архитектура, сборка, контракты, выпуск
scripts/         Проверки публичного снимка и источников
```

Исходники внешних проектов закреплены подмодулями и Go lock-файлами. Клонируйте с `--recurse-submodules`; ZIP, автоматически сформированный GitHub, не включает содержимое подмодулей. Готовые бинарники находятся в Releases, а не в Git-истории.

## Разработка и обратная связь

[Сборка](docs/development/build.md) · [Тестирование](docs/development/testing.md) · [Контракт правил](docs/development/routing-contract.md) · [Известные ограничения](docs/known-limitations.md) · [Вклад в проект](CONTRIBUTING.md) · [Безопасность](SECURITY.md)

Для ошибки приложите версию, протокол, действия воспроизведения и обезличенный журнал. **Не прикладывайте рабочие URI, UUID, пароли, ключи и адреса подписок.**

Структура навигации вдохновлена [v2rayNG](https://github.com/2dust/v2rayNG), [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) и [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid): загрузки, руководство, разработка и лицензии разделены. Их дизайн, торговые знаки и код не становятся собственностью TUNA.

## Лицензии

Android-клиент основан на **WireTurn**; сведения об авторах сохранены. Собственные части этого снимка распространяются по [GPL-3.0](LICENSE), если файл не содержит отдельной лицензии. Внешние компоненты сохраняют свои лицензии (включая MIT, MPL-2.0, Apache-2.0 и BSD). См. [полный обзор](docs/licenses.md). Наличие файла в сборке не означает смену его лицензии.
