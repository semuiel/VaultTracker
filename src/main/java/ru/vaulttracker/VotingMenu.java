package ru.vaulttracker;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

/** User voting links and Root-only operational settings. Provider secrets stay in the Python service. */
final class VotingMenu extends TelegramMenuSection {
    static final long MAX_REWARD_MINOR=100_000_000L;
    VotingMenu(TelegramGuardMenu root) {super(root);}

    TelegramCommands.View userHome(long user) throws Exception {
        if(guard.account(user).get()==null || !guard.donationsVisible().get())
            throw new IllegalArgumentException("Сначала свяжите игровой аккаунт и откройте раздел пожертвований.");
        JsonArray providers=guard.donations.votingProviders(false);List<TelegramCommands.Button> buttons=new ArrayList<>();
        StringBuilder providersText=new StringBuilder();int row=0;
        for(JsonElement element:providers) {
            JsonObject provider=element.getAsJsonObject();String name=provider.get("name").getAsString();
            providersText.append("\n\n").append(name).append("\nНаграда: ").append(DonationClient.money(provider.get("rewardMinor").getAsLong())).append(" монет за голос");
            String url=provider.has("voteUrl")?provider.get("voteUrl").getAsString():"";
            if(url.matches("https?://.+")) buttons.add(TelegramCommands.Button.link("🗳 Проголосовать · "+name,url,row++));
        }
        if(providers.isEmpty()) providersText.append("\n\nСейчас доступных ресурсов голосования нет.");
        buttons.add(b(user,"↩ Пожертвования","donations","",0,row));
        return new TelegramCommands.View("🗳 Голосование\n\nГолосуйте за FLEXITY и получайте монеты на общий баланс."+providersText,List.copyOf(buttons))
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
        buttons.add(b(user,"📊 Статистика","votingStats","",0,row++));buttons.add(b(user,"↩ Root","rootHome","",0,row));
        return new TelegramCommands.View(text.toString(),List.copyOf(buttons)).template("voting.admin",Map.of("providers",text.substring("🗳 Управление голосованиями".length())));
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
    TelegramCommands.View statistics(long user,String provider) throws Exception {
        guard.requireRoot(user);JsonObject stats=guard.donations.votingStatistics(provider);
        String title=provider==null?"📊 Статистика голосований":"📊 "+this.provider(user,provider).get("name").getAsString();
        StringBuilder text=new StringBuilder(title).append("\nВсего валидных: ").append(stats.get("total").getAsLong())
                .append("\nСегодня: ").append(stats.get("today").getAsLong()).append("\nЗа 7 дней: ").append(stats.get("week").getAsLong())
                .append("\nЗа 30 дней: ").append(stats.get("month").getAsLong()).append("\nНачислено: ")
                .append(DonationClient.money(stats.get("rewardedMinor").getAsLong())).append(" монет")
                .append("\nОжидают UUID: ").append(stats.get("pending").getAsLong());
        if(provider==null) for(JsonElement element:stats.getAsJsonArray("providers")) {
            JsonObject row=element.getAsJsonObject();String name=row.get("provider").getAsString();
            try{name=this.provider(user,name).get("name").getAsString();}catch(Exception ignored){}
            text.append("\n\n").append(name).append(": ").append(row.get("votes").getAsLong()).append(" голосов · ")
                    .append(DonationClient.money(row.get("rewarded").getAsLong())).append(" монет · pending ").append(row.get("pending").getAsLong());
        }
        return new TelegramCommands.View(text.toString(),List.of(b(user,"↩ Назад",provider==null?"votingAdmin":"votingProvider",provider==null?"":provider,0,0)))
                .template("voting.statistics",Map.of("title",title,"statistics",text.substring(title.length())));
    }
    private static long reward(String text) {
        try {long value=new BigDecimal(text.trim().replace(',','.')).movePointRight(2).longValueExact();if(value<0||value>MAX_REWARD_MINOR) throw new ArithmeticException();return value;}
        catch(NumberFormatException|ArithmeticException invalid) {throw new IllegalArgumentException("Введите сумму от 0 до 1000000, максимум два знака после точки.");}
    }
    TelegramCommands.View onInput(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
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
        scope.on("votingAdmin",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->adminHome(u));
        scope.on("votingProvider",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->providerHome(u,a.value()));
        scope.on("votingToggleConfirm",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->toggleConfirm(u,a.value()));
        scope.on("votingToggleApply",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(u,a,d)->{
            guard.requireRoot(u);String[] parts=a.value().split("\\|",2);guard.donations.votingUpdate(u,parts[0],Boolean.parseBoolean(parts[1]),null);return providerHome(u,parts[0]);});
        scope.on("votingRewardPrompt",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->{
            guard.requireRoot(u);return prompt(u,"votingReward:"+a.value(),"Введите награду за один голос в монетах, например 5 или 5.50.");});
        scope.on("votingRewardApply",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(u,a,d)->{
            guard.requireRoot(u);String[] parts=a.value().split("\\|",2);guard.donations.votingUpdate(u,parts[0],null,Long.parseLong(parts[1]));return providerHome(u,parts[0]);});
        scope.on("votingStats",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->statistics(u,null));
        scope.on("votingProviderStats",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->statistics(u,a.value()));
    }
}
