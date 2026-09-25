package com.clipvault.client.network;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal STOMP 1.2 frame. No header escaping (not needed for our headers). */
public record StompFrame(String command, Map<String, String> headers, String body) {

    public String encode() {
        StringBuilder sb = new StringBuilder(command).append('\n');
        if (headers != null) headers.forEach((k, v) -> sb.append(k).append(':').append(v).append('\n'));
        sb.append('\n');
        if (body != null) sb.append(body);
        return sb.append('\0').toString();
    }

    public static StompFrame decode(String raw) {
        int start = 0;
        while (start < raw.length() && (raw.charAt(start) == '\n' || raw.charAt(start) == '\r')) start++; // heart-beats
        int end = raw.indexOf('\0', start);
        String s = raw.substring(start, end < 0 ? raw.length() : end);

        int nl = s.indexOf('\n');
        String command = stripCr(nl < 0 ? s : s.substring(0, nl));
        Map<String, String> headers = new LinkedHashMap<>();
        int pos = nl < 0 ? s.length() : nl + 1;
        while (pos < s.length()) {
            nl = s.indexOf('\n', pos);
            String line = stripCr(nl < 0 ? s.substring(pos) : s.substring(pos, nl));
            pos = nl < 0 ? s.length() : nl + 1;
            if (line.isEmpty()) break; // blank line: body follows
            int colon = line.indexOf(':');
            if (colon > 0) headers.putIfAbsent(line.substring(0, colon), line.substring(colon + 1)); // first wins (STOMP 1.2)
        }
        return new StompFrame(command, headers, s.substring(Math.min(pos, s.length())));
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
