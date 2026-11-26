package io.legacy.localsend.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import io.legacy.localsend.Core;
import io.legacy.localsend.R;
import io.legacy.localsend.core.Util;
import io.legacy.localsend.ui.MainActivity;

public class LocalSendService extends Service {
    private Core core;

    @Override
    public void onCreate() {
        super.onCreate();
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("正在启动…")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        startForeground(1, notification);

        core = Core.getOrCreate(this);
        core.initAsync();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (core != null) {
            core.updateForegroundNotification();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep receiving in the background: the foreground service stays alive.
        super.onTaskRemoved(rootIntent);
    }
}
