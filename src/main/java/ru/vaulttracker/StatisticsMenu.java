package ru.vaulttracker;

import java.util.*;

/** Compact, paged views of UUID-keyed vanilla statistics. */
final class StatisticsMenu extends TelegramMenuSection {
    private static final int PAGE=10;
    StatisticsMenu(TelegramGuardMenu root) {super(root);}

    TelegramCommands.View home(long user) throws Exception {
        if(guard.account(user).get()==null) return root.home(user);
        return new TelegramCommands.View("📊 Статистика персонажа",List.of(
                b(user,"📋 Общая","statisticsSection","general",0,0),
                b(user,"🚶 Передвижение","statisticsSection","movement",0,1),
                b(user,"⛏ Блоки и ресурсы","statisticsSection","blocks",0,2),
                b(user,"⚔️ Бои","statisticsSection","combat",0,3),
                b(user,"🎒 Предметы","statisticsSection","items",0,4),
                new TelegramCommands.Button("↩ В кабинет","vg:home",5),homeButton(6)));
    }
    private Map<String,Long> data(long user) throws Exception {
        var account=guard.account(user).get();
        if(account==null) throw new SecurityException("Сначала привяжите персонажа");
        return moderation==null?guard.statistics(account.uuid()).get():moderation.statistics(user,account.uuid()).get();
    }
    private static String meters(Map<String,Long> data,String name) {return PlayerStatistics.value(data,name)/100+" м";}
    private static String topSummary(Map<String,Long> data,String prefix) {
        var rows=PlayerStatistics.top(data,prefix);StringBuilder out=new StringBuilder();
        for(int i=0;i<Math.min(5,rows.size());i++) {
            var row=rows.get(i);out.append("\n").append(i+1).append(". ").append(RussianItems.name(row.getKey().substring(prefix.length()+1))).append(" — ").append(row.getValue());
        }
        return out.isEmpty()?"\nПока нет данных.":out.toString();
    }
    TelegramCommands.View section(long user,String kind) throws Exception {
        Map<String,Long> s=data(user);String text;List<TelegramCommands.Button> buttons=new ArrayList<>();
        switch(kind) {
            case "general" -> text="📋 Общая\nВ игре: "+AdminMenu.playtime(PlayerStatistics.value(s,"PLAY_ONE_MINUTE")/20)
                    +"\nСмерти: "+PlayerStatistics.value(s,"DEATHS")+"\nПрыжки: "+PlayerStatistics.value(s,"JUMP")
                    +"\nУбийства игроков: "+PlayerStatistics.value(s,"PLAYER_KILLS")
                    +"\nУбийства мобов: "+PlayerStatistics.value(s,"MOB_KILLS")
                    +"\nТорговля с жителями: "+PlayerStatistics.value(s,"TRADED_WITH_VILLAGER");
            case "movement" -> text="🚶 Передвижение\nПешком: "+meters(s,"WALK_ONE_CM")
                    +"\nБег: "+meters(s,"SPRINT_ONE_CM")+"\nПлавание: "+meters(s,"SWIM_ONE_CM")
                    +"\nЭлитры: "+meters(s,"AVIATE_ONE_CM")+"\nПолёт: "+meters(s,"FLY_ONE_CM")
                    +"\nЛодка: "+meters(s,"BOAT_ONE_CM")+"\nВагонетка: "+meters(s,"MINECART_ONE_CM");
            case "blocks" -> {
                text="⛏ Блоки и ресурсы\nДобыто блоков: "+PlayerStatistics.sum(s,"mine")
                        +"\nВидов блоков: "+PlayerStatistics.top(s,"mine").size()
                        +"\nЧаще всего:"+topSummary(s,"mine");
                buttons.add(b(user,"📋 Все добытые блоки","statisticsList","mine",0,0));
            }
            case "combat" -> text="⚔️ Бои\nУбийства игроков: "+PlayerStatistics.value(s,"PLAYER_KILLS")
                    +"\nУбийства мобов: "+PlayerStatistics.value(s,"MOB_KILLS")
                    +"\nСмерти: "+PlayerStatistics.value(s,"DEATHS")
                    +"\nНанесённый урон: "+PlayerStatistics.value(s,"DAMAGE_DEALT")
                    +"\nПолученный урон: "+PlayerStatistics.value(s,"DAMAGE_TAKEN");
            case "items" -> {
                text="🎒 Предметы\nСоздано: "+PlayerStatistics.sum(s,"craft")
                        +"\nИспользовано: "+PlayerStatistics.sum(s,"use")
                        +"\nСломано: "+PlayerStatistics.sum(s,"break")
                        +"\nПодобрано: "+PlayerStatistics.sum(s,"pickup")
                        +"\nВыброшено: "+PlayerStatistics.sum(s,"drop");
                int row=0;
                for(String prefix:List.of("craft","use","break","pickup","drop"))
                    buttons.add(b(user,switch(prefix) {case "craft"->"Созданные";case "use"->"Использованные";case "break"->"Сломанные";case "pickup"->"Подобранные";default->"Выброшенные";},"statisticsList",prefix,0,row++));
            }
            default -> throw new IllegalArgumentException("Неизвестный раздел статистики");
        }
        buttons.add(b(user,"↩️ Статистика","statistics","",0,buttons.size()+1));
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    TelegramCommands.View list(long user,String prefix,int requested) throws Exception {
        if(!Set.of("mine","craft","use","break","pickup","drop").contains(prefix)) throw new IllegalArgumentException("Неизвестный список статистики");
        var rows=PlayerStatistics.top(data(user),prefix);
        int pages=Math.max(1,(rows.size()+PAGE-1)/PAGE),page=Math.max(0,Math.min(requested,pages-1));
        StringBuilder text=new StringBuilder((prefix.equals("mine")?"⛏ Блоки":"🎒 Предметы")+" · "+(page+1)+"/"+pages);
        if(rows.isEmpty()) text.append("\nПока нет данных.");
        for(int i=page*PAGE;i<Math.min(rows.size(),(page+1)*PAGE);i++) {
            var row=rows.get(i);text.append("\n").append(i+1).append(". ")
                    .append(RussianItems.name(row.getKey().substring(prefix.length()+1))).append(" — ").append(row.getValue());
        }
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        if(page>0) buttons.add(b(user,"⬅️ Назад","statisticsList",prefix,page-1,0));
        if(page+1<pages) buttons.add(b(user,"Вперёд ➡️","statisticsList",prefix,page+1,0));
        buttons.add(b(user,"↩️ К разделу","statisticsSection",prefix.equals("mine")?"blocks":"items",0,1));
        return new TelegramCommands.View(text.toString(),List.copyOf(buttons));
    }
    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("StatisticsMenu");
        scope.on("statistics",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false,(user,a,data)->home(user));
        scope.on("statisticsSection",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false,(user,a,data)->section(user,a.value()));
        scope.on("statisticsList",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.HANDLER,false,(user,a,data)->list(user,a.value(),a.page()));
    }
}
