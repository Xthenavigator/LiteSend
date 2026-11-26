package io.legacy.localsend.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

public class Settings implements PeerConfig {
    private static final String FILE = "localsend-lite";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String alias() {
        String alias = prefs.getString("alias", null);
        if (alias == null || alias.trim().length() == 0) {
            return Build.MODEL == null || Build.MODEL.length() == 0 ? "Android" : Build.MODEL;
        }
        return alias;
    }

    public void setAlias(String alias) {
        prefs.edit().putString("alias", alias).commit();
    }

    public int port() {
        int port = prefs.getInt("port", Protocol.DEFAULT_PORT);
        if (port < 1 || port > 65535) port = Protocol.DEFAULT_PORT;
        return port;
    }

    public void setPort(int port) {
        prefs.edit().putInt("port", port).commit();
    }

    public boolean https() {
        return prefs.getBoolean("https", true);
    }

    public void setHttps(boolean https) {
        prefs.edit().putBoolean("https", https).commit();
    }

    /** Use the system document picker (SAF, API 19+) instead of the built-in browser. */
    public boolean systemFilePicker() {
        return prefs.getBoolean("systemFilePicker", false);
    }

    public void setSystemFilePicker(boolean enabled) {
        prefs.edit().putBoolean("systemFilePicker", enabled).commit();
    }

    /** Light text for dark platform themes; off switches to dark text. */
    public boolean lightText() {
        return prefs.getBoolean("lightText", true);
    }

    public void setLightText(boolean enabled) {
        prefs.edit().putBoolean("lightText", enabled).commit();
    }

    public String deviceModel() {
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (manufacturer.length() > 0 && !model.toLowerCase(java.util.Locale.US).startsWith(manufacturer.toLowerCase(java.util.Locale.US))) {
            return manufacturer + " " + model;
        }
        return model;
    }
}
