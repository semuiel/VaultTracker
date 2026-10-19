package ru.vaulttracker;

import com.google.gson.JsonParser;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Telegram's bot-token HMAC contract; user is parsed only after signature verification. */
final class TelegramMiniAppAuth {
    static long verify(String raw,String token,long now) {
        try {
            if(raw==null||raw.isBlank()||raw.length()>16_384) throw new SecurityException();
            var values=new TreeMap<String,String>();
            for(String pair:raw.split("&",-1)) {
                String[] part=pair.split("=",2);
                if(part.length!=2) throw new SecurityException();
                String key=URLDecoder.decode(part[0],StandardCharsets.UTF_8),value=URLDecoder.decode(part[1],StandardCharsets.UTF_8);
                if(key.indexOf('\n')>=0||value.indexOf('\n')>=0||values.putIfAbsent(key,value)!=null) throw new SecurityException();
            }
            String hash=values.remove("hash");
            if(hash==null||!hash.matches("[0-9a-fA-F]{64}")) throw new SecurityException();
            String check=String.join("\n",values.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList());
            byte[] secret=hmac("WebAppData".getBytes(StandardCharsets.UTF_8),token);
            if(!MessageDigest.isEqual(HexFormat.of().parseHex(hash),hmac(secret,check))) throw new SecurityException();
            long date=Long.parseLong(values.get("auth_date"));
            if(date<now-300||date>now+30) throw new SecurityException();
            var user=JsonParser.parseString(values.get("user")).getAsJsonObject();
            String id=user.get("id").getAsString();
            if(!id.matches("[1-9][0-9]{0,15}")) throw new SecurityException();
            return Long.parseLong(id);
        } catch(Exception invalid) {throw new SecurityException("Откройте CoreProtect через Telegram-бот FLEXITY");}
    }
    static byte[] hmac(byte[] key,String data) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
