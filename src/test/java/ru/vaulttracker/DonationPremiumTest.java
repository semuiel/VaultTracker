package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @ParameterizedTest @ValueSource(strings={"premium","premiumlite"})
    void absoluteExpiryIsAcknowledgedOnlyAfterLuckPermsSave(String groupName) throws Exception {
        UUID owner=UUID.randomUUID();long until=Instant.now().getEpochSecond()+86400;
        var ack=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/premium-tasks",e->{e.getRequestBody().readAllBytes();byte[] bytes=("{\"tasks\":[{\"owner\":\""+owner+"\",\"product\":\""+groupName+"\",\"until\":"+until+",\"version\":2}]}").getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        var ackProduct=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/premium-ack",e->{ackProduct.set(com.google.gson.JsonParser.parseString(new String(e.getRequestBody().readAllBytes())).getAsJsonObject().get("product").getAsString());ack.incrementAndGet();byte[] bytes="{}".getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try(var provider=mockStatic(LuckPermsProvider.class);var nodes=mockStatic(InheritanceNode.class)) {
            var lp=mock(LuckPerms.class);var manager=mock(UserManager.class);var user=mock(User.class);var map=mock(NodeMap.class);
            provider.when(LuckPermsProvider::get).thenReturn(lp);when(lp.getUserManager()).thenReturn(manager);when(manager.loadUser(owner)).thenReturn(CompletableFuture.completedFuture(user));
            when(user.data()).thenReturn(map);var old=mock(InheritanceNode.class);when(old.getGroupName()).thenReturn(groupName);
            var other=mock(InheritanceNode.class);when(other.getGroupName()).thenReturn(groupName.equals("premium")?"premiumlite":"premium");when(user.getNodes()).thenReturn(Set.of(old,other));
            var builder=mock(InheritanceNode.Builder.class,RETURNS_SELF);var node=mock(InheritanceNode.class);nodes.when(()->InheritanceNode.builder(groupName)).thenReturn(builder);when(builder.build()).thenReturn(node);
            Files.writeString(folder.resolve("donations.yml"),"enabled: true\napi-key: '"+"x".repeat(48)+"'\nport: "+server.getAddress().getPort()+"\n");
            var premium=new DonationPremium(new DonationClient(folder));
            when(manager.saveUser(user)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
            assertThrows(Exception.class,premium::sync);assertEquals(0,ack.get());
            when(manager.saveUser(user)).thenReturn(CompletableFuture.completedFuture(null));premium.sync();
            assertEquals(1,ack.get());verify(builder,times(2)).expiry(Instant.ofEpochSecond(until));verify(map,times(2)).remove(old);
            verify(map,never()).remove(other);assertEquals(groupName,ackProduct.get());
        } finally {server.stop(0);}
    }
    @Test void permanentTaskCreatesInheritanceWithoutExpiry() throws Exception {
        UUID owner=UUID.randomUUID();var ack=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/premium-tasks",e->{e.getRequestBody().readAllBytes();byte[] bytes=("{\"tasks\":[{\"owner\":\""+owner+"\",\"until\":-1,\"version\":3}]}").getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.createContext("/premium-ack",e->{e.getRequestBody().readAllBytes();ack.incrementAndGet();byte[] bytes="{}".getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try(var provider=mockStatic(LuckPermsProvider.class);var nodes=mockStatic(InheritanceNode.class)) {
            var lp=mock(LuckPerms.class);var manager=mock(UserManager.class);var user=mock(User.class);var map=mock(NodeMap.class);
            provider.when(LuckPermsProvider::get).thenReturn(lp);when(lp.getUserManager()).thenReturn(manager);when(manager.loadUser(owner)).thenReturn(CompletableFuture.completedFuture(user));when(manager.saveUser(user)).thenReturn(CompletableFuture.completedFuture(null));
            when(user.data()).thenReturn(map);when(user.getNodes()).thenReturn(Set.of());var builder=mock(InheritanceNode.Builder.class,RETURNS_SELF);var node=mock(InheritanceNode.class);nodes.when(()->InheritanceNode.builder("premium")).thenReturn(builder);when(builder.build()).thenReturn(node);
            Files.writeString(folder.resolve("donations.yml"),"enabled: true\napi-key: '"+"x".repeat(48)+"'\nport: "+server.getAddress().getPort()+"\n");
            new DonationPremium(new DonationClient(folder)).sync();
            verify(builder,never()).expiry(any(Instant.class));verify(map).add(node);assertEquals(1,ack.get());
        } finally {server.stop(0);}
    }

    @ParameterizedTest @ValueSource(strings={"premium","premiumlite"})
    void purchaseUsesSelectedGroupExpiryAndMissingGroupNeverDebits(String groupName) throws Exception {
        UUID owner=UUID.randomUUID();long until=Instant.now().getEpochSecond()+86400;
        var requests=new java.util.concurrent.CopyOnWriteArrayList<com.google.gson.JsonObject>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            var body=com.google.gson.JsonParser.parseString(new String(e.getRequestBody().readAllBytes())).getAsJsonObject();
            String response="{\"tasks\":[]}";
            if(e.getRequestURI().getPath().equals("/donation-change")){requests.add(body);response="{\"balanceMinor\":100}";}
            byte[] bytes=response.getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        try(var provider=mockStatic(LuckPermsProvider.class)) {
            var lp=mock(LuckPerms.class);provider.when(LuckPermsProvider::get).thenReturn(lp);
            var users=mock(UserManager.class);var groups=mock(net.luckperms.api.model.group.GroupManager.class);
            when(lp.getUserManager()).thenReturn(users);when(lp.getGroupManager()).thenReturn(groups);
            var user=mock(User.class);when(users.loadUser(owner)).thenReturn(CompletableFuture.completedFuture(user));
            var own=mock(InheritanceNode.class);when(own.getGroupName()).thenReturn(groupName);when(own.getValue()).thenReturn(true);
            when(own.hasExpiry()).thenReturn(true);when(own.getExpiry()).thenReturn(Instant.ofEpochSecond(until));
            var other=mock(InheritanceNode.class);when(other.getGroupName()).thenReturn(groupName.equals("premium")?"premiumlite":"premium");
            when(other.getValue()).thenReturn(true);when(other.hasExpiry()).thenReturn(false);when(user.getNodes()).thenReturn(Set.of(own,other));
            when(groups.loadGroup(groupName)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
            Files.writeString(folder.resolve("donations.yml"),"enabled: true\napi-key: '"+"x".repeat(48)+"'\nport: "+server.getAddress().getPort()+"\n");
            var premium=new DonationPremium(new DonationClient(folder));var product=PremiumProduct.parse(groupName);
            assertThrows(DonationClient.Rejected.class,()->premium.change(owner,"Player",42,"id","buy",30,0,product));assertTrue(requests.isEmpty());
            when(groups.loadGroup(groupName)).thenReturn(CompletableFuture.completedFuture(Optional.of(mock(net.luckperms.api.model.group.Group.class))));
            premium.change(owner,"Player",42,"id","buy",30,0,product);
            assertEquals(groupName,requests.getFirst().get("product").getAsString());assertEquals(until,requests.getFirst().get("baseUntil").getAsLong());
        } finally {server.stop(0);}
    }
}
