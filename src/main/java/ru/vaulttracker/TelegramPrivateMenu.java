package ru.vaulttracker;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.Material;

/** Personal search state. Group messages never enter this handler. */
final class TelegramPrivateMenu {
    private enum Mode { HOME, SEARCH, PLAYERS, ITEMS, RESULT }
    private enum ActionKind { HOME, SEARCH, PLAYERS, ITEMS, PAGE, SELECT, RESULT_PAGE, BACK }
    private record Key(long userId,long chatId,int topicId) {}
    private record Action(ActionKind kind,String value) {}
    private static final long TTL_MS=30*60*1000L;
    private static final class State {
        Mode mode=Mode.HOME, origin=Mode.SEARCH;
        List<String> choices=List.of();
        int page=1;
        long touched;
        String token;
        TelegramCommands.View result;
        final List<Action> actions=new ArrayList<>();
    }
    private final Catalogue catalogue;
    private final StorageEngine storage;
    private final TelegramCommands commands;
    private final int pageSize;
    private final Predicate<String> validItem;
    private final LongSupplier clock;
    private final Map<Key,State> states=new HashMap<>();

    TelegramPrivateMenu(Catalogue catalogue,StorageEngine storage,TelegramCommands commands,int pageSize) {
        this(catalogue,storage,commands,pageSize,id -> {
            Material material=Material.getMaterial(id);
            return material!=null && material.isItem();
        },System::currentTimeMillis);
    }
    TelegramPrivateMenu(Catalogue catalogue,StorageEngine storage,TelegramCommands commands,int pageSize,
                        Predicate<String> validItem,LongSupplier clock) {
        this.catalogue=catalogue; this.storage=storage; this.commands=commands;
        this.pageSize=Math.max(1,pageSize); this.validItem=validItem; this.clock=clock;
    }

    TelegramCommands.View handle(long userId,long chatId,int topicId,String input,boolean admin) {
        expire();
        Key key=new Key(userId,chatId,topicId);
        String text=input==null ? "" : input.trim();
        String command=TelegramCommands.command(text);
        if(text.startsWith("/")) {
            State state=new State(); states.put(key,state);
            if(Set.of("/start","/menu","/help","/cancel").contains(command)) {
                String notice=command.equals("/cancel") ? "Поиск отменён." : "";
                if(command.equals("/help")) notice="Выберите поиск кнопками или используйте /item предмет, /item Ник.\n/cancel — отменить ввод.";
                return render(state,notice);
            }
            if(command.equals("/search")) { state.mode=Mode.SEARCH; return render(state,""); }
            state.mode=Mode.RESULT;
            state.result=commands.handle(userId,chatId,topicId,text,admin);
            return render(state,"");
        }
        State state=states.get(key);
        if(state==null || text.isBlank() || (state.mode!=Mode.PLAYERS && state.mode!=Mode.ITEMS)) return null;
        return choose(key,state,text);
    }

    TelegramCommands.Callback callback(long userId,long chatId,int topicId,String data) {
        expire();
        Key key=new Key(userId,chatId,topicId);
        State state=states.get(key);
        String[] parts=data==null ? new String[0] : data.split(":",3);
        if(state==null || parts.length!=3 || !parts[0].equals("vm") || !parts[1].equals(state.token))
            return new TelegramCommands.Callback(null,"Это меню устарело или принадлежит другому пользователю. Откройте /menu.",true);
        int index;
        try { index=Integer.parseInt(parts[2]); }
        catch(NumberFormatException e) { return new TelegramCommands.Callback(null,"Неизвестная кнопка.",true); }
        if(index<0 || index>=state.actions.size()) return new TelegramCommands.Callback(null,"Неизвестная кнопка.",true);
        Action action=state.actions.get(index);
        switch(action.kind()) {
            case HOME -> state.mode=Mode.HOME;
            case SEARCH -> state.mode=Mode.SEARCH;
            case PLAYERS, ITEMS -> {
                if(!storage.ready()) return new TelegramCommands.Callback(null,"Каталог загружается. Повторите чуть позже.",true);
                state.mode=action.kind()==ActionKind.PLAYERS ? Mode.PLAYERS : Mode.ITEMS;
                state.choices=choices(state.mode); state.page=1;
            }
            case PAGE -> state.page=Integer.parseInt(action.value());
            case SELECT -> { return new TelegramCommands.Callback(choose(key,state,action.value()),"",false); }
            case RESULT_PAGE -> {
                if(!storage.ready()) return new TelegramCommands.Callback(null,"Каталог загружается. Повторите чуть позже.",true);
                var result=commands.callback(userId,action.value());
                if(result.view()==null) return result;
                state.result=result.view();
            }
            case BACK -> state.mode=state.origin;
        }
        return new TelegramCommands.Callback(render(state,""),"",false);
    }

    private TelegramCommands.View choose(Key key,State state,String query) {
        if(!storage.ready()) return render(state,"Каталог загружается. Повторите чуть позже.");
        if(state.mode==Mode.PLAYERS) {
            var owners=catalogue.ownerItems(query);
            if(owners.size()!=1) return render(state,owners.isEmpty() ? "Игрок не найден. Введите точный ник из каталога."
                    : "С этим ником найдено несколько UUID. Обратитесь к администратору.");
            state.result=commands.handle(key.userId(),key.chatId(),key.topicId(),"/item player "+query,false);
        } else {
            ResourceGroups.Group group=ResourceGroups.resolve(query);
            String material=group==null ? RussianItems.candidates(query).stream().filter(validItem).findFirst().orElse(null) : group.code();
            if(material==null) return render(state,"Предмет не найден. Введите название, например «алмаз» или «железный слиток».");
            state.result=commands.handle(key.userId(),key.chatId(),key.topicId(),"/item "+material,false);
        }
        state.origin=state.mode; state.mode=Mode.RESULT;
        return render(state,"");
    }

    private List<String> choices(Mode mode) {
        if(mode==Mode.PLAYERS) return catalogue.ownerNames();
        Set<String> items=new HashSet<>();
        for(var snapshot:catalogue.all()) snapshot.items().forEach((item,amount)-> { if(amount>0) items.add(item); });
        List<String> sorted=new ArrayList<>(items);
        sorted.sort(Comparator.comparing(RussianItems::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Comparator.naturalOrder()));
        sorted.add(0,"IR"); sorted.add(0,"AR");
        return List.copyOf(sorted);
    }

    private TelegramCommands.View render(State state,String notice) {
        state.touched=clock.getAsLong();
        state.token=UUID.randomUUID().toString().replace("-","").substring(0,16);
        state.actions.clear();
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text;
        switch(state.mode) {
            case HOME -> {
                text="VaultTracker — поиск ресурсов\nНажмите «Поиск», чтобы выбрать игрока или предмет.";
                add(state,buttons,"🔎 Поиск",0,ActionKind.SEARCH,"");
            }
            case SEARCH -> {
                text="Что ищем?\nИгрок — посмотреть его ресурсы.\nПредмет — посмотреть топ владельцев.";
                add(state,buttons,"👤 Игрок",0,ActionKind.PLAYERS,"");
                add(state,buttons,"📦 Предмет",0,ActionKind.ITEMS,"");
                add(state,buttons,"⌂ Меню",1,ActionKind.HOME,"");
            }
            case PLAYERS, ITEMS -> {
                int pages=Math.max(1,(state.choices.size()+pageSize-1)/pageSize);
                state.page=Math.max(1,Math.min(state.page,pages));
                boolean players=state.mode==Mode.PLAYERS;
                text=(players ? "👤 Игроки каталога" : "📦 Предметы каталога")+" • "+state.page+"/"+pages
                        +"\nНажмите кнопку или отправьте "+(players ? "точный ник игрока" : "название предмета")+" в этот личный чат."
                        +"\nОтмена: /cancel";
                if(state.choices.isEmpty()) text+="\nЗарегистрированных игроков пока нет.";
                int first=(state.page-1)*pageSize;
                for(int i=first;i<Math.min(first+pageSize,state.choices.size());i++) {
                    String value=state.choices.get(i);
                    ResourceGroups.Group group=players ? null : ResourceGroups.resolve(value);
                    String label=players ? "👤 "+value : group==null ? RussianItems.name(value) : group.title()+" ("+group.code()+")";
                    add(state,buttons,label,i-first,ActionKind.SELECT,value);
                }
                if(state.page>1) add(state,buttons,"◀ Назад",pageSize,ActionKind.PAGE,Integer.toString(state.page-1));
                if(state.page<pages) add(state,buttons,"Вперёд ▶",pageSize,ActionKind.PAGE,Integer.toString(state.page+1));
                add(state,buttons,"🔎 Другой поиск",pageSize+1,ActionKind.SEARCH,"");
                add(state,buttons,"⌂ Меню",pageSize+1,ActionKind.HOME,"");
            }
            case RESULT -> {
                text=state.result.text();
                for(var button:state.result.buttons()) add(state,buttons,button.text(),0,ActionKind.RESULT_PAGE,button.data());
                if(state.origin==Mode.PLAYERS || state.origin==Mode.ITEMS)
                    add(state,buttons,"◀ К списку",1,ActionKind.BACK,"");
                add(state,buttons,"🔎 Поиск",1,ActionKind.SEARCH,"");
                add(state,buttons,"⌂ Меню",1,ActionKind.HOME,"");
            }
            default -> throw new IllegalStateException();
        }
        return new TelegramCommands.View(notice.isEmpty() ? text : notice+"\n\n"+text,List.copyOf(buttons));
    }
    private static void add(State state,List<TelegramCommands.Button> buttons,String text,int row,ActionKind kind,String value) {
        int index=state.actions.size(); state.actions.add(new Action(kind,value));
        buttons.add(new TelegramCommands.Button(text,"vm:"+state.token+":"+index,row));
    }
    private void expire() { states.entrySet().removeIf(entry->clock.getAsLong()-entry.getValue().touched>=TTL_MS); }
}
