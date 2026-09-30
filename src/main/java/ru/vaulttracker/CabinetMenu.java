package ru.vaulttracker;

import java.util.*;

/** Telegram UI screens for cabinet. */
final class CabinetMenu extends TelegramMenuSection {
    CabinetMenu(TelegramGuardMenu root) {super(root);}

    TelegramCommands.View mainMenu(long user) {
        cancelInput(user);
        root.routes.cancelOperations(user);
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        buttons.add(new TelegramCommands.Button("🔎 Поиск","vg:search",0));
        buttons.add(new TelegramCommands.Button("👤 Личный кабинет","vg:home",1));
        buttons.add(b(user,"🆘 Поддержка","supportHome","",0,2));
        try {if(guard.donationsVisible().get()) buttons.add(b(user,"💝 Пожертвования","donations","",0,3));}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        catch(Exception unavailable) {}
        return new TelegramCommands.View("👋 Вас приветствует торговый бот FLEXITY!\n\n🔎 Хотите купить ресурс, но не знаете, у кого он есть? Нажмите «Поиск».\n🛡 Хотите защитить свои ресурсы? Откройте «Личный кабинет».",List.copyOf(buttons)).template("menu.main",Map.of());
    }
    TelegramCommands.View home(long user) throws Exception {
        cancelInput(user);
        adminPreview.remove(user);
        root.routes.cancelOperations(user);
        var account=guard.account(user).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text="👤 Личный кабинет"+(account==null ? "\nПривяжите персонажа, чтобы открыть личные ресурсы и события." : "\nПерсонаж: "+account.name()+cabinetDetails(user,account));
        if(account==null) buttons.add(b(user,"🔗 Привязать персонажа","link","",0,0));
        else {
            if(root.hasSharedResources(account.uuid())) buttons.add(b(user,"📦 Ресурсы","resourcesMenu","",0,0));
            else buttons.add(b(user,"📦 Мои ресурсы","resources","",0,0));
            buttons.add(b(user,"📊 Статистика","statistics","",0,1));
            buttons.add(b(user,"⚙️ Настройки","settings","",0,3));
            buttons.add(b(user,"💬 Сообщения","messages","",0,4));
            if(guard.child(account.uuid())!=null) buttons.add(b(user,"📏 Размер персонажа","childSizes","",0,8));
        }
        if(guard.superAdmin(user)) buttons.add(b(user,"🛠 Админ","adminMenu","",0,5));
        else if(guard.admin(user)) {
            buttons.add(b(user,"🛠 Функции администратора","adminHome","",0,5));
        }
        buttons.add(homeButton(9));
        if(!guard.enabled()) text+="\nОхрана отключена в guard.yml.";
        return new TelegramCommands.View(TelegramEmojiMarkup.bold(text),List.copyOf(buttons));
    }
    String cabinetDetails(long user,GuardService.Account account) {
        StringBuilder result=new StringBuilder();
        if(moderation!=null) try {
            var profile=moderation.cabinetProfile(user,account.uuid()).get();
            if(profile!=null) {
                result.append("\n\nЗдоровье: ").append(hearts(profile.health(),profile.maxHealth()));
                result.append("\n\nГолод: ").append(hunger(profile.food()));
                if(profile.world()!=null) result.append("\n\n📍 ")
                        .append(profile.world()).append(" · ").append(TelegramEmojiMarkup.code(coordinateNumbers(profile)));
                if(profile.playtimeSeconds()!=null) result.append("\n\n⏱ ").append(root.playtime(profile.playtimeSeconds()));
                if(profile.firstPlayed()>0) result.append("\nПервый вход: ").append(root.moscowDate(profile.firstPlayed()));
            }
        } catch(Exception ignored) {}
        result.append(donationDetails(account.uuid()));
        return result.toString();
    }
    String donationDetails(UUID uuid) {
        StringBuilder result=new StringBuilder();
        try {
            var wallet=guard.donations.wallet(uuid);long balance=wallet.get("balanceMinor").getAsLong();
            long until=wallet.has("premiumUntil")?wallet.get("premiumUntil").getAsLong():0;boolean active=until<0||until>java.time.Instant.now().getEpochSecond();
            if(balance!=0||active) {
                result.append("\n\nБаланс: ").append(DonationClient.money(balance));
                if(wallet.has("pendingMinor") && wallet.get("pendingMinor").getAsLong()>0)
                    result.append("\nДоступно: ").append(DonationClient.money(wallet.get("availableMinor").getAsLong()))
                            .append(" · до подтверждения заказа: ").append(DonationClient.money(wallet.get("pendingMinor").getAsLong()));
                result.append("\nПремиум: ").append(premiumStatus(until));
            }
        } catch(Exception ignored) {}
        return result.toString();
    }
    static String coordinateNumbers(TelegramModeration.CabinetProfile profile) {return profile.x()+" "+profile.y()+" "+profile.z();}
    static String hearts(Double health,double maxHealth) {
        if(health==null||!Double.isFinite(health)) return "нет данных";
        double safe=Math.max(0,Math.min(health,Math.max(0,maxHealth)));int slots=(int)Math.ceil(Math.max(0,maxHealth)/2.0),full=(int)Math.floor(safe/2.0);
        boolean half=safe-full*2>0.001;int empty=Math.max(0,slots-full-(half?1:0));
        return "♥".repeat(full)+(half?"♡":"")+"♤".repeat(empty);
    }
    static String hunger(Integer food) {
        if(food==null) return "нет данных";int safe=Math.max(0,Math.min(20,food)),full=safe/2;boolean half=safe%2!=0;
        return "◆".repeat(full)+(half?"◇":"")+"○".repeat(10-full-(half?1:0));
    }
    static String premiumStatus(long until) {
        if(until<0) return "бессрочный";long remaining=Math.max(0,until-java.time.Instant.now().getEpochSecond());
        if(remaining<=0) return "не активен";
        return "активен · осталось "+(remaining/86400)+" дн. "+(remaining%86400/3600)+" ч. "+(remaining%3600/60)+" мин.";
    }
    void requireDonations(long user) throws Exception {
        if(guard.account(user).get()==null || !guard.donationsVisible().get()) throw new IllegalArgumentException("Раздел пожертвований недоступен.");
    }
    TelegramCommands.View linkView(long user,boolean fromDonations) throws Exception {
        String instruction=guard.generate(user).get();
        return new TelegramCommands.View(instruction,List.of(fromDonations
                ?b(user,"↩ Пожертвования","donations","",0,0)
                :new TelegramCommands.Button("Обновить кабинет","vg:home")));
    }
    TelegramCommands.View donations(long user) throws Exception {
        if(!guard.donationsVisible().get()) throw new IllegalArgumentException("Раздел пожертвований недоступен.");
        if(guard.account(user).get()==null) return linkView(user,true);
        String balance="временно недоступен",premium="временно недоступен",extra="";
        try {var wallet=guard.donations.wallet(guard.account(user).get().uuid());balance=DonationClient.money(wallet.get("balanceMinor").getAsLong());premium=wallet.get("premium").getAsBoolean()?"активен":"не активен";
            if(wallet.has("pendingMinor") && wallet.get("pendingMinor").getAsLong()>0)
                extra="\n⏳ Доступно: "+DonationClient.money(wallet.get("availableMinor").getAsLong())+" · ждёт подтверждения: "+DonationClient.money(wallet.get("pendingMinor").getAsLong());
            if(wallet.get("blocked").getAsBoolean()) extra+="\n⚠️ Покупки приостановлены после возврата. Обратитесь к администратору.";
        } catch(Exception unavailable) {extra="\n⚠️ Сервис пополнений недоступен или ещё настраивается. Попробуйте позже.";}
        return new TelegramCommands.View("💝 Пожертвования FLEXITY\n\n💰 Баланс: "+balance+"\n💎 Премиум: "+premium+extra
                +"\n\nℹ️ Пожертвования добровольны. До подтверждения заказа монеты нельзя тратить; при возврате пополнение и бонус отменяются.",List.of(
                b(user,"💳 Пополнить","donationPay","",0,0),b(user,"💎 Премиум","donationPremium","",0,1),homeButton(2)));
    }
    TelegramCommands.View donationPay(long user) throws Exception {
        requireDonations(user);List<TelegramCommands.Button> buttons=new ArrayList<>();String text;
        try {
            if(!guard.donations.enabled()) throw new java.io.IOException();
            buttons.add(TelegramCommands.Button.link("Открыть FunPay",guard.donations.offerUrl(),0));
            text="Пожертвование добровольное.\n1 единица лота = 1 монета; комиссии FunPay могут увеличить сумму оплаты.\nПосле оплаты в чате заказа придёт код. Введите его здесь и подтвердите получение на FunPay. До подтверждения монеты нельзя тратить.\nЗа любой честный отзыв на подтверждённый заказ — бонус 10%, один раз. При возврате пополнение и бонус отменяются.";
            buttons.add(b(user,"Ввести код","donationCode","",0,1));
        } catch(Exception unavailable) {text="Приём пожертвований ещё настраивается. Оплата пока недоступна.";}
        buttons.add(b(user,"↩ Назад","donations","",0,2));return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    TelegramCommands.View donationAdmin(long user) throws Exception {
        guard.requireRoot(user);boolean visible=guard.donationsVisible().get();
        return new TelegramCommands.View("💝 Пожертвования\nКнопка у связанных игроков: "+(visible?"включена":"выключена"),List.of(
                b(user,visible?"Выключить кнопку у игроков":"Включить кнопку у игроков","donationVisibility",Boolean.toString(!visible),0,0),
                b(user,"Добавить премиум","donationPremiumAdd","",0,1),b(user,"Убрать премиум","donationPremiumRemove","",0,2),
                b(user,"Добавить пожертвования","donationPlayers","add|online|",0,3),b(user,"Отнять пожертвования","donationPlayers","subtract|online|",0,4),
                b(user,"Разблокировать покупки","donationPlayers","unblock|online|",0,5),
                b(user,"Журнал","donationJournal","",0,6),b(user,"↩ Root","rootHome","",0,7),homeButton(8)));
    }
    static String requestId(String value) {return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
    static int premiumDays(String operation) {
        if(operation.equals("remove")||operation.equals("unblock")) return 0;if(operation.equals("grantPermanent")) return -1;
        if(operation.startsWith("grant")) return Integer.parseInt(operation.substring(5));throw new IllegalArgumentException("Неизвестный срок премиума");
    }
    static String premiumConfirmation(String operation,String name) {
        if(operation.equals("unblock")) return "Разблокировать покупки для "+name+"? Долг должен быть погашен подтверждёнными средствами.";
        if(operation.equals("remove")) return "Убрать премиум у "+name+"?";
        int days=premiumDays(operation);return days<0?"Выдать бессрочный премиум: "+name+"?":"Выдать премиум на "+days+" дней: "+name+"?";
    }
    TelegramCommands.View premiumMenu(long user) throws Exception {
        requireDonations(user);var account=guard.account(user).get();var wallet=guard.donations.wallet(account.uuid());
        long until=moderation.premiumExpiry(account.uuid()),remaining=Math.max(0,until-java.time.Instant.now().getEpochSecond());
        String status=until<0?"бессрочный":remaining>0?"активен · осталось "+(remaining/86400)+" дн. "+(remaining%86400/3600)+" ч. "+(remaining%3600/60)+" мин.":"не активен";
        String available=wallet.has("availableMinor")?"\nДоступно для покупки: "+DonationClient.money(wallet.get("availableMinor").getAsLong()):"";
        return new TelegramCommands.View("⭐ Премиум\nБаланс: "+DonationClient.money(wallet.get("balanceMinor").getAsLong())+available+"\nСтатус: "+status+
            (wallet.has("premiumPending")&&wallet.get("premiumPending").getAsBoolean()?"\nИзменение премиума ожидает применения.":"")+"\nПокупка продлевает оставшийся срок.",List.of(
            b(user,"Купить · 600 монет · 30 дней","premiumBuy","30",0,0),b(user,"Купить · 1000 монет · 60 дней","premiumBuy","60",0,1),b(user,"↩ Назад","donations","",0,2)));
    }
    TelegramCommands.View donationResult(long user,com.google.gson.JsonObject result,boolean admin) {
        return new TelegramCommands.View("Операция сохранена. Баланс: "+DonationClient.money(result.get("balanceMinor").getAsLong())+
            (result.has("pending")?"\nВыдача группы ожидает повторной синхронизации; повторно платить не нужно.":""),List.of(b(user,"↩ Назад",admin?"donationAdmin":"donationPremium","",0,0)));
    }
    TelegramCommands.View donationPlayers(long user,String state,int requested) throws Exception {
        guard.requireRoot(user);String[] parts=state.split("\\|",3);String operation=parts[0],mode=parts[1],filter=parts.length>2?parts[2]:"";
        var rows=moderation.players(user,mode.equals("online")?"online":"all").get().stream().filter(p->p.online()==mode.equals("online")&&root.searchKey(p.name()).contains(root.searchKey(filter))).toList();
        int pages=Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE),page=Math.max(0,Math.min(requested,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*PAGE_SIZE;i<Math.min(rows.size(),page*PAGE_SIZE+PAGE_SIZE);i++){var person=rows.get(i);buttons.add(b(user,person.name(),"donationTarget",operation+"|"+person.uuid(),0,i-page*PAGE_SIZE));}
        if(page>0) buttons.add(b(user,"◀ Назад","donationPlayers",state,page-1,PAGE_SIZE));if(page+1<pages) buttons.add(b(user,"Вперёд ▶","donationPlayers",state,page+1,PAGE_SIZE));
        buttons.add(b(user,mode.equals("online")?"Игроки офлайн":"Игроки онлайн","donationPlayers",operation+"|"+(mode.equals("online")?"offline":"online")+"|"+filter,0,21));
        buttons.add(b(user,"🔎 Поиск","donationPlayerSearch",operation+"|"+mode,0,22));buttons.add(b(user,"↩ Назад","donationAdmin","",0,23));
        return new TelegramCommands.View("Выберите игрока · "+(mode.equals("online")?"онлайн":"офлайн")+" · "+(page+1)+"/"+pages+(rows.isEmpty()?"\nИгроков не найдено.":""),List.copyOf(buttons));
    }
    TelegramCommands.View donationJournal(long user,int page) throws Exception {
        guard.requireRoot(user);var body=new com.google.gson.JsonObject();body.addProperty("page",page);var result=guard.donations.call("donation-audit",body);
        page=result.get("page").getAsInt();int pages=result.get("pages").getAsInt();StringBuilder text=new StringBuilder("Журнал пожертвований · "+(page+1)+"/"+pages);
        Map<String,String> labels=Map.of("buy","покупка премиума","grant","выдача премиума","remove","снятие премиума","add","начисление","subtract","списание","unblock","разблокировка покупок");
        for(var element:result.getAsJsonArray("rows")){var row=element.getAsJsonObject();String action=row.get("action").getAsString();long value=row.get("amount").getAsLong();text.append("\n\n").append(java.time.Instant.ofEpochSecond(row.get("created").getAsLong()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))).append(" · ").append(row.get("actor").getAsString()).append(" → ").append(row.get("name").getAsString()).append("\n").append(labels.getOrDefault(action,action));if(!action.equals("remove"))text.append(": ").append(Set.of("buy","grant").contains(action)?(value<0?"навсегда":value+" дней"):DonationClient.money(value));}
        List<TelegramCommands.Button> buttons=new ArrayList<>();if(page>0)buttons.add(b(user,"◀ Назад","donationJournal","",page-1,0));if(page+1<pages)buttons.add(b(user,"Вперёд ▶","donationJournal","",page+1,0));buttons.add(b(user,"↩ Назад","donationAdmin","",0,1));return new TelegramCommands.View(text.toString(),List.copyOf(buttons));
    }

    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("CabinetMenu");
        scope.direct("vg:home",(user,data)->home(user));
        scope.direct("vg:menu",(user,data)->mainMenu(user));
        scope.on("link",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,true, (user,a,data)-> {
            TelegramCommands.View view;
            view=linkView(user,false);
            return view;
        });
        scope.on("donations",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=donations(user);
            return view;
        });
        scope.on("donationPay",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=donationPay(user);
            return view;
        });
        scope.on("donationCode",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {requireDonations(user);view=prompt(user,"donationCode","Введите одноразовый код из чата вашего оплаченного заказа FunPay. Не пересылайте код другим людям.");}
            return view;
        });
        scope.on("donationPremium",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=premiumMenu(user);
            return view;
        });
        scope.on("premiumBuy",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {requireDonations(user);var account=guard.account(user).get();view=donationResult(user,moderation.donationChange(user,account.uuid(),account.name(),requestId(data),"buy",Integer.parseInt(a.value()),0),false);}
            return view;
        });
        scope.on("donationAdmin",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=donationAdmin(user);
            return view;
        });
        scope.on("donationVisibility",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.donationsVisible(user,Boolean.parseBoolean(a.value())).get();view=donationAdmin(user);}
            return view;
        });
        scope.on("donationPremiumAdd",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=new TelegramCommands.View("Выберите срок премиума",List.of(
                                root.b(user,"На 30 дней","donationPlayers","grant30|online|",0,0),root.b(user,"На 60 дней","donationPlayers","grant60|online|",0,1),
                                root.b(user,"Навсегда","donationPlayers","grantPermanent|online|",0,2),root.b(user,"Свой срок","donationPlayers","grantCustom|online|",0,3),
                                root.b(user,"↩ Назад","donationAdmin","",0,4)));}
            return view;
        });
        scope.on("donationPremiumRemove",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=donationPlayers(user,"remove|online|",0);
            return view;
        });
        scope.on("donationPlayers",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=donationPlayers(user,a.value(),a.page());
            return view;
        });
        scope.on("donationPlayerSearch",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);view=prompt(user,"donationSearch:"+a.value(),"Введите часть игрового ника.");}
            return view;
        });
        scope.on("donationTarget",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);String[] parts=a.value().split("\\|",2);UUID target=UUID.fromString(parts[1]);
                            if(parts[0].equals("add")||parts[0].equals("subtract")) view=prompt(user,"donationAmount:"+a.value()+"|"+requestId(data),"Введите сумму "+(parts[0].equals("add")?"начисления":"списания")+". Например: 100 или 100.50.");
                            else if(parts[0].equals("grantCustom")) view=prompt(user,"donationPremiumDays:"+target,"Введите собственный срок премиума в днях — целое число от 1 до 36500.");
                            else {var person=moderation.info(user,target).get();view=new TelegramCommands.View(premiumConfirmation(parts[0],person.name()),List.of(root.b(user,"Подтвердить","donationApply",a.value(),0,0),root.b(user,"↩ Назад","donationAdmin","",0,1)));}}
            return view;
        });
        scope.on("donationApply",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireRoot(user);String[] parts=a.value().split("\\|",2);UUID target=UUID.fromString(parts[1]);var person=moderation.info(user,target).get();int days=premiumDays(parts[0]);view=donationResult(user,moderation.donationChange(user,target,person.name(),requestId(data),Set.of("remove","unblock").contains(parts[0])?parts[0]:"grant",days,0),true);}
            return view;
        });
        scope.on("donationJournal",TelegramActionRegistry.Arguments.PAGE,TelegramActionRegistry.Requirement.ROOT,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=donationJournal(user,a.page());
            return view;
        });
    }

    TelegramCommands.View onInputEarly(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().startsWith("donationSearch:")) {guard.requireRoot(user);String query=text.trim();if(query.isEmpty()||query.length()>64)return new TelegramCommands.View("Введите от 1 до 64 символов.");waiting.remove(user);return donationPlayers(user,pending.kind().substring(15)+"|"+query,0);}
        if(pending.kind().startsWith("donationAmount:")) {
            guard.requireRoot(user);String[] parts=pending.kind().substring(15).split("\\|");long amount;
            try {amount=new java.math.BigDecimal(text.trim().replace(',','.')).movePointRight(2).longValueExact();if(amount<=0||amount>100_000_000)throw new IllegalArgumentException();}
            catch(IllegalArgumentException|ArithmeticException invalid){return new TelegramCommands.View("Введите сумму от 0.01 до 1000000, максимум два знака после точки.");}
            UUID owner=UUID.fromString(parts[1]);var person=moderation.info(user,owner).get();var result=moderation.donationChange(user,owner,person.name(),parts[2],parts[0],0,amount);waiting.remove(user);return donationResult(user,result,true);
        }
        if(pending.kind().equals("donationCode")&&pending.expires()>=System.currentTimeMillis()) {
            if(guard.account(user).get()==null) return new TelegramCommands.View("Сначала свяжите игровой аккаунт в личном кабинете, затем отправьте код снова.",List.of(new TelegramCommands.Button("Личный кабинет","vg:home")));
            String code=text.trim().toUpperCase(Locale.ROOT);
            if(!code.matches("[A-F0-9]{32}")) return new TelegramCommands.View("Проверьте код: нужны 32 символа из сообщения FunPay. Отмена: /cancel.",List.of(root.b(user,"↩ Назад","donations","",0,0)));
            try {
                var result=guard.donations.redeem(guard.account(user).get().uuid(),code);waiting.remove(user);
                String message=result.get("alreadyClaimed").getAsBoolean()?"Этот заказ уже зачислен ранее.":"Оплата проверена. Зачислено: "+DonationClient.money(result.get("amountMinor").getAsLong())+" монет.";
                return new TelegramCommands.View(message+"\nБаланс: "+DonationClient.money(result.get("balanceMinor").getAsLong())+"\nПроверьте баланс и подтвердите получение заказа на FunPay.",List.of(root.b(user,"↩ Пожертвования","donations","",0,0)));
            } catch(Exception unavailable) {return new TelegramCommands.View("Не удалось зачислить код: он недоступен, уже использован другим аккаунтом или проверка временно не работает. Повторите через минуту: повторного списания или зачисления не будет.",List.of(root.b(user,"↩ Назад","donations","",0,0)));}
        }
        return null;
    }

    TelegramCommands.View onInput(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().startsWith("donationPremiumDays:")) {
            guard.requireRoot(user);int days;
            try {days=Integer.parseInt(text.trim());if(days<1||days>36500) throw new NumberFormatException();}
            catch(NumberFormatException invalid) {return new TelegramCommands.View("Введите целое число дней от 1 до 36500. Отмена: /cancel.",List.of(root.b(user,"↩ Пожертвования","donationAdmin","",0,0),homeButton(1)));}
            UUID target=UUID.fromString(pending.kind().substring("donationPremiumDays:".length()));var person=moderation.info(user,target).get();waiting.remove(user);
            return new TelegramCommands.View(premiumConfirmation("grant"+days,person.name()),List.of(root.b(user,"Подтвердить","donationApply","grant"+days+"|"+target,0,0),root.b(user,"↩ Назад","donationAdmin","",0,1)));
        }
        return null;
    }
}
