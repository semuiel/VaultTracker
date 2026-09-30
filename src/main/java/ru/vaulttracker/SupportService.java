package ru.vaulttracker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/** Delivery and moderation of player requests; UI drafts remain in SupportMenu. */
final class SupportService {
    private final GuardService guard;
    private final TelegramApi api;
    private final SupportTopics topics;
    private final Logger log;
    private final TelegramModeration moderation;
    private record Confirmation(long user,String operation,int message,long expires) {}
    private final Map<String,Confirmation> confirmations=new HashMap<>();
    SupportService(GuardService guard,TelegramApi api,SupportTopics topics) {this(guard,api,topics,Logger.getLogger(SupportService.class.getName()));}
    SupportService(GuardService guard,TelegramApi api,SupportTopics topics,Logger log) {this(guard,api,topics,log,null);}
    SupportService(GuardService guard,TelegramApi api,SupportTopics topics,Logger log,TelegramModeration moderation) {
        this.guard=guard;this.api=api;this.topics=topics;this.log=log;this.moderation=moderation;
    }

    boolean configured(SupportTicket.Type type) {return topics.configured(type);}
    int attempt(long user,UUID uuid,boolean linked) throws Exception {
        try {return guard.unbanAttempt(user,uuid,linked).get();}
        catch(java.util.concurrent.ExecutionException wrapped) {
            if(wrapped.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            throw wrapped;
        }
    }
    synchronized SupportTicket submitUnban(long user,GuardService.Account author,UUID uuid,String name,String body,
                              List<Integer> sourceMedia,TelegramModeration lookup) throws Exception {
        if(!configured(SupportTicket.Type.UNBAN)) throw new IllegalStateException("Тема заявок на разбан ещё не настроена.");
        if(uuid==null || name==null) throw new IllegalArgumentException("Выберите персонажа.");
        var current=guard.account(user).get();boolean linked=current!=null;
        if(linked && (author==null || !current.uuid().equals(uuid) || !author.uuid().equals(uuid)))
            throw new SecurityException("Привязка персонажа изменилась. Начните заново.");
        if(!linked && author!=null) throw new SecurityException("Привязка персонажа изменилась. Начните заново.");
        if(body==null || body.isBlank() || body.length()>8000 || !body.contains("11. "))
            throw new IllegalArgumentException("Ответьте на все вопросы анкеты.");
        int number=attempt(user,uuid,linked);
        TelegramModeration.UnbanFacts facts;
        try {facts=lookup.unbanFacts(uuid).get();}
        catch(java.util.concurrent.ExecutionException wrapped) {
            if(wrapped.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            log.warning("Не удалось проверить бан для заявки на разбан: "+api.safe(wrapped.getCause()));
            throw wrapped;
        }
        var draft=new SupportTicket(UUID.randomUUID().toString().replace("-",""),SupportTicket.Type.UNBAN,
                user,uuid,facts.name(),null,null,body.strip(),System.currentTimeMillis(),topics.chatId(),
                topics.unbanRequests(),0,List.of(),"OPEN",List.of(),linked,number,
                "Telegram: "+guard.telegramProfile(user)+"\n"+facts.description(),0);
        return queueAndDeliver(draft,sourceMedia);
    }
    synchronized SupportTicket submit(SupportTicket.Type type,long user,GuardService.Account author,UUID target,String targetName,
                         String body,List<Integer> sourceMedia) throws Exception {
        if(!topics.configured(type)) throw new IllegalStateException("Тема поддержки ещё не настроена. Обращение не отправлено; повторите позже.");
        var current=guard.account(user).get();
        if(author==null || current==null || !author.uuid().equals(current.uuid()))
            throw new SecurityException("Свяжите игровой аккаунт заново.");
        if(type==SupportTicket.Type.COMPLAINT && (target==null || targetName==null)) throw new IllegalArgumentException("Выберите игрока.");
        if(body==null || body.isBlank() || body.length()>3000) throw new IllegalArgumentException("Описание: от 1 до 3000 символов.");
        var draft=new SupportTicket(UUID.randomUUID().toString().replace("-",""),type,user,author.uuid(),author.name(),
                target,targetName,body.strip(),System.currentTimeMillis(),topics.chatId(),topics.topic(type),0,List.of(),"OPEN");
        return queueAndDeliver(draft,sourceMedia);
    }
    private SupportTicket queueAndDeliver(SupportTicket ticket,List<Integer> sourceMedia) throws Exception {
        if(sourceMedia.size()>10 || sourceMedia.stream().anyMatch(id->id<=0)) throw new IllegalArgumentException("Некорректные материалы.");
        SupportTicket pending=ticket.withStatus("DELIVERY_PENDING");
        guard.queueSupport(pending,List.copyOf(sourceMedia)).get();
        return deliver(pending,sourceMedia);
    }
    synchronized void retryDeliveries() {
        try {
            for(var pending:guard.pendingSupport().get()) deliver(pending.ticket(),pending.sources());
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        catch(Exception failure) {log.warning("Очередь обращений временно недоступна: "+failure.getClass().getSimpleName());}
    }
    private SupportTicket deliver(SupportTicket pending,List<Integer> sources) throws Exception {
        SupportTicket progress=pending;
        try {
            if(progress.forms().isEmpty()) {
                boolean unban=progress.type()==SupportTicket.Type.UNBAN;
                String title=unban?"Заявка на разбан #"+progress.attempt():switch(progress.type()) {
                    case COMPLAINT->"Жалоба";case SUGGESTION->"Предложение";case BUG->"Баг";default->throw new IllegalStateException();};
                String document="# "+title+"\n\nИгрок: "+progress.authorName()+"\nUUID: "+progress.authorUuid()+"\n\n"+progress.body()+"\n";
                int form=api.sendDocument(progress.adminChat(),progress.adminTopic(),
                        unban?"anketa-"+progress.authorUuid()+".md":"obrashchenie-"+progress.id()+".md",document);
                progress=progress.withDelivery(progress.adminMessage(),progress.adminMedia(),List.of(form));
                guard.supportDeliveryProgress(progress,false).get();
            }
            List<Integer> copied=new ArrayList<>(progress.adminMedia());
            for(int index=copied.size();index<sources.size();index++) {
                copied.add(api.copyMessage(progress.authorTelegram(),sources.get(index),progress.adminChat(),progress.adminTopic()));
                progress=progress.withDelivery(progress.adminMessage(),copied,progress.forms());
                guard.supportDeliveryProgress(progress,false).get();
            }
            if(progress.adminMessage()==0) {
                SupportTicket card=progress.withStatus("OPEN");
                int message=api.sendHtml(card.adminChat(),card.adminTopic(),html(card,copied.size()),false,buttons(card));
                progress=progress.withDelivery(message,copied,progress.forms());
                guard.supportDeliveryProgress(progress,false).get();
            }
            SupportTicket sent=progress.withStatus("OPEN");
            guard.supportDeliveryProgress(sent,true).get();
            return sent;
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw interrupted;}
        catch(Exception failure) {
            guard.supportDeliveryRetry(progress.id()).get();
            log.warning("Обращение "+progress.id()+" сохранено и ожидает доставки: "+api.safe(failure));
            return progress;
        }
    }
    synchronized void callback(TelegramApi.Incoming update,AdminMenu admin) throws Exception {
        try {
            String[] parts=update.callbackData().split(":",3);
            if(parts.length!=3 || !parts[1].matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Кнопка недоступна.");
            SupportTicket ticket=guard.support(parts[1]).get();
            if(ticket==null) throw new IllegalArgumentException("Кнопка недоступна.");
            if(ticket.type()==SupportTicket.Type.UNBAN) {
                unbanCallback(update,ticket,parts[0],parts[2],admin);
                return;
            }
            if(!parts[0].equals("vs") || ticket.adminChat()!=update.chatId() || ticket.adminChat()!=topics.chatId() ||
                    ticket.adminTopic()!=update.topicId() || ticket.adminMessage()!=update.messageId())
                throw new IllegalArgumentException("Кнопка недоступна.");
            if(ticket.type()==SupportTicket.Type.COMPLAINT) guard.requireAdmin(update.userId());
            else guard.requireSuper(update.userId());
            String operation=parts[2];
            if(operation.equals("reopen")) {
                if(!ticket.status().equals("CLOSED")) throw new IllegalArgumentException("Обращение уже открыто.");
                var reopened=ticket.withStatus("OPEN").withTakenActor(0).withClosedActor(0);
                if(!guard.supportChange(reopened,"CLOSED").get()) throw new IllegalArgumentException("Статус обращения уже изменился.");
                api.editHtml(ticket.adminChat(),ticket.adminMessage(),html(reopened,ticket.adminMedia().size()),buttons(reopened));
                api.answerCallback(update.callbackId(),"Обращение открыто снова.",false);return;
            }
            if(ticket.status().equals("IGNORED") || ticket.status().equals("CLOSED") || ticket.status().equals("BUSY")) throw new IllegalArgumentException("Обращение уже обработано.");
            if(operation.equals("take")) {
                if(ticket.takenActor()>0) throw new IllegalArgumentException("Обращение уже взяли в работу.");
                var taken=ticket.withStatus(ticket.status().equals("OPEN")?"IN_PROGRESS":ticket.status()).withTakenActor(update.userId());
                if(!guard.supportChange(taken,ticket.status()).get()) throw new IllegalArgumentException("Статус обращения уже изменился.");
                api.editHtml(ticket.adminChat(),ticket.adminMessage(),html(taken,ticket.adminMedia().size()),buttons(taken));
                api.answerCallback(update.callbackId(),"Обращение закреплено за вами.",false);return;
            }
            if(operation.equals("message") && ticket.type()==SupportTicket.Type.COMPLAINT) {
                admin.requireOperation(update.userId(),"privateMessage");
                api.send(update.userId(),0,new TelegramCommands.View("💬 Сообщение игроку "+ticket.targetName(),List.of(
                        admin.root.b(update.userId(),"✉️ Написать","privateMessage",ticket.targetUuid().toString(),0,0))));
                api.answerCallback(update.callbackId(),"Откройте личный чат с ботом.",false);return;
            }
            if(operation.equals("ignore")) {
                if(!guard.supportStatus(ticket.id(),ticket.status(),"BUSY").get()) throw new IllegalArgumentException("Обращение уже меняется.");
                try {
                    for(int id:ticket.adminMedia()) deleteIfPresent(ticket.adminChat(),id);
                    for(int id:ticket.forms()) deleteIfPresent(ticket.adminChat(),id);
                    deleteIfPresent(ticket.adminChat(),ticket.adminMessage());
                    guard.supportStatus(ticket.id(),"BUSY","IGNORED").get();
                } catch(Exception failure) {guard.supportStatus(ticket.id(),"BUSY",ticket.status()).get();throw failure;}
                api.answerCallback(update.callbackId(),"Обращение удалено.",false);return;
            }
            if(operation.equals("close")) {
                var closed=ticket.withStatus("CLOSED").withClosedActor(update.userId());
                if(!guard.supportChange(closed,ticket.status()).get()) throw new IllegalArgumentException("Обращение уже меняется.");
                api.editHtml(ticket.adminChat(),ticket.adminMessage(),html(closed,ticket.adminMedia().size()),buttons(closed));
                api.answerCallback(update.callbackId(),"Обращение закрыто.",false);return;
            }
            String next;
            if(ticket.type()==SupportTicket.Type.COMPLAINT) {
                if(ticket.status().startsWith("FAILED_") || ticket.status().equals("ACTION_"+operation.toUpperCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Это действие уже выполнялось. Проверьте состояние игрока перед повтором.");
                String action=switch(operation) {case "kick"->"kick";case "ban"->"permanentBan";case "ban5"->"ban";
                    case "freeze"->"freeze";case "unfreeze"->"unfreeze";default->throw new IllegalArgumentException("Неизвестное действие.");};
                admin.requireOperation(update.userId(),action);
                if(!guard.supportStatus(ticket.id(),ticket.status(),"BUSY").get()) throw new IllegalArgumentException("Обращение уже меняется.");
                try {admin.perform(update.userId(),action,ticket.targetUuid().toString());}
                catch(Exception failure) {guard.supportStatus(ticket.id(),"BUSY","FAILED_"+operation.toUpperCase(Locale.ROOT)).get();throw failure;}
                next="ACTION_"+operation.toUpperCase(Locale.ROOT);
            } else {
                next=switch(ticket.type()) {
                    case SUGGESTION -> switch(operation) {case "doing"->"DOING";case "vote"->"VOTE";case "maybe"->"MAYBE";default->throw new IllegalArgumentException("Неизвестный статус.");};
                    case BUG -> switch(operation) {case "urgent"->"URGENT";case "later"->"LATER";case "wait"->"WAIT";default->throw new IllegalArgumentException("Неизвестный статус.");};
                    default -> throw new IllegalArgumentException("Неизвестный статус.");
                };
                if(!guard.supportStatus(ticket.id(),ticket.status(),"BUSY").get()) throw new IllegalArgumentException("Обращение уже меняется.");
            }
            var changed=ticket.withStatus(next).withTakenActor(ticket.takenActor()>0?ticket.takenActor():update.userId());
            if(!guard.supportChange(changed,"BUSY").get()) throw new IllegalArgumentException("Статус обращения уже изменился.");
            api.editHtml(ticket.adminChat(),ticket.adminMessage(),html(changed,ticket.adminMedia().size()),buttons(changed));
            api.answerCallback(update.callbackId(),"Готово.",false);
        } catch(Exception failure) {
            if(!(failure instanceof SecurityException || failure instanceof IllegalArgumentException))
                log.warning("Действие по обращению не выполнено: "+api.safe(failure));
            api.answerCallback(update.callbackId(),failure instanceof SecurityException?"Нет прав на это действие.":
                    failure instanceof IllegalArgumentException?failure.getMessage():"Действие не выполнено; проверьте права и доступность бота.",true);
        }
    }
    private static TelegramModeration.BanSnapshot snapshot(GuardService.UnbanState state) {
        return new TelegramModeration.BanSnapshot(state.banReason(),state.banExpiry(),state.banSource());
    }
    private void checkUnbanRole(long user,String operation) {
        guard.requireAdmin(user);guard.requireCapability(user,"unban");
        if(operation.equals("talk")) guard.requireCapability(user,"freeze");
    }
    private static boolean finished(String status) {
        return Set.of("CLOSED","UNBANNED","REJECTED","EXPIRED","BUSY","UNBAN_PENDING").contains(status);
    }
    private void updateUnbanCard(SupportTicket ticket) {
        try {api.editHtml(ticket.adminChat(),ticket.adminMessage(),html(ticket,ticket.adminMedia().size()),buttons(ticket));}
        catch(Exception failure) {log.warning("Карточка заявки ожидает обновления Telegram: "+api.safe(failure));}
    }
    private void unbanCallback(TelegramApi.Incoming update,SupportTicket ticket,String prefix,String operation,AdminMenu admin) throws Exception {
        if(prefix.equals("vu")) {playerUnbanCallback(update,ticket,operation);return;}
        if(prefix.equals("vs")) {
            if(update.privateChat() || ticket.adminChat()!=update.chatId() || ticket.adminChat()!=topics.chatId() ||
                    ticket.adminTopic()!=update.topicId() || ticket.adminMessage()!=update.messageId())
                throw new IllegalArgumentException("Кнопка недоступна.");
            if(!Set.of("talk","unban","close").contains(operation)) throw new IllegalArgumentException("Кнопка недоступна.");
            checkUnbanRole(update.userId(),operation);
            if(finished(ticket.status()) || operation.equals("talk") && !ticket.status().equals("OPEN"))
                throw new IllegalArgumentException("Заявка уже меняется или закрыта.");
            String label=switch(operation) {case "talk"->"вызвать на разговор и временно снять бан";
                case "unban"->"полностью разбанить";default->"закрыть без разбана";};
            confirmations.entrySet().removeIf(entry->entry.getValue().expires()<System.currentTimeMillis());
            if(confirmations.size()>2048) confirmations.clear();
            String key=ticket.id()+"|"+update.userId();
            int message=api.send(update.userId(),0,new TelegramCommands.View("Подтвердите действие: "+label+" игрока "+ticket.authorName()+" ("+ticket.authorUuid()+").",List.of(
                    new TelegramCommands.Button("✅ Подтвердить","vc:"+ticket.id()+":"+operation,0),
                    new TelegramCommands.Button("❌ Отмена","vc:"+ticket.id()+":cancel",1))));
            confirmations.put(key,new Confirmation(update.userId(),operation,message,System.currentTimeMillis()+120_000));
            api.answerCallback(update.callbackId(),"Подтвердите действие в личном чате с ботом.",false);return;
        }
        if(!prefix.equals("vc") || !update.privateChat()) throw new IllegalArgumentException("Кнопка недоступна.");
        String key=ticket.id()+"|"+update.userId();Confirmation confirmation=confirmations.remove(key);
        if(confirmation==null || confirmation.message()!=update.messageId() || confirmation.expires()<System.currentTimeMillis() ||
                !operation.equals("cancel") && !operation.equals(confirmation.operation()))
            throw new IllegalArgumentException("Подтверждение устарело.");
        checkUnbanRole(update.userId(),confirmation.operation());
        if(operation.equals("cancel")) {api.answerCallback(update.callbackId(),"Действие отменено.",false);return;}
        SupportTicket current=guard.support(ticket.id()).get();
        if(current==null || finished(current.status()) || operation.equals("talk") && !current.status().equals("OPEN"))
            throw new IllegalArgumentException("Заявка уже меняется или закрыта.");
        String processing=operation.equals("unban")?"UNBAN_PENDING":"BUSY";
        if(!guard.supportStatus(current.id(),current.status(),processing).get()) throw new IllegalArgumentException("Заявка уже меняется.");
        try {
            switch(operation) {
                case "talk" -> {
                    if(moderation==null || !moderation.freezeAvailable()) throw new IllegalStateException("Заморозка FLEXITY недоступна.");
                    long until=System.currentTimeMillis()+600_000;
                    int message=api.send(current.authorTelegram(),0,new TelegramCommands.View(
                            "Администрация вызывает вас на разговор в Minecraft. Примите приглашение в течение 10 минут.",List.of(
                            new TelegramCommands.Button("✅ Принять","vu:"+current.id()+":accept",0),
                            new TelegramCommands.Button("❌ Отказаться","vu:"+current.id()+":reject",1))));
                    guard.unbanInvite(current.id(),until,message,update.userId()).get();
                    guard.supportStatus(current.id(),"BUSY","INVITED").get();
                    guard.supportActor(current.id(),update.userId()).get();
                    updateUnbanCard(current.withStatus("INVITED").withActor(update.userId()));
                }
                case "unban" -> {
                    guard.supportActor(current.id(),update.userId()).get();
                    moderation.act(update.userId(),"unban",current.authorUuid()).get();
                    guard.supportStatus(current.id(),"UNBAN_PENDING","UNBANNED").get();
                    updateUnbanCard(current.withStatus("UNBANNED").withActor(update.userId()));
                    var state=guard.unbanState(current.id()).get();
                    if(state!=null && state.accessUntil()>0) {
                        try {moderation.restoreAfterConversation(state.uuid(),snapshot(state),state.actor(),false).get();
                            guard.clearUnbanAccess(current.id()).get();}
                        catch(Exception failure) {log.warning("Разморозка после разбана будет повторена: "+failure.getClass().getSimpleName());}
                    }
                    try {api.send(current.authorTelegram(),0,new TelegramCommands.View("Ваша заявка рассмотрена: бан снят."));}catch(Exception ignored) {}
                }
                case "close" -> {
                    var state=guard.unbanState(current.id()).get();
                    if(state!=null && state.accessUntil()>0) {
                        moderation.restoreAfterConversation(state.uuid(),snapshot(state),state.actor(),true).get();
                    }
                    guard.supportStatus(current.id(),"BUSY","CLOSED").get();
                    if(state!=null && state.accessUntil()>0) guard.clearUnbanAccess(current.id()).get();
                    guard.supportActor(current.id(),update.userId()).get();
                    updateUnbanCard(current.withStatus("CLOSED").withActor(update.userId()));
                    try {api.send(current.authorTelegram(),0,new TelegramCommands.View("Ваша заявка на разбан закрыта без снятия бана."));}catch(Exception ignored) {}
                }
                default -> throw new IllegalArgumentException("Неизвестное действие.");
            }
            api.answerCallback(update.callbackId(),"Готово.",false);
        } catch(Exception failure) {
            var latest=guard.support(current.id()).get();
            if(latest!=null && latest.status().equals("UNBAN_PENDING")) throw failure;
            if(latest!=null && latest.status().equals("BUSY")) {
                var state=guard.unbanState(current.id()).get();
                if(state==null || state.accessUntil()==0) guard.supportStatus(current.id(),"BUSY",current.status()).get();
            }
            throw failure;
        }
    }
    private void playerUnbanCallback(TelegramApi.Incoming update,SupportTicket ticket,String operation) throws Exception {
        if(!update.privateChat() || update.userId()!=ticket.authorTelegram() || !Set.of("accept","reject").contains(operation))
            throw new SecurityException("Кнопка недоступна.");
        var state=guard.unbanState(ticket.id()).get();
        if(state==null || state.inviteMessage()!=update.messageId() || !ticket.status().equals("INVITED") ||
                state.inviteUntil()<System.currentTimeMillis()) throw new IllegalArgumentException("Приглашение истекло.");
        if(!guard.supportStatus(ticket.id(),"INVITED","BUSY").get()) throw new IllegalArgumentException("Приглашение уже обработано.");
        if(operation.equals("reject")) {
            guard.supportStatus(ticket.id(),"BUSY","REJECTED").get();
            updateUnbanCard(ticket.withStatus("REJECTED"));
            api.answerCallback(update.callbackId(),"Вы отказались от разговора.",false);return;
        }
        try {
            var facts=moderation.unbanFacts(state.uuid()).get();
            long until=System.currentTimeMillis()+600_000;
            guard.unbanAccess(ticket.id(),until,facts.ban().reason(),facts.ban().expiry(),facts.ban().source()).get();
            moderation.liftForConversation(state.actor(),state.uuid(),facts.ban()).get();
            guard.supportStatus(ticket.id(),"BUSY","ACCESS").get();
            updateUnbanCard(ticket.withStatus("ACCESS"));
            try {api.send(ticket.authorTelegram(),0,new TelegramCommands.View("Вы можете войти в Minecraft для разговора. Доступ открыт на 10 минут; на это время действует заморозка."));}
            catch(Exception failure) {log.warning("Игроку не доставлено подтверждение временного доступа: "+api.safe(failure));}
            api.answerCallback(update.callbackId(),"Приглашение принято.",false);
        } catch(Exception failure) {
            var latest=guard.unbanState(ticket.id()).get();
            if(latest!=null && latest.accessUntil()>0) {
                try {moderation.restoreAfterConversation(latest.uuid(),snapshot(latest),latest.actor(),true).get();
                    guard.supportStatus(ticket.id(),"BUSY","OPEN").get();guard.clearUnbanAccess(ticket.id()).get();}
                catch(Exception restoreFailure) {log.warning("Исходный бан ожидает повторного восстановления: "+restoreFailure.getClass().getSimpleName());}
            } else guard.supportStatus(ticket.id(),"BUSY","OPEN").get();
            throw failure;
        }
    }
    synchronized void expirePending() {
        if(moderation==null) return;
        try {
            long now=System.currentTimeMillis();
            for(var state:guard.unbanPending().get()) {
              try {
                SupportTicket ticket=guard.support(state.id()).get();if(ticket==null) continue;
                if(ticket.status().equals("UNBAN_PENDING")) {
                    try {
                        guard.requireCapability(ticket.actionActor(),"unban");
                        moderation.act(ticket.actionActor(),"unban",state.uuid()).get();
                        guard.supportStatus(ticket.id(),"UNBAN_PENDING","UNBANNED").get();
                        updateUnbanCard(ticket.withStatus("UNBANNED"));
                        if(state.accessUntil()>0) {moderation.restoreAfterConversation(state.uuid(),snapshot(state),state.actor(),false).get();guard.clearUnbanAccess(ticket.id()).get();}
                    } catch(SecurityException revoked) {
                        if(state.accessUntil()>0) {
                            moderation.restoreAfterConversation(state.uuid(),snapshot(state),state.actor(),true).get();
                            guard.supportStatus(ticket.id(),"UNBAN_PENDING","CLOSED").get();
                            guard.clearUnbanAccess(ticket.id()).get();updateUnbanCard(ticket.withStatus("CLOSED"));
                        } else if(moderation.currentlyBanned(state.uuid()).get()) {
                            guard.supportStatus(ticket.id(),"UNBAN_PENDING","OPEN").get();updateUnbanCard(ticket.withStatus("OPEN"));
                        } else {
                            guard.supportStatus(ticket.id(),"UNBAN_PENDING","UNBANNED").get();updateUnbanCard(ticket.withStatus("UNBANNED"));
                        }
                    }
                } else if(ticket.status().equals("INVITED") && state.inviteUntil()>0 && state.inviteUntil()<=now) {
                    if(!guard.supportStatus(ticket.id(),"INVITED","EXPIRED").get()) continue;
                    updateUnbanCard(ticket.withStatus("EXPIRED"));
                    try {api.send(ticket.authorTelegram(),0,new TelegramCommands.View("Время ответа на приглашение истекло. Заявка закрыта."));}catch(Exception ignored) {}
                } else if(ticket.status().equals("UNBANNED") && state.accessUntil()>0) {
                    moderation.restoreAfterConversation(state.uuid(),snapshot(state),state.actor(),false).get();
                    guard.clearUnbanAccess(ticket.id()).get();
                } else if(state.accessUntil()>0 && state.accessUntil()<=now && Set.of("ACCESS","BUSY").contains(ticket.status())) {
                    if(ticket.status().equals("ACCESS") && !guard.supportStatus(ticket.id(),"ACCESS","BUSY").get()) continue;
                    moderation.restoreAfterConversation(state.uuid(),snapshot(state),state.actor(),true).get();
                    guard.supportStatus(ticket.id(),"BUSY","CLOSED").get();
                    guard.clearUnbanAccess(ticket.id()).get();
                    updateUnbanCard(ticket.withStatus("CLOSED"));
                    try {api.send(ticket.authorTelegram(),0,new TelegramCommands.View("Время временного доступа истекло. Исходный бан восстановлен, если его срок ещё действовал."));}catch(Exception ignored) {}
                }
              } catch(Exception failure) {log.warning("Проверка заявки "+state.id()+" будет повторена: "+failure.getClass().getSimpleName());}
            }
        } catch(Exception failure) {log.warning("Проверка временного доступа будет повторена: "+failure.getClass().getSimpleName());}
    }
    private void deleteIfPresent(long chat,int message) throws Exception {
        try {api.delete(chat,message);} catch(Exception failure) {
            if(!String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT).contains("message to delete not found")) throw failure;
        }
    }
    private String actorName(long telegram) {
        try {
            var account=guard.account(telegram).get();
            if(account!=null && account.name()!=null && !account.name().isBlank()) return account.name();
        } catch(Exception failure) {log.warning("Не удалось получить имя администратора: "+failure.getClass().getSimpleName());}
        String profile=guard.telegramProfile(telegram);
        return profile.equals("Telegram-пользователь")?"Telegram-пользователь":profile;
    }
    private String html(SupportTicket ticket,int mediaCount) {
        String type=switch(ticket.type()) {case COMPLAINT->"🚨 Жалоба";case SUGGESTION->"💡 Предложение";case BUG->"🐞 Баг";case UNBAN->"🔓 Заявка на разбан";};
        String time=Instant.ofEpochMilli(ticket.created()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String status=switch(ticket.status()) {case "OPEN"->"Новое";case "IN_PROGRESS"->"В работе";case "DOING"->"Делаем";case "VOTE"->"На голосование";
            case "MAYBE"->"Возможно";case "URGENT"->"Срочный";case "LATER"->"Как будет время";case "WAIT"->"Подождёт";case "CLOSED"->"Закрыто";
            case "INVITED"->"Ожидает ответа на разговор";case "ACCESS"->"Временный доступ для разговора";
            case "UNBANNED"->"Разбанен";case "REJECTED"->"Игрок отказался от разговора";case "EXPIRED"->"Время ответа истекло";
            default -> ticket.status().startsWith("ACTION_")?"Действие: "+switch(ticket.status().substring(7)) {
                case "KICK"->"кик";case "BAN"->"бан";case "BAN5"->"бан 5 минут";
                case "FREEZE"->"заморозка";case "UNFREEZE"->"разморозка";default->ticket.status().substring(7);
            }:ticket.status();};
        String materials="";
        if(mediaCount>0) {
            materials="\nМатериалы: "+mediaCount;
            if(ticket.adminChat() < -1000000000000L) {
                String chat=Long.toString(ticket.adminChat()).substring(4);
                for(int i=0;i<ticket.adminMedia().size();i++)
                    materials+=" · <a href=\"https://t.me/c/"+chat+"/"+ticket.adminMedia().get(i)+"\">"+(i+1)+"</a>";
            }
        }
        if(ticket.type()==SupportTicket.Type.UNBAN) {
            StringBuilder form=new StringBuilder();
            if(ticket.adminChat() < -1000000000000L) {
                String chat=Long.toString(ticket.adminChat()).substring(4);
                for(int id:ticket.forms()) form.append(" <a href=\"https://t.me/c/").append(chat).append('/').append(id).append("\">открыть</a>");
            }
            String answers=ticket.forms().isEmpty()?"\nАнкета: "+esc(ticket.body().substring(0,Math.min(230,ticket.body().length())))+
                    (ticket.body().length()>230?"…":""):"\nАнкета: файл выше ·"+form;
            return "<b>"+type+" #"+ticket.attempt()+"</b>\nИгрок: "+esc(ticket.authorName())+" · <code>"+ticket.authorUuid()+"</code>"+
                    "\nTelegram: "+esc(ticket.linked()?"аккаунт связан":"без привязки")+" · ID <code>"+ticket.authorTelegram()+"</code>"+
                    "\nВремя: "+time+"\nСтатус: "+esc(status)+(ticket.actionActor()>0?
                    " · "+(ticket.status().equals("CLOSED")?"закрыл: ":"администратор: ")+esc(actorName(ticket.actionActor())):"")+
                    "\n"+esc(ticket.facts()==null?"":ticket.facts())+answers+materials;
        }
        String description=esc(ticket.body());
        if(!ticket.forms().isEmpty()) {
            String link="";
            if(ticket.adminChat() < -1000000000000L)
                link=" · <a href=\"https://t.me/c/"+Long.toString(ticket.adminChat()).substring(4)+"/"+ticket.forms().get(0)+"\">открыть файл</a>";
            description="Описание: "+esc(ticket.body().substring(0,Math.min(220,ticket.body().length())))+
                    (ticket.body().length()>220?"…":"")+link;
        }
        return "<b>"+type+"</b>\nОт: "+esc(ticket.authorName())+" · <code>"+ticket.authorUuid()+"</code> · TG <code>"+ticket.authorTelegram()+"</code>"+
                (ticket.targetUuid()==null?"":"\nНа: "+esc(ticket.targetName())+" · <code>"+ticket.targetUuid()+"</code>")+"\nВремя: "+time+
                "\nСтатус: "+esc(status)+
                (ticket.takenActor()>0?"\nВзялся: "+esc(actorName(ticket.takenActor())):"")+
                (ticket.closedActor()>0?"\nЗакрыл: "+esc(actorName(ticket.closedActor())):"")+
                materials+"\n\n"+description;
    }
    private static String esc(String value) {return TelegramChatFormat.escape(value);}
    private static List<TelegramCommands.Button> buttons(SupportTicket ticket) {
        List<TelegramCommands.Button> result=new ArrayList<>();String id="vs:"+ticket.id()+":";
        if(ticket.type()==SupportTicket.Type.UNBAN) {
            if(Set.of("CLOSED","UNBANNED","REJECTED","EXPIRED","BUSY","UNBAN_PENDING").contains(ticket.status())) return List.of();
            if(ticket.status().equals("OPEN")) result.add(new TelegramCommands.Button("💬 На разговор",id+"talk",0));
            result.add(new TelegramCommands.Button("🔓 Разбан",id+"unban",1));
            result.add(new TelegramCommands.Button("✅ Закрыть",id+"close",2));
            return List.copyOf(result);
        }
        if(ticket.status().equals("CLOSED")) return List.of(new TelegramCommands.Button("🔄 Открыть снова",id+"reopen",0));
        if(ticket.takenActor()==0) result.add(new TelegramCommands.Button("🙋 Взяться",id+"take",0));
        int firstRow=result.isEmpty()?0:1;
        if(ticket.type()==SupportTicket.Type.COMPLAINT) {
            String[] labels={"Кик","Бан","Бан 5 минут","Заморозить","Разморозить","Сообщение игроку"};
            String[] actions={"kick","ban","ban5","freeze","unfreeze","message"};
            for(int i=0;i<labels.length;i++) result.add(new TelegramCommands.Button(labels[i],id+actions[i],firstRow+i/2));
        } else {
            String[] labels=ticket.type()==SupportTicket.Type.SUGGESTION?
                    new String[]{"✅ Делаем","🗳 На голосование","🤔 Возможно"}:new String[]{"🚨 Срочный","🕒 Как будет время","💤 Подождёт"};
            String[] actions=ticket.type()==SupportTicket.Type.SUGGESTION?
                    new String[]{"doing","vote","maybe"}:new String[]{"urgent","later","wait"};
            for(int i=0;i<labels.length;i++) result.add(new TelegramCommands.Button((ticket.status().equals(actions[i].toUpperCase(Locale.ROOT))?"✓ ":"")+labels[i],id+actions[i],firstRow+i));
        }
        result.add(new TelegramCommands.Button("✅ Закрыть",id+"close",firstRow+3));
        return List.copyOf(result);
    }
}
