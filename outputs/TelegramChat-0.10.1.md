# Чат-мост VaultTracker 0.10.1

Установите `VaultTracker-0.10.1.jar` вместо прежнего JAR и перезапустите сервер. Настройки находятся в `plugins/VaultTracker/telegramchat.yml`; существующий файл не перезаписывается.

## Главное исправление 0.10.1

- `/list` и `/list@имя_бота` работают в теме игрового чата и в теме достижений.
- В одном сообщении показывается до 20 игроков. При большем числе игроков кнопки переключают страницы в этом же сообщении.
- Сообщение списка удаляется через `list.messageLifetimeSeconds`, по умолчанию через 300 секунд.
- Пустое `messages.requirePrefixInMinecraft: ""` пересылает все обычные игровые сообщения. Непустое значение разрешает только сообщения с этим префиксом.

## Минимальная настройка

```yaml
enabled: true
token: "ТОКЕН_ЧАТ_БОТА"
chat:
  chatId: -1000000000000
  topicId: 486
  minecraftToTelegram: true
  telegramToMinecraft: true
messages:
  requirePrefixInMinecraft: ""
advancements:
  enabled: true
  chatId: -1000000000000
  topicId: 123
list:
  enabled: true
  messageLifetimeSeconds: 300
```

Замените ID группы и тем на свои. `topicId: 0` означает обычную группу или основную тему. Затем выполните `/vtrack reload`. Для получения обычных сообщений из Telegram отключите Privacy Mode у бота через BotFather либо назначьте бота администратором группы.

Прокси используются из `telegram-proxies.txt`, а при пустом списке — из `advanced.proxy` файла `telegram.yml`. Два приложения с одним токеном одновременно получать обновления не могут. Остановите tgbridge или другой экземпляр бота, если в журнале появляется `409 Conflict: terminated by other getUpdates request`.

Форматы сообщений, эмодзи измерений, события, направления пересылки и отдельная тема достижений подробно прокомментированы в `telegramchat.yml`.
