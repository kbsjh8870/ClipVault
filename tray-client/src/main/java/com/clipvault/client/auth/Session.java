package com.clipvault.client.auth;

import java.util.prefs.Preferences;

/** Server URL + device tokens, persisted in java.util.prefs. */
public class Session {
    private static final Preferences PREFS = Preferences.userRoot().node("com/clipvault/client");

    public volatile String accessToken = PREFS.get("accessToken", null);
    public volatile String refreshToken = PREFS.get("refreshToken", null);
    public volatile String userId = PREFS.get("userId", null);
    public volatile String deviceId = PREFS.get("deviceId", null);
    public volatile String server = defaultServer();

    private static String defaultServer() {
        String s = System.getProperty("clipvault.server");
        if (s == null) s = System.getenv("CLIPVAULT_SERVER");
        if (s == null) s = PREFS.get("server", "http://localhost:8080");
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public boolean hasDevice() {
        return refreshToken != null && deviceId != null && userId != null;
    }

    public synchronized void save() {
        put("accessToken", accessToken);
        put("refreshToken", refreshToken);
        put("userId", userId);
        put("deviceId", deviceId);
        put("server", server);
    }

    public synchronized void clear() {
        accessToken = refreshToken = userId = deviceId = null;
        save();
    }

    private static void put(String key, String value) {
        if (value == null) PREFS.remove(key); else PREFS.put(key, value);
    }
}
