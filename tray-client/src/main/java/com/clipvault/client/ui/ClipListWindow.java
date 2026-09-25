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

/** Popup list of recent clips near the tray. Click (or Enter) copies the clip locally. Call on the EDT. */
public class ClipListWindow {
    private static JDialog open;

    public static void show(JsonNode clips, Consumer<String> onPick) {
        if (open != null) open.dispose();
        DefaultListModel<String> model = new DefaultListModel<>();
        for (JsonNode c : clips) model.addElement(c.path("content").asText());

        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                String s = v.toString().replaceAll("\\s+", " ").strip();
                return super.getListCellRendererComponent(l, s.length() > 60 ? s.substring(0, 60) + "…" : s, i, sel, focus);
            }
        });

        JDialog d = new JDialog((Frame) null, "최근 클립");
        open = d;
        d.setUndecorated(true);
        d.setAlwaysOnTop(true);
        Runnable pick = () -> {
            String s = list.getSelectedValue();
            d.dispose();
            if (s != null) onPick.accept(s);
        };
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (list.locationToIndex(e.getPoint()) >= 0) pick.run();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pick");
        list.getActionMap().put("pick", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { pick.run(); }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        list.getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { d.dispose(); }
        });
        d.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { d.dispose(); }
        });

        JComponent content = model.isEmpty() ? new JLabel("  클립이 없습니다.  ") : new JScrollPane(list);
        content.setBorder(BorderFactory.createTitledBorder("최근 클립 (클릭하여 복사)"));
        d.setContentPane(content);
        d.setSize(380, model.isEmpty() ? 80 : 420);

        // place next to the mouse (i.e. the tray icon), kept inside the usable screen area
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle screen = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Point m = MouseInfo.getPointerInfo() != null ? MouseInfo.getPointerInfo().getLocation() : new Point(screen.width, screen.height);
        int x = Math.max(screen.x + in.left, Math.min(m.x - d.getWidth() / 2, screen.x + screen.width - in.right - d.getWidth()));
        int y = Math.max(screen.y + in.top, Math.min(m.y - d.getHeight(), screen.y + screen.height - in.bottom - d.getHeight()));
        d.setLocation(x, y);
        d.setVisible(true);
        d.toFront();
        list.requestFocusInWindow();
    }
}
