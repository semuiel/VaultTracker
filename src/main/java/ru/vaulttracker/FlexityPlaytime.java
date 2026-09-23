package ru.vaulttracker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

/** Read-only adapter for Flexity's own playtime service. */
final class FlexityPlaytime {
    private final Object service;
    private final Method getPlaytime;
    FlexityPlaytime(Plugin flexity) throws Exception {
        PluginCommand command=flexity.getServer().getPluginCommand("playtime");
        if(command==null||command.getExecutor()==null) throw new IllegalStateException("Команда Flexity playtime недоступна");
        Object executor=command.getExecutor();Field field=null;
        for(Field candidate:executor.getClass().getDeclaredFields()) if(candidate.getType().getName().equals("org.examples.flexity.service.PlaytimeService")) {field=candidate;break;}
        if(field==null) throw new NoSuchFieldException("Flexity PlaytimeService");field.setAccessible(true);service=field.get(executor);
        getPlaytime=service.getClass().getMethod("getPlaytimeAsync",UUID.class);
    }
    CompletableFuture<Long> seconds(UUID uuid) throws Exception {
        return ((CompletableFuture<?>)getPlaytime.invoke(service,uuid)).thenApply(value->value instanceof Number number?number.longValue():0L);
    }
}
