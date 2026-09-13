package ru.vaulttracker;

import java.util.*;

/** Private cabinet. Permissions are checked on every read and confirmed action. */
final class TelegramGuardMenu {
    private record Action(long user,String op,String value,int page,long expires) {}
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
        long now=System.currentTimeMillis();actions.entrySet().removeIf(e->e.getValue().expires()<now);
        Action a=actions.get(data);
        if(a==null || a.user()!=user) return new TelegramCommands.Callback(null,"Кнопка устарела. Откройте /menu.",true);
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
            case "history" -> view=history(user,a.page(),false);
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
            default -> {
                if(a.op().startsWith("browse:")) view=players(user,a.op().substring(7),a.page());
                else if(a.op().equals("person")) view=person(user,UUID.fromString(a.value()));
                else if(a.op().startsWith("confirm:")) view=confirm(user,a.op().substring(8),a.value());
                else if(a.op().startsWith("do:")) view=perform(user,a.op().substring(3),a.value());
                else view=home(user);
            }
        }
        return answer(view);
    }
    private static TelegramCommands.Callback answer(TelegramCommands.View view) {return new TelegramCommands.Callback(view,"",false);}
    TelegramCommands.View home(long user) throws Exception {
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
                b(user,"⏳ Временный бан · 5 минут","browse:ban","",0,1),b(user,"🚪 Кикнуть игрока","browse:kick","",0,2),homeButton(3)));
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
    private TelegramCommands.View history(long user,int page,boolean own) throws Exception {
        var events=own ? guard.ownHistory(user,page,6).get() : guard.history(user,page,6).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text=(own ? "📜 Мои события за 2 дня" : "🗂 События охраны за 30 дней")+" • страница "+(page+1);
        if(events.isEmpty()) text+="\nСобытий нет.";
        for(int i=0;i<events.size();i++) {var e=events.get(i);String time=java.time.Instant.ofEpochMilli(e.time()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
            buttons.add(b(user,"#"+e.id()+" • "+time+" • "+e.name(),own ? "ownEvent" : "event",Long.toString(e.id()),0,i));}
        if(page>0) buttons.add(b(user,"◀ Назад",own ? "ownHistory" : "history","",page-1,6));
        if(events.size()==6) buttons.add(b(user,"Вперёд ▶",own ? "ownHistory" : "history","",page+1,6));
        buttons.add(homeButton(7));return new TelegramCommands.View(text,List.copyOf(buttons));
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
    private TelegramCommands.View players(long user,String mode,int page) throws Exception {
        guard.requireAdmin(user);if(Set.of("online","all","banned").contains(mode)) guard.requireSuper(user);
        if(moderation==null) return withHome(new TelegramCommands.View("Управление сервером недоступно."));
        var people=moderation.players(user,mode.equals("kick") ? "online" : mode.equals("ban") ? "all" : mode).get();
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*6;i<Math.min(people.size(),page*6+6);i++) {var p=people.get(i);buttons.add(b(user,(p.online() ? "🟢 " : "👤 ")+p.name(),mode.equals("ban") || mode.equals("kick") ? "confirm:"+mode : "person",p.uuid().toString(),0,i-page*6));}
        if(page>0) buttons.add(b(user,"◀ Назад","browse:"+mode,"",page-1,6));if((page+1)*6<people.size()) buttons.add(b(user,"Вперёд ▶","browse:"+mode,"",page+1,6));
        buttons.add(homeButton(7));return new TelegramCommands.View("Игроки • страница "+(page+1)+" • всего "+people.size(),List.copyOf(buttons));
    }
    private TelegramCommands.View person(long user,UUID uuid) throws Exception {
        guard.requireSuper(user);var p=moderation.info(user,uuid).get();String id=uuid.toString();
        return new TelegramCommands.View("👤 "+p.name()+"\n"+p.details(),List.of(b(user,"⏳ Бан на 5 минут","confirm:ban",id,0,0),b(user,"🚪 Кик","confirm:kick",id,0,1),
                b(user,"🔓 Разбан","confirm:unban",id,0,2),b(user,"Снять группу LuckPerms admin","confirm:removeLp",id,0,3),homeButton(4)));
    }
    private TelegramCommands.View confirm(long user,String operation,String value) throws Exception {
        actions.entrySet().removeIf(e->e.getValue().user()==user && e.getValue().op().startsWith("do:"));
        if(Set.of("ban","kick").contains(operation)) guard.requireAdmin(user);else guard.requireSuper(user);
        String target=value;
        if(Set.of("ban","kick","unban","removeLp").contains(operation)) target=moderation.info(user,UUID.fromString(value)).get().name()+"\nUUID: "+value;
        String label=switch(operation) {case "ban" -> "Бан на 5 минут: пока идёт расследование";case "kick" -> "Кик: пока идёт расследование";case "unban" -> "Снять бан";case "removeLp" -> "Удалить группу LuckPerms admin";case "addAdmin" -> "Добавить Telegram-администратора";case "removeAdmin" -> "Убрать Telegram-администратора";case "chat" -> "Отправить в игровой чат";default -> throw new IllegalArgumentException();};
        return new TelegramCommands.View(label+"\n\n"+target+"\n\nПодтвердите действие.",List.of(b(user,"✅ Подтвердить","do:"+operation,value,0,0),homeButton(1)));
    }
    private TelegramCommands.View perform(long user,String operation,String value) throws Exception {
        String result;
        if(Set.of("ban","kick").contains(operation)) guard.requireAdmin(user);else guard.requireSuper(user);
        if(operation.equals("addAdmin") || operation.equals("removeAdmin")) {guard.changeAdmin(user,Long.parseLong(value),operation.equals("addAdmin")).get();result="Список администраторов обновлён.";}
        else if(operation.equals("chat")) result=moderation.broadcast(user,value).get();
        else result=moderation.act(user,operation,UUID.fromString(value)).get();
        return withHome(new TelegramCommands.View(result));
    }
    private TelegramCommands.Button b(long user,String label,String op,String value,int page,int row) {
        String key="vg:"+UUID.randomUUID().toString().replace("-","");actions.put(key,new Action(user,op,value,page,System.currentTimeMillis()+1800000));
        return new TelegramCommands.Button(label,key,row);
    }
    private static TelegramCommands.Button homeButton(int row) {return new TelegramCommands.Button("👤 Кабинет","vg:home",row);}
    static TelegramCommands.View withHome(TelegramCommands.View view) {
        if(view==null) return new TelegramCommands.View("Откройте кабинет заново.",List.of(homeButton(0)));
        var buttons=new ArrayList<>(view.buttons());buttons.add(homeButton(1));return new TelegramCommands.View(view.text(),List.copyOf(buttons));
    }
}
