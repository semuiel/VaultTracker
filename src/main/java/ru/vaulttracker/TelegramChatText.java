package ru.vaulttracker;

import com.google.gson.*;
import net.kyori.adventure.text.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Translate server components without downloading or changing Minecraft assets. */
final class TelegramChatText {
    private final Map<String,String> overrides=new HashMap<>();
    private static final Pattern ARG=Pattern.compile("%(?:(\\d+)\\$)?s|%%");
    TelegramChatText(Path folder) throws Exception {
        Path file=folder.resolve("telegramchat-lang.json");if(!Files.exists(file)) return;
        if(Files.size(file)>2_000_000) throw new IllegalArgumentException("telegramchat-lang.json: файл больше 2 МБ");
        try(var reader=Files.newBufferedReader(file)) {JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(e->overrides.put(e.getKey(),e.getValue().getAsString()));}
    }
    String plain(Component c) {
        if(c==null) return "";StringBuilder text=new StringBuilder();
        if(c instanceof TextComponent literal) text.append(literal.content());
        else if(c instanceof TranslatableComponent translated) {
            String value=overrides.getOrDefault(translated.key(),RussianItems.translation(translated.key()));
            if(value.equals(translated.key()) && translated.fallback()!=null) value=translated.fallback();
            Matcher m=ARG.matcher(value);int position=0;StringBuilder rendered=new StringBuilder();
            var args=translated.args();
            while(m.find()) {int index=m.group().equals("%%")?-1:m.group(1)==null?position++:Integer.parseInt(m.group(1))-1;String replacement=index<0?"%":index<args.size()?plain(args.get(index)):m.group();m.appendReplacement(rendered,Matcher.quoteReplacement(replacement));}
            m.appendTail(rendered);text.append(rendered);
        }
        else text.append(TelegramChatBridge.plain(c.children(List.of())));
        for(Component child:c.children()) text.append(plain(child));return text.toString();
    }
}
