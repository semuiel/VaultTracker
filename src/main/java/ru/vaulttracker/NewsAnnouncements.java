package ru.vaulttracker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Automatic and manual announcements share one formatter and one broadcast path. */
final class NewsAnnouncements implements CommandExecutor {
    private final JavaPlugin plugin;
    private final TelegramModerationRepository repository;
    NewsAnnouncements(JavaPlugin plugin,TelegramModerationRepository repository){this.plugin=plugin;this.repository=repository;}
    void accept(boolean poll,String url) throws Exception {
        var news=new TelegramModerationRepository.News(poll,url);repository.news(news);broadcast(news);
    }
    static Component body(TelegramModerationRepository.News news) {
        return Component.text(news.poll()?"Внимание, важное голосование! ":"Внимание, важная новость! ").append(
            Component.text(news.poll()?"Открыть и проголосовать ↗":"Открыть и прочитать ↗",NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.openUrl(news.url()))
                .hoverEvent(HoverEvent.showText(Component.text("Открыть пост в Telegram"))));
    }
    private void broadcast(TelegramModerationRepository.News news) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin,task->{
            Component message=GameMessages.format(body(news),GameMessages.Tone.WARNING);
            for(var player:plugin.getServer().getOnlinePlayers())player.getScheduler().run(plugin,t->player.sendMessage(message),null);
            plugin.getServer().getConsoleSender().sendMessage(message);
        });
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!sender.hasPermission("vaulttracker.news.repeat")){GameMessages.send(sender,"Нет права повторять новости.",GameMessages.Tone.ERROR);return true;}
        Thread.ofVirtual().name("VaultTracker-news-repeat").start(()->{
            try {var latest=repository.news();if(latest!=null){broadcast(latest);return;}reply(sender,"Последней новости пока нет.");}
            catch(Exception error){reply(sender,"Не удалось прочитать последнюю новость.");}
        });return true;
    }
    private void reply(CommandSender sender,String text){
        if(sender instanceof org.bukkit.entity.Player player)player.getScheduler().run(plugin,t->GameMessages.send(player,text,GameMessages.Tone.INFO),null);
        else plugin.getServer().getGlobalRegionScheduler().run(plugin,t->GameMessages.send(sender,text,GameMessages.Tone.INFO));
    }
}
