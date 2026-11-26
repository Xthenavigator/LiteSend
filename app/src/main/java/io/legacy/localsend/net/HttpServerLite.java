package io.legacy.localsend.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.legacy.localsend.core.Identity;

/** Minimal threaded HTTP/1.1 server on top of {@link Transport}. */
public class HttpServerLite {
    public interface Handler {
        void handle(Request request, Response response) throws Exception;
    }

    public static final class Request {
        public String method;
        public String target;
        public String path;
        public final Map<String, String> query = new HashMap<String, String>();
        public final Map<String, String> headers = new HashMap<String, String>();
        public String remoteIp = "";
        public long contentLength;
        public InputStream body;

        public String param(String name) {
            return query.get(name);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        public byte[] bodyBytes(int limit) throws IOException {
            if (contentLength <= 0) return new byte[0];
            if (contentLength > limit) throw new IOException("body too large");
            byte[] data = new byte[(int) contentLength];
            int offset = 0;
            while (offset < data.length) {
                int n = body.read(data, offset, data.length - offset);
                if (n < 0) throw new IOException("unexpected end of stream");
                offset += n;
            }
            return data;
        }
    }

    public static final class Response {
        private final OutputStream out;
        private boolean sent;

        Response(OutputStream out) {
            this.out = out;
        }

        public boolean isSent() {
            return sent;
        }

        public void send(int code, String contentType, byte[] data) throws IOException {
            if (sent) return;
            sent = true;
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
            if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Content-Length: ").append(data == null ? 0 : data.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes("ISO-8859-1"));
            if (data != null && data.length > 0) out.write(data);
            out.flush();
        }

        public void sendEmpty(int code) throws IOException {
            send(code, null, new byte[0]);
        }

        public void sendJson(int code, String json) throws IOException {
            send(code, "application/json", json == null ? new byte[0] : json.getBytes("UTF-8"));
        }

        public void sendError(int code, String message) throws IOException {
            org.json.JSONObject json = new org.json.JSONObject();
            try {
                json.put("message", message == null ? "" : message);
            } catch (Exception ignored) {
            }
            sendJson(code, json.toString());
        }
    }

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private final Identity identity;
    private final boolean https;
    private final Handler handler;
    private int port;

    public HttpServerLite(Identity identity, boolean https, Handler handler) {
        this.identity = identity;
        this.https = https;
        this.handler = handler;
    }

    public void start(int port) throws IOException {
        this.port = port;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port), 50);
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "localsend-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int getPort() {
        return port;
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!running) return;
                continue;
            }
            final Socket client = socket;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(client);
                }
            }, "localsend-http-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(Socket socket) {
        Transport transport = null;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(60000);
            if (https) {
                transport = Tls.accept(socket, identity);
            } else {
                transport = new Transport.Plain(socket);
            }

            BufferedInputStream in = new BufferedInputStream(transport.in(), 32 * 1024);
            BufferedOutputStream out = new BufferedOutputStream(transport.out(), 32 * 1024);

            String requestLine = Http.readLine(in);
            if (requestLine == null || requestLine.length() == 0) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            Request request = new Request();
            request.method = parts[0].toUpperCase(Locale.US);
            request.target = parts[1];
            int q = request.target.indexOf('?');
            if (q >= 0) {
                request.path = request.target.substring(0, q);
                parseQuery(request.target.substring(q + 1), request.query);
            } else {
                request.path = request.target;
            }
            request.remoteIp = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();

            String line;
            while ((line = Http.readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                request.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
            }

            long contentLength = 0;
            String cl = request.headers.get("content-length");
            if (cl != null) {
                try {
                    contentLength = Long.parseLong(cl.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            request.contentLength = contentLength;

            String expect = request.headers.get("expect");
            if (expect != null && expect.toLowerCase(Locale.US).contains("100-continue")) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("ISO-8859-1"));
                out.flush();
            }

            request.body = new LimitedInputStream(in, contentLength);
            Response response = new Response(out);
            try {
                handler.handle(request, response);
            } catch (Exception e) {
                if (!response.isSent()) {
                    try {
                        response.sendError(500, "Internal error: " + e.getClass().getSimpleName());
                    } catch (IOException ignored) {
                    }
                }
            }
            try {
                request.body.close();
            } catch (Exception ignored) {
            }
            try {
                out.flush();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            // handshake failures / client aborts are expected noise on a LAN,
            // but keep a trace for debugging sessions.
            System.err.println("[httpserver] " + e);
            e.printStackTrace();
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (Exception ignored) {
                }
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void parseQuery(String query, Map<String, String> target) {
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.length() == 0) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) {
                target.put(io.legacy.localsend.core.Util.urlDecode(pair), "");
            } else {
                target.put(io.legacy.localsend.core.Util.urlDecode(pair.substring(0, eq)),
                        io.legacy.localsend.core.Util.urlDecode(pair.substring(eq + 1)));
            }
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 412: return "Precondition Failed";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    /** Reads at most {@code limit} bytes; draining on close keeps the socket usable. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(length, remaining);
            int n = in.read(buffer, offset, toRead);
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            byte[] skip = new byte[8192];
            long drainLimit = 4L * 1024 * 1024;
            while (remaining > 0 && drainLimit > 0) {
                int want = (int) Math.min(skip.length, Math.min(remaining, drainLimit));
                int n = in.read(skip, 0, want);
                if (n < 0) break;
                remaining -= n;
                drainLimit -= n;
            }
        }
    }
}
