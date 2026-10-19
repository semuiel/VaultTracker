package ru.vaulttracker;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonObject;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.InheritanceNode;

/** Absolute expiries and durable outbox make LP retries safe after a crash. */
final class DonationPremium {
    private final DonationClient client;
    DonationPremium(DonationClient client) {this.client=client;}
    private LuckPerms api() {
        try {return LuckPermsProvider.get();}
        catch(IllegalStateException | LinkageError unavailable) {throw new DonationClient.Rejected("LuckPerms недоступен. Покупка не выполнена.");}
    }
    synchronized long expiry(UUID owner) throws Exception {
        return expiry(owner,PremiumProduct.PREMIUM);
    }
    synchronized long expiry(UUID owner,PremiumProduct product) throws Exception {
        var user=api().getUserManager().loadUser(owner).get(10,TimeUnit.SECONDS);
        long until=0;
        for(var node:user.getNodes()) if(node instanceof InheritanceNode group && group.getGroupName().equals(product.group) && node.getValue() && !node.hasExpired()) {
            if(!node.hasExpiry()) return -1;
            until=Math.max(until,node.getExpiry().getEpochSecond());
        }
        return until;
    }
    synchronized JsonObject change(UUID owner,String name,long actor,String requestId,String action,int days,long amount) throws Exception {
        return change(owner,name,actor,requestId,action,days,amount,PremiumProduct.PREMIUM);
    }
    synchronized JsonObject change(UUID owner,String name,long actor,String requestId,String action,int days,long amount,PremiumProduct product) throws Exception {
        long base=0;
        if(Set.of("buy","grant","remove").contains(action)) {
            sync();
            if(!action.equals("remove") && api().getGroupManager().loadGroup(product.group).get(10,TimeUnit.SECONDS).isEmpty()) throw new DonationClient.Rejected("Группа "+product.group+" не создана в LuckPerms. Средства не списаны.");
            base=expiry(owner,product);
            if(base<0 && !action.equals("remove") && days>=0) throw new DonationClient.Rejected("У игрока уже бессрочный премиум.");
        }
        var body=new JsonObject();body.addProperty("owner",owner.toString());body.addProperty("name",name);
        body.addProperty("actor",Long.toString(actor));body.addProperty("requestId",requestId);body.addProperty("action",action);
        body.addProperty("days",days);body.addProperty("amountMinor",amount);body.addProperty("baseUntil",base);
        body.addProperty("product",product.group);
        var result=client.call("donation-change",body);
        // Failure leaves the durable task pending; it never charges again.
        try {sync();} catch(Exception | LinkageError pending) {result.addProperty("pending",true);}
        return result;
    }
    synchronized void sync() throws Exception {
        if(!client.enabled()) return;
        var options=new JsonObject();options.addProperty("includeLite",true);
        var tasks=client.call("premium-tasks",options).getAsJsonArray("tasks");
        if(tasks.isEmpty()) return;
        var lp=api();
        for(var element:tasks) {
            var task=element.getAsJsonObject();UUID owner=UUID.fromString(task.get("owner").getAsString());long until=task.get("until").getAsLong();
            var product=task.has("product")?PremiumProduct.parse(task.get("product").getAsString()):PremiumProduct.PREMIUM;
            var user=lp.getUserManager().loadUser(owner).get(10,TimeUnit.SECONDS);
            for(var node:List.copyOf(user.getNodes())) if(node instanceof InheritanceNode group && group.getGroupName().equals(product.group)) user.data().remove(node);
            if(until<0) user.data().add(InheritanceNode.builder(product.group).build());
            else if(until>Instant.now().getEpochSecond()) user.data().add(InheritanceNode.builder(product.group).expiry(Instant.ofEpochSecond(until)).build());
            lp.getUserManager().saveUser(user).get(10,TimeUnit.SECONDS);
            var ack=new JsonObject();ack.addProperty("owner",owner.toString());ack.addProperty("version",task.get("version").getAsLong());ack.addProperty("product",product.group);client.call("premium-ack",ack);
        }
    }
}
