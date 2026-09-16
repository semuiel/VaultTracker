package ru.vaulttracker;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class RussianItems {
    private static final Map<String,String> TRANSLATIONS = load();
    private static final Map<String,List<String>> REVERSE = reverse();
    private static Map<String,String> load() {
        try (var input = RussianItems.class.getResourceAsStream("/ru_ru.json")) {
            if (input == null) throw new IllegalStateException("Missing bundled Russian translations");
            JsonObject json = JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String,String> names = new HashMap<>();
            json.entrySet().forEach(e -> names.put(e.getKey(),e.getValue().getAsString()));
            return Map.copyOf(names);
        } catch (IOException e) { throw new IllegalStateException("Cannot load Russian translations",e); }
    }
    static boolean block(String material) {return TRANSLATIONS.containsKey("block.minecraft."+material.toLowerCase(Locale.ROOT));}
    static String translation(String key) {return TRANSLATIONS.getOrDefault(key,key);}
    static String name(String material) {
        String key = material.toLowerCase(Locale.ROOT);
        return TRANSLATIONS.getOrDefault("item.minecraft."+key,TRANSLATIONS.getOrDefault("block.minecraft."+key,material));
    }
    static String normalize(String raw) {
        String name = raw.toUpperCase(Locale.ROOT).replaceFirst("^MINECRAFT:","");
        return name.equals("DAIMOND") || name.equals("DIAMON") ? "DIAMOND" : name;
    }
    static List<String> candidates(String raw) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        String english=raw.trim().replace('-','_').replaceAll("\\s+","_");
        if(english.matches("(?i)(?:minecraft:)?[a-z0-9_]+")) result.add(normalize(english));
        result.addAll(REVERSE.getOrDefault(searchKey(raw),List.of()));
        return List.copyOf(result);
    }
    private static Map<String,List<String>> reverse() {
        Map<String,Set<String>> values=new HashMap<>();
        for(var entry:TRANSLATIONS.entrySet()) {
            String key=entry.getKey(); String prefix;
            if(key.startsWith("item.minecraft.")) prefix="item.minecraft.";
            else if(key.startsWith("block.minecraft.")) prefix="block.minecraft.";
            else continue;
            String material=key.substring(prefix.length()).toUpperCase(Locale.ROOT);
            values.computeIfAbsent(searchKey(entry.getValue()),ignored->new TreeSet<>()).add(material);
        }
        Map<String,List<String>> result=new HashMap<>();
        values.forEach((key,value)->result.put(key,List.copyOf(value)));
        return Map.copyOf(result);
    }
    private static String searchKey(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ");
    }
}
