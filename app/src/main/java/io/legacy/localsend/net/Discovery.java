package io.legacy.localsend.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import org.json.JSONObject;

import io.legacy.localsend.core.Device;
import io.legacy.localsend.core.Identity;
import io.legacy.localsend.core.Protocol;
import io.legacy.localsend.core.PeerConfig;

/** UDP multicast discovery for LocalSend protocol v2 (and v1 announcement field). */
public class Discovery {
    public interface Listener {
        void onDevice(Device device);

        /** Peer asked for an answer; the receiver should register itself over TCP or via UDP. */
        void onAnnounce(Device device);
    }

    private final PeerConfig settings;
    private final Identity identity;
    private final Listener listener;

    private MulticastSocket socket;
    private InetAddress group;
    private Thread receiveThread;
    private volatile boolean running;

    public Discovery(PeerConfig settings, Identity identity, Listener listener) {
        this.settings = settings;
        this.identity = identity;
        this.listener = listener;
    }

    public void start() throws IOException {
        group = InetAddress.getByName(Protocol.MULTICAST_GROUP);
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(Protocol.DEFAULT_PORT));
        socket.setTimeToLive(2);

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try {
                    if (!ni.isUp() || !ni.supportsMulticast()) continue;
                    socket.joinGroup(new java.net.InetSocketAddress(group, Protocol.DEFAULT_PORT), ni);
                } catch (Exception ignored) {
                }
            }
        } catch (SocketException ignored) {
        }

        running = true;
        receiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "localsend-discovery");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    public void stop() {
        running = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Sends three announcements like the official implementation (100ms / 500ms / 2000ms). */
    public void announceAsync() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                long[] delays = new long[]{100, 500, 2000};
                for (long delay : delays) {
                    UtilSleep(delay);
                    if (!running) return;
                    send(announceJson(true));
                }
            }
        }, "localsend-announce");
        thread.setDaemon(true);
        thread.start();
    }

    public void sendAnnouncementResponse(boolean announce) {
        if (!running) return;
        send(announceJson(announce));
    }

    private byte[] announceJson(boolean announce) {
        JSONObject o = new JSONObject();
        try {
            o.put("alias", settings.alias());
            o.put("version", Protocol.VERSION);
            o.put("deviceModel", settings.deviceModel());
            o.put("deviceType", "mobile");
            o.put("fingerprint", identity.fingerprint);
            o.put("port", settings.port());
            o.put("protocol", settings.https() ? "https" : "http");
            o.put("download", false);
            o.put("announce", announce);
            o.put("announcement", announce);
        } catch (Exception ignored) {
        }
        try {
            return o.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private void send(byte[] data) {
        if (data.length == 0) return;
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, group, Protocol.DEFAULT_PORT);
            socket.send(packet);
        } catch (Exception ignored) {
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[8192];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), "UTF-8");
                JSONObject o = new JSONObject(json);
                String fingerprint = o.optString("fingerprint", "");
                if (fingerprint.equals(identity.fingerprint)) continue;
                String ip = packet.getAddress().getHostAddress();
                Device device = Device.fromJson(o, ip, settings.port(), settings.https());
                if (device.alias == null || device.alias.length() == 0) continue;
                listener.onDevice(device);
                boolean announce = o.optBoolean("announce", false) || o.optBoolean("announcement", false);
                if (announce) {
                    listener.onAnnounce(device);
                }
            } catch (Exception ignored) {
                if (!running) return;
            }
        }
    }

    private static void UtilSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

