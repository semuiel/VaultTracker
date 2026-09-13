package ru.vaulttracker;

import org.bukkit.Material;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

final class TelegramCommands {
    record Button(String text,String data,int row) {
        Button(String text,String data) { this(text,data,0); }
    }
    record View(String text,List<Button> buttons) {
        View(String text) { this(text,List.of()); }
    }
    record Callback(View view,String notice,boolean alert) {}
    private enum Kind { PLAYER, OWNER, TOP, ITEMS }
    private record Session(long userId,Kind kind,String argument,long createdAt) {}

    private static final long SESSION_TTL_MS=30*60*1000L;
    private final Catalogue catalogue;
    private final StorageEngine storage;
    private final int pageSize;
    private final Predicate<String> validItem;
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    private java.util.function.LongPredicate adminAccess=id->true;
    void adminAccess(java.util.function.LongPredicate predicate) {adminAccess=predicate;}

    TelegramCommands(Catalogue catalogue,StorageEngine storage,int pageSize) {
        this(catalogue,storage,pageSize,id -> {
            Material material=Material.getMaterial(id);
            return material!=null && material.isItem();
        });
    }
    TelegramCommands(Catalogue catalogue,StorageEngine storage,int pageSize,Predicate<String> validItem) {
        this.catalogue=catalogue; this.storage=storage; this.pageSize=pageSize; this.validItem=validItem;
    }

    View handle(long userId,long chatId,int topicId,String input,boolean admin) {
        String text=input==null ? "" : input.trim();
        String command=command(text);
        if(text.isEmpty() || command.equals("/start") || command.equals("/help")) return new View(help(admin));
        if(command.equals("/status")) return admin
                ? new View(storage.status()+"; хранилищ: "+catalogue.size()) : adminOnly();
        if(command.equals("/items")) {
            if(!admin) return adminOnly();
            if(!storage.ready()) return loading();
            return begin(userId,Kind.ITEMS,"",1);
        }
        if(!command.equals("/topitem") && !command.equals("/item") && !command.equals("/itemtop")) return new View("Доступные команды: /item, /topitem и /itemtop\nПример: /item алмаз");
        if(!storage.ready()) return loading();
        return topitem(userId,remainder(text));
    }

    Callback callback(long userId,String data) {
        if(data==null || !data.startsWith("vt:")) return new Callback(null,"Неизвестная кнопка.",true);
        String[] parts=data.split(":",3);
        if(parts.length!=3) return new Callback(null,"Неизвестная кнопка.",true);
        Session session=sessions.get(parts[1]);
        if(session==null || System.currentTimeMillis()-session.createdAt()>SESSION_TTL_MS) {
            sessions.remove(parts[1]); return new Callback(null,"Кнопки устарели. Выполните /topitem ещё раз.",true);
        }
        if(session.userId()!=userId) return new Callback(null,"Эти кнопки принадлежат автору запроса.",true);
        if(session.kind()==Kind.ITEMS && !adminAccess.test(userId)) return new Callback(null,"Нет прав администратора.",true);
        int page;
        try { page=Integer.parseInt(parts[2]); }
        catch(NumberFormatException e) { return new Callback(null,"Некорректная страница.",true); }
        return new Callback(render(parts[1],session,page),"",false);
    }

    private View topitem(long userId,String query) {
        if(query.isBlank()) return new View(topHelp());
        boolean explicit=query.regionMatches(true,0,"player ",0,7);
        if(explicit) query=query.substring(7).trim();
        ResourceGroups.Group group=!explicit ? ResourceGroups.resolve(query) : null;
        if(group!=null) return begin(userId,Kind.TOP,ResourceGroups.query(group),1);
        String material=!explicit ? resolveItem(query) : null;
        if(material!=null) return begin(userId,Kind.TOP,material,1);
        String[] parts=query.split("\\s+",2);
        String first=parts[0]; String tail=parts.length==2 ? parts[1].trim() : "";
        if(!first.matches("[A-Za-z0-9_.-]{1,32}")) return new View("Некорректный ник или название предмета.");
        List<Catalogue.OwnerItems> owners=catalogue.ownerItems(first);
        if(owners.isEmpty()) return new View("Игрок «"+first+"» не найден в каталоге зарегистрированных хранилищ.");
        if(owners.size()>1) return new View("В каталоге несколько UUID с этим ником. Администратору сервера нужно проверить записи владельцев.");
        if(!tail.isEmpty() && !tail.matches("[+-]?[0-9]+")) {
            group=ResourceGroups.resolve(tail);
            if(group!=null) {
                long amount=group.materials().stream().mapToLong(item->owners.getFirst().items().getOrDefault(item,0L)).sum();
                return new View(owners.getFirst().name()+" — "+TelegramItemIcons.groupLabel(group)+": "+ItemAmount.format(group.displayMaterial(),amount));
            }
            material=resolveItem(tail);
            if(material==null) return new View("Неизвестный предмет. Пример: /item "+first+" алмаз");
            var owner=owners.getFirst();
            return new View(owner.name()+" — "+TelegramItemIcons.label(material)+": "+ItemAmount.format(material,owner.items().getOrDefault(material,0L)));
        }
        int page=1;
        if(!tail.isEmpty()) try { page=Integer.parseInt(tail); }
        catch(NumberFormatException e) { return new View("Номер страницы слишком большой."); }
        return begin(userId,Kind.PLAYER,first,page);
    }

    private String resolveItem(String query) {
        return RussianItems.candidates(query).stream().filter(validItem).findFirst().orElse(null);
    }

    private View begin(long userId,Kind kind,String argument,int page) {
        long now=System.currentTimeMillis();
        sessions.entrySet().removeIf(entry->now-entry.getValue().createdAt()>SESSION_TTL_MS);
        String token=UUID.randomUUID().toString().replace("-","").substring(0,16);
        Session session=new Session(userId,kind,argument,now); sessions.put(token,session);
        return render(token,session,page);
    }

    View ownResources(long userId,UUID owner) {
        if(!storage.ready()) return loading();
        return begin(userId,Kind.OWNER,owner.toString(),1);
    }
    private View render(String token,Session session,int requested) {
        return switch(session.kind()) {
            case PLAYER -> playerPage(token,session.argument(),requested);
            case OWNER -> ownerPage(token,catalogue.ownerItems(UUID.fromString(session.argument())),requested);
            case TOP -> topPage(token,session.argument(),requested);
            case ITEMS -> allItemsPage(token,requested);
        };
    }

    private View playerPage(String token,String nickname,int requested) {
        List<Catalogue.OwnerItems> owners=catalogue.ownerItems(nickname);
        if(owners.size()!=1) return new View("Данные игрока изменились. Выполните /topitem "+nickname+" ещё раз.");
        var owner=owners.getFirst();
        return ownerPage(token,owner,requested);
    }
    private View ownerPage(String token,Catalogue.OwnerItems owner,int requested) {
        var rows=owner.items().entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).toList();
        int pages=pages(rows.size()); int page=clamp(requested,pages);
        StringBuilder text=new StringBuilder("📦 Ресурсы ").append(owner.name()).append(" • ").append(page).append('/').append(pages).append("\n\n");
        if(rows.isEmpty()) text.append("В зарегистрированных хранилищах пока нет предметов.");
        int first=(page-1)*pageSize;
        for(int i=first;i<Math.min(first+pageSize,rows.size());i++) {
            var row=rows.get(i);
            text.append(i+1).append(". ").append(TelegramItemIcons.label(row.getKey())).append(" — ")
                    .append(ItemAmount.format(row.getKey(),row.getValue())).append('\n');
        }
        return new View(text.toString().stripTrailing(),buttons(token,page,pages));
    }

    private View topPage(String token,String material,int requested) {
        ResourceGroups.Group group=ResourceGroups.fromQuery(material);
        var rows=group==null ? catalogue.findTotals(material,Integer.MAX_VALUE) : catalogue.findTotals(group.materials(),Integer.MAX_VALUE);
        if(rows.isEmpty()) return new View((group==null ? TelegramItemIcons.label(material) : TelegramItemIcons.groupLabel(group))+" — не найдено в зарегистрированных хранилищах.");
        int pages=pages(rows.size()); int page=clamp(requested,pages);
        String title=group==null ? TelegramItemIcons.label(material) : TelegramItemIcons.groupLabel(group);
        String displayMaterial=group==null ? material : group.displayMaterial();
        StringBuilder text=new StringBuilder("🏆 ").append(title).append(" • топ владельцев • ")
                .append(page).append('/').append(pages).append("\n\n");
        int first=(page-1)*pageSize;
        for(int i=first;i<Math.min(first+pageSize,rows.size());i++) {
            var row=rows.get(i);
            text.append(i+1).append(". ").append(row.name()).append(" — ")
                    .append(ItemAmount.format(displayMaterial,row.amount())).append('\n');
        }
        if(group==null && !catalogue.ownerItems(material).isEmpty()) text.append("\nИгрок с таким ником: /topitem player ").append(material);
        return new View(text.toString().stripTrailing(),buttons(token,page,pages));
    }

    private View allItemsPage(String token,int requested) {
        Map<String,Long> totals=new HashMap<>();
        for(Snapshot snapshot:catalogue.all()) snapshot.items().forEach((item,amount)->totals.merge(item,amount,Long::sum));
        var rows=totals.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).toList();
        if(rows.isEmpty()) return new View("В зарегистрированных хранилищах пока нет ресурсов.");
        int pages=pages(rows.size()); int page=clamp(requested,pages);
        StringBuilder text=new StringBuilder("🗃 Ресурсы каталога • ").append(page).append('/').append(pages).append("\n\n");
        int first=(page-1)*pageSize;
        for(int i=first;i<Math.min(first+pageSize,rows.size());i++) {
            var row=rows.get(i);
            text.append(i+1).append(". ").append(TelegramItemIcons.label(row.getKey())).append(" — ")
                    .append(ItemAmount.format(row.getKey(),row.getValue())).append('\n');
        }
        return new View(text.toString().stripTrailing(),buttons(token,page,pages));
    }

    private int pages(int rows) { return Math.max(1,(rows+pageSize-1)/pageSize); }
    private static int clamp(int page,int pages) { return Math.max(1,Math.min(page,pages)); }
    private static List<Button> buttons(String token,int page,int pages) {
        if(pages<=1) return List.of();
        List<Button> result=new ArrayList<>(2);
        if(page>1) result.add(new Button("◀ Назад","vt:"+token+":"+(page-1)));
        if(page<pages) result.add(new Button("Вперёд ▶","vt:"+token+":"+(page+1)));
        return List.copyOf(result);
    }
    private static String remainder(String text) {
        String[] words=text.split("\\s+",2);
        return words.length<2 ? "" : words[1].trim();
    }
    static String command(String text) {
        if(text==null || text.isBlank()) return "";
        String first=text.split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        int mention=first.indexOf('@'); return mention<0 ? first : first.substring(0,mention);
    }
    private static View loading() { return new View("Каталог загружается. Повторите поиск чуть позже."); }
    private static View adminOnly() { return new View("Команда доступна только администратору Telegram-бота."); }
    private static String topHelp() {
        return "/item предмет — топ игроков\n/item Ник — все ресурсы игрока\n/item Ник предмет — количество предмета\n\nМожно использовать русские названия и команду /topitem.";
    }
    private static String help(boolean admin) {
        String text="Каталог ресурсов VaultTracker.\n\n"+topHelp();
        return admin ? text+"\n\nАдминистратор: /items, /status\n/id — вывести данные настройки в консоль сервера." : text;
    }
}
