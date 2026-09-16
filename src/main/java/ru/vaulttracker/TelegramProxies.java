package ru.vaulttracker;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Private Telegram routes; the list never changes JVM networking defaults. */
final class TelegramProxies {
    static List<TelegramConfig.Proxy> load(TelegramConfig config) throws IOException {
        Path file=config.offsetFile().resolveSibling("telegram-proxies.txt");
        if(!Files.exists(file)) {
            Files.writeString(file,"# SOCKS5: host:port:username:password, one proxy per line\n# Empty file: use advanced.proxy from telegram.yml\n");
        }
        List<TelegramConfig.Proxy> routes=new ArrayList<>();int line=0;
        for(String raw:Files.readAllLines(file)) {
            line++;String value=raw.trim();if(value.isEmpty()||value.startsWith("#")) continue;
            String[] parts=value.split(":",4);
            try {
                if(parts.length!=4 || !parts[0].matches("[A-Za-z0-9.-]+") || parts[2].isBlank() || parts[3].isBlank()) throw new IllegalArgumentException();
                int port=Integer.parseInt(parts[1]);if(port<1||port>65535) throw new IllegalArgumentException();
                if(parts[2].getBytes(java.nio.charset.StandardCharsets.UTF_8).length>255 || parts[3].getBytes(java.nio.charset.StandardCharsets.UTF_8).length>255) throw new IllegalArgumentException();
                routes.add(new TelegramConfig.Proxy(TelegramConfig.ProxyType.SOCKS5,parts[0],port,parts[2],parts[3]));
            } catch(IllegalArgumentException e) {throw new IOException("telegram-proxies.txt: неверный формат в строке "+line);}
        }
        return routes.isEmpty()?List.of(config.proxy()):List.copyOf(routes);
    }
}
