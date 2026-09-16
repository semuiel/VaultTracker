package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import java.sql.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class LinkedChatIdentityTest {
    static class FakeFlexity extends JavaPlugin {Object bootstrap;}
    static class Bootstrap {Object chatFormatter;Object serviceRegistry;}
    public static class Services {
        Settings settings=new Settings();Database database=new Database();
        public Settings getSettingsService(){return settings;}
        public Database getDatabaseService(){return database;}
    }
    public static class Settings {Map<UUID,Style> cache=new java.util.concurrent.ConcurrentHashMap<>();}
    public static class Style {
        public String getCustomPrefix(){return "<gold>VIP</gold>";}
        public String getCustomNickColor(){return "<green>";}
    }
    public static class Database {Connection connection;public Connection getConnection(){return connection;}}
    public static class Formatter {
        Component received;String color;
        public Component format(boolean global,String nickname,Component message,String nickColor) {
            assertTrue(global);received=message;color=nickColor;
            return Component.text("[G] "+nickname+": ").append(message);
        }
    }
    @Test void usesCachedFlexityStyleAndKeepsTelegramTextLiteral() throws Exception {
        UUID uuid=UUID.randomUUID();Services services=new Services();services.settings.cache.put(uuid,new Style());Formatter formatter=new Formatter();
        LinkedChatIdentity identity=adapter(services,formatter);
        String malicious="<click:run_command:'/op me'>Hi</click>";
        Component output=identity.render(new GuardService.Account(uuid,"Alex",true),malicious);
        assertEquals("[TG] VIP [G] Alex: "+malicious,TelegramChatBridge.plain(output));
        assertEquals(Component.text(malicious),formatter.received);assertEquals("<green>",formatter.color);
    }
    @Test void loadsOfflineStyleFromFlexityDatabaseAndClosesResources() throws Exception {
        Services services=new Services();Connection connection=mock(Connection.class);PreparedStatement query=mock(PreparedStatement.class);ResultSet rows=mock(ResultSet.class);
        services.database.connection=connection;when(connection.prepareStatement(anyString())).thenReturn(query);when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);when(rows.getString(1)).thenReturn("VIP");when(rows.getString(2)).thenReturn("<gold>");
        UUID uuid=UUID.randomUUID();var style=adapter(services,new Formatter()).style(uuid);
        assertEquals("VIP",style.prefix());assertEquals("<gold>",style.color());verify(query).setString(1,uuid.toString());verify(connection).close();verify(query).close();verify(rows).close();
    }
    private LinkedChatIdentity adapter(Services services,Formatter formatter) throws Exception {
        FakeFlexity plugin=mock(FakeFlexity.class);Bootstrap bootstrap=new Bootstrap();bootstrap.serviceRegistry=services;bootstrap.chatFormatter=formatter;plugin.bootstrap=bootstrap;return new LinkedChatIdentity(plugin);
    }
}
