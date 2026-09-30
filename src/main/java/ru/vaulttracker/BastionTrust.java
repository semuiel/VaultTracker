package ru.vaulttracker;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses only Bastion's public console command. */
final class BastionTrust {
    record Outcome(boolean success,String message) {}
    private final JavaPlugin plugin;
    BastionTrust(JavaPlugin plugin) {this.plugin=plugin;}

    boolean available() {
        Plugin bastion=findBastion();
        return bastion!=null && bastion.isEnabled();
    }

    private Plugin findBastion() {
        var manager=plugin.getServer().getPluginManager();
        Plugin bastion=manager.getPlugin("bastion");
        if(bastion!=null) return bastion;
        for(Plugin candidate:manager.getPlugins())
            if(candidate!=null && "bastion".equalsIgnoreCase(candidate.getName())) return candidate;
        return null;
    }

    CompletableFuture<Outcome> change(UUID uuid,boolean trusted) {
        return change(uuid,trusted,()->{});
    }

    CompletableFuture<Outcome> change(UUID uuid,boolean trusted,Runnable authorize) {
        CompletableFuture<Outcome> result=new CompletableFuture<>();
        plugin.getServer().getGlobalRegionScheduler().run(plugin,task->{
            try {authorize.run();result.complete(apply(uuid,trusted));}
            catch(Exception failure) {
                plugin.getLogger().warning("Не удалось изменить доверие Bastion для "+uuid+": "+failure.getClass().getSimpleName());
                result.complete(new Outcome(false,"Не удалось изменить доверие Bastion. Проверьте журнал сервера."));
            }
        });
        return result.orTimeout(15,TimeUnit.SECONDS);
    }

    private Outcome apply(UUID uuid,boolean trusted) throws Exception {
        Server server=plugin.getServer();
        Plugin bastion=findBastion();
        if(bastion==null || !bastion.isEnabled()) return new Outcome(false,"Bastion не установлен или выключен.");
        Player player=server.getPlayer(uuid);
        if(player==null || !player.isOnline()) return new Outcome(false,"Игрок должен быть в сети для команды Bastion.");
        String name=player.getName();
        if(name==null || !name.matches("[A-Za-z0-9_]{1,16}")) return new Outcome(false,"Некорректный игровой ник для команды Bastion.");
        String command="bastion trust "+(trusted?"add ":"remove ")+name;
        if(!server.dispatchCommand(server.getConsoleSender(),command))
            return new Outcome(false,"Bastion отклонил команду. Проверьте журнал сервера.");
        return new Outcome(true,trusted?"Игрок добавлен в доверенные Bastion.":"Игрок удалён из доверенных Bastion.");
    }
}
