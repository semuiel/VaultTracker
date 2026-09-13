package ru.vaulttracker;

import java.util.*;

/** Only invoked for private messages. Every action is bound to the requesting Telegram user. */
final class TelegramGuardMenu {
    private record Action(long user,String operation,long value,int page,long expires) {}
    private final Map<String,Action> actions=new HashMap<>();
    private final Map<Long,Long> waiting=new HashMap<>();
    private final GuardService guard;
    private final TelegramCommands commands;
    TelegramGuardMenu(GuardService guard,TelegramCommands commands) {this.guard=guard;this.commands=commands;}

    TelegramCommands.Callback callback(long user,String data) throws Exception {
        cancelInput(user);
        if(data.equals("vg:home")) return new TelegramCommands.Callback(home(user),"",false);
        long now=System.currentTimeMillis();actions.entrySet().removeIf(e->e.getValue().expires()<now);
        Action action=actions.get(data);
        if(action==null || action.user()!=user) return new TelegramCommands.Callback(null,"Кнопка устарела. Откройте /menu.",true);
        if(Set.of("own","admin","link","time").contains(action.operation())) actions.remove(data);
        TelegramCommands.View view=switch(action.operation()) {
            case "link" -> new TelegramCommands.View(guard.generate(user).get(),List.of(new TelegramCommands.Button("Обновить кабинет","vg:home")));
            case "own" -> {guard.toggleOwn(user).get();yield home(user);}
            case "admin" -> {guard.toggleAdmin(user).get();yield home(user);}
            case "settings" -> settings(user);
            case "time" -> {guard.offlineSeconds(user,action.value()<0 ? null : action.value()).get();yield settings(user);}
            case "custom" -> {
                if(guard.account(user).get()==null) yield home(user);
                waiting.entrySet().removeIf(e->e.getValue()<System.currentTimeMillis());
                waiting.put(user,System.currentTimeMillis()+10*60*1000L);
                yield new TelegramCommands.View("Отправьте время отсутствия в секундах: от 0 до 31536000.\nНапример, 86400 — одни сутки.\nВвод действует 10 минут. Отмена: /cancel.",List.of(new TelegramCommands.Button("👤 Кабинет","vg:home")));
            }
            case "resources" -> {
                var account=guard.account(user).get();
                yield account==null ? home(user) : withHome(commands.ownResources(user,account.uuid()));
            }
            case "history" -> history(user,action.page());
            case "event" -> detail(user,action.value(),action.page());
            default -> home(user);
        };
        return new TelegramCommands.Callback(view,"",false);
    }
    TelegramCommands.View home(long user) throws Exception {
        var account=guard.account(user).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text="👤 Личный кабинет";
        if(account==null) {text+="\nПривяжите персонажа командой в Minecraft.";buttons.add(button(user,"🔗 Привязать персонажа","link",0,0,0));}
        else {
            text+="\nПерсонаж: "+account.name()+"\nВаши уведомления: "+(account.notifications() ? "включены" : "выключены");
            buttons.add(button(user,"📦 Мои ресурсы","resources",0,0,0));
            buttons.add(button(user,(account.notifications() ? "🔔 Отключить" : "🔕 Включить")+" мои уведомления","own",0,0,1));
            buttons.add(button(user,"⚙️ Настройки","settings",0,0,2));
        }
        if(guard.admin(user)) {
            boolean enabled=guard.adminAlerts(user).get();
            text+="\nУведомления администратора: "+(enabled ? "включены" : "выключены");
            buttons.add(button(user,(enabled ? "🔔 Отключить" : "🔕 Включить")+" уведомления администратора","admin",0,0,3));
            buttons.add(button(user,"📜 События за 30 дней","history",0,0,4));
        }
        if(!guard.enabled()) text+="\nОхрана отключена администратором в guard.yml.";
        text+="\n\nРесурсы — содержимое ваших зарегистрированных хранилищ. Уведомления приходят сюда, в личный чат.";
        buttons.add(new TelegramCommands.Button("🔎 Поиск","vg:search",5));
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    void cancelInput(long user) {waiting.remove(user);}
    TelegramCommands.View input(long user,String text) throws Exception {
        Long expires=waiting.get(user);if(expires==null) return null;
        if(expires<System.currentTimeMillis()) {waiting.remove(user);return new TelegramCommands.View("Время ввода истекло. Откройте настройки снова.",List.of(new TelegramCommands.Button("👤 Кабинет","vg:home")));}
        long seconds;
        try {seconds=Long.parseLong(text.trim());if(seconds<0 || seconds>GuardService.MAX_OFFLINE_SECONDS) throw new NumberFormatException();}
        catch(NumberFormatException e) {return new TelegramCommands.View("Введите целое число секунд от 0 до 31536000. Одни сутки — 86400. Отмена: /cancel.");}
        guard.offlineSeconds(user,seconds).get();waiting.remove(user);return settings(user);
    }
    private TelegramCommands.View settings(long user) throws Exception {
        if(guard.account(user).get()==null) return home(user);
        Long personal=guard.offlineSeconds(user).get();
        String text="⚙️ Настройки уведомлений\nВремя отсутствия: "+duration(personal==null ? guard.defaultOfflineSeconds() : personal)
                +(personal==null ? " (по настройке сервера)" : " (личное)")
                +"\n\nПосле этого времени новые изменения ваших хранилищ будут приходить в личку, если уведомления включены."
                +"\nОтсчёт идёт от выхода из игры. Настройка действует только на ваши уведомления; администраторы используют время из guard.yml.";
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        long[] values={0,120,300,900,3600,21600,86400,604800};
        for(int i=0;i<values.length;i++) buttons.add(button(user,duration(values[i]),"time",values[i],0,i/2));
        buttons.add(button(user,"✏️ Своё время","custom",0,0,4));
        buttons.add(button(user,"Как на сервере","time",-1,0,5));
        buttons.add(new TelegramCommands.Button("👤 Кабинет","vg:home",6));
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    private static String duration(long seconds) {
        if(seconds==0) return "Сразу после выхода";
        if(seconds%86400==0) return seconds/86400+" дн.";
        if(seconds%3600==0) return seconds/3600+" ч.";
        if(seconds%60==0) return seconds/60+" мин.";
        return seconds+" сек.";
    }
    private TelegramCommands.View history(long user,int page) throws Exception {
        var events=guard.history(user,page,6).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text="📜 События охраны за 30 дней • страница "+(page+1)+"\nНажмите событие для просмотра изменений.";
        if(events.isEmpty()) text+="\nСобытий нет.";
        for(int i=0;i<events.size();i++) {
            var e=events.get(i);
            String time=java.time.Instant.ofEpochMilli(e.time()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
            buttons.add(button(user,"#"+e.id()+" • "+time+" • "+e.name(),"event",e.id(),0,i));
        }
        if(page>0) buttons.add(button(user,"◀ Назад","history",0,page-1,6));
        if(events.size()==6) buttons.add(button(user,"Вперёд ▶","history",0,page+1,6));
        buttons.add(new TelegramCommands.Button("👤 Кабинет","vg:home",7));
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    private TelegramCommands.View detail(long user,long id,int page) throws Exception {
        var event=guard.event(user,id).get();
        if(event==null) return new TelegramCommands.View("Событие уже удалено по сроку хранения.",List.of(new TelegramCommands.Button("👤 Кабинет","vg:home")));
        int pages=Math.max(1,(event.changes().size()+7)/8);page=Math.max(0,Math.min(page,pages-1));
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(page>0) buttons.add(button(user,"◀ Назад","event",id,page-1,0));
        if(page+1<pages) buttons.add(button(user,"Вперёд ▶","event",id,page+1,0));
        buttons.add(button(user,"📜 История","history",0,0,1));
        buttons.add(new TelegramCommands.Button("👤 Кабинет","vg:home",1));
        return new TelegramCommands.View("Событие #"+id+" • "+(page+1)+"/"+pages+"\n"+GuardService.eventText(event,page*8,8),List.copyOf(buttons));
    }
    private TelegramCommands.Button button(long user,String label,String operation,long value,int page,int row) {
        String key="vg:"+UUID.randomUUID().toString().replace("-","");
        actions.put(key,new Action(user,operation,value,page,System.currentTimeMillis()+30*60*1000L));
        return new TelegramCommands.Button(label,key,row);
    }
    static TelegramCommands.View withHome(TelegramCommands.View view) {
        if(view==null) return new TelegramCommands.View("Откройте кабинет заново.",List.of(new TelegramCommands.Button("👤 Кабинет","vg:home")));
        var buttons=new ArrayList<>(view.buttons());buttons.add(new TelegramCommands.Button("👤 Кабинет","vg:home",1));
        return new TelegramCommands.View(view.text(),List.copyOf(buttons));
    }
}
