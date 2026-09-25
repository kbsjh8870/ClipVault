package com.clipvault.client.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 트레이 앱 전체의 모양(테마, 색, 글꼴, 아이콘)을 한 곳에서 정하는 클래스.
 *
 * <p>Swing 기본 모양은 오래된 느낌이라 FlatLaf(모던 플랫 테마 라이브러리)를 쓴다.
 * 윈도우의 앱 모드(라이트/다크) 설정을 따라가고, {@code -Dclipvault.theme=dark|light}로 강제할 수도 있다.</p>
 */
public final class Theme {
    /** 브랜드 색(파랑). 기본 버튼, 선택 표시, 배지 등에 쓴다. */
    public static final Color ACCENT = new Color(0x3B82F6);
    /** 위험한 동작(원격 로그아웃)과 에러 문구 색. */
    public static final Color DANGER = new Color(0xE5484D);
    /** 다크 모드인지. setup() 이후에 의미가 있다. */
    public static boolean dark;

    private Theme() {
    }

    /** 앱 시작 시 화면을 만들기 전에 한 번 호출한다. */
    public static void setup() {
        String forced = System.getProperty("clipvault.theme");
        dark = forced != null ? forced.equalsIgnoreCase("dark") : windowsPrefersDark();
        // 테마 전체의 강조색을 브랜드 색으로 바꾼다 (포커스 테두리, 기본 버튼, 선택 색 등이 함께 바뀜)
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#3B82F6"));
        if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();

        // 둥근 모서리
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        // 얇고 둥근 스크롤바
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 10);
        // 한글이 깔끔하게 보이도록 윈도우 기본 한글 글꼴(맑은 고딕)을 쓴다. 없으면 FlatLaf 기본 글꼴 유지.
        if (fontExists("Malgun Gothic")) UIManager.put("defaultFont", new FontUIResource("Malgun Gothic", Font.PLAIN, 13));
    }

    /** 윈도우 설정 > 개인 설정 > 색 > "앱 모드"가 다크인지 레지스트리에서 읽는다. 읽을 수 없으면 라이트. */
    private static boolean windowsPrefersDark() {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            return out.contains("0x0");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean fontExists(String name) {
        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (f.equals(name)) return true;
        }
        return false;
    }

    // --- 색 도우미 ---

    /** 보조 설명 글자 색 (회색). 테마에 따라 밝기가 달라진다. */
    public static Color muted() {
        return UIManager.getColor("Label.disabledForeground");
    }

    /** 목록 항목에 마우스를 올렸을 때의 배경색. */
    public static Color hover() {
        return dark ? new Color(255, 255, 255, 18) : new Color(0, 0, 0, 12);
    }

    /** 선택된 목록 항목의 배경색 (강조색을 옅게). */
    public static Color selected() {
        return new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), dark ? 70 : 40);
    }

    /** 창 테두리 선 색. */
    public static Color border() {
        return UIManager.getColor("Component.borderColor");
    }

    /** 기존 글꼴을 크기/굵기만 바꿔서 돌려준다. */
    public static Font font(float size, int style) {
        return UIManager.getFont("defaultFont").deriveFont(style, size);
    }

    // --- 시간 표시 ---

    /** "방금 전", "3분 전", "2시간 전", "5일 전" 같은 상대 시간. */
    public static String ago(Instant t) {
        if (t == null) return "-";
        long s = Math.max(0, Duration.between(t, Instant.now()).getSeconds());
        if (s < 60) return "방금 전";
        if (s < 3600) return s / 60 + "분 전";
        if (s < 86400) return s / 3600 + "시간 전";
        return s / 86400 + "일 전";
    }

    /** ISO-8601 문자열을 Instant로. 형식이 이상하면 null. */
    public static Instant parse(String iso) {
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    // --- 그림 ---

    /**
     * 앱 아이콘(트레이, 창 아이콘, 로그인 화면 로고 공용). 코드로 그려서 이미지 파일이 필요 없다.
     * 파란 둥근 사각형 위에 흰 클립보드 모양. dot=true면 새 클립 알림용 빨간 점을 찍는다.
     */
    public static BufferedImage appIcon(int size, boolean dot) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float u = size / 32f; // 32x32 기준 좌표를 크기에 맞게 늘리는 배율
        // 배경: 위→아래 파란 그라데이션의 둥근 사각형
        g.setPaint(new GradientPaint(0, 0, new Color(0x60A5FA), 0, size, new Color(0x2563EB)));
        g.fill(new RoundRectangle2D.Float(1 * u, 1 * u, 30 * u, 30 * u, 10 * u, 10 * u));
        // 클립보드 판 (흰 테두리)
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.2f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(9 * u, 8 * u, 14 * u, 17 * u, 4 * u, 4 * u));
        // 위쪽 집게 (채운 둥근 사각형)
        g.fill(new RoundRectangle2D.Float(12.5f * u, 5.5f * u, 7 * u, 5 * u, 2.5f * u, 2.5f * u));
        // 글줄 두 개
        g.setStroke(new BasicStroke(2f * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(Math.round(12.5f * u), Math.round(15 * u), Math.round(19.5f * u), Math.round(15 * u));
        g.drawLine(Math.round(12.5f * u), Math.round(19.5f * u), Math.round(17 * u), Math.round(19.5f * u));
        if (dot) {
            g.setColor(new Color(0xEF4444));
            g.fillOval(Math.round(19 * u), Math.round(19 * u), Math.round(13 * u), Math.round(13 * u));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.5f * u));
            g.drawOval(Math.round(19 * u), Math.round(19 * u), Math.round(13 * u), Math.round(13 * u));
        }
        g.dispose();
        return img;
    }

    /** 둥근 알약 모양 배지. 예: "현재 기기", "이 PC". */
    public static JLabel pill(String text, Color bg, Color fg) {
        JLabel l = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setBackground(bg);
        l.setForeground(fg);
        l.setFont(font(11f, Font.BOLD));
        l.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        return l;
    }

    /** 모서리가 둥근 배경을 칠하는 패널 (목록 항목의 선택/마우스오버 표시용). 배경이 null이면 칠하지 않는다. */
    public static class RoundPanel extends JPanel {
        public Color fill;

        public RoundPanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            if (fill != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(fill);
                g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 12, 12);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
