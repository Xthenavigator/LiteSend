import java.io.*;
import java.util.zip.*;
import java.util.*;

/**
 * Flattens a (possibly multi-release) jar into a plain jar:
 * drops META-INF/versions/**, signature files and module-info.class.
 * Needed because Dalvik / older D8 does not understand Java 9+ class files.
 */
public class FlattenJar {
    public static void main(String[] args) throws Exception {
        File src = new File(args[0]);
        File dst = new File(args[1]);
        dst.getParentFile().mkdirs();
        ZipInputStream in = new ZipInputStream(new FileInputStream(src));
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(dst));
        Set<String> written = new HashSet<>();
        ZipEntry e;
        byte[] buf = new byte[65536];
        while ((e = in.getNextEntry()) != null) {
            String name = e.getName();
            if (e.isDirectory()) continue;
            if (name.startsWith("META-INF/versions/")) continue;
            if (name.equals("module-info.class")) continue;
            if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA")
                    || name.endsWith(".RSA") || name.contains("BC1024KE") || name.contains("BC2048KE"))) continue;
            if (name.equals("META-INF/MANIFEST.MF")) continue;
            if (!written.add(name)) continue;
            out.putNextEntry(new ZipEntry(name));
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.closeEntry();
        }
        out.close();
        in.close();
        System.out.println("WROTE " + dst.getAbsolutePath());
    }
}
