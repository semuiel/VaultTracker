package ru.vaulttracker;

import java.util.*;
import java.io.IOException;

/** Telegram UI screens for superadmin. */
final class SuperAdminMenu extends TelegramMenuSection {
    SuperAdminMenu(TelegramGuardMenu root) {super(root);}

    TelegramCommands.View adminMenu(long user) {
        guard.requireSuper(user);
        adminPreview.remove(user);
        return new TelegramCommands.View("🛠 Админ",List.of(
                b(user,"🛠 Функции администратора","adminHome","",0,0),
                b(user,"👑 Суперадминистратор","superHome","",0,1),
                new TelegramCommands.Button("↩️ Назад","vg:home",2),homeButton(3)));
    }
    TelegramCommands.View superHome(long user) throws Exception {
        guard.requireSuper(user);
        adminPreview.remove(user);
        var account=guard.account(user).get();
        List<TelegramCommands.Button> buttons=new ArrayList<>(List.of(
                b(user,"👮 Администраторы бота","admins","",0,0),
                b(user,"👥 Список игроков","browse:online","",0,1),
                b(user,account==null?"👤 Привязать персонажа":"👤 "+account.name(),"selfActions","",0,2),
                b(user,"⏱ Личный срок админских супер уведомлений","rootSettings","",0,3)));
        int row=4;
        if(guard.rootAdmin(user)) buttons.add(b(user,"🔐 Root","rootHome","",0,row++));
        buttons.add(b(user,"↩ Назад","adminMenu","",0,row++));buttons.add(homeButton(row));
        return new TelegramCommands.View("👑 Суперадминистратор",List.copyOf(buttons));
    }
    TelegramCommands.View rootHome(long user) {
        guard.requireRoot(user);
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        buttons.add(b(user,"👑 Суперадминистраторы","supers","",0,row++));
        buttons.add(b(user,"👮 Администраторы бота","admins","",0,row++));
        if(guard.primaryRoot(user)) buttons.add(b(user,"👥 Список Root","rootList","",0,row++));
        buttons.add(b(user,"💝 Пожертвования","donationAdmin","",0,row++));
        buttons.add(b(user,"🧒 Дети","children","",0,row++));
        buttons.add(b(user,"♻️ Перезагрузить VaultTracker","reloadPlugin","",0,row++));
        buttons.add(b(user,"📄 Логи","rootLogs","",0,row++));
        buttons.add(b(user,"🖥 Консоль","rootConsole","",0,row++));
        buttons.add(b(user,"↩ Назад","superHome","",0,row++));buttons.add(homeButton(row));
        return new TelegramCommands.View("🔐 Root",List.copyOf(buttons));
    }
    TelegramCommands.View rootList(long user,int requested) {
        List<Long> ids=guard.rootIds(user);
        int pages=Math.max(1,(ids.size()+PAGE_SIZE-1)/PAGE_SIZE);
        int page=Math.max(0,Math.min(requested,pages-1));
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*PAGE_SIZE;i<Math.min(ids.size(),(page+1)*PAGE_SIZE);i++) {
            long id=ids.get(i);
            String label="🔐 "+id+(guard.primaryRoot(id)?" · основной Root":"");
            buttons.add(b(user,label,"rootDetails",Long.toString(id),0,i-page*PAGE_SIZE));
        }
        int row=PAGE_SIZE;
        if(page>0) buttons.add(b(user,"⬅️ Назад","rootList","",page-1,row));
        if(page+1<pages) buttons.add(b(user,"Вперёд ➡️","rootList","",page+1,row));
        buttons.add(b(user,"➕ Добавить Root","rootAdd","",0,++row));
        buttons.add(b(user,"↩️ Root","rootHome","",0,++row));
        return new TelegramCommands.View("👥 Root · "+(page+1)+"/"+pages+"\nОсновной Root из guard.yml защищён от удаления.",List.copyOf(buttons));
    }
    private TelegramCommands.View rootDetails(long user,long target) {
        guard.requirePrimaryRoot(user);
        if(!guard.rootIds(user).contains(target)) return rootList(user,0);
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(guard.canRemoveRoot(user,target)) buttons.add(b(user,"➖ Удалить Root","rootRemoveConfirm",Long.toString(target),0,0));
        buttons.add(b(user,"↩️ К списку","rootList","",0,1));
        String note=guard.primaryRoot(target)?"Основной Root защищён от удаления.":target==user?"Собственную роль удалить нельзя.":"Можно отозвать права Root.";
        return new TelegramCommands.View("🔐 Root\nTelegram ID: "+target+"\n"+guard.telegramProfile(target)+"\n"+note,List.copyOf(buttons));
    }
    TelegramCommands.View console(long user) {
        guard.requireRoot(user);
        return new TelegramCommands.View("🖥 Консоль",List.of(
                b(user,"⌨️ Выполнить команду","rootConsoleInput","",0,0),
                b(user,"🕘 История команд","rootConsoleHistory","",0,1),
                b(user,"↩️ Назад","rootHome","",0,2)));
    }
    TelegramCommands.View consoleHistory(long user) throws Exception {
        guard.requireRoot(user);
        var rows=guard.rootCommands(user).get();List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        for(var command:rows) {
            String label=command.command();
            if(label.codePointCount(0,label.length())>48) label=label.substring(0,label.offsetByCodePoints(0,47))+"…";
            buttons.add(b(user,label,"rootConsoleRun",Long.toString(command.id()),0,row++));
        }
        if(!rows.isEmpty()) buttons.add(b(user,"🗑 Очистить историю","rootConsoleClear","",0,row++));
        buttons.add(b(user,"↩️ Назад","rootConsole","",0,row));
        return new TelegramCommands.View("🕘 История команд"+(rows.isEmpty()?"\nПока пусто.":"\nНажатие сразу повторит команду от консоли."),List.copyOf(buttons));
    }
    private TelegramCommands.View consoleResult(long user,String message) {
        var menu=console(user);return new TelegramCommands.View(message+"\n\n"+menu.text(),menu.buttons());
    }
    TelegramCommands.View runConsoleCommand(long user,String raw) throws Exception {
        guard.requireRoot(user);
        String command=raw==null?"":raw.strip();
        if(command.startsWith("/")) command=command.substring(1).stripLeading();
        if(command.isBlank() || command.length()>2048 || command.indexOf('\n')>=0 || command.indexOf('\r')>=0)
            return prompt(user,"rootConsoleInput","Введите одну команду без / (до 2048 символов).");
        if(moderation==null) return consoleResult(user,"Консоль сейчас недоступна.");
        boolean dispatched;
        try {dispatched=moderation.consoleCommand(user,command).get();}
        catch(java.util.concurrent.ExecutionException failure) {
            if(failure.getCause() instanceof SecurityException denied) throw denied;
            return consoleResult(user,"Не удалось выполнить команду. Проверьте консоль сервера.");
        }
        if(!dispatched) return consoleResult(user,"Команда не найдена: "+command);
        guard.rememberRootCommand(user,command).get();
        return consoleResult(user,"✅ Выполнено: "+command);
    }
    TelegramCommands.View logs(long user) {
        guard.requireRoot(user);
        return new TelegramCommands.View("📄 Логи latest.log",List.of(
                b(user,"📄 Полный latest.log","rootLogFormat","full",0,0),
                b(user,"✂️ Последние строки","rootLogLines","",0,1),
                b(user,"↩️ Назад","rootHome","",0,2)));
    }
    private TelegramCommands.View logLines(long user) {
        guard.requireRoot(user);
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        for(int n:List.of(100,200,500,1000)) buttons.add(b(user,Integer.toString(n)+" строк","rootLogFormat",Integer.toString(n),0,row++));
        buttons.add(b(user,"Другое число","rootLogCustom","",0,row++));
        buttons.add(b(user,"↩️ Назад","rootLogs","",0,row));
        return new TelegramCommands.View("✂️ Сколько последних строк отправить?",List.copyOf(buttons));
    }
    private TelegramCommands.View logFormat(long user,String amount) {
        guard.requireRoot(user);
        if(!amount.equals("full")) {int n=Integer.parseInt(amount);if(n<1 || n>RootLogDocument.MAX_LINES) throw new IllegalArgumentException("Некорректное число строк");}
        return new TelegramCommands.View("Выберите формат latest.log",List.of(
                b(user,".log","rootLogSend",amount+"|log",0,0),
                b(user,".md","rootLogSend",amount+"|md",0,1),
                b(user,"↩️ Назад",amount.equals("full")?"rootLogs":"rootLogLines","",0,2)));
    }
    TelegramCommands.View sendLogs(long user,Integer lines,boolean markdown) {
        guard.requireRoot(user);
        if(api==null || moderation==null) return new TelegramCommands.View("Отправка логов сейчас недоступна.",List.of(b(user,"↩️ Логи","rootLogs","",0,0)));
        try(var document=RootLogDocument.prepare(moderation.latestLogPath(),lines,markdown)) {
            guard.requireRoot(user);
            api.sendDocument(user,document.path(),document.filename());
            return new TelegramCommands.View("📄 "+document.filename()+" отправлен.",List.of(b(user,"↩️ Логи","rootLogs","",0,0)));
        } catch(RootLogDocument.FileTooLarge large) {
            return new TelegramCommands.View(large.getMessage(),List.of(b(user,"✂️ Последние строки","rootLogLines","",0,0)));
        } catch(IOException | RuntimeException failure) {
            if(failure instanceof SecurityException denied) throw denied;
            String reason=String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
            if(reason.contains("too big") || reason.contains("too large") || reason.contains("file is too"))
                return new TelegramCommands.View("Файл слишком большой для Telegram. Выберите последние строки.",List.of(b(user,"✂️ Последние строки","rootLogLines","",0,0)));
            return new TelegramCommands.View("Не удалось прочитать или отправить latest.log.",List.of(b(user,"↩️ Логи","rootLogs","",0,0)));
        }
    }
    TelegramCommands.View children(long user,int requested,String filter) {
        var rows=guard.children(user).stream().filter(c->c.name().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))).toList();
        int pages=Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE),page=Math.max(0,Math.min(requested,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();
        buttons.add(b(user,"➕ Добавить ребёнка","childAdd","",0,0));buttons.add(b(user,"➖ Удалить ребёнка","childDelete","",0,1));
        for(int i=page*PAGE_SIZE;i<Math.min(rows.size(),(page+1)*PAGE_SIZE);i++) buttons.add(b(user,rows.get(i).name(),"childRemoveConfirm",rows.get(i).name(),0,i-page*PAGE_SIZE+2));
        if(page>0) buttons.add(b(user,"⬅ Назад","children",filter,page-1,22));if(page+1<pages) buttons.add(b(user,"Вперёд ➡","children",filter,page+1,22));
        buttons.add(b(user,"🔎 Поиск","childSearch","",0,23));buttons.add(b(user,"↩ Назад","rootHome","",0,24));buttons.add(homeButton(25));
        return new TelegramCommands.View("🧒 Дети · "+(page+1)+"/"+pages+"\n+2 сердца и защита 20%. Размер доступен в связанном личном кабинете."+(rows.isEmpty()?"\nСписок пуст.":""),List.copyOf(buttons));
    }
    TelegramCommands.View childResult(long user,String result) {var menu=children(user,0,"");return new TelegramCommands.View(result+"\n\n"+menu.text(),menu.buttons());}
    TelegramCommands.View childSizes(long user) throws Exception {
        var account=guard.account(user).get();if(account==null||guard.child(account.uuid())==null) throw new SecurityException("Функция доступна только детям с привязанным аккаунтом");
        return new TelegramCommands.View("📏 Размер персонажа",List.of(b(user,"Гном · 0.6","childSize","0.6",0,0),b(user,"Карлик · 0.75","childSize","0.75",0,1),b(user,"Вернуть нормальный размер","childSize","1",0,2),new TelegramCommands.Button("↩ Назад","vg:home",3),homeButton(4)));
    }
    TelegramCommands.View capabilities(long user) {
        guard.requireSuper(user);List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        Map<String,String> labels=Map.ofEntries(Map.entry("bastionTrust","Доверие Bastion"),Map.entry("freeze","Заморозка и разморозка"),Map.entry("ban","Бан на 5 минут"),Map.entry("permanentBan","Бессрочный бан"),Map.entry("kick","Кик"),Map.entry("players","Список игроков"),Map.entry("inventory","Инвентарь"),Map.entry("ender","Эндер-сундук"),Map.entry("unban","Разбан"),Map.entry("heal","Лечение"),Map.entry("kill","Убийство"),Map.entry("repair","Ремонт предметов"),Map.entry("scale","Размер игрока"),Map.entry("flySpeed","Скорость полёта"),Map.entry("walkSpeed","Скорость движения"),Map.entry("teleport","Телепорт к игроку"),Map.entry("teleportCoords","Телепорт на координаты"),Map.entry("luckPerms","LuckPerms admin"),Map.entry("op","Выдать OP"),Map.entry("deop","Забрать OP"),Map.entry("chat","Игровой чат"),Map.entry("manageAdmins","Управление администраторами"));
        for(String capability:GuardService.ADMIN_CAPABILITIES) {boolean enabled=guard.capability(capability);buttons.add(b(user,(enabled?"✅ ":"❌ ")+labels.get(capability),"toggleCapability",capability,0,row++));}
        buttons.add(b(user,"↩ Администраторы бота","admins","",0,row++));buttons.add(homeButton(row));
        return new TelegramCommands.View("🧩 Возможности обычных администраторов\nУведомления доступны всегда и здесь не отключаются.",List.copyOf(buttons));
    }
    TelegramCommands.View admins(long user,int page,String filter) {return admins(user,page,filter,false);}
    TelegramCommands.View admins(long user,int page,String filter,boolean supers) {
        if(supers) guard.requireRoot(user);else guard.requireSuper(user);
        String listOp=supers?"supers":"admins";
        var ids=guard.adminIds().stream().filter(id->guard.superAdmin(id)==supers).sorted().toList();List<String> labels=new ArrayList<>();
        for(long id:ids) {String telegram=guard.telegramProfile(id);String game="не привязан";try {var account=guard.account(id).get();if(account!=null) game=account.name();} catch(Exception ignored) {}labels.add(game+" · "+telegram+" · ID "+id+(guard.superAdmin(id)?" · 👑":""));}
        if(filter!=null && !filter.isBlank()) {String needle=root.searchKey(filter);List<Long> filteredIds=new ArrayList<>();List<String> filteredLabels=new ArrayList<>();for(int i=0;i<ids.size();i++) if(root.searchKey(labels.get(i)).contains(needle)) {filteredIds.add(ids.get(i));filteredLabels.add(labels.get(i));}ids=List.copyOf(filteredIds);labels=List.copyOf(filteredLabels);}
        page=Math.max(0,Math.min(page,Math.max(0,(ids.size()-1)/PAGE_SIZE)));List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*PAGE_SIZE;i<Math.min(ids.size(),page*PAGE_SIZE+PAGE_SIZE);i++) {
            long id=ids.get(i);buttons.add(b(user,labels.get(i),guard.superAdmin(id)?"confirm:removeSuper":"confirm:removeAdmin",Long.toString(id),0,i-page*PAGE_SIZE));
        }
        if(page>0) buttons.add(b(user,"◀ Назад",listOp,filter,page-1,PAGE_SIZE));if((page+1)*PAGE_SIZE<ids.size()) buttons.add(b(user,"Вперёд ▶",listOp,filter,page+1,PAGE_SIZE));
        buttons.add(b(user,"🔎 Поиск",supers?"searchSupers":"searchAdmins","",0,PAGE_SIZE+1));
        if(supers) {
            buttons.add(b(user,"👑 Добавить суперадминистратора","addSuper","",0,PAGE_SIZE+2));
            buttons.add(b(user,"❌ Удалить суперадминистратора","removeSuper","",0,PAGE_SIZE+3));
        } else {
            buttons.add(b(user,"➕ Добавить администратора","addAdmin","",0,PAGE_SIZE+2));
            buttons.add(b(user,"🧩 Возможности администратора","capabilities","",0,PAGE_SIZE+3));
        }
        buttons.add(b(user,"↩ Назад",supers?"rootHome":"superHome","",0,PAGE_SIZE+4));buttons.add(homeButton(PAGE_SIZE+5));
        return new TelegramCommands.View((supers?"👑 Суперадминистраторы":"👮 Администраторы бота")+" · страница "+(page+1)+" · всего "+ids.size()+(filter==null||filter.isBlank()?"":"\nПоиск: "+filter),List.copyOf(buttons));
    }

    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("SuperAdminMenu");
        scope.on("selfActions",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireSuper(user);var account=guard.account(user).get();view=account==null?new TelegramCommands.View("Сначала привяжите своего игрового персонажа в личном кабинете.",List.of(b(user,"↩ Назад","superHome","",0,0))):root.playerActions(user,account.uuid(),0);}
            return view;
        });
        scope.on("adminMenu",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=adminMenu(user);
            return view;
        });
        scope.on("superHome",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=superHome(user);
            return view;
        });
        scope.on("rootHome",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=rootHome(user);
            return view;
        });
        scope.on("rootList",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->rootList(user,a.page()));
        scope.on("rootDetails",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->rootDetails(user,Long.parseLong(a.value())));
        scope.on("rootRemoveConfirm",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->{
            guard.requirePrimaryRoot(user);
            long target=Long.parseLong(a.value());
            if(!guard.canRemoveRoot(user,target)) return rootList(user,0);
            return new TelegramCommands.View("Удалить Root Telegram ID "+target+"? Доступ к Root будет отозван.",List.of(
                    b(user,"✅ Удалить","rootRemove",a.value(),0,0),b(user,"↩️ Назад","rootDetails",a.value(),0,1)));
        });
        scope.on("rootRemove",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(user,a,data)->{
            guard.removeRoot(user,Long.parseLong(a.value())).get();
            var view=rootList(user,0);
            return new TelegramCommands.View("✅ Root "+a.value()+" удалён.\n\n"+view.text(),view.buttons());
        });
        scope.on("rootLogs",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->logs(user));
        scope.on("rootLogLines",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->logLines(user));
        scope.on("rootLogFormat",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->logFormat(user,a.value()));
        scope.on("rootLogSend",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(user,a,data)->{
            String[] parts=a.value().split("\\|",2);
            return sendLogs(user,parts[0].equals("full")?null:Integer.parseInt(parts[0]),parts.length==2 && parts[1].equals("md"));
        });
        scope.on("rootLogCustom",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->{
            guard.requireRoot(user);return prompt(user,"rootLogCustom","Введите число строк от 1 до "+RootLogDocument.MAX_LINES+".");
        });
        scope.on("rootAdd",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->{
            guard.requirePrimaryRoot(user);return prompt(user,"rootAdd","Введите положительный Telegram ID нового Root.");
        });
        scope.on("rootAddConfirm",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(user,a,data)->{
            guard.addRoot(user,Long.parseLong(a.value())).get();
            var view=rootList(user,0);
            return new TelegramCommands.View("✅ Root выдан Telegram ID "+a.value()+".\n\n"+view.text(),view.buttons());
        });
        scope.on("rootConsole",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->console(user));
        scope.on("rootConsoleInput",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->{
            guard.requireRoot(user);return prompt(user,"rootConsoleInput","Введите команду от консоли без /, например: list. Отмена: /cancel.");
        });
        scope.on("rootConsoleHistory",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(user,a,data)->consoleHistory(user));
        scope.on("rootConsoleRun",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(user,a,data)->{
            guard.requireRoot(user);
            long id;try {id=Long.parseLong(a.value());} catch(NumberFormatException invalid) {return consoleHistory(user);}
            String command=guard.rootCommand(user,id).get();
            return command==null?consoleResult(user,"Команда больше не найдена в истории."):runConsoleCommand(user,command);
        });
        scope.on("rootConsoleClear",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,true,(user,a,data)->{
            guard.requireRoot(user);guard.clearRootCommands(user).get();return consoleHistory(user);
        });
        scope.on("children",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=children(user,a.page(),a.value());
            return view;
        });
        scope.on("childSearch",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=prompt(user,"childSearch","Отправьте часть игрового ника ребёнка.");}
            return view;
        });
        scope.on("childAdd",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=prompt(user,"childAdd","Отправьте точный игровой ник или Telegram ID связанного аккаунта ребёнка.");}
            return view;
        });
        scope.on("childDelete",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=prompt(user,"childDelete","Отправьте игровой ник или Telegram ID ребёнка, которого нужно удалить.");}
            return view;
        });
        scope.on("childRemoveConfirm",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=new TelegramCommands.View("Удалить "+a.value()+" из списка детей и снять бонусы?",List.of(b(user,"Да, удалить","childRemove",a.value(),0,0),b(user,"↩ Назад","children","",0,1)));}
            return view;
        });
        scope.on("childRemove",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=childResult(user,moderation.changeChild(user,a.value(),false).get());}
            return view;
        });
        scope.on("childSizes",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=childSizes(user);
            return view;
        });
        scope.on("childSize",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {String result=moderation.childSize(user,Double.parseDouble(a.value())).get();var menu=childSizes(user);view=new TelegramCommands.View(result+"\n\n"+menu.text(),menu.buttons());}
            return view;
        });
        scope.on("admins",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=admins(user,a.page(),a.value());
            return view;
        });
        scope.on("supers",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=admins(user,a.page(),a.value(),true);
            return view;
        });
        scope.on("searchSupers",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=prompt(user,"superSearch","Введите имя или Telegram ID суперадминистратора.");}
            return view;
        });
        scope.on("searchAdmins",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireSuper(user);view=prompt(user,"adminSearch","Отправьте часть имени, @тега, Telegram ID или игрового ника администратора.");}
            return view;
        });
        scope.on("capabilities",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=capabilities(user);
            return view;
        });
        scope.on("toggleCapability",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireSuper(user);guard.capability(user,a.value(),!guard.capability(a.value())).get();view=capabilities(user);}
            return view;
        });
        scope.on("addAdmin",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireSuper(user);view=prompt(user,"addAdmin","Отправьте Telegram ID нового администратора (положительное число).");}
            return view;
        });
        scope.on("addSuper",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=prompt(user,"addSuper","Отправьте Telegram ID нового суперадминистратора. Он получит права суперадминистратора без доступа к Root.");}
            return view;
        });
        scope.on("removeSuper",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=prompt(user,"removeSuper","Отправьте Telegram ID суперадминистратора, которого нужно удалить.");}
            return view;
        });
        scope.on("chat",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireCapability(user,"chat");view=prompt(user,"chat","Отправьте текст для игрового чата: одна строка, до 500 символов. Перед отправкой будет подтверждение.");}
            return view;
        });
        scope.on("rootSettings",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=root.settings(user,true);
            return view;
        });
        scope.on("rootTime",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,true, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.superDelay(user,Long.parseLong(a.value())).get();view=root.settings(user,true);}
            return view;
        });
        scope.on("rootCustom",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireSuper(user);view=prompt(user,"rootTime","Отправьте число секунд от 0 до 31536000 для ваших администраторских уведомлений.");}
            return view;
        });
        scope.on("reloadPlugin",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,true, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);if(moderation==null) throw new IllegalStateException("Управление сервером недоступно");moderation.reloadPlugin(user);view=new TelegramCommands.View("♻️ Перезагрузка VaultTracker запланирована. Настройки применятся через секунду.");}
            return view;
        });
    }

    TelegramCommands.View onInput(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().equals("rootLogCustom")) {
            guard.requireRoot(user);
            try {int lines=Integer.parseInt(text.trim());if(lines<1 || lines>RootLogDocument.MAX_LINES) throw new NumberFormatException();
                waiting.remove(user);return logFormat(user,Integer.toString(lines));}
            catch(NumberFormatException invalid) {return new TelegramCommands.View("Введите число строк от 1 до "+RootLogDocument.MAX_LINES+". Отмена: /cancel.");}
        }
        if(pending.kind().equals("rootAdd")) {
            guard.requirePrimaryRoot(user);
            try {long target=Long.parseLong(text.trim());if(target<=0 || guard.rootAdmin(target)) throw new NumberFormatException();
                waiting.remove(user);return new TelegramCommands.View("Выдать Root Telegram ID "+target+"?",List.of(
                    b(user,"✅ Подтвердить","rootAddConfirm",Long.toString(target),0,0),b(user,"↩️ Назад","rootList","",0,1)));}
            catch(NumberFormatException invalid) {return new TelegramCommands.View("Введите положительный Telegram ID, у которого ещё нет Root. Отмена: /cancel.");}
        }
        if(pending.kind().equals("rootConsoleInput")) {
            guard.requireRoot(user);waiting.remove(user);return runConsoleCommand(user,text);
        }
        if(Set.of("childAdd","childDelete","childSearch").contains(pending.kind())) {
            guard.requireRoot(user);String query=text.trim();if(query.isEmpty()||query.length()>64) return new TelegramCommands.View("Введите ник или Telegram ID. Отмена: /cancel.");
            if(pending.kind().equals("childSearch")) {waiting.remove(user);return children(user,0,query);}
            try {String result=moderation.changeChild(user,query,pending.kind().equals("childAdd")).get();waiting.remove(user);return childResult(user,result);}
            catch(java.util.concurrent.ExecutionException failure) {
                if(failure.getCause() instanceof IllegalArgumentException invalid) return new TelegramCommands.View(invalid.getMessage()+"\nПопробуйте ещё раз. Отмена: /cancel.",List.of(b(user,"↩ Дети","children","",0,0),homeButton(1)));
                throw failure;
            }
        }
        if(pending.kind().equals("superSearch")) {guard.requireRoot(user);waiting.remove(user);return admins(user,0,text.trim(),true);}
        if(pending.kind().equals("adminSearch")) {guard.requireSuper(user);String query=text.trim();if(query.isBlank()||query.length()>64) return new TelegramCommands.View("Введите часть имени длиной от 1 до 64 символов.");waiting.remove(user);return admins(user,0,query);}
        return null;
    }
    TelegramCommands.View superIdInput(long user,TelegramGuardMenu.Input pending,long value) throws Exception {
        if(pending.kind().equals("addSuper")) {
            guard.requireRoot(user);
            if(value==0) return new TelegramCommands.View("Telegram ID должен быть больше 0.");
            waiting.remove(user);
            return root.confirm(user,"addSuper",Long.toString(value));
        }
        guard.requireRoot(user);
        if(value==0 || !guard.superAdmin(value)) return new TelegramCommands.View("Суперадминистратор с таким Telegram ID не найден. Введите другой ID.");
        if(!guard.canRemoveSuper(user,value)) return new TelegramCommands.View("Основного суперадминистратора и собственную роль удалить нельзя. Введите другой ID.");
        waiting.remove(user);
        return root.confirm(user,"removeSuper",Long.toString(value));
    }
}
