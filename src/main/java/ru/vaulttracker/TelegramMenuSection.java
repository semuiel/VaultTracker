package ru.vaulttracker;

import java.util.*;

/** Shared transport state for the cabinet screens; all sections use one callback registry. */
abstract class TelegramMenuSection {
    static final int PAGE_SIZE=TelegramConfig.BUTTON_PAGE_SIZE,TEXT_PAGE_SIZE=TelegramConfig.LIST_PAGE_SIZE;
    final TelegramGuardMenu root;
    final GuardService guard;
    final TelegramCommands commands;
    final TelegramModeration moderation;
    final TelegramTagManager tags;
    final TelegramApi api;
    final Map<Long,TelegramGuardMenu.Input> waiting;
    final Map<Long,Integer> dialogCards,editedDialogs;
    final Map<String,Integer> playerPages;
    final Set<Long> adminPreview;

    TelegramMenuSection(TelegramGuardMenu root) {
        this.root=root;
        guard=root.guard;commands=root.commands;moderation=root.moderation;tags=root.tags;api=root.api;
        waiting=root.waiting;dialogCards=root.dialogCards;editedDialogs=root.editedDialogs;
        playerPages=root.playerPages;adminPreview=root.adminPreview;
    }
    TelegramCommands.Button b(long user,String label,String op,String value,int page,int row) {return root.b(user,label,op,value,page,row);}
    TelegramCommands.View prompt(long user,String kind,String text) {return root.prompt(user,kind,text);}
    void cancelInput(long user) {root.cancelInput(user);}
    static TelegramCommands.Button homeButton(int row) {return TelegramGuardMenu.homeButton(row);}
    static TelegramCommands.View withHome(TelegramCommands.View view) {return TelegramGuardMenu.withHome(view);}
}
