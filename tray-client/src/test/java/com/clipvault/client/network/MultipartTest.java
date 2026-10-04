package com.clipvault.client.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MultipartTest {
    @Test
    void buildsFieldsAndFiles() {
        Multipart m = new Multipart().field("width", "12").file("image", new byte[]{0, (byte) 0xFF, 10});
        String ct = m.contentType();
        assertTrue(ct.startsWith("multipart/form-data; boundary="));
        String boundary = ct.substring(ct.indexOf('=') + 1);
        byte[] body = m.body();
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--" + boundary + "\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"width\"\r\n\r\n12\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"image\"; filename=\"image\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n\u0000ÿ\n\r\n"));
        assertTrue(text.endsWith("--" + boundary + "--\r\n"));
    }
}
