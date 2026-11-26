package io.legacy.localsend.core;

/** The device description announced to peers (implemented by Settings on Android). */
public interface PeerConfig {
    String alias();

    String deviceModel();

    int port();

    boolean https();
}
