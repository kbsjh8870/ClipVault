package com.clipvault.client.network;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StompFrameTest {

    @Test
    void encodeFormat() {
        StompFrame f = new StompFrame("SEND", Map.of("destination", "/app/x"), "hi");
        assertEquals("SEND\ndestination:/app/x\n\nhi\0", f.encode());
    }

    @Test
    void encodeWithoutBody() {
        StompFrame f = new StompFrame("SUBSCRIBE", Map.of("id", "0"), "");
        assertEquals("SUBSCRIBE\nid:0\n\n\0", f.encode());
    }

    @Test
    void decodeMessage() {
        StompFrame f = StompFrame.decode("MESSAGE\ndestination:/topic/clips/u1\nsubscription:0\n\n{\"id\":\"1\"}\0");
        assertEquals("MESSAGE", f.command());
        assertEquals("/topic/clips/u1", f.headers().get("destination"));
        assertEquals("0", f.headers().get("subscription"));
        assertEquals("{\"id\":\"1\"}", f.body());
    }

    @Test
    void decodeSkipsLeadingHeartBeatNewlines() {
        StompFrame f = StompFrame.decode("\n\n\nCONNECTED\nversion:1.2\n\n\0");
        assertEquals("CONNECTED", f.command());
        assertEquals("1.2", f.headers().get("version"));
    }

    @Test
    void decodeMissingBodyIsEmptyString() {
        assertEquals("", StompFrame.decode("CONNECTED\nversion:1.2\n\n\0").body());
    }

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
