package ru.vaulttracker;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VotingMenuTest {
    @TempDir Path folder;
    GuardService guard;TelegramGuardMenu menu;HttpServer server;List<String> requests=new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            requests.add(path+" "+body);String response=switch(path) {
                case "/voting-providers" -> "{\"providers\":[{\"id\":\"minecraft_rating\",\"name\":\"MinecraftRating\",\"enabled\":true,\"rewardMinor\":500,\"voteUrl\":\"https://minecraftrating.ru/\"}]}";
                case "/voting-admin" -> "{\"providers\":[{\"id\":\"minecraft_rating\",\"name\":\"MinecraftRating\",\"enabled\":true,\"rewardMinor\":500,\"voteUrl\":\"https://minecraftrating.ru/\"},{\"id\":\"disabled\",\"name\":\"Выключенный\",\"enabled\":false,\"rewardMinor\":100,\"voteUrl\":\"\"}]}";
                case "/voting-update" -> "{\"id\":\"minecraft_rating\",\"name\":\"MinecraftRating\",\"enabled\":true,\"rewardMinor\":500,\"voteUrl\":\"\"}";
                case "/voting-stats" -> "{\"total\":12,\"today\":1,\"week\":4,\"month\":9,\"rewardedMinor\":6000,\"pending\":2,\"providers\":[{\"provider\":\"minecraft_rating\",\"votes\":12,\"rewarded\":6000,\"pending\":2}]}";
                case "/wallet" -> "{\"balanceMinor\":0,\"blocked\":false,\"premium\":false}";
                default -> "{}";
            };byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        Files.writeString(folder.resolve("donations.yml"),"enabled: true\napi-key: '"+"x".repeat(48)+"'\nport: "+server.getAddress().getPort()+"\n");
        guard=new GuardService(folder.resolve("guard"),new GuardConfig(true,120000,500),Logger.getAnonymousLogger());
        guard.configure(new GuardConfig(true,120000,500),Set.of(99L));guard.changeSuper(500,501,true).get();
        String code=guard.generate(42).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(UUID.randomUUID(),"Player42",code).get();guard.donationsVisible(500,true).get();
        menu=new TelegramGuardMenu(guard,new TelegramCommands(new Catalogue(v->{}),mock(StorageEngine.class),20));
    }
    @AfterEach void close(){guard.close();server.stop(0);}
    private String button(TelegramCommands.View view,String label){return view.buttons().stream().filter(b->b.text().contains(label)).findFirst().orElseThrow().data();}
    private TelegramCommands.View click(long user,TelegramCommands.View view,String label)throws Exception{return menu.callback(user,button(view,label)).view();}

    @Test void userDonationMenuShowsEnabledProviderRewardAndUrl() throws Exception {
        var donations=menu.cabinet.donations(42);assertNotNull(button(donations,"Голосование"));
        var voting=click(42,donations,"Голосование");assertTrue(voting.text().contains("MinecraftRating"));assertTrue(voting.text().contains("5.00 монет"));
        assertFalse(voting.text().contains("Выключенный"));var link=voting.buttons().stream().filter(b->b.text().contains("Проголосовать")).findFirst().orElseThrow();
        assertEquals("https://minecraftrating.ru/",link.url());assertTrue(click(42,voting,"Пожертвования").text().contains("Пожертвования FLEXITY"));
    }

    @Test void rootEntriesAclRewardValidationStatisticsAndNavigation() throws Exception {
        var root=menu.rootHome(500);assertNotNull(button(root,"Управление пожертвованиями"));String votingData=button(root,"Управление голосованиями");
        assertFalse(menu.adminHome(99).buttons().stream().anyMatch(b->b.text().contains("голосованиями")));
        assertFalse(menu.superHome(501).buttons().stream().anyMatch(b->b.text().contains("голосованиями")));
        assertTrue(menu.callback(99,votingData).alert());
        assertTrue(menu.callback(501,votingData).alert());
        var voting=menu.callback(500,votingData).view();assertTrue(voting.text().contains("Награда: 5.00"));
        var provider=click(500,voting,"MinecraftRating");var toggle=click(500,provider,"Выключить");
        assertFalse(requests.stream().anyMatch(r->r.startsWith("/voting-update")));click(500,toggle,"Подтвердить");
        assertTrue(requests.stream().anyMatch(r->r.startsWith("/voting-update")&&r.contains("false")));
        var prompt=click(500,provider,"Изменить награду");assertTrue(prompt.text().contains("например 5"));
        assertTrue(click(500,prompt,"К ресурсу").text().contains("MinecraftRating"));prompt=click(500,provider,"Изменить награду");
        assertTrue(menu.input(500,"-1").text().contains("от 0"));assertTrue(menu.input(500,"1.234").text().contains("два знака"));
        var confirm=menu.input(500,"7.50");assertTrue(confirm.text().contains("7.50"));String apply=button(confirm,"Подтвердить");
        assertTrue(menu.callback(501,apply).alert());menu.callback(500,apply);assertTrue(requests.stream().anyMatch(r->r.startsWith("/voting-update")&&r.contains("750")));
        var stats=click(500,voting,"Статистика");assertTrue(stats.text().contains("Всего валидных: 12"));assertTrue(stats.text().contains("Начислено: 60.00"));
        assertTrue(click(500,provider,"Статистика ресурса").text().contains("За 30 дней: 9"));
        assertTrue(click(500,stats,"Назад").text().contains("Управление голосованиями"));
        guard.configure(new GuardConfig(true,120000,0),Set.of(99L));assertThrows(SecurityException.class,()->menu.callback(500,votingData));
    }
}
