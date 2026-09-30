package ru.vaulttracker;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Read-only latest.log export. Temporary documents are removed by the caller. */
final class RootLogDocument {
    static final int LAST_LINES=300, MAX_LINES=100_000;
    static final long MAX_DOCUMENT_BYTES=49L*1024*1024;

    record Prepared(Path path,String filename,boolean temporary) implements AutoCloseable {
        @Override public void close() throws IOException {if(temporary) Files.deleteIfExists(path);}
    }

    static Prepared prepare(Path latest) throws IOException {return prepare(latest,LAST_LINES,true);}

    static Prepared prepare(Path latest,Integer lines,boolean markdown) throws IOException {
        if(!Files.isRegularFile(latest) || !Files.isReadable(latest)) throw new IOException("latest.log недоступен");
        if(lines!=null && (lines<1 || lines>MAX_LINES)) throw new IllegalArgumentException("Число строк должно быть от 1 до "+MAX_LINES);
        if(lines==null && !markdown) {
            if(Files.size(latest)>MAX_DOCUMENT_BYTES) throw new FileTooLarge();
            return new Prepared(latest,"latest.log",false);
        }
        Path output=Files.createTempFile("vaulttracker-latest-",markdown?".md":".log");
        try {
            if(!markdown) {
                try(var source=FileChannel.open(latest,StandardOpenOption.READ);
                    var target=FileChannel.open(output,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                    long start=lastLinesStart(source,source.size(),lines);
                    long cursor=start;
                    while(cursor<source.size()) {
                        long copied=source.transferTo(cursor,source.size()-cursor,target);
                        if(copied<=0) throw new EOFException("latest.log изменился во время чтения");
                        cursor+=copied;
                    }
                }
                if(Files.size(output)>MAX_DOCUMENT_BYTES) throw new FileTooLarge();
                return new Prepared(output,"latest-"+lines+".log",true);
            }
            try(var source=FileChannel.open(latest,StandardOpenOption.READ);
                var writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8)) {
                long start=lines==null?0:lastLinesStart(source,source.size(),lines);
                source.position(start);
                if(markdown) writer.write("# Лог Minecraft-сервера\n\n"+(lines==null?"Полный `latest.log`":"Последние "+lines+" строк из `latest.log` (или весь лог, если он короче)")+".\n\n");
                try(var reader=new BufferedReader(new InputStreamReader(Channels.newInputStream(source),StandardCharsets.UTF_8))) {
                    String line;int written=0;
                    while((lines==null || written<lines) && (line=reader.readLine())!=null) {
                        if(markdown) writer.write("    ");
                        writer.write(line);writer.newLine();written++;
                    }
                    if(written==0 && markdown) writer.write("_Лог пуст._\n");
                }
            }
            if(Files.size(output)>MAX_DOCUMENT_BYTES) throw new FileTooLarge();
            return new Prepared(output,lines==null?"latest.md":"latest-"+lines+(markdown?".md":".log"),true);
        } catch(IOException | RuntimeException failure) {Files.deleteIfExists(output);throw failure;}
    }

    static final class FileTooLarge extends IOException {
        FileTooLarge() {super("Файл слишком большой для отправки в Telegram. Выберите последние строки.");}
    }

    private static long lastLinesStart(FileChannel source,long size,int count) throws IOException {
        ByteBuffer block=ByteBuffer.allocate(8192);long cursor=size;int found=0;
        boolean trailingNewline=true;
        while(cursor>0) {
            int length=(int)Math.min(block.capacity(),cursor);cursor-=length;block.clear();block.limit(length);
            while(block.hasRemaining()) {
                int read=source.read(block,cursor+block.position());
                if(read<0) throw new EOFException("latest.log изменился во время чтения");
            }
            for(int i=length-1;i>=0;i--) {
                if(trailingNewline) {trailingNewline=false;if(cursor+i==size-1 && block.get(i)=='\n') continue;}
                if(block.get(i)=='\n' && ++found==count) return cursor+i+1;
            }
        }
        return 0;
    }

    private RootLogDocument() {}
}
