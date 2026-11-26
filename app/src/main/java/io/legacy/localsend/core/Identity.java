package io.legacy.localsend.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.bouncycastle.x509.X509V3CertificateGenerator;

/**
 * Self-signed RSA identity, mirroring LocalSend's security provider.
 * The fingerprint is the SHA-256 of the certificate DER, hex encoded.
 */
public class Identity {
    public final byte[] certDer;
    public final byte[] keyDer;
    public final PrivateKey privateKey;
    public final String fingerprint;
    public final BcTlsCrypto crypto;
    public final org.bouncycastle.tls.Certificate tlsCertificate;
    public final AsymmetricKeyParameter bcPrivateKey;

    private Identity(byte[] certDer, byte[] keyDer) throws Exception {
        this.certDer = certDer;
        this.keyDer = keyDer;
        this.privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
        this.fingerprint = Util.sha256Hex(certDer);
        this.crypto = new BigIntTlsCrypto(new SecureRandom());
        this.tlsCertificate = new org.bouncycastle.tls.Certificate(
                new org.bouncycastle.tls.crypto.TlsCertificate[]{crypto.createCertificate(certDer)});
        this.bcPrivateKey = PrivateKeyFactory.createKey(keyDer);
    }

    public static Identity loadOrCreate(File dir) throws Exception {
        File certFile = new File(dir, "tls-cert.der");
        File keyFile = new File(dir, "tls-key.der");
        if (certFile.exists() && keyFile.exists()) {
            try {
                return new Identity(readFile(certFile), readFile(keyFile));
            } catch (Exception e) {
                // Corrupted files: fall through and generate new identity.
                certFile.delete();
                keyFile.delete();
            }
        }
        byte[][] generated = generate();
        dir.mkdirs();
        FileOutputStream certOut = new FileOutputStream(certFile);
        certOut.write(generated[0]);
        certOut.close();
        FileOutputStream keyOut = new FileOutputStream(keyFile);
        keyOut.write(generated[1]);
        keyOut.close();
        return new Identity(generated[0], generated[1]);
    }

    private static byte[] readFile(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            return Util.readAll(in, 1 << 20);
        } finally {
            Util.closeQuietly(in);
        }
    }

    private static byte[][] generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        X509Certificate certificate = null;
        Exception firstError = null;
        String[] algorithms = new String[]{"SHA256withRSA", "SHA1withRSA"};
        for (int i = 0; i < algorithms.length && certificate == null; i++) {
            try {
                X509V3CertificateGenerator gen = new X509V3CertificateGenerator();
                long now = System.currentTimeMillis();
                gen.setSerialNumber(BigInteger.valueOf(now / 1000));
                X500Principal dn = new X500Principal("CN=LocalSend User,O=LocalSend,OU=LocalSend");
                gen.setIssuerDN(dn);
                gen.setSubjectDN(dn);
                gen.setNotBefore(new Date(now - 24L * 3600 * 1000));
                gen.setNotAfter(new Date(now + 10L * 365 * 24 * 3600 * 1000));
                gen.setPublicKey(publicKey);
                gen.setSignatureAlgorithm(algorithms[i]);
                gen.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
                gen.addExtension(Extension.keyUsage, true,
                        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
                certificate = gen.generate(privateKey);
            } catch (Exception e) {
                firstError = e;
            }
        }
        if (certificate == null) {
            throw firstError != null ? firstError : new IOException("certificate generation failed");
        }
        // Round-trip through the certificate factory to be sure the DER is parseable
        // by the platform (matches what peers will see).
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate parsed = (X509Certificate) factory.generateCertificate(
                new java.io.ByteArrayInputStream(certificate.getEncoded()));
        return new byte[][]{parsed.getEncoded(), privateKey.getEncoded()};
    }
}

