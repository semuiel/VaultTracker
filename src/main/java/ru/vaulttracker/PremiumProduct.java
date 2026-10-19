package ru.vaulttracker;

import com.google.gson.JsonObject;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Tariff presentation; the payment service independently validates every price and duration. */
enum PremiumProduct {
    LITE("premiumlite","💠 Premium Lite","premiumLiteUntil","premiumLitePending",new long[]{31900,54900,149000,269000}),
    PREMIUM("premium","💎 Premium","premiumUntil","premiumPending",new long[]{60000,100000,270000,480000});

    static final List<Integer> MONTHS=List.of(1,2,6,12);
    final String group,title,untilKey,pendingKey;
    private final long[] prices;
    PremiumProduct(String group,String title,String untilKey,String pendingKey,long[] prices) {
        this.group=group;this.title=title;this.untilKey=untilKey;this.pendingKey=pendingKey;this.prices=prices;
    }
    static PremiumProduct parse(String id) {
        for(var p:values()) if(p.group.equals(id)) return p;
        throw new IllegalArgumentException("Неизвестный тариф Premium.");
    }
    long price(int months) {
        int index=MONTHS.indexOf(months);if(index<0)throw new IllegalArgumentException("Неизвестный срок Premium.");
        return prices[index];
    }
    long until(JsonObject wallet) {return wallet.has(untilKey)?wallet.get(untilKey).getAsLong():0;}
    String description() {
        String lite="• Цвет ника.\n• Цвет сообщений в чате.\n• Личные пресеты оформления.\n• Цветные названия предметов.\n• Блоки и предметы на голове.";
        if(this==LITE)return "Личное оформление персонажа и чата:\n"+lite;
        return "Все возможности Premium Lite:\n"+lite+"\n\nДополнительно:\n"
                +"• Градиенты и радуга для ника и чата.\n• Значок после ника.\n• Тема профиля и описание.\n"
                +"• Оформление облачков чата.\n• Частицы.\n• Питомцы и выбор плеча.\n"
                +"• Расширенные поводки.\n• Портативная колонка.\n• Запись кастомных пластинок.";
    }
    String status(JsonObject wallet) {return CabinetMenu.premiumStatus(until(wallet));}
    static String summary(JsonObject wallet) {
        List<String> active=new ArrayList<>();
        for(var product:List.of(PREMIUM,LITE)) {
            long until=product.until(wallet);
            if(until<0 || until>Instant.now().getEpochSecond()) {
                String expiry=until<0?"бессрочно":"до "+Instant.ofEpochSecond(until).atZone(ZoneId.of("Europe/Moscow"))
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))+" МСК";
                active.add((product==LITE?"Premium Lite":"Premium")+" · "+expiry);
            }
        }
        return active.isEmpty()?"не активен":String.join("\n",active);
    }
}
