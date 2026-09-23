package ru.vaulttracker;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses Bastion's console command and verifies its own trusted-player state. */
final class BastionTrust {
    record Outcome(boolean success,String message) {}
    private final JavaPlugin plugin;
    BastionTrust(JavaPlugin plugin) {this.plugin=plugin;}

    boolean available() {
        Plugin bastion=plugin.getServer().getPluginManager().getPlugin("bastion");
        return bastion!=null && bastion.isEnabled();
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
        Plugin bastion=server.getPluginManager().getPlugin("bastion");
        if(bastion==null || !bastion.isEnabled()) return new Outcome(false,"Bastion не установлен или выключен.");
        Player player=server.getPlayer(uuid);
        if(player==null || !player.isOnline()) return new Outcome(false,"Игрок должен быть в сети для команды Bastion.");
        String name=player.getName();
        if(name==null || !name.matches("[A-Za-z0-9_]{1,16}")) return new Outcome(false,"Некорректный игровой ник для команды Bastion.");
        Object manager=bastion.getClass().getMethod("getTrustManager").invoke(bastion);
        if(manager==null) return new Outcome(false,"Список доверенных Bastion недоступен.");
        Method check=manager.getClass().getMethod("isTrusted",UUID.class);
        boolean current=Boolean.TRUE.equals(check.invoke(manager,uuid));
        if(current==trusted) return new Outcome(true,trusted?"Игрок уже доверен Bastion.":"Игрок уже удалён из доверенных Bastion.");
        String command="bastion trust "+(trusted?"add ":"remove ")+name;
        if(!server.dispatchCommand(server.getConsoleSender(),command))
            return new Outcome(false,"Bastion отклонил команду. Проверьте журнал сервера.");
        if(!Boolean.valueOf(trusted).equals(check.invoke(manager,uuid)))
            return new Outcome(false,"Bastion не подтвердил изменение доверия. Проверьте журнал сервера.");
        return new Outcome(true,trusted?"Игрок добавлен в доверенные Bastion.":"Игрок удалён из доверенных Bastion.");
    }
}
