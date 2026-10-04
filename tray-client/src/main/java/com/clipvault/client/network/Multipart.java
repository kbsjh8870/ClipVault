package com.clipvault.client.network;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * multipart/form-data 본문 작성기 (JDK HttpClient에는 없어서 직접 만든다).
 * e2e 이미지 업로드에서 원본·썸네일 암호문(바이너리)과 가로·세로·해시(문자열)를 한 요청에 담는 데 쓴다.
 */
public final class Multipart {
    private final String boundary = "----clipvault" + UUID.randomUUID().toString().replace("-", "");
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** 문자열 필드. */
    public Multipart field(String name, String value) {
        write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
        return this;
    }

    /** 바이너리 파일 파트 (파일 이름 = 파트 이름). */
    public Multipart file(String name, byte[] data) {
        write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        out.writeBytes(data);
        write("\r\n");
        return this;
    }

    /** 끝 표시를 붙인 전체 본문. 한 번만 부를 것. */
    public byte[] body() {
        write("--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    private void write(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
