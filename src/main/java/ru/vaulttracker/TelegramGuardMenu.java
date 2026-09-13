package ru.vaulttracker;

import java.util.*;

/** Only invoked for private messages. Every action is bound to the requesting Telegram user. */
final class TelegramGuardMenu {
    private record Action(long user,String operation,long value,int page,long expires) {}
    private final Map<String,Action> actions=new HashMap<>();
    private final GuardService guard;
    private final TelegramCommands commands;
    TelegramGuardMenu(GuardService guard,TelegramCommands commands) {this.guard=guard;this.commands=commands;}

    TelegramCommands.Callback callback(long user,String data) throws Exception {
        if(data.equals("vg:home")) return new TelegramCommands.Callback(home(user),"",false);
        long now=System.currentTimeMillis();actions.entrySet().removeIf(e->e.getValue().expires()<now);
        Action action=actions.get(data);
        if(action==null || action.user()!=user) return new TelegramCommands.Callback(null,"Кнопка устарела. Откройте /menu.",true);
        if(Set.of("own","admin","link").contains(action.operation())) actions.remove(data);
        TelegramCommands.View view=switch(action.operation()) {
            case "link" -> new TelegramCommands.View(guard.generate(user).get(),List.of(new TelegramCommands.Button("Обновить кабинет","vg:home")));
            case "own" -> {guard.toggleOwn(user).get();yield home(user);}
            case "admin" -> {guard.toggleAdmin(user).get();yield home(user);}
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
        }
        if(guard.admin(user)) {
            boolean enabled=guard.adminAlerts(user).get();
            text+="\nУведомления администратора: "+(enabled ? "включены" : "выключены");
            buttons.add(button(user,(enabled ? "🔔 Отключить" : "🔕 Включить")+" уведомления администратора","admin",0,0,2));
            buttons.add(button(user,"📜 События за 30 дней","history",0,0,3));
        }
        if(!guard.enabled()) text+="\nОхрана отключена администратором в guard.yml.";
        text+="\n\nРесурсы — содержимое ваших зарегистрированных хранилищ. Уведомления приходят сюда, в личный чат.";
        buttons.add(new TelegramCommands.Button("🔎 Поиск","vg:search",4));
        return new TelegramCommands.View(text,List.copyOf(buttons));
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
