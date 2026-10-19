/* Local synthetic fixture only. No real bot token, database, messages or external requests. */
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');const root=path.resolve(__dirname,'../../main/resources/telegram-stats');
let revoked=false,requests=[];
const malicious='<img src=x onerror="window.xss=true">';
const options={timezone:'Asia/Novosibirsk',chats:[{chat:-1001,title:'Сообщество Fixture',kind:'supergroup'},{chat:-1002,title:'Канал Fixture',kind:'channel'}],topics:[{chat:-1001,topic:42,title:'Общение '+malicious},{chat:-1001,topic:43,title:'Торговля'}]};
function report(p){const absent=p.get('from')==='2020-01-01',offset=Number(p.get('offset')||0);return {
  timezone:options.timezone,from:p.get('from')||'2026-09-18',to:p.get('to')||'2026-10-17',grain:p.get('grain')==='month'||p.get('period')==='all'?'month':'day',generatedAt:1790000000,started:1789000000,hasCoverage:!absent,partial:true,
  summary:{messages:1000,authors:70,messagesPerDay:33.33,messagesPerAuthor:14.29,mediaShare:20,replyRate:30,forwardShare:10,replies:300,forwards:100,activeTopics:2,dau:20,wau:50,mau:70,newAuthors:10,returningAuthors:60,returningShare:85.71,multiDayAuthors:40,averageActivePerDay:20},
  changes:{messages:null,authors:null},previous:null,comparisonFrom:'2026-08-19',comparisonTo:'2026-09-17',bestMonth:[{month_key:'2026-10',messages:800}],
  timeline:Array.from({length:10},(_,i)=>({bucket:'2026-10-'+String(i+1).padStart(2,'0'),messages:50+i*10,authors:15+i})),heatmap:[{dow:1,hour:18,n:30},{dow:5,hour:20,n:50}],
  topics:{rows:[{chat:-1001,topic:42,title:'Общение '+malicious,messages:800,authors:60,share:80,messagesPerDay:26.66,change:null}],hasMore:offset===0,offset},
  content:[{kind:'text',n:800},{kind:'photo',n:200},{kind:'service',n:5}],audience:{current:1200,delta:20,previousDelta:null,growthPercent:1.69,observedJoins:null,observedLeaves:null,series:[{chat:-1001,at:1790000000,n:145},{chat:-1001,at:1790086400,n:150},{chat:-1002,at:1790000000,n:1000},{chat:-1002,at:1790086400,n:1050}]},retention:{eligible:10,returned:4,incomplete:3,rate:40},diagnostics:{metadatarows:1005,bytes:1048576,schema:1,earliest:1789000000,latest:1790000000},captureFailures:0
};}
const server=http.createServer((req,res)=>{
  const url=new URL(req.url,'http://localhost');assert.equal(req.method,'GET');res.setHeader('Cache-Control','no-store');
  if(url.pathname.startsWith('/api/')){res.setHeader('Content-Type','application/json');if(revoked){res.writeHead(403);res.end(JSON.stringify({error:'Доступ истёк'}));return;}
    if(url.pathname==='/api/session'){assert.equal(req.headers.authorization,'Telegram fixture-init');res.end(JSON.stringify({token:'fixture-session'}));return;}
    assert.equal(req.headers.authorization,'Bearer fixture-session');if(url.pathname==='/api/options'){res.end(JSON.stringify(options));return;}
    assert.equal(url.pathname,'/api/dashboard');requests.push(Object.fromEntries(url.searchParams));res.end(JSON.stringify(report(url.searchParams)));return;
  }
  const file=url.pathname==='/'?'index.html':url.pathname.slice(1);if(!['index.html','app.js','style.css'].includes(file)){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(path.join(root,file)));
});
async function main(){await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
  try{
    browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHANNEL?{channel:process.env.PLAYWRIGHT_CHANNEL}:{})});const context=await browser.newContext({viewport:{width:390,height:844}});
    await context.route('https://telegram.org/**',route=>route.fulfill({contentType:'text/javascript',body:'window.Telegram={WebApp:{initData:"fixture-init",ready(){},expand(){}}};'}));
    const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Отчёт обновлён'));
    assert.match(await page.locator('#coverage').innerText(),/Данные собираются с:.*Есть неполные периоды/);assert.equal(await page.locator('#messagesChart svg').count(),1);
    assert.match(await page.locator('#comparison').innerText(),/два полных одинаковых периода/);
    for(const period of ['7','30','90','180','365','all'])assert.equal(await page.locator(`#period option[value="${period}"]`).count(),1);
    await page.selectOption('#period','all');await page.click('#apply');await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Отчёт обновлён'));assert.equal(requests.at(-1).period,'all');
    await page.click('[data-tab=activity]');assert.equal(await page.locator('#heatmap rect').count(),168);assert.equal(await page.locator('#authorsChart svg').count(),1);assert.match(await page.locator('#retention').innerText(),/Незавершённых или неполных: 3/);
    await page.click('[data-tab=audience]');assert.equal(await page.locator('#membersChart svg').count(),2);assert.match(await page.locator('#memberEvents').innerText(),/Для каналов используются только замеры/);
    await page.click('[data-tab=content]');assert.equal(await page.locator('#contentChart svg').count(),1);
    await page.click('[data-tab=topics]');assert.equal(await page.locator('#topicChart svg').count(),1);assert.equal(await page.locator('#topicList img').count(),0);assert.match(await page.locator('#topicList').innerText(),/<img/);assert.equal(await page.evaluate(()=>window.xss),undefined);
    await page.selectOption('#chat','-1001');assert.match(await page.locator('#topic option[value="42"]').innerText(),/Общение/);
    options.topics[0].title='Жалобы игроков';await page.click('#apply');await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Отчёт обновлён'));
    assert.equal(await page.locator('#topic option[value="42"]').innerText(),'Жалобы игроков');
    assert.match(await page.locator('#topicChart svg title').first().textContent(),/Жалобы игроков/);
    assert.match(await page.locator('#topicList').innerText(),/Жалобы игроков/);assert.doesNotMatch(await page.locator('#topicList').innerText(),/Тема #42/);
    await page.click('#next');await page.waitForFunction(()=>document.getElementById('topicPage').textContent==='2');assert.equal(requests.at(-1).offset,'50');await page.click('#previous');await page.waitForFunction(()=>document.getElementById('topicPage').textContent==='1');
    await page.selectOption('#sort','name');await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Отчёт обновлён'));assert.equal(requests.at(-1).sort,'name');
    await page.click('.topicRow');await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Отчёт обновлён'));assert.equal(requests.at(-1).chat,'-1001');assert.equal(requests.at(-1).topic,'42');
    await page.selectOption('#period','custom');await page.fill('#from','2021-01-01');await page.fill('#to','2026-10-17');await page.selectOption('#grain','month');await page.click('#apply');await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Отчёт обновлён'));assert.equal(requests.at(-1).from,'2021-01-01');assert.equal(requests.at(-1).grain,'month');
    await page.click('[data-tab=advertiser]');const ad=await page.locator('#advertiser').innerText();for(const forbidden of ['-1001','metadatarows','schema','fixture-session','username'])assert.equal(ad.includes(forbidden),false,forbidden);assert.match(ad,/Активных авторов \/ день/);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);await page.screenshot({path:path.resolve('work/telegram-analytics-mobile.png'),fullPage:true});
    await page.setViewportSize({width:1280,height:900});await page.screenshot({path:path.resolve('work/telegram-analytics-desktop.png'),fullPage:true});
    await page.fill('#from','2020-01-01');await page.click('#apply');await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Отчёт обновлён'));assert.match(await page.locator('#coverage').innerText(),/За выбранные даты данных ещё нет/);await page.click('[data-tab=activity]');assert.equal(await page.locator('#heatmap svg').count(),0);
    assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);revoked=true;await page.click('#apply');await page.locator('#viewer').waitFor({state:'hidden'});assert.equal(await page.locator('#gate').isVisible(),true);assert.deepEqual(errors,[]);await context.close();
    const direct=await browser.newContext();await direct.route('https://telegram.org/**',route=>route.fulfill({contentType:'text/javascript',body:'window.Telegram={WebApp:{initData:""}};'}));const locked=await direct.newPage();await locked.goto(`http://127.0.0.1:${server.address().port}/`);assert.equal(await locked.locator('#viewer').isVisible(),false);
    console.log('Telegram Analytics UI: selectors, all-time/custom multi-year, six charts, heatmap, topic drill-down/pagination/sort, advertiser privacy, missing data, ACL revocation, XSS and mobile/desktop passed.');
  }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
