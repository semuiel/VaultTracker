package ru.vaulttracker;

import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;

record GuardConfig(boolean enabled,long absenceMillis) {
    static GuardConfig load(Path folder) throws Exception {
        Path file=folder.resolve("guard.yml");
        if(!Files.exists(file)) try(var in=GuardConfig.class.getResourceAsStream("/guard.yml")) {
            Files.copy(java.util.Objects.requireNonNull(in),file);
        }
        var yaml=new YamlConfiguration(); yaml.load(file.toFile());
        long seconds=yaml.getLong("offline-seconds",120);
        if(seconds<0 || seconds>31_536_000) throw new IllegalArgumentException("guard.yml: offline-seconds должен быть от 0 до 31536000");
        return new GuardConfig(yaml.getBoolean("enabled",true),seconds*1000);
    }
}
