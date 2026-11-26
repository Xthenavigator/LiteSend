package io.legacy.localsend.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

/** Minimal HTTP/1.1 client working on top of {@link Transport} (plain or BC TLS). */
public final class Http {
    private Http() {
    }

    public interface Body {
        long length();

        void writeTo(OutputStream out) throws IOException;
    }

    public static final class Response {
        public final int code;
        public final Map<String, String> headers;
        public final byte[] body;

        Response(int code, Map<String, String> headers, byte[] body) {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }

        public String text() {
            try {
                return new String(body, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        public JSONObject json() {
            try {
                return new JSONObject(text());
            } catch (Exception e) {
                return null;
            }
        }

        public String errorMessage() {
            JSONObject json = json();
            if (json != null && json.has("message")) return json.optString("message", "");
            String text = text();
            return text.length() > 300 ? text.substring(0, 300) : text;
        }
    }

    public static Response execute(String host, int port, boolean https, String method, String path,
                                   Map<String, String> headers, Body body,
                                   int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        Transport transport = null;
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            if (https) {
                transport = Tls.connect(socket);
            } else {
                transport = new Transport.Plain(socket);
            }

            OutputStream out = new BufferedOutputStream(transport.out(), 32 * 1024);
            InputStream in = new BufferedInputStream(transport.in(), 32 * 1024);

            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host).append(':').append(port).append("\r\n");
            head.append("User-Agent: LocalSendLite/1.0\r\n");
            head.append("Accept: */*\r\n");
            head.append("Connection: close\r\n");
            if (body != null) {
                head.append("Content-Length: ").append(body.length()).append("\r\n");
            }
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                }
            }
            head.append("\r\n");
            out.write(head.toString().getBytes("ISO-8859-1"));
            IOException writeError = null;
            try {
                if (body != null) {
                    body.writeTo(out);
                }
                out.flush();
            } catch (IOException e) {
                // The peer may reject the request while the body is still being written
                // (e.g. 403 on upload). Keep the response if there is one.
                writeError = e;
            }

            try {
                return readResponse(in);
            } catch (IOException readError) {
                if (writeError != null) throw writeError;
                throw readError;
            }
        } finally {
            if (transport != null) transport.close();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Response readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || statusLine.length() == 0) {
            throw new IOException("empty response");
        }
        String[] parts = statusLine.split(" ", 3);
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            throw new IOException("malformed status line: " + statusLine);
        }

        Map<String, String> headers = new HashMap<String, String>();
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
        }

        byte[] body = new byte[0];
        if (code != 204 && code != 304 && code >= 200) {
            String transferEncoding = headers.get("transfer-encoding");
            String contentLength = headers.get("content-length");
            if (transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
                body = readChunked(in);
            } else if (contentLength != null) {
                long length;
                try {
                    length = Long.parseLong(contentLength.trim());
                } catch (NumberFormatException e) {
                    length = -1;
                }
                body = length >= 0 ? readExactly(in, (int) Math.min(length, Integer.MAX_VALUE)) : readAll(in);
            } else {
                body = readAll(in);
            }
        }
        return new Response(code, headers, body);
    }

    /** Reads until EOF; a missing TLS close_notify is treated as a normal end of stream. */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (true) {
            try {
                n = in.read(buf);
            } catch (IOException e) {
                if (e instanceof org.bouncycastle.tls.TlsNoCloseNotifyException) break;
                String name = e.getClass().getSimpleName();
                if ("TlsNoCloseNotifyException".equals(name) || "SocketException".equals(name)) break;
                throw e;
            }
            if (n <= 0) break;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(data, offset, length - offset);
            if (n < 0) throw new IOException("unexpected end of stream");
            offset += n;
        }
        return data;
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;
            int semicolon = sizeLine.indexOf(';');
            if (semicolon >= 0) sizeLine = sizeLine.substring(0, semicolon);
            int size;
            try {
                size = Integer.parseInt(sizeLine.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size: " + sizeLine);
            }
            if (size == 0) {
                // consume trailers
                while ((sizeLine = readLine(in)) != null && sizeLine.length() > 0) {
                    // ignore
                }
                break;
            }
            out.write(readExactly(in, size));
            readLine(in); // trailing CRLF
        }
        return out.toByteArray();
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b == '\r') continue;
            out.write(b);
        }
        if (b < 0 && out.size() == 0) return null;
        return new String(out.toByteArray(), "ISO-8859-1");
    }
}
