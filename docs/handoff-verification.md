# Проверка полного снимка перед передачей

Выполнено 19.10.2026 в отдельной локальной рабочей копии. Основной сервер, SSH, live БД, платёжный процесс и proxy во время этой задачи не использовались и не изменялись.

## Что включено

- Все текущие Java-исходники, frontend assets и шаблоны: 99 Java-файлов в src/main/java, 21 файл в src/main/resources.
- Все 71 Java test/helper-файл, 9 Python test-модулей, 3 Playwright fixture и отдельный smoke-плагин.
- Полный Python-сервис, включая новые vote_schema.py, vote_monthly.py, link_rewards.py, premium_products.py; requirements, SETUP/START/CHECK и пример JSON.
- Gradle Wrapper/build, SQL-пример, tools для упаковки и UI runner, шаблон Caddy, правила AGENTS и карта/инструкции передачи.
- Семь прежних локальных коммитов сохраняются предками этого снимка, а новые незакоммиченные наработки включены в передаваемое дерево.

## Реально выполненные проверки

| Проверка | Результат |
| --- | --- |
| JDK 25, Gradle 9.1.0: test shadowJar | BUILD SUCCESSFUL |
| JUnit | 491 тест: 490 прошли, 0 failures, 0 errors, 1 skip |
| Python unittest | 79 тестов, OK |
| CoreProtect UI | Прошёл; русские материалы/списки, optional фильтры, rollback только как текст, ACL, XSS, мобильный/desktop |
| Telegram Analytics UI | Прошёл; периоды/темы/графики/приватность, ACL, XSS, мобильный/desktop |
| Bot Analytics UI | Прошёл; 7 вкладок, фильтры/графики, coverage, ACL, XSS, мобильный/desktop |
| Payment ZIP | 22 allowlist-файла; новые модули и setup присутствуют, private config/SQLite/runtime отсутствуют |
| Shadow JAR | Правильная версия/API; новые классы и assets присутствуют |
| Сравнение с исходной рабочей папкой | Все 228 файлов src/donations/sql/gradle перенесены; изменения только в документации и portable выборе браузера трёх fixture |
| Проверка новых коммитов на credential patterns | 180 blobs семи ранее неопубликованных коммитов, включая текстовые ресурсы нового JAR: совпадений Telegram/GitHub/private-key нет |

Использовались локальные dependency caches: JDK 25.0.4.1, Python runtime и ранее установленные requirements, Playwright 1.62.1 с системным Chrome. Свежая загрузка зависимостей Maven/PyPI/npm на пустом компьютере здесь не проверялась. Для нового разработчика приведены обычные online-команды в README/handoff.

Один JUnit skip — FlexityPremiumStyleTest.realFlexity161FormatterAndGradientMatchGamePipeline: optional проверка настоящего внешнего FLEXITY JAR. В чистой копии отсутствует work/flexity-current-inspect.jar; mock-проверки адаптера прошли. Это не пропуск упавшего теста. Предупреждения Gradle о deprecated API/совместимости с будущим Gradle 10 не являются ошибками текущей сборки.

## Что намеренно не является публичным исходным комплектом

Рабочие токены/пароли, config.local.json, H2/SQLite и данные игроков, Python runtime/lib/venv, Gradle cache, локальные inspection JAR сторонних плагинов, приватные backups и отчёты расследований. Состав необходимой приватной передачи владельцем перечислен в handoff.md.

Старые собранные JAR/ZIP, старый outputs и служебный gcm-diagnose.log исключены из текущего дерева Git; их история не переписывалась. Они не являются каноническим источником новой разработки. Исходники самого FLEXITY нужно получить из его отдельного проекта.
