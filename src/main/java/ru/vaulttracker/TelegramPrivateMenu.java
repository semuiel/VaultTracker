package ru.vaulttracker;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.Material;

/** Personal search state. Group messages never enter this handler. */
final class TelegramPrivateMenu {
    private enum Mode { HOME, SEARCH, PLAYERS, ITEMS, RESULT }
    private enum ActionKind { HOME, SEARCH, PLAYERS, ITEMS, PAGE, SELECT, RESULT_PAGE, BACK, CLEAR, TOTAL_TOP }
    private record Key(long userId,long chatId,int topicId) {}
    private record Action(ActionKind kind,String value) {}
    private static final int MAX_STATES=4096;
    private static final class State {
        Mode mode=Mode.HOME, origin=Mode.SEARCH;
        List<String> choices=List.of();
        String filter="";
        int page=1;
        long touched;
        long user;
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
    private GuardService guard;
    void guard(GuardService guard) {this.guard=guard;}
    void leave(long user,long chat,int topic) {states.remove(new Key(user,chat,topic));}

    TelegramPrivateMenu(Catalogue catalogue,StorageEngine storage,TelegramCommands commands,int pageSize) {
        this(catalogue,storage,commands,pageSize,id -> {
            Material material=Material.getMaterial(id);
            return material!=null && material.isItem();
        },System::currentTimeMillis);
    }
    TelegramPrivateMenu(Catalogue catalogue,StorageEngine storage,TelegramCommands commands,int pageSize,
                        Predicate<String> validItem,LongSupplier clock) {
        this.catalogue=catalogue; this.storage=storage; this.commands=commands;
        this.pageSize=Math.min(TelegramConfig.BUTTON_PAGE_SIZE,Math.max(1,pageSize)); this.validItem=validItem; this.clock=clock;
    }

    TelegramCommands.View handle(long userId,long chatId,int topicId,String input,boolean admin) {
        pruneStates();
        Key key=new Key(userId,chatId,topicId);
        String text=input==null ? "" : input.trim();
        String command=TelegramCommands.command(text);
        if(text.startsWith("/")) {
            State state=new State(); state.user=userId;states.put(key,state);
            pruneStates();
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
        if(!storage.ready()) return render(state,"Каталог загружается. Повторите чуть позже.");
        state.filter=text.length()>128 ? text.substring(0,128) : text;
        state.choices=choices(state.mode).stream().filter(value->matches(state.mode,value,state.filter)).toList();
        state.page=1;
        return render(state,"");
    }

    TelegramCommands.Callback callback(long userId,long chatId,int topicId,String data) {
        pruneStates();
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
                state.choices=choices(state.mode); state.page=1; state.filter="";
            }
            case PAGE -> state.page=Integer.parseInt(action.value());
            case SELECT -> { return new TelegramCommands.Callback(choose(key,state,action.value()),"",false); }
            case TOTAL_TOP -> {
                if(!storage.ready()) return new TelegramCommands.Callback(null,"Каталог загружается. Повторите чуть позже.",true);
                state.result=commands.totalTop(userId);state.origin=Mode.ITEMS;state.mode=Mode.RESULT;
            }
            case RESULT_PAGE -> {
                if(!storage.ready()) return new TelegramCommands.Callback(null,"Каталог загружается. Повторите чуть позже.",true);
                var result=commands.callback(userId,action.value());
                if(result.view()==null) return result;
                state.result=result.view();
            }
            case BACK -> state.mode=state.origin;
            case CLEAR -> { state.filter=""; state.choices=choices(state.mode); state.page=1; }
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
        if(mode==Mode.PLAYERS) return catalogue.ownerNamesByTotalItems();
        Set<String> items=new HashSet<>();
        for(var snapshot:catalogue.all()) snapshot.items().forEach((item,amount)-> { if(amount>0) items.add(item); });
        List<String> sorted=new ArrayList<>(items);
        sorted.sort(Comparator.comparing(RussianItems::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Comparator.naturalOrder()));
        sorted.add(0,"IR"); sorted.add(0,"AR");
        return List.copyOf(sorted);
    }

    private static boolean matches(Mode mode,String value,String query) {
        String needle=searchKey(query);
        if(mode==Mode.PLAYERS) return searchKey(value).contains(needle);
        ResourceGroups.Group group=ResourceGroups.resolve(value);
        String names=group==null ? value+" "+RussianItems.name(value)
                : group.code()+" "+(group.code().equals("AR") ? "АР" : "ИР")+" "+group.title()+" "+String.join(" ",group.materials());
        String haystack=searchKey(names);
        return Arrays.stream(needle.split(" ")).allMatch(haystack::contains);
    }
    private static String searchKey(String value) {
        return value.toLowerCase(Locale.ROOT).replace('ё','е').replace("minecraft:","")
                .replace('_',' ').replace('-',' ').trim().replaceAll("\\s+"," ");
    }

    private TelegramCommands.View render(State state,String notice) {
        state.touched=clock.getAsLong();
        state.token=UUID.randomUUID().toString().replace("-","").substring(0,16);
        state.actions.clear();
        List<TelegramCommands.Button> buttons=new ArrayList<>();
        String text;
        switch(state.mode) {
            case HOME -> {
                text="👋 Вас приветствует торговый бот FLEXITY!\n\n🔎 Хотите купить ресурс, но не знаете, у кого он есть? Нажмите «Поиск».\n🛡 Хотите защитить свои ресурсы? Откройте «Личный кабинет».";
                add(state,buttons,"🔎 Поиск",0,ActionKind.SEARCH,"");
                if(guard!=null) {
                    String label="👤 Личный кабинет / привязка";
                    try {var account=guard.account(state.user).join();if(account!=null) label="👤 "+account.name();}
                    catch(java.util.concurrent.CompletionException ignored) {}
                    buttons.add(new TelegramCommands.Button("👤 Личный кабинет","vg:home",1));
                }
            }
            case SEARCH -> {
                text="🔎 Поиск ресурсов в торговом боте FLEXITY\n\n«Игрок» — найти игрока и узнать, что у него есть в наличии.\n«Предмет» — найти ресурс и узнать, у кого он есть.\n\nЧтобы ваши вещи появились в списке, повесьте на сундук табличку с надписью [v] или [vault].";
                add(state,buttons,"👤 Игрок",0,ActionKind.PLAYERS,"");
                add(state,buttons,"📦 Предмет",0,ActionKind.ITEMS,"");
                add(state,buttons,"⌂ Меню",1,ActionKind.HOME,"");
            }
            case PLAYERS, ITEMS -> {
                int choiceSize=state.mode==Mode.ITEMS ? Math.min(pageSize,6) : pageSize;
                int pages=Math.max(1,(state.choices.size()+choiceSize-1)/choiceSize);
                state.page=Math.max(1,Math.min(state.page,pages));
                boolean players=state.mode==Mode.PLAYERS;
                text=(players ? "👤 Торговый бот FLEXITY · игроки" : "📦 Торговый бот FLEXITY · предметы")+" • "+state.page+"/"+pages
                        +"\nНажмите кнопку или отправьте часть "+(players ? "ника игрока" : "названия предмета на русском или английском")+" в этот личный чат."
                        +"\nОтмена: /cancel";
                if(!state.filter.isEmpty()) text+="\nФильтр: «"+state.filter+"» • Найдено: "+state.choices.size();
                if(state.choices.isEmpty()) text+=state.filter.isEmpty() ? "\nЗарегистрированных игроков пока нет."
                        : "\nНичего не найдено. Введите другую часть названия или сбросьте фильтр.";
                int first=(state.page-1)*choiceSize;
                if(!players) add(state,buttons,"🏆 Топ по всем предметам",0,ActionKind.TOTAL_TOP,"");
                for(int i=first;i<Math.min(first+choiceSize,state.choices.size());i++) {
                    String value=state.choices.get(i);
                    ResourceGroups.Group group=players ? null : ResourceGroups.resolve(value);
                    String label=players ? "👤 "+value : group==null ? TelegramItemIcons.label(value)
                            : group.title()+" ("+group.code()+")";
                    add(state,buttons,label,i-first+(players?0:1),ActionKind.SELECT,value);
                }
                int navRow=choiceSize+(players?0:1);
                if(state.page>1) add(state,buttons,"◀ Назад",navRow,ActionKind.PAGE,Integer.toString(state.page-1));
                if(state.page<pages) add(state,buttons,"Вперёд ▶",navRow,ActionKind.PAGE,Integer.toString(state.page+1));
                if(!state.filter.isEmpty()) add(state,buttons,"✖ Сбросить фильтр",navRow+1,ActionKind.CLEAR,"");
                add(state,buttons,"🔎 Другой поиск",navRow+2,ActionKind.SEARCH,"");
                add(state,buttons,"⌂ Меню",navRow+2,ActionKind.HOME,"");
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
    private void pruneStates() {
        if(states.size()<=MAX_STATES) return;
        states.entrySet().stream().min(Comparator.comparingLong(entry->entry.getValue().touched))
                .ifPresent(entry->states.remove(entry.getKey()));
    }
}
