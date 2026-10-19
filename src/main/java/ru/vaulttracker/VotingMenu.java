package ru.vaulttracker;

import com.google.gson.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** User voting links and Root-only operational settings. Provider secrets stay in the Python service. */
final class VotingMenu extends TelegramMenuSection {
    static final long MAX_REWARD_MINOR=100_000_000L;
    VotingMenu(TelegramGuardMenu root) {super(root);}

    TelegramCommands.View userHome(long user) throws Exception {
        var account=guard.account(user).get();
        if(!guard.donationsVisible().get())
            throw new IllegalArgumentException("Сначала свяжите игровой аккаунт и откройте раздел пожертвований.");
        if(account==null)return root.cabinet.linkView(user,true);
        JsonArray providers=guard.donations.votingProviders(account.uuid());List<TelegramCommands.Button> buttons=new ArrayList<>();
        StringBuilder providersText=new StringBuilder();int row=0;
        for(JsonElement element:providers) {
            JsonObject provider=element.getAsJsonObject();String name=provider.get("name").getAsString();
            providersText.append("\n\n").append(name).append(" · +").append(DonationClient.money(provider.get("rewardMinor").getAsLong())).append(" 🪙\n").append(providerTimer(provider));
            String url=provider.has("voteUrl")?provider.get("voteUrl").getAsString():"";
            if(url.matches("https?://.+")) buttons.add(TelegramCommands.Button.link("🗳 Проголосовать · "+name,url,row++));
        }
        if(providers.isEmpty()) providersText.append("\n\nСейчас доступных ресурсов голосования нет.");
        providersText.append("\n\n🏆 Ежемесячные призы\n").append(prizes(guard.donations.monthlySettings()));
        buttons.add(b(user,"🔄 Обновить","votingUser","",0,row++));
        buttons.add(b(user,"🏆 Топ голосов","votingTop","",0,row++));
        buttons.add(b(user,"↩ Free-монеты","freeCoins","",0,row++));buttons.add(menuButton(row));
        return new TelegramCommands.View("🗳 Голосования\n\nГолосуйте за FLEXITY и получайте монеты на общий баланс."+providersText,List.copyOf(buttons))
                .template("voting.user",Map.of("providers",providersText.toString()));
    }

    TelegramCommands.View adminHome(long user) throws Exception {
        guard.requireRoot(user);JsonArray providers=guard.donations.votingProviders(true);List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        StringBuilder text=new StringBuilder("🗳 Управление голосованиями");
        for(JsonElement element:providers) {
            JsonObject provider=element.getAsJsonObject();String id=provider.get("id").getAsString();
            text.append("\n\n").append(provider.get("name").getAsString()).append("\nСтатус: ")
                    .append(provider.get("enabled").getAsBoolean()?"включён":"выключен").append("\nНаграда: ")
                    .append(DonationClient.money(provider.get("rewardMinor").getAsLong())).append(" монет / голос");
            buttons.add(b(user,"🗳 "+provider.get("name").getAsString(),"votingProvider",id,0,row++));
        }
        buttons.add(b(user,"📊 Статистика","votingStats","",0,row++));
        buttons.add(b(user,"🏆 Призы ТОП-3","votingMonthly","",0,row++));
        buttons.add(b(user,"⚖️ При равенстве голосов","votingTie","",0,row++));
        buttons.add(b(user,"↩ Экономика","rootEconomy","",0,row));
        return new TelegramCommands.View(text.toString(),List.copyOf(buttons)).template("voting.admin",Map.of("providers",text.substring("🗳 Управление голосованиями".length())));
    }

    static String timer(long seconds) {if(seconds<=0)return "✅ Доступно";long minutes=(seconds+59)/60;return "⏳ Следующая награда через "+(minutes>=60?minutes/60+" ч ":"")+minutes%60+" мин";}
    static String providerTimer(JsonObject provider) {
        var known=provider.get("eligibilityKnown");var seconds=provider.get("remainingSeconds");
        if(known==null||!known.isJsonPrimitive()||!known.getAsJsonPrimitive().isBoolean()||!known.getAsBoolean()||seconds==null||!seconds.isJsonPrimitive()||!seconds.getAsJsonPrimitive().isNumber()||seconds.getAsLong()<0)return "Статус награды пока недоступен. Обновите позже.";
        return timer(number(provider,"remainingSeconds"));
    }
    static String prizes(JsonObject settings) {
        if(!settings.has("prizesMinor")||settings.getAsJsonArray("prizesMinor").size()!=3)return "Размеры призов пока недоступны.";
        var amounts=settings.getAsJsonArray("prizesMinor");String[] medals={"🥇","🥈","🥉"};var text=new StringBuilder();
        for(int i=0;i<3;i++){if(i>0)text.append('\n');text.append(medals[i]).append(' ').append(i+1).append(" место — ").append(DonationClient.money(amounts.get(i).getAsLong())).append(" 🪙");}
        return text.toString();
    }
    private TelegramCommands.Button menuButton(int row) {return homeButton(row);}
    TelegramCommands.View monthlyHome(long user) throws Exception {
        guard.requireRoot(user);var settings=guard.donations.monthlySettings();
        return new TelegramCommands.View("🏆 Ежемесячные призы\n\n"+prizes(settings)+"\n\nИтоги подводятся автоматически каждый месяц.\nРанги при ничьей: 1, 1, 3 или 1, 2, 2.\nПри равенстве голосов: "+tieName(settings.get("tiePolicy").getAsString()),List.of(
            b(user,"🥇 Изменить 1 место","votingMonthlyPrompt","1",0,0),b(user,"🥈 Изменить 2 место","votingMonthlyPrompt","2",0,1),b(user,"🥉 Изменить 3 место","votingMonthlyPrompt","3",0,2),
            b(user,"⚖️ При равенстве голосов","votingTie","",0,3),b(user,"↩ Назад","votingAdmin","",0,4)));
    }
    static String tieName(String policy) {return policy.equals("split")?"разделить награду":"каждому полная награда";}
    private static int place(String value) {
        int rank=value==null||value.isBlank()?1:Integer.parseInt(value);
        if(rank<1||rank>3)throw new IllegalArgumentException("Выберите место от 1 до 3.");return rank;
    }
    private static String monthlyDescription(String value) {
        if(value.startsWith("prize:")){String[] parts=value.split(":");return place(parts[1])+" место — "+DonationClient.money(Long.parseLong(parts[2]))+" 🪙";}
        return value.startsWith("reward:")?"1 место — "+DonationClient.money(Long.parseLong(value.substring(7)))+" 🪙":tieName(value);
    }
    TelegramCommands.View tieHome(long user) {guard.requireRoot(user);return new TelegramCommands.View("⚖️ При равенстве голосов",List.of(
        b(user,"Каждому полная награда","votingMonthlyConfirm","full",0,0),b(user,"Разделить награду","votingMonthlyConfirm","split",0,1),b(user,"↩ Назад","votingAdmin","",0,2)));}
    TelegramCommands.View monthlyConfirm(long user,String value) {guard.requireRoot(user);return new TelegramCommands.View(
        "Применить к будущим незакрытым месяцам: "+monthlyDescription(value)+"?",List.of(
        b(user,"✅ Подтвердить","votingMonthlyApply",value,0,0),b(user,"↩ Назад","votingMonthly","",0,1)));}
    TelegramCommands.View top(long user,int page) throws Exception {
        if(guard.account(user).get()==null||!guard.donationsVisible().get())throw new IllegalArgumentException("Сначала свяжите аккаунт.");
        var result=guard.donations.voteTop(page);int actual=(int)number(result,"page"),pages=(int)number(result,"pages");
        StringBuilder text=new StringBuilder("🏆 Топ голосов · ").append(result.get("month").getAsString());int position=actual*10+1;
        for(var row:result.getAsJsonArray("rows")){var p=row.getAsJsonObject();text.append("\n").append(p.has("rank")?number(p,"rank"):position).append(". ").append(p.get("nickname").getAsString()).append(" · ").append(number(p,"votes"));position++;}
        if(result.getAsJsonArray("rows").isEmpty())text.append("\nВ этом месяце голосов пока нет.");
        List<TelegramCommands.Button> buttons=new ArrayList<>();if(actual>0)buttons.add(b(user,"◀ Назад","votingTop","",actual-1,0));if(actual+1<pages)buttons.add(b(user,"Вперёд ▶","votingTop","",actual+1,0));
        buttons.add(b(user,"↩ Голосование","votingUser","",0,1));buttons.add(menuButton(2));return new TelegramCommands.View(text.toString(),List.copyOf(buttons));
    }

    private JsonObject provider(long user,String id) throws Exception {
        guard.requireRoot(user);
        for(JsonElement element:guard.donations.votingProviders(true)) {
            JsonObject provider=element.getAsJsonObject();if(provider.get("id").getAsString().equals(id)) return provider;
        }
        throw new IllegalArgumentException("Ресурс голосования не найден.");
    }
    TelegramCommands.View providerHome(long user,String id) throws Exception {
        JsonObject p=provider(user,id);boolean enabled=p.get("enabled").getAsBoolean();String name=p.get("name").getAsString();
        return new TelegramCommands.View("🗳 "+name+"\nСтатус: "+(enabled?"включён":"выключен")+"\nНаграда: "+DonationClient.money(p.get("rewardMinor").getAsLong())+" монет / голос",List.of(
                b(user,enabled?"⛔ Выключить":"✅ Включить","votingToggleConfirm",id+"|"+!enabled,0,0),
                b(user,"💰 Изменить награду","votingRewardPrompt",id,0,1),
                b(user,"📊 Статистика ресурса","votingProviderStats",id,0,2),
                b(user,"↩ Назад","votingAdmin","",0,3))).template("voting.provider",Map.of("name",name,"status",enabled?"включён":"выключен","reward",DonationClient.money(p.get("rewardMinor").getAsLong())));
    }
    TelegramCommands.View toggleConfirm(long user,String value) throws Exception {
        guard.requireRoot(user);String[] parts=value.split("\\|",2);JsonObject p=provider(user,parts[0]);boolean enabled=Boolean.parseBoolean(parts[1]);
        return new TelegramCommands.View((enabled?"Включить":"Выключить")+" голосования через "+p.get("name").getAsString()+"?",List.of(
                b(user,"✅ Подтвердить","votingToggleApply",value,0,0),b(user,"↩ Назад","votingProvider",parts[0],0,1)));
    }
    private static long number(JsonObject object,String key) {return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsLong():0;}
    private static String scope(String provider) {return provider==null?"all":provider;}
    private static String providerFromScope(String scope) {return "all".equals(scope)?null:scope;}
    private static String date(long epoch) {
        if(epoch<=0) return "нет данных";
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }
    private static String average(long amount,long count) {return count==0?"0.00":BigDecimal.valueOf(amount,2).divide(BigDecimal.valueOf(count),2,java.math.RoundingMode.HALF_UP).toPlainString();}
    private String statisticsTitle(long user,String provider) throws Exception {
        return provider==null?"📊 Статистика голосований":"📊 "+this.provider(user,provider).get("name").getAsString();
    }
    TelegramCommands.View statisticsHome(long user,String provider) throws Exception {
        guard.requireRoot(user);JsonObject stats=guard.donations.votingStatistics(provider);String title=statisticsTitle(user,provider);String value=scope(provider);
        String text=title+"\n\nВыберите раздел аналитики.\nВсего голосов: "+number(stats,"total")+" · за 30 дней: "+number(stats,"month")+
                "\nНачислено: "+DonationClient.money(number(stats,"rewardedMinor"))+" монет · ожидают UUID: "+number(stats,"pending");
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        buttons.add(b(user,"📈 Голоса","votingStatsVotes",value,0,row++));
        buttons.add(b(user,"👥 Игроки","votingStatsPlayers",value,0,row++));
        buttons.add(b(user,"💰 Начисления","votingStatsRewards",value,0,row++));
        buttons.add(b(user,"📅 По дням","votingStatsDaily",value,0,row++));
        if(provider==null) for(JsonElement element:guard.donations.votingProviders(true)) {
            JsonObject item=element.getAsJsonObject();buttons.add(b(user,"🗳 "+item.get("name").getAsString(),"votingProviderStats",item.get("id").getAsString(),0,row++));
        }
        buttons.add(b(user,"↩ Назад",provider==null?"votingAdmin":"votingProvider",provider==null?"":provider,0,row));
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }
    TelegramCommands.View statisticsVotes(long user,String provider) throws Exception {
        guard.requireRoot(user);JsonObject s=guard.donations.votingStatistics(provider);String title=statisticsTitle(user,provider)+" · голоса";
        String text=title+"\n\nСегодня: "+number(s,"today")+"\nЗа 7 дней: "+number(s,"week")+"\nЗа 30 дней: "+number(s,"month")+
                "\nЗа всё время: "+number(s,"total")+"\n\nОжидают UUID: "+number(s,"pending")+" голосов от "+number(s,"pendingPlayers")+" игроков"+
                "\nПервый голос: "+date(number(s,"firstReceived"))+"\nПоследний голос: "+date(number(s,"lastReceived"));
        return statisticsBack(user,provider,text);
    }
    TelegramCommands.View statisticsPlayers(long user,String provider) throws Exception {
        guard.requireRoot(user);JsonObject s=guard.donations.votingStatistics(provider);StringBuilder text=new StringBuilder(statisticsTitle(user,provider)).append(" · игроки")
                .append("\n\nУникальных сегодня: ").append(number(s,"uniqueToday")).append("\nЗа 7 дней: ").append(number(s,"uniqueWeek"))
                .append("\nЗа 30 дней: ").append(number(s,"uniqueMonth")).append("\nЗа всё время: ").append(number(s,"uniqueTotal"));
        JsonArray top=s.has("topPlayers")?s.getAsJsonArray("topPlayers"):new JsonArray();
        if(!top.isEmpty()) {text.append("\n\nТоп по голосам:");int place=1;for(JsonElement element:top) {
            JsonObject row=element.getAsJsonObject();text.append("\n").append(place++).append(". ").append(row.get("nickname").getAsString())
                    .append(" — ").append(number(row,"votes")).append(" · ").append(DonationClient.money(number(row,"rewarded"))).append(" монет");
        }}
        return statisticsBack(user,provider,text.toString());
    }
    TelegramCommands.View statisticsRewards(long user,String provider) throws Exception {
        guard.requireRoot(user);JsonObject s=guard.donations.votingStatistics(provider);long total=number(s,"total"),rewarded=number(s,"rewardedMinor");
        String text=statisticsTitle(user,provider)+" · начисления\n\nСегодня: "+DonationClient.money(number(s,"rewardedTodayMinor"))+" монет"+
                "\nЗа 7 дней: "+DonationClient.money(number(s,"rewardedWeekMinor"))+" монет\nЗа 30 дней: "+DonationClient.money(number(s,"rewardedMonthMinor"))+
                " монет\nЗа всё время: "+DonationClient.money(rewarded)+" монет\nСреднее за голос: "+average(rewarded,total)+" монет";
        return statisticsBack(user,provider,text);
    }
    TelegramCommands.View statisticsDaily(long user,String provider) throws Exception {
        guard.requireRoot(user);JsonObject s=guard.donations.votingStatistics(provider);StringBuilder text=new StringBuilder(statisticsTitle(user,provider)).append(" · последние 7 дней");
        JsonArray daily=s.has("daily")?s.getAsJsonArray("daily"):new JsonArray();
        for(JsonElement element:daily) {JsonObject row=element.getAsJsonObject();text.append("\n\n").append(row.get("date").getAsString()).append(": ")
                .append(number(row,"votes")).append(" голосов · ").append(number(row,"unique")).append(" игроков · ")
                .append(DonationClient.money(number(row,"rewardedMinor"))).append(" монет");}
        return statisticsBack(user,provider,text.toString());
    }
    private TelegramCommands.View statisticsBack(long user,String provider,String text) {
        return new TelegramCommands.View(text,List.of(b(user,"↩ К статистике","votingStatsScope",scope(provider),0,0)));
    }
    private static long reward(String text) {
        try {long value=new BigDecimal(text.trim().replace(',','.')).movePointRight(2).longValueExact();if(value<0||value>MAX_REWARD_MINOR) throw new ArithmeticException();return value;}
        catch(NumberFormatException|ArithmeticException invalid) {throw new IllegalArgumentException("Введите сумму от 0 до 1000000, максимум два знака после точки.");}
    }
    TelegramCommands.View onInput(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().startsWith("votingMonthlyReward")){guard.requireRoot(user);long amount;try{amount=reward(text);}catch(IllegalArgumentException invalid){return new TelegramCommands.View(invalid.getMessage());}int place=pending.kind().contains(":")?place(pending.kind().split(":",2)[1]):1;waiting.remove(user);return monthlyConfirm(user,"prize:"+place+":"+amount);}
        if(!pending.kind().startsWith("votingReward:")) return null;
        guard.requireRoot(user);String provider=pending.kind().substring("votingReward:".length());long amount;
        try {amount=reward(text);} catch(IllegalArgumentException invalid) {return new TelegramCommands.View(invalid.getMessage()+" Отмена: /cancel.");}
        waiting.remove(user);JsonObject p=provider(user,provider);
        return new TelegramCommands.View("Установить для "+p.get("name").getAsString()+" награду "+DonationClient.money(amount)+" монет за будущий голос?",List.of(
                b(user,"✅ Подтвердить","votingRewardApply",provider+"|"+amount,0,0),b(user,"↩ Назад","votingProvider",provider,0,1)));
    }
    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("VotingMenu");
        scope.on("votingUser",TelegramActionRegistry.Arguments.NONE,(u,a,d)->userHome(u));
        scope.on("votingTop",TelegramActionRegistry.Arguments.PAGE,(u,a,d)->top(u,a.page()));
        scope.on("votingMonthly",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->monthlyHome(u));
        scope.on("votingTie",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->tieHome(u));
        scope.on("votingMonthlyPrompt",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->{int place=place(a.value());return prompt(u,"votingMonthlyReward:"+place,"Введите месячный приз за "+place+" место в монетах. 0 отключает выплату этого места.");});
        scope.on("votingMonthlyConfirm",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->monthlyConfirm(u,a.value()));
        scope.on("votingMonthlyApply",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(u,a,d)->{guard.requireRoot(u);String value=a.value();
            if(value.startsWith("prize:")){String[] parts=value.split(":");guard.donations.monthlyUpdate(u,Long.parseLong(parts[2]),null,place(parts[1]));}
            else guard.donations.monthlyUpdate(u,value.startsWith("reward:")?Long.parseLong(value.substring(7)):null,value.startsWith("reward:")?null:value);
            return monthlyHome(u);});
        scope.on("votingAdmin",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->adminHome(u));
        scope.on("votingProvider",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->providerHome(u,a.value()));
        scope.on("votingToggleConfirm",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->toggleConfirm(u,a.value()));
        scope.on("votingToggleApply",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(u,a,d)->{
            guard.requireRoot(u);String[] parts=a.value().split("\\|",2);guard.donations.votingUpdate(u,parts[0],Boolean.parseBoolean(parts[1]),null);return providerHome(u,parts[0]);});
        scope.on("votingRewardPrompt",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->{
            guard.requireRoot(u);return prompt(u,"votingReward:"+a.value(),"Введите награду за один голос в монетах, например 5 или 5.50.");});
        scope.on("votingRewardApply",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(u,a,d)->{
            guard.requireRoot(u);String[] parts=a.value().split("\\|",2);guard.donations.votingUpdate(u,parts[0],null,Long.parseLong(parts[1]));return providerHome(u,parts[0]);});
        scope.on("votingStats",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->statisticsHome(u,null));
        scope.on("votingProviderStats",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->statisticsHome(u,a.value()));
        scope.on("votingStatsScope",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->statisticsHome(u,providerFromScope(a.value())));
        scope.on("votingStatsVotes",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->statisticsVotes(u,providerFromScope(a.value())));
        scope.on("votingStatsPlayers",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->statisticsPlayers(u,providerFromScope(a.value())));
        scope.on("votingStatsRewards",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->statisticsRewards(u,providerFromScope(a.value())));
        scope.on("votingStatsDaily",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->statisticsDaily(u,providerFromScope(a.value())));
    }
}
