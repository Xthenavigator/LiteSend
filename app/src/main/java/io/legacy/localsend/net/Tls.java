package io.legacy.localsend.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;

import io.legacy.localsend.core.Identity;

import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.DefaultTlsServer;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.NamedGroup;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentialedDecryptor;
import org.bouncycastle.tls.TlsCredentialedSigner;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.TlsServerContext;
import org.bouncycastle.tls.TlsServerProtocol;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.TlsECConfig;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedDecryptor;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import io.legacy.localsend.core.BigIntTlsCrypto;

/**
 * TLS 1.2 via BouncyCastle's lightweight API.
 *
 * Android's JSSE implementation cannot be replaced by a third-party provider
 * (SSLContext ignores Security providers), so the platform TLS stack cannot be
 * upgraded on API 14-18. The BC lightweight API is pure Java and provides
 * TLS 1.2 with AES-GCM and ECDHE, which is what modern LocalSend peers speak.
 */
public final class Tls {
    /**
     * Dalvik (Android 4.x) JIT miscompiles BouncyCastle's ECDHE field arithmetic
     * (verified: X25519 RFC 7748 vector is correct only with -Xint, and ECDHE
     * handshakes abort while RSA key exchange works). RSA key exchange uses only
     * RSA/AES/SHA, which behave correctly on Dalvik, so it is the default on
     * legacy hardware. Peers are authenticated by IP/TCP only (LocalSend does not
     * validate certificates either), so this only drops forward secrecy.
     */
    public static volatile boolean RSA_KEY_EXCHANGE_ONLY = false;
    public static volatile boolean CBC_ONLY = false;

    private static final int[] ECDHE_CIPHER_SUITES = new int[]{
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
    };

    private static final int[] RSA_CIPHER_SUITES = new int[]{
            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
    };

    private static int[] cipherSuites() {
        if (CBC_ONLY) {
            return new int[]{CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA};
        }
        return (RSA_KEY_EXCHANGE_ONLY ? RSA_CIPHER_SUITES : ECDHE_CIPHER_SUITES).clone();
    }

    private Tls() {
    }

    /** Connects to a peer and returns a TLS transport. Certificates are not validated on purpose. */
    public static Transport connect(Socket socket) throws IOException {
        final BigIntTlsCrypto crypto = new BigIntTlsCrypto(new SecureRandom());
        final TlsClientProtocol protocol = new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
        DefaultTlsClient client = new DefaultTlsClient(crypto) {
            @Override
            public TlsAuthentication getAuthentication() {
                return new TlsAuthentication() {
                    @Override
                    public void notifyServerCertificate(TlsServerCertificate serverCertificate) {
                        // LocalSend trusts any self-signed certificate: peers are identified
                        // by IP + TCP, and the fingerprint is only used for self-detection.
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
                return cipherSuites();
            }

            @Override
            protected java.util.Vector getSupportedGroups(java.util.Vector namedGroupRoles) {
                java.util.Vector groups = new java.util.Vector();
                groups.add(Integer.valueOf(NamedGroup.secp256r1));
                return groups;
            }
        };
        protocol.connect(client);
        return new TlsTransport(protocol, socket);
    }

    /** Performs the server side handshake for an accepted socket. */
    public static Transport accept(Socket socket, final Identity identity) throws IOException {
        final BcTlsCrypto crypto = identity.crypto;
        final TlsServerProtocol protocol = new TlsServerProtocol(socket.getInputStream(), socket.getOutputStream());
        DefaultTlsServer server = new DefaultTlsServer(crypto) {
            private TlsServerContext context;

            @Override
            public void init(TlsServerContext context) {
                this.context = context;
                super.init(context);
            }

            @Override
            public ProtocolVersion[] getProtocolVersions() {
                return ProtocolVersion.TLSv12.only();
            }

            @Override
            public int[] getCipherSuites() {
                return cipherSuites();
            }

            @Override
            protected TlsCredentialedSigner getRSASignerCredentials() throws IOException {
                return new io.legacy.localsend.core.JceTlsSigner(identity.privateKey, identity.tlsCertificate);
            }

            @Override
            protected TlsCredentialedDecryptor getRSAEncryptionCredentials() throws IOException {
                return new BcDefaultTlsCredentialedDecryptor(crypto, identity.tlsCertificate,
                        identity.bcPrivateKey);
            }

            @Override
            public TlsECConfig getECDHConfig() {
                return new TlsECConfig(NamedGroup.secp256r1);
            }

            @Override
            public int[] getSupportedGroups() {
                return new int[]{NamedGroup.secp256r1};
            }
        };
        protocol.accept(server);
        return new TlsTransport(protocol, socket);
    }

    private static final class TlsTransport implements Transport {
        private final TlsClientProtocol clientProtocol;
        private final TlsServerProtocol serverProtocol;
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        TlsTransport(TlsClientProtocol protocol, Socket socket) throws IOException {
            this.clientProtocol = protocol;
            this.serverProtocol = null;
            this.socket = socket;
            this.in = protocol.getInputStream();
            this.out = protocol.getOutputStream();
        }

        TlsTransport(TlsServerProtocol protocol, Socket socket) throws IOException {
            this.clientProtocol = null;
            this.serverProtocol = protocol;
            this.socket = socket;
            this.in = protocol.getInputStream();
            this.out = protocol.getOutputStream();
        }

        @Override
        public InputStream in() {
            return in;
        }

        @Override
        public OutputStream out() {
            return out;
        }

        @Override
        public void close() {
            try {
                if (clientProtocol != null) clientProtocol.close();
                else if (serverProtocol != null) serverProtocol.close();
            } catch (Exception ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}





