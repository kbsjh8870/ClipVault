package com.clipvault.client.clipboard;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 클립보드 이미지 도우미: 형식 통일(ARGB), 같은 이미지인지 판정할 키, PNG 변환. */
public final class Images {
    /** 서버가 받는 최대 크기 (PNG 바이트). 넘으면 업로드하지 않는다. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private Images() {
    }

    /** 어떤 이미지든 픽셀 형식을 ARGB(투명도 포함 32비트)로 통일한다. 키 계산이 형식에 따라 달라지지 않도록. */
    public static BufferedImage toArgb(Image img) {
        if (img instanceof BufferedImage b && b.getType() == BufferedImage.TYPE_INT_ARGB) return b;
        BufferedImage out = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** 가로·세로와 모든 픽셀의 SHA-256. 픽셀이 하나라도 다르면 다른 키. */
    public static String key(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = ByteBuffer.allocate(8 + px.length * 4);
        buf.putInt(w).putInt(h);
        buf.asIntBuffer().put(px);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buf.array()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PNG 바이트로. */
    public static byte[] toPng(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PNG 바이트를 이미지로. 이미지가 아니면 UncheckedIOException. */
    public static BufferedImage fromPng(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) throw new IOException("not an image");
            return img;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
