import java.math.BigInteger;

import org.bouncycastle.math.ec.rfc7748.X25519;
import org.bouncycastle.math.raw.Mod;

/** Primitive arithmetic self-test: catches VM-level math differences. */
public class PrimTest {
    public static void main(String[] args) {
        System.out.println("VM " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version")
                + " vendor=" + System.getProperty("java.vendor") + " sdk=" + System.getProperty("java.version"));

        long a = 0x7fffffffffffffffL;
        long b = 3;
        System.out.println("MUL " + Long.toHexString(a * b));
        long c = -1L;
        System.out.println("SHR " + Long.toHexString(c >>> 32));

        BigInteger p = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
        System.out.println("BIGINV " + p.subtract(BigInteger.ONE).divide(BigInteger.valueOf(3)).toString(16));

        try {
            int[] m = toInts(hex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"));
            int[] x = toInts(hex("0000000000000000000000000000000000000000000000000000000000000003"));
            int[] z = new int[m.length];
            Mod.checkedModOddInverse(m, x, z);
            System.out.println("MODINV z=" + toHex(z));
        } catch (Throwable t) {
            System.out.println("MODINV FAIL " + t);
        }

        try {
            byte[] scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
            byte[] point = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
            byte[] out = new byte[32];
            X25519.scalarMult(scalar, 0, point, 0, out, 0);
            String got = bytesHex(out);
            String expected = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552";
            System.out.println("X25519 got=" + got);
            System.out.println("X25519 expect=" + expected);
            System.out.println("X25519 MATCH " + expected.equals(got));
        } catch (Throwable t) {
            System.out.println("X25519 FAIL " + t);
            t.printStackTrace();
        }
    }

    private static int[] toInts(byte[] data) {
        int[] out = new int[data.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((data[i * 4] & 0xff) << 24) | ((data[i * 4 + 1] & 0xff) << 16)
                    | ((data[i * 4 + 2] & 0xff) << 8) | (data[i * 4 + 3] & 0xff);
        }
        return out;
    }

    private static String toHex(int[] data) {
        StringBuilder sb = new StringBuilder();
        for (int v : data) {
            sb.append(Integer.toHexString(v));
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String bytesHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte v : data) {
            int b = v & 0xff;
            if (b < 16) sb.append('0');
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
