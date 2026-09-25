package com.clipvault.client.ui;

import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

/**
 * 트레이 아이콘 근처에 뜨는 "최근 클립" 팝업 목록.
 *
 * <p>각 항목은 두 줄짜리 카드다: 첫 줄은 내용 미리보기, 둘째 줄은 "3분 전 · 다른 기기" 같은 정보.
 * 항목을 클릭하거나 방향키로 고른 뒤 Enter를 누르면 그 텍스트가 로컬 클립보드에 복사된다(바로 Ctrl+V 가능).
 * Esc를 누르거나 다른 곳을 클릭하면(포커스를 잃으면) 닫힌다.</p>
 *
 * <p>화면 스레드(EDT)에서 호출해야 한다.</p>
 */
public class ClipListWindow {
    /** 현재 떠 있는 팝업. 트레이를 여러 번 클릭해도 팝업이 하나만 뜨도록 기억해 둔다. */
    private static JDialog open;

    private static final int WIDTH = 380;

    /**
     * 팝업을 띄운다.
     *
     * @param clips      서버에서 받은 클립 목록(JSON 배열, 최신순)
     * @param myDeviceId 이 PC의 기기 ID ("이 PC"/"다른 기기" 표시용)
     * @param onPick     사용자가 항목을 골랐을 때 호출 (선택한 텍스트 전달) - 로컬 클립보드에 넣는 일은 호출한 쪽이 한다
     */
    public static void show(JsonNode clips, String myDeviceId, Consumer<String> onPick) {
        if (open != null) open.dispose(); // 이미 떠 있던 팝업은 닫고 새로 띄운다
        DefaultListModel<JsonNode> model = new DefaultListModel<>();
        for (JsonNode c : clips) model.addElement(c);

        JList<JsonNode> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        int[] hover = {-1}; // 마우스가 올라가 있는 항목 번호 (-1 = 없음)
        list.setCellRenderer(new ClipCell(myDeviceId, hover));
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
            if (c != null) onPick.accept(c.path("content").asText());
        };
        MouseAdapter mouse = new MouseAdapter() {
            /** 클릭 = 선택 (빈 공간 클릭은 무시) */
            @Override public void mouseClicked(MouseEvent e) {
                if (list.locationToIndex(e.getPoint()) >= 0) pick.run();
            }

            /** 마우스가 움직이면 올라가 있는 항목을 기억해서 배경을 살짝 칠한다 */
            @Override public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i != hover[0]) { hover[0] = i; list.repaint(); }
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
            JLabel count = Theme.pill(model.size() + "개", Theme.selected(), Theme.ACCENT);
            JPanel right = new JPanel(new GridBagLayout()); // 세로 가운데 정렬용
            right.setOpaque(false);
            right.add(count);
            header.add(right, BorderLayout.EAST);
        }
        JLabel hint = new JLabel("클릭하면 클립보드에 복사됩니다");
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

        JPanel root = new JPanel(new BorderLayout());
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

    /** 클립이 하나도 없을 때 보여 줄 안내. */
    private static JComponent emptyState() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(24, 16, 32, 16));
        p.setPreferredSize(new Dimension(WIDTH, 150));
        JLabel big = new JLabel("아직 클립이 없어요");
        big.setFont(Theme.font(14f, Font.BOLD));
        JLabel small = new JLabel("다른 PC에서 텍스트를 복사(Ctrl+C)해 보세요");
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
     * │ 3분 전 · [다른 기기]                   │  ← 시간 + 출처 배지
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

        ClipCell(String myDeviceId, int[] hover) {
            this.myDeviceId = myDeviceId;
            this.hover = hover;
            panel.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
            text.setFont(Theme.font(13f, Font.PLAIN));
            time.setFont(Theme.font(11f, Font.PLAIN));
            time.setForeground(Theme.muted());
            meta.setOpaque(false);
            ((FlowLayout) meta.getLayout()).setHgap(0);
            panel.add(text, BorderLayout.CENTER);
            panel.add(meta, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends JsonNode> list, JsonNode clip, int index,
                                                      boolean selected, boolean focus) {
            // 줄바꿈/연속 공백을 한 칸으로 합치고, 길면 잘라서 "…" (실제로 복사되는 값은 원문 그대로)
            String s = clip.path("content").asText().replaceAll("\\s+", " ").strip();
            text.setText(s.length() > 48 ? s.substring(0, 48) + "…" : s);
            text.setForeground(list.getForeground());
            time.setText(Theme.ago(Theme.parse(clip.path("createdAt").asText())) + "  ·  ");
            boolean fromMe = clip.path("sourceDeviceId").asText().equals(myDeviceId);
            meta.removeAll();
            meta.add(time);
            meta.add(fromMe ? mine : other);
            // 선택 > 마우스오버 > 없음 순서로 배경 결정
            panel.fill = selected ? Theme.selected() : index == hover[0] ? Theme.hover() : null;
            return panel;
        }
    }
}
