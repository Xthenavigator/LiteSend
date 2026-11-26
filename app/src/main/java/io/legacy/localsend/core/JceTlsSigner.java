package io.legacy.localsend.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.PrivateKey;
import java.security.Signature;

import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsCredentialedSigner;
import org.bouncycastle.tls.crypto.TlsStreamSigner;

/**
 * RSA signer backed by the platform JCE provider.
 *
 * BouncyCastle's RSABlindedEngine needs a modular inverse of its blinding factor
 * which fails on Dalvik (same class of problem as the EC field arithmetic), so
 * the server-side ServerKeyExchange signature is produced by Android's native
 * RSA implementation instead.
 */
public class JceTlsSigner implements TlsCredentialedSigner {
    private final PrivateKey privateKey;
    private final Certificate certificate;
    private final SignatureAndHashAlgorithm algorithm =
            new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa);

    public JceTlsSigner(PrivateKey privateKey, Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    @Override
    public Certificate getCertificate() {
        return certificate;
    }

    @Override
    public SignatureAndHashAlgorithm getSignatureAndHashAlgorithm() {
        return algorithm;
    }

    /**
     * BouncyCastle feeds the handshake bytes through this stream when a stream
     * signer is available (TLS 1.2 ServerKeyExchange), so the platform hashes the
     * message itself with SHA-256 and PKCS#1 v1.5 padding.
     */
    @Override
    public TlsStreamSigner getStreamSigner() {
        return new TlsStreamSigner() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public OutputStream getOutputStream() {
                return buffer;
            }

            @Override
            public byte[] getSignature() throws IOException {
                try {
                    Signature signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(privateKey);
                    signature.update(buffer.toByteArray());
                    return signature.sign();
                } catch (Exception e) {
                    throw new IOException("RSA signature failed: " + e);
                }
            }
        };
    }

    @Override
    public byte[] generateRawSignature(byte[] message) throws IOException {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        } catch (Exception e) {
            throw new IOException("RSA signature failed: " + e);
        }
    }
}
