package ru.vaulttracker;

import java.util.*;

/** Telegram UI screens for admin. */
final class AdminMenu extends TelegramMenuSection {
    private record PlayerAction(String label,String op,String value) {}
    AdminMenu(TelegramGuardMenu root) {super(root);}

    TelegramCommands.View adminHome(long user) throws Exception {
        guard.requireAdmin(user);boolean on=guard.adminAlerts(user).get();List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(guard.superAdmin(user)) adminPreview.add(user);
        buttons.add(b(user,(on ? "🔔 Отключить" : "🔕 Включить")+" уведомления администратора","admin","",0,0));
        buttons.add(b(user,"👥 Список игроков","browse:online","",0,1));
        buttons.add(b(user,"🗂 События администраторов","history","",0,2));
        buttons.add(guard.superAdmin(user)?b(user,"↩ Назад","adminMenu","",0,3):new TelegramCommands.Button("↩ Назад","vg:home",3));buttons.add(homeButton(4));return new TelegramCommands.View("🛠 Функции администратора\nУведомления администратора: "+(on ? "включены" : "выключены"),List.copyOf(buttons));
    }
    TelegramCommands.View banMenu(long user) {
        guard.requireCapability(user,"ban");
        return new TelegramCommands.View("⏳ Временный бан · 5 минут\nКаких игроков показать?",List.of(
                b(user,"🟢 Игроки онлайн","browse:banOnline","",0,0),
                b(user,"👥 Все игроки","browse:banAll","",0,1),
                b(user,"🛠 Назад в настройки администратора","adminHome","",0,2),homeButton(3)));
    }
    TelegramCommands.View players(long user,String mode,int page) throws Exception { return players(user,mode,page,""); }
    TelegramCommands.View players(long user,String mode,int page,String filter) throws Exception {
        if(mode.equals("kick")) guard.requireCapability(user,"kick");
        else if(mode.equals("banOnline") || mode.equals("banAll")) guard.requireCapability(user,"ban");
        else if(mode.equals("banned")) guard.requireCapability(user,"unban");
        else if(Set.of("online","all","offline").contains(mode)) requirePlayerAccess(user);
        else guard.requireAdmin(user);
        if(moderation==null) return withHome(new TelegramCommands.View("Управление сервером недоступно."));
        String source=switch(mode) {case "kick","banOnline" -> "online";case "banAll","offline" -> "all";default -> mode;};
        String operation=Set.of("banOnline","banAll").contains(mode) ? "ban" : mode;
        var people=moderation.players(user,source).get();if(mode.equals("offline")) people=people.stream().filter(p->!p.online()).toList();
        if(!filter.isBlank()) {String needle=root.searchKey(filter);people=people.stream().filter(p->root.searchKey(p.name()).contains(needle)).toList();}
        page=Math.max(0,Math.min(page,Math.max(0,(people.size()-1)/PAGE_SIZE)));
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*PAGE_SIZE;i<Math.min(people.size(),page*PAGE_SIZE+PAGE_SIZE);i++) {var p=people.get(i);buttons.add(b(user,(p.vanished()?"🕶 ":p.online()?"🟢 ":"👤 ")+p.name(),Set.of("ban","kick").contains(operation) ? "confirm:"+operation : "person",p.uuid().toString(),0,i-page*PAGE_SIZE));}
        String pageOp=filter.isBlank() ? "browse:"+mode : "browseSearch:"+mode;
        if(page>0) buttons.add(b(user,"◀ Назад",pageOp,filter,page-1,PAGE_SIZE));if((page+1)*PAGE_SIZE<people.size()) buttons.add(b(user,"Вперёд ▶",pageOp,filter,page+1,PAGE_SIZE));
        if(Set.of("banOnline","banAll").contains(mode)) buttons.add(b(user,"↩ Выбор списка","banMenu","",0,PAGE_SIZE+1));
        if(mode.equals("online")) buttons.add(b(user,"👤 Игроки офлайн","browse:offline","",0,PAGE_SIZE+1));
        if(mode.equals("offline")) buttons.add(b(user,"🟢 Игроки онлайн","browse:online","",0,PAGE_SIZE+1));
        buttons.add(b(user,"🔎 Поиск","searchPlayers",mode,0,PAGE_SIZE+2));buttons.add(b(user,"↩ Назад",guard.superAdmin(user)&&!adminPreview.contains(user)?"superHome":"adminHome","",0,PAGE_SIZE+3));buttons.add(homeButton(PAGE_SIZE+4));
        String title=mode.equals("banOnline") ? "Игроки онлайн" : mode.equals("banAll") ? "Все игроки" : "Игроки";
        if(!filter.isBlank()) title+=" • поиск «"+filter.trim()+"»";
        return new TelegramCommands.View(title+" • страница "+(page+1)+" • всего "+people.size(),List.copyOf(buttons));
    }
    boolean allowed(long user,String capability) {return guard.superAdmin(user)||guard.capability(capability);}
    boolean allowedPlayerAction(long user,String capability) {return !adminPreview.contains(user)&&guard.superAdmin(user)||guard.capability(capability);}
    boolean hasPlayerTools(long user) {return guard.superAdmin(user)||GuardService.ADMIN_CAPABILITIES.stream().filter(c->!Set.of("chat","manageAdmins").contains(c)).anyMatch(guard::capability);}
    void requirePlayerAccess(long user) {
        guard.requireAdmin(user);
    }
    boolean hasDirectPlayerActions(long user) {
        return guard.superAdmin(user)||List.of("ban","permanentBan","kick","unban","heal","kill","repair","scale","flySpeed","walkSpeed","teleport","op").stream().anyMatch(guard::capability);
    }
    TelegramCommands.View person(long user,UUID uuid) throws Exception {
        return playerActions(user,uuid);
    }
    TelegramCommands.View inventory(long user,UUID uuid,boolean ender,int page) throws Exception {
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
        int pages=Math.max(1,(items.size()+TEXT_PAGE_SIZE-1)/TEXT_PAGE_SIZE);page=Math.max(0,Math.min(page,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();StringBuilder text=new StringBuilder(title+" • страница "+(page+1)+"/"+pages+"\n");
        for(int i=page*TEXT_PAGE_SIZE;i<Math.min(items.size(),page*TEXT_PAGE_SIZE+TEXT_PAGE_SIZE);i++) {var item=items.get(i);text.append(item.label()).append(": ").append(item.amount()).append(" шт.\n");if(item.shulker()) buttons.add(b(user,item.label()+" · открыть","shulker",uuid+"|"+(ender?"ender":"main")+"|"+i,0,buttons.size()));}
        if(page>0) buttons.add(b(user,"◀ Назад","inventory",uuid+"|"+(ender?"ender":"main"),page-1,PAGE_SIZE));if(page+1<pages) buttons.add(b(user,"Вперёд ▶","inventory",uuid+"|"+(ender?"ender":"main"),page+1,PAGE_SIZE));
        buttons.add(b(user,"↩ Карточка игрока","person",uuid.toString(),0,PAGE_SIZE+1));buttons.add(homeButton(PAGE_SIZE+2));return new TelegramCommands.View(text.toString().stripTrailing(),List.copyOf(buttons));
    }
    TelegramCommands.View shulker(long user,UUID uuid,boolean ender,String path,int page) throws Exception {
        var items=moderation.inventory(user,uuid,ender).get();String title="📦 Содержимое шалкера";
        var container=itemAt(items,path);
        if(container==null || !container.shulker()) return inventory(user,uuid,ender,0);
        var nested=container.contents();int pages=Math.max(1,(nested.size()+TEXT_PAGE_SIZE-1)/TEXT_PAGE_SIZE);page=Math.max(0,Math.min(page,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();StringBuilder text=new StringBuilder(title+" • страница "+(page+1)+"/"+pages+"\n");
        if(nested.isEmpty()) text.append("Шалкер пуст."); else for(int i=page*TEXT_PAGE_SIZE;i<Math.min(nested.size(),page*TEXT_PAGE_SIZE+TEXT_PAGE_SIZE);i++) {var item=nested.get(i);text.append(item.label()).append(": ").append(item.amount()).append(" шт.\n");if(item.shulker()) buttons.add(b(user,item.label()+" · открыть","shulker",uuid+"|"+(ender?"ender":"main")+"|"+path+"."+i,0,buttons.size()));}
        String mode=ender?"ender":"main";if(page>0) buttons.add(b(user,"◀ Назад","shulker",uuid+"|"+mode+"|"+path,page-1,PAGE_SIZE));if(page+1<pages) buttons.add(b(user,"Вперёд ▶","shulker",uuid+"|"+mode+"|"+path,page+1,PAGE_SIZE));buttons.add(b(user,"↩ К инвентарю","inventory",uuid+"|"+mode,0,PAGE_SIZE+1));buttons.add(homeButton(PAGE_SIZE+2));return new TelegramCommands.View(text.toString().stripTrailing(),List.copyOf(buttons));
    }
    static TelegramModeration.ItemView itemAt(List<TelegramModeration.ItemView> items,String path) {
        if(path==null || path.isBlank()) return null;
        List<TelegramModeration.ItemView> current=items;TelegramModeration.ItemView result=null;
        for(String part:path.split("\\.")) {
            try {int index=Integer.parseInt(part);if(index<0 || index>=current.size()) return null;result=current.get(index);current=result.contents();}
            catch(NumberFormatException e) {return null;}
        }
        return result;
    }
    TelegramCommands.View playerActions(long user,UUID uuid) throws Exception {
        return playerActions(user,uuid,playerPages.getOrDefault(user+"|"+uuid,0));
    }
    TelegramCommands.View playerActions(long user,UUID uuid,int requestedPage) throws Exception {
        requirePlayerAccess(user);String id=uuid.toString();List<PlayerAction> available=new ArrayList<>();
        var p=moderation.info(user,uuid).get();
        if(allowedPlayerAction(user,"inventory")) available.add(new PlayerAction("🎒 Инвентарь","inventory",id+"|main"));
        if(allowedPlayerAction(user,"ender")) available.add(new PlayerAction("🧰 Эндер-сундук","inventory",id+"|ender"));
        if(allowedPlayerAction(user,"ban")) available.add(new PlayerAction("⏳ Бан 5 мин","confirm:ban",id));
        if(allowedPlayerAction(user,"kick")) available.add(new PlayerAction("🚪 Кик","confirm:kick",id));
        if(allowedPlayerAction(user,"freeze") && moderation.freezeAvailable()) available.add(new PlayerAction("❄ Заморозка","freezeMenu",id));
        available.add(new PlayerAction("💬 Сообщение","privateMessage",id));
        if(allowedPlayerAction(user,"bastionTrust") && moderation.bastionAvailable()) {
            available.add(new PlayerAction("🛡 Добавить в Bastion","confirm:bastionAdd",id));
            available.add(new PlayerAction("🛡 Убрать из Bastion","confirm:bastionRemove",id));
        }
        if(allowedPlayerAction(user,"permanentBan")) available.add(new PlayerAction("🔨 Забанить","confirm:permanentBan",id));
        if(allowedPlayerAction(user,"unban")) available.add(new PlayerAction("🔓 Разбан","confirm:unban",id));
        if(allowedPlayerAction(user,"heal")) available.add(new PlayerAction("💚 Вылечить","confirm:heal",id));
        if(allowedPlayerAction(user,"kill")) available.add(new PlayerAction("☠️ Убить","confirm:kill",id));
        if(allowedPlayerAction(user,"repair")) available.add(new PlayerAction("🔧 Починить","confirm:repair",id));
        if(allowedPlayerAction(user,"scale")) available.add(new PlayerAction("📏 Размер","scaleMenu",id));
        if(allowedPlayerAction(user,"flySpeed")) available.add(new PlayerAction("✈️ Полёт","flySpeedMenu",id));
        if(allowedPlayerAction(user,"walkSpeed")) available.add(new PlayerAction("🏃 Ходьба","walkSpeedMenu",id));
        if(allowedPlayerAction(user,"teleport")) available.add(new PlayerAction("📍 К игроку","teleportPlayers",id));
        if(allowedPlayerAction(user,"teleportCoords")) available.add(new PlayerAction("📍 На координаты","teleportCoords",id));
        if(allowedPlayerAction(user,"chat")) available.add(new PlayerAction("💬 В игровой чат","chat",""));
        if(allowedPlayerAction(user,"luckPerms")) {boolean admin=Boolean.TRUE.equals(moderation.adminGroup(user,uuid).get());available.add(new PlayerAction((admin?"❌ Забрать":"✅ Выдать")+" LP admin","confirm:toggleLp",id));}
        if(allowedPlayerAction(user,"op")) available.add(new PlayerAction("⭐ Выдать OP","confirm:op",id));
        if(allowedPlayerAction(user,"deop")) available.add(new PlayerAction("☆ Забрать OP","confirm:deop",id));
        available.add(new PlayerAction("📋 Копировать UUID","copyUuid",id));
        int page=Math.max(0,Math.min(requestedPage,Math.max(0,(available.size()-1)/9)));
        playerPages.put(user+"|"+uuid,page);
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*9;i<Math.min(available.size(),page*9+9);i++) {
            var action=available.get(i);int row=(i-page*9)/3;
            buttons.add(action.op().equals("copyUuid")?TelegramCommands.Button.copy(action.label(),id,row):b(user,action.label(),action.op(),action.value(),page,row));
        }
        if(page>0) buttons.add(b(user,"◀ Назад","playerActions",id,page-1,3));
        if((page+1)*9<available.size()) buttons.add(b(user,"Вперёд ▶","playerActions",id,page+1,3));
        buttons.add(b(user,"↩ К игрокам","browse:"+(p.online()?"online":"offline"),"",0,4));buttons.add(homeButton(4));
        TelegramModeration.CabinetProfile profile=null;try {profile=moderation.playerProfile(user,uuid).get();} catch(Exception ignored) {}
        StringBuilder card=new StringBuilder("👤 ").append(p.name());
        if(profile!=null) {
            card.append("\n").append(root.hearts(profile.health(),profile.maxHealth()));
            card.append("\n\n").append(root.hunger(profile.food()));
            if(profile.world()!=null) card.append("\n📍 ").append(profile.world()).append(" · ").append(TelegramEmojiMarkup.code(root.coordinateNumbers(profile)));
            if(profile.playtimeSeconds()!=null) card.append("\n⏱ ").append(playtime(profile.playtimeSeconds()));
        }
        card.append(root.donationDetails(uuid).replace("\n\n","\n"));
        card.append("\nUUID: ").append(id.substring(0,8)).append("…")
                .append("\nОнлайн: ").append(yesNo(p.online())).append(" · OP: ").append(yesNo(p.op())).append(" · Бан: ").append(yesNo(p.banned()));
        if(p.firstPlayed()>0) card.append("\nПервый: ").append(moscowDate(p.firstPlayed()));
        if(p.lastLogin()>0) card.append("\nПоследний: ").append(moscowDate(p.lastLogin()));
        if(p.lastSeen()>0) card.append("\nВыход: ").append(moscowDate(p.lastSeen()));
        card.append("\nДействия · ").append(page+1).append('/').append(Math.max(1,(available.size()+8)/9));
        return new TelegramCommands.View(TelegramEmojiMarkup.bold(card.toString()),List.copyOf(buttons));
    }
    TelegramCommands.View freezeMenu(long user,UUID uuid) {
        guard.requireCapability(user,"freeze");
        if(!moderation.freezeAvailable()) throw new IllegalStateException("Заморозка сейчас недоступна.");
        String id=uuid.toString();int page=playerPages.getOrDefault(user+"|"+uuid,0);
        return new TelegramCommands.View("❄ Заморозка игрока",List.of(b(user,"❄ Заморозить","confirm:freeze",id,page,0),
                b(user,"☀ Разморозить","confirm:unfreeze",id,page,0),b(user,"↩ Действия","playerActions",id,page,1)));
    }
    static String yesNo(boolean value) {return value?"да":"нет";}
    static String moscowDate(long millis) {return millis<=0?"нет данных":java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of("Europe/Moscow")).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));}
    static String playtime(long seconds) {long safe=Math.max(0,seconds),days=safe/86400,hours=safe%86400/3600,minutes=safe%3600/60;return (days>0?days+" дн. ":"")+(hours>0?hours+" ч. ":"")+(minutes>0?minutes+" мин.":days==0&&hours==0?"меньше минуты":"");}
    TelegramCommands.View teleportPlayers(long user,String source,int page,String filter) throws Exception {
        guard.requireCapability(user,"teleport");var people=moderation.players(user,"online").get().stream().filter(p->!p.uuid().toString().equals(source)).toList();
        if(!filter.isBlank()) people=people.stream().filter(p->root.searchKey(p.name()).contains(root.searchKey(filter))).toList();
        page=Math.max(0,Math.min(page,Math.max(0,(people.size()-1)/PAGE_SIZE)));
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*PAGE_SIZE;i<Math.min(people.size(),page*PAGE_SIZE+PAGE_SIZE);i++) {var p=people.get(i);buttons.add(b(user,p.name(),"confirm:teleportPlayer",source+"|"+p.uuid(),0,i-page*PAGE_SIZE));}
        if(page>0) buttons.add(b(user,"◀ Назад","teleportPlayers",source+"|"+filter,page-1,PAGE_SIZE));
        if((page+1)*PAGE_SIZE<people.size()) buttons.add(b(user,"Вперёд ▶","teleportPlayers",source+"|"+filter,page+1,PAGE_SIZE));
        buttons.add(b(user,"🔎 Поиск","teleportSearch",source,0,PAGE_SIZE+1));buttons.add(b(user,"↩ Действия игрока","playerActions",source,playerPages.getOrDefault(user+"|"+source,0),PAGE_SIZE+2));buttons.add(homeButton(PAGE_SIZE+3));
        return new TelegramCommands.View("К какому игроку телепортировать?"+(people.isEmpty()?"\nДругих игроков онлайн нет.":""),List.copyOf(buttons));
    }
    TelegramCommands.View scaleMenu(long user,UUID uuid) {
        guard.requireCapability(user,"scale");String id=uuid.toString();double[] values={0.1,0.5,0.65,0.75,0.85,1,1.5,2,5,10,15,20};List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=0;i<values.length;i++) buttons.add(b(user,"📏 "+values[i],"confirm:scale",id+"|"+values[i],0,i/2));
        buttons.add(b(user,"✏️ Своё значение","customNumber","scale|"+id,0,6));
        buttons.add(b(user,"↩ Действия игрока","playerActions",id,playerPages.getOrDefault(user+"|"+id,0),7));buttons.add(homeButton(7));
        return new TelegramCommands.View("📏 Установить размер персонажа",List.copyOf(buttons));
    }
    TelegramCommands.View speedMenu(long user,UUID uuid,String kind) {
        guard.requireCapability(user,kind.equals("fly")?"flySpeed":"walkSpeed");String id=uuid.toString();double[] values={0.1,0.5,0.65,0.75,0.85,1,1.5,2,5,10,15,20};
        String title=kind.equals("fly") ? "✈️ Скорость полёта" : "🏃 Скорость передвижения";
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=0;i<values.length;i++) {double value=values[i];String label=title+" "+value+(value==1.0 ? " (по умолчанию)" : "");buttons.add(b(user,label,"confirm:"+(kind.equals("fly")?"flySpeed":"walkSpeed"),id+"|"+value,0,i/2));}
        int row=(values.length+1)/2;
        buttons.add(b(user,"✏️ Своё значение","customNumber",(kind.equals("fly")?"flySpeed":"walkSpeed")+"|"+id,0,row));
        buttons.add(b(user,"↩ Действия игрока","playerActions",id,playerPages.getOrDefault(user+"|"+id,0),row+1));buttons.add(homeButton(row+1));
        return new TelegramCommands.View(title+"\nКоэффициент относительно стандартной скорости. 1.0 — значение по умолчанию."+ (kind.equals("fly") ? "\nPaper ограничивает полёт значением 10x." : ""),List.copyOf(buttons));
    }
    TelegramCommands.View messagePreview(long user,String id,String text,TelegramModeration.MessageSender sender) throws Exception {
        guard.requireAdmin(user);
        String name;
        if(sender==TelegramModeration.MessageSender.FLEXITY) {guard.requireSuper(user);name="FLEXITY";}
        else {var account=guard.account(user).get();if(account==null) return new TelegramCommands.View("Сначала привяжите игровой аккаунт, чтобы писать от своего ника.",List.of(homeButton(0)));name=account.name();}
        return new TelegramCommands.View("Отправить от имени «"+name+"»?\n\n"+text,List.of(
                b(user,"✅ Отправить","do:privateMessage",id+"|"+sender.name()+"|"+text,0,0),
                b(user,"↩ Действия","playerActions",id,playerPages.getOrDefault(user+"|"+id,0),1),homeButton(1)));
    }
    TelegramCommands.View confirm(long user,String operation,String value) throws Exception {
        root.routes.cancelOperations(user);
        requireOperation(user,operation);
        if(guard.superAdmin(user) && Set.of("bastionAdd","bastionRemove","freeze","unfreeze","ban","permanentBan","kick","unban","toggleLp","heal","kill","repair","scale","flySpeed","walkSpeed","teleportPlayer","teleportCoords","op","deop").contains(operation)) {
            var account=guard.account(user).get();
            if(account!=null && value.split("\\|",2)[0].equals(account.uuid().toString())) return perform(user,operation,value);
        }
        if(Set.of("addSuper","removeSuper").contains(operation)) return new TelegramCommands.View((operation.equals("addSuper")?"Выдать права суперадминистратора":"Удалить суперадминистратора")+"\nTelegram ID: "+value,List.of(b(user,"✅ Подтвердить","do:"+operation,value,0,0),b(user,"↩ Назад","supers","",0,1),homeButton(2)));
        if(operation.equals("teleportPlayer") || operation.equals("teleportCoords")) {
            String[] parts=value.split("\\|",2);String source=moderation.info(user,UUID.fromString(parts[0])).get().name();
            String destination=operation.equals("teleportPlayer")?moderation.info(user,UUID.fromString(parts[1])).get().name():parts[1]+" (текущий мир игрока)";
            return new TelegramCommands.View("Телепортировать «"+source+"» → "+destination+"?",List.of(b(user,"✅ Подтвердить","do:"+operation,value,0,0),b(user,"↩ Действия игрока","playerActions",parts[0],playerPages.getOrDefault(user+"|"+parts[0],0),1),homeButton(2)));
        }
        String target=value;
        String lookup=(operation.equals("scale") || operation.equals("flySpeed") || operation.equals("walkSpeed")) ? value.substring(0,value.indexOf('|')) : value;
        if(Set.of("bastionAdd","bastionRemove","freeze","unfreeze","ban","permanentBan","kick","unban","toggleLp","heal","kill","repair","scale","flySpeed","walkSpeed","addLp","removeLp","op","deop").contains(operation)) target=moderation.info(user,UUID.fromString(lookup)).get().name()+"\nUUID: "+lookup;
        String label=switch(operation) {case "bastionAdd" -> "Добавить игрока в доверенные Bastion";case "bastionRemove" -> "Убрать игрока из доверенных Bastion";case "freeze" -> "Заморозить игрока через Flexity";case "unfreeze" -> "Разморозить игрока через Flexity";case "ban" -> "Бан на 5 минут: пока идёт расследование";case "permanentBan" -> "Забанить бессрочно";case "kick" -> "Кик: пока идёт расследование";case "unban" -> "Снять бан";case "toggleLp" -> "Изменить группу LuckPerms admin";case "addLp" -> "Выдать группу LuckPerms admin";case "removeLp" -> "Удалить группу LuckPerms admin";case "heal" -> "Вылечить игрока";case "kill" -> "Убить игрока";case "repair" -> "Починить предметы";case "scale" -> "Установить размер "+value.substring(value.indexOf('|')+1);case "flySpeed" -> "Установить скорость полёта "+value.substring(value.indexOf('|')+1)+"x";case "walkSpeed" -> "Установить скорость передвижения "+value.substring(value.indexOf('|')+1)+"x";case "op" -> "Выдать OP";case "deop" -> "Забрать OP";case "addAdmin" -> "Добавить Telegram-администратора";case "removeAdmin" -> "Убрать Telegram-администратора";case "chat" -> "Отправить в игровой чат";default -> throw new IllegalArgumentException();};
        TelegramCommands.Button back=Set.of("addAdmin","removeAdmin").contains(operation)?b(user,"↩ Назад","admins","",0,1):operation.equals("chat")?b(user,"↩ Назад",guard.superAdmin(user)?"superHome":"adminHome","",0,1):b(user,"↩ Назад","playerActions",lookup,playerPages.getOrDefault(user+"|"+lookup,0),1);
        return new TelegramCommands.View(label+"\n\n"+target+"\n\nПодтвердите действие.",List.of(b(user,"✅ Подтвердить","do:"+operation,value,0,0),back,homeButton(2)));
    }
    TelegramCommands.View perform(long user,String operation,String value) throws Exception {
        String result;
        requireOperation(user,operation);
        if(operation.equals("addSuper") || operation.equals("removeSuper")) {guard.changeSuper(user,Long.parseLong(value),operation.equals("addSuper")).get();return root.admins(user,0,"",true);}
        else if(operation.equals("addAdmin") || operation.equals("removeAdmin")) {guard.changeAdmin(user,Long.parseLong(value),operation.equals("addAdmin")).get();result="Список администраторов обновлён.";}
        else if(operation.equals("chat")) result=moderation.broadcast(user,value).get();
        else if(operation.equals("privateMessage")) {String[] parts=value.split("\\|",3);result=moderation.privateMessage(user,UUID.fromString(parts[0]),parts[2],TelegramModeration.MessageSender.valueOf(parts[1])).get();}
        else if(operation.equals("teleportPlayer") || operation.equals("teleportCoords")) {String[] parts=value.split("\\|",2);result=moderation.teleport(user,UUID.fromString(parts[0]),operation.equals("teleportPlayer")?UUID.fromString(parts[1]):null,operation.equals("teleportCoords")?parts[1]:null).get();}
        else if(operation.equals("scale")) {int split=value.indexOf('|');result=moderation.scale(user,UUID.fromString(value.substring(0,split)),Double.parseDouble(value.substring(split+1))).get();}
        else if(operation.equals("flySpeed") || operation.equals("walkSpeed")) {int split=value.indexOf('|');result=moderation.speed(user,UUID.fromString(value.substring(0,split)),operation.equals("flySpeed") ? "fly" : "walk",Double.parseDouble(value.substring(split+1))).get();}
        else result=moderation.act(user,operation,UUID.fromString(value)).get();
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(operation.startsWith("teleport")) {String id=value.split("\\|",2)[0];buttons.add(b(user,"↩ Назад к действиям игрока","playerActions",id,playerPages.getOrDefault(user+"|"+id,0),0));}
        if(Set.of("bastionAdd","bastionRemove","freeze","unfreeze","ban","permanentBan","kick","unban","toggleLp","addLp","removeLp","heal","kill","repair","scale","flySpeed","walkSpeed","op","deop").contains(operation)) {
            String raw=operation.equals("scale") || operation.equals("flySpeed") || operation.equals("walkSpeed") ? value.substring(0,value.indexOf('|')) : value;
            buttons.add(b(user,"↩ Назад к действиям игрока","playerActions",raw,playerPages.getOrDefault(user+"|"+raw,0),0));
        } else if(operation.equals("privateMessage")) {
            String id=value.split("\\|",2)[0];buttons.add(b(user,"↩ Назад к действиям игрока","playerActions",id,playerPages.getOrDefault(user+"|"+id,0),0));
        } else if(Set.of("addAdmin","removeAdmin").contains(operation)) buttons.add(b(user,"↩ Назад к администраторам","admins","",0,0));
        else if(operation.equals("chat")) buttons.add(b(user,"↩ Назад",guard.superAdmin(user)?"superHome":"adminHome","",0,0));
        buttons.add(homeButton(1));
        return new TelegramCommands.View(result,List.copyOf(buttons));
    }
    void requireOperation(long user,String operation) {
        if(operation.equals("privateMessage")) {guard.requireAdmin(user);return;}
        if(Set.of("addSuper","removeSuper").contains(operation)) {guard.requireRoot(user);return;}
        if(Set.of("addAdmin","removeAdmin").contains(operation)) {guard.requireSuper(user);return;}
        String capability=switch(operation) {
            case "bastionAdd","bastionRemove" -> "bastionTrust";
            case "freeze","unfreeze" -> "freeze";
            case "ban" -> "ban";case "permanentBan" -> "permanentBan";case "kick" -> "kick";case "unban" -> "unban";
            case "toggleLp","addLp","removeLp" -> "luckPerms";
            case "heal" -> "heal";case "kill" -> "kill";case "repair" -> "repair";
            case "scale" -> "scale";case "flySpeed" -> "flySpeed";case "walkSpeed" -> "walkSpeed";
            case "teleportPlayer" -> "teleport";case "teleportCoords" -> "teleportCoords";case "op" -> "op";case "deop" -> "deop";
            case "chat" -> "chat";case "addAdmin","removeAdmin" -> "manageAdmins";
            default -> throw new IllegalArgumentException("Неизвестное действие");
        };
        guard.requireCapability(user,capability);
    }

    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("AdminMenu");
        scope.prefix("browse:",TelegramActionRegistry.Arguments.PAGE,false,(user,a,data)->players(user,a.op().substring(7),a.page()));
        scope.prefix("browseSearch:",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,false,(user,a,data)->players(user,a.op().substring(13),a.page(),a.value()));
        scope.prefix("confirm:",TelegramActionRegistry.Arguments.VALUE,false,(user,a,data)->confirm(user,a.op().substring(8),a.value()));
        scope.prefix("do:",TelegramActionRegistry.Arguments.VALUE,true,(user,a,data)->perform(user,a.op().substring(3),a.value()));
        scope.on("person",TelegramActionRegistry.Arguments.VALUE,(user,a,data)->playerActions(user,UUID.fromString(a.value()),0));
        scope.on("playerActions",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,(user,a,data)->playerActions(user,UUID.fromString(a.value()),a.page()));
        scope.on("scaleMenu",TelegramActionRegistry.Arguments.VALUE,(user,a,data)->scaleMenu(user,UUID.fromString(a.value())));
        scope.on("flySpeedMenu",TelegramActionRegistry.Arguments.VALUE,(user,a,data)->speedMenu(user,UUID.fromString(a.value()),"fly"));
        scope.on("walkSpeedMenu",TelegramActionRegistry.Arguments.VALUE,(user,a,data)->speedMenu(user,UUID.fromString(a.value()),"walk"));
        scope.on("inventory",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,(user,a,data)-> {
            String[] parts=a.value().split("\\|",2);return inventory(user,UUID.fromString(parts[0]),parts[1].equals("ender"),a.page());
        });
        scope.on("shulker",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,(user,a,data)-> {
            String[] parts=a.value().split("\\|",3);return shulker(user,UUID.fromString(parts[0]),parts[1].equals("ender"),parts[2],a.page());
        });
        scope.on("ignoreNotification",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ADMIN,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireAdmin(user);root.routes.consume(data);view=new TelegramCommands.View(a.value()+"\n\n✓ Уведомление проигнорировано.");}
            return view;
        });
        scope.on("privateMessage",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ADMIN,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireAdmin(user);view=prompt(user,"privateMessage:"+a.value(),"Напишите игроку личное сообщение: одна строка, до 500 символов.");}
            return view;
        });
        scope.on("messageSender",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.SUPER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireSuper(user);String[] parts=a.value().split("\\|",3);view=messagePreview(user,parts[0],parts[2],TelegramModeration.MessageSender.valueOf(parts[1]));}
            return view;
        });
        scope.on("freezeMenu",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=freezeMenu(user,UUID.fromString(a.value()));
            return view;
        });
        scope.on("adminHome",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ADMIN,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=adminHome(user);
            return view;
        });
        scope.on("admin",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,true, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.toggleAdmin(user).get();view=adminHome(user);}
            return view;
        });
        scope.on("banMenu",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=banMenu(user);
            return view;
        });
        scope.on("history",TelegramActionRegistry.Arguments.PAGE,TelegramActionRegistry.Requirement.ADMIN,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=root.history(user,a.page(),false);
            return view;
        });
        scope.on("historySearch",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ADMIN,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireAdmin(user);view=prompt(user,"historySearch","Отправьте часть ника владельца/участника, название предмета, координаты или номер события.");}
            return view;
        });
        scope.on("historyFiltered",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.ADMIN,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=root.history(user,a.page(),false,a.value());
            return view;
        });
        scope.on("customNumber",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireCapability(user,a.value().split("\\|",2)[0]);view=prompt(user,"number:"+a.value(),"Отправьте своё значение от 0.1 до 20. Например: 1.25. 1 — стандартное значение.");}
            return view;
        });
        scope.on("teleportCoords",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireCapability(user,"teleportCoords");view=prompt(user,"coords:"+a.value(),"Отправьте x y z, например: 223 200 1004. Используется текущий мир перемещаемого игрока.");}
            return view;
        });
        scope.on("teleportPlayers",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {String[] parts=a.value().split("\\|",2);view=teleportPlayers(user,parts[0],a.page(),parts.length>1?parts[1]:"");}
            return view;
        });
        scope.on("teleportSearch",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireCapability(user,"teleport");view=prompt(user,"teleportSearch:"+a.value(),"Отправьте часть ника игрока назначения.");}
            return view;
        });
        scope.on("event",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.ADMIN,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=root.detail(user,Long.parseLong(a.value()),a.page(),false);
            return view;
        });
        scope.on("players",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=players(user,"online",0);
            return view;
        });
        scope.on("searchPlayers",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ADMIN,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.requireAdmin(user);view=prompt(user,"playerSearch:"+a.value(),"Отправьте часть ника игрока для поиска.");}
            return view;
        });
    }
    TelegramCommands.View onInputEarly(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().startsWith("privateMessage:")) {
            guard.requireAdmin(user);
            if(pending.expires()<System.currentTimeMillis()) {waiting.remove(user);return new TelegramCommands.View("Время ввода истекло. Нажмите «Написать игроку» ещё раз.");}
            if(text.isBlank() || text.length()>500 || text.contains("\n") || text.contains("\r")) return new TelegramCommands.View("Введите одну строку до 500 символов. Отмена: /cancel.");
            String id=pending.kind().substring("privateMessage:".length());waiting.remove(user);
            if(guard.superAdmin(user)) return new TelegramCommands.View("От чьего имени отправить сообщение?\n\n"+text,List.of(
                    root.b(user,"FLEXITY","messageSender",id+"|FLEXITY|"+text,0,0),
                    root.b(user,"Мой игровой ник","messageSender",id+"|PLAYER|"+text,0,0),homeButton(1)));
            return messagePreview(user,id,text,TelegramModeration.MessageSender.PLAYER);
        }
        return null;
    }

    TelegramCommands.View onInput(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().startsWith("teleportSearch:")) {guard.requireCapability(user,"teleport");String query=text.trim();if(query.isEmpty()||query.length()>64) return withHome(new TelegramCommands.View("Введите от 1 до 64 символов."));waiting.remove(user);return teleportPlayers(user,pending.kind().substring(15),0,query);}
        if(pending.kind().equals("historySearch")) {
            guard.requireAdmin(user);String query=text.trim();
            if(query.isEmpty() || query.length()>100) return new TelegramCommands.View("Введите запрос от 1 до 100 символов. Отмена: /cancel.");
            waiting.remove(user);return root.history(user,0,false,query);
        }
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
            if(!Set.of("online","all","offline","banned","kick","banOnline","banAll").contains(mode)) {waiting.remove(user);return root.home(user);}
            waiting.remove(user);return players(user,mode,0,query);
        }
        if(pending.kind().equals("chat")) {
            guard.requireCapability(user,"chat");if(text.isBlank() || text.length()>500 || text.contains("\n") || text.contains("\r")) return withHome(new TelegramCommands.View("Нужна одна строка до 500 символов."));
            waiting.remove(user);return confirm(user,"chat",text);
        }
        return null;
    }
    TelegramCommands.View adminIdInput(long user,TelegramGuardMenu.Input pending,long value) throws Exception {
        guard.requireSuper(user);
        if(value==0) return new TelegramCommands.View("Telegram ID должен быть больше 0.");
        waiting.remove(user);
        return confirm(user,"addAdmin",Long.toString(value));
    }
}
