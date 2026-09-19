package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import net.luckperms.api.*;
import net.luckperms.api.model.user.*;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.node.types.InheritanceNode;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DonationPremiumTest {
    @TempDir Path folder;
    @Test void absoluteExpiryIsAcknowledgedOnlyAfterLuckPermsSave() throws Exception {
        UUID owner=UUID.randomUUID();long until=Instant.now().getEpochSecond()+86400;
        var ack=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/premium-tasks",e->{e.getRequestBody().readAllBytes();byte[] bytes=("{\"tasks\":[{\"owner\":\""+owner+"\",\"until\":"+until+",\"version\":2}]}").getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.createContext("/premium-ack",e->{e.getRequestBody().readAllBytes();ack.incrementAndGet();byte[] bytes="{}".getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try(var provider=mockStatic(LuckPermsProvider.class);var nodes=mockStatic(InheritanceNode.class)) {
            var lp=mock(LuckPerms.class);var manager=mock(UserManager.class);var user=mock(User.class);var map=mock(NodeMap.class);
            provider.when(LuckPermsProvider::get).thenReturn(lp);when(lp.getUserManager()).thenReturn(manager);when(manager.loadUser(owner)).thenReturn(CompletableFuture.completedFuture(user));
            when(user.data()).thenReturn(map);var old=mock(InheritanceNode.class);when(old.getGroupName()).thenReturn("premium");when(user.getNodes()).thenReturn(Set.of(old));
            var builder=mock(InheritanceNode.Builder.class,RETURNS_SELF);var node=mock(InheritanceNode.class);nodes.when(()->InheritanceNode.builder("premium")).thenReturn(builder);when(builder.build()).thenReturn(node);
            Files.writeString(folder.resolve("donations.yml"),"enabled: true\napi-key: '"+"x".repeat(48)+"'\nport: "+server.getAddress().getPort()+"\n");
            var premium=new DonationPremium(new DonationClient(folder));
            when(manager.saveUser(user)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
            assertThrows(Exception.class,premium::sync);assertEquals(0,ack.get());
            when(manager.saveUser(user)).thenReturn(CompletableFuture.completedFuture(null));premium.sync();
            assertEquals(1,ack.get());verify(builder,times(2)).expiry(Instant.ofEpochSecond(until));verify(map,times(2)).remove(old);
        } finally {server.stop(0);}
    }
}
