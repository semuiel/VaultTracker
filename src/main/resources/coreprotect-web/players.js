/* One bounded, authenticated catalogue for offline/current/historical CoreProtect names. */
(function(root){
  'use strict';
  function mount(input,api){
    const doc=input.ownerDocument,box=doc.createElement('div'),list=doc.createElement('div'),hint=doc.createElement('p'),more=doc.createElement('button');
    box.className='playerPicker';box.hidden=true;list.className='playerOptions';list.id='playerOptions';list.setAttribute('role','listbox');list.setAttribute('aria-label','Известные игроки CoreProtect');
    input.setAttribute('role','combobox');input.setAttribute('aria-autocomplete','list');input.setAttribute('aria-controls',list.id);input.setAttribute('aria-expanded','false');
    hint.className='hint';hint.setAttribute('role','status');more.type='button';more.textContent='Показать ещё';more.hidden=true;box.append(list,hint,more);input.after(box);
    let generation=0,timer,rows=[],index=-1,loading=false;
    function close(){box.hidden=true;input.setAttribute('aria-expanded','false');input.removeAttribute('aria-activedescendant');}
    function active(next){const buttons=[...list.querySelectorAll('button')];if(!buttons.length)return;index=(next+buttons.length)%buttons.length;buttons.forEach((b,i)=>b.setAttribute('aria-selected',String(i===index)));input.setAttribute('aria-activedescendant',buttons[index].id);buttons[index].scrollIntoView({block:'nearest'});}
    function choose(row){input.value=row.uuid||row.name;generation++;input.dispatchEvent(new Event('change',{bubbles:true}));close();}
    async function load(append=false){
      if(input.disabled)return close();const own=++generation,search=input.value.trim();box.hidden=false;input.setAttribute('aria-expanded','true');hint.textContent='Загрузка игроков…';loading=true;more.hidden=true;
      if(!append){rows=[];list.replaceChildren();index=-1;}
      try{
        const page=await api('players?'+new URLSearchParams({search,offset:append?rows.length:0,limit:30}));if(own!==generation)return;
        rows.push(...page.players);list.replaceChildren();
        for(const [i,row] of rows.entries()){
          const button=doc.createElement('button');button.type='button';button.id='cp-player-'+i;button.setAttribute('role','option');button.setAttribute('aria-selected','false');
          const name=doc.createElement('span'),uuid=doc.createElement('small');name.textContent=row.name;uuid.textContent=row.uuid;button.append(name,uuid);
          button.addEventListener('mousedown',e=>e.preventDefault());button.addEventListener('click',()=>choose(row));list.append(button);
        }
        hint.textContent=rows.length?'Показано: '+rows.length+'. Включая офлайн-игроков и прежние ники.':'Игрок не найден. Можно продолжить с введённым ником или UUID.';more.hidden=!page.hasMore;
      }catch(error){if(own===generation)hint.textContent='Список недоступен. Введите ник или UUID вручную.';}
      finally{if(own===generation)loading=false;}
    }
    input.addEventListener('focus',()=>load());
    input.addEventListener('input',()=>{clearTimeout(timer);generation++;timer=setTimeout(()=>load(),300);});
    input.addEventListener('keydown',e=>{
      if(e.key==='Escape'){e.preventDefault();close();}
      else if(!box.hidden&&['ArrowDown','ArrowUp'].includes(e.key)){e.preventDefault();active(index+(e.key==='ArrowDown'?1:-1));}
      else if(!box.hidden&&e.key==='Enter'&&index>=0){e.preventDefault();choose(rows[index]);}
    });
    more.addEventListener('click',()=>{if(!loading)load(true);});
    doc.addEventListener('click',e=>{if(e.target!==input&&!box.contains(e.target))close();});
    return {setDisabled(value){if(value){generation++;clearTimeout(timer);close();}}};
  }
  root.CoreProtectPlayers={mount};
})(window);
