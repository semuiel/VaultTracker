package ru.vaulttracker;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.threadedregions.scheduler.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewsAnnouncementsTest {
    @TempDir Path folder;
    JavaPlugin plugin=mock(JavaPlugin.class);
    Server server=mock(Server.class);
    GlobalRegionScheduler scheduler=mock(GlobalRegionScheduler.class);
    ConsoleCommandSender console=mock(ConsoleCommandSender.class);
    TelegramModerationRepository repository;
    List<Component> messages=new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        repository=new TelegramModerationRepository(folder);when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);when(server.getOnlinePlayers()).thenReturn(List.of());when(server.getConsoleSender()).thenReturn(console);
        doAnswer(call->{call.getArgument(1,Consumer.class).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);}).when(scheduler).run(eq(plugin),any());
        doAnswer(call->{messages.add(call.getArgument(0,Component.class));return null;}).when(console).sendMessage(any(Component.class));
    }
    @Test void permission_denied_and_no_previous_news() throws Exception {
        var news=new NewsAnnouncements(plugin,repository);
        news.onCommand(console,mock(Command.class),"nr",new String[0]);
        verify(console).sendMessage(argThat((Component value)->plain(value).contains("Нет права")));verifyNoInteractions(scheduler);
        when(console.hasPermission("vaulttracker.news.repeat")).thenReturn(true);
        news.onCommand(console,mock(Command.class),"nr",new String[0]);
        verify(console,timeout(5000)).sendMessage(argThat((Component value)->plain(value).contains("пока нет")));
        assertNull(repository.news());
    }
    @Test void news_and_poll_repeat_same_format_url_and_persist_after_restart() throws Exception {
        when(console.hasPermission("vaulttracker.news.repeat")).thenReturn(true);
        var automatic=new NewsAnnouncements(plugin,repository);
        for(boolean poll:new boolean[]{false,true}) {
            String url="https://t.me/c/2669159447/206056/"+(poll?301:300);
            automatic.accept(poll,url);Component first=messages.getLast();
            var restarted=new NewsAnnouncements(plugin,new TelegramModerationRepository(folder));int count=messages.size();
            restarted.onCommand(console,mock(Command.class),"nr",new String[0]);
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(messages.size()==count&&System.nanoTime()<limit)Thread.sleep(10);
            assertEquals(count+1,messages.size());assertEquals(first,messages.getLast());
            assertTrue(plain(first).contains(poll?"важное голосование":"важная новость"));
            assertTrue(hasLink(first,url));assertEquals(new TelegramModerationRepository.News(poll,url),repository.news());
        }
    }
    private static String plain(Component message){return PlainTextComponentSerializer.plainText().serialize(message);}
    private static boolean hasLink(Component c,String url){return c.clickEvent()!=null&&c.clickEvent().value().equals(url)||c.children().stream().anyMatch(child->hasLink(child,url));}
}
