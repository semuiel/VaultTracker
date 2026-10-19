package ru.vaulttracker;

import java.math.BigDecimal;
import java.util.*;

/** Existing voting and linking flows; reward eligibility belongs to the UUID ledger. */
final class FreeCoinsMenu extends TelegramMenuSection {
    FreeCoinsMenu(TelegramGuardMenu root) {super(root);}
    private void visible() throws Exception {
        if(!guard.donationsVisible().get())throw new IllegalArgumentException("Раздел пожертвований недоступен.");
    }
    TelegramCommands.View home(long user) throws Exception {
        visible();var account=guard.account(user).get();var buttons=new ArrayList<TelegramCommands.Button>();
        buttons.add(b(user,"🗳 Голосование","votingUser","",0,0));
        boolean claimed=false;String message="Голосуйте за сервер и получите разовую награду за связь Minecraft с Telegram.";
        if(account!=null) {
            try {var wallet=guard.donations.wallet(account.uuid());claimed=wallet.has("linkRewardClaimed")&&wallet.get("linkRewardClaimed").getAsBoolean();}
            catch(Exception unavailable) {message+="\nПроверка награды временно недоступна. Повторите позже.";}
        }
        if(!claimed)buttons.add(b(user,"🎁 Награда за привязку","linkRewardClaim","",0,1));
        buttons.add(b(user,"↩ Пожертвования","donations","",0,2));buttons.add(homeButton(3));
        return new TelegramCommands.View("🪙 Free-монеты\n\n"+message,List.copyOf(buttons))
                .template("donations.free",Map.of("message",message));
    }
    TelegramCommands.View claim(long user) throws Exception {
        visible();var account=guard.account(user).get();
        if(account==null)return root.cabinet.linkView(user,true);
        var result=guard.donations.claimLinkReward(account,user);var next=home(user);
        if(!result.get("alreadyClaimed").getAsBoolean())BotAnalytics.success("link","link.reward");
        String message=result.get("alreadyClaimed").getAsBoolean()?"Награда за этот игровой аккаунт уже получена.":
                "🎁 Зачислено за привязку: "+DonationClient.money(result.get("amountMinor").getAsLong())+" 🪙.";
        return new TelegramCommands.View(message+"\nБаланс: "+DonationClient.money(result.get("balanceMinor").getAsLong()),next.buttons());
    }
    String adminLabel(long user) {
        guard.requireRoot(user);
        try {return "🎁 Награда за привязку: "+new BigDecimal(DonationClient.money(guard.donations.linkRewardSettings().get("rewardMinor").getAsLong())).stripTrailingZeros().toPlainString();}
        catch(Exception unavailable) {return "🎁 Награда за привязку: недоступна";}
    }
    TelegramCommands.View onInput(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(!pending.kind().equals("linkRewardAmount"))return null;
        guard.requireRoot(user);long amount;
        try {amount=new BigDecimal(text.trim().replace(',','.')).movePointRight(2).longValueExact();
            if(amount<0||amount>VotingMenu.MAX_REWARD_MINOR)throw new ArithmeticException();}
        catch(NumberFormatException|ArithmeticException invalid) {return new TelegramCommands.View("Введите сумму от 0 до 1000000, максимум два знака после точки. Отмена: /cancel.");}
        waiting.remove(user);
        return new TelegramCommands.View("Установить награду "+DonationClient.money(amount)+" 🪙 для будущих получений?",List.of(
                b(user,"✅ Подтвердить","linkRewardAmountApply",Long.toString(amount),0,0),b(user,"↩ Назад","donationAdmin","",0,1)));
    }
    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("FreeCoinsMenu");
        scope.on("freeCoins",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false,(u,a,d)->home(u));
        // Retries use UUID + LINK_REWARD, even when the callback or Telegram account changes.
        scope.on("linkRewardClaim",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false,(u,a,d)->claim(u));
        scope.on("linkRewardAmountPrompt",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.ROOT,false,(u,a,d)->{
            guard.requireRoot(u);return prompt(u,"linkRewardAmount","Введите разовую награду за привязку в монетах, например 150 или 150.50.");});
        scope.on("linkRewardAmountApply",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.ROOT,true,(u,a,d)->{
            guard.requireRoot(u);guard.donations.linkRewardUpdate(u,Long.parseLong(a.value()));return root.cabinet.donationAdmin(u);});
    }
}
