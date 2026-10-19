/* Shared client-side names for search and copied commands. No network or game operations. */
(function(root) {
  'use strict';
  const key=value=>String(value).trim().toLowerCase().replace(/ё/g,'е').replace(/\s+/g,' ');
  const tokens=value=>String(value).split(',').map(v=>v.trim()).filter(Boolean);
  function create(rows) {
    const ids=new Map(),names=new Map();
    for(const row of rows) {
      if(!/^[A-Z0-9_]+$/.test(row.id)||typeof row.label!=='string')continue;
      const entry={id:row.id,label:row.label};ids.set(entry.id,entry);
      const name=key(entry.label);if(!names.has(name))names.set(name,[]);names.get(name).push(entry);
    }
    const entries=[...ids.values()].sort((a,b)=>a.label.localeCompare(b.label,'ru')||a.id.localeCompare(b.id));
    function match(raw) {
      const id=key(raw).replace(/^minecraft:/,'').replace(/[\s-]+/g,'_').toUpperCase();
      if(ids.has(id))return [ids.get(id)];
      return names.get(key(raw)) || [];
    }
    function resolve(raw) {
      const result=[];
      for(const token of tokens(raw)) {
        const found=match(token);
        if(!found.length)throw new Error('Не найден материал «'+token+'». Выберите его из списка или укажите полное название.');
        if(found.length>1)throw new Error('Название «'+token+'» неоднозначно. Выберите нужный вариант из списка.');
        if(!result.includes(found[0].id))result.push(found[0].id);
      }
      if(!result.length&&String(raw).trim())throw new Error('Укажите название материала или выберите его из списка.');
      if(result.length>8)throw new Error('Можно выбрать не больше 8 материалов в каждом фильтре.');
      return result.join(',');
    }
    function search(raw) {
      const words=key(raw).replace(/^minecraft:/,'').replace(/[_-]/g,' ').split(' ').filter(Boolean);
      return entries.filter(entry=>{
        const text=key(entry.label)+' '+entry.id.toLowerCase().replace(/_/g,' ');
        return words.every(word=>text.includes(word));
      });
    }
    // A translated name may identify several block variants; retain the precise ID in that case.
    const textFor=entry=>match(entry.label).length===1?entry.label:entry.id;
    return {entries,match,resolve,search,textFor};
  }
  function mount(input,catalog,label) {
    const doc=input.ownerDocument;
    const element=(tag,text)=>{const n=doc.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
    const box=element('details');box.className='materialPicker';
    const summary=element('summary','Выбрать из списка');
    const search=element('input');search.type='search';search.placeholder='Например: алмазная руда';search.autocomplete='off';search.maxLength=100;
    search.setAttribute('aria-label','Поиск по списку: '+label);
    const count=element('p');count.className='hint';count.setAttribute('role','status');
    const list=element('div');list.className='materialOptions';list.setAttribute('role','group');list.setAttribute('aria-label',label);
    const more=element('button','Показать ещё');more.type='button';
    box.append(summary,search,count,list,more);input.after(box);
    let shown=30,disabled=input.disabled;
    function render() {
      const found=catalog.search(search.value),selected=new Set();
      for(const token of tokens(input.value)){const matches=catalog.match(token);if(matches.length===1)selected.add(matches[0].id);}
      count.textContent=found.length?'Найдено: '+found.length+'. Выбрано: '+selected.size+' из 8.':'Ничего не найдено. Попробуйте другое название.';
      list.replaceChildren();
      for(const entry of found.slice(0,shown)) {
        const button=element('button');button.type='button';button.disabled=disabled;button.dataset.material=entry.id;
        const checked=selected.has(entry.id);button.setAttribute('aria-pressed',String(checked));
        button.append(element('span',(checked?'✓ ':'')+entry.label),element('small',entry.id));
        button.addEventListener('click',()=>{
          const current=tokens(input.value);
          const remaining=current.filter(token=>{const matches=catalog.match(token);return matches.length!==1||matches[0].id!==entry.id;});
          if(!checked) {
            if(remaining.length>=8){count.textContent='Можно выбрать не больше 8 материалов. Уберите лишний материал.';return;}
            remaining.push(catalog.textFor(entry));
          }
          input.value=remaining.join(', ');input.dispatchEvent(new Event('input',{bubbles:true}));
        });
        list.append(button);
      }
      more.hidden=found.length<=shown;more.disabled=disabled;
    }
    search.addEventListener('input',event=>{event.stopPropagation();shown=30;render();});
    search.addEventListener('change',event=>event.stopPropagation());
    search.addEventListener('keydown',event=>{if(event.key==='Enter')event.preventDefault();});
    more.addEventListener('click',()=>{shown+=30;render();});
    box.addEventListener('toggle',()=>{if(box.open)render();});
    input.addEventListener('input',()=>{if(box.open)render();});
    return {refresh(){if(box.open)render();},setDisabled(value){disabled=value;box.hidden=value;search.disabled=value;if(value)box.open=false;}};
  }
  const api={create,mount};
  if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.CoreProtectMaterials=api;
})(typeof window==='undefined'?globalThis:window);
