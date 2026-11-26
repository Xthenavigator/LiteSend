import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Vector;

import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.NamedGroup;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

/** Verbose BC-TLS + HTTP diagnostic used on the device via app_process. */
public class TlsDiag {
    public static void main(String[] args) throws Exception {
        final String host = args[0];
        int port = Integer.parseInt(args[1]);
        String path = args.length > 2 ? args[2] : "/api/localsend/v1/info?fingerprint=abc";
        final boolean onlyP256 = args.length > 3 && "p256".equals(args[3]);
        final boolean onlyCbc = args.length > 3 && "cbc".equals(args[3]);
        final boolean onlyRsa = args.length > 3 && "rsa".equals(args[3]);

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setSoTimeout(15000);
        System.out.println("TCP CONNECTED " + socket.getRemoteSocketAddress());

        BcTlsCrypto crypto = new BcTlsCrypto(new SecureRandom());
        TlsClientProtocol protocol = new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
        try {
            protocol.connect(new DefaultTlsClient(crypto) {
                @Override
                public TlsAuthentication getAuthentication() {
                    return new TlsAuthentication() {
                        @Override
                        public void notifyServerCertificate(TlsServerCertificate serverCertificate) {
                            System.out.println("SERVER CERT received");
                        }

                        @Override
                        public TlsCredentials getClientCredentials(CertificateRequest certificateRequest) {
                            return null;
                        }
                    };
                }

                @Override
                public ProtocolVersion[] getProtocolVersions() {
                    return ProtocolVersion.TLSv12.only();
                }

                @Override
                public int[] getCipherSuites() {
                    if (onlyRsa) {
                        return new int[]{CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                                CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA};
                    }
                    if (onlyCbc) {
                        return new int[]{CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA};
                    }
                    if (onlyP256) {
                        return new int[]{CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256};
                    }
                    return new int[]{
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                    };
                }

                @Override
                protected java.util.Vector getSupportedGroups(java.util.Vector namedGroupRoles) {
                    if (onlyP256 || onlyCbc) {
                        Vector v = new Vector();
                        v.add(Integer.valueOf(NamedGroup.secp256r1));
                        return v;
                    }
                    return super.getSupportedGroups(namedGroupRoles);
                }

                @Override
                public java.util.Hashtable getClientExtensions() throws java.io.IOException {
                    java.util.Hashtable extensions = super.getClientExtensions();
                    StringBuilder sb = new StringBuilder("CLIENT EXTENSIONS:");
                    java.util.Enumeration keys = extensions.keys();
                    while (keys.hasMoreElements()) {
                        Object key = keys.nextElement();
                        sb.append(' ').append(Integer.toHexString(((Integer) key).intValue()));
                    }
                    System.out.println(sb.toString());
                    System.out.println("SIG ALGS: " + getSupportedSignatureAlgorithms());
                    return extensions;
                }

                @Override
                public void notifyServerVersion(ProtocolVersion serverVersion) {
                    System.out.println("NEGOTIATED VERSION " + serverVersion);
                }

                @Override
                public void notifySelectedCipherSuite(int selectedCipherSuite) {
                    System.out.println("NEGOTIATED CIPHER 0x" + Integer.toHexString(selectedCipherSuite));
                }
            });
            System.out.println("HANDSHAKE OK");
        } catch (Throwable t) {
            System.out.println("HANDSHAKE FAIL " + t);
            t.printStackTrace();
            return;
        }

        try {
            OutputStream out = protocol.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port
                    + "\r\nAccept: */*\r\nConnection: close\r\n\r\n";
            out.write(request.getBytes("ISO-8859-1"));
            out.flush();
            System.out.println("REQUEST SENT");

            InputStream in = protocol.getInputStream();
            byte[] buffer = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                System.out.write(buffer, 0, n);
            }
            System.out.println("\nREAD DONE total=" + total);
        } catch (Throwable t) {
            System.out.println("READ FAIL " + t);
            t.printStackTrace();
        }
        try {
            protocol.close();
        } catch (Throwable ignored) {
        }
        socket.close();
    }
}
