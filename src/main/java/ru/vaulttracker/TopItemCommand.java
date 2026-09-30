package ru.vaulttracker;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;
import java.util.function.*;

final class TopItemCommand {
    static final int PAGE_SIZE = 8;
    private final Catalogue catalogue;
    private final BooleanSupplier ready;
    private final Predicate<String> validItem;
    private final Consumer<String> searchRecorder;
    TopItemCommand(Catalogue catalogue, BooleanSupplier ready) {
        this(catalogue,ready,id -> { Material type = Material.getMaterial(id); return type != null && type.isItem(); });
    }
    TopItemCommand(Catalogue catalogue, BooleanSupplier ready, Predicate<String> validItem) {
        this(catalogue,ready,validItem,item->{});
    }
    TopItemCommand(Catalogue catalogue, BooleanSupplier ready, Predicate<String> validItem, Consumer<String> searchRecorder) {
        this.catalogue=catalogue; this.ready=ready; this.validItem=validItem; this.searchRecorder=searchRecorder;
    }
    private static void say(CommandSender sender,String text) {GameMessages.send(sender,text,GameMessages.Tone.INFO);}
    private static void warn(CommandSender sender,String text) {GameMessages.send(sender,text,GameMessages.Tone.WARNING);}
    private static void error(CommandSender sender,String text) {GameMessages.send(sender,text,GameMessages.Tone.ERROR);}
    boolean execute(CommandSender sender,String[] args) {
        if (!sender.hasPermission("vaulttracker.search")) { error(sender,"Нет права vaulttracker.search."); return true; }
        if (!ready.getAsBoolean()) { warn(sender,"Каталог загружается. Повторите поиск чуть позже."); return true; }
        boolean explicit = args.length > 1 && args[0].equalsIgnoreCase("player");
        if (explicit) args=Arrays.copyOfRange(args,1,args.length);
        if (args.length < 1) { help(sender); return true; }
        if(!explicit && args[0].equalsIgnoreCase("all")) {
            var rows=catalogue.allItemTotals();int pages=Math.max(1,(rows.size()+19)/20),page=1;
            try {if(args.length==2) page=Integer.parseInt(args[1]);} catch(NumberFormatException bad) {page=0;}
            if(page<1||page>pages) {warn(sender,"Укажите страницу от 1 до "+pages+".");return true;}
            say(sender,"Топ по всем предметам · "+page+"/"+pages+" · в шалкерах");
            if(rows.isEmpty()) warn(sender,"В каталоге пока нет предметов.");
            for(int i=(page-1)*20;i<Math.min(page*20,rows.size());i++) {var row=rows.get(i);say(sender,(i+1)+". "+row.name()+" — "+ItemAmount.totalShulkers(row.amount()));}
            GameMessages.send(sender,button("◀ Назад",page>1?"/topitem all "+(page-1):null).append(Component.text("  "+page+"/"+pages+"  ")).append(button("Вперёд ▶",page<pages?"/topitem all "+(page+1):null)),GameMessages.Tone.INFO);
            return true;
        }
        String query=String.join(" ",args);
        ResourceGroups.Group group=ResourceGroups.resolve(query);
        if (!explicit && group!=null) {
            searchRecorder.accept(group.code());
            say(sender,group.title()+" — топ владельцев, последние известные остатки:");
            var found=catalogue.findTotals(group.materials(),20);
            if (found.isEmpty()) warn(sender,"Не найдено."); else for(var row:found) ownerLine(sender,row.name(),ItemAmount.format(group.displayMaterial(),row.amount()));
            return true;
        }
        String material=RussianItems.candidates(query).stream().filter(validItem).findFirst().orElse(null);
        if (!explicit && material!=null) {
            searchRecorder.accept(material);
            say(sender,RussianItems.name(material)+" — топ владельцев, последние известные остатки:");
            var found=catalogue.findTotals(material,20);
            if (found.isEmpty()) warn(sender,"Не найдено."); else for(var row:found) ownerLine(sender,row.name(),ItemAmount.format(material,row.amount()));
            if (!catalogue.ownerItems(args[0]).isEmpty()) say(sender,"Для просмотра игрока с таким ником: /topitem player "+args[0]);
            return true;
        }
        if (!args[0].matches("[A-Za-z0-9_.-]{1,32}")) { warn(sender,"Некорректный ник или название предмета."); return true; }
        List<Catalogue.OwnerItems> matches=catalogue.ownerItems(args[0]);
        if (matches.isEmpty()) { warn(sender,"Игрок «"+args[0]+"» не найден в каталоге зарегистрированных хранилищ."); return true; }
        if (matches.size()>1) { warn(sender,"В каталоге несколько UUID с этим ником. Администратору нужно проверить записи владельцев."); return true; }
        var owner=matches.getFirst();
        String itemQuery=args.length<2?"":String.join(" ",Arrays.copyOfRange(args,1,args.length));
        if (!itemQuery.isEmpty() && !itemQuery.matches("[+-]?[0-9]+")) {
            group=ResourceGroups.resolve(itemQuery);
            if(group!=null) {
                searchRecorder.accept(group.code());
                long amount=group.materials().stream().mapToLong(item->owner.items().getOrDefault(item,0L)).sum();
                say(sender,owner.name()+" — "+group.title()+": "+ItemAmount.format(group.displayMaterial(),amount));
                return true;
            }
            material=RussianItems.candidates(itemQuery).stream().filter(validItem).findFirst().orElse(null);
            if (material==null) { warn(sender,"Неизвестный предмет. Пример: /topitem "+args[0]+" алмаз"); return true; }
            searchRecorder.accept(material);
            say(sender,owner.name()+" — "+RussianItems.name(material)+": "+ItemAmount.format(material,owner.items().getOrDefault(material,0L)));
            return true;
        }
        int requested=1;
        if (!itemQuery.isEmpty()) {
            try { requested=Integer.parseInt(itemQuery); }
            catch (NumberFormatException e) { warn(sender,"Номер страницы слишком большой."); return true; }
        }
        var rows=owner.items().entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).toList();
        int pages=Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE);
        if (requested<1 || requested>pages) { warn(sender,"Укажите страницу от 1 до "+pages+"."); return true; }
        say(sender,owner.name()+" — ресурсы хранилищ • страница "+requested+"/"+pages);
        if (rows.isEmpty()) warn(sender,"В зарегистрированных хранилищах пока нет предметов.");
        int first=(requested-1)*PAGE_SIZE;
        for (int i=first;i<Math.min(first+PAGE_SIZE,rows.size());i++) {
            var row=rows.get(i);
            GameMessages.send(sender,Component.text((i+1)+". "+RussianItems.name(row.getKey())+" — "+ItemAmount.format(row.getKey(),row.getValue()))
                    .hoverEvent(HoverEvent.showText(Component.text(row.getKey().toLowerCase(Locale.ROOT)))),GameMessages.Tone.INFO);
        }
        // Always use explicit player routing, including when the nickname matches a material.
        String base="/topitem player "+args[0]+" ";
        GameMessages.send(sender,button("◀ Назад",requested>1 ? base+(requested-1) : null)
                .append(Component.text("   "+requested+" / "+pages+"   ",NamedTextColor.GOLD))
                .append(button("Вперёд ▶",requested<pages ? base+(requested+1) : null)),GameMessages.Tone.INFO);
        return true;
    }
    private static Component button(String text,String command) {
        Component result=Component.text("["+text+"]",command==null ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN);
        return command==null ? result : result.clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Открыть страницу")));
    }
    private static void ownerLine(CommandSender sender,String name,String amount) {
        Component nickname=Component.text(name);
        if(name.matches("[A-Za-z0-9_]{3,16}")) nickname=nickname.color(NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand("/tg "+name+" "))
                .hoverEvent(HoverEvent.showText(Component.text("Написать игроку в личный чат")));
        GameMessages.send(sender,Component.empty().append(nickname).append(Component.text(" — "+amount)),GameMessages.Tone.INFO);
    }
    private static void help(CommandSender sender) {
        say(sender,"/topitem all [страница] — суммарный топ; /topitem предмет — топ; /topitem Ник [страница] — все ресурсы; /topitem Ник предмет — количество.");
    }
}
