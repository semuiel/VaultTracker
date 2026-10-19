/* No storage, URL identities, HTML interpolation or investigation logging. Session stays in memory. */
(function () {
  'use strict';
  const rollback=typeof module!=='undefined'&&module.exports?require('./rollback.js'):window.CoreProtectRollback;
  const materials=typeof module!=='undefined'&&module.exports?require('./materials.js'):window.CoreProtectMaterials;
  const labels = {break:'Сломал',place:'Поставил',interact:'Взаимодействовал',kill:'Убил',container_remove:'Забрал из контейнера',container_add:'Добавил в контейнер',inventory_add:'Получил в инвентарь',inventory_remove:'Убрал из инвентаря',logout:'Вышел',login:'Вошёл',chat:'Чат',command:'Команда',sign:'Изменил табличку',remove:'Забрал',add:'Добавил',drop:'Выбросил',pickup:'Подобрал',withdraw:'Забрал из эндер-сундука',deposit:'Положил в эндер-сундук',throw:'Бросил',shoot:'Выстрелил',destroy:'Уничтожил',create:'Создал',sell:'Продал',buy:'Купил',unknown:'Действие'};
  const actions = {block:['break','place','interact'],container:['container_add','container_remove'],item:['remove','add','drop','pickup','withdraw','deposit','throw','shoot','break','destroy','create','sell','buy'],inventory:['inventory_add','inventory_remove'],session:['login','logout'],kill:['kill']};
  function transition(kind, event) {
    const base = {player:'',world:event.world || '',x:String(event.x),y:String(event.y),z:String(event.z),radius:'0',category:'all',action:'',include:'',exclude:'',text:'',usePlayer:false,useWorld:true,useCoords:true,useCategory:true,useAction:false,useInclude:false,useExclude:false,useText:false};
    if (kind === 'player') return {...base,player:event.uuid || event.player,world:'',x:'',y:'',z:'',usePlayer:true,useWorld:false,useCoords:false};
    if (kind === 'container') return {...base,category:'container'};
    return {...base,radius:'8'};
  }
  function buildQuery(fields, anchor, offset, catalog) {
    if(fields.usePlayer===false&&fields.useCoords===false) throw new Error('Включите фильтр «По игроку» или «Координаты и радиус».');
    const q = new URLSearchParams();
    const toggles={player:'usePlayer',world:'useWorld',category:'useCategory',action:'useAction',include:'useInclude',exclude:'useExclude',text:'useText',textMode:'useText',x:'useCoords',y:'useCoords',z:'useCoords',radius:'useCoords'};
    for (const key of ['player','category','action','world','x','y','z','radius','include','exclude','text','textMode','limit']) {
      if(fields[toggles[key]]===false) continue;
      let value=String(fields[key] ?? '').trim();
      if(value&&catalog&&(key==='include'||key==='exclude'))value=catalog.resolve(value);
      if(value !== '') q.set(key,value);
    }
    if (fields.period === 'custom') {
      if (!fields.from || !fields.to) throw new Error('Укажите начало и конец периода');
      q.set('from',new Date(fields.from).toISOString());q.set('to',new Date(fields.to).toISOString());
    } else {q.set('to',String(anchor));q.set('from',String(anchor-Number(fields.period)));}
    q.set('offset',String(offset));return q;
  }
  const model = {transition,buildQuery,actions,labels,rollback,materials};
  if (typeof module !== 'undefined' && module.exports) module.exports=model;
  if (typeof document === 'undefined') return;
  const $ = id => document.getElementById(id);
  const tg=window.Telegram?.WebApp;
  let token='',caps=null,limits=null,mode='player',anchor=0,offset=0,lastFields=null,selected=null,busy=false;
  let filterRevision=0,commandQuery=null,commandServerTime=0,commandClientTime=0;
  let materialCatalog=null;
  let playerPicker=null;
  const materialPickers={};
  function node(tag,text,className) {const n=document.createElement(tag);if(text !== undefined)n.textContent=text;if(className)n.className=className;return n;}
  function status(message) {$('status').textContent=message;}
  async function api(route,auth) {
    const response=await fetch('./api/'+route,{method:'GET',headers:{Authorization:auth || 'Bearer '+token},cache:'no-store',credentials:'omit',referrerPolicy:'no-referrer'});
    const data=await response.json();
    if(!response.ok) {
      if(response.status===403) {token='';$('viewer').hidden=true;$('gate').hidden=false;}
      throw new Error(data.error || 'CoreProtect сейчас недоступен.');
    }
    return data;
  }
  function controls() {
    $('action').replaceChildren(node('option','Все действия'));
    $('action').firstChild.value='';
    for(const a of actions[$('category').value] || []) {const o=node('option',labels[a]);o.value=a;$('action').append(o);}
    syncFilters();
  }
  function syncFilters() {
    const type=$('useCategory').checked?$('category').value:'all';
    const category=caps?.categories.find(c=>c.id===type);
    $('materials').hidden=!!category&&!category.material;
    $('textFilter').hidden=!category?.text;
    $('player').disabled=!$('usePlayer').checked;$('player').required=$('usePlayer').checked;
    playerPicker?.setDisabled($('player').disabled);
    if($('useCoords').checked) $('useWorld').checked=true;
    $('useWorld').disabled=$('useCoords').checked;
    $('world').disabled=!$('useWorld').checked;$('world').required=$('useWorld').checked;
    $('category').disabled=!$('useCategory').checked;
    $('useAction').disabled=!$('useCategory').checked||!(actions[type]?.length);
    $('action').disabled=$('useAction').disabled||!$('useAction').checked;$('action').required=!$('action').disabled;
    for(const id of ['include','exclude','text']) {
      const flag=$('use'+id[0].toUpperCase()+id.slice(1));
      flag.disabled=id==='text'?$('textFilter').hidden:$('materials').hidden;
      $(id).disabled=flag.disabled||!flag.checked;$(id).required=!$(id).disabled;
      materialPickers[id]?.setDisabled($(id).disabled);
    }
    $('textMode').disabled=$('text').disabled;
    for(const id of ['x','y','z','radius']) {$(id).disabled=!$('useCoords').checked;$(id).required=$('useCoords').checked;}
    $('radius').readOnly=false;
  }
  function setMode(next) {
    mode=next;
    for(const b of document.querySelectorAll('[data-mode]')) b.setAttribute('aria-pressed',String(b.dataset.mode===mode));
    $('usePlayer').checked=mode!=='area';$('useCoords').checked=mode==='area';$('useWorld').checked=mode==='area';$('useCategory').checked=true;$('useAction').checked=false;
    $('category').value=mode==='container'?'container':'all';
    if(mode!=='player') $('filters').open=true;
    if(mode==='container') $('radius').value='0';
    controls();
    for(const picker of Object.values(materialPickers))picker.refresh();
    invalidate();
  }
  function fields() {
    const result={};for(const id of ['player','category','action','world','x','y','z','radius','include','exclude','text','textMode','limit','period','from','to'])result[id]=$(id).value;
    for(const id of ['usePlayer','useWorld','useCoords','useCategory','useAction','useInclude','useExclude','useText'])result[id]=$(id).checked&&!$(id).disabled;
    // The world checkbox is locked on when coordinates are active.
    if(result.useCoords) result.useWorld=true;
    return result;
  }
  function invalidate() {
    filterRevision++;commandQuery=null;$('rollback').hidden=true;$('results').replaceChildren();$('pages').hidden=true;
  }
  function renderCommand() {
    if(!commandQuery)return null;
    const now=commandServerTime+(Date.now()-commandClientTime)/1000;
    const result=rollback.build(commandQuery,now);
    $('rollback').hidden=false;$('rollbackCommand').hidden=!result.command;$('rollbackCommand').value=result.command;
    $('rollbackNote').textContent=result.reason || result.notes.join(' ');
    $('copyRollback').disabled=!result.command;$('copyPreview').hidden=!result.preview;
    return result;
  }
  async function copyCommand(preview) {
    const result=renderCommand(),command=preview?result?.preview:result?.command;
    if(!command)return;
    try {await navigator.clipboard.writeText(command);$(preview?'copyPreview':'copyRollback').textContent='Скопировано';}
    catch(_) {$('rollbackCommand').value=command;$('rollbackCommand').focus();$('rollbackCommand').select();$('rollbackNote').textContent='Выделите и скопируйте команду вручную.';}
  }
  function openDetail(event) {
    selected=event;const box=$('detailContent');box.replaceChildren(node('h2',(caps.categories.find(c=>c.id===event.category)?.emoji || '')+' '+(labels[event.action] || event.action)));
    const dl=node('dl');
    const data=[['Время',new Date(event.timestamp*1000).toLocaleString('ru-RU')],['Игрок',event.player],['UUID',event.uuid || 'Не сохранён'],['Категория',caps.categories.find(c=>c.id===event.category)?.label || event.category],['Действие',labels[event.action] || event.action],['Материал',event.material || '—'],['Количество',event.amount ?? '—'],['Мир',event.world || 'Не сохранён'],['Координаты',`${event.x} ${event.y} ${event.z}`],['Откат',event.rolledBack == null?'Нет данных':event.rolledBack?'Да':'Нет'],['Текст',event.text || '—']];
    for(const [key,value] of data) {dl.append(node('dt',key),node('dd',String(value)));}box.append(dl);
    $('byContainer').hidden=event.category!=='container';
    $('copy').textContent='Копировать координаты';
    $('nearby').hidden=$('copy').hidden=!event.world;
    $('byPlayer').hidden=!event.player || !/^[A-Za-z0-9_]{1,16}$/.test(event.player);
    $('detail').showModal();
  }
  function results(page) {
    $('results').replaceChildren();
    for(const event of page.events) {
      const category=caps.categories.find(c=>c.id===event.category);
      const card=node('button',undefined,'event');card.type='button';
      card.append(node('time',new Date(event.timestamp*1000).toLocaleString('ru-RU')),
        node('strong',`${category?.emoji || ''} ${event.player} · ${labels[event.action] || event.action}${event.amount != null?' '+event.amount:''}${event.material?' '+event.material:''}`),
        node('small',`${event.world || 'Мир не сохранён'} · ${event.x} ${event.y} ${event.z}${event.rolledBack?' · Откачено':''}`));
      card.addEventListener('click',()=>openDetail(event));$('results').append(card);
    }
    status(page.events.length?`На странице: ${page.events.length}. Сначала новые события.`:'За выбранный период событий с такими фильтрами нет.');
    $('pages').hidden=!offset&&!page.hasMore;$('previous').disabled=offset===0;$('next').disabled=!page.hasMore;
    $('pageLabel').textContent=String(Math.floor(offset/Number(lastFields.limit))+1);
    if(offset+Number(lastFields.limit)>limits.maxOffset) status($('status').textContent+' Достигнут предел страниц. Сузьте период.');
  }
  async function search(reset) {
    if(busy)return;busy=true;$('submit').disabled=true;$('previous').disabled=true;$('next').disabled=true;
    if(reset) {offset=0;anchor=Math.floor(Date.now()/1000);lastFields=fields();}
    const revision=filterRevision;
    commandQuery=null;$('rollback').hidden=true;
    status('Ищем события…');$('results').replaceChildren();
    try {
      const query=buildQuery(lastFields,anchor,offset,materialCatalog),data=await api('lookup?'+query);
      if(revision!==filterRevision) {status('Фильтры изменены. Нажмите «Найти события».');return;}
      results(data.page);commandQuery=query;commandServerTime=Number.isFinite(Date.parse(data.serverTime))?Date.parse(data.serverTime)/1000:Date.now()/1000;commandClientTime=Date.now();
      $('copyRollback').textContent='📋 Скопировать откат';$('copyPreview').textContent='👁 Скопировать предпросмотр';renderCommand();
    }
    catch(error) {if(revision===filterRevision) {status(error.message);$('pages').hidden=true;}}
    finally {busy=false;$('submit').disabled=false;}
  }
  $('search').addEventListener('submit',e=>{e.preventDefault();search(true);});
  $('period').addEventListener('change',()=>{$('customPeriod').hidden=$('period').value!=='custom';});
  $('category').addEventListener('change',controls);
  for(const input of document.querySelectorAll('#search input[type=checkbox]')) input.addEventListener('change',syncFilters);
  $('search').addEventListener('input',invalidate);$('search').addEventListener('change',invalidate);
  $('copyRollback').addEventListener('click',()=>copyCommand(false));$('copyPreview').addEventListener('click',()=>copyCommand(true));
  for(const b of document.querySelectorAll('[data-mode]')) b.addEventListener('click',()=>setMode(b.dataset.mode));
  $('previous').addEventListener('click',()=>{offset=Math.max(0,offset-Number(lastFields.limit));search(false);});
  $('next').addEventListener('click',()=>{offset+=Number(lastFields.limit);search(false);});
  $('closeDetail').addEventListener('click',()=>$('detail').close());
  $('copy').addEventListener('click',async()=>{try {await navigator.clipboard.writeText(`${selected.world} ${selected.x} ${selected.y} ${selected.z}`);$('copy').textContent='Скопировано';}catch(_) {$('copy').textContent='Координаты указаны выше';}});
  for(const [id,kind] of [['byPlayer','player'],['byContainer','container'],['nearby','area']]) $(id).addEventListener('click',()=>{
    const data=transition(kind,selected);setMode(kind);for(const [key,value] of Object.entries(data)) {if(typeof value==='boolean')$(key).checked=value;else $(key).value=value;}
    controls();for(const picker of Object.values(materialPickers))picker.refresh();$('detail').close();search(true);
  });
  async function boot() {
    if(!tg?.initData)return;
    try {
      tg.ready();tg.expand();
      const session=await api('session','Telegram '+tg.initData);token=session.token;
      const data=await api('capabilities');caps=data.capabilities;limits=data.limits;
      const dictionary=await api('materials');materialCatalog=materials.create(dictionary.materials);
      playerPicker=window.CoreProtectPlayers.mount($('player'),api);
      for(const [id,label] of [['include','Включить материалы'],['exclude','Исключить материалы']])materialPickers[id]=materials.mount($(id),materialCatalog,label);
      for(const c of caps.categories) {const o=node('option',c.emoji+' '+c.label);o.value=c.id;$('category').append(o);}
      for(const world of caps.worlds) {const o=node('option',world);o.value=world;$('world').append(o);}
      $('containerMode').hidden=!caps.categories.some(c=>c.id==='container');
      $('radius').max=String(limits.maxRadius);
      $('limit').replaceChildren();for(const n of [...new Set([limits.pageSize,limits.maxPageSize])]){const o=node('option',String(n));o.value=String(n);$('limit').append(o);}
      for(const o of [...$('period').options]) if(o.value!=='custom'&&Number(o.value)>limits.maxDays*86400)o.remove();
      $('gate').hidden=true;$('viewer').hidden=false;setMode('player');status('Выберите игрока или координаты и нажмите «Найти события».');
      // Periodic server revalidation also revokes idle panels, without depending on user navigation.
      setInterval(async()=>{if(!token)return;try {await api('capabilities');}catch(error){status(error.message);}},35000);
    } catch(error) {$('gate').replaceChildren(node('h2',error.message));}
  }
  boot();
})();
