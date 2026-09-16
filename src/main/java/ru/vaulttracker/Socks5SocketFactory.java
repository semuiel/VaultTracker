package ru.vaulttracker;

import javax.net.SocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** SOCKS5 authentication scoped to one OkHttp client; never changes JVM-wide networking. */
final class Socks5SocketFactory extends SocketFactory {
    private final TelegramConfig.Proxy proxy;
    Socks5SocketFactory(TelegramConfig.Proxy proxy) {this.proxy=proxy;}
    @Override public Socket createSocket() {return new Socks5Socket(proxy);}
    @Override public Socket createSocket(String host,int port) throws IOException {Socket s=createSocket();s.connect(new InetSocketAddress(host,port));return s;}
    @Override public Socket createSocket(String host,int port,InetAddress local,int localPort) throws IOException {Socket s=createSocket();s.bind(new InetSocketAddress(local,localPort));s.connect(new InetSocketAddress(host,port));return s;}
    @Override public Socket createSocket(InetAddress host,int port) throws IOException {return createSocket(host.getHostAddress(),port);}
    @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort) throws IOException {return createSocket(host.getHostAddress(),port,local,localPort);}

    private static final class Socks5Socket extends Socket {
        private final TelegramConfig.Proxy proxy;
        // Always connect to the configured proxy directly. This prevents JVM-wide
        // ProxySelector/system properties from wrapping this socket in another SOCKS layer.
        private final Socket delegate=new Socket(java.net.Proxy.NO_PROXY);private int timeout;
        Socks5Socket(TelegramConfig.Proxy proxy) {this.proxy=proxy;}
        @Override public void connect(SocketAddress endpoint) throws IOException {connect(endpoint,0);}
        @Override public void connect(SocketAddress endpoint,int connectTimeout) throws IOException {
            if(!(endpoint instanceof InetSocketAddress target)) throw new SocketException("Нужен TCP-адрес");
            try {
            delegate.connect(new InetSocketAddress(proxy.host(),proxy.port()),connectTimeout);delegate.setSoTimeout(connectTimeout>0?connectTimeout:15000);
            InputStream in=delegate.getInputStream();OutputStream out=delegate.getOutputStream();byte[] user=proxy.username().getBytes(StandardCharsets.UTF_8),password=proxy.password().getBytes(StandardCharsets.UTF_8);
            if(user.length>255||password.length>255) throw new SocketException("Логин или пароль SOCKS5 длиннее 255 байт");
            out.write(proxy.authenticated()?new byte[]{5,2,0,2}:new byte[]{5,1,0});out.flush();int version=read(in),method=read(in);if(version!=5||method==255) throw new SocketException("SOCKS5 не принял способ авторизации");
            if(method==2) {out.write(1);out.write(user.length);out.write(user);out.write(password.length);out.write(password);out.flush();if(read(in)!=1||read(in)!=0) throw new SocketException("SOCKS5 отклонил логин или пароль");}
            else if(method!=0) throw new SocketException("SOCKS5 выбрал неподдерживаемую авторизацию: "+method);
            String host=target.getHostString();byte[] name=host.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream request=new ByteArrayOutputStream();
            request.write(new byte[]{5,1,0});
            if(!literalAddress(host)) {if(name.length>255) throw new SocketException("Имя узла слишком длинное");request.write(3);request.write(name.length);request.write(name);}
            else {byte[] address=target.getAddress()!=null?target.getAddress().getAddress():InetAddress.getByName(host).getAddress();request.write(address.length==4?1:4);request.write(address);}
            request.write((target.getPort()>>>8)&255);request.write(target.getPort()&255);out.write(request.toByteArray());out.flush();
            if(read(in)!=5) throw new SocketException("Некорректный ответ SOCKS5");int reply=read(in);read(in);int type=read(in);if(reply!=0) throw new SocketException("SOCKS5: "+replyText(reply));
            int length=switch(type){case 1->4;case 4->16;case 3->read(in);default->throw new SocketException("Некорректный адрес SOCKS5");};readFully(in,length+2);
            delegate.setSoTimeout(timeout);
            } catch(IOException | RuntimeException e) {try {delegate.close();} catch(IOException ignored) {}throw e;}
        }
        private static int read(InputStream in) throws IOException {int value=in.read();if(value<0) throw new EOFException("SOCKS5 закрыл соединение");return value;}
        private static boolean literalAddress(String value) {return value.indexOf(':')>=0 || value.matches("[0-9.]+");}
        private static void readFully(InputStream in,int length) throws IOException {while(length>0){long skipped=in.skip(length);if(skipped>0){length-=skipped;continue;}read(in);length--;}}
        private static String replyText(int code){return switch(code){case 1->"общая ошибка прокси";case 2->"соединение запрещено правилами прокси";case 3->"сеть недоступна";case 4->"узел недоступен";case 5->"соединение отклонено";case 6->"истёк TTL";case 7->"команда не поддерживается";case 8->"тип адреса не поддерживается";default->"ошибка "+code;};}
        @Override public InputStream getInputStream() throws IOException{return delegate.getInputStream();}@Override public OutputStream getOutputStream() throws IOException{return delegate.getOutputStream();}
        @Override public synchronized void close() throws IOException{delegate.close();}@Override public boolean isConnected(){return delegate.isConnected();}@Override public boolean isClosed(){return delegate.isClosed();}
        @Override public void setSoTimeout(int value) throws SocketException{timeout=value;delegate.setSoTimeout(value);}@Override public int getSoTimeout() throws SocketException{return delegate.getSoTimeout();}
        @Override public void setTcpNoDelay(boolean value) throws SocketException{delegate.setTcpNoDelay(value);}@Override public boolean getTcpNoDelay() throws SocketException{return delegate.getTcpNoDelay();}
        @Override public void setKeepAlive(boolean value) throws SocketException{delegate.setKeepAlive(value);}@Override public boolean getKeepAlive() throws SocketException{return delegate.getKeepAlive();}
        @Override public void setSendBufferSize(int value) throws SocketException{delegate.setSendBufferSize(value);}@Override public int getSendBufferSize() throws SocketException{return delegate.getSendBufferSize();}
        @Override public void setReceiveBufferSize(int value) throws SocketException{delegate.setReceiveBufferSize(value);}@Override public int getReceiveBufferSize() throws SocketException{return delegate.getReceiveBufferSize();}
        @Override public void bind(SocketAddress bindpoint) throws IOException{delegate.bind(bindpoint);}@Override public SocketAddress getRemoteSocketAddress(){return delegate.getRemoteSocketAddress();}
        @Override public SocketAddress getLocalSocketAddress(){return delegate.getLocalSocketAddress();}@Override public InetAddress getInetAddress(){return delegate.getInetAddress();}
        @Override public InetAddress getLocalAddress(){return delegate.getLocalAddress();}@Override public int getPort(){return delegate.getPort();}@Override public int getLocalPort(){return delegate.getLocalPort();}
        @Override public void shutdownInput() throws IOException{delegate.shutdownInput();}@Override public void shutdownOutput() throws IOException{delegate.shutdownOutput();}
    }
}
