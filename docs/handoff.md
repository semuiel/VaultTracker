# Передача VaultTracker следующему техническому администратору

Снимок исходников: **0.17.77-preview-folia26, 19.10.2026**. Репозиторий включает Java, все три frontend-приложения, отдельный Python-сервис, тесты, шаблоны YAML/JSON, Gradle Wrapper, SQL и документацию. Исходники FLEXITY нужно получить из его отдельного репозитория. Здесь нет уже объединённого плагина.

## 1. Порядок знакомства

- [AGENTS.md](../AGENTS.md) → [архитектура](architecture.md).
- Для объединения — [границы FLEXITY](flexity-integration.md).
- Для точного поиска классов/тестов — [индекс файлов](code-index.md).
- Для конкретной задачи — документ из [указателя](README.md), нужный handler/service и его тест.

Не нужно заново скармливать нейросети весь репозиторий. Сначала определить владельца данных и точку входа. Архитектурную карту править вместе с изменением соответствующего контракта.

## 2. Получение и сборка на чистом компьютере

```sh
git clone https://github.com/semuiel/VaultTracker.git
cd VaultTracker
```

Основной toolchain — JDK 25. Gradle Wrapper 9.1.0 входит в Git. Java-зависимости указаны в `build.gradle.kts`; проект не требует копии рабочих JAR для компиляции адаптеров FLEXITY. Первый запуск требует доступа к Maven Central и репозиторию PaperMC. `--offline` применим только после наполнения Gradle cache.

Windows PowerShell:

```powershell
.\gradlew.bat test shadowJar
```

Linux:

```sh
sh gradlew test shadowJar
```

Готовый плагин — `build/libs/VaultTracker-0.17.77-preview-folia26.jar`. Plain JAR с суффиксом `plain` не устанавливать вместо shadowJar. `smokeJar` — отдельный тестовый плагин для изолированного стенда, не для рабочего сервера.

`-Plegacy=true` переключает compile API на Paper 1.21.11 и toolchain на Java 21. Нельзя выдавать этот JAR за сборку Folia 26; наличие переключателя не означает повторной проверки legacy-ветки при этой передаче.

## 3. Python-сервис: тесты, упаковка и свежая установка

Python 3.11+; рабочая переносимая установка ранее проверялась на Python 3.13. Отдельное venv позволяет не менять Python других приложений.

Из корня проекта, Windows:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r donations/requirements.txt
.\.venv\Scripts\python.exe -m unittest discover -s donations -p "test_*.py"
.\.venv\Scripts\python.exe tools/package-payment.py
```

Linux: соответствующие команды через `.venv/bin/python`. Python-тесты используют временные SQLite и подставные провайдеры, настоящие деньги/сообщения не создают.

Упаковщик включает явный список модулей, scripts, документацию и **config.example.json**. Выход: `build/distributions/payment-service-0.17.77.zip` и контрольная сумма `.zip.sha256`. В ZIP нет `config.local.json`, SQLite, ключей, runtime или venv.

Для **новой** установки распаковать сервис в отдельную папку, установить requirements в её venv, скопировать `config.example.json` в закрытый `config.local.json`, заполнить значения локально. Запускать `python service.py --config config.local.json` из этого venv. `SETUP.cmd`/`START.cmd` предназначены для ранее подготовленной переносимой Windows-папки с `runtime/python.exe`: runtime не поставляется в исходниках.

Для **обновления существующей** установки сохраняются config.local.json, donations.sqlite и runtime/lib. Не запускать configure/SETUP для обновления кода. Сервис один: два процесса не должны одновременно выполнять provider polling/settlement. [Подробная инструкция](../donations/README.md), [API сайта](../donations/BALANCE-API.md).

`--check` и `--check-order` не начисляют деньги, но обращаются к настоящему FunPay: это эксплуатационные проверки, а не offline unit-тесты.

## 4. Web-проверки без production

```sh
npm install
npx playwright install chromium
npm run test:web
```

Нужен Node.js 20+. Версия Playwright зафиксирована в `package.json`. Три fixtures поднимают локальный HTTP на свободном порту, подставляют Telegram initData и ответы API. Проверяют фильтры, русские имена/материалы, ACL revocation, XSS-safe текст, графики и мобильную ширину. Скриншоты — в `work/`, в Git не входят.

Для системного Chrome вместо загруженного Chromium: PowerShell `$env:PLAYWRIGHT_CHANNEL='chrome'` или переменная среды `PLAYWRIGHT_CHANNEL=chrome`. Запуск fixture не должен читать `plugins/VaultTracker` с рабочего сервера.

## 5. Какие настройки и данные получить от владельца отдельно

Этот публичный Git — исходники и шаблоны, а не перенос работающего сервера вместе с секретами.

| Приватный компонент | Что нужно передать и сохранить |
| --- | --- |
| Плагин | Действующие config.yml, guard.yml, telegram.yml, telegramchat.yml, telegram-menu.yml, donations.yml, item-emojis.yml и при наличии telegram-proxies.txt |
| Guard | Согласованный backup guard-data.mv.db: UUID↔Telegram, Root/роли, друзья, настройки, события, ЛС, заявки и очереди |
| Каталог | cache-<server-id>.mv.db; прежний server-id; настройки и backup MariaDB ресурсов, если включена |
| Telegram transport | telegram.offset, telegramchat-*.offset, expiry/list properties, отдельные файлы модерации/новостей |
| Обе аналитики | telegram-analytics.mv.db и bot-analytics.mv.db целиком; HMAC keys хранятся внутри, отдельно пересоздавать их нельзя |
| Платежи | config.local.json, согласованная donations.sqlite с WAL/SHM, runtime/lib либо сведения о venv; сохранить все операции/idempotency/outbox и настройки Root |
| Внешние интеграции | Рабочие версии/конфиги FLEXITY, Bastion, LuckPerms, VotifierPlus и его RSA keys; доступ к их исходникам, где нужен |
| CoreProtect | Адрес/имя/prefix и отдельный SELECT-only пользователь; backup самой базы согласовывается отдельно, viewer не выполняет миграций CoreProtect |
| Публикация | Действующий HTTPS reverse proxy, DNS/TLS, маршруты /coreprotect/, /telegram-stats/, /bot-analytics/ |

Не помещать этот приватный комплект в Git, issue, PR, Telegram-пост или frontend. Не копировать активную H2 или только один активный SQLite-файл как согласованный backup. SQLite можно снимать штатным backup API; простой файловый backup сервиса делать при остановке **только сервиса**. Для копирования H2 запланировать штатное окно остановки владельца базы либо использовать подходящий проверенный механизм H2 backup; не читать активную H2 второй embedded JVM.

## 6. Подключения и права

| Интерфейс | По умолчанию / границы |
| --- | --- |
| Java → Python | 127.0.0.1:18763, Bearer api_key; финансовый API принимает только localhost |
| Сайт → Python | Другой website_api_key, только разрешённые wallet/add/spend; ключ живёт в backend сайта |
| VotifierPlus | Его собственный TCP listener, обычно 8192; все три сайта могут пользоваться одним публичным RSA-ключом/портом |
| Прямой callback MinecraftRating | Отдельный опциональный старый transport `/voting/minecraft-rating`; не заменяет голоса конкретного сервера VotifierPlus |
| Три Mini App | Один 127.0.0.1:8194, снаружи только HTTPS reverse proxy с сохранением префикса |
| CoreProtect SQL | Отдельный SELECT-only пользователь. Учётная запись основного CoreProtect с write grants не подходит |

Пример маршрутов proxy — [deployment/Caddyfile.example](../deployment/Caddyfile.example); это фрагмент для адаптации, не замена действующей конфигурации сайта. На свежей установке `coreprotectWeb`, `telegramAnalytics`, `botAnalytics` выключены. Для Telegram-статистики нужен явный allowlist чатов. `accessChatId` требует текущих administrator/creator прав пользователя в Telegram; дополнительно проверяется роль VaultTracker. CoreProtect: Admin/Super/Root. Обе аналитики: только Root. Проверки остаются на сервере при прямом вызове API.

## 7. Проверенное состояние установки на 18.10.2026

Это последняя выполненная эксплуатационная проверка, не обещание неизменности сервера после этой даты. Во время Git-передачи 19.10 production не проверялся и не изменялся.

- Пользователь установил JAR 0.17.77; SHA256 совпал: `61E0B459DC5AD47A3F6D93743A6ECE09155837F51AAAE22A7E0693D6428481A8`.
- Платёжный сервис обновлён отдельно; его остановленная полная копия сделана до замены файлов. Minecraft не останавливался и не получал reload/команд.
- Миграции дважды прошли на отдельной SQLite-копии, сохранив старые значения 19 таблиц. После запуска quick_check=ok; прежние 134 финансовые записи сохранились. Реальных тестовых выплат не создавали.
- На тот момент три площадки включены, каждая по 2.00 монеты; призы ТОП-3 300/200/100, tiePolicy full. Эти рабочие значения находятся в SQLite, не в config.example.json.
- Новый bot-analytics маршрут применён через API Caddy с ETag без остановки proxy; общие страницы/другие viewers проверены. Публичный HTTPS отдавал правильный HTML, API без авторизации 403, POST 405.
- bot-analytics.mv.db создана, приложение подхватилось в ходе фонового старта Telegram. Повторный PlugMan/reload для этого не выполнялся.
- Старые backups/служебные файлы вынесены владельцу в отдельный архив. Рабочие базы и текущие настройки сохранены. Пути приватных backup находятся в локальном эксплуатационном отчёте владельца, не нужны для сборки из Git.

Пользователь ранее загрузил JAR через PlugMan. Это описание факта, **не рекомендуемая процедура дальнейшего обновления**. Объединённую версию испытывать на отдельном стенде, затем устанавливать в штатное окно.

## 8. При объединении с FLEXITY

Сначала составить mapping существующих хранилищ/прав/команд на владельцев в объединённом плагине. Не переносить всё в один main-класс. Сохранить финансовый backend и его транзакции, UUID, durable очереди, прежние offsets и HMAC keys. В частности, объединение Java-плагинов не означает перенос кошелька в MariaDB FLEXITY.

Рекомендуемая очередность: общий lifecycle/schedulers → явные интерфейсы вместо reflection → единый UI/chat formatter → роли/команды без расширения прав → данные/миграции → интеграционные проверки. Конкретные точки и тесты: [flexity-integration.md](flexity-integration.md).

## 9. Проверки перед передачей

Фактические результаты текущей сборки и список включённых файлов зафиксированы в [handoff-verification.md](handoff-verification.md). JDK/Python/Node/runtime не копируются в репозиторий. Старые release-документы описывают прежний этап; не воспринимать их локальные пути или «установка ещё не выполнена» как текущее состояние production.
