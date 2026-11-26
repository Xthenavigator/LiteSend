package io.legacy.localsend.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.legacy.localsend.core.Util;

/** Tiny built-in file browser: Android 4.0 has no Storage Access Framework. */
public class FilePickerActivity extends Activity {
    private static final class Entry {
        File file;
        boolean dir;
        String label;
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private ArrayAdapter<String> adapter;
    private TextView pathView;
    private ListView listView;
    private File currentDir;
    private File rootDir;
    private boolean browseMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("选择文件");
        browseMode = "browse".equals(getIntent().getStringExtra("mode"));
        if (browseMode) setTitle("文件浏览");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 8, 12, 8);

        pathView = new TextView(this);
        pathView.setTextSize(12);
        pathView.setTextColor(new io.legacy.localsend.core.Settings(this).lightText() ? 0xFFBBBBBB : 0xFF666666);
        pathView.setPadding(0, 0, 0, 6);
        root.addView(pathView);

        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextSize(16);
                view.setPadding(8, 14, 8, 14);
                return view;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Entry entry = entries.get(position);
                if (entry.dir) {
                    loadDir(entry.file);
                } else if (browseMode) {
                    openExternally(entry.file);
                } else {
                    Intent result = new Intent();
                    result.putExtra("path", entry.file.getAbsolutePath());
                    setResult(RESULT_OK, result);
                    finish();
                }
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("上级目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(button("取消", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        setContentView(root);

        String startPath = getIntent().getStringExtra("dir");
        File start = startPath == null ? null : new File(startPath);
        rootDir = (start != null && start.isDirectory()) ? start : resolveStartDir();
        loadDir(rootDir);
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setOnClickListener(listener);
        return button;
    }

    private void openExternally(File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(android.net.Uri.fromFile(file), io.legacy.localsend.core.Protocol.mimeFor(file.getName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "没有可打开该文件的应用: " + file.getName(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private File resolveStartDir() {
        File external = Environment.getExternalStorageDirectory();
        File[] candidates = new File[]{
                new File(external, "Download"),
                new File(external, "LocalSend"),
                external,
                new File("/storage/sdcard0"),
                new File("/mnt/sdcard"),
                getFilesDir()
        };
        for (File candidate : candidates) {
            if (candidate != null && candidate.isDirectory() && candidate.canRead()) return candidate;
        }
        return new File("/");
    }

    private void goUp() {
        File parent = currentDir.getParentFile();
        if (parent != null && currentDir.getAbsolutePath().length() > 1) {
            loadDir(parent);
        } else {
            finish();
        }
    }

    private void loadDir(File dir) {
        currentDir = dir;
        pathView.setText(dir.getAbsolutePath());
        entries.clear();
        adapter.clear();

        File[] children = dir.listFiles();
        if (children != null) {
            List<File> sorted = new ArrayList<File>(Arrays.asList(children));
            Collections.sort(sorted, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File child : sorted) {
                if (child.getName().startsWith(".")) continue;
                Entry entry = new Entry();
                entry.file = child;
                entry.dir = child.isDirectory();
                if (entry.dir) {
                    entry.label = "[目录] " + child.getName();
                } else {
                    entry.label = child.getName() + "  (" + Util.formatSize(child.length()) + ")";
                }
                entries.add(entry);
                adapter.add(entry.label);
            }
        }
        if (entries.isEmpty()) {
            adapter.add("(空目录)");
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onBackPressed() {
        goUp();
    }
}



