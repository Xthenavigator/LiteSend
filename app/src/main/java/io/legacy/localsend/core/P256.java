package io.legacy.localsend.core;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Minimal P-256 (secp256r1) arithmetic on BigInteger.
 *
 * BouncyCastle's optimised field arithmetic (int limbs) produces wrong points
 * under Dalvik's JIT on Android 4.x: X25519 fails the RFC 7748 vector and ECDHE
 * handshakes abort, while the same code is correct on HotSpot and with -Xint.
 * BigInteger operations are unaffected, so TLS uses this implementation instead.
 */
public final class P256 {
    public static final BigInteger P = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    public static final BigInteger A = new BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16);
    public static final BigInteger B = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    public static final BigInteger N = new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
    private static final BigInteger GX = new BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16);
    private static final BigInteger GY = new BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16);
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    /** Affine point; null represents the point at infinity. */
    public static final BigInteger[] G = new BigInteger[]{GX, GY};

    private P256() {
    }

    public static boolean isOnCurve(BigInteger x, BigInteger y) {
        if (x == null || y == null) return false;
        BigInteger left = y.multiply(y).mod(P);
        BigInteger right = x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(P);
        return left.equals(right);
    }

    public static BigInteger[] twice(BigInteger[] point) {
        if (point == null) return null;
        BigInteger x = point[0];
        BigInteger y = point[1];
        if (y.signum() == 0) return null;
        BigInteger lambda = x.multiply(x).multiply(THREE).add(A)
                .multiply(y.shiftLeft(1).modInverse(P)).mod(P);
        BigInteger x3 = lambda.multiply(lambda).subtract(x.shiftLeft(1)).mod(P);
        BigInteger y3 = lambda.multiply(x.subtract(x3)).subtract(y).mod(P);
        return new BigInteger[]{x3, y3};
    }

    public static BigInteger[] add(BigInteger[] p1, BigInteger[] p2) {
        if (p1 == null) return p2;
        if (p2 == null) return p1;
        if (p1[0].equals(p2[0])) {
            if (p1[1].equals(p2[1])) return twice(p1);
            return null;
        }
        BigInteger lambda = p2[1].subtract(p1[1])
                .multiply(p2[0].subtract(p1[0]).modInverse(P)).mod(P);
        BigInteger x3 = lambda.multiply(lambda).subtract(p1[0]).subtract(p2[0]).mod(P);
        BigInteger y3 = lambda.multiply(p1[0].subtract(x3)).subtract(p1[1]).mod(P);
        return new BigInteger[]{x3, y3};
    }

    public static BigInteger[] multiply(BigInteger[] point, BigInteger scalar) {
        BigInteger[] result = null;
        BigInteger[] addend = point;
        for (int i = 0; i < scalar.bitLength(); i++) {
            if (scalar.testBit(i)) result = add(result, addend);
            addend = twice(addend);
        }
        return result;
    }

    public static BigInteger randomScalar(SecureRandom random) {
        while (true) {
            BigInteger candidate = new BigInteger(256, random).mod(N);
            if (candidate.signum() != 0) return candidate;
        }
    }

    /** Uncompressed point encoding: 0x04 || X(32) || Y(32). */
    public static byte[] encodePoint(BigInteger[] point) {
        if (point == null) throw new IllegalArgumentException("point at infinity");
        byte[] out = new byte[65];
        out[0] = 0x04;
        byte[] x = toFixed(point[0]);
        byte[] y = toFixed(point[1]);
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    public static BigInteger[] decodePoint(byte[] data) {
        if (data == null || data.length != 65 || data[0] != 0x04) return null;
        BigInteger x = new BigInteger(1, slice(data, 1, 33));
        BigInteger y = new BigInteger(1, slice(data, 33, 65));
        if (x.compareTo(P) >= 0 || y.compareTo(P) >= 0) return null;
        if (!isOnCurve(x, y)) return null;
        return new BigInteger[]{x, y};
    }

    /** Shared secret: the big-endian X coordinate, zero padded to 32 bytes. */
    public static byte[] encodeX(BigInteger[] point) {
        if (point == null) throw new IllegalArgumentException("point at infinity");
        return toFixed(point[0]);
    }

    private static byte[] toFixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] slice(byte[] data, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(data, from, out, 0, out.length);
        return out;
    }
}
