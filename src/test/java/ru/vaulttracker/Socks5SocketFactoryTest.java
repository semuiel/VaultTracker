package ru.vaulttracker;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Socks5SocketFactoryTest {
    @Test void tunnelsUnauthenticatedConnectionWithRemoteDns() throws Exception {
        try(ServerSocket proxyServer=new ServerSocket(0)) {
            var observed=CompletableFuture.supplyAsync(()->serve(proxyServer,false));
            var proxy=new TelegramConfig.Proxy(TelegramConfig.ProxyType.SOCKS5,"127.0.0.1",proxyServer.getLocalPort(),"","");
            try(Socket socket=new Socks5SocketFactory(proxy).createSocket()) {
                socket.connect(InetSocketAddress.createUnresolved("api.telegram.org",443),2000);
            }
            assertEquals("api.telegram.org:443",observed.get(2,TimeUnit.SECONDS));
        }
    }

    @Test void authenticatesInsideTheClientLocalSocksTunnel() throws Exception {
        try(ServerSocket proxyServer=new ServerSocket(0)) {
            var observed=CompletableFuture.supplyAsync(()->serve(proxyServer,true));
            var proxy=new TelegramConfig.Proxy(TelegramConfig.ProxyType.SOCKS5,"127.0.0.1",proxyServer.getLocalPort(),"user","secret");
            try(Socket socket=new Socks5SocketFactory(proxy).createSocket()) {
                socket.connect(InetSocketAddress.createUnresolved("api.telegram.org",443),2000);
            }
            assertEquals("api.telegram.org:443",observed.get(2,TimeUnit.SECONDS));
        }
    }

    private static String serve(ServerSocket server,boolean authenticated) {
        try(Socket client=server.accept()) {
            var in=new DataInputStream(client.getInputStream());OutputStream out=client.getOutputStream();
            assertEquals(5,in.readUnsignedByte());int methodCount=in.readUnsignedByte();byte[] methods=in.readNBytes(methodCount);
            assertArrayEquals(authenticated?new byte[]{0,2}:new byte[]{0},methods);
            out.write(new byte[]{5,(byte)(authenticated?2:0)});out.flush();
            if(authenticated) {
                assertEquals(1,in.readUnsignedByte());String user=readText(in);String password=readText(in);
                assertEquals("user",user);assertEquals("secret",password);out.write(new byte[]{1,0});out.flush();
            }
            assertEquals(5,in.readUnsignedByte());assertEquals(1,in.readUnsignedByte());assertEquals(0,in.readUnsignedByte());
            assertEquals(3,in.readUnsignedByte());String host=readText(in);int port=in.readUnsignedShort();
            out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();return host+":"+port;
        } catch(Exception error) {throw new RuntimeException(error);}
    }

    private static String readText(DataInputStream in) throws Exception {
        return new String(in.readNBytes(in.readUnsignedByte()),StandardCharsets.UTF_8);
    }
}
