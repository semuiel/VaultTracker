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
                case "/voting-stats" -> "{\"total\":12,\"today\":1,\"week\":4,\"month\":9,\"rewardedMinor\":6000,\"rewardedTodayMinor\":500,\"rewardedWeekMinor\":2000,\"rewardedMonthMinor\":4500,\"uniqueTotal\":5,\"uniqueToday\":1,\"uniqueWeek\":3,\"uniqueMonth\":4,\"pending\":2,\"pendingPlayers\":1,\"firstReceived\":1700000000,\"lastReceived\":1700100000,\"topPlayers\":[{\"nickname\":\"Player42\",\"votes\":4,\"rewarded\":2000,\"lastReceived\":1700100000}],\"daily\":[{\"date\":\"30.09\",\"votes\":4,\"unique\":3,\"rewardedMinor\":2000}],\"providers\":[{\"provider\":\"minecraft_rating\",\"votes\":12,\"today\":1,\"week\":4,\"month\":9,\"rewarded\":6000,\"rewardedToday\":500,\"rewardedWeek\":2000,\"rewardedMonth\":4500,\"uniqueTotal\":5,\"uniqueMonth\":4,\"pending\":2,\"pendingPlayers\":1}]}";
                case "/wallet" -> "{\"balanceMinor\":0,\"blocked\":false,\"premium\":false}";
                case "/voting-month-settings", "/voting-month-update" -> "{\"rewardMinor\":10000,\"prizesMinor\":[10000,20000,10000],\"tiePolicy\":\"full\"}";
                case "/voting-top" -> "{\"month\":\"2026-10\",\"page\":0,\"pages\":2,\"rows\":[{\"owner\":\"unused\",\"nickname\":\"Player42\",\"votes\":8}]}";
                case "/donation-stats" -> "{\"periods\":{\"today\":{\"paymentCount\":2,\"paymentMinor\":40000,\"confirmedMinor\":30000,\"refundCount\":1,\"refundMinor\":10000,\"reviewCount\":1,\"reviewMinor\":200,\"premiumPurchases\":1,\"premiumPurchaseMinor\":60000,\"premiumGrants\":2,\"donors\":2,\"manualCount\":3,\"manualMinor\":100}}}";
                default -> "{}";
            };
            if(path.equals("/voting-providers")||path.equals("/voting-admin")) {
                var json=com.google.gson.JsonParser.parseString(response).getAsJsonObject();
                var top=new com.google.gson.JsonObject();
                top.addProperty("id","top_minecrafter");top.addProperty("name","Top-Minecrafter");
                top.addProperty("enabled",true);top.addProperty("rewardMinor",300);
                top.addProperty("voteUrl","https://top-minecrafter.com/server/flexity/");
                json.getAsJsonArray("providers").add(top);response=json.toString();
                var hot=new com.google.gson.JsonObject();
                hot.addProperty("id","hotmc");hot.addProperty("name","HotMC");
                hot.addProperty("enabled",true);hot.addProperty("rewardMinor",400);
                hot.addProperty("voteUrl","https://hotmc.ru/vote-281777");
                json.getAsJsonArray("providers").add(hot);response=json.toString();
                if(path.equals("/voting-providers"))for(var item:json.getAsJsonArray("providers")) {
                    var p=item.getAsJsonObject();p.addProperty("eligibilityKnown",true);p.addProperty("remainingSeconds",0);
                }
                response=json.toString();
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
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
        var donations=menu.cabinet.donations(42);assertFalse(donations.buttons().stream().anyMatch(b->b.text().contains("Голосование")));
        var free=click(42,donations,"Free-монеты");assertNotNull(button(free,"Награда за привязку"));
        var voting=click(42,free,"Голосование");assertTrue(voting.text().contains("MinecraftRating"));assertTrue(voting.text().contains("+5.00 🪙\n✅ Доступно"));
        assertTrue(voting.text().contains("🥈 2 место — 200.00"));assertNotNull(button(voting,"Обновить"));
        assertFalse(voting.buttons().stream().anyMatch(b->b.text().contains("В кабинет")));
        assertNotNull(button(voting,"Топ голосов"));assertNotNull(button(voting,"Меню"));
        assertFalse(voting.text().contains("Выключенный"));var link=voting.buttons().stream().filter(b->b.text().contains("Проголосовать")).findFirst().orElseThrow();
        assertEquals("https://minecraftrating.ru/",link.url());assertTrue(click(42,click(42,voting,"Free-монеты"),"Пожертвования").text().contains("Пожертвования FLEXITY"));
    }

    @Test void rootEntriesAclRewardValidationStatisticsAndNavigation() throws Exception {
        var root=click(500,menu.rootHome(500),"Экономика");assertNotNull(button(root,"Пожертвования и премиум"));String votingData=button(root,"Голосования и награды");
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
        var stats=click(500,voting,"Статистика");assertTrue(stats.text().contains("Выберите раздел аналитики"));assertTrue(stats.text().contains("Начислено: 60.00"));
        assertTrue(click(500,stats,"Голоса").text().contains("За 30 дней: 9"));
        assertTrue(click(500,stats,"Игроки").text().contains("Player42 — 4"));
        assertTrue(click(500,stats,"Начисления").text().contains("За 7 дней: 20.00"));
        assertTrue(click(500,stats,"По дням").text().contains("30.09: 4 голосов"));
        var providerStats=click(500,provider,"Статистика ресурса");assertTrue(providerStats.text().contains("Выберите раздел аналитики"));
        assertTrue(click(500,providerStats,"Голоса").text().contains("За 30 дней: 9"));
        assertTrue(click(500,stats,"Назад").text().contains("Управление голосованиями"));
        guard.configure(new GuardConfig(true,120000,0),Set.of(99L));assertThrows(SecurityException.class,()->menu.callback(500,votingData));
    }

    @Test void secondSiteHasPlayerLinkAndIndependentRootActions() throws Exception {
        var voting=menu.voting.userHome(42);
        var top=voting.buttons().stream().filter(b->b.text().contains("Top-Minecrafter")).findFirst().orElseThrow();
        assertEquals("https://top-minecrafter.com/server/flexity/",top.url());
        assertTrue(voting.text().contains("+3.00 🪙"));
        var root=menu.voting.adminHome(500);
        var provider=click(500,root,"Top-Minecrafter");
        assertTrue(provider.text().contains("3.00"));
        click(500,provider,"Статистика ресурса");
        assertTrue(requests.stream().anyMatch(r->r.startsWith("/voting-stats")&&r.contains("top_minecrafter")));
        click(500,provider,"Изменить награду");
        click(500,menu.input(500,"8"),"Подтвердить");
        assertTrue(requests.stream().anyMatch(r->r.startsWith("/voting-update")&&r.contains("top_minecrafter")&&r.contains("800")));
    }

    @Test void hotMcHasPlayerLinkAndIndependentRootActions() throws Exception {
        var voting=menu.voting.userHome(42);
        var hot=voting.buttons().stream().filter(b->b.text().contains("HotMC")).findFirst().orElseThrow();
        assertEquals("https://hotmc.ru/vote-281777",hot.url());
        assertTrue(voting.text().contains("+4.00 🪙"));
        var root=menu.voting.adminHome(500);
        var provider=click(500,root,"HotMC");
        assertTrue(provider.text().contains("4.00"));
        click(500,provider,"Статистика ресурса");
        assertTrue(requests.stream().anyMatch(r->r.startsWith("/voting-stats")&&r.contains("hotmc")));
        click(500,provider,"Изменить награду");
        click(500,menu.input(500,"9"),"Подтвердить");
        assertTrue(requests.stream().anyMatch(r->r.startsWith("/voting-update")&&r.contains("hotmc")&&r.contains("900")));
    }
    @Test void monthly_settings_require_root_confirmation_and_top_navigation_works() throws Exception {
        var home=menu.voting.adminHome(500);var monthly=click(500,home,"Призы ТОП-3");
        assertTrue(monthly.text().contains("100.00"));var prompt=click(500,monthly,"Изменить 2 место");
        assertTrue(prompt.text().contains("месячный"));var confirmation=menu.input(500,"7.01");
        assertFalse(requests.stream().anyMatch(r->r.startsWith("/voting-month-update")));
        String apply=button(confirmation,"Подтвердить");assertTrue(menu.callback(99,apply).alert());
        menu.callback(500,apply);assertTrue(requests.stream().anyMatch(r->r.startsWith("/voting-month-update")&&r.contains("701")&&r.contains("\"place\":2")));
        var tie=click(500,home,"При равенстве");click(500,click(500,tie,"Разделить"),"Подтвердить");
        assertTrue(requests.stream().anyMatch(r->r.startsWith("/voting-month-update")&&r.contains("split")));
        var top=click(42,menu.voting.userHome(42),"Топ голосов");assertTrue(top.text().contains("1. Player42 · 8"));
        click(42,top,"Вперёд");assertTrue(requests.stream().anyMatch(r->r.startsWith("/voting-top")&&r.contains("1")));
        assertTrue(click(42,top,"Голосование").text().contains("Голосуйте"));
    }
    @Test void donation_statistics_root_acl_and_period_navigation() throws Exception {
        var home=menu.cabinet.donationAdmin(500);var statistics=click(500,home,"Статистика");
        assertTrue(menu.callback(99,button(statistics,"Сегодня")).alert());
        var today=click(500,statistics,"Сегодня");assertTrue(today.text().contains("Пополнения: 2 · 400.00"));
        assertTrue(today.text().contains("Бонусы отзывов: 1 · 2.00"));assertTrue(today.text().contains("Ручные изменения: 3 · 1.00"));
        assertNotNull(button(click(500,today,"Периоды"),"30 дней"));
    }
    @Test void cooldown_text_rounds_up_compactly() {
        assertEquals("✅ Доступно",VotingMenu.timer(0));assertEquals("⏳ Следующая награда через 7 ч 42 мин",VotingMenu.timer((7*60+42)*60));
        assertEquals("⏳ Следующая награда через 1 мин",VotingMenu.timer(1));
        assertTrue(VotingMenu.providerTimer(new com.google.gson.JsonObject()).contains("недоступен"));
        assertTrue(VotingMenu.providerTimer(com.google.gson.JsonParser.parseString("{\"eligibilityKnown\":true,\"remainingSeconds\":null}").getAsJsonObject()).contains("недоступен"));
    }
}
