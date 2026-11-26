package io.legacy.localsend;

import android.content.Intent;
import androidx.multidex.MultiDexApplication;

import io.legacy.localsend.service.LocalSendService;

public class App extends MultiDexApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, LocalSendService.class));
    }
}
