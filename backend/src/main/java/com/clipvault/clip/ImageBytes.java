package com.clipvault.clip;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
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

    /**
     * 긴 변이 max px가 되도록 비율을 유지해 줄인 PNG. 원본이 더 작으면 원본 크기 그대로.
     *
     * <p>원본 전체를 풀지 않는다. 16비트 RGBA PNG는 픽셀당 8바이트라 5천만 픽셀이면 400MB가 되기 때문에,
     * 읽을 때부터 n픽셀마다 하나씩만 읽어(서브샘플링) 긴 변이 max 근처인 작은 이미지만 메모리에 올린 뒤
     * 정확한 크기로 다시 줄인다. 픽셀 데이터가 깨졌으면 IllegalArgumentException.</p>
     */
    static byte[] thumbnail(byte[] png, int max) {
        BufferedImage src;
        int width, height;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IllegalArgumentException("not an image");
            ImageReader r = readers.next();
            try {
                r.setInput(in);
                width = r.getWidth(0);
                height = r.getHeight(0);
                int n = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) max));
                ImageReadParam param = r.getDefaultReadParam();
                param.setSourceSubsampling(n, n, 0, 0);
                src = r.read(0, param);
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // 헤더는 멀쩡해도 픽셀 데이터가 깨졌으면 여기서 실패한다 (IIOException 등)
            throw new IllegalArgumentException("broken image", e);
        }
        // 원래 크기를 기준으로 목표 크기를 정해야 서브샘플링 반올림 오차 없이 기존과 같은 크기가 나온다
        double scale = Math.min(1.0, (double) max / Math.max(width, height));
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
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
