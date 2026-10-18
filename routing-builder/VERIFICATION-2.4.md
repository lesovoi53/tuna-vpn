# Routing Builder 2.4: локальная приёмка 18 октября 2026

Артефакт: `dist/TunaRoutingBuilder-v2.4.exe`, Windows x64, один файл,
14 264 854 байта. SHA-256:

```text
749fd362975d702163135e70debf848fcb6c97a36dfce25b7f77599d63eb01cf
```

Python 3.12.14, PyInstaller 6.16.0 (hooks 2026.8), Windows 11.
Использовано имеющееся окружение соседнего `TunaRoutingBuilder/.build-env`;
зависимости не скачивались. Команда сборки:

```powershell
./build-exe.ps1 -PythonExe ../..\TunaRoutingBuilder\.build-env\Scripts\python.exe
```

Скрипт находит Tcl/Tk своего Python и останавливает сборку при ошибке runtime.
В песочнице Tcl не инициализировался даже с явным путём; GUI и сборка выполнены
локально с одобренным повышением разрешений. Код приложения для этого не менялся.

Пройдено:

- `python -m unittest test_core test_policy test_geo test_routing_sources test_dns_pools test_dns_recovery -q`: 45 тестов, OK.
- `python test_dns_gui.py`: реальные скрытые виджеты; лимит 8, перестановка,
  независимость групп, hosts; все пресеты аварийного DNS и bootstrap;
  ввод частного DoH без pin и IPv6 TCP bootstrap; system/disabled/inherit;
  загрузка, правка и экспорт/импорт без потери новых полей.
- `python test_paste_gui.py`: вставка кнопкой, русская раскладка, контекстное
  меню, пустой буфер и режим APK; системный буфер не изменяется.
- `python app.py --self-test --self-test-report source-smoke.json`: OK.
- Только EXE скопирован в отдельную папку, очищены TCL_LIBRARY, TK_LIBRARY,
  PYTHONPATH. `TunaRoutingBuilder-v2.4.exe --self-test --self-test-report frozen-smoke.json`:
  exit 0, `ok=true`, `frozen=true`; реальные Tk/CustomTkinter, DoH без IP,
  system/disabled, ручной TCP IPv6 bootstrap, TUNA roundtrip, реальные
  save/open project обработчики с временным синтетическим файлом,
  постоянный путь GEO-кэша вне распакованного EXE.

Отчёты self-test сохранены в игнорируемом `build/acceptance/`.
PyInstaller сообщил ожидаемое предупреждение о macOS AppKit в darkdetect;
Windows GUI/self-test проходит. Его список optional/conditional imports
проверен; отсутствующие PIL/typing_extensions не нужны текущему интерфейсу.

Контракт: отдельные remoteEmergency/domesticEmergency, режимы
system/resolver/disabled; custom resolver следует маршруту своей группы.
Системный резерв всегда прямой. Bootstrap отсутствует для наследования
или имеет system/resolver; ручной вариант только TCP literal IP DIRECT,
без собственного непустого bootstrapIp. DoH без pin сохраняет HTTPS URL.
Явный disabled сохраняется; старый документ без dns не переписывается парсером.
Экспорт Happ блокирует потерю расширенных DNS-настроек.

Не проверено: реальная доступность DNS в сети, Android/TUN, системный
resolver bridge, обновление Android подписки, запуск на другом чистом ПК,
визуальная ручная приёмка большого окна и длительная работа. EXE не имеет
Authenticode-подписи. Публикация не выполнялась, пользовательские проекты,
профили и настройки не изменялись.
