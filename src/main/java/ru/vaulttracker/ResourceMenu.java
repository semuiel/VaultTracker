package ru.vaulttracker;

import java.util.*;

/** Telegram UI screens for resource. */
final class ResourceMenu extends TelegramMenuSection {
    ResourceMenu(TelegramGuardMenu root) {super(root);}

    TelegramCommands.View resourceHome(long user,UUID owner) {
        var view=commands.ownResources(user,owner);
        var buttons=new ArrayList<>(view.buttons());int row=buttons.stream().mapToInt(TelegramCommands.Button::row).max().orElse(-1)+1;
        buttons.add(b(user,"🏆 Личный топ","personalTop","",0,row++));
        buttons.add(b(user,"📜 События","eventsHome","",0,row++));
        if(hasSharedResources(owner)) buttons.add(b(user,"↩️ Ресурсы","resourcesMenu","",0,row++));
        buttons.add(homeButton(row));
        return new TelegramCommands.View(view.text(),List.copyOf(buttons));
    }
    TelegramCommands.View resourceResults(long user,String query,int requested) throws Exception {
        return resourceResults(user,query,requested,false);
    }
    TelegramCommands.View resourceResults(long user,String query,int requested,boolean initial) throws Exception {return resourceResults(user,query,requested,initial,null);}
    TelegramCommands.View resourceResults(long user,String query,int requested,boolean initial,UUID sharedOwner) throws Exception {
        var account=guard.account(user).get();if(account==null) return root.home(user);
        BlockKey origin=moderation==null?null:moderation.ownPosition(user,account.uuid()).get();
        Map<UUID,String> worlds=moderation==null?Map.of():moderation.worldLabels().get();
        if(sharedOwner!=null) root.requireShared(user,sharedOwner);
        String pageOp=sharedOwner==null?"resourceResults":"sharedResults",pageValue=sharedOwner==null?query:sharedOwner+"|"+query;
        var rows=commands.ownSearch(sharedOwner==null?account.uuid():sharedOwner,query,origin);int size=moderation==null?TEXT_PAGE_SIZE:6;
        int pages=Math.max(1,(rows.size()+size-1)/size),page=Math.max(0,Math.min(requested,pages-1));
        if(initial && moderation!=null&&origin!=null) moderation.pointOwnCompass(user,account.uuid(),rows);
        StringBuilder text=new StringBuilder("🔎 "+query+" · "+(page+1)+"/"+pages+"\n"+(origin==null?"Игрок офлайн: сортировка по миру и координатам.":"Ближайшие сундуки в вашем мире сначала.")+"\n");
        if(rows.isEmpty()) text.append("Не найдено в ваших зарегистрированных хранилищах.");
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        int index=0;
        for(var row:rows.subList(page*size,Math.min(rows.size(),(page+1)*size))) {
            text.append(OwnResourceSearch.line(row,worlds.getOrDefault(row.chest().world(),row.chest().world().toString()))).append('\n');
            BlockKey chest=row.chest();
            if(moderation!=null) {
                String item=RussianItems.name(row.material());
                if(item.length()>18) item=item.substring(0,17)+"…";
                buttons.add(b(user,"🧭 "+item+" ×"+row.amount(),"compass",account.uuid()+"|"+chest.world()+"|"+chest.x()+"|"+chest.y()+"|"+chest.z(),0,index/2));
                index++;
            }
        }
        if(!rows.isEmpty() && moderation!=null) text.append("\nНажмите 🧭 с нужным предметом, чтобы направить компас на его сундук.");
        int navigationRow=(index+1)/2;
        if(page>0) buttons.add(b(user,"⬅ Назад",pageOp,pageValue,page-1,navigationRow));
        if(page+1<pages) buttons.add(b(user,"Вперёд ➡",pageOp,pageValue,page+1,navigationRow));
        buttons.add(new TelegramCommands.Button("🔎 Другой поиск",sharedOwner==null?"vg:resourceSearch":"vg:sharedSearch:"+sharedOwner,navigationRow+1));
        buttons.add(homeButton(navigationRow+1));
        buttons.add(b(user,sharedOwner==null?"↩ Мои ресурсы":"↩ Ресурсы друга",sharedOwner==null?"resources":"sharedResources",sharedOwner==null?"":sharedOwner.toString(),0,navigationRow+2));
        return new TelegramCommands.View(text.toString(),List.copyOf(buttons));
    }
    boolean hasSharedResources(UUID owner) {
        return !commands.sharedOwners(guard.sharedOwners(owner)).isEmpty();
    }
    TelegramCommands.View resourcesMenu(long user) throws Exception {
        var account=guard.account(user).get();if(account==null) return root.home(user);
        if(!hasSharedResources(account.uuid())) return resourceHome(user,account.uuid());
        return new TelegramCommands.View("📦 Ресурсы",List.of(
                b(user,"📦 Мои ресурсы","resources","",0,0),
                b(user,"👥 Ресурсы друзей","sharedOwners","",0,1),
                b(user,"🏆 Личный топ","personalTop","",0,2),
                new TelegramCommands.Button("↩️ Назад","vg:home",3),homeButton(4)));
    }
    TelegramCommands.View personalTop(long user,int requested,String filter) throws Exception {
        var account=guard.account(user).get();if(account==null) return root.home(user);
        if(!commands.catalogueReady()) return new TelegramCommands.View("Каталог загружается. Повторите чуть позже.",List.of(
                b(user,"↩️ Назад","resourcesMenu","",0,0),homeButton(1)));
        var ranks=commands.personalTop(user,account.uuid());
        if(filter!=null && !filter.isBlank()) ranks=ranks.stream().filter(rank->personalTopMatches(rank,filter)).toList();
        int size=10;
        int pages=Math.max(1,(ranks.size()+size-1)/size),page=Math.max(0,Math.min(requested,pages-1));
        StringBuilder text=new StringBuilder("🏆 Личный топ · ").append(page+1).append('/').append(pages);
        if(filter!=null && !filter.isBlank()) text.append("\nПоиск: «").append(filter).append("»");
        if(ranks.isEmpty()) text.append(filter==null||filter.isBlank()?"\nПока нет мест в топ-10 по предметам.":"\nПо этому запросу достижений нет.");
        else for(var rank:ranks.subList(page*size,Math.min(ranks.size(),(page+1)*size)))
            text.append("\n#").append(rank.place()).append(' ').append(rank.label())
                    .append(" — ").append(String.format(Locale.US,"%,d",rank.amount()).replace(',',' '));
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(page>0) buttons.add(b(user,"◀ Назад","personalTop",filter,page-1,0));
        if(page+1<pages) buttons.add(b(user,"Вперёд ▶","personalTop",filter,page+1,0));
        buttons.add(b(user,"🔎 Поиск","personalTopSearch",filter,0,1));
        if(filter!=null && !filter.isBlank()) buttons.add(b(user,"✖ Сбросить поиск","personalTop","",0,2));
        buttons.add(b(user,"↩️ Назад","resourcesMenu","",0,3));buttons.add(homeButton(4));
        return new TelegramCommands.View(text.toString(),List.copyOf(buttons));
    }
    static boolean personalTopMatches(Catalogue.PersonalRank rank,String filter) {
        String needle=searchKey(filter);
        if(searchKey(rank.label()).contains(needle)||searchKey(rank.material()).contains(needle)) return true;
        if(RussianItems.candidates(filter).contains(rank.material())) return true;
        var group=ResourceGroups.resolve(filter);
        return group!=null && group.code().equals(rank.material());
    }
    TelegramCommands.View eventsHome(long user) throws Exception {
        if(guard.account(user).get()==null) return root.home(user);
        return new TelegramCommands.View("📜 События\nЧтобы получать уведомления, повесьте на хранилище табличку [v] или [vault] либо вставьте бумагу в рамку на нём.\nЗдесь доступна история изменений в ваших хранилищах.",List.of(
                b(user,"📋 Список событий за 2 дня","ownHistory","",0,0),
                b(user,"↩ Назад","resources","",0,1),homeButton(2)));
    }
    TelegramCommands.View history(long user,int page,boolean own) throws Exception {return history(user,page,own,"");}
    TelegramCommands.View history(long user,int page,boolean own,String filter) throws Exception {
        var events=own ? guard.ownHistory(user,page,6).get() : filter.isBlank()?guard.history(user,page,6).get():guard.searchHistory(user,filter,page,6).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text=(own ? "📜 Мои события за 2 дня" : "🗂 События охраны за 30 дней")+" • страница "+(page+1);
        if(events.isEmpty()) text+="\nСобытий нет.";
        for(int i=0;i<events.size();i++) {var e=events.get(i);String time=java.time.Instant.ofEpochMilli(e.time()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
            buttons.add(b(user,"#"+e.id()+" • "+time+" • "+e.name(),own ? "ownEvent" : "event",Long.toString(e.id()),0,i));}
        String op=own?"ownHistory":filter.isBlank()?"history":"historyFiltered";
        if(page>0) buttons.add(b(user,"◀ Назад",op,filter,page-1,6));
        if(events.size()==6) buttons.add(b(user,"Вперёд ▶",op,filter,page+1,6));
        if(!own) {buttons.add(b(user,"🔎 Поиск событий","historySearch","",0,7));if(!filter.isBlank()) {text+="\nПоиск: "+filter;buttons.add(b(user,"Сбросить поиск","history","",0,8));}}
        buttons.add(b(user,"↩ Назад",own?"eventsHome":"adminHome","",0,9));buttons.add(homeButton(10));return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    TelegramCommands.View detail(long user,long id,int page,boolean own) throws Exception {
        var event=own ? guard.ownEvent(user,id).get() : guard.event(user,id).get();
        if(event==null) return withHome(new TelegramCommands.View("Событие недоступно или срок хранения истёк."));
        int pages=Math.max(1,(event.changes().size()+TEXT_PAGE_SIZE-1)/TEXT_PAGE_SIZE);page=Math.max(0,Math.min(page,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(page>0) buttons.add(b(user,"◀ Назад",own ? "ownEvent" : "event",Long.toString(id),page-1,0));
        if(page+1<pages) buttons.add(b(user,"Вперёд ▶",own ? "ownEvent" : "event",Long.toString(id),page+1,0));
        buttons.add(b(user,"📜 История",own ? "ownHistory" : "history","",0,1));buttons.add(homeButton(2));
        return new TelegramCommands.View("Событие #"+id+" • "+(page+1)+"/"+pages+"\n"+GuardService.eventText(event,page*TEXT_PAGE_SIZE,TEXT_PAGE_SIZE),List.copyOf(buttons));
    }
    static String searchKey(String value) {return value.toLowerCase(Locale.ROOT).replace('ё','е').replace('_',' ').replace('-',' ').trim().replaceAll("\\s+"," ");}

    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("ResourceMenu");
        scope.direct("vg:resourceSearch",(user,data)->prompt(user,"resourceSearch","Введите название ресурса на русском или английском. Поиск только по вашим зарегистрированным сундукам."));
        scope.notice("compass",TelegramActionRegistry.Arguments.VALUE,(user,a,data)-> {
            String[] parts=a.value().split("\\|");
            String result=moderation.selectOwnCompass(user,UUID.fromString(parts[0]),new BlockKey(UUID.fromString(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]),Integer.parseInt(parts[4]))).get();
            return new TelegramCommands.Callback(null,result,true);
        });
        scope.on("resources",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {var account=guard.account(user).get();view=account==null ? root.home(user) : resourceHome(user,account.uuid());}
            return view;
        });
        scope.on("resourcesMenu",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=resourcesMenu(user);
            return view;
        });
        scope.on("personalTop",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=personalTop(user,a.page(),a.value());
            return view;
        });
        scope.on("personalTopSearch",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=prompt(user,"personalTopSearch:"+a.value(),"Введите название предмета или его часть на русском либо английском.");
            return view;
        });
        scope.on("resourceResults",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=resourceResults(user,a.value(),a.page());
            return view;
        });
        scope.on("eventsHome",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=eventsHome(user);
            return view;
        });
        scope.on("ownHistory",TelegramActionRegistry.Arguments.PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=history(user,a.page(),true);
            return view;
        });
        scope.on("ownEvent",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=detail(user,Long.parseLong(a.value()),a.page(),true);
            return view;
        });
    }

    TelegramCommands.View onInputEarly(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().startsWith("personalTopSearch:")) {
            if(guard.account(user).get()==null) {waiting.remove(user);return root.home(user);}
            if(pending.expires()<System.currentTimeMillis()) {waiting.remove(user);return personalTop(user,0,pending.kind().substring("personalTopSearch:".length()));}
            String query=text==null?"":text.strip();
            if(query.isEmpty()||query.length()>64) return new TelegramCommands.View("Введите от 1 до 64 символов. Отмена: /cancel.",List.of(
                    root.b(user,"↩️ Личный топ","personalTop",pending.kind().substring("personalTopSearch:".length()),0,0),homeButton(1)));
            waiting.remove(user);return personalTop(user,0,query);
        }
        if(pending.kind().startsWith("sharedSearch:")&&pending.expires()>=System.currentTimeMillis()) {
            UUID owner=UUID.fromString(pending.kind().substring("sharedSearch:".length()));root.requireShared(user,owner);String query=text.trim();
            if(query.isEmpty()||query.length()>64) return new TelegramCommands.View("Введите название предмета до 64 символов.");
            guard.recordItemSearch(ItemSorting.queryItem(query)).get();
            waiting.remove(user);return resourceResults(user,query,0,true,owner);
        }
        if(pending.kind().equals("resourceSearch")&&pending.expires()>=System.currentTimeMillis()) {
            String query=text.strip();if(query.isEmpty()||query.length()>64) return new TelegramCommands.View("Введите от 1 до 64 символов.");
            guard.recordItemSearch(ItemSorting.queryItem(query)).get();
            waiting.remove(user);return resourceResults(user,query,0,true);
        }
        return null;
    }
}
