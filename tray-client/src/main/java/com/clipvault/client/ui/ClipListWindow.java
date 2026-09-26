package com.clipvault.client.ui;

import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * 트레이 아이콘 근처에 뜨는 "최근 클립" 팝업 목록.
 *
 * <p>각 항목은 두 줄짜리 카드다: 첫 줄은 내용 미리보기, 둘째 줄은 "3분 전 · 다른 기기" 같은 정보.
 * 항목을 클릭하거나 방향키로 고른 뒤 Enter를 누르면 그 텍스트가 로컬 클립보드에 복사된다(바로 Ctrl+V 가능).
 * 항목에 마우스를 올리면 오른쪽에 휴지통 아이콘이 나타나고, 그걸 누르거나 Delete 키를 누르면 서버에서 삭제된다.
 * Esc를 누르거나 다른 곳을 클릭하면(포커스를 잃으면) 닫힌다.</p>
 *
 * <p>화면 스레드(EDT)에서 호출해야 한다.</p>
 */
public class ClipListWindow {
    /** 현재 떠 있는 팝업. 트레이를 여러 번 클릭해도 팝업이 하나만 뜨도록 기억해 둔다. */
    private static JDialog open;

    private static final int WIDTH = 380;

    /**
     * 썸네일 공급자. 캐시에 있으면 바로 돌려주고, 없으면 null을 돌려주면서 백그라운드로 받아 온 뒤 onReady를 부른다
     * (onReady는 목록을 다시 그리게 한다).
     */
    @FunctionalInterface
    public interface Thumbs {
        Image get(String clipId, Runnable onReady);
    }

    /**
     * 팝업을 띄운다.
     *
     * @param clips      서버에서 받은 클립 목록(JSON 배열, 최신순)
     * @param myDeviceId 이 PC의 기기 ID ("이 PC"/"다른 기기" 표시용)
     * @param onPick     사용자가 고른 클립(JSON 전체) - 텍스트/이미지에 따라 클립보드에 넣는 일은 호출한 쪽이 한다
     * @param onDelete   사용자가 항목을 삭제했을 때 호출 (삭제한 클립 전달) - 서버 삭제 요청은 호출한 쪽이 한다.
     *                   목록에서는 즉시 빠진다(서버 응답을 기다리지 않음)
     * @param thumbs     이미지 클립의 썸네일을 가져오는 함수 (Task 10에서 렌더러에 연결)
     */
    public static void show(JsonNode clips, String myDeviceId, Consumer<JsonNode> onPick, Consumer<JsonNode> onDelete,
                             Thumbs thumbs) {
        if (open != null) open.dispose(); // 이미 떠 있던 팝업은 닫고 새로 띄운다
        DefaultListModel<JsonNode> model = new DefaultListModel<>();
        for (JsonNode c : clips) model.addElement(c);

        JList<JsonNode> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        // hover[0] = 마우스가 올라가 있는 항목 번호 (-1 = 없음), hover[1] = 1이면 그 항목의 휴지통 위에 있음
        int[] hover = {-1, 0};
        list.setCellRenderer(new ClipCell(myDeviceId, hover, thumbs, list::repaint));
        // 칸 너비를 고정해야 긴 텍스트가 가로 스크롤을 만들지 않고 "…"으로 잘린다
        list.setFixedCellWidth(WIDTH - 24);

        JDialog d = new JDialog((Frame) null, "최근 클립");
        open = d;
        d.setUndecorated(true); // 제목 표시줄 없는 팝업 모양
        d.setAlwaysOnTop(true); // 다른 창 위에 표시
        // 선택 처리: 팝업을 닫고 선택한 텍스트를 넘긴다
        Runnable pick = () -> {
            JsonNode c = list.getSelectedValue();
            d.dispose();
            if (c != null) onPick.accept(c);
        };
        JLabel count = Theme.pill(model.size() + "개", Theme.selected(), Theme.ACCENT);
        JPanel root = new JPanel(new BorderLayout());
        // 삭제 처리: 목록에서 바로 빼고, 개수 배지를 고치고, 호출한 쪽에 알린다. 다 지우면 "비어 있음" 화면으로 바꾼다.
        java.util.function.IntConsumer delete = i -> {
            if (i < 0 || i >= model.size()) return;
            JsonNode c = model.remove(i);
            hover[0] = -1;
            onDelete.accept(c);
            count.setText(model.size() + "개");
            if (model.isEmpty()) {
                int bottom = d.getY() + d.getHeight(); // 팝업 아래쪽(트레이 쪽) 위치를 유지한 채 크기만 줄인다
                root.remove(1);
                root.add(emptyState(), BorderLayout.CENTER);
                count.getParent().setVisible(false);
                d.pack();
                d.setLocation(d.getX(), bottom - d.getHeight());
            } else {
                list.setSelectedIndex(Math.min(i, model.size() - 1)); // 키보드로 연달아 지울 수 있게 다음 항목 선택
            }
        };
        MouseAdapter mouse = new MouseAdapter() {
            /** 클릭 = 선택, 단 휴지통 영역(오른쪽 끝)을 누르면 삭제 (빈 공간 클릭은 무시) */
            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i < 0) return;
                if (overTrash(list, i, e.getPoint())) delete.accept(i); else pick.run();
            }

            /** 마우스가 움직이면 올라가 있는 항목(과 휴지통 위인지)을 기억해서 배경과 아이콘을 칠한다 */
            @Override public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                int t = i >= 0 && overTrash(list, i, e.getPoint()) ? 1 : 0;
                if (i != hover[0] || t != hover[1]) { hover[0] = i; hover[1] = t; list.repaint(); }
                list.setToolTipText(t == 1 ? "삭제" : null);
            }

            @Override public void mouseExited(MouseEvent e) {
                hover[0] = -1;
                list.repaint();
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        // Enter = 선택
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pick");
        list.getActionMap().put("pick", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { pick.run(); }
        });
        // Delete = 선택한 항목 삭제
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        list.getActionMap().put("delete", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { delete.accept(list.getSelectedIndex()); }
        });
        // Esc = 닫기
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        list.getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { d.dispose(); }
        });
        // 팝업 밖을 클릭해서 포커스를 잃으면 닫기
        d.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { d.dispose(); }
        });

        // ----- 머리말: "최근 클립" + 개수, 안내 문구 -----
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        JLabel title = new JLabel("최근 클립");
        title.setFont(Theme.font(15f, Font.BOLD));
        title.setIcon(new ImageIcon(Theme.appIcon(18, false)));
        title.setIconTextGap(8);
        header.add(title, BorderLayout.WEST);
        if (!model.isEmpty()) {
            JPanel right = new JPanel(new GridBagLayout()); // 세로 가운데 정렬용
            right.setOpaque(false);
            right.add(count);
            header.add(right, BorderLayout.EAST);
        }
        JLabel hint = new JLabel("클릭: 복사   ·   휴지통 / Delete 키: 삭제");
        hint.setFont(Theme.font(11f, Font.PLAIN));
        hint.setForeground(Theme.muted());
        hint.setBorder(BorderFactory.createEmptyBorder(4, 26, 0, 0));
        header.add(hint, BorderLayout.SOUTH);

        // ----- 본문: 목록 또는 "비어 있음" 안내 -----
        JComponent body;
        if (model.isEmpty()) {
            body = emptyState();
        } else {
            JScrollPane scroll = new JScrollPane(list);
            scroll.setBorder(BorderFactory.createEmptyBorder(0, 6, 8, 6));
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            // 목록 실제 높이만큼 늘리되 최대 420px, 그 이상은 세로 스크롤
            int h = Math.min(list.getPreferredSize().height, 420);
            scroll.setPreferredSize(new Dimension(WIDTH, h + 10));
            body = scroll;
        }

        root.setBorder(BorderFactory.createLineBorder(Theme.border())); // 팝업 가장자리 얇은 선
        root.add(header, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        d.setContentPane(root);
        d.pack();

        // 위치: 마우스 포인터(= 방금 클릭한 트레이 아이콘) 바로 위에 띄우되, 화면 밖이나 작업표시줄 위로 삐져나가지 않게 조정
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle screen = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc); // 작업표시줄이 차지하는 영역
        Point m = MouseInfo.getPointerInfo() != null ? MouseInfo.getPointerInfo().getLocation() : new Point(screen.width, screen.height);
        // 가로: 마우스 중심 기준, 화면 좌우 경계 안으로 제한
        int x = Math.max(screen.x + in.left, Math.min(m.x - d.getWidth() / 2, screen.x + screen.width - in.right - d.getWidth()));
        // 세로: 마우스 위쪽에 붙이되, 화면 위아래 경계 안으로 제한
        int y = Math.max(screen.y + in.top, Math.min(m.y - d.getHeight(), screen.y + screen.height - in.bottom - d.getHeight()));
        d.setLocation(x, y);
        d.setVisible(true);
        d.toFront();
        list.requestFocusInWindow(); // 바로 방향키/Enter로 조작할 수 있게 포커스
    }

    /** 휴지통 영역 너비(px). 각 칸의 오른쪽 끝 이만큼이 삭제 버튼 역할을 한다. */
    private static final int TRASH_W = 40;

    /** 마우스 위치가 i번째 칸의 휴지통 영역(오른쪽 끝) 안인지. */
    private static boolean overTrash(JList<?> list, int i, Point p) {
        Rectangle r = list.getCellBounds(i, i);
        return r != null && r.contains(p) && p.x >= r.x + r.width - TRASH_W;
    }

    /** 코드로 그린 작은 휴지통 아이콘. 색은 상황에 따라(평소 회색, 마우스 올리면 빨강) 바꿔 그린다. */
    private static final class TrashIcon implements Icon {
        Color color = Color.GRAY;

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x + 2, y + 4, x + 14, y + 4);        // 뚜껑
            g2.drawLine(x + 6, y + 4, x + 6, y + 2);         // 손잡이
            g2.drawLine(x + 6, y + 2, x + 10, y + 2);
            g2.drawLine(x + 10, y + 2, x + 10, y + 4);
            g2.drawRoundRect(x + 4, y + 5, 8, 10, 2, 2);     // 통
            g2.drawLine(x + 7, y + 8, x + 7, y + 12);        // 세로줄
            g2.drawLine(x + 9, y + 8, x + 9, y + 12);
            g2.dispose();
        }

        @Override public int getIconWidth() { return 16; }

        @Override public int getIconHeight() { return 16; }
    }

    /**
     * 썸네일 아이콘. 원본 비율대로 최대 220×90 안에 맞춰 그리고, 썸네일이 아직 없으면 같은 크기의 회색 자리를 그린다.
     * 받기 전후 크기가 같아서 목록이 덜컥거리지 않는다.
     */
    private static final class ThumbIcon implements Icon {
        static final int MAX_W = 220, MAX_H = 90;
        Image image;
        int w = MAX_W, h = MAX_H;

        /** 그릴 이미지(없으면 null)와 원본 크기로 표시 크기를 정한다. */
        void set(Image image, int srcW, int srcH) {
            this.image = image;
            double s = Math.min(1.0, Math.min((double) MAX_W / Math.max(1, srcW), (double) MAX_H / Math.max(1, srcH)));
            w = Math.max(1, (int) Math.round(srcW * s));
            h = Math.max(1, (int) Math.round(srcH * s));
        }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.clip(new RoundRectangle2D.Float(x, y, w, h, 8, 8)); // 모서리를 둥글게
            if (image == null) {
                g2.setColor(Theme.border());
                g2.fillRect(x, y, w, h);
            } else {
                g2.drawImage(image, x, y, w, h, null);
            }
            g2.dispose();
        }

        @Override public int getIconWidth() { return w; }

        @Override public int getIconHeight() { return h; }
    }

    /** 클립이 하나도 없을 때 보여 줄 안내. */
    private static JComponent emptyState() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(24, 16, 32, 16));
        p.setPreferredSize(new Dimension(WIDTH, 150));
        JLabel big = new JLabel("아직 클립이 없어요");
        big.setFont(Theme.font(14f, Font.BOLD));
        JLabel small = new JLabel("다른 PC에서 텍스트나 이미지를 복사(Ctrl+C)해 보세요");
        small.setForeground(Theme.muted());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        p.add(new JLabel(new ImageIcon(Theme.appIcon(40, false))), g);
        g.insets = new Insets(12, 0, 0, 0);
        p.add(big, g);
        g.insets = new Insets(4, 0, 0, 0);
        p.add(small, g);
        return p;
    }

    /**
     * 목록 한 칸을 그리는 렌더러. JList는 칸마다 이 컴포넌트를 "도장"처럼 재사용해서 그린다.
     *
     * <pre>
     * ┌──────────────────────────────────────┐
     * │ 회의 링크 https://meet.example.com/…  │  ← 내용 (줄바꿈은 공백으로, 길면 …)
     * │ ┌────────────┐                        │
     * │ │  썸네일      │                        │  ← 이미지 클립: 썸네일 (받기 전엔 회색 자리)
     * │ └────────────┘                        │
     * │ 이미지 · 1920×1080  ·  3분 전 · [다른 기기] │
     * └──────────────────────────────────────┘
     * </pre>
     */
    private static final class ClipCell implements ListCellRenderer<JsonNode> {
        private final String myDeviceId;
        private final int[] hover;
        private final Theme.RoundPanel panel = new Theme.RoundPanel(new BorderLayout(0, 4));
        private final JLabel text = new JLabel();
        private final JLabel time = new JLabel();
        private final JLabel mine = Theme.pill("이 PC", Theme.dark ? new Color(0x3F3F46) : new Color(0xE4E4E7), Theme.muted());
        private final JLabel other = Theme.pill("다른 기기", Theme.selected(), Theme.ACCENT);
        private final JPanel meta = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        private final TrashIcon trashIcon = new TrashIcon();
        /** 오른쪽 휴지통 자리. 마우스가 올라간 칸에서만 아이콘을 보여 주고, 평소엔 빈 자리로 두어 글자 폭이 흔들리지 않게 한다. */
        private final JLabel trash = new JLabel();
        private final Thumbs thumbs;
        private final Runnable repaint;
        private final ThumbIcon thumbIcon = new ThumbIcon();

        ClipCell(String myDeviceId, int[] hover, Thumbs thumbs, Runnable repaint) {
            this.myDeviceId = myDeviceId;
            this.hover = hover;
            this.thumbs = thumbs;
            this.repaint = repaint;
            panel.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
            text.setFont(Theme.font(13f, Font.PLAIN));
            time.setFont(Theme.font(11f, Font.PLAIN));
            time.setForeground(Theme.muted());
            meta.setOpaque(false);
            ((FlowLayout) meta.getLayout()).setHgap(0);
            JPanel textCol = new JPanel(new BorderLayout(0, 4));
            textCol.setOpaque(false);
            textCol.add(text, BorderLayout.CENTER);
            textCol.add(meta, BorderLayout.SOUTH);
            trash.setPreferredSize(new Dimension(TRASH_W - 14, 16));
            trash.setHorizontalAlignment(SwingConstants.RIGHT);
            panel.add(textCol, BorderLayout.CENTER);
            panel.add(trash, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends JsonNode> list, JsonNode clip, int index,
                                                      boolean selected, boolean focus) {
            String ago = Theme.ago(Theme.parse(clip.path("createdAt").asText()));
            if ("IMAGE".equals(clip.path("type").asText())) {
                // 이미지: 썸네일(없으면 회색 자리, 받아지면 repaint로 다시 그려짐) + "이미지 · W×H"
                int w = clip.path("width").asInt(), h = clip.path("height").asInt();
                thumbIcon.set(thumbs.get(clip.path("id").asText(), repaint), w, h);
                text.setText(null);
                text.setIcon(thumbIcon);
                time.setText("이미지 · " + w + "×" + h + "  ·  " + ago + "  ·  ");
            } else {
                // 줄바꿈/연속 공백을 한 칸으로 합치고, 길면 잘라서 "…" (실제로 복사되는 값은 원문 그대로)
                String s = clip.path("content").asText().replaceAll("\\s+", " ").strip();
                text.setIcon(null);
                text.setText(s.length() > 48 ? s.substring(0, 48) + "…" : s);
                time.setText(ago + "  ·  ");
            }
            text.setForeground(list.getForeground());
            boolean fromMe = clip.path("sourceDeviceId").asText().equals(myDeviceId);
            meta.removeAll();
            meta.add(time);
            meta.add(fromMe ? mine : other);
            // 휴지통: 마우스가 올라간 칸에만 표시, 휴지통 바로 위면 빨간색
            boolean hovered = index == hover[0];
            trashIcon.color = hovered && hover[1] == 1 ? Theme.DANGER : Theme.muted();
            trash.setIcon(hovered ? trashIcon : null);
            // 선택 > 마우스오버 > 없음 순서로 배경 결정
            panel.fill = selected ? Theme.selected() : index == hover[0] ? Theme.hover() : null;
            return panel;
        }
    }
}
