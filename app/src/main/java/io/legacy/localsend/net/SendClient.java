package io.legacy.localsend.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

import io.legacy.localsend.core.Device;
import io.legacy.localsend.core.FileItem;
import io.legacy.localsend.core.Identity;
import io.legacy.localsend.core.Protocol;
import io.legacy.localsend.core.PeerConfig;
import io.legacy.localsend.core.Util;

/** Implements the sending half of the LocalSend protocol (client side). */
public class SendClient {
    public interface Listener {
        void onPrepared(Device target);

        void onFileProgress(int index, int total, String fileName, long sent, long size);

        void onFinished();

        void onError(String message);

        void onCanceled();
    }

    private final PeerConfig settings;
    private final Identity identity;
    private volatile boolean canceled;

    public SendClient(PeerConfig settings, Identity identity) {
        this.settings = settings;
        this.identity = identity;
    }

    public void cancel() {
        canceled = true;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void send(Device target, List<FileItem> items, Listener listener) {
        canceled = false;
        String version = target.version == null ? Protocol.VERSION : target.version;
        boolean v1 = "1.0".equals(version);
        try {
            listener.onPrepared(target);

            JSONObject root = new JSONObject();
            root.put("info", infoJson());
            JSONObject filesJson = new JSONObject();
            for (FileItem item : items) {
                JSONObject file = new JSONObject();
                file.put("id", item.id);
                file.put("fileName", item.fileName);
                file.put("size", item.size);
                file.put("fileType", v1 ? item.legacyType() : item.mimeType);
                if (item.preview != null) file.put("preview", item.preview);
                JSONObject metadata = new JSONObject();
                metadata.put("modified", Util.isoNow(item.lastModified));
                metadata.put("accessed", Util.isoNow(item.lastModified));
                file.put("metadata", metadata);
                filesJson.put(item.id, file);
            }
            root.put("files", filesJson);

            byte[] payload = root.toString().getBytes(Util.UTF8);
            // The receiver shows an interactive accept dialog, so this can take a while.
            Http.Response prepareResponse = Http.execute(target.ip, target.port, target.https,
                    "POST", Protocol.prepareUploadPath(version), jsonHeaders(),
                    new ByteBody(payload), 10000, 600000);

            if (prepareResponse.code == 204) {
                // Receiver had nothing to do (e.g. a text message was already read).
                if (!canceled) listener.onFinished();
                return;
            }
            if (prepareResponse.code != 200) {
                listener.onError(describeError(prepareResponse));
                return;
            }

            String sessionId = null;
            Map<String, String> tokens = new HashMap<String, String>();
            JSONObject responseJson = prepareResponse.json();
            if (responseJson == null) {
                listener.onError("响应格式错误");
                return;
            }
            if (v1) {
                Iterator<String> keys = responseJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    tokens.put(key, responseJson.optString(key, ""));
                }
            } else {
                sessionId = responseJson.optString("sessionId", null);
                JSONObject files = responseJson.optJSONObject("files");
                if (files != null) {
                    Iterator<String> keys = files.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        tokens.put(key, files.optString(key, ""));
                    }
                }
            }

            if (tokens.isEmpty()) {
                if (!canceled) listener.onFinished();
                return;
            }

            List<FileItem> accepted = new ArrayList<FileItem>();
            for (FileItem item : items) {
                if (tokens.containsKey(item.id)) accepted.add(item);
            }

            int index = 0;
            for (FileItem item : accepted) {
                if (canceled) {
                    listener.onCanceled();
                    cancelSession(target, sessionId, version);
                    return;
                }
                index++;
                final String token = tokens.get(item.id);
                String query = "?fileId=" + Util.urlEncode(item.id) + "&token=" + Util.urlEncode(token);
                if (!v1 && sessionId != null) {
                    query = "?sessionId=" + Util.urlEncode(sessionId) + "&" + query.substring(1);
                }
                Map<String, String> headers = new HashMap<String, String>();
                headers.put("Content-Type", item.mimeType);
                Http.Response uploadResponse;
                try {
                    uploadResponse = Http.execute(target.ip, target.port, target.https,
                            "POST", Protocol.uploadPath(version) + query, headers,
                            new StreamBody(item, listener, index, accepted.size(), this),
                            10000, 600000);
                } catch (IOException e) {
                    if (canceled) {
                        listener.onCanceled();
                        cancelSession(target, sessionId, version);
                    } else {
                        listener.onError("上传失败: " + e.getMessage());
                    }
                    return;
                }
                if (uploadResponse.code != 200) {
                    listener.onError(describeError(uploadResponse));
                    return;
                }
            }
            if (canceled) {
                listener.onCanceled();
                return;
            }
            listener.onFinished();
        } catch (Exception e) {
            if (canceled) {
                listener.onCanceled();
            } else {
                listener.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    private void cancelSession(Device target, String sessionId, String version) {
        try {
            String path = Protocol.cancelPath(version);
            if (sessionId != null) path += "?sessionId=" + Util.urlEncode(sessionId);
            Http.execute(target.ip, target.port, target.https, "POST", path, null,
                    new ByteBody(new byte[0]), 5000, 5000);
        } catch (Exception ignored) {
        }
    }

    private JSONObject infoJson() throws Exception {
        JSONObject info = new JSONObject();
        info.put("alias", settings.alias());
        info.put("version", Protocol.VERSION);
        info.put("deviceModel", settings.deviceModel());
        info.put("deviceType", "mobile");
        info.put("fingerprint", identity.fingerprint);
        info.put("port", settings.port());
        info.put("protocol", settings.https() ? "https" : "http");
        info.put("download", false);
        return info;
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private static String describeError(Http.Response response) {
        String message = response.errorMessage();
        switch (response.code) {
            case 401:
                return "对方设置了 PIN，本客户端暂不支持 (" + message + ")";
            case 403:
                return "对方拒绝接收 (" + message + ")";
            case 409:
                return "对方正在接收其它文件 (" + message + ")";
            case 429:
                return "尝试次数过多 (" + message + ")";
            default:
                return "HTTP " + response.code + " " + message;
        }
    }

    private static final class ByteBody implements Http.Body {
        private final byte[] data;

        ByteBody(byte[] data) {
            this.data = data;
        }

        @Override
        public long length() {
            return data.length;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            out.write(data);
        }
    }

    private static final class StreamBody implements Http.Body {
        private final FileItem item;
        private final Listener listener;
        private final int index;
        private final int total;
        private final SendClient owner;

        StreamBody(FileItem item, Listener listener, int index, int total, SendClient owner) {
            this.item = item;
            this.listener = listener;
            this.index = index;
            this.total = total;
            this.owner = owner;
        }

        @Override
        public long length() {
            return item.size;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            InputStream in = item.open();
            long sent = 0;
            long last = 0;
            byte[] buffer = new byte[64 * 1024];
            try {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (owner.canceled) throw new IOException("canceled");
                    out.write(buffer, 0, n);
                    sent += n;
                    long now = System.currentTimeMillis();
                    if (now - last > 150 || sent >= item.size) {
                        last = now;
                        listener.onFileProgress(index, total, item.fileName, sent, item.size);
                    }
                }
            } finally {
                Util.closeQuietly(in);
            }
        }
    }
}

