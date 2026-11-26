package io.legacy.localsend.core;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** One file (or text message) that is about to be sent. */
public class FileItem {
    public String id = Util.uuid();
    public String fileName;
    public String mimeType;
    public long size;
    public File file;
    public byte[] bytes;
    public String preview;
    public boolean textMessage;
    public long lastModified;

    public static FileItem fromFile(File file) {
        FileItem item = new FileItem();
        item.file = file;
        item.fileName = file.getName();
        item.size = file.length();
        item.mimeType = Protocol.mimeFor(file.getName());
        item.lastModified = file.lastModified();
        return item;
    }

    public static FileItem fromText(String message) {
        FileItem item = new FileItem();
        byte[] data = Util.utf8(message);
        item.bytes = data;
        item.fileName = Util.uuid() + ".txt";
        item.size = data.length;
        item.mimeType = "text/plain";
        item.preview = message;
        item.textMessage = true;
        item.lastModified = System.currentTimeMillis();
        return item;
    }

    public String legacyType() {
        return textMessage ? "text" : Protocol.legacyTypeForMime(mimeType);
    }

    public InputStream open() throws IOException {
        if (file != null) return new FileInputStream(file);
        return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
    }
}
