package ru.vaulttracker;

import org.bukkit.configuration.file.YamlConfiguration;
import java.net.URI;
import java.nio.file.Path;

record CoreProtectWebConfig(boolean enabled,long accessChatId,String publicUrl,String bindHost,int bindPort,
                            int pageSize,int maxPageSize,int maxOffset,int maxDays,int maxRadius,
                            int concurrency,int timeoutSeconds,int requestsPerMinute,
                            String dbHost,int dbPort,String database,String prefix,String dbUser,String dbPassword) {
    static CoreProtectWebConfig load(Path folder) {
        var y=YamlConfiguration.loadConfiguration(folder.resolve("telegram.yml").toFile());
        String p="coreprotectWeb.";
        boolean enabled=y.getBoolean(p+"enabled",false);
        String password=System.getenv("VAULT_COREPROTECT_VIEWER_PASSWORD");
        var result=new CoreProtectWebConfig(enabled,y.getLong(p+"accessChatId",0),y.getString(p+"publicUrl","").strip(),
                y.getString(p+"bindHost","127.0.0.1"),number(y,p+"bindPort",8194,1024,65535),
                number(y,p+"limits.pageSize",50,1,100),number(y,p+"limits.maxPageSize",100,1,100),
                number(y,p+"limits.maxOffset",2000,0,10000),number(y,p+"limits.maxDays",30,1,366),
                number(y,p+"limits.maxRadius",32,0,128),number(y,p+"limits.concurrency",2,1,4),
                number(y,p+"limits.timeoutSeconds",5,1,15),number(y,p+"limits.requestsPerMinute",20,1,120),
                y.getString(p+"database.host","127.0.0.1"),number(y,p+"database.port",3306,1,65535),
                y.getString(p+"database.name","coreprotect"),y.getString(p+"database.prefix","co_"),
                y.getString(p+"database.user",""),password==null?y.getString(p+"database.password",""):password);
        if(enabled) result.validate();
        return result;
    }
    private static int number(YamlConfiguration y,String key,int fallback,int min,int max) {
        int n=y.getInt(key,fallback);
        if(n<min||n>max) throw new IllegalArgumentException("Проверьте "+key);
        return n;
    }
    void validate() {
        if(accessChatId>=0||!bindHost.equals("127.0.0.1")||pageSize>maxPageSize)
            throw new IllegalArgumentException("coreprotectWeb: укажите accessChatId; listener разрешён только на 127.0.0.1");
        if(!publicUrl.isEmpty()) {
            URI u=URI.create(publicUrl);
            if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||u.getPort()==0||u.getPort()>65535
                    ||!u.getRawPath().matches("[A-Za-z0-9_/-]*")||u.getRawPath().contains("//"))
                throw new IllegalArgumentException("coreprotectWeb.publicUrl: нужен HTTPS URL без параметров");
        }
        if(!dbHost.matches("[a-zA-Z0-9.:-]+")||!database.matches("[a-zA-Z0-9_]+")||!prefix.matches("[a-zA-Z0-9_]{1,40}")||dbUser.isBlank()||dbPassword.isBlank())
            throw new IllegalArgumentException("coreprotectWeb.database: нужна отдельная SELECT-only учётная запись");
    }
    boolean buttonEnabled() {return enabled&&!publicUrl.isBlank();}
    String basePath() {
        String path=publicUrl.isBlank()?"/":URI.create(publicUrl).getPath();
        if(path==null||path.isBlank()) return "/";
        return path.endsWith("/")?path:path+"/";
    }
    String origin() {
        if(publicUrl.isBlank()) return "";
        URI u=URI.create(publicUrl);return "https://"+u.getRawAuthority();
    }
    @Override public String toString() {return "CoreProtectWebConfig[enabled="+enabled+"]";}
}
