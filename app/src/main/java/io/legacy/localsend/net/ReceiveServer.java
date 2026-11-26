package io.legacy.localsend.net;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

import io.legacy.localsend.core.Device;
import io.legacy.localsend.core.Identity;
import io.legacy.localsend.core.Protocol;
import io.legacy.localsend.core.PeerConfig;
import io.legacy.localsend.core.Util;

/** Implements the receiving half of the LocalSend protocol (server side). */
public class ReceiveServer implements HttpServerLite.Handler {
    public interface Listener {
        void onPeerRegistered(Device device);

        void onSessionStarted(int fileCount, String senderAlias);

        void onFileProgress(String fileName, long received, long total);

        void onFileFinished(File file);

        void onSessionFinished(boolean success, String message);

        void onTextMessage(String senderAlias, String message);
    }

    private static final class Incoming {
        String id;
        String fileName;
        String fileTypeRaw;
        long size;
        String token;
        boolean done;
        File destination;
    }

    private static final class Session {
        String sessionId;
        Device sender;
        final Map<String, Incoming> files = new LinkedHashMap<String, Incoming>();
        int remaining;
    }

    private final PeerConfig settings;
    private final Identity identity;
    private final Listener listener;
    private final Object lock = new Object();
    private Session session;

    public ReceiveServer(PeerConfig settings, Identity identity, Listener listener) {
        this.settings = settings;
        this.identity = identity;
        this.listener = listener;
    }

    public boolean isBusy() {
        synchronized (lock) {
            return session != null;
        }
    }

    @Override
    public void handle(HttpServerLite.Request request, HttpServerLite.Response response) throws Exception {
        String path = request.path;
        if (Protocol.INFO_V1.equals(path) || Protocol.INFO_V2.equals(path)) {
            if ("POST".equals(request.method)) {
                handleRegister(request, response);
            } else {
                handleInfo(request, response);
            }
            return;
        }
        if (Protocol.REGISTER_V1.equals(path) || Protocol.REGISTER_V2.equals(path)) {
            handleRegister(request, response);
            return;
        }
        if (Protocol.PREPARE_UPLOAD_V1.equals(path) || Protocol.PREPARE_UPLOAD_V2.equals(path)) {
            handlePrepareUpload(request, response, path.endsWith("/v2/prepare-upload"));
            return;
        }
        if (Protocol.UPLOAD_V1.equals(path) || Protocol.UPLOAD_V2.equals(path)) {
            handleUpload(request, response, Protocol.UPLOAD_V2.equals(path));
            return;
        }
        if (Protocol.CANCEL_V1.equals(path) || Protocol.CANCEL_V2.equals(path)) {
            handleCancel(request, response);
            return;
        }
        if (Protocol.SHOW_V1.equals(path) || Protocol.SHOW_V2.equals(path)) {
            response.sendError(403, "Invalid token");
            return;
        }
        response.sendError(404, "Not found");
    }

    private void handleInfo(HttpServerLite.Request request, HttpServerLite.Response response) throws Exception {
        String senderFingerprint = request.param("fingerprint");
        if (identity.fingerprint.equals(senderFingerprint)) {
            response.sendError(412, "Self-discovered");
            return;
        }
        response.sendJson(200, infoJson().toString());
    }

    private void handleRegister(HttpServerLite.Request request, HttpServerLite.Response response) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(new String(request.bodyBytes(256 * 1024), Util.UTF8));
        } catch (Exception e) {
            response.sendError(400, "Request body malformed");
            return;
        }
        if (identity.fingerprint.equals(body.optString("fingerprint", ""))) {
            response.sendError(412, "Self-discovered");
            return;
        }
        Device device = Device.fromJson(body, request.remoteIp, settings.port(), settings.https());
        listener.onPeerRegistered(device);
        response.sendJson(200, infoJson().toString());
    }

    private void handlePrepareUpload(HttpServerLite.Request request, HttpServerLite.Response response, boolean v2) throws Exception {
        Session current;
        synchronized (lock) {
            current = session;
        }
        if (current != null) {
            response.sendError(409, "Blocked by another session");
            return;
        }

        JSONObject root;
        try {
            root = new JSONObject(new String(request.bodyBytes(1024 * 1024), Util.UTF8));
        } catch (Exception e) {
            response.sendError(400, "Request body malformed");
            return;
        }
        JSONObject infoJson = root.optJSONObject("info");
        JSONObject filesJson = root.optJSONObject("files");
        if (filesJson == null || filesJson.length() == 0) {
            response.sendError(400, "Request must contain at least one file");
            return;
        }

        Device sender = infoJson == null
                ? Device.fromJson(new JSONObject(), request.remoteIp, settings.port(), settings.https())
                : Device.fromJson(infoJson, request.remoteIp, settings.port(), settings.https());
        String senderAlias = sender.alias;

        // Text messages are transferred as a single text file whose "preview" holds the text.
        if (filesJson.length() == 1) {
            JSONObject only = filesJson.optJSONObject(filesJson.keys().next());
            if (only != null) {
                String preview = only.optString("preview", "");
                String fileType = only.optString("fileType", "");
                if (preview.length() > 0 && Protocol.isTextType(fileType)) {
                    listener.onTextMessage(senderAlias, preview);
                    response.sendEmpty(204);
                    return;
                }
            }
        }

        File dir = Protocol.defaultDownloadDir();
        if (!dir.exists()) dir.mkdirs();

        Session newSession = new Session();
        newSession.sessionId = Util.uuid();
        newSession.sender = sender;
        try {
            java.util.Iterator<String> keys = filesJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject fileJson = filesJson.optJSONObject(key);
                if (fileJson == null) continue;
                Incoming incoming = new Incoming();
                incoming.id = fileJson.optString("id", key);
                incoming.fileName = Util.sanitizeFileName(fileJson.optString("fileName", "file"));
                incoming.size = fileJson.optLong("size", 0);
                incoming.fileTypeRaw = fileJson.optString("fileType", "other");
                incoming.token = Util.uuid();
                newSession.files.put(incoming.id, incoming);
            }
        } catch (Exception e) {
            response.sendError(400, "Request body malformed");
            return;
        }
        if (newSession.files.isEmpty()) {
            response.sendError(400, "Request must contain at least one file");
            return;
        }
        newSession.remaining = newSession.files.size();

        synchronized (lock) {
            if (session != null) {
                response.sendError(409, "Blocked by another session");
                return;
            }
            session = newSession;
        }

        listener.onSessionStarted(newSession.files.size(), senderAlias);

        JSONObject tokens = new JSONObject();
        for (Incoming file : newSession.files.values()) {
            tokens.put(file.id, file.token);
        }
        if (v2) {
            JSONObject result = new JSONObject();
            result.put("sessionId", newSession.sessionId);
            result.put("files", tokens);
            response.sendJson(200, result.toString());
        } else {
            response.sendJson(200, tokens.toString());
        }
    }

    private void handleUpload(HttpServerLite.Request request, HttpServerLite.Response response, boolean v2) throws Exception {
        Session current;
        synchronized (lock) {
            current = session;
        }
        if (current == null) {
            response.sendError(409, "No session");
            return;
        }
        if (!current.sender.ip.equals(request.remoteIp) && !"127.0.0.1".equals(request.remoteIp)) {
            response.sendError(403, "Invalid IP address: " + request.remoteIp);
            return;
        }

        String fileId = request.param("fileId");
        String token = request.param("token");
        String sessionId = request.param("sessionId");
        if (fileId == null || token == null || (v2 && sessionId == null)) {
            response.sendError(400, "Missing parameters");
            return;
        }
        if (v2 && !current.sessionId.equals(sessionId)) {
            response.sendError(403, "Invalid session id");
            return;
        }

        Incoming incoming;
        synchronized (lock) {
            incoming = current.files.get(fileId);
            if (incoming == null || incoming.token == null || !incoming.token.equals(token) || incoming.done) {
                response.sendError(403, "Invalid token");
                return;
            }
            incoming.done = true;
            incoming.token = null;
        }

        File dir = Protocol.defaultDownloadDir();
        if (!dir.exists()) dir.mkdirs();
        File destination = Util.uniqueFile(dir, incoming.fileName);
        boolean success = false;
        FileOutputStream fileOut = null;
        try {
            fileOut = new FileOutputStream(destination);
            final String displayName = incoming.fileName;
            final long expected = request.contentLength > 0 ? request.contentLength : incoming.size;
            long received = Util.copy(request.body, fileOut, expected, new Util.Progress() {
                private long lastReport;

                @Override
                public void onProgress(long bytes) {
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 300 || bytes == expected) {
                        lastReport = now;
                        listener.onFileProgress(displayName, bytes, expected);
                    }
                }
            });
            fileOut.flush();
            fileOut.close();
            fileOut = null;
            if (received != expected && expected > 0) {
                throw new IOException("incomplete transfer: " + received + "/" + expected);
            }
            destination.setLastModified(System.currentTimeMillis());
            success = true;
            listener.onFileFinished(destination);

            synchronized (lock) {
                current.remaining--;
                if (current.remaining <= 0) {
                    session = null;
                    listener.onSessionFinished(true, destination.getParent());
                }
            }
        } catch (Exception e) {
            Util.closeQuietly(fileOut);
            if (destination.exists()) destination.delete();
            synchronized (lock) {
                current.remaining--;
                if (current.remaining <= 0) session = null;
            }
            listener.onSessionFinished(false, e.getClass().getSimpleName() + ": " + e.getMessage());
            response.sendError(500, "Could not save file");
            return;
        }

        if (success) {
            response.sendJson(200, "{}");
        } else {
            response.sendError(500, "Could not save file");
        }
    }

    private void handleCancel(HttpServerLite.Request request, HttpServerLite.Response response) throws Exception {
        synchronized (lock) {
            if (session != null && request.param("sessionId") != null
                    && !session.sessionId.equals(request.param("sessionId"))) {
                response.sendError(403, "No permission");
                return;
            }
            session = null;
        }
        listener.onSessionFinished(false, "已取消");
        response.sendJson(200, "{}");
    }

    private JSONObject infoJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("alias", settings.alias());
            o.put("version", Protocol.VERSION);
            o.put("deviceModel", settings.deviceModel());
            o.put("deviceType", "mobile");
            o.put("fingerprint", identity.fingerprint);
            o.put("download", false);
        } catch (Exception ignored) {
        }
        return o;
    }
}

