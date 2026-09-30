package ru.vaulttracker;

import java.nio.file.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RootLogDocumentTest {
    @TempDir Path directory;

    @Test void shortLogBecomesReadableMarkdownAndIsDeletedAfterUse() throws Exception {
        Path latest=directory.resolve("latest.log");
        Files.writeString(latest,"запуск\n``` не закрывает блок\nпоследняя строка");
        byte[] original=Files.readAllBytes(latest);
        Path preview;
        try(var document=RootLogDocument.prepare(latest)) {
            preview=document.path();assertTrue(document.temporary());assertEquals("latest-300.md",document.filename());
            String markdown=Files.readString(preview);
            assertTrue(markdown.startsWith("# Лог Minecraft-сервера"));
            assertTrue(markdown.lines().anyMatch(line->line.equals("    запуск")));
            assertTrue(markdown.lines().anyMatch(line->line.equals("    ``` не закрывает блок")));
            assertEquals("    последняя строка",markdown.lines().reduce((a,b)->b).orElseThrow());
        }
        assertFalse(Files.exists(preview));
        assertArrayEquals(original,Files.readAllBytes(latest));
    }

    @Test void takesExactlyLast300LinesFromLargeLogWithoutChangingSource() throws Exception {
        Path latest=directory.resolve("latest.log");
        String content=IntStream.range(0,355).mapToObj(i->"line-"+i+" "+"x".repeat(50)).reduce("",(a,b)->a+b+"\n");
        Files.writeString(latest,content);
        Path preview;
        try(var document=RootLogDocument.prepare(latest)) {
            preview=document.path();
            String markdown=Files.readString(preview);
            assertFalse(markdown.contains("line-54 "));
            assertTrue(markdown.contains("    line-55 "));
            assertTrue(markdown.contains("    line-354 "));
            assertEquals(300,markdown.lines().filter(line->line.startsWith("    line-")).count());
        }
        assertFalse(Files.exists(preview));
        assertEquals(content,Files.readString(latest));
    }

    @Test void emptyLogAndMissingLogHaveClearHandling() throws Exception {
        Path latest=directory.resolve("latest.log");
        assertThrows(java.io.IOException.class,()->RootLogDocument.prepare(latest));
        Files.writeString(latest,"");
        try(var document=RootLogDocument.prepare(latest)) {
            assertTrue(Files.readString(document.path()).contains("Лог пуст"));
        }
    }

    @Test void exportsFullRawAndTailInBothFormats() throws Exception {
        Path latest=directory.resolve("latest.log");Files.writeString(latest,"one\ntwo\nthree\n");
        try(var full=RootLogDocument.prepare(latest,null,false)) {
            assertEquals(latest,full.path());assertFalse(full.temporary());assertEquals("latest.log",full.filename());
        }
        try(var raw=RootLogDocument.prepare(latest,2,false)) {
            assertEquals("latest-2.log",raw.filename());assertEquals("two\nthree\n",Files.readString(raw.path()));
        }
        try(var markdown=RootLogDocument.prepare(latest,null,true)) {
            assertEquals("latest.md",markdown.filename());
            assertEquals(java.util.List.of("    one","    two","    three"),Files.readAllLines(markdown.path()).stream().filter(line->line.startsWith("    ")).toList());
        }
        assertThrows(IllegalArgumentException.class,()->RootLogDocument.prepare(latest,0,false));
        assertThrows(IllegalArgumentException.class,()->RootLogDocument.prepare(latest,RootLogDocument.MAX_LINES+1,true));
    }

    @Test void oversizedFullLogOffersTailWithoutTouchingSource() throws Exception {
        Path latest=directory.resolve("latest.log");
        try(var file=new java.io.RandomAccessFile(latest.toFile(),"rw")) {file.setLength(RootLogDocument.MAX_DOCUMENT_BYTES+1);}
        assertThrows(RootLogDocument.FileTooLarge.class,()->RootLogDocument.prepare(latest,null,false));
        assertEquals(RootLogDocument.MAX_DOCUMENT_BYTES+1,Files.size(latest));
    }
}
