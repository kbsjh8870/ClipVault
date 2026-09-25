package com.clipvault.client.network;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link StompFrame}의 문자열 변환(encode)과 해석(decode)이 STOMP 형식에 맞는지 검사한다. */
class StompFrameTest {

    /** 명령어 / 헤더 / 빈 줄 / 본문 / NULL 문자 순서로 정확히 만들어지는지 */
    @Test
    void encodeFormat() {
        StompFrame f = new StompFrame("SEND", Map.of("destination", "/app/x"), "hi");
        assertEquals("SEND\ndestination:/app/x\n\nhi\0", f.encode());
    }

    /** 본문이 없어도 빈 줄과 NULL 문자는 붙어야 한다 */
    @Test
    void encodeWithoutBody() {
        StompFrame f = new StompFrame("SUBSCRIBE", Map.of("id", "0"), "");
        assertEquals("SUBSCRIBE\nid:0\n\n\0", f.encode());
    }

    /** 서버가 보내는 MESSAGE 프레임에서 명령어, 헤더, 본문(JSON)을 제대로 꺼내는지 */
    @Test
    void decodeMessage() {
        StompFrame f = StompFrame.decode("MESSAGE\ndestination:/topic/clips/u1\nsubscription:0\n\n{\"id\":\"1\"}\0");
        assertEquals("MESSAGE", f.command());
        assertEquals("/topic/clips/u1", f.headers().get("destination"));
        assertEquals("0", f.headers().get("subscription"));
        assertEquals("{\"id\":\"1\"}", f.body());
    }

    /** 앞에 하트비트 줄바꿈이 붙어 와도 무시하고 해석하는지 */
    @Test
    void decodeSkipsLeadingHeartBeatNewlines() {
        StompFrame f = StompFrame.decode("\n\n\nCONNECTED\nversion:1.2\n\n\0");
        assertEquals("CONNECTED", f.command());
        assertEquals("1.2", f.headers().get("version"));
    }

    /** 본문이 없으면 null이 아니라 빈 문자열 */
    @Test
    void decodeMissingBodyIsEmptyString() {
        assertEquals("", StompFrame.decode("CONNECTED\nversion:1.2\n\n\0").body());
    }

    /** encode → decode 하면 원래 프레임과 똑같아야 한다 (본문 안의 줄바꿈, 한글 포함) */
    @Test
    void roundTrip() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("destination", "/topic/clips/abc");
        headers.put("content-type", "application/json");
        headers.put("subscription", "sub-0");
        StompFrame original = new StompFrame("MESSAGE", headers, "line1\nline2 한글");
        StompFrame decoded = StompFrame.decode(original.encode());
        assertEquals(original.command(), decoded.command());
        assertEquals(original.headers(), decoded.headers());
        assertEquals(original.body(), decoded.body());
    }
}
