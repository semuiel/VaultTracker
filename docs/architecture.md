# VaultTracker: актуальная карта кода

Версия 0.17.77, сверено при передаче 19.10.2026. Все Java-классы ниже находятся в `src/main/java/ru/vaulttracker/`, если путь не указан отдельно. Эта карта — вход для небольших задач: читать нужный модуль, его контракт и ближайшие тесты, а не весь проект. [Полный индекс файлов](code-index.md), [настройка и передача](handoff.md), [границы FLEXITY](flexity-integration.md).

## 1. Состав и жизненный цикл

| Вход | Ответственность |
| --- | --- |
| `VaultTrackerPlugin.onEnable/onDisable` | Конфиги, Guard, каталог, storage, listeners/команды, голосовая outbox, Telegram/мост, завершение worker-ов |
| `VaultTrackerPlugin.replaceTelegram` | Один владелец Telegram lifecycle под `telegramLock`; объединённый или отдельный transport чат-моста |
| `TelegramBotService` | Polling, маршрутизация update, доставка уведомлений и pending-голосов; создание/закрытие всех Mini Apps |
| `TelegramChatBridge` | Telegram ↔ игровой чат и объявления событий; при общем токене не создаёт второй polling |
| `donations/service.py:main/Service` | Самостоятельный процесс: HTTP API, FunPay polling, reconcile голосов, месячный scheduler |
| `src/main/resources/plugin.yml` | JavaPlugin entrypoint, Folia support, команды, permissions и softdepend. Версию/API при сборке задаёт `build.gradle.kts` |

`config.yml` задаёт `server-id`, внешнюю MariaDB и debounce. `guard.yml` — основной Root и настройки Guard. `telegram.yml` — основной бот, новости, moderation и три Mini App. `telegramchat.yml` — мост, топики и поддержка. `telegram-menu.yml` — тексты/замены. `donations.yml` — Java-клиент локального сервиса. `item-emojis.yml` — словарь значков. Секреты не являются исходниками.

## 2. Хранилища и игровой каталог

| Путь изменения | Файлы / инварианты | Ближайшие тесты |
| --- | --- | --- |
| Регистрация/изменение блока | `VaultTrackerPlugin`, `ChestLayout`, `BlockKey`, `ItemCounter`, `Snapshot`; таблички и рамки, обе половины сундука | `BlockStorageTest`, `ChestTest`, `FrameMarkerTest`, `NestedItemsTest` |
| Индексы ресурсов | `Catalogue`, `OwnResourceSearch`; UUID владельца, claims, суммы, индекс по предмету | `CatalogueTest`, `OwnResourceSearchTest` |
| Постоянное хранение | `StorageEngine` → `LocalStore` H2 + необязательный `RemoteStore` MariaDB | `StorageTest` |
| UUID/старые ники | `GuardService.knownPlayerUuid/knownPlayerName`, `LocalStore.loadAndResolve`; ambiguous name не превращается в чужой UUID | `GuardServiceTest`, `StorageTest` |
| Поиск/топ | `TopItemCommand`, `TopItemTabCompletion`, `FindTabCompletion`, `RussianItems`, `ResourceGroups`, `ItemSorting`, `ItemAmount` | `TopItemTest`, `TopItemTabCompletionTest`, `ItemSortingTest` |
| Компас/статистика | `SearchCompass`, `PlayerStatistics`, `OfflineInventory`; живые inventories только на entity scheduler | `PlayerStatisticsTest`, `OfflineInventoryTest` |

`StorageEngine` синхронизирует только dirty/resolved строки; локальный cache не равен Guard. `max-vaults-per-player` сейчас не ограничивает число мест. Массовую сверку не запускать на live ради теста.

## 3. Telegram: вход, экраны и права

`TelegramApi` — Bot API, HTTP/proxy/retry, parse update и исходящие результаты. `TelegramConfig`/`TelegramChatConfig`, `TelegramProxies`/`Socks5SocketFactory` — загрузка соединения. `TelegramWorkQueue` последовательно обрабатывает работу одного Telegram ID, разные ID могут идти независимо.

`/start` или `/menu` → `TelegramBotService` → `TelegramGuardMenu` → `CabinetMenu.mainMenu`. Callback `vg:` → `TelegramActionRegistry` → `*Menu.registerActions()` → сервис. Состояние ввода принадлежит общей сессии; `TelegramMenuSection` передаёт меню необходимый контекст. `TelegramPrivateMenu` и `TelegramCommands` обслуживают поиск и страницы.

| Экран | Где менять |
| --- | --- |
| Главное меню, кабинет, привязка, донаты, Premium | `CabinetMenu` |
| Ресурсы/топ/друзья/статистика/ЛС/настройки | `ResourceMenu`, `FriendsMenu`, `StatisticsMenu`, `MessagesMenu`, `SettingsMenu` |
| Обычный администратор | `AdminMenu`, capability-проверки `GuardService`, действия `TelegramModeration` |
| Super и Root | `SuperAdminMenu`; Root → команда/роли, аналитика, экономика, техническое управление |
| Root-кнопки кабинета | `CabinetMenu.home`: «Root» и «Мой персонаж» только Root; `selfActions` тоже проверяет Root |
| Голоса/Free-монеты | `VotingMenu`, `FreeCoinsMenu` |
| Тексты/значки/чат-формат | `TelegramMenuText`, `TelegramItemIcons`, `TelegramEmojiMarkup`, `TelegramChatText`, `TelegramChatFormat` |

Авторизация находится в `GuardService.requireAdmin/requireSuper/requireRoot/requireCapability` и handlers, а не только в видимости кнопок. Основной Root — `guard.yml:superAdminUserId`; дополнительные Root — `guard_roots`; обычные администраторы — `telegram.yml:adminUserIds`. Список Root изменяет только основной Root. Minecraft OP и Telegram role не взаимозаменяемы. `/unlink` требует непосредственно `Player.isOp()`.

Root → Аналитика содержит **CoreProtect, Telegram-статистику и Аналитику бота**; каждый viewer имеет дополнительную проверку текущего Telegram administrator/creator в `accessChatId`. [Тесты навигации и ACL](../src/test/java/ru/vaulttracker/AdminNavigationTest.java), `CabinetRolesTest`, `RootConsoleMenuTest`.

## 4. Сообщения, поддержка и модерация

| Поток | Владелец / данные |
| --- | --- |
| `/tg` / диалог бота | `PlayerDirectMessages` → `GuardService.directMessage`; UUID, история и unread в guard-data; `MessagesMenu`, доставка через `TelegramBotService` |
| Игровой чат ↔ Telegram | `TelegramChatBridge`, `LinkedChatIdentity`, `FlexityPremiumStyle`; получатель может отключить `telegram_game_chat` |
| Жалоба/предложение/баг/разбан | `SupportMenu` → `SupportService` → `GuardService` → `SupportRepository`; `SupportTicket`, `SupportTopics` |
| Durable отправка поддержки | `support_requests`, `support_unban`, `support_delivery`; сохранять до HTTP, прогресс вложений после каждого результата; callbacks `vs:`/`vc:`/`vu:` |
| Уведомления автору жалобы | `SupportService` + `SupportRepository`; взятие/закрытие/повторное открытие только жалоб на игроков, retry при сбое |
| Новости и `/nr` | `NewsAnnouncements`, `TelegramApi.Incoming.poll`, `TelegramModerationRepository`; последняя исходная ссылка сохраняется |
| `/offtop`, `/unofftop` | `TelegramTopicModeration` раньше моста; текущие Telegram-права, ограничения только `(chat,topic,target)` до срока |
| Временные Telegram-ответы | `TelegramListExpiry`; durable удаление команды/первого подтверждения, обычный enforcement молча |
| Чат-репорты/теги/дети | `TelegramReports`, `TelegramTagManager`, `ChildPlayers` |
| Root логи/консоль | `RootLogDocument`, `SuperAdminMenu`, `TelegramModeration`; история Root-команд в Guard |

Тесты: `SupportDeliveryTest`, `UnbanSupportTest`, `SupportMenuTest`, `ComplaintNotificationsTest`, `NewsAnnouncementsTest`, `TelegramTopicModerationTest`, `TelegramApiTest`, `TelegramResilienceTest`. Не считать строку ответа игрового API доказательством подтверждённого наказания — см. ограничения аналитики.

## 5. Деньги, Premium и голосования

Java: `DonationClient` — единственный HTTP-клиент финансового сервиса; `DonationPremium` — синхронизация абсолютных сроков с LuckPerms и ack после save. `PremiumProduct` — UI-контракт; authoritative цены — Python `premium_products.py`. `FreeCoinsMenu` использует уже существующий связанный UUID, не создаёт новую привязку.

| Python-модуль | Ответственность |
| --- | --- |
| `service.py` | HTTP handlers, auth/localhost, polling/reconcile, единственный месячный scheduler |
| `funpay_provider.py` | Проверка заказа/продавца/количества/оценки через FunPayAPI; неизвестные ответы отклонять |
| `ledger.py` | Кошельки, entries, операции/идемпотентность, заказы/возвраты/бонусы, Premium outbox, аудит |
| `premium_products.py` | Цены и продукты `premium` / `premiumlite`; разные сроки/таблицы |
| `link_rewards.py` | Однократная награда на UUID (`link_reward_claims`), настройки суммы и аудит |
| `vote_providers.py` | Точные Votifier serviceName/проверка HTTP callback, без баланса |
| `voting.py` | Providers, vote_events, pending UUID, credited/cooldown, reward и notifications в одной транзакции |
| `vote_schema.py` | Versioned миграции голосований |
| `vote_monthly.py` | ТОП-3, immutable закрытый месяц, competition rank 1,1,3 / 1,2,2; full/split |
| `vote_reconcile.py` | Только поддержанная история MinecraftRating; не универсальная история всех Votifier-сайтов |
| `configure.py`, `check_local.py` | Подготовка/диагностика сервиса; не выполнять configure поверх live ради обновления |

Деньги — целые сотые монеты. Общий wallet в **donations.sqlite**, в его SQLite BEGIN IMMEDIATE фиксируются начисление, idempotency и уведомление. Другие процессы не пишут эту базу напрямую. Идентичность игрока — UUID. `api_key` и отдельный ограниченный `website_api_key` не являются Telegram/FunPay ключами.

Голос конкретного сервера: VotifierPlus `VotifierEvent` → `VaultTrackerPlugin.registerVotifier` → `GuardService.server_vote_outbox` → `deliverServerVotes` → `/voting-record`. Ack только после результата; Python определяет provider по точному serviceName. Порядок меню: MinecraftRating → Top-Minecrafter → HotMC. Для начисления нужен однозначно известный UUID. `vote_eligibility` ограничивает credited минимум 24 ч на UUID/provider; старое последнее credited лениво восстанавливает таймер. Более длинный настроенный интервал сохраняется. Cooldown не меняет баланс, рейтинг и уведомление об успехе.

`vote_notifications` доставляет Java `TelegramBotService`, ack после отправки. Root-переключатели/награды и месячные призы хранятся в SQLite; `config.example.json` лишь начальные значения. Старые закрытые месяцы не доплачивать, прежние credited не переписывать. Награда за привязку — также UUID/транзакция, не Telegram ID. [Контракт голосов](voting-eligibility.md), [сервис](../donations/README.md), [API сайта](../donations/BALANCE-API.md).

Тесты Java: `DonationPremiumTest`, `DonationMenusTest`, `VotingMenuTest`. Python: все `donations/test_*.py`, особенно eligibility/monthly/link_rewards/ledger/service_http.

## 6. Три Mini App

`TelegramBotService.openWebApps/closeWebApps` владеет общим `TelegramWebRuntime` на localhost (`coreprotectWeb.bindHost/bindPort`, по умолчанию 127.0.0.1:8194). Одно bounded HTTP pool, отдельные backend/базы/session/cache каждого viewer. Reverse proxy сохраняет префикс пути и публикует HTTPS; новый внешний backend-порт не нужен.

`TelegramMiniAppAuth` проверяет Telegram initData HMAC, `CoreProtectWebAccess` — короткие sessions и текущий Root/Admin + Telegram admin ACL. Auth передаётся header, не URL; content/logging не должны утекать в access log. Отозванные права действуют с ограниченным ACL cache TTL.

| Viewer | Backend / frontend / данные | Документ |
| --- | --- | --- |
| CoreProtect | `CoreProtectViewer`, `CoreProtectWebConfig`, `CoreProtectWebServer`, `CoreProtectQuery`, `CoreProtectLookupSource`, `CoreProtectMariaDbSource`; `coreprotect-web/` | [CoreProtect](coreprotect-viewer.md) |
| Сообщения Telegram | `TelegramAnalyticsConfig/Event/Service/Repository/Query/Queries/Web`; `telegram-stats/`; отдельная H2 | [Telegram-статистика](telegram-analytics.md) |
| Использование бота | `BotAnalytics`, `BotAnalyticsCatalog/Config/Service/Repository/Query/Queries/Web`; `bot-analytics/`; отдельная H2 | [Аналитика бота](bot-analytics.md), [каталог](bot-analytics-catalog.md) |

CoreProtect: GET session/capabilities/materials/players/lookup, prepared SELECT, отдельный SELECT-only пользователь MariaDB, проверка grants/таймауты/LIMIT. `players.js` читает online/offline имя/историю из co_user/co_username_log; `materials.js` переводит выбор/русский поиск в Material ID. Галочки отправляют только включённые фильтры, координаты необязательны. `rollback.js` строит только копируемый текст native-команды после успешного поиска; приложение ничего не откатывает.

TelegramAnalytics: allowlist чатов, метаданные без текста, входящие updates и собственные успешные исходящие сообщения (включая игровой мост), edited/duplicate не прибавляют сообщений; боты не увеличивают число человеческих авторов. Названия тем сохраняются из доступных событий/настроек, полной прошлой истории Bot API не даёт.

BotAnalytics: контекст update/стабильные action IDs из `TelegramActionRegistry`, явные outcome hooks, bounded queue и отдельный worker. HMAC identities, события/агрегаты/coverage сохраняются бессрочно; нет сырых сообщений, кодов оплаты или текста консольных команд. Наблюдаемые подтверждения отделены от попыток. До включения статистики нет. Обе аналитики не заменяют друг друга.

Тесты: `CoreProtectWebTest`, `CoreProtectMariaDbSourceTest`, `TelegramAnalyticsTest`, `TelegramAnalyticsOutgoingTest`, `TelegramAnalyticsWebTest`, `BotAnalyticsTest`, `BotAnalyticsWebTest`; `src/test/web/*.cjs`.

## 7. Данные и потоки при изменениях

| Хранилище | Владелец и содержимое |
| --- | --- |
| `cache-<server-id>.mv.db` | `StorageEngine/LocalStore`: локальные snapshots и dirty; server-id не менять при переносе |
| Внешняя MariaDB ресурсов | `RemoteStore`: необязательный общий каталог, отдельна от CoreProtect |
| `guard-data.mv.db` | `GuardService`: аккаунты/UUID, роли, друзья, настройки, ЛС, статистика, обращения и delivery/outbox |
| `telegram-moderation.mv.db`, `telegramchat-moderation-*.mv.db` | Новости и topic-модерация соответствующего transport |
| `telegram-analytics.mv.db` | Метаданные чатов, агрегаты и собственный HMAC key |
| `bot-analytics.mv.db` | Продуктовая аналитика и собственный HMAC key |
| `donations.sqlite` + WAL/SHM | Только Python-сервис: деньги, заказы, голоса, настройки, idempotency, outbox |
| `telegram.offset`, `telegramchat-*.offset`, expiry properties | Состояние transport, update offsets и durable удаление |
| База CoreProtect | Сторонняя: viewer имеет только SELECT; rollback — ручная отдельная операция |

Снимок SQL/HTTP/строки допустимы на worker. `Player`, inventories, блоки и мир — через entity/region scheduler; общие команды — global scheduler, соседний сундук может относиться к другому Folia-региону. Не блокировать игровой поток ожиданием Telegram/FunPay/SQL. Закрывать executors/pollers/listeners один раз.

## 8. Работа нейросети

Начать с `AGENTS.md`, этой карты и ближайших тестов. При задаче на интерфейс не перечитывать storage/все меню. При добавлении маршрута или владельца данных обновить соответствующий раздел карты. Для объединения FLEXITY читать [контракты адаптеров](flexity-integration.md), не угадывать чужую реализацию. Не запускать второй экземпляр с production Telegram/БД и не выполнять Minecraft stop/restart/reload без отдельного поручения владельца.
