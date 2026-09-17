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
    TopItemCommand(Catalogue catalogue, BooleanSupplier ready) {
        this(catalogue,ready,id -> { Material type = Material.getMaterial(id); return type != null && type.isItem(); });
    }
    TopItemCommand(Catalogue catalogue, BooleanSupplier ready, Predicate<String> validItem) {
        this.catalogue=catalogue; this.ready=ready; this.validItem=validItem;
    }
    private static void say(CommandSender sender,String text) { sender.sendMessage(Component.text("[VaultTracker] "+text)); }
    boolean execute(CommandSender sender,String[] args) {
        if (!sender.hasPermission("vaulttracker.search")) { say(sender,"Нет права vaulttracker.search."); return true; }
        if (!ready.getAsBoolean()) { say(sender,"Каталог загружается. Повторите поиск чуть позже."); return true; }
        boolean explicit = args.length > 1 && args[0].equalsIgnoreCase("player");
        if (explicit) args=Arrays.copyOfRange(args,1,args.length);
        if (args.length < 1 || args.length > 2) { help(sender); return true; }
        if(!explicit && args[0].equalsIgnoreCase("all")) {
            var rows=catalogue.allItemTotals();int pages=Math.max(1,(rows.size()+19)/20),page=1;
            try {if(args.length==2) page=Integer.parseInt(args[1]);} catch(NumberFormatException bad) {page=0;}
            if(page<1||page>pages) {say(sender,"Укажите страницу от 1 до "+pages+".");return true;}
            say(sender,"Топ по всем предметам · "+page+"/"+pages+" · в шалкерах");
            if(rows.isEmpty()) say(sender,"В каталоге пока нет предметов.");
            for(int i=(page-1)*20;i<Math.min(page*20,rows.size());i++) {var row=rows.get(i);say(sender,(i+1)+". "+row.name()+" — "+ItemAmount.totalShulkers(row.amount()));}
            sender.sendMessage(button("◀ Назад",page>1?"/topitem all "+(page-1):null).append(Component.text("  "+page+"/"+pages+"  ")).append(button("Вперёд ▶",page<pages?"/topitem all "+(page+1):null)));
            return true;
        }
        ResourceGroups.Group group=ResourceGroups.resolve(args[0]);
        if (!explicit && args.length==1 && group!=null) {
            say(sender,group.title()+" — топ владельцев, последние известные остатки:");
            var found=catalogue.findTotals(group.materials(),20);
            if (found.isEmpty()) say(sender,"Не найдено."); else for(var row:found) say(sender,row.name()+" — "+ItemAmount.format(group.displayMaterial(),row.amount()));
            return true;
        }
        String material=RussianItems.normalize(args[0]);
        if (!explicit && args.length==1 && validItem.test(material)) {
            say(sender,RussianItems.name(material)+" — топ владельцев, последние известные остатки:");
            var found=catalogue.findTotals(material,20);
            if (found.isEmpty()) say(sender,"Не найдено."); else for(var row:found) say(sender,row.name()+" — "+ItemAmount.format(material,row.amount()));
            if (!catalogue.ownerItems(args[0]).isEmpty()) say(sender,"Для просмотра игрока с таким ником: /topitem player "+args[0]);
            return true;
        }
        if (!args[0].matches("[A-Za-z0-9_.-]{1,32}")) { say(sender,"Некорректный ник или название предмета."); return true; }
        List<Catalogue.OwnerItems> matches=catalogue.ownerItems(args[0]);
        if (matches.isEmpty()) { say(sender,"Игрок «"+args[0]+"» не найден в каталоге зарегистрированных хранилищ."); return true; }
        if (matches.size()>1) { say(sender,"В каталоге несколько UUID с этим ником. Администратору нужно проверить записи владельцев."); return true; }
        var owner=matches.getFirst();
        if (args.length==2 && !args[1].matches("[+-]?[0-9]+")) {
            group=ResourceGroups.resolve(args[1]);
            if(group!=null) {
                long amount=group.materials().stream().mapToLong(item->owner.items().getOrDefault(item,0L)).sum();
                say(sender,owner.name()+" — "+group.title()+": "+ItemAmount.format(group.displayMaterial(),amount));
                return true;
            }
            material=RussianItems.normalize(args[1]);
            if (!validItem.test(material)) { say(sender,"Неизвестный предмет. Пример: /topitem "+args[0]+" diamond"); return true; }
            say(sender,owner.name()+" — "+RussianItems.name(material)+": "+ItemAmount.format(material,owner.items().getOrDefault(material,0L)));
            return true;
        }
        int requested=1;
        if (args.length==2) {
            try { requested=Integer.parseInt(args[1]); }
            catch (NumberFormatException e) { say(sender,"Номер страницы слишком большой."); return true; }
        }
        var rows=owner.items().entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).toList();
        int pages=Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE);
        if (requested<1 || requested>pages) { say(sender,"Укажите страницу от 1 до "+pages+"."); return true; }
        say(sender,owner.name()+" — ресурсы хранилищ • страница "+requested+"/"+pages);
        if (rows.isEmpty()) say(sender,"В зарегистрированных хранилищах пока нет предметов.");
        int first=(requested-1)*PAGE_SIZE;
        for (int i=first;i<Math.min(first+PAGE_SIZE,rows.size());i++) {
            var row=rows.get(i);
            sender.sendMessage(Component.text((i+1)+". "+RussianItems.name(row.getKey())+" — "+ItemAmount.format(row.getKey(),row.getValue()))
                    .hoverEvent(HoverEvent.showText(Component.text(row.getKey().toLowerCase(Locale.ROOT)))));
        }
        // Always use explicit player routing, including when the nickname matches a material.
        String base="/topitem player "+args[0]+" ";
        sender.sendMessage(button("◀ Назад",requested>1 ? base+(requested-1) : null)
                .append(Component.text("   "+requested+" / "+pages+"   ",NamedTextColor.GOLD))
                .append(button("Вперёд ▶",requested<pages ? base+(requested+1) : null)));
        return true;
    }
    private static Component button(String text,String command) {
        Component result=Component.text("["+text+"]",command==null ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN);
        return command==null ? result : result.clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Открыть страницу")));
    }
    private static void help(CommandSender sender) {
        say(sender,"/topitem all [страница] — суммарный топ; /topitem предмет — топ; /topitem Ник [страница] — все ресурсы; /topitem Ник предмет — количество.");
    }
}
