package com.clipvault.clip;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/** 이미지 바이트 도우미: 헤더만 읽어 크기 확인, 썸네일 만들기, PNG 인코딩. */
final class ImageBytes {
    private ImageBytes() {
    }

    /**
     * PNG의 가로·세로를 헤더만 읽어서 알아낸다 (픽셀을 풀지 않으므로 거대한 이미지도 안전).
     * PNG가 아니거나 읽을 수 없으면 IllegalArgumentException.
     */
    static Dimension dimensions(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IllegalArgumentException("not an image");
            ImageReader r = readers.next();
            try {
                if (!"png".equalsIgnoreCase(r.getFormatName())) throw new IllegalArgumentException("not a png");
                r.setInput(in);
                return new Dimension(r.getWidth(0), r.getHeight(0));
            } finally {
                r.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("not an image", e);
        }
    }

    /** 긴 변이 max px가 되도록 비율을 유지해 줄인 PNG. 원본이 더 작으면 원본 크기 그대로. */
    static byte[] thumbnail(BufferedImage src, int max) {
        double scale = Math.min(1.0, (double) max / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return png(out);
    }

    /** 이미지를 PNG 바이트로. */
    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
