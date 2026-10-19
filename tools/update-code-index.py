"""Regenerate the complete source/test index without scanning local runtime or databases."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SECTIONS = (
    ('Java-плагин', 'src/main/java', '*.java'),
    ('Ресурсы, YAML и frontend', 'src/main/resources', '*'),
    ('Java-тесты и generators', 'src/test/java', '*.java'),
    ('Web-тесты', 'src/test/web', '*.cjs'),
    ('Изолированный smoke-плагин', 'src/smoke', '*'),
    ('Python-сервис и его тесты', 'donations', '*'),
    ('SQL', 'sql', '*.sql'),
    ('Инструменты передачи', 'tools', '*'),
    ('Deployment-пример', 'deployment', '*'),
)

def main():
    lines = ['# Полный индекс исходников', '',
             'Сгенерирован `python tools/update-code-index.py`. Карта ответственности — [architecture.md](architecture.md); сборка/перенос — [handoff.md](handoff.md).', '',
             'Указаны все исходники и шаблоны в перечисленных каталогах. Локальные runtime, базы, кэши и секретные конфиги в индекс не входят.', '']
    total = 0
    for title, folder, pattern in SECTIONS:
        files = sorted(path for path in (ROOT / folder).rglob(pattern)
                       if path.is_file() and not any(part in ('__pycache__', '.venv', 'runtime', 'lib') for part in path.relative_to(ROOT).parts)
                       and not path.name.startswith('config.local.') and path.suffix not in ('.pyc', '.sqlite', '.db'))
        total += len(files)
        lines += ['## ' + title + ' (' + str(len(files)) + ')', '']
        for path in files:
            name = path.relative_to(ROOT).as_posix()
            lines.append('- [' + name + '](../' + name + ')')
        lines.append('')
    lines += ['Всего файлов в индексе: **' + str(total) + '**. Gradle/README/AGENTS/package.json находятся в корне репозитория.', '']
    (ROOT / 'docs' / 'code-index.md').write_text('\n'.join(lines), encoding='utf-8')
    print('Indexed source/test/template files:', total)

if __name__ == '__main__':
    main()
