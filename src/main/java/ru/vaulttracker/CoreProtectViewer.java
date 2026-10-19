package ru.vaulttracker;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.function.BooleanSupplier;

/** Optional integration: only reads plugin metadata and API availability, never a game world or CP write API. */
final class CoreProtectViewer {
    private CoreProtectViewer() {}
    static CoreProtectWebServer create(JavaPlugin plugin,TelegramConfig telegram,TelegramApi api,GuardService guard,TelegramModeration moderation) {
        return create(plugin,telegram,api,guard,moderation,null);
    }
    static CoreProtectWebServer create(JavaPlugin plugin,TelegramConfig telegram,TelegramApi api,GuardService guard,TelegramModeration moderation,TelegramWebRuntime runtime) {
        CoreProtectWebConfig c;
        try {c=CoreProtectWebConfig.load(plugin.getDataFolder().toPath());}
        catch(Exception invalid) {plugin.getLogger().warning("CoreProtect viewer выключен: проверьте секцию coreprotectWeb в telegram.yml.");return null;}
        if(!c.enabled()) return null;
        try {
            Plugin cp=plugin.getServer().getPluginManager().getPlugin("CoreProtect");
            if(cp==null||!cp.isEnabled()||!"24.1".equals(cp.getPluginMeta().getVersion())) throw new IllegalStateException();
            Object cpApi=cp.getClass().getMethod("getAPI").invoke(cp);
            if(!Integer.valueOf(12).equals(cpApi.getClass().getMethod("APIVersion").invoke(cpApi))) throw new IllegalStateException();
            var enabled=cpApi.getClass().getMethod("isEnabled");
            BooleanSupplier available=()-> {try {return cp.isEnabled()&&Boolean.TRUE.equals(enabled.invoke(cpApi));}catch(Exception failure) {return false;}};
            if(!available.getAsBoolean()) throw new IllegalStateException();
            var source=new CoreProtectMariaDbSource(c,available);
            var server=new CoreProtectWebServer(c,telegram.token(),source,guard::admin,api::chatAdministrator,moderation::audit,System::currentTimeMillis,runtime);
            server.diagnostics(plugin.getLogger()::warning);return server;
        } catch(Exception | LinkageError unavailable) {
            plugin.getLogger().warning("CoreProtect viewer недоступен: требуется CoreProtect 24.1 / API 12, включённый API и свободный localhost-порт.");return null;
        }
    }
}
