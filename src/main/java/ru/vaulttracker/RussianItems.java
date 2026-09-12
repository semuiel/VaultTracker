package ru.vaulttracker;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class RussianItems {
    private static final Map<String,String> TRANSLATIONS = load();
    private static Map<String,String> load() {
        try (var input = RussianItems.class.getResourceAsStream("/ru_ru.json")) {
            if (input == null) throw new IllegalStateException("Missing bundled Russian translations");
            JsonObject json = JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String,String> names = new HashMap<>();
            json.entrySet().forEach(e -> names.put(e.getKey(),e.getValue().getAsString()));
            return Map.copyOf(names);
        } catch (IOException e) { throw new IllegalStateException("Cannot load Russian translations",e); }
    }
    static String name(String material) {
        String key = material.toLowerCase(Locale.ROOT);
        return TRANSLATIONS.getOrDefault("item.minecraft."+key,TRANSLATIONS.getOrDefault("block.minecraft."+key,material));
    }
    static String normalize(String raw) {
        String name = raw.toUpperCase(Locale.ROOT).replaceFirst("^MINECRAFT:","");
        return name.equals("DAIMOND") || name.equals("DIAMON") ? "DIAMOND" : name;
    }
}
