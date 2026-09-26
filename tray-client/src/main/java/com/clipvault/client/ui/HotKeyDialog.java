package com.clipvault.client.ui;

import com.clipvault.client.hotkey.HotKeys;
import com.clipvault.client.hotkey.HotKeys.Action;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "단축키 설정" 창. 기능마다 칸을 누르고 원하는 키 조합을 누르면 기록된다. [저장]하면 바로 적용.
 *
 * <p>창이 떠 있는 동안에는 전역 단축키를 잠시 해제한다. 등록된 키는 윈도우가 가로채서 이 창에 키 입력으로
 * 들어오지 않기 때문이다. 창이 닫히면(저장이든 취소든) 다시 등록한다.</p>
 *
 * <p>화면 스레드(EDT)에서 호출해야 한다.</p>
 */
public class HotKeyDialog {
    private static final String HINT = "Ctrl·Alt·Shift 중 하나 이상 + 영문자, 숫자, F1~F12";

    /**
     * @param hotKeys   실행 중인 전역 단축키 (창이 떠 있는 동안 해제했다가 닫힐 때 다시 등록)
     * @param onClosed  창이 닫히고 단축키를 다시 등록한 뒤 호출 (메뉴 글자 갱신용)
     */
    public static void show(HotKeys hotKeys, Runnable onClosed) {
        JDialog d = new JDialog((Frame) null, "단축키 설정");
        d.setIconImages(List.of(Theme.appIcon(16, false), Theme.appIcon(32, false)));
        Map<Action, KeyStroke> keys = new EnumMap<>(Action.class);
        Map<Action, JTextField> fields = new EnumMap<>(Action.class);
        JLabel status = new JLabel(HINT);
        status.setFont(Theme.font(12f, Font.PLAIN));
        status.setForeground(Theme.muted());

        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 0, 4, 8);
        g.anchor = GridBagConstraints.WEST;
        for (Action a : Action.values()) {
            keys.put(a, HotKeys.get(a));
            JTextField f = new JTextField(HotKeys.text(keys.get(a)), 14);
            f.setEditable(false); // 글자를 타이핑하는 칸이 아니라 키 조합을 "누르는" 칸
            f.setHorizontalAlignment(SwingConstants.CENTER);
            f.putClientProperty("JTextField.placeholderText", "눌러서 키 입력");
            f.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    int c = e.getKeyCode();
                    // Ctrl/Alt/Shift만 누른 순간은 아직 조합이 끝나지 않은 것
                    if (c == KeyEvent.VK_CONTROL || c == KeyEvent.VK_ALT || c == KeyEvent.VK_SHIFT || c == KeyEvent.VK_META) return;
                    e.consume();
                    KeyStroke k = KeyStroke.getKeyStroke(c, e.getModifiersEx());
                    if (!HotKeys.valid(k)) {
                        status.setText("쓸 수 없는 조합입니다. " + HINT);
                        return;
                    }
                    for (Action other : Action.values()) {
                        if (other != a && k.equals(keys.get(other))) {
                            status.setText("'" + other.label + "'에서 이미 쓰는 조합입니다.");
                            return;
                        }
                    }
                    keys.put(a, k);
                    f.setText(HotKeys.text(k));
                    status.setText(HINT);
                }
            });
            fields.put(a, f);
            JButton clear = new JButton("지우기");
            clear.addActionListener(e -> {
                keys.put(a, null);
                f.setText(HotKeys.text(null));
            });
            g.gridx = 0;
            JLabel label = new JLabel(a.label);
            label.setFont(Theme.font(13f, Font.PLAIN));
            rows.add(label, g);
            g.gridx = 1;
            rows.add(f, g);
            g.gridx = 2;
            rows.add(clear, g);
        }

        JButton reset = new JButton("기본값");
        reset.addActionListener(e -> HotKeys.defaults().forEach((a, k) -> {
            keys.put(a, k);
            fields.get(a).setText(HotKeys.text(k));
        }));
        JButton cancel = new JButton("취소");
        cancel.addActionListener(e -> d.dispose());
        JButton save = new JButton("저장");
        save.addActionListener(e -> {
            keys.forEach(HotKeys::set);
            d.dispose();
        });
        d.getRootPane().setDefaultButton(save);

        JLabel title = new JLabel("단축키 설정");
        title.setFont(Theme.font(17f, Font.BOLD));
        JLabel desc = new JLabel("다른 앱을 쓰는 중에도 이 키로 ClipVault를 부를 수 있습니다.");
        desc.setFont(Theme.font(12f, Font.PLAIN));
        desc.setForeground(Theme.muted());
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(title, BorderLayout.NORTH);
        header.add(desc, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(reset);
        buttons.add(cancel);
        buttons.add(save);
        JPanel footer = new JPanel(new BorderLayout(0, 12));
        footer.add(status, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        root.add(header, BorderLayout.NORTH);
        root.add(rows, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        d.setContentPane(root);

        // 창이 떠 있는 동안 해제, 닫히면(저장/취소/X 모두) 저장된 설정으로 다시 등록
        hotKeys.stop();
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                hotKeys.apply();
                onClosed.run();
            }
        });
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(null);
        d.setVisible(true);
    }
}
