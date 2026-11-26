package io.legacy.localsend;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.json.JSONObject;

import io.legacy.localsend.core.Device;
import io.legacy.localsend.core.FileItem;
import io.legacy.localsend.core.Identity;
import io.legacy.localsend.core.Paths;
import io.legacy.localsend.core.Protocol;
import io.legacy.localsend.core.Settings;
import io.legacy.localsend.core.Util;
import io.legacy.localsend.net.Discovery;
import io.legacy.localsend.net.Http;
import io.legacy.localsend.net.HttpServerLite;
import io.legacy.localsend.net.PeerResolver;
import io.legacy.localsend.net.ReceiveServer;
import io.legacy.localsend.net.SendClient;
import io.legacy.localsend.ui.MainActivity;

/** Application-wide state: identity, HTTP server, discovery, devices, transfers. */
public final class Core implements Discovery.Listener, ReceiveServer.Listener {
    public interface Listener {
        void onDevicesChanged();

        void onReady();

        void onReceiveStarted(int fileCount, String senderAlias);

        void onReceiveProgress(String fileName, long received, long total);

        void onReceiveFinished(boolean success, String message);

        void onTextMessage(String senderAlias, String message);

        void onSendProgress(int index, int total, String fileName, long sent, long size);

        void onSendFinished(boolean success, String message);
    }

    public interface SendCallback {
        void onProgress(int index, int total, String fileName, long sent, long size);
    }

    private static final int NOTIFICATION_FOREGROUND = 1;
    private static final int NOTIFICATION_RECEIVE = 20;
    private static final int NOTIFICATION_SEND = 21;
    private static final int NOTIFICATION_MESSAGE = 22;

    private static volatile Core instance;

    public static Core get() {
        return instance;
    }

    public static Core getOrCreate(Context context) {
        Core local = instance;
        if (local == null) {
            synchronized (Core.class) {
                if (instance == null) instance = new Core(context.getApplicationContext());
                local = instance;
            }
        }
        return local;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Map<String, Device> devices = new LinkedHashMap<String, Device>();
    private final List<String> recentMessages = new CopyOnWriteArrayList<String>();
    private final List<ActivityEntry> activities = new CopyOnWriteArrayList<ActivityEntry>();
    private final List<String> logLines = new ArrayList<String>();

    /** One entry of the receive/send history shown on the "接收" tab. */
    public static final class ActivityEntry {
        public final long time;
        public final boolean incoming;
        public final String title;
        public final String detail;

        public ActivityEntry(long time, boolean incoming, String title, String detail) {
            this.time = time;
            this.incoming = incoming;
            this.title = title;
            this.detail = detail;
        }
    }

    private volatile boolean receiving;
    private volatile String receiveFileName;
    private volatile long receiveBytes;
    private volatile long receiveTotal;
    private volatile int receiveFileCount;
    private volatile String currentSendTarget;

    public final Settings settings;
    private Identity identity;
    private ReceiveServer receiveServer;
    private HttpServerLite httpServer;
    private Discovery discovery;
    private SendClient activeSendClient;

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock transferWakeLock;

    private volatile boolean ready;
    private volatile String error;
    private volatile String serverStatus = "初始化中…";
    private long lastProgressNotify;

    private Core(Context context) {
        this.context = context;
        this.settings = new Settings(context);
    }

    // ------------------------------------------------------------------ lifecycle

    public void initAsync() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverStatus = "生成证书…";
                    identity = Identity.loadOrCreate(new File(context.getFilesDir(), "identity"));
                    Paths.setDownloadDir(new File(new File(android.os.Environment.getExternalStorageDirectory(), "Download"), "LiteSend"));
                    serverStatus = "启动服务…";
                    startNetwork();
                    serverStatus = "运行中";
                } catch (Throwable t) {
                    error = "启动失败: " + t.getClass().getSimpleName() + " " + t.getMessage();
                    addLog(error);
                    serverStatus = error;
                }
                ready = true;
                notifyReady();
                updateForegroundNotification();
            }
        }, "localsend-core-init");
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void startNetwork() throws Exception {
        receiveServer = new ReceiveServer(settings, identity, this);
        httpServer = new HttpServerLite(identity, settings.https(), receiveServer);
        httpServer.start(settings.port());

        acquireLocks();

        addLog("服务已启动: 端口 " + settings.port() + (settings.https() ? " HTTPS" : " HTTP"));
        discovery = new Discovery(settings, identity, this);
        try {
            discovery.start();
            discovery.announceAsync();
        } catch (Exception e) {
            // Multicast may be unavailable (no Wi-Fi); manual IP still works.
            error = "组播不可用: " + e.getMessage();
        }
    }

    public synchronized void restartNetwork() {
        stopNetwork();
        ready = false;
        error = null;
        initAsync();
    }

    private synchronized void stopNetwork() {
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        releaseLocks();
    }

    private void acquireLocks() {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("localsend-lite-multicast");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "localsend-lite-wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                transferWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localsend-lite-transfer");
                transferWakeLock.setReferenceCounted(false);
                transferWakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (transferWakeLock != null && transferWakeLock.isHeld()) transferWakeLock.release();
        } catch (Exception ignored) {
        }
        multicastLock = null;
        wifiLock = null;
        transferWakeLock = null;
    }

    // ------------------------------------------------------------------ listeners

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyReady() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) listener.onReady();
            }
        });
    }

    private void notifyDevicesChanged() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) listener.onDevicesChanged();
            }
        });
    }

    // ------------------------------------------------------------------ status

    public boolean isReady() {
        return ready;
    }

    public String getError() {
        return error;
    }

    public String getStatusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("别名: ").append(settings.alias());
        sb.append("\n本机 IP: ").append(Util.localIpv4());
        sb.append("\n端口: ").append(settings.port()).append(settings.https() ? "  HTTPS" : "  HTTP");
        sb.append("\n状态: ").append(serverStatus);
        if (error != null) sb.append("\n注意: ").append(error);
        if (identity != null && identity.fingerprint.length() >= 16) {
            sb.append("\n指纹: ").append(identity.fingerprint.substring(0, 16)).append("…");
        }
        return sb.toString();
    }

    public String getFingerprint() {
        return identity == null ? "" : identity.fingerprint;
    }

    // ------------------------------------------------------------------ history / logs

    public void addLog(String line) {
        synchronized (logLines) {
            logLines.add(0, timestamp() + "  " + line);
            while (logLines.size() > 200) logLines.remove(logLines.size() - 1);
        }
    }

    public void clearLogs() {
        synchronized (logLines) {
            logLines.clear();
        }
    }

    public List<String> getLogs() {
        synchronized (logLines) {
            return new ArrayList<String>(logLines);
        }
    }

    public void addActivity(boolean incoming, String title, String detail) {
        activities.add(0, new ActivityEntry(System.currentTimeMillis(), incoming, title, detail));
        while (activities.size() > 100) activities.remove(activities.size() - 1);
    }

    public List<ActivityEntry> getActivities() {
        return new ArrayList<ActivityEntry>(activities);
    }

    public boolean isReceiving() {
        return receiving;
    }

    public String getReceiveFileName() {
        return receiveFileName;
    }

    public long getReceiveBytes() {
        return receiveBytes;
    }

    public long getReceiveTotal() {
        return receiveTotal;
    }

    public int getReceiveFileCount() {
        return receiveFileCount;
    }

    public String getCurrentSendTarget() {
        return currentSendTarget;
    }

    private static String timestamp() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        return format.format(new java.util.Date());
    }

    // ------------------------------------------------------------------ devices

    public List<Device> getDevices() {
        synchronized (devices) {
            List<Device> list = new ArrayList<Device>(devices.values());
            Collections.sort(list, new Comparator<Device>() {
                @Override
                public int compare(Device a, Device b) {
                    return a.displayName().compareToIgnoreCase(b.displayName());
                }
            });
            return list;
        }
    }

    private void upsertDevice(Device device) {
        if (device == null || device.ip == null) return;
        boolean changed = false;
        synchronized (devices) {
            Device existing = devices.get(device.ip);
            if (existing == null) {
                addLog("发现设备: " + device.displayName() + " (" + device.ip + ")");
            }
            if (existing == null || !existing.displayName().equals(device.displayName())
                    || existing.port != device.port || existing.https != device.https
                    || !existing.version.equals(device.version)) {
                changed = true;
            }
            devices.put(device.ip, device);
        }
        if (changed) notifyDevicesChanged();
    }

    public void refresh() {
        Discovery local = discovery;
        if (local != null) local.announceAsync();
    }

    public void addManualDeviceAsync(final String input, final ManualResult callback) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                String host = input.trim();
                int port = Protocol.DEFAULT_PORT;
                int colon = host.indexOf(':');
                if (colon > 0) {
                    try {
                        port = Integer.parseInt(host.substring(colon + 1).trim());
                        host = host.substring(0, colon).trim();
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (identity == null) {
                    callback.onResult(null, "初始化未完成");
                    return;
                }
                PeerResolver.Result result = PeerResolver.resolve(host, port, identity);
                if (result.device != null) {
                    upsertDevice(result.device);
                    callback.onResult(result.device, null);
                } else {
                    callback.onResult(null, result.error);
                }
            }
        }, "localsend-manual-device");
        thread.setDaemon(true);
        thread.start();
    }

    public interface ManualResult {
        void onResult(Device device, String error);
    }

    // ------------------------------------------------------------------ discovery callbacks

    @Override
    public void onDevice(Device device) {
        upsertDevice(device);
    }

    @Override
    public void onAnnounce(final Device device) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = registerTo(device);
                if (!ok && discovery != null) discovery.sendAnnouncementResponse(false);
            }
        }, "localsend-answer-announce");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean registerTo(Device device) {
        if (identity == null) return false;
        try {
            JSONObject json = new JSONObject();
            json.put("alias", settings.alias());
            json.put("version", Protocol.VERSION);
            json.put("deviceModel", settings.deviceModel());
            json.put("deviceType", "mobile");
            json.put("fingerprint", identity.fingerprint);
            json.put("port", settings.port());
            json.put("protocol", settings.https() ? "https" : "http");
            json.put("download", false);
            byte[] body = json.toString().getBytes(Util.UTF8);
            java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
            headers.put("Content-Type", "application/json");
            Http.Response response = Http.execute(device.ip, device.port, device.https, "POST",
                    Protocol.registerPath(device.version), headers,
                    new Http.Body() {
                        private final byte[] data = body;

                        @Override
                        public long length() {
                            return data.length;
                        }

                        @Override
                        public void writeTo(java.io.OutputStream out) throws java.io.IOException {
                            out.write(data);
                        }
                    }, 3000, 5000);
            if (response.code == 200) {
                org.json.JSONObject info = response.json();
                if (info != null) {
                    Device answer = Device.fromJson(info, device.ip, device.port, device.https);
                    answer.port = device.port;
                    if (answer.fingerprint == null || answer.fingerprint.length() == 0) {
                        answer.fingerprint = device.fingerprint;
                    }
                    upsertDevice(answer);
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ receiving

    @Override
    public void onPeerRegistered(Device device) {
        upsertDevice(device);
    }

    @Override
    public void onSessionStarted(final int fileCount, final String senderAlias) {
        receiving = true;
        receiveFileCount = fileCount;
        receiveFileName = null;
        receiveBytes = 0;
        receiveTotal = 0;
        addLog("开始接收 " + fileCount + " 个文件，来自 " + senderAlias);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) listener.onReceiveStarted(fileCount, senderAlias);
            }
        });
        showReceivingNotification(senderAlias, fileCount, null, 0, 0);
    }

    @Override
    public void onFileProgress(final String fileName, final long received, final long total) {
        receiveFileName = fileName;
        receiveBytes = received;
        receiveTotal = total;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) listener.onReceiveProgress(fileName, received, total);
            }
        });
        long now = System.currentTimeMillis();
        if (now - lastProgressNotify > 700) {
            lastProgressNotify = now;
            int progress = total > 0 ? (int) (received * 100 / total) : 0;
            showReceivingNotification(null, 0, fileName, progress, received);
        }
    }

    @Override
    public void onFileFinished(File file) {
        addLog("已保存 " + file.getName() + " (" + Util.formatSize(file.length()) + ")");
        addActivity(true, file.getName(), "已保存 · " + Util.formatSize(file.length()));
    }

    @Override
    public void onSessionFinished(final boolean success, final String message) {
        receiving = false;
        receiveFileName = null;
        addLog(success ? "接收完成" : "接收停止: " + message);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) listener.onReceiveFinished(success, message);
            }
        });
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (success) {
            Notification.Builder builder = baseNotification("接收完成", "已保存到 " + message);
            manager.notify(NOTIFICATION_RECEIVE, builder.build());
        } else {
            Notification.Builder builder = baseNotification("接收已停止", message);
            manager.notify(NOTIFICATION_RECEIVE, builder.build());
        }
    }

    @Override
    public void onTextMessage(final String senderAlias, final String message) {
        recentMessages.add(0, senderAlias + ": " + message);
        while (recentMessages.size() > 30) recentMessages.remove(recentMessages.size() - 1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) listener.onTextMessage(senderAlias, message);
            }
        });
        Notification.Builder builder = baseNotification("收到 " + senderAlias + " 的消息", message);
        addLog("收到 " + senderAlias + " 的消息: " + message);
        addActivity(true, "消息 · " + senderAlias, message);
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_MESSAGE, builder.build());
    }

    public List<String> getRecentMessages() {
        return new ArrayList<String>(recentMessages);
    }

    // ------------------------------------------------------------------ sending

    public void sendAsync(final Device target, final List<FileItem> items, final SendCallback callback) {
        currentSendTarget = target.displayName();
        addLog("开始发送到 " + target.displayName() + " (" + items.size() + " 个文件)");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                SendClient client = new SendClient(settings, identity);
                activeSendClient = client;
                client.send(target, items, new SendClient.Listener() {
                    @Override
                    public void onPrepared(Device device) {
                        showSendingNotification("正在发送到 " + device.displayName(), 0, null);
                    }

                    @Override
                    public void onFileProgress(final int index, final int total, final String fileName,
                                               final long sent, final long size) {
                        if (callback != null) callback.onProgress(index, total, fileName, sent, size);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                for (Listener listener : listeners) {
                                    listener.onSendProgress(index, total, fileName, sent, size);
                                }
                            }
                        });
                        long now = System.currentTimeMillis();
                        if (now - lastProgressNotify > 700) {
                            lastProgressNotify = now;
                            int progress = size > 0 ? (int) (sent * 100 / size) : 0;
                            showSendingNotification("正在发送 " + fileName, progress, fileName);
                        }
                    }

                    @Override
                    public void onFinished() {
                        activeSendClient = null;
                        notifySendFinished(true, "发送完成");
                        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                                .cancel(NOTIFICATION_SEND);
                    }

                    @Override
                    public void onError(String message) {
                        activeSendClient = null;
                        notifySendFinished(false, message);
                        Notification.Builder builder = baseNotification("发送失败", message);
                        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                                .notify(NOTIFICATION_SEND, builder.build());
                    }

                    @Override
                    public void onCanceled() {
                        activeSendClient = null;
                        notifySendFinished(false, "已取消");
                        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                                .cancel(NOTIFICATION_SEND);
                    }
                });
            }
        }, "localsend-send");
        thread.setDaemon(true);
        thread.start();
    }

    public void cancelActiveSend() {
        SendClient client = activeSendClient;
        if (client != null) client.cancel();
    }

    private void notifySendFinished(final boolean success, final String message) {
        currentSendTarget = null;
        addLog((success ? "发送完成: " : "发送失败: ") + message);
        addActivity(false, success ? "发送完成" : "发送失败", message);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) listener.onSendFinished(success, message);
            }
        });
    }

    // ------------------------------------------------------------------ notifications

    private Notification.Builder baseNotification(String title, String text) {
        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT));
        if (Build.VERSION.SDK_INT >= 16) {
            Notification.BigTextStyle style = new Notification.BigTextStyle().bigText(text);
            builder.setStyle(style);
        }
        return builder;
    }

    private void showReceivingNotification(String senderAlias, int fileCount, String fileName, int progress, long received) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentTitle("正在接收" + (senderAlias == null ? "" : "（" + senderAlias + "）"))
                .setContentText(fileName == null ? (fileCount + " 个文件") : fileName)
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT));
        if (fileName != null && progress > 0) {
            builder.setProgress(100, Math.min(progress, 100), false);
        } else {
            builder.setProgress(0, 0, true);
        }
        manager.notify(NOTIFICATION_RECEIVE, builder.build());
    }

    private void showSendingNotification(String title, int progress, String fileName) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentTitle(title)
                .setContentText(fileName == null ? "" : fileName)
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT));
        if (progress > 0) {
            builder.setProgress(100, Math.min(progress, 100), false);
        } else {
            builder.setProgress(0, 0, true);
        }
        manager.notify(NOTIFICATION_SEND, builder.build());
    }

    public void updateForegroundNotification() {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("LiteSend · " + settings.alias())
                .setContentText(Util.localIpv4() + ":" + settings.port())
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT));
        manager.notify(NOTIFICATION_FOREGROUND, builder.build());
    }
}





