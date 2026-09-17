package ru.vaulttracker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;
class OfflineInventoryTest {
 @TempDir Path folder;
 UUID id=UUID.randomUUID();
 Map<String,Object> item(String name,int count) {return Map.of("id","minecraft:"+name,"count",count);}
 Path save(Map<String,Object> root) throws Exception {
  Path file=folder.resolve("playerdata").resolve(id+".dat");Files.createDirectories(file.getParent());
  try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {out.writeByte(10);out.writeUTF("");write(out,root);}return file;
 }
 int type(Object o) {return o instanceof Map?10:o instanceof List?9:o instanceof Byte?1:o instanceof Integer?3:8;}
 void write(DataOutputStream out,Object value) throws Exception {
  if(value instanceof Map<?,?> map) {for(var e:map.entrySet()) {out.writeByte(type(e.getValue()));out.writeUTF(e.getKey().toString());write(out,e.getValue());}out.writeByte(0);}
  else if(value instanceof List<?> list) {out.writeByte(list.isEmpty()?10:type(list.getFirst()));out.writeInt(list.size());for(Object o:list) write(out,o);}
  else if(value instanceof Byte n) out.writeByte(n);else if(value instanceof Integer n) out.writeInt(n);else out.writeUTF(value.toString());
 }
 @Test void readsSavedInventoryArmorOffhandAndModernNestedShulkersWithoutWriting() throws Exception {
  var empty=Map.of("id","minecraft:red_shulker_box","count",1);
  var box=Map.of("id","minecraft:shulker_box","count",1,"components",Map.of("minecraft:container",List.of(Map.of("slot",0,"item",empty),Map.of("slot",1,"item",item("diamond",4)))));
  var helmet=new HashMap<String,Object>(item("diamond_helmet",1));helmet.put("Slot",(byte)103);
  var shield=new HashMap<String,Object>(item("shield",1));shield.put("Slot",(byte)-106);
  Path file=save(Map.of("Inventory",List.of(item("diamond",64),item("diamond",12),box,helmet,shield),"EnderItems",List.of(item("emerald",9))));
  byte[] before=Files.readAllBytes(file);
  var items=OfflineInventory.read(List.of(folder),id,false);assertEquals(4,items.size());assertEquals(76,items.getFirst().amount());
  assertTrue(items.get(1).shulker());assertTrue(items.get(1).contents().getFirst().shulker());assertTrue(items.get(1).contents().getFirst().contents().isEmpty());
  assertEquals(9,OfflineInventory.read(List.of(folder),id,true).getFirst().amount());assertArrayEquals(before,Files.readAllBytes(file));
 }
 @Test void readsLegacyShulkerAndDistinguishesMissingCorruptAndEmpty() throws Exception {
  assertThrows(IOException.class,()->OfflineInventory.read(List.of(folder),id,false));
  var legacy=Map.of("id","minecraft:shulker_box","Count",(byte)1,"tag",Map.of("BlockEntityTag",Map.of("Items",List.of(Map.of("id","minecraft:diamond","Count",(byte)6)))));
  Path file=save(Map.of("Inventory",List.of(legacy),"EnderItems",List.of()));
  assertEquals(6,OfflineInventory.read(List.of(folder),id,false).getFirst().contents().getFirst().amount());assertTrue(OfflineInventory.read(List.of(folder),id,true).isEmpty());
  Files.write(file,new byte[]{1,2,3});assertThrows(IOException.class,()->OfflineInventory.read(List.of(folder),id,false));
 }
 @Test void readsNewPlayerStorageFromDimensionPaths() throws Exception {
  Path old=save(Map.of("Inventory",List.of(item("diamond",12)),"EnderItems",List.of(item("emerald",3))));
  Path modern=folder.resolve("players/data").resolve(id+".dat");Files.createDirectories(modern.getParent());Files.move(old,modern);
  Path dimension=folder.resolve("dimensions/minecraft/overworld");
  assertEquals(12,OfflineInventory.read(List.of(dimension),id,false).getFirst().amount());
  assertEquals(3,OfflineInventory.read(List.of(dimension),id,true).getFirst().amount());
 }
 @Test void categoriesDoNotConfuseBlocksFoodAndResources() {
  assertEquals("🧱",TelegramItemIcons.icon("DIAMOND_BLOCK"));assertEquals("🧱",TelegramItemIcons.icon("COPPER_BLOCK"));
  assertEquals("💎",TelegramItemIcons.icon("IRON_INGOT"));assertEquals("💎",TelegramItemIcons.icon("EMERALD"));
  assertEquals("🍖",TelegramItemIcons.icon("GOLDEN_CARROT"));assertEquals("⚙️",TelegramItemIcons.icon("REDSTONE_BLOCK"));
  assertEquals("•",TelegramItemIcons.icon("UNKNOWN_FUTURE_ITEM"));
 }
}
