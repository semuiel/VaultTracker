package ru.vaulttracker;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FriendSharingTest {
    @TempDir Path folder;
    UUID owner=UUID.randomUUID(),viewer=UUID.randomUUID();
    @Test void legacyFriendsKeepMutedNotificationsWithoutGrantingSearch() throws Exception {
        Class.forName("org.h2.Driver");
        try(var db=java.sql.DriverManager.getConnection("jdbc:h2:"+folder.toAbsolutePath(),"sa","");var sql=db.createStatement()) {
            sql.execute("CREATE TABLE guard_friends (owner_uuid VARCHAR(36),friend_uuid VARCHAR(36),nickname VARCHAR(64),PRIMARY KEY(owner_uuid,friend_uuid))");
            try(var insert=db.prepareStatement("INSERT INTO guard_friends VALUES(?,?,?)")) {insert.setString(1,owner.toString());insert.setString(2,viewer.toString());insert.setString(3,"Viewer");insert.executeUpdate();}
        }
        try(var guard=new GuardService(folder,new GuardConfig(true,0),Logger.getAnonymousLogger())) {
            link(guard,42,owner,"Owner");var friend=guard.friends(42).get().getFirst();
            assertTrue(friend.muted());assertFalse(friend.shared());assertFalse(friend.notified());assertFalse(guard.canSearch(viewer,owner));
        }
    }
    void link(GuardService guard,long user,UUID uuid,String name) throws Exception {
        String code=guard.generate(user).get().split("/vtrack link ")[1].substring(0,GuardService.LINK_CODE_LENGTH);
        guard.link(uuid,name,code).get();
    }
    @Test void sharingIsDirectionalIndependentFromMutingAndPersists() throws Exception {
        try(var guard=new GuardService(folder,new GuardConfig(true,0),Logger.getAnonymousLogger())) {
            link(guard,42,owner,"Owner");link(guard,43,viewer,"Viewer");
            guard.addFriend(42,viewer,"Viewer").get();
            assertFalse(guard.canSearch(viewer,owner));assertTrue(guard.friends(42).get().getFirst().muted());
            guard.toggleFriend(42,viewer,true).get();
            assertTrue(guard.canSearch(viewer,owner));assertFalse(guard.canSearch(owner,viewer));
            guard.toggleFriend(42,viewer,false).get();
            assertFalse(guard.friends(42).get().getFirst().muted());assertTrue(guard.canSearch(viewer,owner));
            guard.toggleFriendNotifications(42,viewer).get();
            guard.addFriend(42,viewer,"Renamed").get();assertTrue(guard.canSearch(viewer,owner));
            assertTrue(guard.friends(42).get().getFirst().notified());
        }
        try(var guard=new GuardService(folder,new GuardConfig(true,0),Logger.getAnonymousLogger())) {
            assertTrue(guard.canSearch(viewer,owner));assertFalse(guard.friends(42).get().getFirst().muted());
            assertTrue(guard.friends(42).get().getFirst().notified());
            guard.removeFriend(42,viewer).get();assertFalse(guard.canSearch(viewer,owner));
        }
    }
    @Test void telegramShowsOnlyGrantedResourcesAndRechecksOldPagesAfterRevocation() throws Exception {
        try(var guard=new GuardService(folder,new GuardConfig(true,0),Logger.getAnonymousLogger())) {
            link(guard,42,owner,"Owner");link(guard,43,viewer,"Viewer");guard.addFriend(42,viewer,"Viewer").get();
            Catalogue catalogue=new Catalogue(v->{});UUID world=UUID.randomUUID();
            catalogue.register(new BlockKey(world,1,65,1),owner,"Owner",List.of(new BlockKey(world,1,64,1)),Map.of("DIAMOND",5L,"STONE",10L),1,0);
            StorageEngine storage=mock(StorageEngine.class);when(storage.ready()).thenReturn(true);
            var commands=new TelegramCommands(catalogue,storage,1,id->true);var menu=new TelegramGuardMenu(guard,commands);
            assertFalse(menu.home(43).buttons().stream().anyMatch(b->b.text().contains("Ресурсы друзей")));
            assertTrue(menu.home(43).buttons().stream().anyMatch(b->b.text().equals("📦 Мои ресурсы")));
            guard.toggleFriend(42,viewer,true).get();
            var home=menu.home(43);
            assertFalse(home.buttons().stream().anyMatch(b->b.text().equals("📦 Мои ресурсы")||b.text().equals("👥 Ресурсы друзей")));
            var resourcesMenu=menu.callback(43,button(home,"📦 Ресурсы")).view();
            assertEquals(4,resourcesMenu.buttons().stream().filter(b->!b.text().equals("⌂ Меню")).count());
            assertTrue(resourcesMenu.buttons().stream().anyMatch(b->b.text().equals("🏆 Личный топ")));
            assertTrue(menu.callback(43,button(resourcesMenu,"Назад")).view().text().contains("Личный кабинет"));
            var own=menu.callback(43,button(resourcesMenu,"Мои ресурсы")).view();
            assertTrue(own.text().contains("Ресурсы"));
            assertEquals("📦 Ресурсы",menu.callback(43,button(own,"↩️ Ресурсы")).view().text());
            var list=menu.callback(43,button(resourcesMenu,"Ресурсы друзей")).view();
            assertEquals("📦 Ресурсы",menu.callback(43,button(list,"↩️ Ресурсы")).view().text());
            var resources=menu.callback(43,button(list,"Owner")).view();assertTrue(resources.text().contains("Ресурсы Owner"));
            String next=button(resources,"Вперёд"),search=button(resources,"Найти ресурс");
            menu.callback(43,search);assertTrue(menu.input(43,"алмаз").text().contains("Алмаз ×5"));
            guard.toggleFriend(42,viewer,true).get();
            assertThrows(SecurityException.class,()->commands.callback(43,next));
            assertThrows(SecurityException.class,()->menu.callback(43,search));
            assertFalse(menu.home(43).buttons().stream().anyMatch(b->b.text().contains("Ресурсы друзей")));
        }
    }
    String button(TelegramCommands.View view,String text) {return view.buttons().stream().filter(b->b.text().contains(text)).findFirst().orElseThrow().data();}
}
