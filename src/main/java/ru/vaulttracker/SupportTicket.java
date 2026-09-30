package ru.vaulttracker;

import java.util.List;
import java.util.UUID;

/** Persisted request; Telegram message IDs identify its card and copied attachments. */
record SupportTicket(String id,Type type,long authorTelegram,UUID authorUuid,String authorName,
                     UUID targetUuid,String targetName,String body,long created,long adminChat,int adminTopic,
                     int adminMessage,List<Integer> adminMedia,String status,List<Integer> formMessages,
                     boolean linked,int attempt,String facts,long actionActor,long takenActor,long closedActor) {
    enum Type {COMPLAINT,SUGGESTION,BUG,UNBAN}
    SupportTicket(String id,Type type,long authorTelegram,UUID authorUuid,String authorName,
                  UUID targetUuid,String targetName,String body,long created,long adminChat,int adminTopic,
                  int adminMessage,List<Integer> adminMedia,String status) {
        this(id,type,authorTelegram,authorUuid,authorName,targetUuid,targetName,body,created,
                adminChat,adminTopic,adminMessage,adminMedia,status,List.of(),true,0,null,0);
    }
    SupportTicket(String id,Type type,long authorTelegram,UUID authorUuid,String authorName,
                  UUID targetUuid,String targetName,String body,long created,long adminChat,int adminTopic,
                  int adminMessage,List<Integer> adminMedia,String status,List<Integer> formMessages,
                  boolean linked,int attempt,String facts,long actionActor) {
        this(id,type,authorTelegram,authorUuid,authorName,targetUuid,targetName,body,created,
                adminChat,adminTopic,adminMessage,adminMedia,status,formMessages,linked,attempt,facts,actionActor,0,0);
    }
    SupportTicket withStatus(String next) {return new SupportTicket(id,type,authorTelegram,authorUuid,authorName,
            targetUuid,targetName,body,created,adminChat,adminTopic,adminMessage,adminMedia,next,
            formMessages,linked,attempt,facts,actionActor,takenActor,closedActor);}
    SupportTicket withActor(long actor) {return new SupportTicket(id,type,authorTelegram,authorUuid,authorName,
            targetUuid,targetName,body,created,adminChat,adminTopic,adminMessage,adminMedia,status,
            formMessages,linked,attempt,facts,actor,takenActor,closedActor);}
    SupportTicket withTakenActor(long actor) {return new SupportTicket(id,type,authorTelegram,authorUuid,authorName,
            targetUuid,targetName,body,created,adminChat,adminTopic,adminMessage,adminMedia,status,
            formMessages,linked,attempt,facts,actionActor,actor,closedActor);}
    SupportTicket withClosedActor(long actor) {return new SupportTicket(id,type,authorTelegram,authorUuid,authorName,
            targetUuid,targetName,body,created,adminChat,adminTopic,adminMessage,adminMedia,status,
            formMessages,linked,attempt,facts,actionActor,takenActor,actor);}
    List<Integer> forms() {return formMessages==null?List.of():formMessages;}
}
