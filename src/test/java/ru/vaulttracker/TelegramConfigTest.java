package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramConfigTest {
    @TempDir Path directory;
    @Test void createsDisabledSeparateConfigurationByDefault() throws Exception {
        TelegramConfig config=TelegramConfig.load(directory);
        assertFalse(config.enabled()); assertTrue(Files.exists(directory.resolve("telegram.yml")));
        assertEquals(TelegramConfig.ProxyType.NONE,config.proxy().type());
    }
    @Test void loadsAuthenticatedSocksAndRetrySettings() throws Exception {
        Files.writeString(directory.resolve("telegram.yml"),"""
                enabled: true
                token: "123456:abcdefghijklmnopqrstuvwxyz_ABCDE"
                allowedChatIds: [42, -100123]
                adminUserIds: [777]
                pageSize: 6
                messageLifetimeSeconds: 300
                chats:
                  - isDefault: true
                    chatId: -1001000000001
                    topicId: 12345
                advanced:
                  botApiUrl: "https://api.telegram.org"
                  proxy:
                    type: "socks5"
                    host: "proxy.local"
                    port: 1080
                    username: "user"
                    password: "secret"
                  connectionRetry:
                    maxAttempts: 0
                    initialDelay: 500
                    maxDelay: 5000
                """);
        TelegramConfig config=TelegramConfig.load(directory);
        assertTrue(config.enabled());
        assertTrue(config.allowed(-1001000000001L,12345));
        assertFalse(config.allowed(-1001000000001L,12346));
        assertFalse(config.allowed(42,0));
        assertTrue(config.admin(777)); assertFalse(config.admin(778)); assertEquals(6,config.pageSize());
        assertEquals(300,config.messageLifetimeSeconds());
        assertEquals(TelegramConfig.ProxyType.SOCKS5,config.proxy().type()); assertTrue(config.proxy().authenticated());
        assertEquals(0,config.retry().maxAttempts()); assertEquals(5000,config.retry().maxDelay());
    }
    @Test void rejectsUnsafeApiUrlAndIncompleteProxyCredentials() throws Exception {
        Files.writeString(directory.resolve("telegram.yml"),"enabled: true\ntoken: '123456:abcdefghijklmnopqrstuvwxyz_ABCDE'\nadvanced:\n  botApiUrl: 'http://api.telegram.org'\n");
        assertThrows(IllegalArgumentException.class,()->TelegramConfig.load(directory));
        Files.writeString(directory.resolve("telegram.yml"),"enabled: true\ntoken: '123456:abcdefghijklmnopqrstuvwxyz_ABCDE'\nadvanced:\n  proxy:\n    type: http\n    host: proxy.local\n    port: 8080\n    username: user\n");
        assertThrows(IllegalArgumentException.class,()->TelegramConfig.load(directory));
    }
}
