package com.clipvault.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;

/**
 * 볼트(종단간 암호화) 창: 만들기, 입력, 변경, 초기화 확인.
 *
 * <p>모두 모달이고 화면 스레드(EDT)에서 부른다. [확인]을 누르면 입력값 검사(새 암호 = 확인 칸, 8자 이상) 뒤
 * {@link Check}를 <b>백그라운드</b>에서 실행한다(PBKDF2와 서버 요청이 0.5초 이상 걸리므로). 그동안 버튼은 "확인 중…".
 * Check가 null을 돌려주면 성공으로 창을 닫고, 문자열을 돌려주면 그 문구를 빨간 글씨로 보여 주고 다시 입력받는다.</p>
 */
public final class VaultDialog {
    /** 새 볼트 암호 최소 길이 */
    static final int MIN_LENGTH = 8;

    /** 입력값을 받아 실제 일을 하는 함수 (백그라운드). null = 성공, 아니면 사용자에게 보여 줄 오류 문구. */
    @FunctionalInterface
    public interface Check {
        String run(char[][] inputs);
    }

    private VaultDialog() {
    }

    /** 볼트 암호 만들기 (처음 한 번). inputs = [새 암호]. */
    public static boolean create(Check check) {
        return show("볼트 암호 만들기",
                "클립을 이 PC에서 암호화해서 올립니다. 서버도 내용을 볼 수 없습니다.",
                "볼트 암호를 잊으면 클립을 복구할 수 없고 초기화만 할 수 있습니다.",
                List.of(), true, "만들기", check, null, null);
    }

    /** 볼트 암호 입력 (새 PC, 다른 PC에서 초기화됨). inputs = [암호]. note가 있으면 설명 대신 보여 준다. */
    public static boolean unlock(String note, Check check, Runnable onForgot) {
        return show("볼트 암호 입력",
                note != null ? note : "이 계정의 클립을 열려면 볼트 암호를 입력하세요. 이 PC에서는 한 번만 입력하면 됩니다.",
                null, List.of("볼트 암호"), false, "열기", check, "암호를 잊었어요", onForgot);
    }

    /** 볼트 암호 변경. inputs = [현재 암호, 새 암호]. */
    public static boolean change(Check check) {
        return show("볼트 암호 변경", "다른 PC는 다시 입력하지 않아도 됩니다.", null,
                List.of("현재 볼트 암호"), true, "변경", check, null, null);
    }

    /** 볼트 초기화 (암호를 잊었을 때). inputs = [새 암호]. */
    public static boolean reset(Check check) {
        return show("볼트 초기화", "새 볼트 암호로 다시 시작합니다.",
                "모든 클립(고정한 클립 포함)이 삭제되고 되돌릴 수 없습니다. 다른 PC에서는 새 암호를 다시 입력해야 합니다.",
                List.of(), true, "초기화", check, null, null);
    }

    /**
     * 공통 창.
     *
     * @param plainLabels 그냥 입력 칸들 (확인 칸 없음)
     * @param withNew     true면 "새 볼트 암호" + "새 볼트 암호 확인" 칸을 뒤에 붙이고 일치·길이를 검사한다
     */
    private static boolean show(String titleText, String descText, String warningText, List<String> plainLabels,
                                boolean withNew, String okText, Check check, String linkText, Runnable onLink) {
        JDialog d = new JDialog((Frame) null, titleText, true);
        d.setIconImages(List.of(Theme.appIcon(16, false), Theme.appIcon(32, false)));
        boolean[] ok = {false};
        boolean[] link = {false};

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        java.util.List<JPasswordField> inputs = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>(plainLabels);
        if (withNew) {
            labels.add("새 볼트 암호 (" + MIN_LENGTH + "자 이상)");
            labels.add("새 볼트 암호 확인");
        }
        for (String l : labels) {
            JLabel label = new JLabel(l);
            label.setFont(Theme.font(12f, Font.PLAIN));
            g.insets = new Insets(8, 0, 4, 0);
            fields.add(label, g);
            JPasswordField f = new JPasswordField(22);
            g.insets = new Insets(0, 0, 0, 0);
            fields.add(f, g);
            inputs.add(f);
        }

        JLabel error = new JLabel(" ");
        error.setFont(Theme.font(12f, Font.PLAIN));
        error.setForeground(Theme.DANGER);

        JButton okButton = new JButton(okText);
        JButton cancel = new JButton("취소");
        cancel.addActionListener(e -> d.dispose());
        d.getRootPane().setDefaultButton(okButton);
        okButton.addActionListener(e -> {
            char[][] values = inputs.stream().map(JPasswordField::getPassword).toArray(char[][]::new);
            if (withNew) {
                char[] nw = values[values.length - 2], again = values[values.length - 1];
                if (nw.length < MIN_LENGTH) { error.setText("볼트 암호는 " + MIN_LENGTH + "자 이상이어야 합니다."); return; }
                if (!Arrays.equals(nw, again)) { error.setText("새 볼트 암호가 서로 다릅니다."); return; }
                values = Arrays.copyOf(values, values.length - 1); // 확인 칸은 넘기지 않는다
            }
            char[][] submit = values;
            okButton.setEnabled(false);
            cancel.setEnabled(false);
            okButton.setText("확인 중…");
            error.setText(" ");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() {
                    try {
                        return check.run(submit);
                    } catch (RuntimeException ex) {
                        return "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.";
                    } finally {
                        for (char[] v : submit) Arrays.fill(v, '\0'); // 암호를 메모리에 오래 두지 않는다
                    }
                }

                @Override protected void done() {
                    String msg;
                    try { msg = get(); } catch (Exception ex) { msg = "알 수 없는 오류가 발생했습니다."; }
                    if (msg == null) { ok[0] = true; d.dispose(); return; }
                    error.setText(msg);
                    okButton.setText(okText);
                    okButton.setEnabled(true);
                    cancel.setEnabled(true);
                }
            }.execute();
        });

        JLabel title = new JLabel(titleText);
        title.setFont(Theme.font(17f, Font.BOLD));
        title.setIcon(new ImageIcon(Theme.appIcon(22, false)));
        title.setIconTextGap(8);
        JLabel desc = new JLabel("<html><div style='width:300px'>" + descText + "</div></html>");
        desc.setFont(Theme.font(12f, Font.PLAIN));
        desc.setForeground(Theme.muted());
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.add(title, BorderLayout.NORTH);
        header.add(desc, BorderLayout.CENTER);
        if (warningText != null) {
            JLabel warn = new JLabel("<html><div style='width:300px'>⚠ " + warningText + "</div></html>");
            warn.setFont(Theme.font(12f, Font.BOLD));
            warn.setForeground(Theme.DANGER);
            header.add(warn, BorderLayout.SOUTH);
        }

        JPanel buttons = new JPanel(new BorderLayout());
        if (linkText != null) {
            JLabel l = new JLabel("<html><u>" + linkText + "</u></html>");
            l.setFont(Theme.font(12f, Font.PLAIN));
            l.setForeground(Theme.ACCENT);
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            l.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { link[0] = true; d.dispose(); }
            });
            buttons.add(l, BorderLayout.WEST);
        }
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(cancel);
        right.add(okButton);
        buttons.add(right, BorderLayout.EAST);

        JPanel footer = new JPanel(new BorderLayout(0, 10));
        footer.add(error, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        root.add(header, BorderLayout.NORTH);
        root.add(fields, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(null);
        d.setVisible(true); // 모달: 닫힐 때까지 여기서 기다린다
        if (link[0] && onLink != null) onLink.run();
        return ok[0];
    }
}
