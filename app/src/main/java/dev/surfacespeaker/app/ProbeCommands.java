package dev.surfacespeaker.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** READ ONLY. Never write to kernel devices from unverified hardware mappings. */
final class ProbeCommands {
    private ProbeCommands() { }
    static final String SCRIPT = String.join("\n",
        "echo '[authorization]'", "id",
        "echo '[device]'", "getprop ro.product.device", "getprop ro.product.model",
        "echo '[kernel]'", "uname -r",
        "echo '[haptic interface candidates — paths only]'",
        "for base in /sys/class/leds /sys/class/timed_output /sys/class/misc /sys/bus/i2c/devices /dev; do",
        "  if [ -d \"$base\" ]; then",
        "    find \"$base\" -maxdepth 4 \\( -iname '*vib*' -o -iname '*haptic*' -o -iname '*awinic*' -o -iname '*aw869*' -o -iname '*richtap*' -o -iname '*cs40*' -o -iname '*dw791*' \\) -print 2>/dev/null | head -60",
        "  fi", "done",
        "echo '[read-only frequency inspection]'",
        "for f in /sys/class/leds/vibrator/device/f0 /sys/class/leds/vibrator/device/resonance /sys/class/leds/vibrator/f0 /sys/class/timed_output/vibrator/f0; do",
        "  if [ -r \"$f\" ]; then echo \"$f\"; head -c 128 \"$f\"; echo; fi",
        "done", "echo '[kernel input devices]'",
        "if [ -r /proc/bus/input/devices ]; then grep -iE 'vib|haptic|awinic' /proc/bus/input/devices | head -30; fi",
        "echo '[report end]'"
    );
    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    static String execute(boolean root) {
        Process p = null;
        try {
            // Only fixed application-controlled commands. No arbitrary user input.
            // Some KernelSU / vendor setups reject direct exec("su") even if the app is authorized.
            // Start through Android's system shell, retaining the exact error for diagnosis.
            String command = root ? "su -c " + quote(SCRIPT) : SCRIPT;
            p = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            while (System.nanoTime() < until && out.length() < 20000) {
                if (br.ready()) {
                    int c = br.read(); if (c < 0) break;
                    out.append((char)c);
                } else if (!p.isAlive()) break;
                else Thread.sleep(25);
            }
            if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly();
            return "exit=" + p.exitValue() + "\n" + out;
        } catch (Exception e) {
            return "root=" + root + "\n" + e.getClass().getSimpleName() + ": " + e.getMessage()
                + "\nCheck KernelSU authorization for package dev.surfacespeaker.lab03.";
        } finally { if (p != null && p.isAlive()) p.destroyForcibly(); }
    }
}