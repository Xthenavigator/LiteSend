package io.legacy.localsend.core;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;

import org.bouncycastle.tls.NamedGroup;
import org.bouncycastle.tls.crypto.TlsAgreement;
import org.bouncycastle.tls.crypto.TlsECConfig;
import org.bouncycastle.tls.crypto.TlsECDomain;
import org.bouncycastle.tls.crypto.TlsSecret;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

/**
 * BcTlsCrypto with the ECDH primitive replaced by {@link P256}.
 *
 * Everything else (AES-GCM, SHA-256, RSA, records) comes from BouncyCastle,
 * which behaves correctly on Dalvik; only the int-limb EC arithmetic is broken
 * there. Only secp256r1 is supported, so peers must be offered that group alone.
 */
public class BigIntTlsCrypto extends BcTlsCrypto {
    public BigIntTlsCrypto(SecureRandom random) {
        super(random);
    }

    @Override
    public TlsECDomain createECDomain(TlsECConfig ecConfig) {
        int group = ecConfig == null ? NamedGroup.secp256r1 : ecConfig.getNamedGroup();
        if (group != NamedGroup.secp256r1) {
            throw new IllegalArgumentException("unsupported EC group: " + group);
        }
        return new P256Domain(this);
    }

    private static final class P256Domain implements TlsECDomain {
        private final BigIntTlsCrypto crypto;

        P256Domain(BigIntTlsCrypto crypto) {
            this.crypto = crypto;
        }

        @Override
        public TlsAgreement createECDH() {
            return new P256Agreement(crypto);
        }
    }

    private static final class P256Agreement implements TlsAgreement {
        private final BigIntTlsCrypto crypto;
        private BigInteger privateKey;
        private BigInteger[] peerPoint;

        P256Agreement(BigIntTlsCrypto crypto) {
            this.crypto = crypto;
        }

        @Override
        public byte[] generateEphemeral() {
            if (privateKey == null) {
                privateKey = P256.randomScalar(crypto.getSecureRandom());
            }
            return P256.encodePoint(P256.multiply(P256.G, privateKey));
        }

        @Override
        public void receivePeerValue(byte[] peerValue) throws IOException {
            peerPoint = P256.decodePoint(peerValue);
            if (peerPoint == null) {
                throw new IOException("invalid EC point");
            }
        }

        @Override
        public TlsSecret calculateSecret() throws IOException {
            if (privateKey == null || peerPoint == null) {
                throw new IOException("incomplete ECDH state");
            }
            return crypto.createSecret(P256.encodeX(P256.multiply(peerPoint, privateKey)));
        }
    }
}
