import java.security.SecureRandom;

import org.bouncycastle.tls.NamedGroup;
import org.bouncycastle.tls.crypto.TlsAgreement;
import org.bouncycastle.tls.crypto.TlsECDomain;
import org.bouncycastle.tls.crypto.TlsSecret;
import org.bouncycastle.tls.crypto.TlsECConfig;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

/** Exercises BouncyCastle's ECDH primitives used by TLS 1.2 ECDHE. */
public class EcTest {
    public static void main(String[] args) {
        int[] groups = new int[]{NamedGroup.secp256r1, NamedGroup.secp384r1, NamedGroup.x25519};
        for (int group : groups) {
            System.out.println("=== group 0x" + Integer.toHexString(group));
            try {
                test(group);
            } catch (Throwable t) {
                System.out.println("GROUP FAIL " + t);
                t.printStackTrace();
            }
        }
    }

    private static void test(int group) throws Exception {
        BcTlsCrypto crypto = new BcTlsCrypto(new SecureRandom());
        TlsECDomain d1 = crypto.createECDomain(new TlsECConfig(group));
        TlsECDomain d2 = crypto.createECDomain(new TlsECConfig(group));
        TlsAgreement a1 = d1.createECDH();
        TlsAgreement a2 = d2.createECDH();
        byte[] p1 = a1.generateEphemeral();
        byte[] p2 = a2.generateEphemeral();
        System.out.println("pub1 len=" + p1.length + " " + hex(p1));
        System.out.println("pub2 len=" + p2.length + " " + hex(p2));
        a1.receivePeerValue(p2);
        a2.receivePeerValue(p1);
        TlsSecret s1 = a1.calculateSecret();
        TlsSecret s2 = a2.calculateSecret();
        byte[] k1 = s1.extract();
        byte[] k2 = s2.extract();
        System.out.println("secret1 len=" + k1.length + " " + hex(k1));
        System.out.println("secret2 len=" + k2.length + " " + hex(k2));
        System.out.println("MATCH " + java.util.Arrays.equals(k1, k2));
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
