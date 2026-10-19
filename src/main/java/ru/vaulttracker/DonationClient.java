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
            // All mutations carry a durable requestId, so reconnecting a stale
            // loopback HTTP/1.0 socket cannot duplicate a balance operation.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true).build();
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
        try {return execute(request);}
        catch(java.net.SocketException reset) {
            // Windows can reset a just-closed loopback HTTP/1.0 connection.
            // donation-change is idempotent by requestId; other calls are reads
            // or use their own durable identity, so one retry is safe.
            return execute(request);
        }
    }
    private JsonObject execute(Request request) throws Exception {
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
    JsonObject playerSummary(UUID owner) throws Exception {return call("player-summary",owner(owner));}
    JsonArray votingProviders(UUID owner) throws Exception {return call("voting-providers",owner(owner)).getAsJsonArray("providers");}
    JsonObject voteTop(int page) throws Exception {var body=new JsonObject();body.addProperty("page",page);return call("voting-top",body);}
    JsonObject monthlySettings() throws Exception {return call("voting-month-settings",new JsonObject());}
    JsonObject monthlyUpdate(long actor,Long reward,String policy) throws Exception {
        return monthlyUpdate(actor,reward,policy,1);
    }
    JsonObject monthlyUpdate(long actor,Long reward,String policy,int place) throws Exception {
        var body=new JsonObject();body.addProperty("actor",Long.toString(actor));body.addProperty("place",place);if(reward!=null)body.addProperty("rewardMinor",reward);if(policy!=null)body.addProperty("tiePolicy",policy);return call("voting-month-update",body);
    }
    JsonObject donationStatistics() throws Exception {return call("donation-stats",new JsonObject());}
    JsonObject redeem(UUID owner,String code) throws Exception {var data=owner(owner);data.addProperty("code",code);return call("redeem",data);}
    JsonObject linkRewardSettings() throws Exception {return call("link-reward-settings",new JsonObject());}
    JsonObject linkRewardUpdate(long actor,long amount) throws Exception {
        var data=new JsonObject();data.addProperty("actor",Long.toString(actor));data.addProperty("rewardMinor",amount);
        return call("link-reward-update",data);
    }
    JsonObject claimLinkReward(GuardService.Account account,long actor) throws Exception {
        var data=owner(account.uuid());data.addProperty("actor",Long.toString(actor));data.addProperty("name",account.name());
        return call("link-reward-claim",data);
    }
    JsonArray votingProviders(boolean admin) throws Exception {
        return call(admin?"voting-admin":"voting-providers",new JsonObject()).getAsJsonArray("providers");
    }
    JsonObject votingUpdate(long actor,String provider,Boolean enabled,Long rewardMinor) throws Exception {
        var body=new JsonObject();body.addProperty("actor",Long.toString(actor));body.addProperty("provider",provider);
        if(enabled!=null) body.addProperty("enabled",enabled);if(rewardMinor!=null) body.addProperty("rewardMinor",rewardMinor);
        return call("voting-update",body);
    }
    JsonObject votingStatistics(String provider) throws Exception {
        var body=new JsonObject();if(provider!=null&&!provider.isBlank()) body.addProperty("provider",provider);
        return call("voting-stats",body);
    }
    void resolveKnownVotes(GuardService guard) throws Exception {
        var pending=call("voting-pending",new JsonObject()).getAsJsonArray("players");
        for(var item:pending) {
            String nickname=item.getAsJsonObject().get("nickname").getAsString();UUID owner=guard.knownPlayerUuid(nickname);
            if(owner==null) continue;
            String display=guard.knownPlayerName(owner);if(display==null||display.isBlank()) display=nickname;
            var body=owner(owner);body.addProperty("nickname",nickname);body.addProperty("displayName",display);
            call("voting-resolve",body);
        }
    }
    void recordServerVote(String nickname,String service,String timestamp) throws Exception {
        var body=new JsonObject();body.addProperty("nickname",nickname);body.addProperty("service",service);body.addProperty("timestamp",timestamp);
        call("voting-record",body);
    }
    JsonArray voteNotifications() throws Exception {
        var body=new JsonObject();body.addProperty("limit",50);
        return call("voting-notifications",body).getAsJsonArray("notifications");
    }
    void voteNotificationAck(String id,boolean success) throws Exception {
        var body=new JsonObject();body.addProperty("id",id);body.addProperty("success",success);
        call("voting-notification-ack",body);
    }
    private static JsonObject owner(UUID owner) {var body=new JsonObject();body.addProperty("owner",owner.toString());return body;}
    static String money(long minor) {return java.math.BigDecimal.valueOf(minor,2).toPlainString();}
}
