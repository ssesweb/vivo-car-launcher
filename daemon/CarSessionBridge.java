import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class CarSessionBridge {
    static Object manager;

    static Object call(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        try {
            return target.getClass().getMethod(name, types).invoke(target, values);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getTargetException();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map) {
            List<String> entries = new ArrayList<>();
            for (Object raw : new TreeMap<>((Map<String, Object>) value).entrySet()) {
                Map.Entry<?, ?> item = (Map.Entry<?, ?>) raw;
                entries.add(json(item.getKey().toString()) + ":" + json(item.getValue()));
            }
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable) {
            List<String> entries = new ArrayList<>();
            for (Object item : (Iterable<?>) value) entries.add(json(item));
            return "[" + String.join(",", entries) + "]";
        }
        StringBuilder result = new StringBuilder("\"");
        for (char item : value.toString().toCharArray()) {
            if (item == '"' || item == '\\') result.append('\\').append(item);
            else if (item == '\b') result.append("\\b");
            else if (item == '\f') result.append("\\f");
            else if (item == '\n') result.append("\\n");
            else if (item == '\r') result.append("\\r");
            else if (item == '\t') result.append("\\t");
            else if (item < 32) result.append(String.format("\\u%04x", (int) item));
            else result.append(item);
        }
        return result.append('"').toString();
    }

    static String digest(Object value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(json(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : bytes) result.append(String.format("%02x", item & 255));
        return result.toString();
    }

    static List<Map<String, Object>> displays() throws Exception {
        Object global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
        int[] ids;
        try {
            ids = (int[]) call(global, "getDisplayIds", new Class<?>[]{boolean.class}, false);
        } catch (NoSuchMethodException error) {
            ids = (int[]) call(global, "getDisplayIds", new Class<?>[0]);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int id : ids) {
            Object info = call(global, "getDisplayInfo", new Class<?>[]{int.class}, id);
            if (info == null) continue;
            Map<String, Object> row = new TreeMap<>();
            row.put("id", id);
            for (String key : new String[]{"name", "uniqueId", "logicalWidth", "logicalHeight", "logicalDensityDpi", "type"}) {
                row.put(key, info.getClass().getField(key).get(info));
            }
            if ("vivo_car_screen".equals(row.get("name")) && id > 0 && Integer.valueOf(5).equals(row.get("type"))) {
                Object raw = call(manager, "getMultiLandsAppsForVirtual", new Class<?>[]{int.class}, id);
                if (raw == null) row.put("multilands", null);
                else {
                    Map<String, Boolean> normalized = new TreeMap<>();
                    for (Map.Entry<?, ?> item : ((Map<?, ?>) raw).entrySet()) {
                        if (!(item.getValue() instanceof Boolean)) throw new IllegalStateException("UNEXPECTED_MAP_VALUE");
                        normalized.put(item.getKey().toString(), (Boolean) item.getValue());
                    }
                    row.put("multilands", normalized);
                }
            }
            result.add(row);
        }
        return result;
    }

    static Map<String, Object> onlyCar() throws Exception {
        List<Map<String, Object>> found = new ArrayList<>();
        for (Map<String, Object> row : displays()) {
            if (row.containsKey("multilands")) found.add(row);
        }
        if (found.size() != 1) throw new IllegalStateException("ONE_ACTIVE_CAR_DISPLAY_REQUIRED");
        Map<String, Object> car = found.get(0);
        if (((Number) car.get("logicalWidth")).intValue() < 400 || ((Number) car.get("logicalHeight")).intValue() < 200) {
            throw new IllegalStateException("UNEXPECTED_DISPLAY_SIZE");
        }
        return car;
    }

    static Map<String, Object> inspect() throws Exception {
        Map<String, Object> result = new TreeMap<>();
        result.put("uid", Class.forName("android.os.Process").getMethod("myUid").invoke(null));
        result.put("displays", displays());
        result.put("networkDbReadable", new File("/data/user/0/com.vivo.car.networking/databases/car.db").canRead());
        result.put("launcherDbReadable", new File("/data/user/0/com.vivo.carlauncher/databases/CarLauncher.db").canRead());
        result.put("rulesReadable", new File("/data/user/0/com.vivo.car.networking/files/multi_lands_configs.xml").canRead());
        result.put("status", "READ_ONLY");
        return result;
    }

    static Map<String, Object> set(String[] arguments) throws Exception {
        if (arguments.length != 5) throw new IllegalArgumentException("SET_ARGUMENTS_REQUIRED");
        Map<String, Object> before = onlyCar();
        if (!digest(before).equals(arguments[1])) throw new IllegalStateException("BASELINE_CHANGED");
        String pkg = arguments[2];
        if (!pkg.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) throw new IllegalArgumentException("INVALID_PACKAGE");
        if (!("true".equals(arguments[3]) || "false".equals(arguments[3])) || !("true".equals(arguments[4]) || "false".equals(arguments[4]))) {
            throw new IllegalArgumentException("BOOLEAN_REQUIRED");
        }
        Map<String, Boolean> states = (Map<String, Boolean>) before.get("multilands");
        if (states == null || !states.containsKey(pkg)) throw new IllegalStateException("PACKAGE_NOT_IN_RUNTIME_MAP");
        boolean expected = Boolean.parseBoolean(arguments[3]);
        boolean desired = Boolean.parseBoolean(arguments[4]);
        if (states.get(pkg).booleanValue() != expected) throw new IllegalStateException("PACKAGE_STATE_CHANGED");
        if (expected != desired) {
            Class<?> bundleType = Class.forName("android.os.Bundle");
            Object bundle = bundleType.getConstructor().newInstance();
            call(bundle, "putBoolean", new Class<?>[]{String.class, boolean.class}, "isKillProcess", false);
            call(bundle, "putInt", new Class<?>[]{String.class, int.class}, "displayId", before.get("id"));
            call(bundle, "putString", new Class<?>[]{String.class, String.class}, "packageName", pkg);
            call(bundle, "putBoolean", new Class<?>[]{String.class, boolean.class}, "enable", desired);
            call(manager, "changeMLAppEnableWithBundleForVirtual", new Class<?>[]{bundleType}, bundle);
        }
        Map<String, Object> after = onlyCar();
        Map<String, Object> expectedAfter = new TreeMap<>(before);
        Map<String, Boolean> expectedStates = new TreeMap<>(states);
        expectedStates.put(pkg, desired);
        expectedAfter.put("multilands", expectedStates);
        Map<String, Object> result = new TreeMap<>();
        result.put("before", before);
        result.put("after", after);
        result.put("package", pkg);
        result.put("readbackMatches", json(expectedAfter).equals(json(after)));
        result.put("status", expected == desired ? "ALREADY_REQUESTED_STATE" : "SETTER_RETURNED");
        return result;
    }

    /** 在册应用翻多窗口开关: true->false (全屏), 已是 false 则跳过。读-写-验同进程原子完成。 */
    static Map<String, Object> autoflip(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("AUTOFLIP_ARGUMENTS_REQUIRED");
        String pkg = arguments[1];
        if (!pkg.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) throw new IllegalArgumentException("INVALID_PACKAGE");
        Map<String, Object> before = onlyCar();
        Map<String, Boolean> states = (Map<String, Boolean>) before.get("multilands");
        Map<String, Object> result = new TreeMap<>();
        result.put("package", pkg);
        if (states == null || !states.containsKey(pkg) || !(states.get(pkg) instanceof Boolean)) {
            result.put("status", "NOT_IN_MAP");
            return result;
        }
        if (!states.get(pkg).booleanValue()) {
            result.put("status", "ALREADY_FULLSCREEN");
            return result;
        }
        Class<?> bundleType = Class.forName("android.os.Bundle");
        Object bundle = bundleType.getConstructor().newInstance();
        call(bundle, "putBoolean", new Class<?>[]{String.class, boolean.class}, "isKillProcess", false);
        call(bundle, "putInt", new Class<?>[]{String.class, int.class}, "displayId", before.get("id"));
        call(bundle, "putString", new Class<?>[]{String.class, String.class}, "packageName", pkg);
        call(bundle, "putBoolean", new Class<?>[]{String.class, boolean.class}, "enable", false);
        call(manager, "changeMLAppEnableWithBundleForVirtual", new Class<?>[]{bundleType}, bundle);
        Map<String, Object> after = onlyCar();
        Map<String, Object> expectedAfter = new TreeMap<>(before);
        Map<String, Boolean> expectedStates = new TreeMap<>(states);
        expectedStates.put(pkg, false);
        expectedAfter.put("multilands", expectedStates);
        result.put("readbackMatches", json(expectedAfter).equals(json(after)));
        result.put("status", "SETTER_RETURNED");
        return result;
    }

    public static void main(String[] arguments) {
        try {
            manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            Map<String, Object> result;
            if (arguments.length == 1 && "inspect".equals(arguments[0])) result = inspect();
            else if (arguments.length > 0 && "set".equals(arguments[0])) result = set(arguments);
            else if (arguments.length > 0 && "autoflip".equals(arguments[0])) result = autoflip(arguments);
            else throw new IllegalArgumentException("UNKNOWN_OPERATION");
            System.out.println("CARLINK_JSON " + json(result));
            System.exit(0);
        } catch (Throwable error) {
            Map<String, Object> result = new TreeMap<>();
            result.put("status", "ERROR");
            result.put("error", error.toString());
            System.out.println("CARLINK_JSON " + json(result));
            System.exit(2);
        }
    }
}
