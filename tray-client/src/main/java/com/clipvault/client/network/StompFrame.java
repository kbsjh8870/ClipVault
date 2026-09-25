package com.clipvault.client.network;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * STOMP 프레임 한 개를 표현하고, 문자열로 만들거나(encode) 문자열에서 읽어 오는(decode) 최소 구현.
 *
 * <p>STOMP 라이브러리를 추가하지 않고, 필요한 만큼만 직접 구현했다. 프레임 모양은 다음과 같다.</p>
 * <pre>
 * COMMAND          ← 첫 줄: 명령어 (CONNECT, SUBSCRIBE, MESSAGE, ERROR ...)
 * key1:value1      ← 헤더들 (한 줄에 하나)
 * key2:value2
 *                  ← 빈 줄: 헤더 끝
 * body             ← 본문 (우리 경우 클립 JSON)
 * \0               ← NULL 문자: 프레임 끝
 * </pre>
 *
 * <p>헤더 값 이스케이프(\c, \n 등)는 구현하지 않았다. 우리가 쓰는 헤더(토큰, 주소 등)에는 콜론/줄바꿈이 없어서 필요 없다.</p>
 *
 * @param command 명령어
 * @param headers 헤더 (순서 유지)
 * @param body    본문 (없으면 빈 문자열)
 */
public record StompFrame(String command, Map<String, String> headers, String body) {

    /** 프레임을 서버로 보낼 문자열로 만든다. */
    public String encode() {
        StringBuilder sb = new StringBuilder(command).append('\n');
        if (headers != null) headers.forEach((k, v) -> sb.append(k).append(':').append(v).append('\n'));
        sb.append('\n'); // 헤더와 본문 사이의 빈 줄
        if (body != null) sb.append(body);
        return sb.append('\0').toString(); // 프레임 끝 표시
    }

    /**
     * 서버에서 받은 문자열을 프레임으로 해석한다.
     *
     * <p>서버가 연결 유지용으로 보내는 빈 줄(하트비트)이 앞에 붙어 있어도 건너뛴다.
     * 윈도우식 줄바꿈(\r\n)도 처리한다.</p>
     */
    public static StompFrame decode(String raw) {
        // 1) 앞쪽의 하트비트 줄바꿈들을 건너뛴다
        int start = 0;
        while (start < raw.length() && (raw.charAt(start) == '\n' || raw.charAt(start) == '\r')) start++;
        // 2) 프레임 끝(\0)까지만 자른다. \0이 없으면 끝까지.
        int end = raw.indexOf('\0', start);
        String s = raw.substring(start, end < 0 ? raw.length() : end);

        // 3) 첫 줄 = 명령어
        int nl = s.indexOf('\n');
        String command = stripCr(nl < 0 ? s : s.substring(0, nl));
        // 4) 빈 줄이 나올 때까지 "키:값" 헤더를 읽는다
        Map<String, String> headers = new LinkedHashMap<>();
        int pos = nl < 0 ? s.length() : nl + 1;
        while (pos < s.length()) {
            nl = s.indexOf('\n', pos);
            String line = stripCr(nl < 0 ? s.substring(pos) : s.substring(pos, nl));
            pos = nl < 0 ? s.length() : nl + 1;
            if (line.isEmpty()) break; // 빈 줄: 여기서부터 본문
            int colon = line.indexOf(':');
            // 같은 헤더가 여러 번 오면 첫 번째 값을 쓴다 (STOMP 1.2 규칙)
            if (colon > 0) headers.putIfAbsent(line.substring(0, colon), line.substring(colon + 1));
        }
        // 5) 나머지 전부가 본문
        return new StompFrame(command, headers, s.substring(Math.min(pos, s.length())));
    }

    /** 줄 끝의 \r(윈도우 줄바꿈의 앞부분)을 떼어 낸다. */
    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
