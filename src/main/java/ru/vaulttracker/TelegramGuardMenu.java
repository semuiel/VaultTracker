package ru.vaulttracker;

import java.util.*;

/** Private cabinet. Permissions are checked on every read and confirmed action. */
final class TelegramGuardMenu {
    private record Action(long user,String op,String value,int page,long createdAt) {}
    private record Input(String kind,long expires) {}
    private final Map<String,Action> actions=new HashMap<>();
    private final Map<Long,Input> waiting=new HashMap<>();
    private final GuardService guard;
    private final TelegramCommands commands;
    private final TelegramModeration moderation;
    private final TelegramTagManager tags;
    TelegramGuardMenu(GuardService guard,TelegramCommands commands) {this(guard,commands,null,null);}
    TelegramGuardMenu(GuardService guard,TelegramCommands commands,TelegramModeration moderation) {this(guard,commands,moderation,null);}
    TelegramGuardMenu(GuardService guard,TelegramCommands commands,TelegramModeration moderation,TelegramTagManager tags) {
        this.guard=guard;this.commands=commands;this.moderation=moderation;this.tags=tags;
    }

    TelegramCommands.Callback callback(long user,String data) throws Exception {
        cancelInput(user);
        if("vg:home".equals(data)) return answer(home(user));
        Action a=actions.get(data);
        if(a==null || a.user()!=user) return new TelegramCommands.Callback(null,"Кнопка больше недоступна. Откройте /menu.",true);
        if(Set.of("own","admin","link","time","tagApply","tagReset","rootTime").contains(a.op()) || a.op().startsWith("do:")) actions.remove(data);
        TelegramCommands.View view;
        switch(a.op()) {
            case "link" -> view=new TelegramCommands.View(guard.generate(user).get(),List.of(new TelegramCommands.Button("Обновить кабинет","vg:home")));
            case "resources" -> {var account=guard.account(user).get();view=account==null ? home(user) : withHome(commands.ownResources(user,account.uuid()));}
            case "settings" -> view=settings(user,false);
            case "own" -> {guard.toggleOwn(user).get();view=settings(user,false);}
            case "tagApply" -> view=tag(user,true);
            case "tagReset" -> view=tag(user,false);
            case "time" -> {long value=Long.parseLong(a.value());guard.offlineSeconds(user,value== -2 ? null : value).get();view=settings(user,false);}
            case "custom" -> view=prompt(user,"time","Отправьте число секунд от 0 до 31536000. Например, 172800 — двое суток.");
            case "adminHome" -> view=adminHome(user);
            case "admin" -> {guard.toggleAdmin(user).get();view=adminHome(user);}
            case "banMenu" -> view=banMenu(user);
            case "history" -> view=history(user,a.page(),false);
            case "historySearch" -> {guard.requireAdmin(user);view=prompt(user,"historySearch","Отправьте часть ника владельца/участника, название предмета, координаты или номер события.");}
            case "historyFiltered" -> view=history(user,a.page(),false,a.value());
            case "customNumber" -> {guard.requireSuper(user);view=prompt(user,"number:"+a.value(),"Отправьте своё значение от 0.1 до 20. Например: 1.25. 1 — стандартное значение.");}
            case "teleportCoords" -> {guard.requireSuper(user);view=prompt(user,"coords:"+a.value(),"Отправьте x y z, например: 223 200 1004. Используется текущий мир перемещаемого игрока.");}
            case "teleportPlayers" -> view=teleportPlayers(user,a.value(),a.page());
            case "ownHistory" -> view=history(user,a.page(),true);
            case "event" -> view=detail(user,Long.parseLong(a.value()),a.page(),false);
            case "ownEvent" -> view=detail(user,Long.parseLong(a.value()),a.page(),true);
            case "superHome" -> view=superHome(user);
            case "admins" -> view=admins(user,a.page());
            case "addAdmin" -> {guard.requireSuper(user);view=prompt(user,"addAdmin","Отправьте Telegram ID нового администратора (положительное число).");}
            case "chat" -> {guard.requireSuper(user);view=prompt(user,"chat","Отправьте текст для игрового чата: одна строка, до 500 символов. Перед отправкой будет подтверждение.");}
            case "rootSettings" -> view=settings(user,true);
            case "rootTime" -> {guard.superDelay(user,Long.parseLong(a.value())).get();view=settings(user,true);}
            case "rootCustom" -> {guard.requireSuper(user);view=prompt(user,"rootTime","Отправьте число секунд от 0 до 31536000 для ваших администраторских уведомлений.");}
            case "players" -> {guard.requireSuper(user);view=new TelegramCommands.View("Список игроков",List.of(b(user,"🟢 Онлайн","browse:online","",0,0),b(user,"👥 Все","browse:all","",0,1),homeButton(2)));}
            case "searchPlayers" -> {guard.requireSuper(user);view=prompt(user,"playerSearch:"+a.value(),"Отправьте часть ника игрока для поиска.");}
            default -> {
                if(a.op().startsWith("browse:")) view=players(user,a.op().substring(7),a.page());
                else if(a.op().startsWith("browseSearch:")) view=players(user,a.op().substring(13),a.page(),a.value());
                else if(a.op().equals("person")) view=person(user,UUID.fromString(a.value()));
                else if(a.op().equals("playerActions")) view=playerActions(user,UUID.fromString(a.value()));
                else if(a.op().equals("scaleMenu")) view=scaleMenu(user,UUID.fromString(a.value()));
                else if(a.op().equals("flySpeedMenu")) view=speedMenu(user,UUID.fromString(a.value()),"fly");
                else if(a.op().equals("walkSpeedMenu")) view=speedMenu(user,UUID.fromString(a.value()),"walk");
                else if(a.op().equals("inventory")) {String[] parts=a.value().split("\\|",2);view=inventory(user,UUID.fromString(parts[0]),parts[1].equals("ender"),a.page());}
                else if(a.op().equals("shulker")) {String[] parts=a.value().split("\\|",3);view=shulker(user,UUID.fromString(parts[0]),parts[1].equals("ender"),parts[2],a.page());}
                else if(a.op().startsWith("confirm:")) view=confirm(user,a.op().substring(8),a.value());
                else if(a.op().startsWith("do:")) view=perform(user,a.op().substring(3),a.value());
                else view=home(user);
            }
        }
        return answer(view);
    }
    private static TelegramCommands.Callback answer(TelegramCommands.View view) {return new TelegramCommands.Callback(view,"",false);}
    TelegramCommands.View home(long user) throws Exception {
        cancelInput(user);
        actions.entrySet().removeIf(e->e.getValue().user()==user && e.getValue().op().startsWith("do:"));
        var account=guard.account(user).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text="👤 Личный кабинет"+(account==null ? "\nПривяжите персонажа, чтобы открыть личные ресурсы и события." : "\nПерсонаж: "+account.name());
        if(account==null) buttons.add(b(user,"🔗 Привязать персонажа","link","",0,0));
        else {
            buttons.add(b(user,"📦 Мои ресурсы","resources","",0,0));
            buttons.add(b(user,"📜 Мои события · 2 дня","ownHistory","",0,1));
            buttons.add(b(user,"⚙️ Настройки","settings","",0,2));
        }
        if(guard.admin(user)) {
            buttons.add(b(user,"🛠 Настройки администратора","adminHome","",0,3));
            buttons.add(b(user,"🗂 События администраторов · 30 дней","history","",0,4));
        }
        if(guard.superAdmin(user)) buttons.add(b(user,"👑 Супер администратор","superHome","",0,5));
        buttons.add(new TelegramCommands.Button("🔎 Поиск","vg:search",6));
        if(!guard.enabled()) text+="\nОхрана отключена в guard.yml.";
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    private TelegramCommands.View adminHome(long user) throws Exception {
        guard.requireAdmin(user);boolean on=guard.adminAlerts(user).get();
        return new TelegramCommands.View("🛠 Настройки администратора\nУведомления администратора: "+(on ? "включены" : "выключены"),List.of(
                b(user,(on ? "🔔 Отключить" : "🔕 Включить")+" уведомления администратора","admin","",0,0),
                b(user,"⏳ Временный бан · 5 минут","banMenu","",0,1),b(user,"🚪 Кикнуть игрока","browse:kick","",0,2),homeButton(3)));
    }
    private TelegramCommands.View banMenu(long user) {
        guard.requireAdmin(user);
        return new TelegramCommands.View("⏳ Временный бан · 5 минут\nКаких игроков показать?",List.of(
                b(user,"🟢 Игроки онлайн","browse:banOnline","",0,0),
                b(user,"👥 Все игроки","browse:banAll","",0,1),
                b(user,"🛠 Назад в настройки администратора","adminHome","",0,2),homeButton(3)));
    }
    private TelegramCommands.View superHome(long user) {
        guard.requireSuper(user);
        return new TelegramCommands.View("👑 Супер администратор",List.of(
                b(user,"➕ Добавить администратора","addAdmin","",0,0),b(user,"👮 Список администраторов / удалить","admins","",0,1),
                b(user,"👥 Список игроков","players","",0,2),b(user,"⛔ Забаненные / разбан","browse:banned","",0,3),
                b(user,"💬 Сообщение в игровой чат","chat","",0,4),b(user,"⏱ Мой срок админских уведомлений","rootSettings","",0,5),homeButton(6)));
    }
    private TelegramCommands.View settings(long user,boolean root) throws Exception {
        if(root) guard.requireSuper(user);else if(guard.account(user).get()==null) return home(user);
        Long personal=root ? Long.valueOf(guard.superDelay(user)) : guard.offlineSeconds(user).get();
        String text=(root ? "👑 Ваши администраторские уведомления" : "⚙️ Настройки игрока")+"\nВремя отсутствия: "+duration(personal==null ? guard.defaultOfflineSeconds() : personal)+(personal==null ? " (по настройке сервера)" : " (личное)")
                +"\nВремя отсчитывается от выхода из игры. «В том числе в игре» включает сообщения и во время игры.";
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        if(!root) {
            boolean on=guard.account(user).get().notifications(),tag=guard.tag(user,false).get();
            text+="\nВаши уведомления: "+(on ? "включены" : "выключены")+"\nTelegram-тег: "+(tag ? "успешно применён во всех настроенных чатах" : "сброшен, ещё не применялся или применился не во всех чатах")+".";
            buttons.add(b(user,(on ? "🔔 Отключить" : "🔕 Включить")+" мои уведомления","own","",0,row++));
        }
        long[] values={-1,0,120,300,900,3600,21600,86400,172800,604800};
        for(long value:values) buttons.add(b(user,duration(value),root ? "rootTime" : "time",Long.toString(value),0,row++));
        buttons.add(b(user,"✏️ Своё время",root ? "rootCustom" : "custom","",0,row++));
        buttons.add(b(user,"Как на сервере",root ? "rootTime" : "time","-2",0,row++));
        if(!root) {
            String nickname=guard.account(user).get().name();
            buttons.add(b(user,"🏷 Применить тег «"+nickname+"»","tagApply","",0,row++));
            buttons.add(b(user,"♻️ Сбросить тег","tagReset","",0,row++));
        }
        buttons.add(homeButton(row));return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    private TelegramCommands.View tag(long user,boolean apply) throws Exception {
        var account=guard.account(user).get();
        if(account==null) return home(user);
        if(tags==null) return withHome(new TelegramCommands.View("Управление Telegram-тегами сейчас недоступно."));
        TelegramTagManager.Result result=apply ? tags.apply(user,account.name()) : tags.reset(user);
        if(result.complete()) guard.setTag(user,apply).get();
        return new TelegramCommands.View(result.text(),List.of(b(user,"⚙️ Назад в настройки","settings","",0,0),homeButton(1)));
    }
    private static String duration(long seconds) {
        if(seconds<0) return "В том числе в игре";if(seconds==0) return "Сразу после выхода";
        if(seconds%86400==0) return seconds/86400+" дн.";if(seconds%3600==0) return seconds/3600+" ч.";
        if(seconds%60==0) return seconds/60+" мин.";return seconds+" сек.";
    }
    void cancelInput(long user) {waiting.remove(user);}
    private TelegramCommands.View prompt(long user,String kind,String text) {
        waiting.entrySet().removeIf(e->e.getValue().expires()<System.currentTimeMillis());waiting.put(user,new Input(kind,System.currentTimeMillis()+600000));
        return withHome(new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel."));
    }
    TelegramCommands.View input(long user,String text) throws Exception {
        Input pending=waiting.get(user);if(pending==null) return null;
        if(pending.expires()<System.currentTimeMillis()) {waiting.remove(user);return withHome(new TelegramCommands.View("Время ввода истекло. Откройте настройки снова."));}
        if(pending.kind().equals("historySearch")) {
            guard.requireAdmin(user);String query=text.trim();
            if(query.isEmpty() || query.length()>100) return new TelegramCommands.View("Введите запрос от 1 до 100 символов. Отмена: /cancel.");
            waiting.remove(user);return history(user,0,false,query);
        }
        if(pending.kind().startsWith("number:")) {
            guard.requireSuper(user);double value;
            try {value=Double.parseDouble(text.trim().replace(',','.'));if(!Double.isFinite(value) || value<0.1 || value>20) throw new NumberFormatException();}
            catch(NumberFormatException e) {return new TelegramCommands.View("Введите число от 0.1 до 20. Например: 1.25. Отмена: /cancel.");}
            String[] parts=pending.kind().substring(7).split("\\|",2);waiting.remove(user);return confirm(user,parts[0],parts[1]+"|"+value);
        }
        if(pending.kind().startsWith("coords:")) {
            guard.requireSuper(user);
            try {TelegramModeration.coordinates(text);} catch(IllegalArgumentException e) {return new TelegramCommands.View(e.getMessage()+" Отмена: /cancel.");}
            waiting.remove(user);return confirm(user,"teleportCoords",pending.kind().substring(7)+"|"+text.trim());
        }
        if(pending.kind().startsWith("playerSearch:")) {
            guard.requireSuper(user);String query=text==null ? "" : text.trim();
            if(query.isBlank() || query.length()>64) return withHome(new TelegramCommands.View("Введите часть ника длиной от 1 до 64 символов."));
            String mode=pending.kind().substring("playerSearch:".length());
            if(!Set.of("online","all","banned","kick","banOnline","banAll").contains(mode)) {waiting.remove(user);return home(user);}
            waiting.remove(user);return players(user,mode,0,query);
        }
        if(pending.kind().equals("chat")) {
            guard.requireSuper(user);if(text.isBlank() || text.length()>500 || text.contains("\n") || text.contains("\r")) return withHome(new TelegramCommands.View("Нужна одна строка до 500 символов."));
            waiting.remove(user);return confirm(user,"chat",text);
        }
        long value;
        try {value=Long.parseLong(text.trim());if(value<0 || (!pending.kind().equals("addAdmin") && value>GuardService.MAX_OFFLINE_SECONDS)) throw new NumberFormatException();}
        catch(NumberFormatException e) {return new TelegramCommands.View("Введите целое число"+(pending.kind().equals("addAdmin") ? " Telegram ID." : " секунд от 0 до 31536000.")+" Отмена: /cancel.");}
        if(pending.kind().equals("addAdmin")) {guard.requireSuper(user);if(value==0) return new TelegramCommands.View("Telegram ID должен быть больше 0.");waiting.remove(user);return confirm(user,"addAdmin",Long.toString(value));}
        if(pending.kind().equals("rootTime")) guard.superDelay(user,value).get();else guard.offlineSeconds(user,value).get();
        waiting.remove(user);return settings(user,pending.kind().equals("rootTime"));
    }
    private TelegramCommands.View history(long user,int page,boolean own) throws Exception {return history(user,page,own,"");}
    private TelegramCommands.View history(long user,int page,boolean own,String filter) throws Exception {
        var events=own ? guard.ownHistory(user,page,6).get() : filter.isBlank()?guard.history(user,page,6).get():guard.searchHistory(user,filter,page,6).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text=(own ? "📜 Мои события за 2 дня" : "🗂 События охраны за 30 дней")+" • страница "+(page+1);
        if(events.isEmpty()) text+="\nСобытий нет.";
        for(int i=0;i<events.size();i++) {var e=events.get(i);String time=java.time.Instant.ofEpochMilli(e.time()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
            buttons.add(b(user,"#"+e.id()+" • "+time+" • "+e.name(),own ? "ownEvent" : "event",Long.toString(e.id()),0,i));}
        String op=own?"ownHistory":filter.isBlank()?"history":"historyFiltered";
        if(page>0) buttons.add(b(user,"◀ Назад",op,filter,page-1,6));
        if(events.size()==6) buttons.add(b(user,"Вперёд ▶",op,filter,page+1,6));
        if(!own) {buttons.add(b(user,"🔎 Поиск событий","historySearch","",0,7));if(!filter.isBlank()) {text+="\nПоиск: "+filter;buttons.add(b(user,"Сбросить поиск","history","",0,8));}}
        buttons.add(homeButton(9));return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    private TelegramCommands.View detail(long user,long id,int page,boolean own) throws Exception {
        var event=own ? guard.ownEvent(user,id).get() : guard.event(user,id).get();
        if(event==null) return withHome(new TelegramCommands.View("Событие недоступно или срок хранения истёк."));
        int pages=Math.max(1,(event.changes().size()+7)/8);page=Math.max(0,Math.min(page,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(page>0) buttons.add(b(user,"◀ Назад",own ? "ownEvent" : "event",Long.toString(id),page-1,0));
        if(page+1<pages) buttons.add(b(user,"Вперёд ▶",own ? "ownEvent" : "event",Long.toString(id),page+1,0));
        buttons.add(b(user,"📜 История",own ? "ownHistory" : "history","",0,1));buttons.add(homeButton(2));
        return new TelegramCommands.View("Событие #"+id+" • "+(page+1)+"/"+pages+"\n"+GuardService.eventText(event,page*8,8,!own),List.copyOf(buttons));
    }
    private TelegramCommands.View admins(long user,int page) {
        guard.requireSuper(user);var ids=guard.adminIds().stream().sorted().toList();List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*6;i<Math.min(ids.size(),page*6+6);i++) {
            long id=ids.get(i);if(guard.superAdmin(id)) continue;
            buttons.add(b(user,"Убрать администратора "+id,"confirm:removeAdmin",Long.toString(id),0,i-page*6));
        }
        if(page>0) buttons.add(b(user,"◀ Назад","admins","",page-1,6));if((page+1)*6<ids.size()) buttons.add(b(user,"Вперёд ▶","admins","",page+1,6));
        buttons.add(homeButton(7));return new TelegramCommands.View("Администраторов (включая супер администратора): "+ids.size()+"\nСупер администратор: "+user+"\nВыберите ID для удаления.",List.copyOf(buttons));
    }
    private TelegramCommands.View players(long user,String mode,int page) throws Exception { return players(user,mode,page,""); }
    private TelegramCommands.View players(long user,String mode,int page,String filter) throws Exception {
        guard.requireAdmin(user);if(Set.of("online","all","banned").contains(mode)) guard.requireSuper(user);
        if(moderation==null) return withHome(new TelegramCommands.View("Управление сервером недоступно."));
        String source=switch(mode) {case "kick","banOnline" -> "online";case "banAll" -> "all";default -> mode;};
        String operation=Set.of("banOnline","banAll").contains(mode) ? "ban" : mode;
        var people=moderation.players(user,source).get();
        if(!filter.isBlank()) {String needle=searchKey(filter);people=people.stream().filter(p->searchKey(p.name()).contains(needle)).toList();}
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*6;i<Math.min(people.size(),page*6+6);i++) {var p=people.get(i);buttons.add(b(user,(p.online() ? "🟢 " : "👤 ")+p.name(),Set.of("ban","kick").contains(operation) ? "confirm:"+operation : "person",p.uuid().toString(),0,i-page*6));}
        String pageOp=filter.isBlank() ? "browse:"+mode : "browseSearch:"+mode;
        if(page>0) buttons.add(b(user,"◀ Назад",pageOp,filter,page-1,6));if((page+1)*6<people.size()) buttons.add(b(user,"Вперёд ▶",pageOp,filter,page+1,6));
        if(Set.of("banOnline","banAll").contains(mode)) buttons.add(b(user,"↩ Выбор списка","banMenu","",0,7));
        buttons.add(b(user,"🔎 Поиск","searchPlayers",mode,0,8));buttons.add(homeButton(9));
        String title=mode.equals("banOnline") ? "Игроки онлайн" : mode.equals("banAll") ? "Все игроки" : "Игроки";
        if(!filter.isBlank()) title+=" • поиск «"+filter.trim()+"»";
        return new TelegramCommands.View(title+" • страница "+(page+1)+" • всего "+people.size(),List.copyOf(buttons));
    }
    private static String searchKey(String value) {return value.toLowerCase(Locale.ROOT).replace('ё','е').replace('_',' ').replace('-',' ').trim().replaceAll("\\s+"," ");}
    private TelegramCommands.View person(long user,UUID uuid) throws Exception {
        guard.requireSuper(user);var p=moderation.info(user,uuid).get();String id=uuid.toString();
        var adminFuture=moderation.adminGroup(user,uuid);
        boolean admin=adminFuture!=null && Boolean.TRUE.equals(adminFuture.get());
        return new TelegramCommands.View("👤 "+p.name()+"\n"+p.details(),List.of(
                b(user,(admin ? "❌ Забрать" : "✅ Выдать")+" группу LuckPerms admin","confirm:toggleLp",id,0,0),
                b(user,"🎒 Инвентарь","inventory",id+"|main",0,1),b(user,"🧰 Эндер-сундук","inventory",id+"|ender",0,2),
                b(user,"🛠 Действия игрока","playerActions",id,0,3),homeButton(4)));
    }
    private TelegramCommands.View inventory(long user,UUID uuid,boolean ender,int page) throws Exception {
        guard.requireSuper(user);List<TelegramModeration.ItemView> items;
        try {items=moderation.inventory(user,uuid,ender).get();}
        catch(java.util.concurrent.ExecutionException e) {
            Throwable cause=e;while(cause.getCause()!=null) cause=cause.getCause();
            if(!(cause instanceof java.io.IOException)) throw e;
            return new TelegramCommands.View("Не удалось прочитать сохранённый инвентарь: файл playerdata отсутствует, повреждён или недоступен. Это не означает, что инвентарь пуст. Попробуйте после сохранения игрока.",List.of(b(user,"Повторить","inventory",uuid+"|"+(ender?"ender":"main"),page,0),b(user,"↩ Карточка игрока","person",uuid.toString(),0,1),homeButton(2)));
        }
        String title=ender ? "🧰 Эндер-сундук" : "🎒 Инвентарь";
        var info=moderation.info(user,uuid).get();if(info!=null && !info.online()) title+=" · офлайн, последнее сохранение";
        if(items.isEmpty()) return new TelegramCommands.View(title+"\nИнвентарь пуст.",List.of(b(user,"↩ Карточка игрока","person",uuid.toString(),0,0),homeButton(1)));
        int pages=Math.max(1,(items.size()+7)/8);page=Math.max(0,Math.min(page,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();StringBuilder text=new StringBuilder(title+" • страница "+(page+1)+"/"+pages+"\n");
        for(int i=page*8;i<Math.min(items.size(),page*8+8);i++) {var item=items.get(i);text.append(item.label()).append(": ").append(item.amount()).append(" шт.\n");if(item.shulker()) buttons.add(b(user,item.label()+" · открыть","shulker",uuid+"|"+(ender?"ender":"main")+"|"+i,0,buttons.size()));}
        if(page>0) buttons.add(b(user,"◀ Назад","inventory",uuid+"|"+(ender?"ender":"main"),page-1,8));if(page+1<pages) buttons.add(b(user,"Вперёд ▶","inventory",uuid+"|"+(ender?"ender":"main"),page+1,8));
        buttons.add(b(user,"↩ Карточка игрока","person",uuid.toString(),0,9));buttons.add(homeButton(10));return new TelegramCommands.View(text.toString().stripTrailing(),List.copyOf(buttons));
    }
    private TelegramCommands.View shulker(long user,UUID uuid,boolean ender,String path,int page) throws Exception {
        var items=moderation.inventory(user,uuid,ender).get();String title="📦 Содержимое шалкера";
        var container=itemAt(items,path);
        if(container==null || !container.shulker()) return inventory(user,uuid,ender,0);
        var nested=container.contents();int pages=Math.max(1,(nested.size()+7)/8);page=Math.max(0,Math.min(page,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();StringBuilder text=new StringBuilder(title+" • страница "+(page+1)+"/"+pages+"\n");
        if(nested.isEmpty()) text.append("Шалкер пуст."); else for(int i=page*8;i<Math.min(nested.size(),page*8+8);i++) {var item=nested.get(i);text.append(item.label()).append(": ").append(item.amount()).append(" шт.\n");if(item.shulker()) buttons.add(b(user,item.label()+" · открыть","shulker",uuid+"|"+(ender?"ender":"main")+"|"+path+"."+i,0,buttons.size()));}
        String mode=ender?"ender":"main";if(page>0) buttons.add(b(user,"◀ Назад","shulker",uuid+"|"+mode+"|"+path,page-1,8));if(page+1<pages) buttons.add(b(user,"Вперёд ▶","shulker",uuid+"|"+mode+"|"+path,page+1,8));buttons.add(b(user,"↩ К инвентарю","inventory",uuid+"|"+mode,0,9));buttons.add(homeButton(10));return new TelegramCommands.View(text.toString().stripTrailing(),List.copyOf(buttons));
    }
    private static TelegramModeration.ItemView itemAt(List<TelegramModeration.ItemView> items,String path) {
        if(path==null || path.isBlank()) return null;
        List<TelegramModeration.ItemView> current=items;TelegramModeration.ItemView result=null;
        for(String part:path.split("\\.")) {
            try {int index=Integer.parseInt(part);if(index<0 || index>=current.size()) return null;result=current.get(index);current=result.contents();}
            catch(NumberFormatException e) {return null;}
        }
        return result;
    }
    private TelegramCommands.View playerActions(long user,UUID uuid) throws Exception {
        guard.requireSuper(user);String id=uuid.toString();
        return new TelegramCommands.View("🛠 Действия игрока",List.of(
                b(user,"⏳ Бан на 5 минут","confirm:ban",id,0,0),b(user,"🚪 Кик","confirm:kick",id,0,1),
                b(user,"🔓 Разбан","confirm:unban",id,0,2),b(user,"💚 Вылечить игрока","confirm:heal",id,0,3),
                b(user,"☠️ Убить игрока","confirm:kill",id,0,4),b(user,"🔧 Починить предметы","confirm:repair",id,0,5),
                b(user,"📏 Размер персонажа","scaleMenu",id,0,6),b(user,"✈️ Скорость полёта","flySpeedMenu",id,0,7),
                b(user,"🏃 Скорость передвижения","walkSpeedMenu",id,0,8),
                b(user,"📍 Телепортировать к игроку","teleportPlayers",id,0,9),b(user,"📍 Телепортировать на координаты","teleportCoords",id,0,10),
                b(user,"↩ Карточка игрока","person",id,0,11),homeButton(12)));
    }
    private TelegramCommands.View teleportPlayers(long user,String source,int page) throws Exception {
        guard.requireSuper(user);var people=moderation.players(user,"online").get().stream().filter(p->!p.uuid().toString().equals(source)).toList();
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*6;i<Math.min(people.size(),page*6+6);i++) {var p=people.get(i);buttons.add(b(user,p.name(),"confirm:teleportPlayer",source+"|"+p.uuid(),0,i-page*6));}
        if(page>0) buttons.add(b(user,"◀ Назад","teleportPlayers",source,page-1,6));
        if((page+1)*6<people.size()) buttons.add(b(user,"Вперёд ▶","teleportPlayers",source,page+1,6));
        buttons.add(b(user,"↩ Действия игрока","playerActions",source,0,7));buttons.add(homeButton(8));
        return new TelegramCommands.View("К какому игроку телепортировать?"+(people.isEmpty()?"\nДругих игроков онлайн нет.":""),List.copyOf(buttons));
    }
    private TelegramCommands.View scaleMenu(long user,UUID uuid) {
        guard.requireSuper(user);String id=uuid.toString();double[] values={0.1,0.5,0.65,0.75,0.85,1,1.5,2,5,10,15,20};List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=0;i<values.length;i++) buttons.add(b(user,"📏 "+values[i],"confirm:scale",id+"|"+values[i],0,i/2));
        buttons.add(b(user,"✏️ Своё значение","customNumber","scale|"+id,0,6));
        buttons.add(b(user,"↩ Действия игрока","playerActions",id,0,7));buttons.add(homeButton(7));
        return new TelegramCommands.View("📏 Установить размер персонажа",List.copyOf(buttons));
    }
    private TelegramCommands.View speedMenu(long user,UUID uuid,String kind) {
        guard.requireSuper(user);String id=uuid.toString();double[] values={0.1,0.5,0.65,0.75,0.85,1,1.5,2,5,10,15,20};
        String title=kind.equals("fly") ? "✈️ Скорость полёта" : "🏃 Скорость передвижения";
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=0;i<values.length;i++) {double value=values[i];String label=title+" "+value+(value==1.0 ? " (по умолчанию)" : "");buttons.add(b(user,label,"confirm:"+(kind.equals("fly")?"flySpeed":"walkSpeed"),id+"|"+value,0,i/2));}
        int row=(values.length+1)/2;
        buttons.add(b(user,"✏️ Своё значение","customNumber",(kind.equals("fly")?"flySpeed":"walkSpeed")+"|"+id,0,row));
        buttons.add(b(user,"↩ Действия игрока","playerActions",id,0,row+1));buttons.add(homeButton(row+1));
        return new TelegramCommands.View(title+"\nКоэффициент относительно стандартной скорости. 1.0 — значение по умолчанию."+ (kind.equals("fly") ? "\nPaper ограничивает полёт значением 10x." : ""),List.copyOf(buttons));
    }
    private TelegramCommands.View confirm(long user,String operation,String value) throws Exception {
        actions.entrySet().removeIf(e->e.getValue().user()==user && e.getValue().op().startsWith("do:"));
        if(Set.of("ban","kick").contains(operation)) guard.requireAdmin(user);else guard.requireSuper(user);
        if(operation.equals("teleportPlayer") || operation.equals("teleportCoords")) {
            String[] parts=value.split("\\|",2);String source=moderation.info(user,UUID.fromString(parts[0])).get().name();
            String destination=operation.equals("teleportPlayer")?moderation.info(user,UUID.fromString(parts[1])).get().name():parts[1]+" (текущий мир игрока)";
            return new TelegramCommands.View("Телепортировать «"+source+"» → "+destination+"?",List.of(b(user,"✅ Подтвердить","do:"+operation,value,0,0),b(user,"↩ Действия игрока","playerActions",parts[0],0,1),homeButton(2)));
        }
        String target=value;
        String lookup=(operation.equals("scale") || operation.equals("flySpeed") || operation.equals("walkSpeed")) ? value.substring(0,value.indexOf('|')) : value;
        if(Set.of("ban","kick","unban","toggleLp","heal","kill","repair","scale","flySpeed","walkSpeed","addLp","removeLp").contains(operation)) target=moderation.info(user,UUID.fromString(lookup)).get().name()+"\nUUID: "+lookup;
        String label=switch(operation) {case "ban" -> "Бан на 5 минут: пока идёт расследование";case "kick" -> "Кик: пока идёт расследование";case "unban" -> "Снять бан";case "toggleLp" -> "Изменить группу LuckPerms admin";case "addLp" -> "Выдать группу LuckPerms admin";case "removeLp" -> "Удалить группу LuckPerms admin";case "heal" -> "Вылечить игрока";case "kill" -> "Убить игрока";case "repair" -> "Починить предметы";case "scale" -> "Установить размер "+value.substring(value.indexOf('|')+1);case "flySpeed" -> "Установить скорость полёта "+value.substring(value.indexOf('|')+1)+"x";case "walkSpeed" -> "Установить скорость передвижения "+value.substring(value.indexOf('|')+1)+"x";case "addAdmin" -> "Добавить Telegram-администратора";case "removeAdmin" -> "Убрать Telegram-администратора";case "chat" -> "Отправить в игровой чат";default -> throw new IllegalArgumentException();};
        return new TelegramCommands.View(label+"\n\n"+target+"\n\nПодтвердите действие.",List.of(b(user,"✅ Подтвердить","do:"+operation,value,0,0),homeButton(1)));
    }
    private TelegramCommands.View perform(long user,String operation,String value) throws Exception {
        String result;
        if(Set.of("ban","kick").contains(operation)) guard.requireAdmin(user);else guard.requireSuper(user);
        if(operation.equals("addAdmin") || operation.equals("removeAdmin")) {guard.changeAdmin(user,Long.parseLong(value),operation.equals("addAdmin")).get();result="Список администраторов обновлён.";}
        else if(operation.equals("chat")) result=moderation.broadcast(user,value).get();
        else if(operation.equals("teleportPlayer") || operation.equals("teleportCoords")) {String[] parts=value.split("\\|",2);result=moderation.teleport(user,UUID.fromString(parts[0]),operation.equals("teleportPlayer")?UUID.fromString(parts[1]):null,operation.equals("teleportCoords")?parts[1]:null).get();}
        else if(operation.equals("scale")) {int split=value.indexOf('|');result=moderation.scale(user,UUID.fromString(value.substring(0,split)),Double.parseDouble(value.substring(split+1))).get();}
        else if(operation.equals("flySpeed") || operation.equals("walkSpeed")) {int split=value.indexOf('|');result=moderation.speed(user,UUID.fromString(value.substring(0,split)),operation.equals("flySpeed") ? "fly" : "walk",Double.parseDouble(value.substring(split+1))).get();}
        else result=moderation.act(user,operation,UUID.fromString(value)).get();
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(operation.startsWith("teleport")) buttons.add(b(user,"↩ Назад к действиям игрока","playerActions",value.split("\\|",2)[0],0,0));
        if(Set.of("ban","kick","unban","toggleLp","addLp","removeLp","heal","kill","repair","scale","flySpeed","walkSpeed").contains(operation)) {
            String raw=operation.equals("scale") || operation.equals("flySpeed") || operation.equals("walkSpeed") ? value.substring(0,value.indexOf('|')) : value;
            buttons.add(b(user,"↩ Назад к действиям игрока","playerActions",raw,0,0));
        } else if(Set.of("addAdmin","removeAdmin").contains(operation)) buttons.add(b(user,"↩ Назад к администраторам","admins","",0,0));
        else if(operation.equals("chat")) buttons.add(b(user,"↩ Назад в супер-админку","superHome","",0,0));
        buttons.add(homeButton(1));
        return new TelegramCommands.View(result,List.copyOf(buttons));
    }
    private TelegramCommands.Button b(long user,String label,String op,String value,int page,int row) {
        String key="vg:"+UUID.randomUUID().toString().replace("-","");actions.put(key,new Action(user,op,value,page,System.currentTimeMillis()));
        // Keep navigation effectively permanent while bounding memory if a chat is very busy.
        long count=actions.values().stream().filter(action->action.user()==user).count();
        if(count>2048) actions.entrySet().stream().filter(entry->entry.getValue().user()==user)
                .min(Comparator.comparingLong(entry->entry.getValue().createdAt())).ifPresent(entry->actions.remove(entry.getKey()));
        return new TelegramCommands.Button(label,key,row);
    }
    private static TelegramCommands.Button homeButton(int row) {return new TelegramCommands.Button("👤 Кабинет","vg:home",row);}
    static TelegramCommands.View withHome(TelegramCommands.View view) {
        if(view==null) return new TelegramCommands.View("Откройте кабинет заново.",List.of(homeButton(0)));
        var buttons=new ArrayList<>(view.buttons());buttons.add(homeButton(1));return new TelegramCommands.View(view.text(),List.copyOf(buttons));
    }
}
