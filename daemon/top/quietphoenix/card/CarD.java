package top.quietphoenix.card;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 车载启动守护进程: 以 shell 身份常驻, 监听 127.0.0.1:7878。
 * 协议 (行文本):
 *   LAUNCH <pkg> <displayId>   -> 以 shell 执行 am start 到指定显示屏
 *   PING                       -> PONG
 *   STOP                       -> 退出
 *   START                      -> 删看门狗暂停标记
 *   BESTDISPLAY                -> 查询首选应用屏
 *   FLIPMAP <pkg>              -> car-bridge 地图翻转
 *   LAUNCHICON <pngLen>\n<png> -> 原生方式启动: 回桌面开应用网格, 按图标像素匹配定位,
 *                                 input tap 真实图标, 由系统 networking 决定安全区/全屏
 * 仅绑定本机回环, 供同机的 CarStarter 应用调用。
 */
public class CarD {

    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(7878, 16, java.net.InetAddress.getByName("127.0.0.1"));
        log("CarD listening on 127.0.0.1:7878 uid=" + android.os.Process.myUid());
        while (true) {
            Socket s = server.accept();
            try {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                String line = readLine(in);
                if (line == null) { s.close(); continue; }
                String[] p = line.trim().split("\\s+");
                String resp;
                if ("PING".equals(p[0])) {
                    resp = "PONG uid=" + android.os.Process.myUid();
                } else if ("STOP".equals(p[0])) {
                    out.write("BYE\n".getBytes());
                    out.flush();
                    s.close();
                    try {
                        new java.io.File("/data/local/tmp/card.stop").createNewFile();
                    } catch (Exception ignore) {}
                    System.exit(0);
                    return;
                } else if ("START".equals(p[0])) {
                    new java.io.File("/data/local/tmp/card.stop").delete();
                    resp = "OK watchdog resumed";
                } else if ("LAUNCH".equals(p[0]) && p.length >= 3) {
                    String pkg = sanitize(p[1]);
                    String disp = "auto".equals(p[2]) ? bestDisplay() : sanitize(p[2]);
                    resp = exec("am start -n $(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + pkg + " | tail -1) --display " + disp);
                } else if ("BESTDISPLAY".equals(p[0])) {
                    resp = bestDisplay();
                } else if ("FLIPMAP".equals(p[0]) && p.length >= 2) {
                    String pkg = sanitize(p[1]);
                    // 只对地图类应用翻屏, 其余直接跳过 (car-bridge 对未知包会跑满超时)
                    if (pkg.matches("com\\.autonavi\\.(minimap|amapauto)|com\\.baidu\\.(BaiduMap|mapauto|naviauto)|com\\.tencent\\.(map|wecarnavi)")) {
                        resp = exec("CLASSPATH=/data/local/tmp/car-bridge.jar timeout 25 /system/bin/app_process /system/bin CarSessionBridge autoflip " + pkg);
                    } else {
                        resp = "skip non-map";
                    }
                } else if ("LAUNCHICON".equals(p[0]) && p.length >= 2) {
                    resp = launchIcon(in, p[1]);
                } else if ("MATCHDBG".equals(p[0])) {
                    Object[] r = matchIcon("/data/local/tmp/card_shot.png", "/data/local/tmp/card_icon.png");
                    resp = r == null ? "ERR decode" : "dbg x=" + r[0] + " y=" + r[3] + " score=" + String.format("%.4f", r[1]);
                } else {
                    resp = "ERR unknown command";
                }
                out.write((resp.replaceAll("[\\r\\n]+", " | ") + "\n").getBytes());
                out.flush();
                s.close();
            } catch (Exception e) {
                try { s.close(); } catch (Exception ignore) {}
            }
        }
    }

    /** 手工按字节读一行, 避免 BufferedReader 预读吃掉后续二进制载荷 */
    private static String readLine(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
            if (bo.size() > 4096) break;
        }
        if (!any && c == -1) return null;
        return bo.toString("UTF-8");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) {}
    }

    /**
     * 原生启动: 图标像素匹配 + 真实点击。
     * 应用先回桌面再开应用网格, 逐页截图匹配应用图标, 命中后 tap 图标中心。
     * 后续窗口形态 (continuity 投影 / multi-landscape 安全区 / 游戏全屏) 全部交给系统。
     */
    private static String launchIcon(InputStream in, String lenStr) {
        try {
            int len = Integer.parseInt(lenStr.replaceAll("[^0-9]", ""));
            if (len <= 0 || len > 4 * 1024 * 1024) return "ERR bad length";
            byte[] png = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(png, off, len - off);
                if (n < 0) return "ERR payload truncated";
                off += n;
            }
            FileOutputStream fo = new FileOutputStream("/data/local/tmp/card_icon.png");
            fo.write(png);
            fo.close();

            String sfid = carScreenSfId();
            if (sfid == null) return "ERR no vivo_car_screen";
            exec("am start -n com.vivo.carlauncher/.activity.CarLauncherActivity --display 90000");
            sleep(900);
            exec("screencap -d " + sfid + " /data/local/tmp/card_shot.png");
            int[] wh = pngSize("/data/local/tmp/card_shot.png");
            if (wh == null) { // 屏幕重连后 id 可能已变: 重解析一次
                sfid = carScreenSfId();
                if (sfid == null) return "ERR no vivo_car_screen";
                exec("screencap -d " + sfid + " /data/local/tmp/card_shot.png");
                wh = pngSize("/data/local/tmp/card_shot.png");
            }
            if (wh == null) return "ERR screencap failed";
            int W = wh[0], H = wh[1];
            int gx = (int) (W * 0.0326), gy = (int) (H * 0.9633);
            exec("input -d 90000 tap " + gx + " " + gy);
            sleep(1500);

            float bestOverall = 1f;
            for (int pass = 0; pass < 2; pass++) {
                int pages = pass == 0 ? 6 : 3;
                for (int page = 0; page < pages; page++) {
                    exec("screencap -d " + sfid + " /data/local/tmp/card_shot.png");
                    Object[] r = matchIcon("/data/local/tmp/card_shot.png", "/data/local/tmp/card_icon.png");
                    float score = r == null ? 1f : ((Float) r[1]).floatValue();
                    if (score < bestOverall) bestOverall = score;
                    if (r != null && score < 0.15f) {
                        int div = ((Integer) r[2]).intValue();
                        int tsz = ((Integer) r[4]).intValue();
                        int tx = (((Integer) r[0]).intValue() + tsz / 2) * div;
                        int ty = (((Integer) r[3]).intValue() + tsz / 2) * div;
                        exec("input -d 90000 tap " + tx + " " + ty);
                        return "OK tapped " + tx + "," + ty + " score=" + String.format("%.3f", score);
                    }
                    if (page < pages - 1) {
                        exec("input -d 90000 swipe " + (int) (W * 0.82) + " " + (int) (H * 0.45)
                                + " " + (int) (W * 0.18) + " " + (int) (H * 0.45) + " 220");
                        sleep(1000);
                    }
                }
                if (pass == 0) {
                    // 网格可能被上次点击关掉/翻到尾页: 再按一次网格按钮, 重扫
                    exec("am start -n com.vivo.carlauncher/.activity.CarLauncherActivity --display 90000");
                    sleep(700);
                    exec("input -d 90000 tap " + gx + " " + gy);
                    sleep(1500);
                }
            }
            // 失败收场: BACK 收起网格, 免得盖住后续回落的直启应用
            exec("input -d 90000 keyevent 4");
            sleep(600);
            return "ERR iconnotfound bestscore=" + String.format("%.3f", bestOverall);
        } catch (Exception e) {
            return "ERR " + e;
        }
    }

    /** vivo_car_screen 的 SurfaceFlinger 显示 id (screencap -d 用)。每次调用重新解析:
     *  车机屏重连后 SF 会重建虚拟屏并换 id, 不能缓存。 */
    private static String carScreenSfId() {
        String r = exec("dumpsys SurfaceFlinger --display-id | awk '/displayName=\"vivo_car_screen\"$/{print $2; exit}'");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{8,}").matcher(r);
        return m.find() ? m.group() : null;
    }

    /** 读 PNG IHDR 宽高 (字节 16-23) */
    private static int[] pngSize(String path) {
        try {
            FileInputStream fi = new FileInputStream(path);
            byte[] h = new byte[24];
            int n = fi.read(h);
            fi.close();
            if (n < 24) return null;
            int w = ((h[16] & 255) << 24) | ((h[17] & 255) << 16) | ((h[18] & 255) << 8) | (h[19] & 255);
            int hh = ((h[20] & 255) << 24) | ((h[21] & 255) << 16) | ((h[22] & 255) << 8) | (h[23] & 255);
            return new int[]{w, hh};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在截图中匹配图标。两级: 1/8 粗扫多形态模板 (legacy 全出血 / adaptive 前景 58-74%),
     * 再对每形态的粗扫最优位置在 1/2 尺度全采样精扫, 拉开真图标与噪声的分数差。
     * 返回 [x, bestScore, div, y, tsz], 分数 = 平均绝对色差 (0-1), 位置为模板左上角。
     */
    private static Object[] matchIcon(String shotPath, String iconPath) {
        try {
            android.graphics.Bitmap shot = android.graphics.BitmapFactory.decodeFile(shotPath);
            android.graphics.Bitmap icon = android.graphics.BitmapFactory.decodeFile(iconPath);
            if (shot == null || icon == null) return null;
            float[] scales = {1f, 0.58f, 0.66f, 0.74f};
            int corner = icon.getPixel(4, icon.getHeight() / 2);

            // ---- 粗扫 1/8 ----
            int div = 8;
            int tsz = Math.max(24, Math.round(shot.getWidth() * 0.075f / div));
            android.graphics.Bitmap S = android.graphics.Bitmap.createScaledBitmap(
                    shot, shot.getWidth() / div, shot.getHeight() / div, true);
            int sw = S.getWidth(), sh = S.getHeight();
            int[] sp = new int[sw * sh];
            S.getPixels(sp, 0, sw, 0, 0, sw, sh);
            int m = Math.max(2, tsz / 7);
            int maxY = (int) (0.86f * sh) - tsz;
            int vCount = scales.length;
            float[] cBest = new float[vCount];
            int[][] cPos = new int[vCount][2];
            java.util.Arrays.fill(cBest, 1f);
            int[][] variants = new int[vCount][tsz * tsz];
            for (int v = 0; v < vCount; v++) {
                android.graphics.Bitmap tile = renderTile(icon, corner, scales[v], tsz);
                tile.getPixels(variants[v], 0, tsz, 0, 0, tsz, tsz);
                int[] tp = variants[v];
                for (int y = tsz / 2; y < maxY; y += 2) {
                    for (int x = 0; x <= sw - tsz; x += 2) {
                        float score = diffAt(sp, sw, x, y - tsz / 2, tp, tsz, m);
                        if (score < cBest[v]) {
                            cBest[v] = score;
                            cPos[v][0] = x;
                            cPos[v][1] = y - tsz / 2;
                        }
                    }
                }
            }

            // ---- 精扫 1/2 (窗口 = 粗扫位置 ±12) ----
            int div2 = 2;
            int tsz2 = Math.max(96, Math.round(shot.getWidth() * 0.075f / div2));
            android.graphics.Bitmap S2 = android.graphics.Bitmap.createScaledBitmap(
                    shot, shot.getWidth() / div2, shot.getHeight() / div2, true);
            int sw2 = S2.getWidth(), sh2 = S2.getHeight();
            int[] sp2 = new int[sw2 * sh2];
            S2.getPixels(sp2, 0, sw2, 0, 0, sw2, sh2);
            int m2 = Math.max(8, tsz2 / 10);
            float best = 1f;
            int bx = 0, by = 0;
            for (int v = 0; v < vCount; v++) {
                android.graphics.Bitmap tile = renderTile(icon, corner, scales[v], tsz2);
                int[] tp = new int[tsz2 * tsz2];
                tile.getPixels(tp, 0, tsz2, 0, 0, tsz2, tsz2);
                int cx = cPos[v][0] * (div / div2), cy = cPos[v][1] * (div / div2);
                for (int dy = -12; dy <= 12; dy += 2) {
                    int y = cy + dy;
                    if (y < 0 || y + tsz2 > sh2 || y > (int) (0.88f * sh2) - tsz2) continue;
                    for (int dx = -12; dx <= 12; dx += 2) {
                        int x = cx + dx;
                        if (x < 0 || x + tsz2 > sw2) continue;
                        float score = diffAt(sp2, sw2, x, y, tp, tsz2, m2);
                        if (score < best) {
                            best = score;
                            bx = x;
                            by = y;
                        }
                    }
                }
            }
            return new Object[]{bx, best, div2, by, tsz2};
        } catch (Throwable t) {
            log("matchIcon error " + t);
            return null;
        }
    }

    /** 模板渲染: scale=1 全出血, 否则底色铺满 + 前景按 scale 居中 (模拟 adaptive 渲染) */
    private static android.graphics.Bitmap renderTile(android.graphics.Bitmap icon, int corner,
                                                      float scale, int tsz) {
        android.graphics.Bitmap tile = android.graphics.Bitmap.createBitmap(tsz, tsz,
                android.graphics.Bitmap.Config.ARGB_8888);
        tile.eraseColor(corner);
        android.graphics.Canvas c = new android.graphics.Canvas(tile);
        if (scale >= 1f) {
            c.drawBitmap(android.graphics.Bitmap.createScaledBitmap(icon, tsz, tsz, true), 0, 0, null);
        } else {
            int fsz = Math.round(tsz * scale);
            c.drawBitmap(android.graphics.Bitmap.createScaledBitmap(icon, fsz, fsz, true),
                    (tsz - fsz) / 2f, (tsz - fsz) / 2f, null);
        }
        return tile;
    }

    /** 以 (sx, sy) 为模板左上角, 从大图取中心裁剪区与模板求平均绝对色差 (0-1) */
    private static float diffAt(int[] sp, int sw, int sx, int sy, int[] tp, int tsz, int m) {
        long sum = 0;
        int cnt = 0;
        for (int ty = m; ty < tsz - m; ty += 2) {
            int srow = (sy + ty) * sw + sx + m;
            int trow = ty * tsz + m;
            for (int tx = m; tx < tsz - m; tx += 2) {
                int c1 = sp[srow + (tx - m)];
                int c2 = tp[trow + (tx - m)];
                sum += Math.abs(((c1 >> 16) & 255) - ((c2 >> 16) & 255))
                        + Math.abs(((c1 >> 8) & 255) - ((c2 >> 8) & 255))
                        + Math.abs((c1 & 255) - (c2 & 255));
                cnt++;
            }
        }
        return cnt == 0 ? 1f : (sum / (float) cnt) / 255f;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._$-]", "");
    }

    /** 选应用目标屏: 优先 continuity 应用屏 (无 dock 遮挡), 回落主车机屏 */
    private static String bestDisplay() {
        String r = exec("CID=$(dumpsys display | awk '/mDisplayId=/{id=$2} /mPrimaryDisplayDevice=.*continuity/{if(id!=\"\")print id; exit}'); if [ -n \"$CID\" ]; then echo $CID; else MID=$(dumpsys display | awk '/mDisplayId=/{id=$2} /mPrimaryDisplayDevice=vivo_car_screen\\(/{if(id!=\"\")print id; exit}'); echo ${MID:-90000}; fi");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("900\\d\\d").matcher(r);
        return m.find() ? m.group() : "90000";
    }

    private static String exec(String cmd) {
        try {
            Process pr = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            BufferedReader e = new BufferedReader(new InputStreamReader(pr.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            while ((l = e.readLine()) != null) sb.append("[E]").append(l).append('\n');
            pr.waitFor();
            sb.append("exit=").append(pr.exitValue());
            return sb.toString().trim();
        } catch (Exception ex) {
            return "ERR " + ex;
        }
    }

    private static void log(String s) {
        System.out.println("[CarD] " + s);
    }
}
