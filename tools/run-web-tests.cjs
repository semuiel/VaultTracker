/* Creates only local fixture output. No production configs, bot, database or Minecraft. */
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
fs.mkdirSync(path.join(root, 'work'), {recursive: true});
for (const name of ['coreprotect-ui.cjs', 'telegram-analytics-ui.cjs', 'bot-analytics-ui.cjs']) {
  const result = spawnSync(process.execPath, [path.join(root, 'src/test/web', name)], {
    cwd: root, env: process.env, stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}
