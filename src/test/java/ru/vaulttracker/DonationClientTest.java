package ru.vaulttracker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DonationClientTest {
    @TempDir Path folder;
    @Test void sendsAuthenticatedOwnerAndNeverFollowsRedirects() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        UUID owner=UUID.randomUUID();String key="a".repeat(48);
        server.createContext("/wallet",exchange->{
            assertEquals("Bearer "+key,exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(new String(exchange.getRequestBody().readAllBytes()).contains(owner.toString()));
            byte[] bytes="{\"balanceMinor\":12345,\"blocked\":false,\"premium\":false}".getBytes();
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            Files.writeString(folder.resolve("donations.yml"),"enabled: true\napi-key: '"+key+"'\nport: "+server.getAddress().getPort()+"\noffer-url: 'https://funpay.com/lots/offer?id=123'\n");
            var client=new DonationClient(folder);
            assertEquals(12345,client.wallet(owner).get("balanceMinor").getAsLong());
            assertEquals("https://funpay.com/lots/offer?id=123",client.offerUrl());
            server.removeContext("/wallet");
            var redirected = new java.util.concurrent.atomic.AtomicBoolean();
            server.createContext("/redirected", exchange -> {
                redirected.set(true); exchange.sendResponseHeaders(200, -1); exchange.close();
            });
            server.createContext("/wallet", exchange -> {
                exchange.getResponseHeaders().add("Location", "/redirected");
                byte[] bytes = "{}".getBytes();
                exchange.sendResponseHeaders(302, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            assertThrows(Exception.class, () -> client.wallet(owner));
            assertFalse(redirected.get(), "Bearer credentials must not follow redirects");
            Files.writeString(folder.resolve("donations.yml"),"offer-url: 'https://evil.example/'\n");
            assertThrows(Exception.class,client::offerUrl);assertThrows(Exception.class,()->client.wallet(owner));
        } finally {server.stop(0);}
    }
}
