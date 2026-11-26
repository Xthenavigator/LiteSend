import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.legacy.localsend.core.Device;
import io.legacy.localsend.core.FileItem;
import io.legacy.localsend.core.Identity;
import io.legacy.localsend.core.Paths;
import io.legacy.localsend.core.PeerConfig;
import io.legacy.localsend.net.Discovery;
import io.legacy.localsend.net.HttpServerLite;
import io.legacy.localsend.net.PeerResolver;
import io.legacy.localsend.net.ReceiveServer;
import io.legacy.localsend.net.SendClient;

/**
 * Desktop harness that runs the *shipping* protocol code (same classes as the APK)
 * so it can be tested against the official LocalSend or a reference peer.
 *
 *   receive <port> <identityDir> <downloadDir> <alias> [https]
 *   send    <ip> <port> <file>
 *   text    <ip> <port> <message>
 *   probe   <ip> <port>
 */
public class Harness {
    private static final class Config implements PeerConfig {
        private final String alias;
        private final int port;
        private final boolean https;

        Config(String alias, int port, boolean https) {
            this.alias = alias;
            this.port = port;
            this.https = https;
        }

        @Override
        public String alias() {
            return alias;
        }

        @Override
        public String deviceModel() {
            return "jvm-harness";
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public boolean https() {
            return https;
        }
    }

    public static void main(String[] args) throws Exception {
        if (System.getenv("LS_CBC") != null) io.legacy.localsend.net.Tls.CBC_ONLY = true;
        String tlsMode = System.getenv("LS_TLS");
        if ("ecdhe".equals(tlsMode)) {
            io.legacy.localsend.net.Tls.RSA_KEY_EXCHANGE_ONLY = false;
        } else if ("rsa".equals(tlsMode)) {
            io.legacy.localsend.net.Tls.RSA_KEY_EXCHANGE_ONLY = true;
        }
        String mode = args[0];
        if ("receive".equals(mode)) {
            receive(args);
        } else if ("send".equals(mode)) {
            send(args);
        } else if ("sendmulti".equals(mode)) {
            sendMulti(args);
        } else if ("text".equals(mode)) {
            text(args);
        } else if ("probe".equals(mode)) {
            probe(args);
        } else {
            System.out.println("unknown mode " + mode);
            System.exit(2);
        }
    }

    private static void receive(String[] args) throws Exception {
        final int port = Integer.parseInt(args[1]);
        File identityDir = new File(args[2]);
        File downloadDir = new File(args[3]);
        String alias = args[4];
        boolean https = args.length < 6 || Boolean.parseBoolean(args[5]);

        Paths.setDownloadDir(downloadDir);
        final Identity identity = Identity.loadOrCreate(identityDir);
        System.out.println("FINGERPRINT=" + identity.fingerprint);

        final Config config = new Config(alias, port, https);
        ReceiveServer receiveServer = new ReceiveServer(config, identity, new ReceiveServer.Listener() {
            @Override
            public void onPeerRegistered(Device device) {
                System.out.println("EVENT registered " + device.alias + " " + device.ip);
            }

            @Override
            public void onSessionStarted(int fileCount, String senderAlias) {
                System.out.println("EVENT session " + fileCount + " file(s) from " + senderAlias);
            }

            @Override
            public void onFileProgress(String fileName, long received, long total) {
                // quiet
            }

            @Override
            public void onFileFinished(File file) {
                System.out.println("EVENT saved " + file.getAbsolutePath() + " (" + file.length() + " bytes)");
            }

            @Override
            public void onSessionFinished(boolean success, String message) {
                System.out.println("EVENT sessionFinished success=" + success + " " + message);
            }

            @Override
            public void onTextMessage(String senderAlias, String message) {
                System.out.println("EVENT message from " + senderAlias + ": " + message);
            }
        });

        HttpServerLite server = new HttpServerLite(identity, https, receiveServer);
        server.start(port);
        System.out.println("LISTENING " + port + " https=" + https);

        // Announce ourselves so the official LocalSend can also pick us up via multicast.
        try {
            final Discovery discovery = new Discovery(config, identity, new Discovery.Listener() {
                @Override
                public void onDevice(Device device) {
                    System.out.println("EVENT discovered " + device.alias + " " + device.ip + ":" + device.port);
                }

                @Override
                public void onAnnounce(Device device) {
                    System.out.println("EVENT announce " + device.alias + " " + device.ip);
                }
            });
            discovery.start();
            discovery.announceAsync();
            System.out.println("MULTICAST started");
        } catch (Exception e) {
            System.out.println("MULTICAST unavailable: " + e);
        }

        Thread.sleep(Long.MAX_VALUE);
    }

    private static void send(String[] args) throws Exception {
        String ip = args[1];
        int port = Integer.parseInt(args[2]);
        File file = new File(args[3]);
        Identity identity = Identity.loadOrCreate(new File("harness-identity"));
        Device target = resolve(ip, port, identity);
        if (target == null) return;

        List<FileItem> items = new ArrayList<FileItem>();
        items.add(FileItem.fromFile(file));
        SendClient client = new SendClient(new Config("jvm-harness", port, target.https), identity);
        client.send(target, items, new PrintListener());
    }

    private static void sendMulti(String[] args) throws Exception {
        String ip = args[1];
        int port = Integer.parseInt(args[2]);
        Identity identity = Identity.loadOrCreate(new File("harness-identity"));
        Device target = resolve(ip, port, identity);
        if (target == null) return;

        List<FileItem> items = new ArrayList<FileItem>();
        for (int i = 3; i < args.length; i++) {
            items.add(FileItem.fromFile(new File(args[i])));
        }
        SendClient client = new SendClient(new Config("jvm-harness", port, target.https), identity);
        client.send(target, items, new PrintListener());
    }

    private static void text(String[] args) throws Exception {
        String ip = args[1];
        int port = Integer.parseInt(args[2]);
        String message = args[3];
        Identity identity = Identity.loadOrCreate(new File("harness-identity"));
        Device target = resolve(ip, port, identity);
        if (target == null) return;

        List<FileItem> items = new ArrayList<FileItem>();
        items.add(FileItem.fromText(message));
        SendClient client = new SendClient(new Config("jvm-harness", port, target.https), identity);
        client.send(target, items, new PrintListener());
    }

    private static void probe(String[] args) throws Exception {
        Identity identity = Identity.loadOrCreate(new File("harness-identity"));
        resolve(args[1], Integer.parseInt(args[2]), identity);
    }

    private static Device resolve(String ip, int port, Identity identity) {
        PeerResolver.Result result = PeerResolver.resolve(ip, port, identity);
        if (result.device == null) {
            System.out.println("RESOLVE FAILED: " + result.error);
            return null;
        }
        Device d = result.device;
        System.out.println("RESOLVED alias=" + d.alias + " version=" + d.version + " https=" + d.https
                + " model=" + d.deviceModel + " fingerprint=" + d.fingerprint);
        return d;
    }

    private static final class PrintListener implements SendClient.Listener {
        private long lastPrint;

        @Override
        public void onPrepared(Device target) {
            System.out.println("EVENT prepared target=" + target.displayName());
        }

        @Override
        public void onFileProgress(int index, int total, String fileName, long sent, long size) {
            long now = System.currentTimeMillis();
            if (now - lastPrint > 500 || sent >= size) {
                lastPrint = now;
                System.out.println("PROGRESS " + index + "/" + total + " " + fileName + " " + sent + "/" + size);
            }
        }

        @Override
        public void onFinished() {
            System.out.println("EVENT finished");
            System.exit(0);
        }

        @Override
        public void onError(String message) {
            System.out.println("EVENT error: " + message);
            System.exit(1);
        }

        @Override
        public void onCanceled() {
            System.out.println("EVENT canceled");
            System.exit(2);
        }
    }
}


