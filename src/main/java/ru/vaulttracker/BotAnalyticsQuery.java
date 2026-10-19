package ru.vaulttracker;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

record BotAnalyticsQuery(LocalDate from,LocalDate until,String role,String feature) {
    static BotAnalyticsQuery parse(Map<String,String> p,ZoneId zone,long now,long started) {
        if(!Set.of("period","from","to","role","feature").containsAll(p.keySet()))throw new IllegalArgumentException("Неизвестный фильтр");
        LocalDate today=Instant.ofEpochSecond(now).atZone(zone).toLocalDate(),from,until=today.plusDays(1);
        String period=p.getOrDefault("period","30");
        try {
            if(period.equals("custom")){from=LocalDate.parse(p.get("from"));until=LocalDate.parse(p.get("to")).plusDays(1);}
            else if(period.equals("all"))from=Instant.ofEpochSecond(started).atZone(zone).toLocalDate();
            else if(period.equals("yesterday")){from=today.minusDays(1);until=today;}
            else if(period.equals("today"))from=today;
            else {int days=Integer.parseInt(period);if(!Set.of(7,30,90,180,365).contains(days))throw new IllegalArgumentException();from=today.minusDays(days-1);}
            if(!from.isBefore(until)||until.isAfter(today.plusDays(1))||from.getYear()<2000)throw new IllegalArgumentException();
        }catch(Exception invalid){throw new IllegalArgumentException("Проверьте период: начало не позже окончания, без будущих дат");}
        String role=p.getOrDefault("role","all"),feature=p.getOrDefault("feature","all");
        if(!Set.of("all","player","admin","super","root").contains(role)||!feature.equals("all")&&!BotAnalyticsCatalog.FEATURES.containsKey(feature))throw new IllegalArgumentException("Неизвестная роль или функция");
        return new BotAnalyticsQuery(from,until,role,feature);
    }
    long start(ZoneId zone){return from.atStartOfDay(zone).toEpochSecond();}
    long end(ZoneId zone){return until.atStartOfDay(zone).toEpochSecond();}
    BotAnalyticsQuery previous(){long days=ChronoUnit.DAYS.between(from,until);return new BotAnalyticsQuery(from.minusDays(days),from,role,feature);}
}
