/* CoreProtect 24.1 command text only. Never sends a request or executes a command. */
(function (root) {
  'use strict';
  function build(query,now) {
    const q=query instanceof URLSearchParams?query:new URLSearchParams(query);
    const fail=reason=>({command:'',preview:'',reason,notes:[]});
    const category=q.get('category') || 'all',action=q.get('action') || '';
    const mappings={block:{break:'-block',place:'+block'},container:{'':'container',container_add:'+container',container_remove:'-container'},inventory:{'':'inventory',inventory_add:'+inventory',inventory_remove:'-inventory'},kill:{'':'kill',kill:'kill'}};
    if(category==='block'&&!action) return fail('Для команды выберите действие «Сломал» или «Поставил». Общий список блоков включает взаимодействия, которые не откатываются.');
    const nativeAction=mappings[category]?.[action];
    if(!nativeAction) return fail('Выберите поддерживаемое действие: поломка/установка блоков, контейнеры, инвентарь или убийства сущностей. CoreProtect 24.1 не откатывает эту категорию целиком.');
    if(q.get('text')) return fail('Текстовый фильтр нельзя перенести в команду отката CoreProtect 24.1.');
    const player=q.get('player') || '',world=q.get('world') || '';
    if(player&&!/^[A-Za-z0-9_]{1,16}$/.test(player)) return fail('Для команды укажите актуальный игровой ник вместо UUID: команда CoreProtect 24.1 ищет пользователя по нику.');
    if(world&&(!/^[A-Za-z0-9_./-]{1,128}$/.test(world)||['global','worldedit','we','off','none','false'].includes(world.toLowerCase()))) return fail('Название мира нельзя безопасно перенести в команду CoreProtect.');
    const coords=['x','y','z'].some(k=>q.has(k));
    if(!player&&!coords) return fail('Укажите игрока или точные координаты, чтобы ограничить откат.');
    if(category==='inventory'&&!player) return fail('Для отката инвентаря CoreProtect требует игровой ник; игрок должен быть онлайн.');
    const from=Number(q.get('from')),to=Number(q.get('to'));
    // buildQuery uses numeric Unix timestamps or ISO dates, including custom periods.
    const seconds=value=>/^\d+$/.test(value || '')?Number(value):Date.parse(value)/1000;
    const start=Number.isFinite(from)&&q.get('from')?from:seconds(q.get('from'));
    const end=Number.isFinite(to)&&q.get('to')?to:seconds(q.get('to'));
    now=Math.floor(now);
    if(!Number.isSafeInteger(now)||!Number.isFinite(start)||!Number.isFinite(end)||start>=end||end>now+1||now-start>366*86400) return fail('Обновите поиск: для команды нужен корректный ограниченный период.');
    const older=Math.max(1,Math.floor(now-start)),newer=Math.max(0,Math.floor(now-end));
    const parts=['/co rollback'];
    if(player) parts.push('u:'+player);
    parts.push('t:'+older+'s'+(newer?'-'+newer+'s':''),'a:'+nativeAction);
    if(coords) {
      if(!world) return fail('Для координат выберите мир.');
      const values=['x','y','z'].map(k=>q.get(k));
      const n=values.map(Number),r=Number(q.get('radius') || '0');
      if(values.some(v=>v===null||!/^[-]?\d+$/.test(v))||n.some(v=>!Number.isSafeInteger(v))||Math.abs(n[0])>30000000||Math.abs(n[2])>30000000||Math.abs(n[1])>2048||!Number.isInteger(r)||r<0||r>128) return fail('Проверьте координаты и радиус.');
      // Two r: parameters are intentional: WorldParser keeps #world, LocationParser reads the numeric XYZ cuboid.
      parts.push('r:#'+world,'c:'+n.join(','),'r:'+r+'x'+r+'x'+r);
    } else parts.push(world?'r:#'+world:'r:#global'); // Never inherit CoreProtect's default radius around the administrator.
    for(const [key,prefix] of [['include','i:'],['exclude','e:']]) {
      const raw=q.get(key);if(!raw) continue;
      const materials=raw.split(',').map(v=>v.trim().toLowerCase().replace(/^minecraft:/,''));
      if(materials.length>8||materials.some(v=>!/^[a-z0-9_]+$/.test(v))) return fail('Проверьте список материалов.');
      parts.push(prefix+materials.join(','));
    }
    const command=parts.join(' '),transactions=['container','inventory'].includes(category);
    const notes=[world?'Мир: '+world+'.':'Область: все миры.','Команда охватывает все совпадения фильтров, включая другие страницы. Она только копируется. Вставьте её в игру сразу после копирования: период в CoreProtect относительный.'];
    if(coords) notes.push('Запускайте команду, находясь в мире '+world+': CoreProtect проверяет высоту координат по текущему миру.');
    if(transactions) notes.push('CoreProtect 24.1 не поддерживает предпросмотр отката контейнеров и инвентаря.');
    if(category==='inventory') notes.push('Для отката инвентаря указанный игрок должен быть онлайн.');
    return {command,preview:transactions?'':command+' #preview',reason:'',notes};
  }
  const api={build};
  if(typeof module!=='undefined'&&module.exports) module.exports=api;
  else root.CoreProtectRollback=api;
})(typeof window==='undefined'?globalThis:window);
