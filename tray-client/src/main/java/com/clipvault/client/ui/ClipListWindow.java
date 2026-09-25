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
 * <p>항목을 클릭하거나 방향키로 고른 뒤 Enter를 누르면 그 텍스트가 로컬 클립보드에 복사된다(바로 Ctrl+V 가능).
 * Esc를 누르거나 다른 곳을 클릭하면(포커스를 잃으면) 닫힌다.</p>
 *
 * <p>화면 스레드(EDT)에서 호출해야 한다.</p>
 */
public class ClipListWindow {
    /** 현재 떠 있는 팝업. 트레이를 여러 번 클릭해도 팝업이 하나만 뜨도록 기억해 둔다. */
    private static JDialog open;

    /**
     * 팝업을 띄운다.
     *
     * @param clips  서버에서 받은 클립 목록(JSON 배열, 최신순)
     * @param onPick 사용자가 항목을 골랐을 때 호출 (선택한 텍스트 전달) - 로컬 클립보드에 넣는 일은 호출한 쪽이 한다
     */
    public static void show(JsonNode clips, Consumer<String> onPick) {
        if (open != null) open.dispose(); // 이미 떠 있던 팝업은 닫고 새로 띄운다
        DefaultListModel<String> model = new DefaultListModel<>();
        for (JsonNode c : clips) model.addElement(c.path("content").asText());

        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // 목록에 보이는 모양만 바꾼다: 줄바꿈/연속 공백을 한 칸으로 합치고 60자가 넘으면 "…"으로 자른다.
        // (실제로 복사되는 값은 잘리지 않은 원문 그대로)
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                String s = v.toString().replaceAll("\\s+", " ").strip();
                return super.getListCellRendererComponent(l, s.length() > 60 ? s.substring(0, 60) + "…" : s, i, sel, focus);
            }
        });

        JDialog d = new JDialog((Frame) null, "최근 클립");
        open = d;
        d.setUndecorated(true); // 제목 표시줄 없는 팝업 모양
        d.setAlwaysOnTop(true); // 다른 창 위에 표시
        // 선택 처리: 팝업을 닫고 선택한 텍스트를 넘긴다
        Runnable pick = () -> {
            String s = list.getSelectedValue();
            d.dispose();
            if (s != null) onPick.accept(s);
        };
        // 마우스 클릭으로 선택 (빈 공간 클릭은 무시)
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (list.locationToIndex(e.getPoint()) >= 0) pick.run();
            }
        });
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

        JComponent content = model.isEmpty() ? new JLabel("  클립이 없습니다.  ") : new JScrollPane(list);
        content.setBorder(BorderFactory.createTitledBorder("최근 클립 (클릭하여 복사)"));
        d.setContentPane(content);
        d.setSize(380, model.isEmpty() ? 80 : 420);

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
}
