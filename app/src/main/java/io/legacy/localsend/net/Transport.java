package io.legacy.localsend.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** A byte transport (plain TCP or TLS) used by the minimal HTTP implementation. */
public interface Transport extends Closeable {
    InputStream in();

    OutputStream out();

    /** Plain TCP transport. */
    final class Plain implements Transport {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        public Plain(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        public static Plain connect(String host, int port, int timeoutMs) throws IOException {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            return new Plain(socket);
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
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
