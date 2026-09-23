package ru.vaulttracker;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies editable menu phrases at the final Telegram view boundary. */
final class TelegramMenuText {
    private record Replacement(String from,String to) {}
    private final Map<String,String> replacements;
    private final Pattern pattern;
    private final Map<String,String> emojis;
    private final Pattern emojiPattern;
    private static final Pattern LOCATION=Pattern.compile("Мир: ([^\\r\\n]+)\\RКоординаты: (-?[0-9]+ -?[0-9]+ -?[0-9]+)");

    private TelegramMenuText(List<Replacement> rows,List<Replacement> emojiRows) {
        Map<String,String> values=new HashMap<>();for(Replacement row:rows) values.put(row.from(),row.to());replacements=Map.copyOf(values);
        pattern=rows.isEmpty()?null:Pattern.compile(rows.stream().map(row->Pattern.quote(row.from())).reduce((left,right)->left+"|"+right).orElse("(?!)"));
        Map<String,String> icons=new HashMap<>();for(Replacement row:emojiRows) icons.put(row.from(),row.to());emojis=Map.copyOf(icons);
        emojiPattern=emojiRows.isEmpty()?null:Pattern.compile(emojiRows.stream().map(row->Pattern.quote(row.from())).reduce((left,right)->left+"|"+right).orElse("(?!)"));
    }
    static TelegramMenuText load(Path folder,Logger log) {
        try {
            var yaml=YamlConfiguration.loadConfiguration(folder.resolve("telegram-menu.yml").toFile());
            List<Replacement> rows=new ArrayList<>();
            for(Map<?,?> entry:yaml.getMapList("replacements")) {
                String from=Objects.toString(entry.get("from"),"");String to=Objects.toString(entry.get("to"),from);
                if(!from.isEmpty()) rows.add(new Replacement(from,to));
            }
            rows.sort(Comparator.comparingInt((Replacement row)->row.from().length()).reversed());
            List<Replacement> emojiRows=new ArrayList<>();
            for(Map<?,?> entry:yaml.getMapList("emojis")) {
                String original=Objects.toString(entry.get("original"),"");String fallback=Objects.toString(entry.get("emoji"),original);
                String id=Objects.toString(entry.get("customEmojiId"),"").strip();
                if(!id.isEmpty()&&!id.matches("[0-9]{1,30}")) {log.warning("telegram-menu.yml: customEmojiId для "+original+" должен содержать только цифры; используется обычный смайлик.");id="";}
                if(!original.isEmpty()) emojiRows.add(new Replacement(original,TelegramEmojiMarkup.marker(id,fallback)));
            }
            emojiRows.sort(Comparator.comparingInt((Replacement row)->row.from().length()).reversed());
            return new TelegramMenuText(List.copyOf(rows),List.copyOf(emojiRows));
        } catch(Exception failure) {
            log.warning("Не удалось прочитать telegram-menu.yml: "+failure.getMessage()+". Используются встроенные тексты.");
            return new TelegramMenuText(List.of(),List.of());
        }
    }
    String text(String value) {
        if(value==null||value.isEmpty()) return value;String result=value;
        if(pattern!=null) {Matcher matcher=pattern.matcher(result);StringBuilder changed=new StringBuilder();while(matcher.find()) matcher.appendReplacement(changed,Matcher.quoteReplacement(replacements.get(matcher.group())));matcher.appendTail(changed);result=changed.toString();}
        {Matcher matcher=LOCATION.matcher(result);StringBuilder changed=new StringBuilder();while(matcher.find()) matcher.appendReplacement(changed,Matcher.quoteReplacement("📍 "+matcher.group(1)+" · "+TelegramEmojiMarkup.plainCode(matcher.group(2))));matcher.appendTail(changed);result=changed.toString();}
        for(String key:List.of("FLEXITY","Поиск ресурсов","«Игрок»","«Предмет»","[v]","[vault]")) result=boldOnce(result,key);
        if(emojiPattern!=null) {Matcher matcher=emojiPattern.matcher(result);StringBuilder changed=new StringBuilder();while(matcher.find()) matcher.appendReplacement(changed,Matcher.quoteReplacement(emojis.get(matcher.group())));matcher.appendTail(changed);result=changed.toString();}
        return result;
    }
    private static String boldOnce(String value,String key) {
        String bold=TelegramEmojiMarkup.bold(key);return value.contains(bold)?value:value.replace(key,bold);
    }
    TelegramCommands.View view(TelegramCommands.View source) {
        if(source==null) return null;
        boolean mainMenu=source.buttons().stream().anyMatch(button->button.data().equals("vg:search"))
                && source.buttons().stream().anyMatch(button->button.data().equals("vg:home"));
        List<TelegramCommands.Button> buttons=source.buttons().stream()
                .filter(button->!mainMenu || (!button.text().equals("↩ В кабинет")&&!button.data().equals("vg:menu")))
                .map(button->new TelegramCommands.Button(text(button.text()),button.data(),button.row(),button.url())).toList();
        return new TelegramCommands.View(text(source.text()),buttons);
    }
}
