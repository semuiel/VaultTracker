package ru.vaulttracker;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One localhost listener and bounded HTTP pool shared by Mini Apps. */
final class TelegramWebRuntime implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService http;
    private final AtomicBoolean started=new AtomicBoolean();
    TelegramWebRuntime(String host,int port) throws IOException {
        if(!host.equals("127.0.0.1"))throw new IllegalArgumentException("Web listener: localhost only");
        http=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),Thread.ofPlatform().daemon().name("VaultTracker-web-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        try{server=HttpServer.create(new InetSocketAddress(host,port),16);server.setExecutor(http);}catch(IOException failure){http.shutdownNow();throw failure;}
    }
    void mount(String path,HttpHandler handler){server.createContext(path,handler);}
    void unmount(String path){server.removeContext(path);}
    void start(){if(started.compareAndSet(false,true))server.start();}
    int port(){return server.getAddress().getPort();}
    public void close(){server.stop(0);http.shutdownNow();}
}
