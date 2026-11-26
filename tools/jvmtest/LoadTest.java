/**
 * Verifies that every class of the shipped APK can be loaded/verified on the
 * device runtime (catches Dalvik verification errors before installing).
 */
public class LoadTest {
    private static final String[] CLASSES = new String[]{
            "io.legacy.localsend.App",
            "io.legacy.localsend.Core",
            "io.legacy.localsend.core.Protocol",
            "io.legacy.localsend.core.Device",
            "io.legacy.localsend.core.FileItem",
            "io.legacy.localsend.core.Identity",
            "io.legacy.localsend.core.Paths",
            "io.legacy.localsend.core.PeerConfig",
            "io.legacy.localsend.core.Settings",
            "io.legacy.localsend.core.Util",
            "io.legacy.localsend.core.P256",
            "io.legacy.localsend.core.BigIntTlsCrypto",
            "io.legacy.localsend.core.JceTlsSigner",
            "io.legacy.localsend.net.Transport",
            "io.legacy.localsend.net.Tls",
            "io.legacy.localsend.net.Http",
            "io.legacy.localsend.net.HttpServerLite",
            "io.legacy.localsend.net.Discovery",
            "io.legacy.localsend.net.PeerResolver",
            "io.legacy.localsend.net.ReceiveServer",
            "io.legacy.localsend.net.SendClient",
            "io.legacy.localsend.service.LocalSendService",
            "io.legacy.localsend.ui.MainActivity",
            "io.legacy.localsend.ui.FilePickerActivity",
            "androidx.multidex.MultiDexApplication",
    };

    public static void main(String[] args) {
        System.out.println("VM " + System.getProperty("java.vm.name"));
        int failures = 0;
        for (String name : CLASSES) {
            try {
                Class<?> clazz = Class.forName(name, false, LoadTest.class.getClassLoader());
                System.out.println("OK   " + name + " -> " + clazz.getSuperclass().getName());
            } catch (Throwable t) {
                failures++;
                System.out.println("FAIL " + name + " -> " + t);
            }
        }
        System.out.println("RESULT failures=" + failures);
    }
}
