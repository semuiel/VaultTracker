package ru.vaulttracker;

import java.util.*;

/** Player-facing request wizard. Completed requests are owned by SupportService/GuardService. */
final class SupportMenu extends TelegramMenuSection {
    private static final long DRAFT_TTL=30*60*1000L;
    private static final int PAGE=8,MAX_MEDIA=10;
    private static final List<String> UNBAN_QUESTIONS=List.of(
            "Почему вас забанили?", "Когда примерно получили бан?", "Согласны ли вы с причиной бана?",
            "Если не согласны — почему?", "Подробно опишите ситуацию своими словами.",
            "Что происходило непосредственно перед баном?", "Кто ещё присутствовал или может подтвердить ситуацию?",
            "Были ли у вас раньше баны, предупреждения или другие наказания?",
            "Почему администрация должна пересмотреть наказание?",
            "Что вы сделаете, чтобы ситуация не повторилась?", "Есть ли дополнительная информация для администрации?");
    private enum Stage {TARGET,SEARCH,INTRO,QUESTION,TEXT,MEDIA,REVIEW}
    private static final class Draft {
        final SupportTicket.Type type;final long created=System.currentTimeMillis();
        Stage stage;UUID target;String targetName,body;final List<Integer> media=new ArrayList<>();
        final List<String> answers=new ArrayList<>();
        Draft(SupportTicket.Type type) {this.type=type;stage=type==SupportTicket.Type.COMPLAINT||type==SupportTicket.Type.UNBAN?Stage.TARGET:Stage.TEXT;}
    }
    private final Map<Long,Draft> drafts=new java.util.concurrent.ConcurrentHashMap<>();
    private final SupportService service;
    SupportMenu(TelegramGuardMenu root,SupportService service) {super(root);this.service=service;}
    TelegramCommands.View home(long user) throws Exception {
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        buttons.add(b(user,"🚨 Жалоба на игрока","supportStart","COMPLAINT",0,row++));
        buttons.add(b(user,"💡 Предложение","supportStart","SUGGESTION",0,row++));
        buttons.add(b(user,"🐞 Баг","supportStart","BUG",0,row++));
        buttons.add(b(user,"🔓 Заявка на разбан","supportStart","UNBAN",0,row++));
        buttons.add(homeButton(row));
        return new TelegramCommands.View("🆘 Поддержка\nВыберите тип обращения.",List.copyOf(buttons)).template("support.home",Map.of());
    }
    private GuardService.Account linked(long user) throws Exception {
        var account=guard.account(user).get();if(account==null) throw new SecurityException("Сначала свяжите игровой аккаунт.");return account;
    }
    private Draft draft(long user) {
        Draft value=drafts.get(user);
        if(value==null || System.currentTimeMillis()-value.created>DRAFT_TTL) {drafts.remove(user);throw new IllegalArgumentException("Обращение устарело. Начните заново в разделе «Поддержка».");}
        return value;
    }
    void cancel(long user) {drafts.remove(user);waiting.remove(user);}
    boolean active(long user) {Draft value=drafts.get(user);return value!=null && System.currentTimeMillis()-value.created<=DRAFT_TTL;}
    private TelegramCommands.View start(long user,SupportTicket.Type type) throws Exception {
        GuardService.Account account=guard.account(user).get();
        if(type!=SupportTicket.Type.UNBAN && account==null) {
            cancel(user);
            return new TelegramCommands.View("Чтобы отправить обращение, сначала свяжите Telegram с игровым аккаунтом.",List.of(
                    b(user,"🔗 Привязать персонажа","link","",0,0),
                    b(user,"↩ Поддержка","supportHome","",0,1))).template("support.link-required",Map.of());
        }
        long now=System.currentTimeMillis();drafts.entrySet().removeIf(entry->now-entry.getValue().created>DRAFT_TTL);
        if(drafts.size()>=2048) drafts.remove(drafts.keySet().iterator().next());
        Draft value=new Draft(type);drafts.put(user,value);
        if(type==SupportTicket.Type.UNBAN) {
            if(account==null) {
                value.stage=Stage.SEARCH;
                return prompt(user,"supportSearch","🔓 Заявка на разбан\nВведите часть игрового ника. Затем выберите персонажа из найденных вариантов. Отмена: /cancel.");
            }
            value.target=account.uuid();value.targetName=account.name();
            try {service.attempt(user,value.target,true);}
            catch(IllegalArgumentException limit) {cancel(user);return new TelegramCommands.View(limit.getMessage(),List.of(b(user,"↩ Поддержка","supportHome","",0,0)));}
            return instruction(user,value);
        }
        if(type==SupportTicket.Type.COMPLAINT) {
            value.stage=Stage.SEARCH;
            return prompt(user,"supportSearch","🚨 Жалоба · 1/3\nНа кого жалуетесь? Введите ник или его часть. Игрок может быть офлайн.").template("support.complaint.search",Map.of());
        }
        return instruction(user,value);
    }
    private TelegramCommands.View players(long user,String filter,boolean onlineOnly,int requested) throws Exception {
        Draft value=draft(user);if(value.type!=SupportTicket.Type.COMPLAINT && value.type!=SupportTicket.Type.UNBAN) throw new IllegalArgumentException("Нужен выбор игрока.");
        value.stage=Stage.TARGET;
        var all=moderation.supportPlayers(filter,onlineOnly).get();int pages=Math.max(1,(all.size()+PAGE-1)/PAGE),page=Math.max(0,Math.min(requested,pages-1));
        List<TelegramCommands.Button> buttons=new ArrayList<>();int row=0;
        for(var player:all.subList(page*PAGE,Math.min(all.size(),(page+1)*PAGE)))
            buttons.add(b(user,player.name(),"supportTarget",player.uuid().toString(),0,row++));
        String context=(onlineOnly?"online":"search")+"|"+filter;
        if(page>0) buttons.add(b(user,"⬅ Назад","supportPlayers",context,page-1,row));
        if(page+1<pages) buttons.add(b(user,"Вперёд ➡","supportPlayers",context,page+1,row));
        buttons.add(b(user,"🔎 Изменить поиск","supportSearch","",0,row+1));
        buttons.add(b(user,"❌ Отмена","supportCancel","",0,row+2));
        String hint="\nВыберите игрока или уточните поиск.";
        return new TelegramCommands.View((value.type==SupportTicket.Type.UNBAN?"🔓 Заявка на разбан":"🚨 Жалоба на игрока")+"\n"+(onlineOnly?"Сначала выберите игрока онлайн.":"Результаты поиска: "+filter)+
                (all.isEmpty()?"\nИгроков не найдено.":"")+hint,List.copyOf(buttons));
    }
    private TelegramCommands.View instruction(long user,Draft value) {
        if(value.type==SupportTicket.Type.UNBAN) {
            value.stage=Stage.INTRO;
            return new TelegramCommands.View("🔓 Заявка на разбан · "+value.targetName+"\nОтветьте на 11 обязательных вопросов по очереди. Пишите подробно и честно; если вопрос не относится к вам, ответьте «нет». После анкеты можно приложить фото и видео. Перед отправкой вы проверите ответы.",List.of(
                    b(user,"✍️ Начать анкету","supportQuestions","",0,0),b(user,"❌ Отмена","supportCancel","",0,1)));
        }
        value.stage=Stage.TEXT;
        String text=switch(value.type) {
            case COMPLAINT -> "🚨 Жалоба · 2/3 · "+value.targetName+"\nКратко расскажите, что произошло и когда. Фото и видео можно добавить после описания.";
            case SUGGESTION -> "💡 Предложение\nОпишите идею: что изменить или добавить, зачем это игрокам и как это должно работать. Пример: «Добавить ...; это поможет ...». Фото/видео необязательны.";
            case BUG -> "🐞 Баг\nНапишите: что произошло, что вы ожидали, как повторить ошибку и когда она возникла. Пример: «После ... случилось ...; ожидал ...». Фото/видео необязательны.";
            case UNBAN -> throw new IllegalStateException();
        };
        return prompt(user,"supportText",text+"\n\nОтправьте описание текстом (до 3000 символов).")
                .template("support."+value.type.name().toLowerCase(Locale.ROOT)+".description",Map.of("player",Objects.toString(value.targetName,"")));
    }
    private TelegramCommands.View question(long user,Draft value) {
        value.stage=Stage.QUESTION;
        int index=value.answers.size();
        return prompt(user,"supportAnswer","🔓 Анкета на разбан · вопрос "+(index+1)+"/"+UNBAN_QUESTIONS.size()+"\n"+UNBAN_QUESTIONS.get(index)+"\n\nОтветьте сообщением до 500 символов. Если не относится к вам, напишите «нет».");
    }
    private static String unbanBody(List<String> answers) {
        StringBuilder body=new StringBuilder();
        for(int i=0;i<UNBAN_QUESTIONS.size();i++)
            body.append(i+1).append(". ").append(UNBAN_QUESTIONS.get(i)).append("\n")
                    .append(i<answers.size()?answers.get(i):"").append("\n\n");
        return body.toString().strip();
    }
    private TelegramCommands.View mediaStep(long user,Draft value) throws Exception {
        if(value.type==SupportTicket.Type.COMPLAINT) return review(user);
        value.stage=Stage.MEDIA;
        return new TelegramCommands.View("📎 Материалы: "+value.media.size()+"/"+MAX_MEDIA+"\nПришлите фото или видео отдельными сообщениями. Если материалов нет или вы закончили, нажмите «К проверке».",List.of(
                b(user,"✅ К проверке","supportReview","",0,0),
                b(user,"✏️ Изменить текст","supportEditText","",0,1),
                b(user,"❌ Отмена","supportCancel","",0,2)));
    }
    TelegramCommands.View media(long user,TelegramApi.Incoming incoming) throws Exception {
        if(!incoming.privateChat() || !active(user)) return null;
        Draft value=draft(user);
        if(value.stage==Stage.TEXT || value.stage==Stage.QUESTION) return new TelegramCommands.View("Сначала ответьте на вопросы текстом. Фото и видео можно добавить следующим шагом.");
        if(value.stage!=Stage.MEDIA && !(value.type==SupportTicket.Type.COMPLAINT && value.stage==Stage.REVIEW)) return null;
        if(incoming.text()==null || !(incoming.text().startsWith("[Фото]") || incoming.text().startsWith("[Видео]")))
            return new TelegramCommands.View("Принимаются только фото и видео.",List.of(b(user,"↩ К материалам","supportMedia","",0,0)));
        if(value.media.size()>=MAX_MEDIA) return mediaStep(user,value);
        if(!value.media.contains(incoming.messageId())) value.media.add(incoming.messageId());
        return mediaStep(user,value);
    }
    private TelegramCommands.View review(long user) throws Exception {
        Draft value=draft(user);if(value.body==null || value.body.isBlank()) return instruction(user,value);
        value.stage=Stage.REVIEW;
        String title=switch(value.type) {case COMPLAINT->"Жалоба";case SUGGESTION->"Предложение";case BUG->"Баг";case UNBAN->"Заявка на разбан";};
        String preview=value.body.length()>850?value.body.substring(0,849)+"…":value.body;
        return new TelegramCommands.View((value.type==SupportTicket.Type.COMPLAINT?"🚨 Жалоба · 3/3\nПроверьте текст и нажмите «Отправить». Фото и видео можно прислать сюда — это необязательно.":"🆘 Проверьте обращение")+"\nТип: "+title+
                (value.targetName==null?"":"\nИгрок: "+value.targetName)+"\nМатериалы: "+value.media.size()+"\n\n"+preview,
                List.of(b(user,"✅ Отправить","supportSend","",0,0),
                        b(user,"📎 Добавить материалы","supportMedia","",0,1),
                        b(user,"✏️ Изменить текст","supportEditText","",0,2),
                        b(user,"❌ Отмена","supportCancel","",0,3)));
    }
    private TelegramCommands.View send(long user) throws Exception {
        Draft value=draft(user);if(value.stage!=Stage.REVIEW) return review(user);
        if(service==null || !service.configured(value.type)) return new TelegramCommands.View(
                "Тема поддержки ещё не настроена. Обращение не отправлено; текст и материалы пока сохранены в этом меню.",List.of(
                        b(user,"↩ К обращению","supportReview","",0,0),b(user,"❌ Отмена","supportCancel","",0,1)));
        SupportTicket submitted;
        try {
            if(value.type==SupportTicket.Type.UNBAN) submitted=service.submitUnban(user,guard.account(user).get(),value.target,value.targetName,value.body,List.copyOf(value.media),moderation);
            else submitted=service.submit(value.type,user,linked(user),value.target,value.targetName,value.body,List.copyOf(value.media));
        }
        catch(Exception failure) {return new TelegramCommands.View((failure instanceof SecurityException?
                "Свяжите игровой аккаунт заново. Обращение не отправлено.":
                failure instanceof IllegalArgumentException?failure.getMessage():
                "Не удалось отправить обращение. Проверьте доступность бота и попробуйте ещё раз. Текст и материалы сохранены в этом меню."),List.of(
                b(user,"🔁 Повторить","supportSend","",0,0),b(user,"❌ Отмена","supportCancel","",0,1)));}
        cancel(user);return new TelegramCommands.View(submitted!=null && submitted.status().equals("DELIVERY_PENDING")?"✅ Обращение сохранено. Бот автоматически отправит его администраторам, когда связь восстановится. Повторять отправку не нужно.":"✅ Обращение отправлено администраторам.",List.of(b(user,"↩ Поддержка","supportHome","",0,0),homeButton(1)));
    }
    void registerActions(TelegramActionRegistry registry) {
        var scope=registry.scope("SupportMenu");
        scope.on("supportHome",TelegramActionRegistry.Arguments.NONE,(user,a,data)->home(user));
        scope.on("supportStart",TelegramActionRegistry.Arguments.VALUE,(user,a,data)->start(user,SupportTicket.Type.valueOf(a.value())));
        scope.on("supportPlayers",TelegramActionRegistry.Arguments.VALUE_AND_PAGE,(user,a,data)->{
            String[] parts=a.value().split("\\|",2);return players(user,parts.length>1?parts[1]:"",parts[0].equals("online"),a.page());});
        scope.on("supportSearch",TelegramActionRegistry.Arguments.NONE,(user,a,data)->{
            draft(user).stage=Stage.SEARCH;return prompt(user,"supportSearch","Введите часть ника игрока, в том числе офлайн. До 64 символов.");});
        scope.on("supportTarget",TelegramActionRegistry.Arguments.VALUE,(user,a,data)->{
            Draft value=draft(user);var player=moderation.supportPlayer(UUID.fromString(a.value())).get();
            value.target=player.uuid();value.targetName=player.name();
            if(value.type==SupportTicket.Type.UNBAN) try {service.attempt(user,value.target,false);}
            catch(IllegalArgumentException limit) {cancel(user);return new TelegramCommands.View(limit.getMessage(),List.of(b(user,"↩ Поддержка","supportHome","",0,0)));}
            return instruction(user,value);});
        scope.on("supportQuestions",TelegramActionRegistry.Arguments.NONE,(user,a,data)->{
            Draft value=draft(user);if(value.type!=SupportTicket.Type.UNBAN || value.stage!=Stage.INTRO) throw new IllegalArgumentException("Анкета уже начата или устарела.");
            return question(user,value);});
        scope.on("supportMedia",TelegramActionRegistry.Arguments.NONE,(user,a,data)->mediaStep(user,draft(user)));
        scope.on("supportEditText",TelegramActionRegistry.Arguments.NONE,(user,a,data)->{
            Draft value=draft(user);if(value.type==SupportTicket.Type.UNBAN) {value.answers.clear();value.body=null;return question(user,value);}
            return instruction(user,value);});
        scope.on("supportReview",TelegramActionRegistry.Arguments.NONE,(user,a,data)->review(user));
        scope.on("supportSend",TelegramActionRegistry.Arguments.NONE,TelegramActionRegistry.Requirement.HANDLER,true,(user,a,data)->send(user));
        scope.on("supportCancel",TelegramActionRegistry.Arguments.NONE,(user,a,data)->{cancel(user);return home(user);});
    }
    TelegramCommands.View onInput(long user,String text,TelegramGuardMenu.Input pending) throws Exception {
        if(pending.kind().equals("supportSearch")) {
            Draft value=draft(user);if(value.stage!=Stage.SEARCH) return home(user);
            String query=text.strip();if(query.isBlank()||query.length()>64) return new TelegramCommands.View("Введите от 1 до 64 символов ника. Отмена: /cancel.");
            waiting.remove(user);return players(user,query,false,0);
        }
        if(pending.kind().equals("supportText")) {
            Draft value=draft(user);if(value.stage!=Stage.TEXT) return home(user);
            String body=text.strip();if(body.isBlank() || body.length()>3000) return new TelegramCommands.View("Описание: от 1 до 3000 символов. Отмена: /cancel.");
            value.body=body;waiting.remove(user);return mediaStep(user,value);
        }
        if(pending.kind().equals("supportAnswer")) {
            Draft value=draft(user);if(value.stage!=Stage.QUESTION || value.type!=SupportTicket.Type.UNBAN) return home(user);
            String answer=text.strip();if(answer.isBlank() || answer.length()>500) return new TelegramCommands.View("Ответьте на вопрос текстом до 500 символов. Если не относится к вам, напишите «нет». Отмена: /cancel.");
            value.answers.add(answer);
            waiting.remove(user);
            if(value.answers.size()<UNBAN_QUESTIONS.size()) return question(user,value);
            value.body=unbanBody(value.answers);return mediaStep(user,value);
        }
        return null;
    }
}
