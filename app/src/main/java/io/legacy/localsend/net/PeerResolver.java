package io.legacy.localsend.net;

import org.json.JSONObject;

import io.legacy.localsend.core.Device;
import io.legacy.localsend.core.Identity;
import io.legacy.localsend.core.Protocol;
import io.legacy.localsend.core.Util;

/** Resolves a device from a plain IP address (works without multicast). */
public final class PeerResolver {
    public static final class Result {
        public final Device device;
        public final String error;

        Result(Device device, String error) {
            this.device = device;
            this.error = error;
        }
    }

    private PeerResolver() {
    }

    public static Result resolve(String ip, int port, Identity identity) {
        String path = Protocol.INFO_V1 + "?fingerprint=" + Util.urlEncode(identity.fingerprint);
        String lastError = null;

        // Official LocalSend probes the legacy route over HTTPS first, then falls back to HTTP.
        boolean[] schemes = new boolean[]{true, false};
        for (boolean https : schemes) {
            try {
                Http.Response response = Http.execute(ip, port, https, "GET", path, null, null, 5000, 8000);
                if (response.code == 412) {
                    return new Result(null, "这是本机自己");
                }
                if (response.code == 200) {
                    JSONObject json = response.json();
                    if (json != null) {
                        Device device = Device.fromJson(json, ip, port, https);
                        device.port = port;
                        return new Result(device, null);
                    }
                }
                lastError = "HTTP " + response.code;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        return new Result(null, lastError == null ? "无法连接" : lastError);
    }
}
