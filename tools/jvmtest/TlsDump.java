import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;

import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

/**
 * TLS 1.2 transcript recorder: dumps the raw TLS records exchanged with a peer
 * so a failing handshake can be inspected on a device without packet capture.
 */
public class TlsDump {
    private static final ByteArrayOutputStream SENT = new ByteArrayOutputStream();
    private static final ByteArrayOutputStream RECEIVED = new ByteArrayOutputStream();

    public static void main(String[] args) throws Exception {
        final String host = args[0];
        int port = Integer.parseInt(args[1]);
        String path = args.length > 2 ? args[2] : "/api/localsend/v1/info?fingerprint=abc";

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setSoTimeout(15000);
        System.out.println("TCP CONNECTED " + socket.getRemoteSocketAddress());

        InputStream in = new RecorderInput(socket.getInputStream(), RECEIVED);
        OutputStream out = new Recorder(socket.getOutputStream(), SENT);
        BcTlsCrypto crypto = new BcTlsCrypto(new SecureRandom());
        TlsClientProtocol protocol = new TlsClientProtocol(in, out);
        boolean ok = false;
        try {
            protocol.connect(new DefaultTlsClient(crypto) {
                @Override
                public TlsAuthentication getAuthentication() {
                    return new TlsAuthentication() {
                        @Override
                        public void notifyServerCertificate(TlsServerCertificate serverCertificate) {
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
                    return new int[]{
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                    };
                }
            });
            ok = true;
            System.out.println("HANDSHAKE OK");
        } catch (Throwable t) {
            System.out.println("HANDSHAKE FAIL " + t);
        }

        dump("CLIENT->SERVER", SENT.toByteArray());
        dump("SERVER->CLIENT", RECEIVED.toByteArray());
        System.out.println("RESULT " + (ok ? "OK" : "FAIL"));
    }

    private static void dump(String label, byte[] data) {
        System.out.println("=== " + label + " (" + data.length + " bytes) ===");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i % 32 == 0) sb.append('\n');
            sb.append(String.format("%02x", data[i] & 0xff));
        }
        System.out.println(sb.toString());
    }

    private static final class Recorder extends OutputStream {
        private final OutputStream delegate;
        private final ByteArrayOutputStream record;

        Recorder(OutputStream delegate, ByteArrayOutputStream record) {
            this.delegate = delegate;
            this.record = record;
        }

        @Override
        public void write(int b) throws IOException {
            record.write(b);
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            record.write(b, off, len);
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class RecorderInput extends InputStream {
        private final InputStream delegate;
        private final ByteArrayOutputStream record;

        RecorderInput(InputStream delegate, ByteArrayOutputStream record) {
            this.delegate = delegate;
            this.record = record;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) record.write(b);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) record.write(b, off, n);
            return n;
        }
    }
}
