package ru.vaulttracker;

import java.util.*;

/** Private cabinet. Permissions are checked on every read and confirmed action. */
final class TelegramGuardMenu {
    final CabinetMenu cabinet;
    final ResourceMenu resources;
    final FriendsMenu friendsMenu;
    final MessagesMenu messages;
    final SettingsMenu settingsMenu;
    final StatisticsMenu statisticsMenu;
    final AdminMenu admin;
    final SuperAdminMenu superAdmin;
    final SupportMenu support;
    final VotingMenu voting;
    record Input(String kind,long expires) {}
    final TelegramActionRegistry routes;
    final Map<Long,Input> waiting=new java.util.concurrent.ConcurrentHashMap<>();
    final Map<Long,Integer> dialogCards=new java.util.concurrent.ConcurrentHashMap<>();
    final Map<Long,Integer> editedDialogs=new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String,Integer> playerPages=new java.util.concurrent.ConcurrentHashMap<>();
    final Set<Long> adminPreview=java.util.concurrent.ConcurrentHashMap.newKeySet();
    final GuardService guard;
    final TelegramCommands commands;
    final TelegramModeration moderation;
    final TelegramTagManager tags;
    final TelegramApi api;
    TelegramGuardMenu(GuardService guard,TelegramCommands commands) {this(guard,commands,null,null);}
    TelegramGuardMenu(GuardService guard,TelegramCommands commands,TelegramModeration moderation) {this(guard,commands,moderation,null);}
    TelegramGuardMenu(GuardService guard,TelegramCommands commands,TelegramModeration moderation,TelegramTagManager tags) {this(guard,commands,moderation,tags,null);}
    TelegramGuardMenu(GuardService guard,TelegramCommands commands,TelegramModeration moderation,TelegramTagManager tags,TelegramApi api) {
        this(guard,commands,moderation,tags,api,null);
    }
    TelegramGuardMenu(GuardService guard,TelegramCommands commands,TelegramModeration moderation,TelegramTagManager tags,TelegramApi api,SupportService supportService) {
        this.guard=guard;this.commands=commands;this.moderation=moderation;this.tags=tags;this.api=api;
        cabinet=new CabinetMenu(this);resources=new ResourceMenu(this);friendsMenu=new FriendsMenu(this);
        messages=new MessagesMenu(this);settingsMenu=new SettingsMenu(this);statisticsMenu=new StatisticsMenu(this);admin=new AdminMenu(this);superAdmin=new SuperAdminMenu(this);
        support=new SupportMenu(this,supportService);voting=new VotingMenu(this);
        routes=new TelegramActionRegistry(guard);
        cabinet.registerActions(routes);resources.registerActions(routes);friendsMenu.registerActions(routes);
        messages.registerActions(routes);settingsMenu.registerActions(routes);statisticsMenu.registerActions(routes);admin.registerActions(routes);superAdmin.registerActions(routes);support.registerActions(routes);voting.registerActions(routes);
        commands.sharedAccess((user,owner)->{var account=guard.account(user).join();return account!=null&&guard.canSearch(account.uuid(),owner);});
    }

    TelegramCommands.View notification(GuardService.Delivery delivery) {
        long user=delivery.recipient();
        if(!guard.admin(user)) return new TelegramCommands.View(delivery.text());
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(delivery.actorUuid()!=null && moderation!=null) {
            String id=delivery.actorUuid().toString();
            buttons.add(b(user,"💬 Написать игроку","privateMessage",id,0,0));
            if(allowed(user,"ban")) buttons.add(b(user,"⏳ Бан на 5 минут","confirm:ban",id,0,1));
            if(allowed(user,"freeze") && moderation.freezeAvailable()) buttons.add(b(user,"❄ Заморозить","confirm:freeze",id,0,2));
        }
        buttons.add(b(user,"✓ Игнорировать","ignoreNotification",delivery.text(),0,3));
        return new TelegramCommands.View(delivery.text(),List.copyOf(buttons));
    }
    TelegramCommands.Callback callback(long user,String data) throws Exception {
        cancelInput(user);
        return routes.dispatch(user,data);
    }
    static TelegramCommands.Callback answer(TelegramCommands.View view) {return new TelegramCommands.Callback(navigation(view),"",false);}
    private static TelegramCommands.View navigation(TelegramCommands.View view) {
        if(view==null || view.buttons().stream().anyMatch(b->b.data().equals("vg:menu"))
                || view.buttons().stream().anyMatch(b->b.data().equals("vg:search"))) return view;
        return withHome(view);
    }
    void cancelInput(long user) {waiting.remove(user);}
    boolean rootConsolePending(long user) {
        Input input=waiting.get(user);
        return input!=null && input.kind().equals("rootConsoleInput") && input.expires()>=System.currentTimeMillis();
    }
    void cancelSupport(long user) {support.cancel(user);}
    TelegramCommands.View supportMedia(long user,TelegramApi.Incoming incoming) throws Exception {return support.media(user,incoming);}
    TelegramCommands.View prompt(long user,String kind,String text) {
        if(kind.equals("donationCode")) {waiting.put(user,new Input(kind,Long.MAX_VALUE));return withHome(new TelegramCommands.View(text+"\nКод можно отправить в этот чат в любое время. Отмена: /cancel."));}
        waiting.entrySet().removeIf(e->e.getValue().expires()<System.currentTimeMillis());waiting.put(user,new Input(kind,System.currentTimeMillis()+600000));
        if(kind.startsWith("personalTopSearch:")) return new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel.",List.of(
                b(user,"↩️ Личный топ","personalTop",kind.substring("personalTopSearch:".length()),0,0),homeButton(1)));
        if(kind.startsWith("child")) return new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel.",List.of(b(user,"↩ Дети","children","",0,0),homeButton(1)));
        if(kind.equals("friendAdd")) return new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel.",List.of(b(user,"↩ Друзья","friends","",0,0),homeButton(1)));
        if(kind.startsWith("donationPremiumDays:")) return new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel.",List.of(b(user,"↩ Пожертвования","donationAdmin","",0,0),homeButton(1)));
        if(kind.startsWith("votingReward:")) return new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel.",List.of(
                b(user,"↩ К ресурсу","votingProvider",kind.substring("votingReward:".length()),0,0),homeButton(1)));
        return withHome(new TelegramCommands.View(text+"\nВвод действует 10 минут. Отмена: /cancel."));
    }
    TelegramCommands.View input(long user,String text) throws Exception {
        try {return navigation(inputValue(user,text));}
        catch(DonationClient.Rejected rejected) {return navigation(new TelegramCommands.View(rejected.getMessage()+"\nВведите другую сумму или /cancel."));}
    }
    private TelegramCommands.View inputValue(long user,String text) throws Exception {
        Input pending=waiting.get(user);
        if((pending==null || !pending.kind().equals("rootConsoleInput")) && text!=null&&text.trim().matches("(?i)[A-F0-9]{32}")) pending=new Input("donationCode",Long.MAX_VALUE);
        if(pending==null) return null;
        TelegramCommands.View view=resources.onInputEarly(user,text,pending);
        if(view==null) view=messages.onInputEarly(user,text,pending);
        if(view==null) view=admin.onInputEarly(user,text,pending);
        if(view==null) view=cabinet.onInputEarly(user,text,pending);
        if(view==null) view=support.onInput(user,text,pending);
        if(view!=null) return view;
        if(pending.expires()<System.currentTimeMillis()) {waiting.remove(user);return withHome(new TelegramCommands.View("Время ввода истекло. Откройте настройки снова."));}
        view=cabinet.onInput(user,text,pending);
        if(view==null) view=voting.onInput(user,text,pending);
        if(view==null) view=friendsMenu.onInput(user,text,pending);
        if(view==null) view=superAdmin.onInput(user,text,pending);
        if(view==null) view=admin.onInput(user,text,pending);
        if(view!=null) return view;
        long value;
        boolean roleInput=Set.of("addAdmin","addSuper","removeSuper").contains(pending.kind());
        try {value=Long.parseLong(text.trim());if(value<0 || (!roleInput && value>GuardService.MAX_OFFLINE_SECONDS)) throw new NumberFormatException();}
        catch(NumberFormatException e) {return new TelegramCommands.View(roleInput?"Введите положительное целое число Telegram ID. Отмена: /cancel.":"Введите целое число секунд от 0 до 31536000. Отмена: /cancel.");}
        if(pending.kind().equals("addAdmin")) return admin.adminIdInput(user,pending,value);
        if(Set.of("addSuper","removeSuper").contains(pending.kind())) return superAdmin.superIdInput(user,pending,value);
        return settingsMenu.timeInput(user,pending,value);
    }
    TelegramCommands.Button b(long user,String label,String op,String value,int page,int row) {
        return routes.button(user,label,op,value,page,row);
    }
    static TelegramCommands.Button homeButton(int row) {return new TelegramCommands.Button("⌂ Меню","vg:menu",row);}
    static TelegramCommands.View withHome(TelegramCommands.View view) {
        if(view==null) return new TelegramCommands.View("Откройте кабинет заново.",List.of(homeButton(0)));
        var buttons=new ArrayList<>(view.buttons());int row=buttons.stream().mapToInt(TelegramCommands.Button::row).max().orElse(-1)+1;buttons.add(new TelegramCommands.Button("↩ В кабинет","vg:home",row));buttons.add(homeButton(row+1));return view.withButtons(List.copyOf(buttons));
    }
    TelegramCommands.View resourceHome(long user,UUID owner) {return resources.resourceHome(user,owner);}
    TelegramCommands.View resourceResults(long user,String query,int requested) throws Exception {return resources.resourceResults(user,query,requested);}
    TelegramCommands.View resourceResults(long user,String query,int requested,boolean initial) throws Exception {return resources.resourceResults(user,query,requested,initial);}
    TelegramCommands.View resourceResults(long user,String query,int requested,boolean initial,UUID sharedOwner) throws Exception {return resources.resourceResults(user,query,requested,initial,sharedOwner);}
    TelegramCommands.View mainMenu(long user) {return cabinet.mainMenu(user);}
    TelegramCommands.View home(long user) throws Exception {return cabinet.home(user);}
    boolean hasSharedResources(UUID owner) {return resources.hasSharedResources(owner);}
    TelegramCommands.View resourcesMenu(long user) throws Exception {return resources.resourcesMenu(user);}
    TelegramCommands.View personalTop(long user,int requested,String filter) throws Exception {return resources.personalTop(user,requested,filter);}
    static boolean personalTopMatches(Catalogue.PersonalRank rank,String filter) {return ResourceMenu.personalTopMatches(rank,filter);}
    void rememberDialogCard(long user,int messageId) {messages.rememberDialogCard(user,messageId);}
    int takeDialogEditTarget(long user) {return messages.takeDialogEditTarget(user);}
    TelegramCommands.View dialogs(long user,int requested) throws Exception {return messages.dialogs(user,requested);}
    TelegramCommands.View messagePlayers(long user,int requested) throws Exception {return messages.messagePlayers(user,requested);}
    TelegramCommands.View dialog(long user,UUID peer,int requested) throws Exception {return messages.dialog(user,peer,requested);}
    static String shortMessage(String value) {return MessagesMenu.shortMessage(value);}
    String cabinetDetails(long user,GuardService.Account account) {return cabinet.cabinetDetails(user,account);}
    String donationDetails(UUID uuid) {return cabinet.donationDetails(uuid);}
    static String coordinateNumbers(TelegramModeration.CabinetProfile profile) {return CabinetMenu.coordinateNumbers(profile);}
    static String hearts(Double health,double maxHealth) {return CabinetMenu.hearts(health,maxHealth);}
    static String hunger(Integer food) {return CabinetMenu.hunger(food);}
    static String premiumStatus(long until) {return CabinetMenu.premiumStatus(until);}
    TelegramCommands.View eventsHome(long user) throws Exception {return resources.eventsHome(user);}
    void requireShared(long user,UUID owner) throws Exception {friendsMenu.requireShared(user,owner);}
    TelegramCommands.View sharedOwners(long user,int requested) throws Exception {return friendsMenu.sharedOwners(user,requested);}
    TelegramCommands.View friendView(long user,UUID uuid) throws Exception {return friendsMenu.friendView(user,uuid);}
    TelegramCommands.View friends(long user,int requestedPage) throws Exception {return friendsMenu.friends(user,requestedPage);}
    TelegramCommands.View adminHome(long user) throws Exception {return admin.adminHome(user);}
    TelegramCommands.View adminMenu(long user) {return superAdmin.adminMenu(user);}
    TelegramCommands.View banMenu(long user) {return admin.banMenu(user);}
    TelegramCommands.View superHome(long user) throws Exception {return superAdmin.superHome(user);}
    TelegramCommands.View rootHome(long user) {return superAdmin.rootHome(user);}
    TelegramCommands.View rootCommand(long user,String input) throws Exception {
        guard.requireRoot(user);
        String stripped=input.strip();int space=stripped.indexOf(' ');
        String verb=TelegramCommands.command(stripped);
        String args=space<0?"":stripped.substring(space+1).strip();
        if(verb.equalsIgnoreCase("/cmd")) {
            if(args.isBlank() || args.length()>2049 || args.indexOf('\n')>=0 || args.indexOf('\r')>=0)
                return new TelegramCommands.View("Использование: /cmd <команда консоли до 2048 символов>");
            return superAdmin.runConsoleCommand(user,args);
        }
        if(!verb.equalsIgnoreCase("/log")) return null;
        String[] parts=args.isBlank()?new String[0]:args.split("\\s+");
        if(parts.length>2 || (parts.length>0 && !Set.of("md","log").contains(parts[0].toLowerCase(Locale.ROOT))))
            return new TelegramCommands.View("Использование: /log [md|log] [число строк]");
        Integer lines=null;
        if(parts.length==2) try {lines=Integer.parseInt(parts[1]);if(lines<1 || lines>RootLogDocument.MAX_LINES) throw new NumberFormatException();}
            catch(NumberFormatException invalid) {return new TelegramCommands.View("Использование: /log [md|log] [число строк от 1 до "+RootLogDocument.MAX_LINES+"]");}
        return superAdmin.sendLogs(user,lines,parts.length>0 && parts[0].equalsIgnoreCase("md"));
    }
    void requireDonations(long user) throws Exception {cabinet.requireDonations(user);}
    TelegramCommands.View donations(long user) throws Exception {return cabinet.donations(user);}
    TelegramCommands.View donationPay(long user) throws Exception {return cabinet.donationPay(user);}
    TelegramCommands.View donationAdmin(long user) throws Exception {return cabinet.donationAdmin(user);}
    static String requestId(String value) {return CabinetMenu.requestId(value);}
    static int premiumDays(String operation) {return CabinetMenu.premiumDays(operation);}
    static String premiumConfirmation(String operation,String name) {return CabinetMenu.premiumConfirmation(operation,name);}
    TelegramCommands.View premiumMenu(long user) throws Exception {return cabinet.premiumMenu(user);}
    TelegramCommands.View donationResult(long user,com.google.gson.JsonObject result,boolean admin) {return cabinet.donationResult(user,result,admin);}
    TelegramCommands.View donationPlayers(long user,String state,int requested) throws Exception {return cabinet.donationPlayers(user,state,requested);}
    TelegramCommands.View donationJournal(long user,int page) throws Exception {return cabinet.donationJournal(user,page);}
    TelegramCommands.View children(long user,int requested,String filter) {return superAdmin.children(user,requested,filter);}
    TelegramCommands.View childResult(long user,String result) {return superAdmin.childResult(user,result);}
    TelegramCommands.View childSizes(long user) throws Exception {return superAdmin.childSizes(user);}
    TelegramCommands.View capabilities(long user) {return superAdmin.capabilities(user);}
    TelegramCommands.View settings(long user,boolean root) throws Exception {return settingsMenu.settings(user,root);}
    TelegramCommands.View playerSettings(long user) throws Exception {return settingsMenu.playerSettings(user);}
    TelegramCommands.View itemSorting(long user) throws Exception {return settingsMenu.itemSorting(user);}
    TelegramCommands.View notifications(long user) throws Exception {return settingsMenu.notifications(user);}
    TelegramCommands.View ownNotifications(long user) throws Exception {return settingsMenu.ownNotifications(user);}
    TelegramCommands.View tagMenu(long user) throws Exception {return settingsMenu.tagMenu(user);}
    TelegramCommands.View tag(long user,boolean apply) throws Exception {return settingsMenu.tag(user,apply);}
    static String duration(long seconds) {return SettingsMenu.duration(seconds);}
    TelegramCommands.View history(long user,int page,boolean own) throws Exception {return resources.history(user,page,own);}
    TelegramCommands.View history(long user,int page,boolean own,String filter) throws Exception {return resources.history(user,page,own,filter);}
    TelegramCommands.View detail(long user,long id,int page,boolean own) throws Exception {return resources.detail(user,id,page,own);}
    TelegramCommands.View admins(long user,int page,String filter) {return superAdmin.admins(user,page,filter);}
    TelegramCommands.View admins(long user,int page,String filter,boolean supers) {return superAdmin.admins(user,page,filter,supers);}
    TelegramCommands.View players(long user,String mode,int page) throws Exception {return admin.players(user,mode,page);}
    TelegramCommands.View players(long user,String mode,int page,String filter) throws Exception {return admin.players(user,mode,page,filter);}
    static String searchKey(String value) {return ResourceMenu.searchKey(value);}
    boolean allowed(long user,String capability) {return admin.allowed(user,capability);}
    boolean allowedPlayerAction(long user,String capability) {return admin.allowedPlayerAction(user,capability);}
    boolean hasPlayerTools(long user) {return admin.hasPlayerTools(user);}
    void requirePlayerAccess(long user) {admin.requirePlayerAccess(user);}
    boolean hasDirectPlayerActions(long user) {return admin.hasDirectPlayerActions(user);}
    TelegramCommands.View person(long user,UUID uuid) throws Exception {return admin.person(user,uuid);}
    TelegramCommands.View inventory(long user,UUID uuid,boolean ender,int page) throws Exception {return admin.inventory(user,uuid,ender,page);}
    TelegramCommands.View shulker(long user,UUID uuid,boolean ender,String path,int page) throws Exception {return admin.shulker(user,uuid,ender,path,page);}
    static TelegramModeration.ItemView itemAt(List<TelegramModeration.ItemView> items,String path) {return AdminMenu.itemAt(items,path);}
    TelegramCommands.View playerActions(long user,UUID uuid) throws Exception {return admin.playerActions(user,uuid);}
    TelegramCommands.View playerActions(long user,UUID uuid,int requestedPage) throws Exception {return admin.playerActions(user,uuid,requestedPage);}
    TelegramCommands.View freezeMenu(long user,UUID uuid) {return admin.freezeMenu(user,uuid);}
    static String yesNo(boolean value) {return AdminMenu.yesNo(value);}
    static String moscowDate(long millis) {return AdminMenu.moscowDate(millis);}
    static String playtime(long seconds) {return AdminMenu.playtime(seconds);}
    TelegramCommands.View teleportPlayers(long user,String source,int page,String filter) throws Exception {return admin.teleportPlayers(user,source,page,filter);}
    TelegramCommands.View scaleMenu(long user,UUID uuid) {return admin.scaleMenu(user,uuid);}
    TelegramCommands.View speedMenu(long user,UUID uuid,String kind) {return admin.speedMenu(user,uuid,kind);}
    TelegramCommands.View messagePreview(long user,String id,String text,TelegramModeration.MessageSender sender) throws Exception {return admin.messagePreview(user,id,text,sender);}
    TelegramCommands.View confirm(long user,String operation,String value) throws Exception {return admin.confirm(user,operation,value);}
    TelegramCommands.View perform(long user,String operation,String value) throws Exception {return admin.perform(user,operation,value);}
    void requireOperation(long user,String operation) {admin.requireOperation(user,operation);}

}
