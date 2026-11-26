import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;

/** Prints PRNG behaviour and installed providers (Dalvik diagnostics). */
public class RandTest {
    public static void main(String[] args) throws Exception {
        SecureRandom random = new SecureRandom();
        System.out.println("ALGO " + random.getAlgorithm());
        for (int i = 0; i < 4; i++) {
            byte[] data = new byte[16];
            random.nextBytes(data);
            System.out.println("RAND " + hex(data));
        }
        byte[] direct = new byte[16];
        new java.util.Random().nextBytes(direct);
        System.out.println("JAVAUTIL " + hex(direct));
        for (Provider provider : Security.getProviders()) {
            System.out.println("PROVIDER " + provider.getName() + " " + provider.getInfo());
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
