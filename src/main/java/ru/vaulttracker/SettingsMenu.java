package ru.vaulttracker;

import java.util.*;

/** Telegram UI screens for settings. */
final class SettingsMenu extends TelegramMenuSection {
    SettingsMenu(TelegramGuardMenu root) {super(root);}

    TelegramCommands.View settings(long user,boolean root) throws Exception {
        if(!root) return playerSettings(user);
        guard.requireSuper(user);
        Long personal=guard.superDelay(user);
        String text="👑 Ваши администраторские уведомления"+"\nВремя отсутствия: "+duration(personal)
                +"\n«Всегда» — в том числе во время игры. «После выхода» — сразу после выхода. «2 дня» — после двух дней отсутствия."
                +"\n\nЧтобы получать уведомления, повесьте на хранилище табличку [v] или [vault] либо вставьте бумагу в рамку на нём.";
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        long[] values={-1,0,120,300,900,3600,21600,86400,172800,604800};
        for(long value:values) buttons.add(b(user,duration(value),"rootTime",Long.toString(value),0,row++));
        buttons.add(b(user,"✏️ Своё время","rootCustom","",0,row++));buttons.add(b(user,"Как на сервере","rootTime","-2",0,row++));
        buttons.add(b(user,"↩ Личные настройки","superPreferences","",0,row++));buttons.add(homeButton(row));return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    TelegramCommands.View playerSettings(long user) throws Exception {
        if(guard.account(user).get()==null) return root.home(user);
        return new TelegramCommands.View("⚙️ Настройки игрока",List.of(
                b(user,"🔔 Уведомления","notifications","",0,0),
                b(user,"🏷 Тег","tagMenu","",0,1),
                b(user,"👥 Друзья","friends","",0,2),
                b(user,"🔃 Сортировка предметов","itemSorting","",0,3),
                new TelegramCommands.Button("↩ Назад","vg:home",4),homeButton(5)));
    }
    TelegramCommands.View itemSorting(long user) throws Exception {
        if(guard.account(user).get()==null) return root.home(user);
        var selected=guard.itemSortMode(user);
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        for(var option:ItemSorting.Mode.values()) {
            String label=switch(option) {
                case MINECRAFT -> "🌍 Популярное в Minecraft";
                case SERVER -> "🔥 Популярное на сервере";
                case ALPHABETICAL -> "🔤 По алфавиту";
                case AMOUNT -> "📊 По количеству";
            };
            buttons.add(b(user,(selected==option?"✅ ":"▫️ ")+label,"itemSortMode",option.name(),0,row++));
        }
        buttons.add(b(user,"↩️ Назад","settings","",0,row++));buttons.add(homeButton(row));
        return new TelegramCommands.View("🔃 Сортировка предметов\nВыбрано: "+switch(selected) {
            case MINECRAFT -> "Популярное в Minecraft";
            case SERVER -> "Популярное на сервере";
            case ALPHABETICAL -> "По алфавиту";
            case AMOUNT -> "По количеству";
        },List.copyOf(buttons));
    }
    TelegramCommands.View notifications(long user) throws Exception {
        var account=guard.account(user).get();if(account==null) return root.home(user);
        boolean friends=guard.friendAlerts(user).get(),messages=guard.gameMessageNotifications(user).get();
        boolean telegramChat=guard.telegramGameChatEnabled(account.uuid());
        return new TelegramCommands.View("🔔 Уведомления\nВыберите, какие уведомления получать.",List.of(
                b(user,"🔔 Личные уведомления хранилищ","ownNotifications","",0,0),
                b(user,"👥 Уведомления из хранилищ друзей: "+(friends?"ВКЛ":"ВЫКЛ"),"friendAlerts","",0,1),
                b(user,"💬 Уведомления о сообщениях: "+(messages?"ВКЛ":"ВЫКЛ"),"gameMessageNotifications","",0,2),
                b(user,"💬 Сообщения из Telegram в игре: "+(telegramChat?"ВКЛ":"ВЫКЛ"),"telegramGameChat","",0,3),
                b(user,"↩️ Назад","settings","",0,4),homeButton(5)));
    }
    TelegramCommands.View ownNotifications(long user) throws Exception {
        var account=guard.account(user).get();if(account==null) return root.home(user);
        Long personal=guard.offlineSeconds(user).get();long selected=personal==null?guard.defaultOfflineSeconds():personal;
        String text="🔔 Личные уведомления хранилищ\nСостояние: "+(account.notifications()?"ВКЛ":"ВЫКЛ")
                +"\nРежим: "+duration(selected)+(personal==null?" (настройка сервера)":"")
                +"\nЧтобы получать уведомления, повесьте на хранилище табличку [v] или [vault] либо вставьте бумагу в рамку на нём.";
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        buttons.add(b(user,"Личные уведомления: "+(account.notifications()?"ВКЛ":"ВЫКЛ"),"own","",0,row++));
        for(long value:new long[]{-1,0,172800}) {
            String label=value<0?"Всегда":value==0?"После выхода":"2 дня";
            buttons.add(b(user,(selected==value?"✅ ":"▫️ ")+label,"time",Long.toString(value),0,row++));
        }
        buttons.add(b(user,"↩️ Назад","notifications","",0,row++));buttons.add(homeButton(row));
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    TelegramCommands.View tagMenu(long user) throws Exception {
        var account=guard.account(user).get();if(account==null) return root.home(user);
        return new TelegramCommands.View("🏷 Тег\nИгровой ник: "+account.name(),List.of(
                b(user,"🏷 Применить тег «"+account.name()+"»","tagApply","",0,0),
                b(user,"♻️ Сбросить тег","tagReset","",0,1),
                b(user,"↩️ Назад","settings","",0,2),homeButton(3)));
    }
    TelegramCommands.View tag(long user,boolean apply) throws Exception {
        var account=guard.account(user).get();
        if(account==null) return root.home(user);
        if(tags==null) return new TelegramCommands.View("Управление Telegram-тегами сейчас недоступно.",List.of(b(user,"↩️ Назад к тегу","tagMenu","",0,0),homeButton(1)));
        TelegramTagManager.Result result=apply ? tags.apply(user,account.name()) : tags.reset(user);
        if(result.complete()){guard.setTag(user,apply).get();BotAnalytics.success("settings",apply?"tagApply":"tagReset");}
        return new TelegramCommands.View(result.text(),List.of(b(user,"↩️ Назад к тегу","tagMenu","",0,0),homeButton(1)));
    }
    static String duration(long seconds) {
        if(seconds<0) return "Всегда";if(seconds==0) return "Сразу после выхода";
        if(seconds%86400==0) return seconds/86400+" дн.";if(seconds%3600==0) return seconds/3600+" ч.";
        if(seconds%60==0) return seconds/60+" мин.";return seconds+" сек.";
    }

    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("SettingsMenu");
        scope.on("settings",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=settings(user,false);
            return view;
        });
        scope.on("itemSorting",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=itemSorting(user);
            return view;
        });
        scope.on("itemSortMode",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.itemSortMode(user,ItemSorting.Mode.valueOf(a.value())).get();BotAnalytics.success("settings","itemSortMode");view=itemSorting(user);}
            return view;
        });
        scope.on("notifications",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=notifications(user);
            return view;
        });
        scope.on("ownNotifications",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=ownNotifications(user);
            return view;
        });
        scope.on("tagMenu",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=tagMenu(user);
            return view;
        });
        scope.on("own",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,true, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.toggleOwn(user).get();BotAnalytics.success("settings","own");view=ownNotifications(user);}
            return view;
        });
        scope.on("friendAlerts",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.toggleFriendAlerts(user).get();BotAnalytics.success("settings","friendAlerts");view=notifications(user);}
            return view;
        });
        scope.on("gameMessageNotifications",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.toggleGameMessageNotifications(user).get();BotAnalytics.success("settings","gameMessageNotifications");view=notifications(user);}
            return view;
        });
        scope.on("telegramGameChat",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false,(user,a,data)->{
            guard.toggleTelegramGameChat(user).get();BotAnalytics.success("settings","telegramGameChat");return notifications(user);
        });
        scope.on("tagApply",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,true, (user,a,data)-> {
            TelegramCommands.View view;
            view=tag(user,true);
            return view;
        });
        scope.on("tagReset",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,true, (user,a,data)-> {
            TelegramCommands.View view;
            view=tag(user,false);
            return view;
        });
        scope.on("time",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,true, (user,a,data)-> {
            TelegramCommands.View view;
            {long value=Long.parseLong(a.value());guard.offlineSeconds(user,value== -2 ? null : value).get();BotAnalytics.success("settings","time");view=ownNotifications(user);}
            return view;
        });
        scope.on("custom",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=prompt(user,"time","Отправьте число секунд от 0 до 31536000. Например, 172800 — двое суток.");
            return view;
        });
    }
    TelegramCommands.View timeInput(long user,TelegramGuardMenu.Input pending,long value) throws Exception {
        if(pending.kind().equals("rootTime")) guard.superDelay(user,value).get();else guard.offlineSeconds(user,value).get();
        BotAnalytics.success(pending.kind().equals("rootTime")?"root":"settings","notificationTime");
        waiting.remove(user);
        return settings(user,pending.kind().equals("rootTime"));
    }
}
