package ru.vaulttracker;

import java.util.*;

/** Private cabinet. Permissions are checked on every read and confirmed action. */
final class TelegramGuardMenu {
    private static final int PAGE_SIZE=TelegramConfig.LIST_PAGE_SIZE;
    private record Action(long user,String op,String value,int page,long createdAt) {}
    private record Input(String kind,long expires) {}
    private final Map<String,Action> actions=new HashMap<>();
    private final Map<Long,Input> waiting=new HashMap<>();
    private final GuardService guard;
    private final TelegramCommands commands;
    private final TelegramModeration moderation;
    private final TelegramTagManager tags;
    private final TelegramApi api;
    TelegramGuardMenu(GuardService guard,TelegramCommands commands) {this(guard,commands,null,null);}
    TelegramGuardMenu(GuardService guard,TelegramCommands commands,TelegramModeration moderation) {this(guard,commands,moderation,null);}
    TelegramGuardMenu(GuardService guard,TelegramCommands commands,TelegramModeration moderation,TelegramTagManager tags) {this(guard,commands,moderation,tags,null);}
    TelegramGuardMenu(GuardService guard,TelegramCommands commands,TelegramModeration moderation,TelegramTagManager tags,TelegramApi api) {
        this.guard=guard;this.commands=commands;this.moderation=moderation;this.tags=tags;this.api=api;
    }

    TelegramCommands.Callback callback(long user,String data) throws Exception {
        cancelInput(user);
        if("vg:home".equals(data)) return answer(home(user));
        if("vg:menu".equals(data)) return answer(mainMenu(user));
        if("vg:resourceSearch".equals(data)) return answer(prompt(user,"resourceSearch","Введите название ресурса на русском или английском. Поиск только по вашим зарегистрированным сундукам."));
        Action a=actions.get(data);
        if(a==null || a.user()!=user) return new TelegramCommands.Callback(null,"Кнопка больше недоступна. Откройте /menu.",true);
        if(Set.of("own","admin","link","time","tagApply","tagReset","rootTime").contains(a.op()) || a.op().startsWith("do:")) actions.remove(data);
        TelegramCommands.View view;
        switch(a.op()) {
            case "link" -> view=new TelegramCommands.View(guard.generate(user).get(),List.of(new TelegramCommands.Button("Обновить кабинет","vg:home")));
            case "resources" -> {var account=guard.account(user).get();view=account==null ? home(user) : resourceHome(user,account.uuid());}
            case "resourceResults" -> view=resourceResults(user,a.value(),a.page());
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
            case "customNumber" -> {guard.requireCapability(user,a.value().split("\\|",2)[0]);view=prompt(user,"number:"+a.value(),"Отправьте своё значение от 0.1 до 20. Например: 1.25. 1 — стандартное значение.");}
            case "teleportCoords" -> {guard.requireCapability(user,"teleportCoords");view=prompt(user,"coords:"+a.value(),"Отправьте x y z, например: 223 200 1004. Используется текущий мир перемещаемого игрока.");}
            case "teleportPlayers" -> {String[] parts=a.value().split("\\|",2);view=teleportPlayers(user,parts[0],a.page(),parts.length>1?parts[1]:"");}
            case "teleportSearch" -> {guard.requireCapability(user,"teleport");view=prompt(user,"teleportSearch:"+a.value(),"Отправьте часть ника игрока назначения.");}
            case "eventsHome" -> view=eventsHome(user);
            case "ownHistory" -> view=history(user,a.page(),true);
            case "friends" -> view=friends(user,a.page());
            case "friendAdd" -> {if(guard.account(user).get()==null) throw new SecurityException("Сначала привяжите персонажа");view=prompt(user,"friendAdd","Введите точный игровой ник друга.");}
            case "friendRemoveConfirm" -> {var friend=guard.friends(user).get().stream().filter(f->f.uuid().toString().equals(a.value())).findFirst().orElse(null);
                view=friend==null?friends(user,a.page()):new TelegramCommands.View("Удалить "+friend.name()+" из друзей? После этого его действия снова смогут вызывать уведомления.",List.of(b(user,"Да, удалить","friendRemove",a.value(),a.page(),0),b(user,"↩ Назад","friends","",a.page(),1),homeButton(2)));}
            case "friendRemove" -> {guard.removeFriend(user,UUID.fromString(a.value())).get();view=friends(user,a.page());}
            case "event" -> view=detail(user,Long.parseLong(a.value()),a.page(),false);
            case "ownEvent" -> view=detail(user,Long.parseLong(a.value()),a.page(),true);
            case "superHome" -> view=superHome(user);
            case "donations" -> view=donations(user);
            case "donationPay" -> view=donationPay(user);
            case "donationCode" -> {requireDonations(user);view=prompt(user,"donationCode","Введите одноразовый код из чата вашего оплаченного заказа FunPay. Не пересылайте код другим людям.");}
            case "donationPremium" -> view=premiumMenu(user);
            case "premiumBuy" -> {requireDonations(user);var account=guard.account(user).get();view=donationResult(user,moderation.donationChange(user,account.uuid(),account.name(),requestId(data),"buy",Integer.parseInt(a.value()),0),false);}
            case "donationAdmin" -> view=donationAdmin(user);
            case "donationVisibility" -> {guard.donationsVisible(user,Boolean.parseBoolean(a.value())).get();view=donationAdmin(user);}
            case "donationPremiumAdd" -> {guard.requireSuper(user);view=new TelegramCommands.View("Выберите срок премиума",List.of(
                    b(user,"На 30 дней","donationPlayers","grant30|online|",0,0),b(user,"На 60 дней","donationPlayers","grant60|online|",0,1),
                    b(user,"Навсегда","donationPlayers","grantPermanent|online|",0,2),b(user,"Свой срок","donationPlayers","grantCustom|online|",0,3),
                    b(user,"↩ Назад","donationAdmin","",0,4)));}
            case "donationPremiumRemove" -> view=donationPlayers(user,"remove|online|",0);
            case "donationPlayers" -> view=donationPlayers(user,a.value(),a.page());
            case "donationPlayerSearch" -> {guard.requireSuper(user);view=prompt(user,"donationSearch:"+a.value(),"Введите часть игрового ника.");}
            case "donationTarget" -> {guard.requireSuper(user);String[] parts=a.value().split("\\|",2);UUID target=UUID.fromString(parts[1]);
                if(parts[0].equals("add")||parts[0].equals("subtract")) view=prompt(user,"donationAmount:"+a.value()+"|"+requestId(data),"Введите сумму "+(parts[0].equals("add")?"начисления":"списания")+". Например: 100 или 100.50.");
                else if(parts[0].equals("grantCustom")) view=prompt(user,"donationPremiumDays:"+target,"Введите собственный срок премиума в днях — целое число от 1 до 36500.");
                else {var person=moderation.info(user,target).get();view=new TelegramCommands.View(premiumConfirmation(parts[0],person.name()),List.of(b(user,"Подтвердить","donationApply",a.value(),0,0),b(user,"↩ Назад","donationAdmin","",0,1)));}}
            case "donationApply" -> {guard.requireSuper(user);String[] parts=a.value().split("\\|",2);UUID target=UUID.fromString(parts[1]);var person=moderation.info(user,target).get();int days=premiumDays(parts[0]);view=donationResult(user,moderation.donationChange(user,target,person.name(),requestId(data),parts[0].equals("remove")?"remove":"grant",days,0),true);}
            case "donationJournal" -> view=donationJournal(user,a.page());
            case "children" -> view=children(user,a.page(),a.value());
            case "childSearch" -> {guard.requireSuper(user);view=prompt(user,"childSearch","Отправьте часть игрового ника ребёнка.");}
            case "childAdd" -> {guard.requireSuper(user);view=prompt(user,"childAdd","Отправьте точный игровой ник или Telegram ID связанного аккаунта ребёнка.");}
            case "childDelete" -> {guard.requireSuper(user);view=prompt(user,"childDelete","Отправьте игровой ник или Telegram ID ребёнка, которого нужно удалить.");}
            case "childRemoveConfirm" -> {guard.requireSuper(user);view=new TelegramCommands.View("Удалить "+a.value()+" из списка детей и снять бонусы?",List.of(b(user,"Да, удалить","childRemove",a.value(),0,0),b(user,"↩ Назад","children","",0,1)));}
            case "childRemove" -> {guard.requireSuper(user);view=childResult(user,moderation.changeChild(user,a.value(),false).get());}
            case "childSizes" -> view=childSizes(user);
            case "childSize" -> {String result=moderation.childSize(user,Double.parseDouble(a.value())).get();var menu=childSizes(user);view=new TelegramCommands.View(result+"\n\n"+menu.text(),menu.buttons());}
            case "admins" -> view=admins(user,a.page(),a.value());
            case "searchAdmins" -> {guard.requireSuper(user);view=prompt(user,"adminSearch","Отправьте часть имени, @тега, Telegram ID или игрового ника администратора.");}
            case "capabilities" -> view=capabilities(user);
            case "toggleCapability" -> {guard.requireSuper(user);guard.capability(user,a.value(),!guard.capability(a.value())).get();view=capabilities(user);}
            case "addAdmin" -> {guard.requireSuper(user);view=prompt(user,"addAdmin","Отправьте Telegram ID нового администратора (положительное число).");}
            case "addSuper" -> {guard.requireSuper(user);view=prompt(user,"addSuper","Отправьте Telegram ID нового супер администратора. Он получит все права управления сервером.");}
            case "removeSuper" -> {guard.requireSuper(user);view=prompt(user,"removeSuper","Отправьте Telegram ID супер администратора, которого нужно удалить.");}
            case "chat" -> {guard.requireCapability(user,"chat");view=prompt(user,"chat","Отправьте текст для игрового чата: одна строка, до 500 символов. Перед отправкой будет подтверждение.");}
            case "rootSettings" -> view=settings(user,true);
            case "rootTime" -> {guard.superDelay(user,Long.parseLong(a.value())).get();view=settings(user,true);}
            case "rootCustom" -> {guard.requireSuper(user);view=prompt(user,"rootTime","Отправьте число секунд от 0 до 31536000 для ваших администраторских уведомлений.");}
            case "players" -> view=players(user,"online",0);
            case "searchPlayers" -> {guard.requireAdmin(user);view=prompt(user,"playerSearch:"+a.value(),"Отправьте часть ника игрока для поиска.");}
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
    private TelegramCommands.View resourceHome(long user,UUID owner) {
        return withHome(commands.ownResources(user,owner));
    }
    private TelegramCommands.View resourceResults(long user,String query,int requested) throws Exception {
        var account=guard.account(user).get();if(account==null) return home(user);
        BlockKey origin=moderation==null?null:moderation.ownPosition(user,account.uuid()).get();
        Map<UUID,String> worlds=moderation==null?Map.of():moderation.worldLabels().get();
        var rows=commands.ownSearch(account.uuid(),query,origin);int pages=Math.max(1,(rows.size()+19)/20),page=Math.max(0,Math.min(requested,pages-1));
        if(moderation!=null&&origin!=null) moderation.pointOwnCompass(user,account.uuid(),rows);
        StringBuilder text=new StringBuilder("🔎 "+query+" · "+(page+1)+"/"+pages+"\n"+(origin==null?"Игрок офлайн: сортировка по миру и координатам.":"Ближайшие сундуки в вашем мире сначала.")+"\n");
        if(rows.isEmpty()) text.append("Не найдено в ваших зарегистрированных хранилищах.");
        for(var row:rows.subList(page*20,Math.min(rows.size(),(page+1)*20))) text.append(OwnResourceSearch.line(row,worlds.getOrDefault(row.chest().world(),row.chest().world().toString()))).append('\n');
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(page>0) buttons.add(b(user,"⬅ Назад","resourceResults",query,page-1,0));
        if(page+1<pages) buttons.add(b(user,"Вперёд ➡","resourceResults",query,page+1,0));
        buttons.add(new TelegramCommands.Button("🔎 Другой поиск","vg:resourceSearch",1));
        buttons.add(b(user,"↩ Мои ресурсы","resources","",0,2));buttons.add(homeButton(3));return new TelegramCommands.View(text.toString(),List.copyOf(buttons));
    }
    private static TelegramCommands.Callback answer(TelegramCommands.View view) {return new TelegramCommands.Callback(navigation(view),"",false);}
    private static TelegramCommands.View navigation(TelegramCommands.View view) {
        if(view==null || view.buttons().stream().anyMatch(b->b.data().equals("vg:menu")) || view.text().equals("Главное меню")) return view;
        return withHome(view);
    }
    TelegramCommands.View mainMenu(long user) {
        cancelInput(user);
        actions.entrySet().removeIf(e->e.getValue().user()==user && e.getValue().op().startsWith("do:"));
        return new TelegramCommands.View("Главное меню",List.of(new TelegramCommands.Button("🔎 Поиск","vg:search",0),new TelegramCommands.Button("👤 Личный кабинет","vg:home",1)));
    }
    TelegramCommands.View home(long user) throws Exception {
        cancelInput(user);
        actions.entrySet().removeIf(e->e.getValue().user()==user && e.getValue().op().startsWith("do:"));
        var account=guard.account(user).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text="👤 Личный кабинет"+(account==null ? "\nПривяжите персонажа, чтобы открыть личные ресурсы и события." : "\nПерсонаж: "+account.name());
        if(account==null) buttons.add(b(user,"🔗 Привязать персонажа","link","",0,0));
        else {
            buttons.add(b(user,"📦 Мои ресурсы","resources","",0,0));
            buttons.add(b(user,"📜 События","eventsHome","",0,1));
            buttons.add(b(user,"⚙️ Настройки игрока","settings","",0,2));
            if(guard.donationsVisible().get()) buttons.add(b(user,"💝 Пожертвования","donations","",0,7));
            if(guard.child(account.uuid())!=null) buttons.add(b(user,"📏 Размер персонажа","childSizes","",0,4));
        }
        if(guard.admin(user)) {
            buttons.add(b(user,"🛠 Функции администратора","adminHome","",0,3));
        }
        if(guard.superAdmin(user)) buttons.add(b(user,"👑 Супер администратор","superHome","",0,5));
        buttons.add(homeButton(6));
        if(!guard.enabled()) text+="\nОхрана отключена в guard.yml.";
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    private TelegramCommands.View eventsHome(long user) throws Exception {
        if(guard.account(user).get()==null) return home(user);
        return new TelegramCommands.View("📜 События\nИстория изменений ваших хранилищ и список друзей, действия которых не вызывают уведомления.",List.of(
                b(user,"📋 Список событий за 2 дня","ownHistory","",0,0),
                b(user,"👥 Друзья","friends","",0,1),
                new TelegramCommands.Button("↩ Назад","vg:home",2),homeButton(3)));
    }
    private TelegramCommands.View friends(long user,int requestedPage) throws Exception {
        var entries=guard.friends(user).get();int pages=Math.max(1,(entries.size()+PAGE_SIZE-1)/PAGE_SIZE),page=Math.max(0,Math.min(requestedPage,pages-1));
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        for(var friend:entries.subList(page*PAGE_SIZE,Math.min(entries.size(),(page+1)*PAGE_SIZE)))
            buttons.add(b(user,"❌ "+friend.name(),"friendRemoveConfirm",friend.uuid().toString(),page,row++));
        if(page>0) buttons.add(b(user,"◀ Назад","friends","",page-1,row));
        if(page+1<pages) buttons.add(b(user,"Вперёд ▶","friends","",page+1,row));
        buttons.add(b(user,"➕ Добавить друга","friendAdd","",0,++row));
        buttons.add(b(user,"↩ События","eventsHome","",0,++row));buttons.add(homeButton(++row));
        String text="👥 Друзья · "+(page+1)+"/"+pages+"\nДействия этих игроков с вашими хранилищами не вызывают уведомления вам и администраторам.";
        if(entries.isEmpty()) text+="\nСписок пуст.";
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    private TelegramCommands.View adminHome(long user) throws Exception {
        guard.requireAdmin(user);boolean on=guard.adminAlerts(user).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        buttons.add(b(user,(on ? "🔔 Отключить" : "🔕 Включить")+" уведомления администратора","admin","",0,0));
        buttons.add(b(user,"👥 Список игроков","browse:online","",0,1));
        buttons.add(b(user,"🗂 События администраторов","history","",0,2));
        buttons.add(new TelegramCommands.Button("↩ Назад","vg:home",3));buttons.add(homeButton(4));return new TelegramCommands.View("🛠 Функции администратора\nУведомления администратора: "+(on ? "включены" : "выключены"),List.copyOf(buttons));
    }
    private TelegramCommands.View banMenu(long user) {
        guard.requireCapability(user,"ban");
        return new TelegramCommands.View("⏳ Временный бан · 5 минут\nКаких игроков показать?",List.of(
                b(user,"🟢 Игроки онлайн","browse:banOnline","",0,0),
                b(user,"👥 Все игроки","browse:banAll","",0,1),
                b(user,"🛠 Назад в настройки администратора","adminHome","",0,2),homeButton(3)));
    }
    private TelegramCommands.View superHome(long user) {
        guard.requireSuper(user);
        return new TelegramCommands.View("👑 Супер администратор",List.of(
                b(user,"👮 Администраторы бота","admins","",0,0),
                b(user,"👥 Список игроков","browse:online","",0,1),
                b(user,"⏱ Личный срок админских супер уведомлений","rootSettings","",0,2),b(user,"🧒 Дети","children","",0,3),
                b(user,"💝 Пожертвования","donationAdmin","",0,4),new TelegramCommands.Button("↩ Назад","vg:home",5),homeButton(6)));
    }
    private void requireDonations(long user) throws Exception {
        if(guard.account(user).get()==null || !guard.donationsVisible().get()) throw new IllegalArgumentException("Раздел пожертвований недоступен.");
    }
    private TelegramCommands.View donations(long user) throws Exception {
        requireDonations(user);String balance="временно недоступен",premium="временно недоступен",extra="";
        try {var wallet=guard.donations.wallet(guard.account(user).get().uuid());balance=DonationClient.money(wallet.get("balanceMinor").getAsLong());premium=wallet.get("premium").getAsBoolean()?"активен":"не активен";
            if(wallet.get("blocked").getAsBoolean()) extra="\nПокупки приостановлены после возврата. Обратитесь к администратору.";
        } catch(Exception unavailable) {extra="\nСервис пополнений недоступен или ещё настраивается. Попробуйте позже.";}
        return new TelegramCommands.View("💝 Пожертвования\nБаланс: "+balance+"\nВсе пожертвования добровольны.\nПремиум: "+premium+extra,List.of(
                b(user,"Пожертвовать","donationPay","",0,0),b(user,"Премиум","donationPremium","",0,1),new TelegramCommands.Button("↩ Назад","vg:home",2),homeButton(3)));
    }
    private TelegramCommands.View donationPay(long user) throws Exception {
        requireDonations(user);List<TelegramCommands.Button> buttons=new ArrayList<>();String text;
        try {
            if(!guard.donations.enabled()) throw new java.io.IOException();
            buttons.add(TelegramCommands.Button.link("Открыть FunPay",guard.donations.offerUrl(),0));
            text="Пожертвование добровольное.\n1 единица лота = 1 монета; комиссии FunPay могут увеличить сумму оплаты.\nПосле оплаты в чате заказа придёт код. Введите его здесь. После зачисления подтвердите получение на FunPay.\nЗа любой честный отзыв на подтверждённый заказ — бонус 10%, один раз. При возврате пополнение и бонус отменяются.";
            buttons.add(b(user,"Ввести код","donationCode","",0,1));
        } catch(Exception unavailable) {text="Приём пожертвований ещё настраивается. Оплата пока недоступна.";}
        buttons.add(b(user,"↩ Назад","donations","",0,2));return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    private TelegramCommands.View donationAdmin(long user) throws Exception {
        guard.requireSuper(user);boolean visible=guard.donationsVisible().get();
        return new TelegramCommands.View("💝 Пожертвования\nКнопка у связанных игроков: "+(visible?"включена":"выключена"),List.of(
                b(user,visible?"Выключить кнопку у игроков":"Включить кнопку у игроков","donationVisibility",Boolean.toString(!visible),0,0),
                b(user,"Добавить премиум","donationPremiumAdd","",0,1),b(user,"Убрать премиум","donationPremiumRemove","",0,2),
                b(user,"Добавить пожертвования","donationPlayers","add|online|",0,3),b(user,"Отнять пожертвования","donationPlayers","subtract|online|",0,4),
                b(user,"Журнал","donationJournal","",0,5),b(user,"↩ Назад","superHome","",0,6),homeButton(7)));
    }
    private static String requestId(String value) {return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
    private static int premiumDays(String operation) {
        if(operation.equals("remove")) return 0;if(operation.equals("grantPermanent")) return -1;
        if(operation.startsWith("grant")) return Integer.parseInt(operation.substring(5));throw new IllegalArgumentException("Неизвестный срок премиума");
    }
    private static String premiumConfirmation(String operation,String name) {
        if(operation.equals("remove")) return "Убрать премиум у "+name+"?";
        int days=premiumDays(operation);return days<0?"Выдать бессрочный премиум: "+name+"?":"Выдать премиум на "+days+" дней: "+name+"?";
    }
    private TelegramCommands.View premiumMenu(long user) throws Exception {
        requireDonations(user);var account=guard.account(user).get();var wallet=guard.donations.wallet(account.uuid());
        long until=moderation.premiumExpiry(account.uuid()),remaining=Math.max(0,until-java.time.Instant.now().getEpochSecond());
        String status=until<0?"бессрочный":remaining>0?"активен · осталось "+(remaining/86400)+" дн. "+(remaining%86400/3600)+" ч. "+(remaining%3600/60)+" мин.":"не активен";
        return new TelegramCommands.View("⭐ Премиум\nБаланс: "+DonationClient.money(wallet.get("balanceMinor").getAsLong())+"\nСтатус: "+status+
            (wallet.has("premiumPending")&&wallet.get("premiumPending").getAsBoolean()?"\nИзменение премиума ожидает применения.":"")+"\nПокупка продлевает оставшийся срок.",List.of(
            b(user,"Купить · 600 монет · 30 дней","premiumBuy","30",0,0),b(user,"Купить · 1000 монет · 60 дней","premiumBuy","60",0,1),b(user,"↩ Назад","donations","",0,2)));
    }
    private TelegramCommands.View donationResult(long user,com.google.gson.JsonObject result,boolean admin) {
        return new TelegramCommands.View("Операция сохранена. Баланс: "+DonationClient.money(result.get("balanceMinor").getAsLong())+
            (result.has("pending")?"\nВыдача группы ожидает повторной синхронизации; повторно платить не нужно.":""),List.of(b(user,"↩ Назад",admin?"donationAdmin":"donationPremium","",0,0)));
    }
    private TelegramCommands.View donationPlayers(long user,String state,int requested) throws Exception {
        guard.requireSuper(user);String[] parts=state.split("\\|",3);String operation=parts[0],mode=parts[1],filter=parts.length>2?parts[2]:"";
        var rows=moderation.players(user,mode.equals("online")?"online":"all").get().stream().filter(p->p.online()==mode.equals("online")&&searchKey(p.name()).contains(searchKey(filter))).toList();
        int pages=Math.max(1,(rows.size()+19)/20),page=Math.max(0,Math.min(requested,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*20;i<Math.min(rows.size(),page*20+20);i++){var person=rows.get(i);buttons.add(b(user,person.name(),"donationTarget",operation+"|"+person.uuid(),0,i-page*20));}
        if(page>0) buttons.add(b(user,"◀ Назад","donationPlayers",state,page-1,20));if(page+1<pages) buttons.add(b(user,"Вперёд ▶","donationPlayers",state,page+1,20));
        buttons.add(b(user,mode.equals("online")?"Игроки офлайн":"Игроки онлайн","donationPlayers",operation+"|"+(mode.equals("online")?"offline":"online")+"|"+filter,0,21));
        buttons.add(b(user,"🔎 Поиск","donationPlayerSearch",operation+"|"+mode,0,22));buttons.add(b(user,"↩ Назад","donationAdmin","",0,23));
        return new TelegramCommands.View("Выберите игрока · "+(mode.equals("online")?"онлайн":"офлайн")+" · "+(page+1)+"/"+pages+(rows.isEmpty()?"\nИгроков не найдено.":""),List.copyOf(buttons));
    }
    private TelegramCommands.View donationJournal(long user,int page) throws Exception {
        guard.requireSuper(user);var body=new com.google.gson.JsonObject();body.addProperty("page",page);var result=guard.donations.call("donation-audit",body);
        page=result.get("page").getAsInt();int pages=result.get("pages").getAsInt();StringBuilder text=new StringBuilder("Журнал пожертвований · "+(page+1)+"/"+pages);
        Map<String,String> labels=Map.of("buy","покупка премиума","grant","выдача премиума","remove","снятие премиума","add","начисление","subtract","списание");
        for(var element:result.getAsJsonArray("rows")){var row=element.getAsJsonObject();String action=row.get("action").getAsString();long value=row.get("amount").getAsLong();text.append("\n\n").append(java.time.Instant.ofEpochSecond(row.get("created").getAsLong()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))).append(" · ").append(row.get("actor").getAsString()).append(" → ").append(row.get("name").getAsString()).append("\n").append(labels.getOrDefault(action,action));if(!action.equals("remove"))text.append(": ").append(Set.of("buy","grant").contains(action)?(value<0?"навсегда":value+" дней"):DonationClient.money(value));}
        List<TelegramCommands.Button> buttons=new ArrayList<>();if(page>0)buttons.add(b(user,"◀ Назад","donationJournal","",page-1,0));if(page+1<pages)buttons.add(b(user,"Вперёд ▶","donationJournal","",page+1,0));buttons.add(b(user,"↩ Назад","donationAdmin","",0,1));return new TelegramCommands.View(text.toString(),List.copyOf(buttons));
    }
    private TelegramCommands.View children(long user,int requested,String filter) {
        var rows=guard.children(user).stream().filter(c->c.name().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))).toList();
        int pages=Math.max(1,(rows.size()+19)/20),page=Math.max(0,Math.min(requested,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();
        buttons.add(b(user,"➕ Добавить ребёнка","childAdd","",0,0));buttons.add(b(user,"➖ Удалить ребёнка","childDelete","",0,1));
        for(int i=page*20;i<Math.min(rows.size(),(page+1)*20);i++) buttons.add(b(user,rows.get(i).name(),"childRemoveConfirm",rows.get(i).name(),0,i-page*20+2));
        if(page>0) buttons.add(b(user,"⬅ Назад","children",filter,page-1,22));if(page+1<pages) buttons.add(b(user,"Вперёд ➡","children",filter,page+1,22));
        buttons.add(b(user,"🔎 Поиск","childSearch","",0,23));buttons.add(b(user,"↩ Назад","superHome","",0,24));buttons.add(homeButton(25));
        return new TelegramCommands.View("🧒 Дети · "+(page+1)+"/"+pages+"\n+2 сердца и защита 20%. Размер доступен в связанном личном кабинете."+(rows.isEmpty()?"\nСписок пуст.":""),List.copyOf(buttons));
    }
    private TelegramCommands.View childResult(long user,String result) {var menu=children(user,0,"");return new TelegramCommands.View(result+"\n\n"+menu.text(),menu.buttons());}
    private TelegramCommands.View childSizes(long user) throws Exception {
        var account=guard.account(user).get();if(account==null||guard.child(account.uuid())==null) throw new SecurityException("Функция доступна только детям с привязанным аккаунтом");
        return new TelegramCommands.View("📏 Размер персонажа",List.of(b(user,"Гном · 0.6","childSize","0.6",0,0),b(user,"Карлик · 0.75","childSize","0.75",0,1),b(user,"Вернуть нормальный размер","childSize","1",0,2),new TelegramCommands.Button("↩ Назад","vg:home",3),homeButton(4)));
    }
    private TelegramCommands.View capabilities(long user) {
        guard.requireSuper(user);List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        Map<String,String> labels=Map.ofEntries(Map.entry("ban","Бан на 5 минут"),Map.entry("kick","Кик"),Map.entry("players","Список игроков"),Map.entry("inventory","Инвентарь"),Map.entry("ender","Эндер-сундук"),Map.entry("unban","Разбан"),Map.entry("heal","Лечение"),Map.entry("kill","Убийство"),Map.entry("repair","Ремонт предметов"),Map.entry("scale","Размер игрока"),Map.entry("flySpeed","Скорость полёта"),Map.entry("walkSpeed","Скорость движения"),Map.entry("teleport","Телепорт к игроку"),Map.entry("teleportCoords","Телепорт на координаты"),Map.entry("luckPerms","LuckPerms admin"),Map.entry("op","Выдать OP"),Map.entry("deop","Забрать OP"),Map.entry("chat","Игровой чат"),Map.entry("manageAdmins","Управление администраторами"));
        for(String capability:GuardService.ADMIN_CAPABILITIES) {boolean enabled=guard.capability(capability);buttons.add(b(user,(enabled?"✅ ":"❌ ")+labels.get(capability),"toggleCapability",capability,0,row++));}
        buttons.add(b(user,"↩ Администраторы бота","admins","",0,row++));buttons.add(homeButton(row));
        return new TelegramCommands.View("🧩 Возможности обычных администраторов\nУведомления доступны всегда и здесь не отключаются.",List.copyOf(buttons));
    }
    private TelegramCommands.View settings(long user,boolean root) throws Exception {
        if(root) guard.requireSuper(user);else if(guard.account(user).get()==null) return home(user);
        Long personal=root ? Long.valueOf(guard.superDelay(user)) : guard.offlineSeconds(user).get();
        String text=(root ? "👑 Ваши администраторские уведомления" : "⚙️ Настройки игрока")+"\nВремя отсутствия: "+duration(personal==null ? guard.defaultOfflineSeconds() : personal)+(personal==null ? " (по настройке сервера)" : " (личное)")
                +"\n«Всегда» — в том числе во время игры. «После выхода» — сразу после выхода. «2 дня» — после двух дней отсутствия.";
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        if(!root) {
            boolean on=guard.account(user).get().notifications(),tag=guard.tag(user,false).get();
            text+="\nВаши уведомления: "+(on ? "включены" : "выключены")+".";
            buttons.add(b(user,(on ? "🔔 Отключить" : "🔕 Включить")+" мои уведомления","own","",0,row++));
        }
        long[] values=root ? new long[]{-1,0,120,300,900,3600,21600,86400,172800,604800} : new long[]{-1,0,172800};
        for(long value:values) buttons.add(b(user,!root?(value<0?"Всегда":value==0?"После выхода":"2 дня"):duration(value),root ? "rootTime" : "time",Long.toString(value),0,row++));
        if(root) {buttons.add(b(user,"✏️ Своё время","rootCustom","",0,row++));buttons.add(b(user,"Как на сервере","rootTime","-2",0,row++));}
        if(!root) {
            String nickname=guard.account(user).get().name();
            buttons.add(b(user,"🏷 Применить тег «"+nickname+"»","tagApply","",0,row++));
            buttons.add(b(user,"♻️ Сбросить тег","tagReset","",0,row++));
        }
        buttons.add(root?b(user,"↩ Назад","superHome","",0,row++):new TelegramCommands.Button("↩ Назад","vg:home",row++));buttons.add(homeButton(row));return new TelegramCommands.View(text,List.copyOf(buttons));
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
        if(seconds<0) return "Всегда";if(seconds==0) return "Сразу после выхода";
        if(seconds%86400==0) return seconds/86400+" дн.";if(seconds%3600==0) return seconds/3600+" ч.";
        if(seconds%60==0) return seconds/60+" мин.";return seconds+" сек.";
    }
    void cancelInput(long user) {waiting.remove(user);}
    private TelegramCommands.View prompt(long user,String kind,String text) {
        if(kind.equals("donationCode")) {waiting.put(user,new Input(kind,Long.MAX_VALUE));return withHome(new TelegramCommands.View(text+"\nКод можно отправить в этот чат в любое время. Отмена: /cancel."));}
        waiting.entrySet().removeIf(e->e.getValue().expires()<System.currentTimeMillis());waiting.put(user,new Input(kind,System.currentTimeMillis()+600000));
        if(kind.startsWith("child")) return new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel.",List.of(b(user,"↩ Дети","children","",0,0),homeButton(1)));
        if(kind.equals("friendAdd")) return new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel.",List.of(b(user,"↩ Друзья","friends","",0,0),homeButton(1)));
        if(kind.startsWith("donationPremiumDays:")) return new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel.",List.of(b(user,"↩ Пожертвования","donationAdmin","",0,0),homeButton(1)));
        return withHome(new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel."));
    }
    TelegramCommands.View input(long user,String text) throws Exception {
        try {return navigation(inputValue(user,text));}
        catch(DonationClient.Rejected rejected) {return navigation(new TelegramCommands.View(rejected.getMessage()+"\nВведите другую сумму или /cancel."));}
    }
    private TelegramCommands.View inputValue(long user,String text) throws Exception {
        Input pending=waiting.get(user);
        if(text!=null&&text.trim().matches("(?i)[A-F0-9]{32}")) pending=new Input("donationCode",Long.MAX_VALUE);
        if(pending==null) return null;
        if(pending.kind().startsWith("donationSearch:")) {guard.requireSuper(user);String query=text.trim();if(query.isEmpty()||query.length()>64)return new TelegramCommands.View("Введите от 1 до 64 символов.");waiting.remove(user);return donationPlayers(user,pending.kind().substring(15)+"|"+query,0);}
        if(pending.kind().startsWith("donationAmount:")) {
            guard.requireSuper(user);String[] parts=pending.kind().substring(15).split("\\|");long amount;
            try {amount=new java.math.BigDecimal(text.trim().replace(',','.')).movePointRight(2).longValueExact();if(amount<=0||amount>100_000_000)throw new IllegalArgumentException();}
            catch(IllegalArgumentException|ArithmeticException invalid){return new TelegramCommands.View("Введите сумму от 0.01 до 1000000, максимум два знака после точки.");}
            UUID owner=UUID.fromString(parts[1]);var person=moderation.info(user,owner).get();var result=moderation.donationChange(user,owner,person.name(),parts[2],parts[0],0,amount);waiting.remove(user);return donationResult(user,result,true);
        }
        if(pending.kind().equals("donationCode")&&pending.expires()>=System.currentTimeMillis()) {
            if(guard.account(user).get()==null) return new TelegramCommands.View("Сначала свяжите игровой аккаунт в личном кабинете, затем отправьте код снова.",List.of(new TelegramCommands.Button("Личный кабинет","vg:home")));
            String code=text.trim().toUpperCase(Locale.ROOT);
            if(!code.matches("[A-F0-9]{32}")) return new TelegramCommands.View("Проверьте код: нужны 32 символа из сообщения FunPay. Отмена: /cancel.",List.of(b(user,"↩ Назад","donations","",0,0)));
            try {
                var result=guard.donations.redeem(guard.account(user).get().uuid(),code);waiting.remove(user);
                String message=result.get("alreadyClaimed").getAsBoolean()?"Этот заказ уже зачислен ранее.":"Оплата проверена. Зачислено: "+DonationClient.money(result.get("amountMinor").getAsLong())+" монет.";
                return new TelegramCommands.View(message+"\nБаланс: "+DonationClient.money(result.get("balanceMinor").getAsLong())+"\nПроверьте баланс и подтвердите получение заказа на FunPay.",List.of(b(user,"↩ Пожертвования","donations","",0,0)));
            } catch(Exception unavailable) {return new TelegramCommands.View("Не удалось зачислить код: он недоступен, уже использован другим аккаунтом или проверка временно не работает. Повторите через минуту: повторного списания или зачисления не будет.",List.of(b(user,"↩ Назад","donations","",0,0)));}
        }
        if(pending.kind().equals("resourceSearch")&&pending.expires()>=System.currentTimeMillis()) {
            String query=text.strip();if(query.isEmpty()||query.length()>64) return new TelegramCommands.View("Введите от 1 до 64 символов.");
            waiting.remove(user);return resourceResults(user,query,0);
        }
        if(pending.expires()<System.currentTimeMillis()) {waiting.remove(user);return withHome(new TelegramCommands.View("Время ввода истекло. Откройте настройки снова."));}
        if(pending.kind().startsWith("donationPremiumDays:")) {
            guard.requireSuper(user);int days;
            try {days=Integer.parseInt(text.trim());if(days<1||days>36500) throw new NumberFormatException();}
            catch(NumberFormatException invalid) {return new TelegramCommands.View("Введите целое число дней от 1 до 36500. Отмена: /cancel.",List.of(b(user,"↩ Пожертвования","donationAdmin","",0,0),homeButton(1)));}
            UUID target=UUID.fromString(pending.kind().substring("donationPremiumDays:".length()));var person=moderation.info(user,target).get();waiting.remove(user);
            return new TelegramCommands.View(premiumConfirmation("grant"+days,person.name()),List.of(b(user,"Подтвердить","donationApply","grant"+days+"|"+target,0,0),b(user,"↩ Назад","donationAdmin","",0,1)));
        }
        if(pending.kind().equals("friendAdd")) {
            if(guard.account(user).get()==null) {waiting.remove(user);return home(user);}
            String nickname=text==null?"":text.strip();if(nickname.isEmpty()||nickname.length()>64) return new TelegramCommands.View("Введите игровой ник длиной от 1 до 64 символов. Отмена: /cancel.");
            if(moderation==null) return new TelegramCommands.View("Поиск игроков временно недоступен. Повторите позже.");
            try {var player=moderation.playerByName(nickname).get();guard.addFriend(user,player.uuid(),player.name()).get();waiting.remove(user);return friends(user,0);}
            catch(java.util.concurrent.ExecutionException failure) {
                if(failure.getCause() instanceof IllegalArgumentException invalid) return new TelegramCommands.View(invalid.getMessage()+"\nПопробуйте ещё раз. Отмена: /cancel.",List.of(b(user,"↩ Друзья","friends","",0,0),homeButton(1)));
                throw failure;
            }
        }
        if(Set.of("childAdd","childDelete","childSearch").contains(pending.kind())) {
            guard.requireSuper(user);String query=text.trim();if(query.isEmpty()||query.length()>64) return new TelegramCommands.View("Введите ник или Telegram ID. Отмена: /cancel.");
            if(pending.kind().equals("childSearch")) {waiting.remove(user);return children(user,0,query);}
            try {String result=moderation.changeChild(user,query,pending.kind().equals("childAdd")).get();waiting.remove(user);return childResult(user,result);}
            catch(java.util.concurrent.ExecutionException failure) {
                if(failure.getCause() instanceof IllegalArgumentException invalid) return new TelegramCommands.View(invalid.getMessage()+"\nПопробуйте ещё раз. Отмена: /cancel.",List.of(b(user,"↩ Дети","children","",0,0),homeButton(1)));
                throw failure;
            }
        }
        if(pending.kind().startsWith("teleportSearch:")) {guard.requireCapability(user,"teleport");String query=text.trim();if(query.isEmpty()||query.length()>64) return withHome(new TelegramCommands.View("Введите от 1 до 64 символов."));waiting.remove(user);return teleportPlayers(user,pending.kind().substring(15),0,query);}
        if(pending.kind().equals("historySearch")) {
            guard.requireAdmin(user);String query=text.trim();
            if(query.isEmpty() || query.length()>100) return new TelegramCommands.View("Введите запрос от 1 до 100 символов. Отмена: /cancel.");
            waiting.remove(user);return history(user,0,false,query);
        }
        if(pending.kind().equals("adminSearch")) {guard.requireSuper(user);String query=text.trim();if(query.isBlank()||query.length()>64) return new TelegramCommands.View("Введите часть имени длиной от 1 до 64 символов.");waiting.remove(user);return admins(user,0,query);}
        if(pending.kind().startsWith("number:")) {
            String capability=pending.kind().substring(7).split("\\|",2)[0];guard.requireCapability(user,capability);double value;
            try {value=Double.parseDouble(text.trim().replace(',','.'));if(!Double.isFinite(value) || value<0.1 || value>20) throw new NumberFormatException();}
            catch(NumberFormatException e) {return new TelegramCommands.View("Введите число от 0.1 до 20. Например: 1.25. Отмена: /cancel.");}
            String[] parts=pending.kind().substring(7).split("\\|",2);waiting.remove(user);return confirm(user,parts[0],parts[1]+"|"+value);
        }
        if(pending.kind().startsWith("coords:")) {
            guard.requireCapability(user,"teleportCoords");
            try {TelegramModeration.coordinates(text);} catch(IllegalArgumentException e) {return new TelegramCommands.View(e.getMessage()+" Отмена: /cancel.");}
            waiting.remove(user);return confirm(user,"teleportCoords",pending.kind().substring(7)+"|"+text.trim());
        }
        if(pending.kind().startsWith("playerSearch:")) {
            guard.requireAdmin(user);String query=text==null ? "" : text.trim();
            if(query.isBlank() || query.length()>64) return withHome(new TelegramCommands.View("Введите часть ника длиной от 1 до 64 символов."));
            String mode=pending.kind().substring("playerSearch:".length());
            if(!Set.of("online","all","offline","banned","kick","banOnline","banAll").contains(mode)) {waiting.remove(user);return home(user);}
            waiting.remove(user);return players(user,mode,0,query);
        }
        if(pending.kind().equals("chat")) {
            guard.requireCapability(user,"chat");if(text.isBlank() || text.length()>500 || text.contains("\n") || text.contains("\r")) return withHome(new TelegramCommands.View("Нужна одна строка до 500 символов."));
            waiting.remove(user);return confirm(user,"chat",text);
        }
        long value;
        boolean roleInput=Set.of("addAdmin","addSuper","removeSuper").contains(pending.kind());
        try {value=Long.parseLong(text.trim());if(value<0 || (!roleInput && value>GuardService.MAX_OFFLINE_SECONDS)) throw new NumberFormatException();}
        catch(NumberFormatException e) {return new TelegramCommands.View(roleInput?"Введите положительное целое число Telegram ID. Отмена: /cancel.":"Введите целое число секунд от 0 до 31536000. Отмена: /cancel.");}
        if(pending.kind().equals("addAdmin")) {guard.requireSuper(user);if(value==0) return new TelegramCommands.View("Telegram ID должен быть больше 0.");waiting.remove(user);return confirm(user,"addAdmin",Long.toString(value));}
        if(pending.kind().equals("addSuper")) {guard.requireSuper(user);if(value==0) return new TelegramCommands.View("Telegram ID должен быть больше 0.");waiting.remove(user);return confirm(user,"addSuper",Long.toString(value));}
        if(pending.kind().equals("removeSuper")) {
            guard.requireSuper(user);
            if(value==0 || !guard.superAdmin(value)) return new TelegramCommands.View("Супер администратор с таким Telegram ID не найден. Введите другой ID.");
            if(!guard.canRemoveSuper(user,value)) return new TelegramCommands.View("Основного супер администратора и собственную роль удалить нельзя. Введите другой ID.");
            waiting.remove(user);return confirm(user,"removeSuper",Long.toString(value));
        }
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
        buttons.add(own?new TelegramCommands.Button("↩ Назад","vg:home",9):b(user,"↩ Назад","adminHome","",0,9));buttons.add(homeButton(10));return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    private TelegramCommands.View detail(long user,long id,int page,boolean own) throws Exception {
        var event=own ? guard.ownEvent(user,id).get() : guard.event(user,id).get();
        if(event==null) return withHome(new TelegramCommands.View("Событие недоступно или срок хранения истёк."));
        int pages=Math.max(1,(event.changes().size()+PAGE_SIZE-1)/PAGE_SIZE);page=Math.max(0,Math.min(page,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(page>0) buttons.add(b(user,"◀ Назад",own ? "ownEvent" : "event",Long.toString(id),page-1,0));
        if(page+1<pages) buttons.add(b(user,"Вперёд ▶",own ? "ownEvent" : "event",Long.toString(id),page+1,0));
        buttons.add(b(user,"📜 История",own ? "ownHistory" : "history","",0,1));buttons.add(homeButton(2));
        return new TelegramCommands.View("Событие #"+id+" • "+(page+1)+"/"+pages+"\n"+GuardService.eventText(event,page*PAGE_SIZE,PAGE_SIZE,!own),List.copyOf(buttons));
    }
    private TelegramCommands.View admins(long user,int page,String filter) {
        guard.requireSuper(user);var ids=guard.adminIds().stream().sorted().toList();List<String> labels=new ArrayList<>();
        for(long id:ids) {String telegram=guard.telegramProfile(id);String game="не привязан";try {var account=guard.account(id).get();if(account!=null) game=account.name();} catch(Exception ignored) {}labels.add(game+" · "+telegram+" · ID "+id+(guard.superAdmin(id)?" · 👑":""));}
        if(filter!=null && !filter.isBlank()) {String needle=searchKey(filter);List<Long> filteredIds=new ArrayList<>();List<String> filteredLabels=new ArrayList<>();for(int i=0;i<ids.size();i++) if(searchKey(labels.get(i)).contains(needle)) {filteredIds.add(ids.get(i));filteredLabels.add(labels.get(i));}ids=List.copyOf(filteredIds);labels=List.copyOf(filteredLabels);}
        page=Math.max(0,Math.min(page,Math.max(0,(ids.size()-1)/PAGE_SIZE)));List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*PAGE_SIZE;i<Math.min(ids.size(),page*PAGE_SIZE+PAGE_SIZE);i++) {
            long id=ids.get(i);buttons.add(b(user,labels.get(i),guard.superAdmin(id)?"confirm:removeSuper":"confirm:removeAdmin",Long.toString(id),0,i-page*PAGE_SIZE));
        }
        if(page>0) buttons.add(b(user,"◀ Назад","admins",filter,page-1,PAGE_SIZE));if((page+1)*PAGE_SIZE<ids.size()) buttons.add(b(user,"Вперёд ▶","admins",filter,page+1,PAGE_SIZE));
        buttons.add(b(user,"🔎 Поиск","searchAdmins","",0,PAGE_SIZE+1));buttons.add(b(user,"➕ Добавить администратора","addAdmin","",0,PAGE_SIZE+2));buttons.add(b(user,"👑 Добавить супер администратора","addSuper","",0,PAGE_SIZE+3));buttons.add(b(user,"❌ Удалить супер администратора","removeSuper","",0,PAGE_SIZE+4));buttons.add(b(user,"🧩 Возможности администратора","capabilities","",0,PAGE_SIZE+5));buttons.add(b(user,"↩ Назад","superHome","",0,PAGE_SIZE+6));buttons.add(homeButton(PAGE_SIZE+7));return new TelegramCommands.View("👮 Администраторы бота · страница "+(page+1)+" · всего "+ids.size()+(filter==null||filter.isBlank()?"":"\nПоиск: "+filter),List.copyOf(buttons));
    }
    private TelegramCommands.View players(long user,String mode,int page) throws Exception { return players(user,mode,page,""); }
    private TelegramCommands.View players(long user,String mode,int page,String filter) throws Exception {
        if(mode.equals("kick")) guard.requireCapability(user,"kick");
        else if(mode.equals("banOnline") || mode.equals("banAll")) guard.requireCapability(user,"ban");
        else if(mode.equals("banned")) guard.requireCapability(user,"unban");
        else if(Set.of("online","all","offline").contains(mode)) requirePlayerAccess(user);
        else guard.requireAdmin(user);
        if(moderation==null) return withHome(new TelegramCommands.View("Управление сервером недоступно."));
        String source=switch(mode) {case "kick","banOnline" -> "online";case "banAll","offline" -> "all";default -> mode;};
        String operation=Set.of("banOnline","banAll").contains(mode) ? "ban" : mode;
        var people=moderation.players(user,source).get();if(mode.equals("offline")) people=people.stream().filter(p->!p.online()).toList();
        if(!filter.isBlank()) {String needle=searchKey(filter);people=people.stream().filter(p->searchKey(p.name()).contains(needle)).toList();}
        page=Math.max(0,Math.min(page,Math.max(0,(people.size()-1)/PAGE_SIZE)));
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*PAGE_SIZE;i<Math.min(people.size(),page*PAGE_SIZE+PAGE_SIZE);i++) {var p=people.get(i);buttons.add(b(user,(p.online() ? "🟢 " : "👤 ")+p.name(),Set.of("ban","kick").contains(operation) ? "confirm:"+operation : "person",p.uuid().toString(),0,i-page*PAGE_SIZE));}
        String pageOp=filter.isBlank() ? "browse:"+mode : "browseSearch:"+mode;
        if(page>0) buttons.add(b(user,"◀ Назад",pageOp,filter,page-1,PAGE_SIZE));if((page+1)*PAGE_SIZE<people.size()) buttons.add(b(user,"Вперёд ▶",pageOp,filter,page+1,PAGE_SIZE));
        if(Set.of("banOnline","banAll").contains(mode)) buttons.add(b(user,"↩ Выбор списка","banMenu","",0,PAGE_SIZE+1));
        if(mode.equals("online")) buttons.add(b(user,"👤 Игроки офлайн","browse:offline","",0,PAGE_SIZE+1));
        if(mode.equals("offline")) buttons.add(b(user,"🟢 Игроки онлайн","browse:online","",0,PAGE_SIZE+1));
        buttons.add(b(user,"🔎 Поиск","searchPlayers",mode,0,PAGE_SIZE+2));buttons.add(b(user,"↩ Назад",guard.superAdmin(user)?"superHome":"adminHome","",0,PAGE_SIZE+3));buttons.add(homeButton(PAGE_SIZE+4));
        String title=mode.equals("banOnline") ? "Игроки онлайн" : mode.equals("banAll") ? "Все игроки" : "Игроки";
        if(!filter.isBlank()) title+=" • поиск «"+filter.trim()+"»";
        return new TelegramCommands.View(title+" • страница "+(page+1)+" • всего "+people.size(),List.copyOf(buttons));
    }
    private static String searchKey(String value) {return value.toLowerCase(Locale.ROOT).replace('ё','е').replace('_',' ').replace('-',' ').trim().replaceAll("\\s+"," ");}
    private boolean allowed(long user,String capability) {return guard.superAdmin(user)||guard.capability(capability);}
    private boolean hasPlayerTools(long user) {return guard.superAdmin(user)||GuardService.ADMIN_CAPABILITIES.stream().filter(c->!Set.of("chat","manageAdmins").contains(c)).anyMatch(guard::capability);}
    private void requirePlayerAccess(long user) {
        guard.requireAdmin(user);
    }
    private boolean hasDirectPlayerActions(long user) {
        return guard.superAdmin(user)||List.of("ban","kick","unban","heal","kill","repair","scale","flySpeed","walkSpeed","teleport","op").stream().anyMatch(guard::capability);
    }
    private TelegramCommands.View person(long user,UUID uuid) throws Exception {
        return playerActions(user,uuid);
    }
    private TelegramCommands.View inventory(long user,UUID uuid,boolean ender,int page) throws Exception {
        guard.requireCapability(user,ender?"ender":"inventory");List<TelegramModeration.ItemView> items;
        try {items=moderation.inventory(user,uuid,ender).get();}
        catch(java.util.concurrent.ExecutionException e) {
            Throwable cause=e;while(cause.getCause()!=null) cause=cause.getCause();
            if(!(cause instanceof java.io.IOException)) throw e;
            return new TelegramCommands.View("Не удалось прочитать сохранённый инвентарь: файл playerdata отсутствует, повреждён или недоступен. Это не означает, что инвентарь пуст. Попробуйте после сохранения игрока.",List.of(b(user,"Повторить","inventory",uuid+"|"+(ender?"ender":"main"),page,0),b(user,"↩ Карточка игрока","person",uuid.toString(),0,1),homeButton(2)));
        }
        String title=ender ? "🧰 Эндер-сундук" : "🎒 Инвентарь";
        var info=moderation.info(user,uuid).get();if(info!=null && !info.online()) title+=" · офлайн, последнее сохранение";
        if(items.isEmpty()) return new TelegramCommands.View(title+"\nИнвентарь пуст.",List.of(b(user,"↩ Карточка игрока","person",uuid.toString(),0,0),homeButton(1)));
        int pages=Math.max(1,(items.size()+PAGE_SIZE-1)/PAGE_SIZE);page=Math.max(0,Math.min(page,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();StringBuilder text=new StringBuilder(title+" • страница "+(page+1)+"/"+pages+"\n");
        for(int i=page*PAGE_SIZE;i<Math.min(items.size(),page*PAGE_SIZE+PAGE_SIZE);i++) {var item=items.get(i);text.append(item.label()).append(": ").append(item.amount()).append(" шт.\n");if(item.shulker()) buttons.add(b(user,item.label()+" · открыть","shulker",uuid+"|"+(ender?"ender":"main")+"|"+i,0,buttons.size()));}
        if(page>0) buttons.add(b(user,"◀ Назад","inventory",uuid+"|"+(ender?"ender":"main"),page-1,PAGE_SIZE));if(page+1<pages) buttons.add(b(user,"Вперёд ▶","inventory",uuid+"|"+(ender?"ender":"main"),page+1,PAGE_SIZE));
        buttons.add(b(user,"↩ Карточка игрока","person",uuid.toString(),0,PAGE_SIZE+1));buttons.add(homeButton(PAGE_SIZE+2));return new TelegramCommands.View(text.toString().stripTrailing(),List.copyOf(buttons));
    }
    private TelegramCommands.View shulker(long user,UUID uuid,boolean ender,String path,int page) throws Exception {
        var items=moderation.inventory(user,uuid,ender).get();String title="📦 Содержимое шалкера";
        var container=itemAt(items,path);
        if(container==null || !container.shulker()) return inventory(user,uuid,ender,0);
        var nested=container.contents();int pages=Math.max(1,(nested.size()+PAGE_SIZE-1)/PAGE_SIZE);page=Math.max(0,Math.min(page,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();StringBuilder text=new StringBuilder(title+" • страница "+(page+1)+"/"+pages+"\n");
        if(nested.isEmpty()) text.append("Шалкер пуст."); else for(int i=page*PAGE_SIZE;i<Math.min(nested.size(),page*PAGE_SIZE+PAGE_SIZE);i++) {var item=nested.get(i);text.append(item.label()).append(": ").append(item.amount()).append(" шт.\n");if(item.shulker()) buttons.add(b(user,item.label()+" · открыть","shulker",uuid+"|"+(ender?"ender":"main")+"|"+path+"."+i,0,buttons.size()));}
        String mode=ender?"ender":"main";if(page>0) buttons.add(b(user,"◀ Назад","shulker",uuid+"|"+mode+"|"+path,page-1,PAGE_SIZE));if(page+1<pages) buttons.add(b(user,"Вперёд ▶","shulker",uuid+"|"+mode+"|"+path,page+1,PAGE_SIZE));buttons.add(b(user,"↩ К инвентарю","inventory",uuid+"|"+mode,0,PAGE_SIZE+1));buttons.add(homeButton(PAGE_SIZE+2));return new TelegramCommands.View(text.toString().stripTrailing(),List.copyOf(buttons));
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
        requirePlayerAccess(user);String id=uuid.toString();List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        var p=moderation.info(user,uuid).get();
        if(allowed(user,"inventory")) buttons.add(b(user,"🎒 Инвентарь","inventory",id+"|main",0,row++));
        if(allowed(user,"ender")) buttons.add(b(user,"🧰 Эндер-сундук","inventory",id+"|ender",0,row++));
        if(allowed(user,"ban")) buttons.add(b(user,"⏳ Бан на 5 минут","confirm:ban",id,0,row++));if(allowed(user,"kick")) buttons.add(b(user,"🚪 Кик","confirm:kick",id,0,row++));if(allowed(user,"unban")) buttons.add(b(user,"🔓 Разбан","confirm:unban",id,0,row++));
        if(allowed(user,"heal")) buttons.add(b(user,"💚 Вылечить игрока","confirm:heal",id,0,row++));if(allowed(user,"kill")) buttons.add(b(user,"☠️ Убить игрока","confirm:kill",id,0,row++));if(allowed(user,"repair")) buttons.add(b(user,"🔧 Починить предметы","confirm:repair",id,0,row++));
        if(allowed(user,"scale")) buttons.add(b(user,"📏 Размер персонажа","scaleMenu",id,0,row++));if(allowed(user,"flySpeed")) buttons.add(b(user,"✈️ Скорость полёта","flySpeedMenu",id,0,row++));if(allowed(user,"walkSpeed")) buttons.add(b(user,"🏃 Скорость передвижения","walkSpeedMenu",id,0,row++));
        if(allowed(user,"teleport")) buttons.add(b(user,"📍 Телепортировать к игроку","teleportPlayers",id,0,row++));
        if(allowed(user,"teleportCoords")) buttons.add(b(user,"📍 Телепортировать на координаты","teleportCoords",id,0,row++));
        if(allowed(user,"chat")) buttons.add(b(user,"💬 Отправить сообщение в игровой чат","chat","",0,row++));
        if(allowed(user,"luckPerms")) {boolean admin=Boolean.TRUE.equals(moderation.adminGroup(user,uuid).get());buttons.add(b(user,(admin?"❌ Забрать":"✅ Выдать")+" группу admin в LP","confirm:toggleLp",id,0,row++));}
        if(allowed(user,"op")) buttons.add(b(user,"⭐ Выдать OP","confirm:op",id,0,row++));
        if(allowed(user,"deop")) buttons.add(b(user,"☆ Забрать OP","confirm:deop",id,0,row++));
        buttons.add(b(user,"↩ Назад к игрокам","browse:"+(p.online()?"online":"offline"),"",0,row++));buttons.add(homeButton(row));return new TelegramCommands.View("👤 "+p.name()+"\n"+p.details()+"\n\nДействия игрока",List.copyOf(buttons));
    }
    private TelegramCommands.View teleportPlayers(long user,String source,int page,String filter) throws Exception {
        guard.requireCapability(user,"teleport");var people=moderation.players(user,"online").get().stream().filter(p->!p.uuid().toString().equals(source)).toList();
        if(!filter.isBlank()) people=people.stream().filter(p->searchKey(p.name()).contains(searchKey(filter))).toList();
        page=Math.max(0,Math.min(page,Math.max(0,(people.size()-1)/PAGE_SIZE)));
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*PAGE_SIZE;i<Math.min(people.size(),page*PAGE_SIZE+PAGE_SIZE);i++) {var p=people.get(i);buttons.add(b(user,p.name(),"confirm:teleportPlayer",source+"|"+p.uuid(),0,i-page*PAGE_SIZE));}
        if(page>0) buttons.add(b(user,"◀ Назад","teleportPlayers",source+"|"+filter,page-1,PAGE_SIZE));
        if((page+1)*PAGE_SIZE<people.size()) buttons.add(b(user,"Вперёд ▶","teleportPlayers",source+"|"+filter,page+1,PAGE_SIZE));
        buttons.add(b(user,"🔎 Поиск","teleportSearch",source,0,PAGE_SIZE+1));buttons.add(b(user,"↩ Действия игрока","playerActions",source,0,PAGE_SIZE+2));buttons.add(homeButton(PAGE_SIZE+3));
        return new TelegramCommands.View("К какому игроку телепортировать?"+(people.isEmpty()?"\nДругих игроков онлайн нет.":""),List.copyOf(buttons));
    }
    private TelegramCommands.View scaleMenu(long user,UUID uuid) {
        guard.requireCapability(user,"scale");String id=uuid.toString();double[] values={0.1,0.5,0.65,0.75,0.85,1,1.5,2,5,10,15,20};List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=0;i<values.length;i++) buttons.add(b(user,"📏 "+values[i],"confirm:scale",id+"|"+values[i],0,i/2));
        buttons.add(b(user,"✏️ Своё значение","customNumber","scale|"+id,0,6));
        buttons.add(b(user,"↩ Действия игрока","playerActions",id,0,7));buttons.add(homeButton(7));
        return new TelegramCommands.View("📏 Установить размер персонажа",List.copyOf(buttons));
    }
    private TelegramCommands.View speedMenu(long user,UUID uuid,String kind) {
        guard.requireCapability(user,kind.equals("fly")?"flySpeed":"walkSpeed");String id=uuid.toString();double[] values={0.1,0.5,0.65,0.75,0.85,1,1.5,2,5,10,15,20};
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
        requireOperation(user,operation);
        if(Set.of("addSuper","removeSuper").contains(operation)) return new TelegramCommands.View((operation.equals("addSuper")?"Выдать все права супер администратора":"Удалить супер администратора")+"\nTelegram ID: "+value,List.of(b(user,"✅ Подтвердить","do:"+operation,value,0,0),b(user,"↩ Назад","admins","",0,1),homeButton(2)));
        if(operation.equals("teleportPlayer") || operation.equals("teleportCoords")) {
            String[] parts=value.split("\\|",2);String source=moderation.info(user,UUID.fromString(parts[0])).get().name();
            String destination=operation.equals("teleportPlayer")?moderation.info(user,UUID.fromString(parts[1])).get().name():parts[1]+" (текущий мир игрока)";
            return new TelegramCommands.View("Телепортировать «"+source+"» → "+destination+"?",List.of(b(user,"✅ Подтвердить","do:"+operation,value,0,0),b(user,"↩ Действия игрока","playerActions",parts[0],0,1),homeButton(2)));
        }
        String target=value;
        String lookup=(operation.equals("scale") || operation.equals("flySpeed") || operation.equals("walkSpeed")) ? value.substring(0,value.indexOf('|')) : value;
        if(Set.of("ban","kick","unban","toggleLp","heal","kill","repair","scale","flySpeed","walkSpeed","addLp","removeLp","op","deop").contains(operation)) target=moderation.info(user,UUID.fromString(lookup)).get().name()+"\nUUID: "+lookup;
        String label=switch(operation) {case "ban" -> "Бан на 5 минут: пока идёт расследование";case "kick" -> "Кик: пока идёт расследование";case "unban" -> "Снять бан";case "toggleLp" -> "Изменить группу LuckPerms admin";case "addLp" -> "Выдать группу LuckPerms admin";case "removeLp" -> "Удалить группу LuckPerms admin";case "heal" -> "Вылечить игрока";case "kill" -> "Убить игрока";case "repair" -> "Починить предметы";case "scale" -> "Установить размер "+value.substring(value.indexOf('|')+1);case "flySpeed" -> "Установить скорость полёта "+value.substring(value.indexOf('|')+1)+"x";case "walkSpeed" -> "Установить скорость передвижения "+value.substring(value.indexOf('|')+1)+"x";case "op" -> "Выдать OP";case "deop" -> "Забрать OP";case "addAdmin" -> "Добавить Telegram-администратора";case "removeAdmin" -> "Убрать Telegram-администратора";case "chat" -> "Отправить в игровой чат";default -> throw new IllegalArgumentException();};
        TelegramCommands.Button back=Set.of("addAdmin","removeAdmin").contains(operation)?b(user,"↩ Назад","admins","",0,1):operation.equals("chat")?b(user,"↩ Назад",guard.superAdmin(user)?"superHome":"adminHome","",0,1):b(user,"↩ Назад","playerActions",lookup,0,1);
        return new TelegramCommands.View(label+"\n\n"+target+"\n\nПодтвердите действие.",List.of(b(user,"✅ Подтвердить","do:"+operation,value,0,0),back,homeButton(2)));
    }
    private TelegramCommands.View perform(long user,String operation,String value) throws Exception {
        String result;
        requireOperation(user,operation);
        if(operation.equals("addSuper") || operation.equals("removeSuper")) {guard.changeSuper(user,Long.parseLong(value),operation.equals("addSuper")).get();return admins(user,0,"");}
        else if(operation.equals("addAdmin") || operation.equals("removeAdmin")) {guard.changeAdmin(user,Long.parseLong(value),operation.equals("addAdmin")).get();result="Список администраторов обновлён.";}
        else if(operation.equals("chat")) result=moderation.broadcast(user,value).get();
        else if(operation.equals("teleportPlayer") || operation.equals("teleportCoords")) {String[] parts=value.split("\\|",2);result=moderation.teleport(user,UUID.fromString(parts[0]),operation.equals("teleportPlayer")?UUID.fromString(parts[1]):null,operation.equals("teleportCoords")?parts[1]:null).get();}
        else if(operation.equals("scale")) {int split=value.indexOf('|');result=moderation.scale(user,UUID.fromString(value.substring(0,split)),Double.parseDouble(value.substring(split+1))).get();}
        else if(operation.equals("flySpeed") || operation.equals("walkSpeed")) {int split=value.indexOf('|');result=moderation.speed(user,UUID.fromString(value.substring(0,split)),operation.equals("flySpeed") ? "fly" : "walk",Double.parseDouble(value.substring(split+1))).get();}
        else result=moderation.act(user,operation,UUID.fromString(value)).get();
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(operation.startsWith("teleport")) buttons.add(b(user,"↩ Назад к действиям игрока","playerActions",value.split("\\|",2)[0],0,0));
        if(Set.of("ban","kick","unban","toggleLp","addLp","removeLp","heal","kill","repair","scale","flySpeed","walkSpeed","op","deop").contains(operation)) {
            String raw=operation.equals("scale") || operation.equals("flySpeed") || operation.equals("walkSpeed") ? value.substring(0,value.indexOf('|')) : value;
            buttons.add(b(user,"↩ Назад к действиям игрока","playerActions",raw,0,0));
        } else if(Set.of("addAdmin","removeAdmin").contains(operation)) buttons.add(b(user,"↩ Назад к администраторам","admins","",0,0));
        else if(operation.equals("chat")) buttons.add(b(user,"↩ Назад",guard.superAdmin(user)?"superHome":"adminHome","",0,0));
        buttons.add(homeButton(1));
        return new TelegramCommands.View(result,List.copyOf(buttons));
    }
    private void requireOperation(long user,String operation) {
        if(Set.of("addSuper","removeSuper","addAdmin","removeAdmin").contains(operation)) {guard.requireSuper(user);return;}
        String capability=switch(operation) {
            case "ban" -> "ban";case "kick" -> "kick";case "unban" -> "unban";
            case "toggleLp","addLp","removeLp" -> "luckPerms";
            case "heal" -> "heal";case "kill" -> "kill";case "repair" -> "repair";
            case "scale" -> "scale";case "flySpeed" -> "flySpeed";case "walkSpeed" -> "walkSpeed";
            case "teleportPlayer" -> "teleport";case "teleportCoords" -> "teleportCoords";case "op" -> "op";case "deop" -> "deop";
            case "chat" -> "chat";case "addAdmin","removeAdmin" -> "manageAdmins";
            default -> throw new IllegalArgumentException("Неизвестное действие");
        };
        guard.requireCapability(user,capability);
    }
    private TelegramCommands.Button b(long user,String label,String op,String value,int page,int row) {
        String key="vg:"+UUID.randomUUID().toString().replace("-","");actions.put(key,new Action(user,op,value,page,System.currentTimeMillis()));
        // Keep navigation effectively permanent while bounding memory if a chat is very busy.
        long count=actions.values().stream().filter(action->action.user()==user).count();
        if(count>2048) actions.entrySet().stream().filter(entry->entry.getValue().user()==user)
                .min(Comparator.comparingLong(entry->entry.getValue().createdAt())).ifPresent(entry->actions.remove(entry.getKey()));
        return new TelegramCommands.Button(label,key,row);
    }
    private static TelegramCommands.Button homeButton(int row) {return new TelegramCommands.Button("⌂ Меню","vg:menu",row);}
    static TelegramCommands.View withHome(TelegramCommands.View view) {
        if(view==null) return new TelegramCommands.View("Откройте кабинет заново.",List.of(homeButton(0)));
        var buttons=new ArrayList<>(view.buttons());int row=buttons.stream().mapToInt(TelegramCommands.Button::row).max().orElse(-1)+1;buttons.add(new TelegramCommands.Button("↩ В кабинет","vg:home",row));buttons.add(homeButton(row+1));return new TelegramCommands.View(view.text(),List.copyOf(buttons));
    }
}
