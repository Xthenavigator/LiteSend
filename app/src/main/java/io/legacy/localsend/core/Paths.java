package io.legacy.localsend.core;

import java.io.File;

/** Configurable receive directory (Android sets it to /sdcard/LocalSend). */
public final class Paths {
    private static volatile File downloadDir = new File("LocalSend").getAbsoluteFile();

    private Paths() {
    }

    public static void setDownloadDir(File dir) {
        if (dir != null) downloadDir = dir;
    }

    public static File downloadDir() {
        File dir = downloadDir;
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
}
