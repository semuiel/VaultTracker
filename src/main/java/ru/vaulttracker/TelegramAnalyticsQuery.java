package ru.vaulttracker;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

record TelegramAnalyticsQuery(Long chat,Integer topic,LocalDate from,LocalDate to,boolean all,String grain,String sort,int offset) {
    static TelegramAnalyticsQuery parse(Map<String,String> p,TelegramAnalyticsConfig c,LocalDate today,LocalDate earliest) {
        if(!Set.of("chat","topic","period","from","to","grain","sort","offset").containsAll(p.keySet()))throw new IllegalArgumentException("Неизвестный фильтр");
        Long chat=p.getOrDefault("chat","").isBlank()?null:Long.valueOf(p.get("chat"));
        if(chat!=null&&!c.chats().contains(chat))throw new IllegalArgumentException("Чат не отслеживается");
        Integer topic=p.getOrDefault("topic","").isBlank()?null:Integer.valueOf(p.get("topic"));
        if(topic!=null&&(chat==null||topic<0))throw new IllegalArgumentException("Для темы выберите чат");
        String preset=p.getOrDefault("period","30");boolean all=preset.equals("all");LocalDate from,to=today.plusDays(1);
        try {
            if(preset.equals("custom")){from=LocalDate.parse(p.get("from"));to=LocalDate.parse(p.get("to")).plusDays(1);}
            else if(all)from=earliest;
            else if(preset.equals("yesterday")){from=today.minusDays(1);to=today;}
            else {if(!Set.of("1","7","30","90","180","365").contains(preset))throw new IllegalArgumentException();from=to.minusDays(Integer.parseInt(preset));}
            if(from.getYear()<1970||to.getYear()>2200||!from.isBefore(to)||to.isAfter(today.plusDays(1)))throw new IllegalArgumentException();
        }catch(Exception invalid){throw new IllegalArgumentException("Проверьте даты: начало не позже конца, конец не в будущем");}
        long days=ChronoUnit.DAYS.between(from,to);String grain=p.getOrDefault("grain","auto");
        if(grain.equals("auto"))grain=all||days>365?"month":days>60?"week":"day";
        if(!Set.of("day","week","month").contains(grain)||days>2000&&grain.equals("day"))throw new IllegalArgumentException("Для длинного периода выберите недели или месяцы");
        String sort=p.getOrDefault("sort","active");if(!Set.of("active","growth","fall","name").contains(sort))throw new IllegalArgumentException("Проверьте сортировку");
        int offset=Integer.parseInt(p.getOrDefault("offset","0"));if(offset<0||offset>10000)throw new IllegalArgumentException("Сузьте список тем");
        return new TelegramAnalyticsQuery(chat,topic,from,to,all,grain,sort,offset);
    }
}
