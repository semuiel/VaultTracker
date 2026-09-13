package ru.vaulttracker;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Read-only bounded NBT reader. Never instantiates or saves an offline player. */
final class OfflineInventory {
 static List<TelegramModeration.ItemView> read(List<Path> folders,UUID uuid,boolean ender) throws IOException {
  Path file=null;long newest=Long.MIN_VALUE;
  for(Path folder:folders) {
   Path candidate=folder.resolve("playerdata").resolve(uuid+".dat");
   if(Files.isRegularFile(candidate)) {long time=Files.getLastModifiedTime(candidate).toMillis();if(time>newest) {file=candidate;newest=time;}}
  }
  if(file==null) throw new IOException("Сохранённые данные игрока не найдены (playerdata). Это не означает пустой инвентарь.");
  try(InputStream raw=Files.newInputStream(file);InputStream zip=new GZIPInputStream(raw)) {
   byte[] bytes=zip.readNBytes(16*1024*1024+1);
   if(bytes.length>16*1024*1024) throw new IOException("Данные игрока превышают лимит безопасного чтения.");
   DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
   if(in.readUnsignedByte()!=10) throw new IOException("Некорректный NBT игрока.");
   in.readUTF();Map<?,?> root=map(tag(in,10,0,new int[]{0}));
   return items(root.get(ender?"EnderItems":"Inventory"),0);
  } catch(EOFException e) {throw new IOException("Файл игрока неполный. Повторите просмотр после сохранения.",e);}
 }
 private static Object tag(DataInputStream in,int type,int depth,int[] nodes) throws IOException {
  if(depth>64 || ++nodes[0]>200000) throw new IOException("Слишком сложный NBT игрока.");
  return switch(type) {
   case 1 -> in.readByte(); case 2 -> in.readShort(); case 3 -> in.readInt(); case 4 -> in.readLong();
   case 5 -> in.readFloat(); case 6 -> in.readDouble(); case 8 -> in.readUTF();
   case 7 -> {int n=length(in);in.skipNBytes(n);yield null;}
   case 11 -> {int n=length(in);in.skipNBytes((long)n*4);yield null;}
   case 12 -> {int n=length(in);in.skipNBytes((long)n*8);yield null;}
   case 9 -> {int t=in.readUnsignedByte(),n=length(in);List<Object> list=new ArrayList<>();for(int i=0;i<n;i++) list.add(tag(in,t,depth+1,nodes));yield list;}
   case 10 -> {Map<String,Object> map=new LinkedHashMap<>();int t;while((t=in.readUnsignedByte())!=0) {String key=in.readUTF();map.put(key,tag(in,t,depth+1,nodes));}yield map;}
   default -> throw new IOException("Неизвестный тип NBT: "+type);
  };
 }
 private static int length(DataInputStream in) throws IOException {int n=in.readInt();if(n<0 || n>200000) throw new IOException("Некорректная длина NBT.");return n;}
 private static Map<?,?> map(Object value) {return value instanceof Map<?,?> m?m:Map.of();}
 static List<TelegramModeration.ItemView> items(Object value,int depth) throws IOException {
  if(depth>32) throw new IOException("Слишком много вложенных контейнеров.");
  if(!(value instanceof List<?> list)) return List.of();
  List<TelegramModeration.ItemView> result=new ArrayList<>();Map<String,Integer> indexes=new LinkedHashMap<>();
  for(Object entry:list) {
   Map<?,?> item=map(entry);if(item.containsKey("item")) item=map(item.get("item"));
   Object id=item.get("id");if(!(id instanceof String name)) throw new IOException("Формат предмета не поддерживается.");
   String material=name.startsWith("minecraft:")?name.substring(10).toUpperCase(Locale.ROOT):name;
   Object count=item.containsKey("count")?item.get("count"):item.get("Count");
   int amount=count instanceof Number number?number.intValue():1;if(amount<=0 || material.equals("AIR")) continue;
   Map<?,?> components=map(item.get("components"));
   Object nested=components.get("minecraft:container");
   if(nested==null) nested=map(map(item.get("tag")).get("BlockEntityTag")).get("Items");
   boolean container=material.endsWith("SHULKER_BOX");
   String label=TelegramItemIcons.label(material);
   if(container) result.add(new TelegramModeration.ItemView(label,amount,items(nested,depth+1),true));
   else {Integer index=indexes.get(material);if(index==null) {indexes.put(material,result.size());result.add(new TelegramModeration.ItemView(label,amount,List.of(),false));}
    else {var old=result.get(index);result.set(index,new TelegramModeration.ItemView(label,Math.addExact(old.amount(),amount),List.of(),false));}}
  }
  return List.copyOf(result);
 }
 private OfflineInventory() {}
}
