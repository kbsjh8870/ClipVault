package com.clipvault.client.clipboard;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class ImagesTest {
    /** w×h를 한 색으로 칠한 이미지 */
    private static BufferedImage solid(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        return img;
    }

    @Test
    void samePixelsGiveSameKey() {
        assertEquals(Images.key(solid(4, 3, 0xFF112233)), Images.key(solid(4, 3, 0xFF112233)));
    }

    @Test
    void differentPixelOrSizeGivesDifferentKey() {
        BufferedImage a = solid(4, 3, 0xFF112233);
        BufferedImage b = solid(4, 3, 0xFF112233);
        b.setRGB(0, 0, 0xFF000000);
        assertNotEquals(Images.key(a), Images.key(b));
        // 픽셀 수와 색이 같아도 모양이 다르면 다른 이미지
        assertNotEquals(Images.key(solid(4, 3, 0xFF112233)), Images.key(solid(3, 4, 0xFF112233)));
    }

    @Test
    void toArgbConvertsRgbImages() {
        BufferedImage rgb = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        rgb.setRGB(1, 1, 0x00ABCDEF);
        BufferedImage argb = Images.toArgb(rgb);
        assertEquals(BufferedImage.TYPE_INT_ARGB, argb.getType());
        assertEquals(0xFFABCDEF, argb.getRGB(1, 1));
    }

    @Test
    void pngRoundTripKeepsPixels() {
        BufferedImage img = solid(5, 7, 0xFF445566);
        img.setRGB(2, 3, 0x80FF0000); // 반투명 픽셀도 보존
        assertEquals(Images.key(img), Images.key(Images.toArgb(Images.fromPng(Images.toPng(img)))));
    }
}
