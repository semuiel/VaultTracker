/* Isolated browser fixture: synthetic players/data, no Telegram or production connections. */
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');
const model = require('../../main/resources/coreprotect-web/app.js');
const root = path.resolve(__dirname,'../../main/resources/coreprotect-web');
const categories=[['block','Блоки','🧱',true,false],['container','Контейнеры','📦',true,false],['item','Предметы','🎒',true,false],['chat','Чат','💬',false,true]].map(([id,label,emoji,material,text])=>({id,label,emoji,material,text}));
const event={id:'container:1',timestamp:1800000000,player:'Fixture',uuid:'00000000-0000-0000-0000-000000000001',category:'container',action:'container_remove',material:'DIAMOND',amount:32,world:'world',x:10,y:64,z:20,rolledBack:false,text:'<img src=x onerror="window.xss=true">'};
const materials=[{id:'DIAMOND',label:'Алмаз'},{id:'DIAMOND_ORE',label:'Алмазная руда'},{id:'DEEPSLATE_DIAMOND_ORE',label:'Глубинная алмазная руда'},{id:'DIRT',label:'Земля'},{id:'STONE',label:'Камень'},{id:'DARK_OAK_LOG',label:'Бревно тёмного дуба'},{id:'BRICKS',label:'Одинаковое название'},{id:'BRICK',label:'Одинаковое название'},{id:'PAPER',label:'<img src=x onerror="window.materialXss=true">'},...Array.from({length:40},(_,i)=>({id:'FILLER_'+i,label:'Тестовый материал '+i}))];
let requests=[],revoked=false,empty=false;
const server=http.createServer((req,res)=>{
  const url=new URL(req.url,'http://localhost');res.setHeader('Cache-Control','no-store');
  if(url.pathname.startsWith('/api/')) {
    res.setHeader('Content-Type','application/json');
    if(revoked){res.writeHead(403);res.end(JSON.stringify({error:'Доступ истёк'}));return;}
    if(url.pathname==='/api/session'){assert.equal(req.headers.authorization,'Telegram fixture-init-data');res.end(JSON.stringify({token:'fixture-session'}));return;}
    assert.equal(req.headers.authorization,'Bearer fixture-session');
    if(url.pathname==='/api/capabilities'){res.end(JSON.stringify({capabilities:{categories,worlds:['world'],usernameHistory:true},limits:{pageSize:50,maxPageSize:100,maxRadius:32,maxDays:30,maxOffset:2000}}));return;}
    if(url.pathname==='/api/materials'){res.end(JSON.stringify({materials}));return;}
    if(url.pathname==='/api/players'){const search=url.searchParams.get('search').toLowerCase();res.end(JSON.stringify({players:search==='missing'?[]:[{name:'OfflineFixture',uuid:event.uuid}],hasMore:false}));return;}
    requests.push(Object.fromEntries(url.searchParams));res.end(JSON.stringify({page:{events:empty?[]:[event],hasMore:!url.searchParams.get('offset')||url.searchParams.get('offset')==='0'}}));return;
  }
  const name=url.pathname==='/'?'index.html':url.pathname.slice(1);
  if(!['index.html','app.js','rollback.js','materials.js','players.js','style.css'].includes(name)){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(path.join(root,name)));
});
async function main(){
  const catalog=model.materials.create(materials);
  assert.equal(catalog.resolve('АЛМАЗ, minecraft:diamond_ore, Земля, алмаз'),'DIAMOND,DIAMOND_ORE,DIRT');
  assert.equal(catalog.resolve('  бревно  ТЕМНОГО  дуба '),'DARK_OAK_LOG');
  assert.equal(catalog.resolve('diamond ore'),'DIAMOND_ORE');
  assert.equal(catalog.search('алмаз руд').length,2);assert.equal(catalog.search('minecraft:diamond_ore').length,2);
  assert.throws(()=>catalog.resolve('Одинаковое название'),/неоднозначно/);
  assert.equal(catalog.textFor(catalog.match('BRICKS')[0]),'BRICKS');
  assert.throws(()=>catalog.resolve('алмазик'),/Не найден материал/);
  assert.throws(()=>catalog.resolve('diamond #silent'),/Не найден материал/);
  assert.throws(()=>catalog.resolve(' , , '),/Укажите название материала/);
  assert.throws(()=>catalog.resolve(Array.from({length:9},(_,i)=>'FILLER_'+i).join(',')),/больше 8/);
  const translated=model.buildQuery({player:'Fixture',period:'1800',include:'Алмазная руда, minecraft:diamond',exclude:'Земля'},1800000000,0,catalog);
  assert.equal(translated.get('include'),'DIAMOND_ORE,DIAMOND');assert.equal(translated.get('exclude'),'DIRT');
  assert.match(model.rollback.build(new URLSearchParams({...Object.fromEntries(translated),category:'container'}),1800000000).command,/i:diamond_ore,diamond e:dirt/);
  assert.equal(model.buildQuery({player:'Fixture',period:'1800',include:'несуществующее',useInclude:false},1800000000,0,catalog).has('include'),false);
  assert.equal(model.transition('container',event).radius,'0');assert.equal(model.transition('container',event).player,'');
  assert.equal(model.transition('player',event).player,event.uuid);assert.equal(model.transition('area',event).radius,'8');
  const query=model.buildQuery({player:'Fixture',period:'1800',limit:'50'},1800000000,50);assert.equal(query.get('from'),'1799998200');assert.equal(query.get('offset'),'50');assert.equal(query.has('user'),false);
  const disabled=model.buildQuery({player:'Fixture',period:'1800',world:'stale',x:'bad',y:'',z:'2',radius:'32',include:'NOT_REAL',text:'secret',useWorld:false,useCoords:false,useInclude:false,useText:false},1800000000,0);
  for(const field of ['world','x','y','z','radius','include','text'])assert.equal(disabled.has(field),false,field);
  const commandQuery=new URLSearchParams({player:'Fixture',category:'container',action:'container_remove',from:'1799996400',to:'1800000000',include:'DIAMOND, minecraft:DIAMOND_ORE',exclude:'STONE'});
  const commands=model.rollback.build(commandQuery,1800000010);
  assert.equal(commands.command,'/co rollback u:Fixture t:3610s-10s a:-container r:#global i:diamond,diamond_ore e:stone');assert.equal(commands.preview,'');
  const spatial=new URLSearchParams(commandQuery);spatial.delete('player');for(const [k,v] of Object.entries({world:'world',x:'-12',y:'64',z:'20',radius:'0'}))spatial.set(k,v);
  assert.match(model.rollback.build(spatial,1800000010).command,/r:#world c:-12,64,20 r:0x0x0/);
  spatial.set('radius','8');assert.match(model.rollback.build(spatial,1800000010).command,/r:8x8x8/);
  const block=new URLSearchParams({player:'Fixture',category:'block',action:'break',from:'1799996400',to:'1800000000'});
  assert.equal(model.rollback.build(block,1800000000).preview,'/co rollback u:Fixture t:3600s a:-block r:#global #preview');
  block.set('world','nether');assert.match(model.rollback.build(block,1800000000).command,/r:#nether/);
  block.set('from',new Date(1799996400*1000).toISOString());block.set('to',new Date(1800000000*1000).toISOString());assert.match(model.rollback.build(block,1800000010).command,/t:3610s-10s/);
  for(const category of ['all','chat','command','session','sign','item']) {const q=new URLSearchParams(commandQuery);q.set('category',category);assert.equal(model.rollback.build(q,1800000000).command,'');}
  for(const [k,v] of [['player',event.uuid],['player','Fixture #silent'],['world','global'],['world','world a:block'],['include','diamond #silent'],['text','secret']]) {const q=new URLSearchParams(commandQuery);q.set(k,v);assert.equal(model.rollback.build(q,1800000000).command,'',k);}
  const inventory=new URLSearchParams(commandQuery);inventory.set('category','inventory');inventory.set('action','inventory_add');assert.match(model.rollback.build(inventory,1800000000).command,/a:\+inventory/);assert.equal(model.rollback.build(inventory,1800000000).preview,'');
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let browser;
  try {
    browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHANNEL?{channel:process.env.PLAYWRIGHT_CHANNEL}:{})});
    const context=await browser.newContext({viewport:{width:390,height:844}});
    await context.addInitScript(()=>Object.defineProperty(navigator,'clipboard',{value:{writeText:async text=>{window.copiedCommand=text;}}}));
    await context.route('https://telegram.org/**',route=>route.fulfill({contentType:'text/javascript',body:'window.Telegram={WebApp:{initData:"fixture-init-data",ready(){},expand(){}}};'}));
    const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.locator('#viewer').waitFor({state:'visible'});
    await page.focus('#player');await page.locator('#playerOptions button').waitFor();assert.match(await page.locator('#playerOptions').innerText(),/OfflineFixture/);
    await page.keyboard.press('ArrowDown');await page.keyboard.press('Enter');assert.equal(await page.locator('#player').inputValue(),event.uuid);
    await page.fill('#player','missing');await page.waitForFunction(()=>document.querySelector('.playerPicker .hint').textContent.includes('не найден'));
    assert.equal(await page.locator('#category option[value=spawn]').count(),0);assert.equal(await page.locator('#category option[value=sign]').count(),0);
    await page.fill('#player','Fixture');await page.click('#submit');await page.locator('.event').waitFor();
    assert.equal(requests.at(-1).player,'Fixture');assert.match(await page.locator('.event').innerText(),/Забрал из контейнера 32 DIAMOND/);
    await page.click('[data-mode=container]');assert.equal(await page.locator('#x').isDisabled(),true);assert.equal(await page.locator('#player').inputValue(),'Fixture');
    await page.click('#submit');await page.locator('.event').waitFor();assert.equal(requests.at(-1).category,'container');assert.equal(requests.at(-1).x,undefined);
    assert.match(await page.locator('#rollbackCommand').inputValue(),/u:Fixture .* a:container r:#global/);assert.equal(await page.locator('#copyPreview').isVisible(),false);
    const readRequests=requests.length;await page.click('#copyRollback');assert.match(await page.evaluate(()=>window.copiedCommand),/^\/co rollback u:Fixture /);assert.equal(requests.length,readRequests);
    await page.check('#useInclude');
    const includePicker=page.locator('#include').locator('..').locator('.materialPicker');
    const excludePicker=page.locator('#exclude').locator('..').locator('.materialPicker');
    await includePicker.locator('summary').click();await includePicker.locator('[data-material]').first().waitFor();assert.equal(await includePicker.locator('[data-material]').count(),30);
    await includePicker.getByRole('button',{name:'Показать ещё',exact:true}).click();assert.equal(await includePicker.locator('[data-material]').count(),materials.length);
    await includePicker.getByRole('searchbox').fill('алмаз руд');assert.equal(await includePicker.locator('[data-material]').count(),2);
    await includePicker.locator('[data-material=DIAMOND_ORE]').click();await includePicker.locator('[data-material=DEEPSLATE_DIAMOND_ORE]').click();
    assert.equal(await page.locator('#include').inputValue(),'Алмазная руда, Глубинная алмазная руда');
    await page.check('#useExclude');await page.fill('#exclude','Земля');
    await excludePicker.locator('summary').click();await excludePicker.getByRole('searchbox').fill('Одинаковое');
    await excludePicker.locator('[data-material=BRICKS]').click();assert.equal(await page.locator('#exclude').inputValue(),'Земля, BRICKS');
    await excludePicker.locator('[data-material=BRICKS]').click();assert.equal(await page.locator('#exclude').inputValue(),'Земля');
    await page.click('#submit');await page.locator('.event').waitFor();assert.equal(requests.at(-1).include,'DIAMOND_ORE,DEEPSLATE_DIAMOND_ORE');assert.equal(requests.at(-1).exclude,'DIRT');
    await page.click('#copyRollback');assert.match(await page.evaluate(()=>window.copiedCommand),/i:diamond_ore,deepslate_diamond_ore e:dirt/);
    await includePicker.getByRole('searchbox').fill('кам');assert.equal(await page.locator('#rollback').isVisible(),true);
    await includePicker.locator('[data-material=STONE]').click();assert.equal(await page.locator('#rollback').isVisible(),false);
    await includePicker.locator('[data-material=STONE]').click();assert.equal(await page.locator('#include').inputValue().then(v=>v.includes('Камень')),false);
    await includePicker.getByRole('searchbox').fill('<img');assert.equal(await includePicker.locator('img').count(),0);assert.match(await includePicker.innerText(),/<img src=x/);assert.equal(await page.evaluate(()=>window.materialXss),undefined);
    await page.fill('#include','алмазик');const beforeInvalid=requests.length;await page.click('#submit');await page.waitForFunction(()=>document.getElementById('status').textContent.includes('Не найден материал'));assert.equal(requests.length,beforeInvalid);
    await page.fill('#include','алмаз, DIAMOND_ORE');await page.click('#submit');await page.locator('.event').waitFor();assert.equal(requests.at(-1).include,'DIAMOND,DIAMOND_ORE');
    await includePicker.getByRole('searchbox').fill('руда');await page.screenshot({path:path.resolve('work/coreprotect-materials-mobile.png'),fullPage:true});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
    await page.uncheck('#useInclude');assert.equal(await includePicker.isVisible(),false);await page.uncheck('#useExclude');assert.equal(await excludePicker.isVisible(),false);
    await page.check('#useCoords');await page.selectOption('#world','world');await page.fill('#x','10');await page.fill('#y','64');await page.fill('#z','20');
    await page.uncheck('#useCoords');await page.uncheck('#useWorld');await page.check('#useInclude');await page.fill('#include','NOT_REAL');await page.uncheck('#useInclude');
    await page.click('#submit');await page.locator('.event').waitFor();for(const k of ['x','y','z','radius','world','include'])assert.equal(requests.at(-1)[k],undefined,k);
    await page.screenshot({path:path.resolve('work/coreprotect-mobile.png'),fullPage:true});
    await page.click('.event');await page.locator('#detail').waitFor({state:'visible'});assert.match(await page.locator('#detailContent').innerText(),/10 64 20/);
    assert.equal(await page.locator('#detail img').count(),0);assert.equal(await page.evaluate(()=>window.xss),undefined);
    await page.click('#byContainer');await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('На странице'));
    assert.equal(requests.at(-1).category,'container');assert.equal(requests.at(-1).radius,'0');assert.equal(requests.at(-1).player,undefined);assert.equal(requests.at(-1).y,'64');
    await page.click('.event');await page.click('#byPlayer');await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('На странице'));
    assert.equal(requests.at(-1).player,event.uuid);assert.equal(requests.at(-1).x,undefined);
    await page.click('.event');await page.click('#nearby');await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('На странице'));
    assert.equal(requests.at(-1).radius,'8');assert.equal(requests.at(-1).world,'world');
    await page.selectOption('#category','chat');assert.equal(await page.locator('#textFilter').isVisible(),true);assert.equal(await page.locator('#materials').isVisible(),false);
    await page.selectOption('#category','block');assert.equal(await page.locator('#textFilter').isVisible(),false);assert.equal(await page.locator('#action option').count(),4);
    await page.check('#useAction');await page.selectOption('#action','break');await page.click('#submit');await page.locator('.event').waitFor();assert.equal(await page.locator('#copyPreview').isVisible(),true);
    await page.click('#copyPreview');assert.match(await page.evaluate(()=>window.copiedCommand),/a:-block .* #preview$/);
    await page.fill('#x','11');assert.equal(await page.locator('#rollback').isVisible(),false);await page.click('#submit');await page.locator('.event').waitFor();
    await page.click('#next');await page.waitForFunction(()=>document.getElementById('pageLabel').textContent==='2');assert.equal(requests.at(-1).offset,'50');
    await page.click('#previous');await page.waitForFunction(()=>document.getElementById('pageLabel').textContent==='1');
    await page.setViewportSize({width:1280,height:900});await page.screenshot({path:path.resolve('work/coreprotect-desktop.png'),fullPage:true});
    empty=true;await page.click('#submit');await page.waitForFunction(()=>document.getElementById('status').textContent.includes('событий с такими фильтрами нет'));assert.equal(await page.locator('.event').count(),0);
    revoked=true;await page.click('#submit');await page.locator('#viewer').waitFor({state:'hidden'});assert.equal(await page.locator('#gate').isVisible(),true);
    assert.deepEqual(errors,[]);
    await context.close();
    const direct=await browser.newContext();await direct.route('https://telegram.org/**',route=>route.fulfill({contentType:'text/javascript',body:'window.Telegram={WebApp:{initData:""}};'}));
    const locked=await direct.newPage();await locked.goto(`http://127.0.0.1:${server.address().port}/`);assert.equal(await locked.locator('#viewer').isVisible(),false);assert.match(await locked.locator('#gate').innerText(),/Откройте CoreProtect через Telegram/);
    console.log('CoreProtect UI: Russian/English material search, list selection/removal/pagination, ambiguous/unknown names, eight-item limit, XSS, canonical query and rollback IDs, ignored disabled filters; existing copy-only, coordinates, pagination, ACL and mobile/desktop checks passed.');
  } finally {if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
