# Объединение VaultTracker и FLEXITY: существующие границы

Это описание кода VaultTracker 0.17.77. Исходники самого FLEXITY находятся отдельно; его внутренние классы ниже — **ожидаемые существующими адаптерами контракты**, а не гарантия для любой версии FLEXITY. Перед объединением сверить с передаваемой версией FLEXITY. Изменений на основном сервере ради этой проверки не делать.

## Существующие подключения

| Файл VaultTracker | Какой контракт используется | Что сохранить при объединении |
| --- | --- | --- |
| `GameMessages` | Plugin `flexity`, `org.examples.flexity.ui.Ui.INSTANCE`, message(String,boolean), getText/getAccent/getDanger/getWarning | Общий formatter Adventure Component, цвета/ссылки/тон; fallback палитра при недоступной интеграции |
| `LinkedChatIdentity` | bootstrap.chatFormatter, serviceRegistry.getSettingsService/getDatabaseService, cache и SELECT flexity_settings(custom_prefix,nick_color) по UUID; старый format(boolean,String,Component,String) | Оформлять **связанного Minecraft-игрока**, не Telegram display name; не создавать искусственные игровые chat events |
| `FlexityPremiumStyle` | Современный format с nick/title/theme, settings.readAsync(UUID), visible(permission callback), StyleColors.paint | Сохранять выбранные стили/градиенты и проверку разрешений, включая offline LuckPerms |
| `FlexityFreeze` | bootstrap.serviceRegistry.getPunishmentService, freezePlayer/unfreezePlayer(UUID target,UUID actor) → CompletableFuture<Boolean> | Использовать постоянный punishment service и его live cache; отдельный локальный freeze flag приведёт к рассинхронизации |
| `FlexityVanish` | serviceRegistry.getVanishService.getVanishedPlayers → Set UUID | Соблюдать видимость в списках/админ-интерфейсах, возвращать snapshot |
| `FlexityPlaytime` | Executor команды playtime содержит PlaytimeService, getPlaytimeAsync(UUID) | Сохранять единый источник наигранного времени; не складывать его с повторным vanilla-счётчиком |
| `DonationPremium` | LuckPerms API, группы premium/premiumlite, absolute expiry, saveUser перед ack | Две независимые подписки; не списывать деньги второй раз при повторе LP sync |
| `BastionTrust` | Команда bastion trust add/remove текущий онлайн-ник, global scheduler | Проверка online и результата; выдача после привязки через durable retry |
| `VaultTrackerPlugin.registerVotifier` | VotifierPlus: com.vexsoftware.votifier.model.VotifierEvent, getVote/getUsername/getServiceName/getTimeStamp | Сначала durable server_vote_outbox, затем Python API и ack; не начислять параллельно из нового listener |

Reflection здесь позволяет собирать VaultTracker без FLEXITY JAR. В объединённом проекте лучше заменить его явными интерфейсами сервисов; сигнатуры/семантику сверить по настоящим исходникам FLEXITY, не по догадке нейросети. Все адаптеры доступны в `src/main/java/ru/vaulttracker/`.

## Где разделить ответственность

- **Lifecycle.** Один JavaPlugin запускает и завершает каждый сервис один раз. Сохранить shutdown всех worker-ов, региональных задач, HTTP и Telegram. При смене имени плагина меняется getDataFolder(): нужна явная однократная миграция пути, иначе появятся пустые базы/роли и вторые очереди.
- **Folia.** Entity/region/global schedulers остаются владельцами игровых операций. SQL/FunPay/Telegram — background. Не подменять эту схему единым main-thread executor; на Folia такого универсального игрового потока нет.
- **Права.** Minecraft OP, LuckPerms, Telegram administrator, VaultTracker Admin/Super/Root — разные признаки. Не превращать Telegram admin в Root. Root-кнопка кабинета и selfActions остаются Root-only. `/unlink` требует `isOp()`, `/nr` — отдельный permission.
- **Telegram.** Один getUpdates-поллер на токен. Основной бот и мост умеют разделять transport; сохранить shared/separate token логику, update offsets и durable expiry. Не запускать объединённую копию одновременно со старой с тем же токеном.
- **Данные.** Guard, storage cache, две аналитики, moderation, SQLite платежей и CoreProtect — разные владельцы. Не объединять таблицы из-за похожего UUID/имени. Прежний server-id и UUID сохраняются; исторический ник может быть неоднозначным.
- **Деньги.** Python остаётся единственным писателем donations.sqlite. В Java — UI/HTTP-клиент и LuckPerms outbox. Все суммы — integer minor units, все финансовые операции — idempotent. Менять этот контракт можно только отдельной миграцией с тестами.
- **Поддержка.** Сначала запись заявки/outbox, потом Telegram HTTP. Сохранять прогресс вложений и уведомления автору жалобы. Не выполнять административное наказание автоматически из формулировки «жалоба закрыта».
- **Mini Apps.** Один localhost listener, три независимых префикса и session namespaces; общий HTTPS proxy. Frontend/скрытие кнопок не заменяют серверную авторизацию. CoreProtect остаётся read-only; текст rollback-команды — только подсказка пользователю.
- **Аналитика.** Статистика сообщений и аналитика использования — разные потоки. Перенести явные outcome hooks и стабильные action IDs, не считать любой callback успешной покупкой/наказанием. Сохранить HMAC keys и timezone вместе с базами.

## Что проверить на стенде после объединения

1. Загрузка/завершение без повторного poller, listener, scheduler и команды; корректный путь к старым данным.
2. Матрица Player/Admin/Super/Root, основной/дополнительный Root, прямая попытка callback/API, потеря Telegram admin. Тесты `CabinetRolesTest`, `AdminNavigationTest`, `RootConsoleMenuTest`, viewer ACL.
3. Каталог/двойные сундуки/компас на разных регионах, UUID и неоднозначные старые ники. `StorageTest`, тесты Catalogue/ChestLayout/OwnResourceSearch.
4. Связанный chat identity, стили, freeze/unfreeze, vanish и playtime при online/offline и ошибках FLEXITY. `GameMessagesTest`, `LinkedChatIdentityTest`, `FlexityPremiumStyleTest`, `FlexityFreezeTest`; vanish/playtime проверить отдельно на стенде: отдельных unit-классов для этих двух адаптеров нет.
5. Рестарт/сбой между записью и HTTP/ack: заявка с медиа, ЛС, голос, LP Premium, Telegram-уведомление. Не создавать реальные платежи или наказывать реальных игроков ради тестов.
6. Дубликаты/гонки в голосах, 24 ч, восстановление из старого credited, месячная ничья и закрытый месяц; Premium обоих продуктов и одноразовая награда UUID. Запустить все Python-тесты.
7. Три Mini App: русские фильтры/списки, optional coordinates, auth/session revocation, mobile width, запрет write/SQL injection/XSS. JUnit и Playwright fixtures из репозитория.

Эта инструкция не предписывает немедленно переносить работающие данные или перезапускать сервер. Сначала собирается и проверяется объединённый проект отдельно; применение на основном сервере — по отдельному поручению владельца.

## Optional проверка настоящего FLEXITY JAR

`FlexityPremiumStyleTest.realFlexity161FormatterAndGradientMatchGamePipeline` использует локальный `work/flexity-current-inspect.jar`. Без него JUnit отмечает тест skipped; остальные тесты адаптера работают с подставным API. Для проверки именно передаваемого FLEXITY получите его JAR у владельца отдельно, сверяйте версию/сигнатуры и запускайте эту проверку на локальном стенде. JAR не нужно добавлять в публичный Git. При изменённом API перенесите проверку на новый явный контракт.
