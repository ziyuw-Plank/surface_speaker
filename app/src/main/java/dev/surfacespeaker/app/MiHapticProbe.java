package dev.surfacespeaker.app;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Optional Xiaomi/HyperOS proprietary interface probe. Small one-shot test only. */
final class MiHapticProbe {
    private MiHapticProbe() {}
    private static Object player;
    static String classReport() {
        StringBuilder out = new StringBuilder();
        for (String name : new String[] {
                "android.os.DynamicEffect", "android.os.HapticPlayer",
                "miui.os.DynamicEffect", "miui.util.HapticFeedbackUtil",
                "com.immersion.Vibetonz", "android.os.RichTapVibrationEffect"}) {
            try {
                Class<?> klass = Class.forName(name);
                out.append(name).append(": 存在 (methods=").append(klass.getDeclaredMethods().length).append(")\n");
            } catch (Throwable e) {
                out.append(name).append(": 无法访问\n");
            }
        }
        return out.toString();
    }
    static String shortTest() {
        try {
            stop();
            Class<?> effectType = Class.forName("android.os.DynamicEffect");
            Class<?> playerType = Class.forName("android.os.HapticPlayer");
            // 120 ms, low intensity, single HE event. Frequency parameter is a normalized
            // vendor control value, not physical audio frequency in hertz.
            String he = "{\"Metadata\":{\"Version\":1,\"Created\":\"2026-10-10\",\"Description\":\"low energy probe\"},"
                    + "\"Pattern\":[{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":0,\"Duration\":120,"
                    + "\"Parameters\":{\"Intensity\":15,\"Frequency\":45,\"Curve\":["
                    + "{\"Time\":0,\"Intensity\":0,\"Frequency\":45},"
                    + "{\"Time\":60,\"Intensity\":0.15,\"Frequency\":45},"
                    + "{\"Time\":120,\"Intensity\":0,\"Frequency\":45}]}}}]}";
            Method create = effectType.getMethod("create", String.class);
            Object effect = create.invoke(null, he);
            Constructor<?> constructor = playerType.getConstructor(effectType);
            player = constructor.newInstance(effect);
            Method start = playerType.getMethod("start");
            start.invoke(player);
            return "已通过厂商 HapticPlayer 发送 120ms 低强度测试。若无振动，可能被 HyperOS 权限/隐藏 API 拦截。";
        } catch (Throwable e) {
            player = null;
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            return "厂商接口未能播放：" + root.getClass().getSimpleName() + " " + root.getMessage();
        }
    }
    static void stop() {
        if (player != null) {
            try { player.getClass().getMethod("stop").invoke(player); } catch (Throwable ignored) {}
            player = null;
        }
    }
}
