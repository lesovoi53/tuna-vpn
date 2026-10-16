# Контракт TUNA Routing v1

Источники истины: `routing-builder/policy.py`, `android/.../domain/AppRoutingSubscription.kt`, `ProfileRouting.kt` и классы `data/kernel`/`domain` для split DNS и Happ. Перед расширением формата находите фактический парсер поиском `tuna://routing/v1`.

## Передача

```text
tuna://routing/v1?data=<base64url UTF-8 JSON, без обязательного padding>
```

Минимальный демонстрационный документ (показан декодированным; это не серверный профиль):

```json
{
  "version": 1,
  "policy": {
    "Name": "Example routing",
    "GlobalProxy": true,
    "RouteOrder": "block-proxy-direct",
    "DomainStrategy": "AsIs",
    "DirectSites": ["domain:example.com"],
    "DirectIp": ["192.0.2.0/24"],
    "ProxySites": [],
    "ProxyIp": [],
    "BlockSites": [],
    "BlockIp": [],
    "FakeDNS": false,
    "UseChunkFiles": false
  },
  "apps": {"mode": "bypass", "packages": ["com.android.chrome"]},
  "dns": {
    "remoteServers": [{"transport": "DOH", "endpoint": "https://dns.google/dns-query", "bootstrapIp": null, "route": "VPN"}],
    "domesticServers": [{"transport": "UDP", "endpoint": "1.1.1.1", "route": "DIRECT"}],
    "remoteSelectionMode": "auto",
    "domesticSelectionMode": "priority",
    "timeoutMs": 5000
  }
}
```

Для рабочего экспорта используйте `policy.document`/`policy.tuna_link`, а не конкатенацию строк: они валидируют документ и согласуют legacy DNS-поля. Не копируйте адреса из примера как обещание доступности конкретной сети.

## Поля и ограничения

- `version`: поддерживаемая схема 1. Неизвестную версию отвергать с причиной.
- `policy`: переносимые Happ-подобные поля. Domain rules: `domain:`, `full:`, `keyword:`, `regexp:`, `geosite:`; IP/CIDR и `geoip:` — в IP-массивах.
- `RouteOrder` задаёт порядок block/proxy/direct; `GlobalProxy` — исходный маршрут для не совпавшего трафика. Пустые списки всё равно могут менять DNS/маршрут: длинная ссылка не означает наличие приложений.
- `apps` необязателен. `mode=bypass` — перечисленные приложения без VPN; `mode=vpn` — выбранные через VPN. До 100 уникальных Android package names. Неустановленные пакеты можно хранить; не удалять их при импорте только из-за отсутствия на устройстве.
- `dns` необязателен. В каждой группе 1–8 серверов, `auto` либо `priority`; timeout 1000–10000 мс. UDP/TCP/DOH; DOT зависит от выбранного router, текущий общий Xray-путь его не принимает.
- `bootstrapIp` для DoH необязателен; это не отключение TLS-проверки. Endpoint может быть частным HTTPS URL, а не только preset публичного провайдера.
- `hosts` при наличии согласуется с `policy.DnsHosts`. Это статическое соответствие имён и IP, не ещё одна DNS-группа.
- До 500 правил; декодированный routing payload ограничен 256 KiB. HTTP-источники редактора дополнительно ограничены размером (350 KB). Проверяйте предел конкретного импортного пути.

## Нормализация и потери

При TUNA-экспорте Builder создаёт копию: отключает FakeDNS/UseChunkFiles и убирает неизвестные Happ-поля с предупреждением. Исходный проект остаётся пригодным для отдельного Happ-экспорта. Не превращать это в молчаливое удаление произвольных VLESS transport-полей: другой формат/владелец.

Happ не выражает Android apps и расширенные TUNA DNS pools. Если экспорт не может представить их без потери, программа должна сообщить ограничение, а не обещать эквивалентный результат.

## Подписка и локальное состояние

Строка TUNA routing может передаваться отдельно от строк серверов в подписке. Поддерживается также структурированный subscription container; синтетические образцы — `android/testdata/subscription-lab/`. Добавление/обновление подписки применяет достаточные правила автоматически, без отдельного диалога согласия. Поэтому подписка — доверенный источник конфигурации, а не только список адресов.

Применение включает выбранный режим списка приложений и дополняет его, сохраняет личные дополнения по merge-политике. Изменение активной VPN-конфигурации требует корректного применения/reconnect; показывать сохранение как доказательство уже переключённого DNS нельзя.

Обязательные round-trip тесты: импорт → сохранение → перезапуск → экспорт; обновление подписки; пустые и максимальные списки; неизвестные поля/версия; duplicate packages; отсутствующее приложение; payload выше лимита; malformed URL/Base64/JSON; разные источники и локальные overrides.

