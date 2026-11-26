import java.math.BigInteger;

/**
 * Pure BigInteger P-256 arithmetic. Used to check whether Dalvik's JIT can be
 * trusted for Elliptic Curve operations if BC's optimised field code is avoided.
 */
public class EcdhBigTest {
    static final BigInteger P = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    static final BigInteger A = new BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16);
    static final BigInteger B = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    static final BigInteger GX = new BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16);
    static final BigInteger GY = new BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16);
    static final BigInteger N = new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);

    static final BigInteger TWO_GX = new BigInteger("7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978", 16);
    static final BigInteger TWO_GY = new BigInteger("7775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1", 16);

    public static void main(String[] args) {
        System.out.println("VM " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        try {
            System.out.println("G_ON_CURVE " + onCurve(GX, GY));
            BigInteger[] two = twice(new BigInteger[]{GX, GY});
            System.out.println("2G " + (two[0].equals(TWO_GX) && two[1].equals(TWO_GY)));
            BigInteger[] twoMul = multiply(new BigInteger[]{GX, GY}, BigInteger.valueOf(2));
            System.out.println("2G_MUL " + (twoMul[0].equals(TWO_GX) && twoMul[1].equals(TWO_GY)));

            for (int i = 0; i < 3; i++) {
                BigInteger da = new BigInteger(200, new java.security.SecureRandom()).mod(N);
                BigInteger db = new BigInteger(200, new java.security.SecureRandom()).mod(N);
                BigInteger[] pa = multiply(new BigInteger[]{GX, GY}, da);
                BigInteger[] pb = multiply(new BigInteger[]{GX, GY}, db);
                BigInteger[] sa = multiply(pb, da);
                BigInteger[] sb = multiply(pa, db);
                System.out.println("ECDH " + sa[0].equals(sb[0]) + " onCurveA=" + onCurve(pa[0], pa[1]));
            }
        } catch (Throwable t) {
            System.out.println("FAIL " + t);
            t.printStackTrace();
        }
    }

    static boolean onCurve(BigInteger x, BigInteger y) {
        BigInteger left = y.multiply(y).mod(P);
        BigInteger right = x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(P);
        return left.equals(right);
    }

    static BigInteger[] twice(BigInteger[] point) {
        BigInteger x = point[0];
        BigInteger y = point[1];
        BigInteger lambda = x.multiply(x).multiply(BigInteger.valueOf(3)).add(A)
                .multiply(y.shiftLeft(1).modInverse(P)).mod(P);
        BigInteger x3 = lambda.multiply(lambda).subtract(x.shiftLeft(1)).mod(P);
        BigInteger y3 = lambda.multiply(x.subtract(x3)).subtract(y).mod(P);
        return new BigInteger[]{x3, y3};
    }

    static BigInteger[] add(BigInteger[] p1, BigInteger[] p2) {
        if (p1 == null) return p2;
        if (p2 == null) return p1;
        if (p1[0].equals(p2[0])) {
            if (p1[1].equals(p2[1])) return twice(p1);
            return null;
        }
        BigInteger lambda = p2[1].subtract(p1[1]).multiply(p2[0].subtract(p1[0]).modInverse(P)).mod(P);
        BigInteger x3 = lambda.multiply(lambda).subtract(p1[0]).subtract(p2[0]).mod(P);
        BigInteger y3 = lambda.multiply(p1[0].subtract(x3)).subtract(p1[1]).mod(P);
        return new BigInteger[]{x3, y3};
    }

    static BigInteger[] multiply(BigInteger[] point, BigInteger scalar) {
        BigInteger[] result = null;
        BigInteger[] addend = point;
        for (int i = 0; i < scalar.bitLength(); i++) {
            if (scalar.testBit(i)) result = add(result, addend);
            addend = twice(addend);
        }
        return result;
    }
}
