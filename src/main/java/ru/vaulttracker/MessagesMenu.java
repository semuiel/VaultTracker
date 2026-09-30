package ru.vaulttracker;

import java.util.*;

/** Telegram UI screens for messages. */
final class MessagesMenu extends TelegramMenuSection {
    MessagesMenu(TelegramGuardMenu root) {super(root);}

    void rememberDialogCard(long user,int messageId) {if(messageId>0) dialogCards.put(user,messageId);}
    int takeDialogEditTarget(long user) {return editedDialogs.getOrDefault(user,0)==0?0:editedDialogs.remove(user);}
    TelegramCommands.View dialogs(long user,int requested) throws Exception {
        if(guard.account(user).get()==null) return root.home(user);
        int page=Math.max(0,requested),size=10;
        List<GuardService.Dialog> rows=guard.dialogs(user,page,size).get();
        if(rows.isEmpty()&&page>0) return dialogs(user,page-1);
        boolean more=rows.size()>size;if(more) rows=rows.subList(0,size);
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        buttons.add(b(user,"✉️ Написать игроку","messagePlayers","",0,row++));
        for(var dialog:rows) buttons.add(b(user,dialog.name()+(dialog.unread()>0?" · "+dialog.unread():""),"dialog",dialog.peer().toString(),0,row++));
        if(page>0) buttons.add(b(user,"⬅ Назад","messages","",page-1,row));
        if(more) buttons.add(b(user,"Вперёд ➡","messages","",page+1,row));
        buttons.add(new TelegramCommands.Button("↩ Личный кабинет","vg:home",row+1));
        buttons.add(homeButton(row+2));
        return new TelegramCommands.View("💬 Сообщения"+(rows.isEmpty()?"\nДиалогов пока нет.":"\nНепрочитанные диалоги — первыми."),List.copyOf(buttons));
    }
    TelegramCommands.View messagePlayers(long user,int requested) throws Exception {
        var account=guard.account(user).get();if(account==null) return root.home(user);
        var players=guard.onlineLinked(account.uuid());int size=6,pages=Math.max(1,(players.size()+size-1)/size);
        int page=Math.max(0,Math.min(requested,pages-1));List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        for(int i=page*size;i<Math.min(players.size(),(page+1)*size);i++) {
            var player=players.get(i);buttons.add(b(user,"🟢 "+player.name(),"dialog",player.uuid().toString(),0,row++));
        }
        if(page>0) buttons.add(b(user,"⬅ Назад","messagePlayers","",page-1,row));
        if(page+1<pages) buttons.add(b(user,"Вперёд ➡","messagePlayers","",page+1,row));
        buttons.add(b(user,"↩ Сообщения","messages","",0,row+1));buttons.add(homeButton(row+2));
        return new TelegramCommands.View("✉️ Кому написать?"+(players.isEmpty()?"\nСейчас нет игроков онлайн с привязанным Telegram.":"\nИгроки онлайн · "+(page+1)+"/"+pages),List.copyOf(buttons));
    }
    TelegramCommands.View dialog(long user,UUID peer,int requested) throws Exception {
        var account=guard.account(user).get();if(account==null) return root.home(user);
        int page=Math.max(0,requested);var conversation=guard.conversation(user,peer,page,10).get();
        if(conversation.messages().isEmpty()&&page>0) return dialog(user,peer,page-1);
        String name=guard.directDisplayName(peer).get();
        StringBuilder text=new StringBuilder("💬 ").append(name).append('\n');
        for(var message:conversation.messages()) {
            String who=message.sender().equals(account.uuid())?"↗ Вы":"↙ "+message.senderName();
            String time=java.time.Instant.ofEpochMilli(message.created()).atZone(java.time.ZoneId.of("Europe/Moscow"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
            text.append(who).append(" · ").append(time).append("\n").append(shortMessage(message.text())).append('\n');
        }
        if(conversation.messages().isEmpty()) text.append("Сообщений пока нет.\n");
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        if(guard.linkedPlayer(peer).get()!=null) buttons.add(b(user,"✉️ Написать","dialogCompose",peer.toString(),0,row++));
        if(conversation.older()) buttons.add(b(user,"⬅ Ранее","dialog",peer.toString(),page+1,row));
        if(page>0) buttons.add(b(user,"Позже ➡","dialog",peer.toString(),page-1,row));
        buttons.add(b(user,"↩ Сообщения","messages","",0,row+1));
        return new TelegramCommands.View(text.toString().stripTrailing(),List.copyOf(buttons));
    }
    static String shortMessage(String value) {
        String clean=value.replaceAll("\\s+"," ").strip();int points=clean.codePointCount(0,clean.length());
        return points<=180?clean:clean.substring(0,clean.offsetByCodePoints(0,179)).stripTrailing()+"…";
    }

    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("MessagesMenu");
        scope.directPrefix("vg:dialog:",(user,data)->dialog(user,UUID.fromString(data.substring("vg:dialog:".length())),0));
        scope.on("messages",TelegramActionRegistry.Arguments.PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=dialogs(user,a.page());
            return view;
        });
        scope.on("messagePlayers",TelegramActionRegistry.Arguments.PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=messagePlayers(user,a.page());
            return view;
        });
        scope.on("dialog",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=dialog(user,UUID.fromString(a.value()),a.page());
            return view;
        });
        scope.on("dialogCompose",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {if(guard.account(user).get()==null) throw new SecurityException("Сначала привяжите персонажа");view=prompt(user,"dialogSend:"+a.value(),"Напишите сообщение игроку: одна строка, до 500 символов.");}
            return view;
        });
    }

    TelegramCommands.View onInputEarly(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().startsWith("dialogSend:")) {
            if(pending.expires()<System.currentTimeMillis()) {waiting.remove(user);return new TelegramCommands.View("Время ввода истекло. Откройте диалог снова.");}
            if(text==null||text.isBlank()||text.length()>500||text.contains("\n")||text.contains("\r")) return new TelegramCommands.View("Введите одну строку до 500 символов. Отмена: /cancel.");
            UUID peer=UUID.fromString(pending.kind().substring("dialogSend:".length()));
            moderation.directMessages().fromTelegram(user,peer,text).get();waiting.remove(user);
            Integer card=dialogCards.get(user);if(card!=null) editedDialogs.put(user,card);
            return dialog(user,peer,0);
        }
        return null;
    }
}
