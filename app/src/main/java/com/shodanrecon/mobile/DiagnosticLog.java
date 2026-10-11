package com.shodanrecon.mobile;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Private, bounded diagnostic event log. Never accepts raw request URLs, credentials,
 * Shodan queries, IP addresses, stack traces, or camera images.
 * A user must explicitly choose a document destination before exporting the log.
 */
final class DiagnosticLog {
    private static final String FILE = "diagnostics-v1.txt";
    private static final int MAX_BYTES = 128 * 1024;
    private static final int KEEP_BYTES = 72 * 1024;
    private static final Pattern HTTP = Pattern.compile("(?i)\\bHTTP\\s*([1-5][0-9]{2})\\b");
    private final File file;

    DiagnosticLog(Context context) {
        file = new File(context.getFilesDir(), FILE);
    }

    static String atom(String value) {
        if (value == null) return "unknown";
        String v = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return v.substring(0, Math.min(48, v.length()));
    }

    /** Extract only the HTTP status or generic failure type, never exception text. */
    static String errorCode(String message) {
        if (message == null) return "unknown";
        Matcher m = HTTP.matcher(message);
        if (m.find()) return "http-" + m.group(1);
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) return "timeout";
        if (lower.contains("ssl") || lower.contains("certificate") || lower.contains("tls")) return "tls";
        if (lower.contains("network") || lower.contains("connection") || lower.contains("unreachable")) return "network";
        if (lower.contains("cancel")) return "cancelled";
        if (lower.contains("permission") || lower.contains("denied")) return "permission";
        if (lower.contains("storage") || lower.contains("disk") || lower.contains("write")) return "storage";
        if (lower.contains("image") || lower.contains("snapshot")) return "image";
        if (lower.contains("credential") || lower.contains("authentication")) return "auth";
        if (lower.contains("shodan")) return "api";
        return "operation-failed";
    }

    synchronized void add(String area, String event, String code) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
        String row = timestamp + " | " + atom(area) + " | " + atom(event) + " | " + atom(code) + "\n";
        try {
            if (file.exists() && file.length() > MAX_BYTES) {
                byte[] bytes = readBytes(MAX_BYTES + 100);
                int start = Math.max(0, bytes.length - KEEP_BYTES);
                while (start < bytes.length && bytes[start] != '\n') start++;
                if (start < bytes.length) start++;
                try (FileOutputStream trim = new FileOutputStream(file, false)) {
                    trim.write(bytes, start, bytes.length - start);
                }
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(row.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { /* Logging must never crash camera or network tasks. */ }
    }

    private byte[] readBytes(int max) throws Exception {
        if (!file.exists()) return new byte[0];
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[max];
            int used = 0, n;
            while (used < buffer.length && (n = in.read(buffer, used, buffer.length - used)) > 0) used += n;
            return Arrays.copyOf(buffer, used);
        }
    }

    synchronized String text() {
        String header = "Shodan Recon diagnostic report\n"
                + "Android SDK " + Build.VERSION.SDK_INT + "\n"
                + "App version 1.2 (diagnostics)\n"
                + "No keys, passwords, full URLs, camera images or target IPs are collected.\n"
                + "Events are stored only on this device until you export them.\n\n";
        try { return header + new String(readBytes(MAX_BYTES + 100), StandardCharsets.UTF_8); }
        catch (Exception e) { return header + "Unable to load saved diagnostics.\n"; }
    }

    synchronized void clear() {
        if (file.exists()) file.delete();
        add("app", "log-cleared", "ok");
    }
}
