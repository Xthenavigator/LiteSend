package io.legacy.localsend.core;

import org.json.JSONObject;

/** A discovered or manually configured LocalSend peer. */
public class Device {
    public String ip;
    public int port = Protocol.DEFAULT_PORT;
    public String version = "2.0";
    public boolean https = true;
    public String fingerprint = "";
    public String alias = "";
    public String deviceModel = "";
    public String deviceType = "mobile";
    public boolean download = false;

    public String displayName() {
        if (alias != null && alias.length() > 0) return alias;
        return ip;
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        sb.append(ip).append(':').append(port);
        sb.append("  ").append(https ? "HTTPS" : "HTTP");
        if (deviceModel != null && deviceModel.length() > 0) sb.append("  ").append(deviceModel);
        sb.append("  v").append(version);
        return sb.toString();
    }

    public static Device fromJson(JSONObject o, String ip, int fallbackPort, boolean fallbackHttps) {
        Device d = new Device();
        d.ip = ip;
        d.alias = o.optString("alias", ip);
        d.version = o.optString("version", Protocol.PEER_VERSION);
        if (d.version == null || d.version.length() == 0) d.version = Protocol.PEER_VERSION;
        d.deviceModel = o.optString("deviceModel", "");
        d.deviceType = o.optString("deviceType", "desktop");
        d.fingerprint = o.optString("fingerprint", "");
        d.download = o.optBoolean("download", false);
        d.port = o.has("port") && !o.isNull("port") ? o.optInt("port", fallbackPort) : fallbackPort;
        if (d.port <= 0 || d.port > 65535) d.port = fallbackPort;
        String protocol = o.optString("protocol", "");
        d.https = protocol.length() > 0 ? "https".equals(protocol) : fallbackHttps;
        return d;
    }

    public JSONObject toInfoJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("alias", alias == null ? "" : alias);
            o.put("version", version);
            o.put("deviceModel", deviceModel == null ? "" : deviceModel);
            o.put("deviceType", deviceType == null ? "mobile" : deviceType);
            o.put("fingerprint", fingerprint == null ? "" : fingerprint);
            o.put("download", download);
        } catch (Exception ignored) {
        }
        return o;
    }
}
