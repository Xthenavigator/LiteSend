package io.legacy.localsend.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.legacy.localsend.Core;
import io.legacy.localsend.R;
import io.legacy.localsend.core.Device;
import io.legacy.localsend.core.FileItem;
import io.legacy.localsend.core.Util;

/**
 * Three tabs like the original LocalSend: receive, send, logs & settings.
 */
public class MainActivity extends Activity implements Core.Listener {
    private static final int TAB_RECEIVE = 0;
    private static final int TAB_SEND = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int REQUEST_PICK_FILE = 1001;
    private static final int REQUEST_PICK_FILE_SYSTEM = 1002;

    private Core core;
    private final Handler handler = new Handler();
    private int currentTab = TAB_SEND;
    private int colorPrimary = 0xFFEEEEEE;
    private int colorSecondary = 0xFFBBBBBB;
    private int colorAccent = 0xFF82B1FF;
    private int colorBody = 0xFFCCCCCC;
    private int colorLog = 0xFFDDDDDD;
    private int colorTabBg = 0xFF444444;
    private int colorTabText = 0xFFDDDDDD;
    private final int colorTabSelectedBg = 0xFF3F51B5;
    private final int colorTabSelectedText = 0xFFFFFFFF;
    private CheckBox systemPickerBox;
    private CheckBox lightTextBox;
    private volatile boolean resumed;

    private Button[] tabButtons = new Button[3];
    private View receivePageView;
    private View sendPageView;
    private View settingsPageView;

    // send tab
    private TextView statusView;
    private TextView sendStatusView;
    private ListView deviceListView;
    private DeviceAdapter deviceAdapter;
    private final List<Device> devices = new ArrayList<Device>();
    private Device pendingTarget;

    // receive tab
    private TextView receiveStatusView;
    private ProgressBar receiveProgress;
    private ListView activityListView;
    private ActivityAdapter activityAdapter;
    private final List<Core.ActivityEntry> activities = new ArrayList<Core.ActivityEntry>();

    // settings tab
    private EditText aliasInput;
    private EditText portInput;
    private CheckBox httpsBox;
    private TextView infoView;
    private TextView logView;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            updateReceiveStatus();
            if (currentTab == TAB_SEND) refreshDevices();
            if (currentTab == TAB_RECEIVE) refreshActivities();
            if (currentTab == TAB_SETTINGS) refreshLogs();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.app_name));
        core = Core.getOrCreate(this);
        applyPalette();
        buildUi();
        core.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        core.addListener(this);
        handler.post(ticker);
        showTab(currentTab);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        core.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ layout

    private void applyPalette() {
        boolean light = core.settings.lightText();
        colorPrimary = light ? 0xFFEEEEEE : 0xFF222222;
        colorSecondary = light ? 0xFFBBBBBB : 0xFF666666;
        colorAccent = light ? 0xFF82B1FF : 0xFF3F51B5;
        colorBody = light ? 0xFFCCCCCC : 0xFF555555;
        colorLog = light ? 0xFFDDDDDD : 0xFF444444;
        colorTabBg = light ? 0xFF444444 : 0xFFE0E0E0;
        colorTabText = light ? 0xFFDDDDDD : 0xFF333333;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        String[] titles = new String[]{"接收", "发送", "日志与设置"};
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setText(titles[i]);
            button.setTextSize(15);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(index);
                }
            });
            tabBar.addView(button, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tabButtons[i] = button;
        }
        root.addView(tabBar);

        FrameLayout content = new FrameLayout(this);
        receivePageView = buildReceivePage();
        sendPageView = buildSendPage();
        settingsPageView = buildSettingsPage();
        content.addView(receivePageView);
        content.addView(sendPageView);
        content.addView(settingsPageView);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showTab(TAB_SEND);
    }

    private View buildSendPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(12, 8, 12, 8);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(colorPrimary);
        page.addView(statusView);

        TextView hint = new TextView(this);
        hint.setText("选设备 → 发送。也可点设备行直接发送。");
        hint.setTextSize(12);
        hint.setTextColor(colorSecondary);
        hint.setPadding(0, 4, 0, 4);
        page.addView(hint);

        deviceListView = new ListView(this);
        deviceAdapter = new DeviceAdapter();
        deviceListView.setAdapter(deviceAdapter);
        deviceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showDeviceActions(devices.get(position));
            }
        });
        page.addView(deviceListView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        sendStatusView = new TextView(this);
        sendStatusView.setTextSize(12);
        sendStatusView.setTextColor(colorAccent);
        sendStatusView.setPadding(0, 4, 0, 4);
        page.addView(sendStatusView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(button("发送文件", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseDevice(new DeviceHandler() {
                    @Override
                    public void onDevice(Device device) {
                        sendFileTo(device);
                    }
                });
            }
        }), weight());
        row1.addView(button("发送文本", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseDevice(new DeviceHandler() {
                    @Override
                    public void onDevice(Device device) {
                        showSendTextDialog(device);
                    }
                });
            }
        }), weight());
        row1.addView(button("刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                core.refresh();
                core.addLog("手动广播一次");
                updateStatus();
            }
        }), weight());
        page.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, 4, 0, 0);
        row2.addView(button("手动IP", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showManualIpDialog();
            }
        }), weight());
        row2.addView(button("复制我的IP", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setText(Util.localIpv4() + ":" + core.settings.port());
                Toast.makeText(MainActivity.this, "已复制本机地址", Toast.LENGTH_SHORT).show();
            }
        }), weight());
        page.addView(row2);
        return page;
    }

    private View buildReceivePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(12, 8, 12, 8);

        receiveStatusView = new TextView(this);
        receiveStatusView.setTextSize(13);
        receiveStatusView.setTextColor(colorPrimary);
        page.addView(receiveStatusView);

        receiveProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        receiveProgress.setMax(100);
        receiveProgress.setVisibility(View.GONE);
        page.addView(receiveProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 20));

        TextView hint = new TextView(this);
        hint.setText("接收到的文件与消息：");
        hint.setTextSize(12);
        hint.setTextColor(colorSecondary);
        hint.setPadding(0, 6, 0, 2);
        page.addView(hint);

        activityListView = new ListView(this);
        activityAdapter = new ActivityAdapter();
        activityListView.setAdapter(activityAdapter);
        activityListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Core.ActivityEntry entry = activities.get(position);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(entry.title)
                        .setMessage(entry.detail)
                        .setPositiveButton("关闭", null)
                        .show();
            }
        });
        page.addView(activityListView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("接收目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDownloadDir();
            }
        }), weight());
        buttons.addView(button("消息记录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMessagesDialog();
            }
        }), weight());
        page.addView(buttons);
        return page;
    }

    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(12, 8, 12, 8);
        scroll.addView(page);

        TextView section1 = new TextView(this);
        section1.setText("本机设置");
        section1.setTextSize(14);
        section1.setTextColor(colorAccent);
        page.addView(section1);

        aliasInput = new EditText(this);
        aliasInput.setHint("设备别名");
        aliasInput.setTextSize(14);
        page.addView(aliasInput);

        portInput = new EditText(this);
        portInput.setHint("端口（默认 53317）");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setTextSize(14);
        page.addView(portInput);

        httpsBox = new CheckBox(this);
        httpsBox.setText("使用 HTTPS（TLS 1.2）");
        httpsBox.setTextSize(14);
        page.addView(httpsBox);

        systemPickerBox = new CheckBox(this);
        systemPickerBox.setText("优先使用系统文件管理器选文件（Android 4.4+）");
        systemPickerBox.setTextSize(14);
        page.addView(systemPickerBox);

        lightTextBox = new CheckBox(this);
        lightTextBox.setText("浅色文字（深色背景下更清楚）");
        lightTextBox.setTextSize(14);
        page.addView(lightTextBox);

        Button save = button("保存并重启服务", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });
        page.addView(save);

        TextView section2 = new TextView(this);
        section2.setText("运行信息");
        section2.setTextSize(14);
        section2.setTextColor(colorAccent);
        section2.setPadding(0, 10, 0, 2);
        page.addView(section2);

        infoView = new TextView(this);
        infoView.setTextSize(11);
        infoView.setTextColor(colorBody);
        page.addView(infoView);

        TextView section3 = new TextView(this);
        section3.setText("日志");
        section3.setTextSize(14);
        section3.setTextColor(colorAccent);
        section3.setPadding(0, 10, 0, 2);
        page.addView(section3);

        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setTextColor(colorLog);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setMovementMethod(new ScrollingMovementMethod());
        page.addView(logView);

        Button clear = button("清空日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                core.clearLogs();
                refreshLogs();
            }
        });
        page.addView(clear);

        TextView footer = new TextView(this);
        footer.setText(getString(R.string.settings_footer));
        footer.setTextSize(12);
        footer.setTextColor(colorBody);
        footer.setPadding(0, 16, 0, 24);
        page.addView(footer);
        return scroll;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void showTab(int tab) {
        currentTab = tab;
        for (int i = 0; i < tabButtons.length; i++) {
            boolean selected = i == tab;
            tabButtons[i].setBackgroundColor(selected ? colorTabSelectedBg : colorTabBg);
            tabButtons[i].setTextColor(selected ? colorTabSelectedText : colorTabText);
        }
        receivePageView.setVisibility(tab == TAB_RECEIVE ? View.VISIBLE : View.GONE);
        sendPageView.setVisibility(tab == TAB_SEND ? View.VISIBLE : View.GONE);
        settingsPageView.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);

        updateStatus();
        updateReceiveStatus();
        if (tab == TAB_SEND) refreshDevices();
        if (tab == TAB_RECEIVE) refreshActivities();
        if (tab == TAB_SETTINGS) {
            fillSettings();
            refreshLogs();
        }
    }

    // ------------------------------------------------------------------ send tab

    private void refreshDevices() {
        List<Device> latest = core.getDevices();
        devices.clear();
        devices.addAll(latest);
        deviceAdapter.notifyDataSetChanged();
    }

    private void updateStatus() {
        if (statusView == null) return;
        statusView.setText(core.getStatusText());
    }

    private void fillSettings() {
        if (aliasInput == null) return;
        if (aliasInput.getText().length() == 0) aliasInput.setText(core.settings.alias());
        if (portInput.getText().length() == 0) portInput.setText(String.valueOf(core.settings.port()));
        httpsBox.setChecked(core.settings.https());
        systemPickerBox.setChecked(core.settings.systemFilePicker());
        lightTextBox.setChecked(core.settings.lightText());
        StringBuilder info = new StringBuilder();
        info.append("版本: 1.0 (minSdk 14)\n");
        info.append("指纹: ").append(core.getFingerprint()).append('\n');
        info.append("接收目录: ").append(io.legacy.localsend.core.Paths.downloadDir());
        info.append('\n');
        info.append(core.getStatusText());
        infoView.setText(info.toString());
    }

    private void saveSettings() {
        String alias = aliasInput.getText().toString().trim();
        int port = core.settings.port();
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        boolean https = httpsBox.isChecked();
        boolean systemPicker = systemPickerBox.isChecked();
        boolean lightText = lightTextBox.isChecked();
        boolean restart = !alias.equals(core.settings.alias()) || port != core.settings.port()
                || https != core.settings.https();
        boolean paletteChanged = lightText != core.settings.lightText();
        if (alias.length() > 0) core.settings.setAlias(alias);
        core.settings.setPort(port);
        core.settings.setHttps(https);
        core.settings.setSystemFilePicker(systemPicker);
        core.settings.setLightText(lightText);
        if (paletteChanged) {
            core.addLog("文字深浅已切换，重建界面");
            applyPalette();
            recreate();
            return;
        }
        if (restart) {
            core.addLog("设置已修改，重启服务");
            core.restartNetwork();
            Toast.makeText(this, "设置已保存，服务正在重启", Toast.LENGTH_SHORT).show();
        } else {
            core.updateForegroundNotification();
        }
        updateStatus();
        fillSettings();
        refreshLogs();
    }

    private void refreshLogs() {
        if (logView == null) return;
        StringBuilder sb = new StringBuilder();
        for (String line : core.getLogs()) {
            sb.append(line).append('\n');
        }
        logView.setText(sb.toString());
    }

    private final class DeviceAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return devices.size();
        }

        @Override
        public Object getItem(int position) {
            return devices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(4, 10, 4, 10);

                LinearLayout column = new LinearLayout(MainActivity.this);
                column.setOrientation(LinearLayout.VERTICAL);
                TextView title = new TextView(MainActivity.this);
                title.setTextSize(17);
                title.setTextColor(colorPrimary);
                title.setId(1001);
                TextView sub = new TextView(MainActivity.this);
                sub.setTextSize(12);
                sub.setTextColor(colorSecondary);
                sub.setId(1002);
                column.addView(title);
                column.addView(sub);
                row.addView(column, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView action = new TextView(MainActivity.this);
                action.setText("发送 ›");
                action.setTextSize(14);
                action.setTextColor(colorAccent);
                action.setGravity(Gravity.CENTER_VERTICAL);
                action.setPadding(8, 0, 4, 0);
                row.addView(action);
            } else {
                row = (LinearLayout) convertView;
            }
            Device device = devices.get(position);
            ((TextView) row.findViewById(1001)).setText(device.displayName());
            ((TextView) row.findViewById(1002)).setText(device.subtitle());
            row.setTag(device);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Object tag = v.getTag();
                    if (tag instanceof Device) showDeviceActions((Device) tag);
                }
            });
            return row;
        }
    }

    // ------------------------------------------------------------------ receive tab

    private void updateReceiveStatus() {
        if (receiveStatusView == null) return;
        if (core.isReceiving()) {
            String name = core.getReceiveFileName();
            long total = core.getReceiveTotal();
            long current = core.getReceiveBytes();
            int percent = total > 0 ? (int) (current * 100 / total) : 0;
            receiveStatusView.setText("正在接收" + (name == null ? "…" : ": " + name)
                    + "  " + Util.formatSize(current) + " / " + Util.formatSize(total));
            receiveProgress.setProgress(percent);
            receiveProgress.setVisibility(View.VISIBLE);
        } else if (core.getError() != null && !core.isReady()) {
            receiveStatusView.setText("服务异常: " + core.getError());
            receiveProgress.setVisibility(View.GONE);
        } else {
            receiveStatusView.setText("空闲，等待对端发送（端口 " + core.settings.port() + "）");
            receiveProgress.setVisibility(View.GONE);
        }
    }

    private void refreshActivities() {
        List<Core.ActivityEntry> latest = core.getActivities();
        activities.clear();
        activities.addAll(latest);
        activityAdapter.notifyDataSetChanged();
    }

    private final class ActivityAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return activities.size();
        }

        @Override
        public Object getItem(int position) {
            return activities.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(4, 8, 4, 8);
                TextView title = new TextView(MainActivity.this);
                title.setTextSize(15);
                title.setTextColor(colorPrimary);
                title.setId(2001);
                TextView sub = new TextView(MainActivity.this);
                sub.setTextSize(11);
                sub.setTextColor(colorSecondary);
                sub.setId(2002);
                row.addView(title);
                row.addView(sub);
            } else {
                row = (LinearLayout) convertView;
            }
            Core.ActivityEntry entry = activities.get(position);
            String marker = entry.incoming ? "[收] " : "[发] ";
            ((TextView) row.findViewById(2001)).setText(marker + entry.title);
            ((TextView) row.findViewById(2002)).setText(formatTime(entry.time) + " · " + entry.detail);
            return row;
        }
    }

    private static String formatTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.US);
        return format.format(new Date(millis));
    }

    // ------------------------------------------------------------------ device actions

    private void showDeviceActions(final Device device) {
        String[] actions = new String[]{"发送文件", "发送文本消息", "复制 IP"};
        new AlertDialog.Builder(this)
                .setTitle(device.displayName())
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            sendFileTo(device);
                        } else if (which == 1) {
                            showSendTextDialog(device);
                        } else {
                            android.content.ClipboardManager clipboard =
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            clipboard.setText(device.ip);
                            Toast.makeText(MainActivity.this, "已复制 " + device.ip, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private interface DeviceHandler {
        void onDevice(Device device);
    }

    private void chooseDevice(final DeviceHandler handler) {
        if (devices.isEmpty()) {
            Toast.makeText(this, "还没有发现设备：先点「刷新」，或用「手动IP」添加", Toast.LENGTH_LONG).show();
            return;
        }
        if (devices.size() == 1) {
            handler.onDevice(devices.get(0));
            return;
        }
        final String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = devices.get(i).displayName() + "  " + devices.get(i).subtitle();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择设备")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        handler.onDevice(devices.get(which));
                    }
                })
                .show();
    }

    private void sendFileTo(Device device) {
        pendingTarget = device;
        // Android 4.4+ has the Storage Access Framework: prefer the system picker,
        // fall back to the built-in browser (needed on API 14-18).
        if (core.settings.systemFilePicker() && android.os.Build.VERSION.SDK_INT >= 19) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivityForResult(intent, REQUEST_PICK_FILE_SYSTEM);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        startActivityForResult(new Intent(this, FilePickerActivity.class), REQUEST_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || pendingTarget == null) return;
        if (requestCode == REQUEST_PICK_FILE_SYSTEM) {
            android.net.Uri uri = data.getData();
            if (uri != null) importUriAndSend(pendingTarget, uri);
            return;
        }
        if (requestCode == REQUEST_PICK_FILE) {
            String path = data.getStringExtra("path");
            if (path == null) return;
            File file = new File(path);
            if (!file.exists() || !file.isFile()) {
                Toast.makeText(this, "无法读取文件: " + path, Toast.LENGTH_LONG).show();
                return;
            }
            beginSend(pendingTarget, Collections.singletonList(FileItem.fromFile(file)));
        }
    }

    /** Copies a content:// document into the app cache so it can be streamed. */
    private void importUriAndSend(final Device target, final android.net.Uri uri) {
        Toast.makeText(this, "正在读取文件…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File cached = null;
                String error = null;
                FileOutputStream out = null;
                InputStream in = null;
                try {
                    String name = queryDisplayName(uri);
                    File dir = new File(getCacheDir(), "send");
                    dir.mkdirs();
                    File[] stale = dir.listFiles();
                    if (stale != null) {
                        for (File file : stale) file.delete();
                    }
                    cached = new File(dir, name);
                    in = getContentResolver().openInputStream(uri);
                    out = new FileOutputStream(cached);
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                } catch (Exception e) {
                    error = e.toString();
                    cached = null;
                } finally {
                    Util.closeQuietly(in);
                    Util.closeQuietly(out);
                }
                final File file = cached;
                final String message = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (file != null && file.exists()) {
                            beginSend(target, Collections.singletonList(FileItem.fromFile(file)));
                        } else {
                            Toast.makeText(MainActivity.this, "读取文件失败: " + message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "localsend-import").start();
    }

    private String queryDisplayName(android.net.Uri uri) {
        String name = null;
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        if (name == null || name.length() == 0) name = "file";
        return Util.sanitizeFileName(name);
    }

    private void beginSend(Device target, List<FileItem> items) {
        if (!core.isReady()) {
            Toast.makeText(this, "服务尚未就绪，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        sendStatusView.setText("正在连接 " + target.displayName() + "…");
        core.sendAsync(target, items, null);
    }

    private void showSendTextDialog(final Device device) {
        final EditText input = new EditText(this);
        input.setHint("输入要发送的文本");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.LEFT);
        new AlertDialog.Builder(this)
                .setTitle("发送文本到 " + device.displayName())
                .setView(input)
                .setPositiveButton("发送", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String message = input.getText().toString();
                        if (message.trim().length() == 0) return;
                        beginSend(device, Collections.singletonList(FileItem.fromText(message)));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ dialogs

    private void showManualIpDialog() {
        final EditText input = new EditText(this);
        input.setHint("例如 192.168.0.100 或 192.168.0.100:53317");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("手动添加设备")
                .setMessage("输入对方 IP（可选端口，默认 53317）")
                .setView(input)
                .setPositiveButton("连接", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String value = input.getText().toString().trim();
                        if (value.length() == 0) return;
                        core.addLog("手动连接 " + value);
                        core.addManualDeviceAsync(value, new Core.ManualResult() {
                            @Override
                            public void onResult(final Device device, final String error) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (device != null) {
                                            Toast.makeText(MainActivity.this,
                                                    "已连接: " + device.displayName(), Toast.LENGTH_SHORT).show();
                                            refreshDevices();
                                        } else {
                                            Toast.makeText(MainActivity.this,
                                                    "连接失败: " + error, Toast.LENGTH_LONG).show();
                                        }
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMessagesDialog() {
        List<String> messages = core.getRecentMessages();
        if (messages.isEmpty()) {
            Toast.makeText(this, "还没有收到消息", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String message : messages) {
            sb.append(message).append("\n\n");
        }
        TextView text = new TextView(this);
        text.setText(sb.toString());
        text.setTextSize(14);
        text.setPadding(16, 8, 16, 8);
        new AlertDialog.Builder(this)
                .setTitle("收到的消息")
                .setView(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void openDownloadDir() {
        final File dir = io.legacy.localsend.core.Paths.downloadDir();
        if (!dir.exists()) dir.mkdirs();
        core.addLog("打开接收目录: " + dir.getAbsolutePath());

        // Prefer the system file manager; Android 4.x has no directory URI API,
        // so a few common intent types are tried before falling back.
        android.net.Uri uri = android.net.Uri.fromFile(dir);
        String[] directoryTypes = new String[]{"resource/folder", "vnd.android.document/directory",
                "inode/directory"};
        for (String type : directoryTypes) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, type);
            try {
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        // No file manager available: just show where the files are.
        new AlertDialog.Builder(this)
                .setTitle("接收目录")
                .setMessage(dir.getAbsolutePath())
                .setPositiveButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------------ Core.Listener

    @Override
    public void onDevicesChanged() {
        refreshDevices();
    }

    @Override
    public void onReady() {
        updateStatus();
        updateReceiveStatus();
        refreshDevices();
        core.addLog("界面已连接到服务");
    }

    @Override
    public void onReceiveStarted(int fileCount, String senderAlias) {
        updateReceiveStatus();
        if (currentTab == TAB_RECEIVE) refreshActivities();
    }

    @Override
    public void onReceiveProgress(String fileName, long received, long total) {
        updateReceiveStatus();
    }

    @Override
    public void onReceiveFinished(boolean success, String message) {
        updateReceiveStatus();
        refreshActivities();
        Toast.makeText(this, (success ? "接收完成: " : "接收失败: ") + message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onTextMessage(String senderAlias, final String message) {
        refreshActivities();
        if (isFinishing() || !resumed) return;
        new AlertDialog.Builder(this)
                .setTitle(senderAlias + " 发来消息")
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        android.content.ClipboardManager clipboard =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setText(message);
                        Toast.makeText(MainActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    @Override
    public void onSendProgress(int index, int total, String fileName, long sent, long size) {
        if (sendStatusView == null) return;
        int percent = size > 0 ? (int) (sent * 100 / size) : 0;
        sendStatusView.setText("发送中 " + index + "/" + total + "  " + fileName + "  "
                + percent + "%  (" + Util.formatSize(sent) + " / " + Util.formatSize(size) + ")");
    }

    @Override
    public void onSendFinished(boolean success, String message) {
        if (sendStatusView != null) {
            sendStatusView.setText((success ? "上次发送完成: " : "上次发送失败: ") + message);
        }
        refreshActivities();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}









