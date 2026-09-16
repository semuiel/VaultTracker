package ru.vaulttracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramProxyFailoverTest {
    @TempDir Path dir;
    TelegramConfig config() {return new TelegramConfig(true,"test-token",Set.of(),Set.of(),List.of(),8,5,300,"http://telegram.invalid",new TelegramConfig.Proxy(TelegramConfig.ProxyType.NONE,"",0,"",""),new TelegramConfig.Retry(1,1,10),dir.resolve("telegram.offset"));}
    @Test void parsesListAndNeverLeaksBadCredentials() throws Exception {
        Files.writeString(dir.resolve("telegram-proxies.txt"),"# proxies\n127.0.0.1:1080:user:pass:colon\nlocalhost:1081:other:secret\n");
        var routes=TelegramProxies.load(config());assertEquals(2,routes.size());assertEquals("pass:colon",routes.getFirst().password());
        Files.writeString(dir.resolve("telegram-proxies.txt"),"localhost:bad:user:secret\n");
        IOException error=assertThrows(IOException.class,()->TelegramProxies.load(config()));assertFalse(error.getMessage().contains("secret"));assertTrue(error.getMessage().contains("1"));
    }
    @Test void switchesToNextProxyWithoutChangingGlobalAuthenticatorOrResolvingTargetLocally() throws Exception {
        var authenticator=Authenticator.getDefault();var selector=ProxySelector.getDefault();
        int closedPort;try(ServerSocket unused=new ServerSocket(0)) {closedPort=unused.getLocalPort();}
        try(ServerSocket server=new ServerSocket(0)) {
            server.setSoTimeout(5000);
            Files.writeString(dir.resolve("telegram-proxies.txt"),"127.0.0.1:"+closedPort+":a:b\n127.0.0.1:"+server.getLocalPort()+":user:secret\n");
            var serving=CompletableFuture.runAsync(()-> {
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);DataInputStream in=new DataInputStream(socket.getInputStream());var out=socket.getOutputStream();
                    assertEquals(5,in.readUnsignedByte());in.readNBytes(in.readUnsignedByte());out.write(new byte[]{5,2});out.flush();
                    assertEquals(1,in.readUnsignedByte());in.readNBytes(in.readUnsignedByte());in.readNBytes(in.readUnsignedByte());out.write(new byte[]{1,0});out.flush();
                    assertEquals(5,in.readUnsignedByte());assertEquals(1,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());assertEquals(3,in.readUnsignedByte());
                    assertEquals("telegram.invalid",new String(in.readNBytes(in.readUnsignedByte()),java.nio.charset.StandardCharsets.UTF_8));in.readUnsignedShort();
                    out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();
                    BufferedReader reader=new BufferedReader(new InputStreamReader(in));String line;int length=0;
                    while((line=reader.readLine())!=null && !line.isEmpty()) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(15).trim());
                    for(int i=0;i<length;i++) reader.read();
                    String body="{\"ok\":true,\"result\":{\"username\":\"TestBot\"}}";
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length()+"\r\nConnection: close\r\n\r\n"+body).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();
                } catch(IOException e) {throw new UncheckedIOException(e);}
            });
            try(TelegramApi api=new TelegramApi(config())) {assertThrows(IOException.class,api::verify);try {assertEquals("TestBot",api.verify());} catch(IOException failure) {try {serving.get(6,TimeUnit.SECONDS);} catch(Exception serverFailure) {failure.addSuppressed(serverFailure);} throw failure;}}
            serving.get(6,TimeUnit.SECONDS);
        }
        assertSame(authenticator,Authenticator.getDefault());assertSame(selector,ProxySelector.getDefault());
    }
}
