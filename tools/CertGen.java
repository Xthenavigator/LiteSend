import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.x509.X509V3CertificateGenerator;

/** Generates a self-signed RSA certificate for LocalSend-style TLS. */
public class CertGen {
    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : ".");
        outDir.mkdirs();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        X509V3CertificateGenerator gen = new X509V3CertificateGenerator();
        gen.setSerialNumber(BigInteger.valueOf(now / 1000));
        gen.setIssuerDN(new X500Principal("CN=LocalSend User,O=LocalSend,OU=LocalSend"));
        gen.setSubjectDN(new X500Principal("CN=LocalSend User,O=LocalSend,OU=LocalSend"));
        gen.setNotBefore(new Date(now - 24L * 3600 * 1000));
        gen.setNotAfter(new Date(now + 10L * 365 * 24 * 3600 * 1000));
        gen.setPublicKey(kp.getPublic());
        gen.setSignatureAlgorithm("SHA256withRSA");
        gen.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        gen.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        X509Certificate cert = gen.generate(kp.getPrivate());

        byte[] certDer = cert.getEncoded();
        byte[] keyDer = kp.getPrivate().getEncoded(); // PKCS#8

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] fp = md.digest(certDer);
        StringBuilder sb = new StringBuilder();
        for (byte b : fp) sb.append(String.format("%02x", b & 0xff));
        System.out.println("FINGERPRINT=" + sb);

        write(new File(outDir, "cert.der"), certDer);
        write(new File(outDir, "key.der"), keyDer);
        writePem(new File(outDir, "cert.pem"), "CERTIFICATE", certDer);
        writePem(new File(outDir, "key.pem"), "PRIVATE KEY", keyDer);
    }

    static void write(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
    }

    static void writePem(File f, String type, byte[] der) throws IOException {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END ").append(type).append("-----\n");
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        w.write(sb.toString());
        w.close();
    }
}
