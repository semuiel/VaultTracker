package ru.vaulttracker;

import java.util.*;

/** Telegram UI screens for friends. */
final class FriendsMenu extends TelegramMenuSection {
    FriendsMenu(TelegramGuardMenu root) {super(root);}

    void requireShared(long user,UUID owner) throws Exception {
        var account=guard.account(user).get();
        if(account==null||!guard.canSearch(account.uuid(),owner)) throw new SecurityException("Владелец не открыл вам доступ к ресурсам");
    }
    TelegramCommands.View sharedOwners(long user,int requested) throws Exception {
        var account=guard.account(user).get();if(account==null) return root.home(user);
        var owners=commands.sharedOwners(guard.sharedOwners(account.uuid()));int size=6,pages=Math.max(1,(owners.size()+size-1)/size),page=Math.max(0,Math.min(requested,pages-1));
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        for(int i=page*size;i<Math.min(owners.size(),(page+1)*size);i++) {var owner=owners.get(i);buttons.add(b(user,owner.name(),"sharedResources",owner.owner().toString(),0,i-page*size));}
        if(page>0) buttons.add(b(user,"◀ Назад","sharedOwners","",page-1,size));
        if(page+1<pages) buttons.add(b(user,"Вперёд ▶","sharedOwners","",page+1,size));
        buttons.add(b(user,"↩️ Ресурсы","resourcesMenu","",0,size+1));buttons.add(homeButton(size+2));
        return new TelegramCommands.View("👥 Ресурсы друзей · "+(page+1)+"/"+pages+"\n"+(owners.isEmpty()?"Нет доступных хранилищ.":"Выберите игрока, который открыл вам доступ к своим хранилищам."),List.copyOf(buttons));
    }
    TelegramCommands.View friendView(long user,UUID uuid) throws Exception {
        var friend=guard.friends(user).get().stream().filter(f->f.uuid().equals(uuid)).findFirst().orElseThrow(()->new SecurityException("Друг больше не в списке"));
        return new TelegramCommands.View("👤 "+friend.name()+"\nНастройки уведомлений и доступа к вашим хранилищам.",List.of(
            b(user,(friend.muted()?"✅":"⬜")+" Не получать уведомления","friendMute",uuid.toString(),0,0),
            b(user,(friend.shared()?"✅":"⬜")+" Разрешить поиск ресурсов","friendShare",uuid.toString(),0,1),
            b(user,(friend.notified()?"✅":"⬜")+" Отправлять другу уведомления","friendNotify",uuid.toString(),0,2),
            b(user,"❌ Удалить друга","friendRemoveConfirm",uuid.toString(),0,3),b(user,"↩ Друзья","friends","",0,4),homeButton(4)));
    }
    TelegramCommands.View friends(long user,int requestedPage) throws Exception {
        var entries=guard.friends(user).get();int pages=Math.max(1,(entries.size()+PAGE_SIZE-1)/PAGE_SIZE),page=Math.max(0,Math.min(requestedPage,pages-1));
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        for(var friend:entries.subList(page*PAGE_SIZE,Math.min(entries.size(),(page+1)*PAGE_SIZE)))
            buttons.add(b(user,"👤 "+friend.name(),"friendView",friend.uuid().toString(),page,row++));
        if(page>0) buttons.add(b(user,"◀ Назад","friends","",page-1,row));
        if(page+1<pages) buttons.add(b(user,"Вперёд ▶","friends","",page+1,row));
        buttons.add(b(user,"➕ Добавить друга","friendAdd","",0,++row));
        buttons.add(b(user,"↩ Настройки","settings","",0,++row));buttons.add(homeButton(++row));
        String text="👥 Друзья · "+(page+1)+"/"+pages+"\nВыберите друга, чтобы настроить уведомления и доступ к вашим ресурсам.";
        if(entries.isEmpty()) text+="\nСписок пуст.";
        return new TelegramCommands.View(text,List.copyOf(buttons));
    }

    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("FriendsMenu");
        scope.direct("vg:sharedOwners",(user,data)->sharedOwners(user,0));
        scope.directPrefix("vg:sharedSearch:",(user,data)-> {
            UUID owner=UUID.fromString(data.substring("vg:sharedSearch:".length()));
            requireShared(user,owner);
            return prompt(user,"sharedSearch:"+owner,"Введите название ресурса на русском или английском.");
        });
        scope.on("sharedOwners",TelegramActionRegistry.Arguments.PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=sharedOwners(user,a.page());
            return view;
        });
        scope.on("sharedResources",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {UUID owner=UUID.fromString(a.value());requireShared(user,owner);view=withHome(commands.sharedResources(user,owner));}
            return view;
        });
        scope.on("sharedResults",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {String[] parts=a.value().split("\\|",2);view=root.resourceResults(user,parts[1],a.page(),false,UUID.fromString(parts[0]));}
            return view;
        });
        scope.on("friendView",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=friendView(user,UUID.fromString(a.value()));
            return view;
        });
        TelegramActionRegistry.ViewHandler toggleFriend=(user,a,data)-> {
            UUID friend=UUID.fromString(a.value());
            guard.toggleFriend(user,friend,a.op().equals("friendShare")).get();
            return friendView(user,friend);
        };
        scope.on("friendMute",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false,toggleFriend);
        scope.on("friendShare",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false,toggleFriend);
        scope.on("friendNotify",TelegramActionRegistry.Arguments.VALUE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.toggleFriendNotifications(user,UUID.fromString(a.value())).get();view=friendView(user,UUID.fromString(a.value()));}
            return view;
        });
        scope.on("friends",TelegramActionRegistry.Arguments.PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            view=friends(user,a.page());
            return view;
        });
        scope.on("friendAdd",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {if(guard.account(user).get()==null) throw new SecurityException("Сначала привяжите персонажа");view=prompt(user,"friendAdd","Введите точный игровой ник друга.");}
            return view;
        });
        scope.on("friendRemoveConfirm",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {var friend=guard.friends(user).get().stream().filter(f->f.uuid().toString().equals(a.value())).findFirst().orElse(null);
                            view=friend==null?friends(user,a.page()):new TelegramCommands.View("Удалить "+friend.name()+" из друзей? Доступ к ресурсам будет закрыт, уведомления восстановлены.",List.of(root.b(user,"Да, удалить","friendRemove",a.value(),a.page(),0),root.b(user,"↩ Назад","friends","",a.page(),1),homeButton(2)));}
            return view;
        });
        scope.on("friendRemove",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,TelegramActionRegistry.Requirement.HANDLER,false, (user,a,data)-> {
            TelegramCommands.View view;
            {guard.removeFriend(user,UUID.fromString(a.value())).get();view=friends(user,a.page());}
            return view;
        });
    }

    TelegramCommands.View onInput(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().equals("friendAdd")) {
            if(guard.account(user).get()==null) {waiting.remove(user);return root.home(user);}
            String nickname=text==null?"":text.strip();if(nickname.isEmpty()||nickname.length()>64) return new TelegramCommands.View("Введите игровой ник длиной от 1 до 64 символов. Отмена: /cancel.");
            if(moderation==null) return new TelegramCommands.View("Поиск игроков временно недоступен. Повторите позже.");
            try {var player=moderation.playerByName(nickname).get();guard.addFriend(user,player.uuid(),player.name()).get();waiting.remove(user);return friends(user,0);}
            catch(java.util.concurrent.ExecutionException failure) {
                if(failure.getCause() instanceof IllegalArgumentException invalid) return new TelegramCommands.View(invalid.getMessage()+"\nПопробуйте ещё раз. Отмена: /cancel.",List.of(root.b(user,"↩ Друзья","friends","",0,0),homeButton(1)));
                throw failure;
            }
        }
        return null;
    }
}
