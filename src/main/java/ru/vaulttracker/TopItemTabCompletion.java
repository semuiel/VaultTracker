package ru.vaulttracker;

import java.util.*;

/** The /itemtop forms use the same Russian item suggestions as /find. */
final class TopItemTabCompletion {
    static List<String> complete(Catalogue catalogue,String[] args) {
        return complete(catalogue,args,RussianItems.searchTerms());
    }
    static List<String> complete(Catalogue catalogue,String[] args,List<String> items) {
        if(args.length==0) return List.of();
        String typed=args[args.length-1].toLowerCase(Locale.ROOT);
        if(args.length>1 && args[0].equalsIgnoreCase("all")) {
            int pages=Math.max(1,(catalogue.allItemTotals().size()+19)/20);
            return pages(typed,pages);
        }
        boolean explicit=args.length>1 && args[0].equalsIgnoreCase("player");
        String[] query=explicit?Arrays.copyOfRange(args,1,args.length):args;
        if(explicit && query.length==1)
            return catalogue.ownerNames().stream().filter(name->name.toLowerCase(Locale.ROOT).startsWith(typed))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        Set<String> options=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        options.addAll(FindTabCompletion.complete(catalogue,query,items));
        if(!explicit && args.length==1) {
            options.add("player");options.add("all");
        }
        if(query.length==1 || query.length==2) {
            for(String group:List.of("AR","IR","АР","ИР")) options.add(group);
        }
        if(query.length==2 && catalogue.ownerItems(query[0]).size()==1) {
            int count=catalogue.ownerItems(query[0]).getFirst().items().size();
            int pages=Math.max(1,(count+TopItemCommand.PAGE_SIZE-1)/TopItemCommand.PAGE_SIZE);
            options.addAll(pages(typed,pages));
        }
        return options.stream().filter(value->value.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }
    private static List<String> pages(String typed,int count) {
        return java.util.stream.IntStream.rangeClosed(1,count).mapToObj(Integer::toString)
                .filter(value->value.startsWith(typed)).toList();
    }
    private TopItemTabCompletion() {}
}
