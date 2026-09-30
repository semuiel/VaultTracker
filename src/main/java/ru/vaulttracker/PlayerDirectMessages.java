package ru.vaulttracker;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** One player-to-player flow for Minecraft and Telegram; GuardService owns the durable history. */
final class PlayerDirectMessages {
    private final JavaPlugin plugin;
    private final GuardService guard;
    PlayerDirectMessages(JavaPlugin plugin,GuardService guard) {this.plugin=plugin;this.guard=guard;}

    CompletableFuture<String> fromGame(UUID sender,String senderName,String targetName,String text) {
        return guard.linkedPlayer(targetName).thenCompose(target->{
            if(target==null) return CompletableFuture.failedFuture(new IllegalArgumentException("Игрок не найден среди привязанных к Telegram."));
            return send(sender,senderName,target.uuid(),target.name(),text,true);
        });
    }
    CompletableFuture<String> fromTelegram(long user,UUID target,String text) {
        return guard.account(user).thenCompose(sender->{
            if(sender==null) return CompletableFuture.failedFuture(new SecurityException("Сначала привяжите персонажа"));
            return guard.linkedPlayer(target).thenCompose(recipient->{
                if(recipient==null) return CompletableFuture.failedFuture(new IllegalArgumentException("У получателя нет связанного Telegram-аккаунта."));
                return send(sender.uuid(),sender.name(),recipient.uuid(),recipient.name(),text,false);
            });
        });
    }
    private CompletableFuture<String> send(UUID sender,String name,UUID recipient,String recipientName,String text,boolean fromGame) {
        return guard.directMessage(sender,name,recipient,recipientName,text,fromGame).thenCompose(saved->
            deliver(plugin,recipient,message(name,text,false))
                .thenCompose(online->online?guard.directDelivered(saved.id(),recipient).thenApply(ignored->"Сообщение доставлено в игру.")
                        :CompletableFuture.completedFuture("Сообщение сохранено. Игрок увидит его в Telegram-кабинете.")));
    }
    static Component message(String other,String text,boolean outgoing) {
        Component nickname=Component.text(other,NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand("/tg "+other+" "))
                .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы ответить",NamedTextColor.GRAY)));
        return LinkedChatIdentity.tag(true).append(Component.text("[",NamedTextColor.GRAY)
                .append(Component.text("✉",NamedTextColor.BLUE))
                .append(Component.text("] Вы",NamedTextColor.GRAY))
                .append(Component.text(outgoing?" ⮕ ":" ⬅ ",NamedTextColor.BLUE))
                .append(nickname)
                .append(Component.text(": ",NamedTextColor.GRAY))
                .append(Component.text(text,NamedTextColor.WHITE)));
    }
    static CompletableFuture<Boolean> deliver(JavaPlugin plugin,UUID recipient,Component message) {
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        try {plugin.getServer().getGlobalRegionScheduler().run(plugin,task->{
            Player player=plugin.getServer().getPlayer(recipient);
            if(player==null) {result.complete(false);return;}
            try {var scheduled=player.getScheduler().run(plugin,t->{
                if(player.isOnline()) {player.sendMessage(message);result.complete(true);}
                else result.complete(false);
            },()->result.complete(false));
                if(scheduled==null) result.complete(false);
            } catch(Exception unavailable) {result.complete(false);}
        });} catch(Exception unavailable) {result.complete(false);}
        return result.orTimeout(15,TimeUnit.SECONDS).exceptionally(ignored->false);
    }
}
