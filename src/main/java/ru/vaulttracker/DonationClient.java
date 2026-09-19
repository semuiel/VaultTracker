package ru.vaulttracker;

import com.google.gson.*;
import okhttp3.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Called only by Telegram workers. FunPay credentials never enter the plugin. */
final class DonationClient {
    static final class Rejected extends IllegalArgumentException {Rejected(String message){super(message);}}
    private final Path config;
    private final OkHttpClient http=new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(1,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    DonationClient(Path folder) {config=folder.resolve("donations.yml");}
    private YamlConfiguration settings() throws Exception {
        var yaml=new YamlConfiguration();if(Files.exists(config)) yaml.load(config.toFile());return yaml;
    }
    boolean enabled() throws Exception {return settings().getBoolean("enabled",false);}
    String offerUrl() throws Exception {
        String url=settings().getString("offer-url","");
        if(!url.matches("https://funpay\\.com/lots/offer\\?id=[0-9]+")) throw new IOException("Лот ещё не настроен.");
        return url;
    }
    JsonObject call(String operation,JsonObject body) throws Exception {
        var yaml=settings();String key=yaml.getString("api-key","");int port=yaml.getInt("port",18763);
        if(!yaml.getBoolean("enabled",false)||key.length()<40||port<1024||port>65535) throw new IOException("Приём пожертвований ещё настраивается.");
        Request request=new Request.Builder().url("http://127.0.0.1:"+port+"/"+operation)
                .header("Authorization","Bearer "+key).post(RequestBody.create(body.toString(),MediaType.get("application/json"))).build();
        try(Response response=http.newCall(request).execute()) {
            if(response.body()==null) throw new IOException("Сервис пожертвований недоступен.");
            byte[] bytes=response.body().byteStream().readNBytes(32769);
            if(bytes.length>32768) throw new IOException("Некорректный ответ сервиса.");
            var result=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if(response.code()==400) throw new Rejected(result.has("error")?result.get("error").getAsString():"Повторите позже.");
            if(!response.isSuccessful()) throw new IOException("Сервис пожертвований временно недоступен.");
            return result;
        }
    }
    JsonObject wallet(UUID owner) throws Exception {return call("wallet",owner(owner));}
    JsonObject redeem(UUID owner,String code) throws Exception {var data=owner(owner);data.addProperty("code",code);return call("redeem",data);}
    private static JsonObject owner(UUID owner) {var body=new JsonObject();body.addProperty("owner",owner.toString());return body;}
    static String money(long minor) {return java.math.BigDecimal.valueOf(minor,2).toPlainString();}
}
