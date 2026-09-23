package ru.vaulttracker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Internal safe marker for Telegram custom emoji entities. */
final class TelegramEmojiMarkup {
    private static final char START='\uE100',MIDDLE='\uE101',END='\uE102',BOLD_START='\uE103',BOLD_END='\uE104',CODE_START='\uE105',CODE_END='\uE106',PLAIN_CODE_START='\uE107',PLAIN_CODE_END='\uE108';
    private static final Pattern CONFIG=Pattern.compile("\\{emoji:([0-9]{1,30})\\|([^{}\\r\\n]+)}");
    private static final Pattern MARKER=Pattern.compile(Pattern.quote(String.valueOf(START))+"([0-9]{1,30})"+Pattern.quote(String.valueOf(MIDDLE))+"([^"+END+"]+)"+Pattern.quote(String.valueOf(END)));
    record Rendered(String text,boolean custom) {}

    static String configured(String value) {
        if(value==null||value.isEmpty()) return "";
        Matcher matcher=CONFIG.matcher(value);StringBuilder result=new StringBuilder();
        while(matcher.find()) matcher.appendReplacement(result,Matcher.quoteReplacement(marker(matcher.group(1),matcher.group(2))));
        matcher.appendTail(result);return result.toString();
    }
    static String marker(String id,String fallback) {
        if(id==null||id.isBlank()||fallback==null||fallback.isBlank()) return fallback==null?"":fallback;
        return START+id+MIDDLE+fallback+END;
    }
    static String bold(String value) {return value==null||value.isEmpty()?value:BOLD_START+value+BOLD_END;}
    static String code(String value) {return value==null||value.isEmpty()?value:CODE_START+value+CODE_END;}
    static String plainCode(String value) {return value==null||value.isEmpty()?value:PLAIN_CODE_START+value+PLAIN_CODE_END;}
    static String plain(String value) {
        if(value==null||value.isEmpty()) return value;
        Matcher matcher=MARKER.matcher(value);StringBuilder result=new StringBuilder();
        while(matcher.find()) matcher.appendReplacement(result,Matcher.quoteReplacement(matcher.group(2)));
        matcher.appendTail(result);return result.toString().replace(String.valueOf(BOLD_START),"").replace(String.valueOf(BOLD_END),"")
                .replace(String.valueOf(CODE_START),"").replace(String.valueOf(CODE_END),"")
                .replace(String.valueOf(PLAIN_CODE_START),"").replace(String.valueOf(PLAIN_CODE_END),"");
    }
    static Rendered html(String value) {
        if(value==null) return new Rendered("",false);
        Matcher matcher=MARKER.matcher(value);StringBuilder result=new StringBuilder();int position=0;boolean custom=false;
        while(matcher.find()) {
            result.append(escape(value.substring(position,matcher.start())));
            result.append("<tg-emoji emoji-id=\"").append(matcher.group(1)).append("\">").append(escape(matcher.group(2))).append("</tg-emoji>");
            position=matcher.end();custom=true;
        }
        if(!custom) return value.indexOf(BOLD_START)<0&&value.indexOf(BOLD_END)<0&&value.indexOf(CODE_START)<0&&value.indexOf(CODE_END)<0&&value.indexOf(PLAIN_CODE_START)<0&&value.indexOf(PLAIN_CODE_END)<0?new Rendered(value,false):new Rendered(escape(value),true);
        result.append(escape(value.substring(position)));return new Rendered(result.toString(),true);
    }
    private static String escape(String value) {return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
            .replace(String.valueOf(BOLD_START),"<b>").replace(String.valueOf(BOLD_END),"</b>")
            .replace(String.valueOf(CODE_START),"</b><code>").replace(String.valueOf(CODE_END),"</code><b>").replace("<b></b>","")
            .replace(String.valueOf(PLAIN_CODE_START),"<code>").replace(String.valueOf(PLAIN_CODE_END),"</code>");}
    private TelegramEmojiMarkup() {}
}
