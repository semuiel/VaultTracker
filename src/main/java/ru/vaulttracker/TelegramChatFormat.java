package ru.vaulttracker;

import java.util.*;
import java.util.regex.*;

final class TelegramChatFormat {
    record PlayerRow(String username,String displayName,String world,String dimension,int ping) {
        PlayerRow(String username,String displayName,String world,String dimension) {this(username,displayName,world,dimension,0);}
    }
    private static final Pattern VARIABLE=Pattern.compile("\\{([a-zA-Z]+)}");
    static String escape(String value) {return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static String template(String format,Map<String,String> escapedValues) {
        Matcher matcher=VARIABLE.matcher(format);StringBuilder result=new StringBuilder();
        while(matcher.find()) matcher.appendReplacement(result,Matcher.quoteReplacement(escapedValues.getOrDefault(matcher.group(1),matcher.group())));
        matcher.appendTail(result);return result.toString();
    }
    static String withoutCustomEmoji(String html) {return html.replaceAll("<tg-emoji\\s+emoji-id=\"[0-9]+\">(.*?)</tg-emoji>","$1");}
    static String chatText(TelegramChatConfig config,String message) {
        String prefix=config.text("messages.requirePrefixInMinecraft","");
        if(!prefix.isEmpty() && !message.startsWith(prefix)) return null;
        return !prefix.isEmpty() && !config.flag("messages.keepPrefix",false)?message.substring(prefix.length()).stripLeading():message;
    }
    static List<String> playerList(TelegramChatConfig config,Collection<PlayerRow> input) {
        var people=input.stream().sorted(Comparator.comparing(PlayerRow::username,String.CASE_INSENSITIVE_ORDER)).toList();
        if(people.isEmpty()) return List.of(config.format("listEmpty","<b>Идеальное время для захвата мира. Никакой конкуренции.😉</b>"));
        List<String> pages=new ArrayList<>();int count=people.size();StringBuilder current=new StringBuilder();int lines=0;
        boolean realNames=config.flag("list.useRealUsername",true);
        int nameWidth=people.stream().map(p->realNames?p.username():p.displayName()).mapToInt(n->n.codePointCount(0,n.length())).max().orElse(0);
        for(int i=0;i<count;i++) {
            var player=people.get(i);String name=config.flag("list.useRealUsername",true)?player.username():player.displayName();
            String ping=config.flag("list.showPing",false)?template(config.format("listPing"," · {ping} мс"),Map.of("ping",Integer.toString(Math.max(0,player.ping())))):"";
            String rowFormat=config.format("listRow","{emoji} {name}{ping}");
            // Old listRow templates also gain ping when the switch is enabled.
            if(!rowFormat.contains("{ping}")) rowFormat+="{ping}";
            // Keep the icon outside monospace: Telegram custom emoji cannot be nested in code.
            // Preserve arbitrary HTML templates; alignment applies to the standard plain suffix.
            if(config.flag("list.showPing",false) && config.flag("list.alignPing",true)
                    && rowFormat.equals("{emoji} {name}{ping}") && !ping.contains("<")) {
                name += " ".repeat(Math.max(0,nameWidth-name.codePointCount(0,name.length()))+2);
                rowFormat="{emoji} <code>{name}{ping}</code>";
            }
            String row=template(rowFormat,Map.of("emoji",config.dimensions.getOrDefault(player.dimension(),config.dimensions.get("other")).html(),"name",escape(name),"username",escape(player.username()),"world",escape(player.world()),"number",Integer.toString(i+1),"ping",ping));
            if(lines==20 || (!current.isEmpty() && current.length()+row.length()>3000)) {pages.add(current.toString());current.setLength(0);lines=0;}
            current.append(row).append('\n');lines++;
        }
        if(!current.isEmpty()) pages.add(current.toString());
        List<String> result=new ArrayList<>();
        for(int i=0;i<pages.size();i++) result.add(template(config.format("listHeader","📝 <b>Вот они слева направо:</b> · {count} онлайн"),Map.of("count",Integer.toString(count),"page",Integer.toString(i+1),"pages",Integer.toString(pages.size())))+"\n"+pages.get(i).stripTrailing());
        return List.copyOf(result);
    }
}
