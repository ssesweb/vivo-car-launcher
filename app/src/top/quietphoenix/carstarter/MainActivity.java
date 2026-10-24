package top.quietphoenix.carstarter;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import rikka.shizuku.Shizuku;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String CAR_DISPLAY_TAG = "vivo_car_screen";
    private static final int DAEMON_PORT = 7878;

    private static class App {
        String label;
        String pkg;
        Drawable icon;
        long lastUsed;
        App(String l, String p, Drawable i) { label = l; pkg = p; icon = i; }
    }

    private TextView status;
    private GridView grid;
    private EditText search;
    private Button btnAz, btnUsed;
    private String sortMode = "az";
    private List<App> all = new ArrayList<>();
    private List<App> shown = new ArrayList<>();
    private BaseAdapter adapter;
    private int lastCarDisplay = -1;
    private volatile boolean launching;
    private volatile boolean daemonOnline;
    private volatile ICarService carService;
    private Button shizukuButton;
    private String shizukuState = "正在检查 Shizuku";
    private boolean permissionRequested;
    private boolean binding;
    private boolean destroyed;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName("top.quietphoenix.carstarter", CarUserService.class.getName()))
            .tag("carstarter-daemon").processNameSuffix("car_service").daemon(true).version(15);
    private final Shizuku.OnBinderReceivedListener binderReceived =
            () -> runOnUiThread(() -> prepareShizuku(true));
    private final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(() -> {
        carService = null;
        binding = false;
        shizukuState = "Shizuku 已停止";
        refreshStatus();
        checkDaemon();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> {
        if (code != 1001) return;
        runOnUiThread(() -> {
            shizukuState = result == PackageManager.PERMISSION_GRANTED
                    ? "Shizuku 已授权" : "Shizuku 未授权, 可点击重试";
            prepareShizuku(false);
        });
    };
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            carService = ICarService.Stub.asInterface(binder);
            startDaemon();
        }
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            carService = null;
            shizukuState = "后台服务已断开, 点击恢复";
            checkDaemon();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 40, 24, 24);
        root.setBackgroundColor(0xFF101418);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("车启");
        title.setTextSize(20);
        title.setTextColor(0xFFE8EAED);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tp);
        head.addView(title);

        btnAz = new Button(this);
        btnAz.setText("A-Z");
        btnAz.setTextSize(13);
        btnUsed = new Button(this);
        btnUsed.setText("最近使用");
        btnUsed.setTextSize(13);
        head.addView(btnAz);
        head.addView(btnUsed);
        root.addView(head);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFF9AA0A6);
        status.setPadding(8, 8, 8, 8);
        root.addView(status);
        shizukuButton = new Button(this);
        shizukuButton.setText("检查 Shizuku");
        shizukuButton.setOnClickListener(v -> {
            permissionRequested = false;
            if (!Shizuku.pingBinder()) {
                Intent manager = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (manager == null) toast("请先安装 Shizuku");
                else startActivity(manager);
            } else prepareShizuku(true);
        });
        root.addView(shizukuButton);

        search = new EditText(this);
        search.setHint("搜索应用 / 输入包名回车");
        search.setTextColor(0xFFE8EAED);
        search.setHintTextColor(0xFF5F6368);
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        search.setOnEditorActionListener((v, actionId, ev) -> {
            String q = search.getText().toString().trim();
            if (q.contains(".") && q.indexOf('.') > 0) { launch(q); return true; }
            return false;
        });
        root.addView(search);

        btnAz.setOnClickListener(v -> setSort("az"));
        btnUsed.setOnClickListener(v -> {
            if (!hasUsageAccess()) {
                toast("请先在下一个页面授权“使用情况访问”");
                startActivity(new Intent("android.settings.USAGE_ACCESS_SETTINGS"));
                return;
            }
            setSort("used");
        });

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(8);
        grid.setVerticalSpacing(16);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gp.topMargin = 16;
        grid.setLayoutParams(gp);
        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View convertView, ViewGroup parent) {
                LinearLayout cell = (LinearLayout) convertView;
                if (cell == null) {
                    cell = new LinearLayout(MainActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    ImageView iv = new ImageView(MainActivity.this);
                    iv.setLayoutParams(new LinearLayout.LayoutParams(
                            (int) (52 * getResources().getDisplayMetrics().density),
                            (int) (52 * getResources().getDisplayMetrics().density)));
                    iv.setId(1);
                    TextView tv = new TextView(MainActivity.this);
                    tv.setTextSize(11);
                    tv.setTextColor(0xFFE8EAED);
                    tv.setMaxLines(1);
                    tv.setGravity(Gravity.CENTER_HORIZONTAL);
                    tv.setId(2);
                    cell.addView(iv);
                    cell.addView(tv);
                }
                App a = shown.get(i);
                ImageView iv = cell.findViewById(1);
                TextView tv = cell.findViewById(2);
                iv.setImageDrawable(a.icon);
                tv.setText(a.label);
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, i, id) -> launch(shown.get(i).pkg));
        root.addView(grid);

        setContentView(root);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastCarDisplay = carDisplayId();
        loadApps();
        prepareShizuku(true);
        checkDaemon();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        uiHandler.removeCallbacksAndMessages(null);
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        if (Shizuku.pingBinder()) {
            try { Shizuku.unbindUserService(serviceArgs, serviceConnection, false); }
            catch (RuntimeException ignored) {}
        }
        super.onDestroy();
    }

    private void refreshStatus() {
        if (destroyed || launching) return;
        lastCarDisplay = carDisplayId();
        String car = lastCarDisplay >= 0 ? "车载屏 " + lastCarDisplay : "未连车";
        status.setText(car + " · " + shizukuState + " · "
                + (daemonOnline ? "守护进程在线" : "守护进程未运行") + " · 应用 " + all.size());
        shizukuButton.setText(!Shizuku.pingBinder() ? "打开 Shizuku"
                : carService != null ? "检查 / 恢复服务" : "授权 / 启动服务");
    }

    private void checkDaemon() {
        new Thread(() -> {
            boolean online = pingDaemon();
            runOnUiThread(() -> {
                daemonOnline = online;
                refreshStatus();
            });
        }, "car-status").start();
    }

    private void prepareShizuku(boolean request) {
        if (destroyed) return;
        try {
            if (!Shizuku.pingBinder()) {
                shizukuState = "Shizuku 未运行, 请先启动";
            } else if (Shizuku.isPreV11()) {
                shizukuState = "请更新 Shizuku";
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                shizukuState = "Shizuku 未授权";
                if (request && !permissionRequested) {
                    permissionRequested = true;
                    if (Shizuku.shouldShowRequestPermissionRationale()) {
                        shizukuState = "请在 Shizuku 应用管理中允许车启";
                        toast(shizukuState);
                        Intent manager = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        if (manager != null) startActivity(manager);
                    } else Shizuku.requestPermission(1001);
                }
            } else {
                shizukuState = "Shizuku 已授权";
                if (carService != null && carService.asBinder().pingBinder()) startDaemon();
                else if (!binding) {
                    binding = true;
                    shizukuState = "正在启动后台服务";
                    Shizuku.bindUserService(serviceArgs, serviceConnection);
                    uiHandler.postDelayed(() -> {
                        if (binding) {
                            binding = false;
                            shizukuState = "服务连接超时, 点击重试";
                            refreshStatus();
                        }
                    }, 15000);
                }
            }
        } catch (RuntimeException e) {
            binding = false;
            shizukuState = "Shizuku 连接失败, 点击重试";
            android.util.Log.e("CarStarter", "Shizuku setup failed", e);
        }
        refreshStatus();
    }

    private void startDaemon() {
        new Thread(() -> {
            try {
                ICarService service = carService;
                if (service == null) return;
                String result = service.ensureDaemon();
                boolean online = pingDaemon();
                runOnUiThread(() -> {
                    daemonOnline = online;
                    shizukuState = online ? "Shizuku 服务已就绪" : "后台服务启动失败, 点击重试";
                    android.util.Log.i("CarStarter", "Shizuku daemon: " + result);
                    refreshStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    daemonOnline = false;
                    carService = null;
                    shizukuState = "后台服务连接失败, 点击恢复";
                    refreshStatus();
                });
                android.util.Log.e("CarStarter", "Shizuku daemon failed", e);
            }
        }, "car-service-start").start();
    }

    private void setSort(String mode) {
        sortMode = mode;
        btnAz.setEnabled(!"az".equals(mode));
        btnUsed.setEnabled(!"used".equals(mode));
        if ("used".equals(mode)) loadUsage();
        filter(search.getText().toString());
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        return ops.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(),
                getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private void loadUsage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 90L * 86400000, now);
            Map<String, Long> last = new HashMap<>();
            for (UsageStats s : stats) {
                Long prev = last.get(s.getPackageName());
                if (prev == null || s.getLastTimeUsed() > prev) last.put(s.getPackageName(), s.getLastTimeUsed());
            }
            for (App a : all) a.lastUsed = last.containsKey(a.pkg) ? last.get(a.pkg) : 0;
        } catch (Exception e) {
            android.util.Log.d("CarStarter", "usage error " + e);
        }
    }

    private void loadApps() {
        all.clear();
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) continue;
            try {
                all.add(new App(String.valueOf(ri.loadLabel(pm)), pkg, ri.loadIcon(pm)));
            } catch (Exception e) {
                all.add(new App(pkg, pkg, null));
            }
        }
        Collections.sort(all, (a, b) -> a.label.compareToIgnoreCase(b.label));
        android.util.Log.d("CarStarter", "loadApps: " + all.size() + " apps, tmgp.sgame=" +
                (all.stream().anyMatch(a -> a.pkg.equals("com.tencent.tmgp.sgame"))));
        filter(search.getText().toString());
    }

    private void filter(String q) {
        shown.clear();
        List<App> src = new ArrayList<>(all);
        if ("used".equals(sortMode)) {
            Collections.sort(src, (a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        }
        q = q == null ? "" : q.trim().toLowerCase();
        if (q.isEmpty()) {
            shown.addAll(src);
        } else {
            for (App a : src)
                if (a.label.toLowerCase().contains(q) || a.pkg.toLowerCase().contains(q)) shown.add(a);
        }
        adapter.notifyDataSetChanged();
    }

    private int carDisplayId() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display fallback = null;
        for (Display d : dm.getDisplays()) {
            if (d.getName() != null && d.getName().contains(CAR_DISPLAY_TAG)) {
                if (d.getState() == Display.STATE_ON) return d.getDisplayId();
                fallback = d;
            }
        }
        return fallback != null ? fallback.getDisplayId() : -1;
    }

    private String daemonCmd(String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", DAEMON_PORT), 2000);
            s.setSoTimeout(cmd.startsWith("FLIPMAP ") ? 35000 : "PING".equals(cmd) ? 1500 : 15000);
            PrintStream out = new PrintStream(s.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out.println(cmd);
            String resp = in.readLine();
            return resp == null ? "(empty)" : resp;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean pingDaemon() {
        String r = daemonCmd("PING");
        return r != null && r.startsWith("PONG");
    }

    private void launch(String pkg) {
        if (launching) { toast("正在启动, 请稍候"); return; }
        if (!daemonOnline) {
            prepareShizuku(true);
            checkDaemon();
            toast("请先完成授权并等待服务就绪");
            return;
        }
        final int displayId = carDisplayId();
        if (displayId < 0) {
            status.setText("未检测到车载屏, 请先连接车机");
            toast("未连车");
            return;
        }
        status.setText("启动中: " + pkg);
        launching = true;
        new Thread(() -> {
            try {
            String flip = daemonCmd("FLIPMAP " + pkg);
            android.util.Log.d("CarStarter", "flip " + pkg + " -> " + flip);
            String resp = daemonCmd("LAUNCH " + pkg + " auto");
            android.util.Log.d("CarStarter", "launch " + pkg + " -> " + resp);
            if (resp != null) {
                final String line = resp.contains("exit=0") ? "启动命令已执行: " + pkg : shortResp(resp);
                final boolean ok = resp.contains("exit=0");
                runOnUiThread(() -> {
                    status.setText(line);
                    if (!ok) toast("返回异常, 详见状态栏");
                });
                return;
            }
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                runOnUiThread(() -> status.setText("未安装: " + pkg));
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final String[] outcome = new String[1];
            try {
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(displayId);
                startActivity(intent, opts.toBundle());
                outcome[0] = "直启 " + pkg + " → display " + displayId;
            } catch (SecurityException e) {
                outcome[0] = "直启被拒, 请点击恢复 Shizuku 服务";
            } catch (Exception e) {
                outcome[0] = "启动失败: " + e;
            }
            runOnUiThread(() -> status.setText(outcome[0]));
            } finally { launching = false; }
        }, "car-launch").start();
    }

    private String shortResp(String r) {
        int i = r.indexOf("Starting:");
        return i >= 0 ? r.substring(i) : r;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
