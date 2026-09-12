package ru.vaulttracker;

import java.util.*;

final class TelegramCommands {
    private final Catalogue catalogue; private final StorageEngine storage; private final int limit;
    TelegramCommands(Catalogue catalogue,StorageEngine storage,int limit) { this.catalogue=catalogue; this.storage=storage; this.limit=limit; }
    String handle(long chatId,String input) {
        String text=input==null ? "" : input.trim(); String command=command(text);
        if(text.isEmpty() || command.equals("/start") || command.equals("/help")) return help();
        if(command.equals("/id")) return "ID этого чата: "+chatId;
        if(command.equals("/status")) return storage.status()+"; хранилищ: "+catalogue.size();
        if(command.equals("/items")) return items();
        if(command.equals("/find")) command="/topitem";
        if(command.equals("/topitem")) {
            String[] parts=text.split("\\s+",2);
            if(parts.length<2) return "Укажите ресурс. Пример: /topitem diamond";
            String material=RussianItems.normalize(parts[1].trim().replace('-','_').replace(' ','_'));
            if(!material.matches("[A-Z0-9_]{1,128}")) return "Название ресурса не распознано.";
            var rows=catalogue.findTotals(material,limit);
            if(rows.isEmpty()) return RussianItems.name(material)+" — не найдено в зарегистрированных хранилищах.";
            StringBuilder answer=new StringBuilder(RussianItems.name(material)).append(" — топ владельцев:\n\n");
            for(int i=0;i<rows.size();i++) answer.append(i+1).append(". ").append(rows.get(i).name()).append(" — ")
                    .append(ItemAmount.format(material,rows.get(i).amount())).append('\n');
            return answer.toString().stripTrailing();
        }
        return "Неизвестная команда.\n\n"+help();
    }
    private String items() {
        Map<String,Long> totals=new HashMap<>();
        for(Snapshot snapshot:catalogue.all()) snapshot.items().forEach((item,amount)->totals.merge(item,amount,Long::sum));
        if(totals.isEmpty()) return "В зарегистрированных хранилищах пока нет ресурсов.";
        StringBuilder answer=new StringBuilder("Ресурсы каталога:\n\n");
        totals.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(limit)
                .forEach(row->answer.append(RussianItems.name(row.getKey())).append(" — ").append(ItemAmount.format(row.getKey(),row.getValue())).append('\n'));
        return answer.append("\nДля поиска: /topitem diamond").toString();
    }
    static String command(String text) {
        if(text.isBlank()) return ""; String first=text.split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        int mention=first.indexOf('@'); return mention<0 ? first : first.substring(0,mention);
    }
    private static String help() {
        return "Каталог ресурсов VaultTracker.\n\n/topitem diamond — топ владельцев ресурса\n/items — ресурсы каталога\n/status — состояние плагина\n/id — ID чата\n/help — помощь";
    }
}
