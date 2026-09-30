package ru.vaulttracker;

import java.util.*;

/** Completes the same player and item forms accepted by /find. */
final class FindTabCompletion {
    static List<String> complete(Catalogue catalogue,String[] args) {
        return complete(catalogue,args,RussianItems.searchTerms());
    }
    static List<String> complete(Catalogue catalogue,String[] args,List<String> items) {
        if(args.length==0) return List.of();
        String typed=args[args.length-1].toLowerCase(Locale.ROOT);
        if(args.length==1) {
            Set<String> choices=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            choices.addAll(catalogue.ownerNames());
            choices.addAll(items);
            return choices.stream().filter(choice->choice.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        int queryStart=catalogue.ownerItems(args[0]).size()==1?1:0;
        String before=String.join(" ",Arrays.copyOfRange(args,queryStart,args.length-1));
        String prefix=(before.isBlank()?"":before+" ")+args[args.length-1];
        String key=prefix.toLowerCase(Locale.ROOT).replace('ё','е');
        Set<String> choices=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(String term:items) {
            String searchable=term.toLowerCase(Locale.ROOT).replace('ё','е');
            if(!searchable.startsWith(key)) continue;
            String remainder=term.substring(before.isBlank()?0:before.length()+1);
            int nextSpace=remainder.indexOf(' ');
            String suggestion=nextSpace<0?remainder:remainder.substring(0,nextSpace);
            if(!suggestion.isBlank()) choices.add(suggestion);
        }
        return List.copyOf(choices);
    }
    private FindTabCompletion() {}
}
