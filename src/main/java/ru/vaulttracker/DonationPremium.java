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
        var user=api().getUserManager().loadUser(owner).get(10,TimeUnit.SECONDS);
        long until=0;
        for(var node:user.getNodes()) if(node instanceof InheritanceNode group && group.getGroupName().equals("premium") && node.getValue() && !node.hasExpired()) {
            if(!node.hasExpiry()) return -1;
            until=Math.max(until,node.getExpiry().getEpochSecond());
        }
        return until;
    }
    synchronized JsonObject change(UUID owner,String name,long actor,String requestId,String action,int days,long amount) throws Exception {
        long base=0;
        if(Set.of("buy","grant","remove").contains(action)) {
            sync();
            if(!action.equals("remove") && api().getGroupManager().loadGroup("premium").get(10,TimeUnit.SECONDS).isEmpty()) throw new DonationClient.Rejected("Группа premium не создана в LuckPerms. Средства не списаны.");
            base=expiry(owner);
            if(base<0 && !action.equals("remove")) throw new DonationClient.Rejected("У игрока уже бессрочный премиум.");
        }
        var body=new JsonObject();body.addProperty("owner",owner.toString());body.addProperty("name",name);
        body.addProperty("actor",Long.toString(actor));body.addProperty("requestId",requestId);body.addProperty("action",action);
        body.addProperty("days",days);body.addProperty("amountMinor",amount);body.addProperty("baseUntil",Math.max(0,base));
        var result=client.call("donation-change",body);
        // Failure leaves the durable task pending; it never charges again.
        try {sync();} catch(Exception | LinkageError pending) {result.addProperty("pending",true);}
        return result;
    }
    synchronized void sync() throws Exception {
        if(!client.enabled()) return;
        var tasks=client.call("premium-tasks",new JsonObject()).getAsJsonArray("tasks");
        if(tasks.isEmpty()) return;
        var lp=api();
        for(var element:tasks) {
            var task=element.getAsJsonObject();UUID owner=UUID.fromString(task.get("owner").getAsString());long until=task.get("until").getAsLong();
            var user=lp.getUserManager().loadUser(owner).get(10,TimeUnit.SECONDS);
            for(var node:List.copyOf(user.getNodes())) if(node instanceof InheritanceNode group && group.getGroupName().equals("premium")) user.data().remove(node);
            if(until>Instant.now().getEpochSecond()) user.data().add(InheritanceNode.builder("premium").expiry(Instant.ofEpochSecond(until)).build());
            lp.getUserManager().saveUser(user).get(10,TimeUnit.SECONDS);
            var ack=new JsonObject();ack.addProperty("owner",owner.toString());ack.addProperty("version",task.get("version").getAsLong());client.call("premium-ack",ack);
        }
    }
}
