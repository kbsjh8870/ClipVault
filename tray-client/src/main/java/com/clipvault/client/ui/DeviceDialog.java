package com.clipvault.client.ui;

import com.clipvault.client.network.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * "기기 관리" 창. 내 계정에 로그인된 기기 목록을 보여 주고, 선택한 기기를 원격 로그아웃할 수 있다.
 *
 * <p>사용 예: 학교 실습실 PC에서 로그인했던 걸 깜빡했을 때, 집 PC에서 그 기기를 골라 "원격 로그아웃"을 누르면
 * 그 PC는 더 이상 클립을 받지 못한다.</p>
 *
 * <p>화면 스레드(EDT)에서 호출해야 한다.</p>
 */
public class DeviceDialog {

    /**
     * 기기 관리 창을 띄운다.
     *
     * @param myDeviceId  이 PC의 기기 ID ("현재 기기" 표시와, 자기 자신을 로그아웃하는 경우 구분용)
     * @param onLoggedOut 현재 기기를 로그아웃했거나 인증이 끊겼을 때(EDT에서) 호출 → 로그인 화면으로
     */
    public static void show(ApiClient api, String myDeviceId, Runnable onLoggedOut) {
        JDialog d = new JDialog((Frame) null, "기기 관리");
        d.setIconImages(List.of(Theme.appIcon(16, false), Theme.appIcon(32, false)));
        DefaultListModel<JsonNode> model = new DefaultListModel<>();
        JList<JsonNode> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        list.setCellRenderer(new DeviceCell(myDeviceId));

        JButton remove = new JButton("원격 로그아웃");
        remove.setEnabled(false); // 기기를 고르기 전에는 누를 수 없다
        // 위험한 동작이라 빨간 버튼으로 표시
        remove.putClientProperty("FlatLaf.style",
                "background: #E5484D; foreground: #FFFFFF; borderColor: #E5484D; hoverBackground: #D13438; pressedBackground: #B42318; disabledBackground: $Button.background");
        JButton reload = new JButton("새로고침");
        JLabel status = new JLabel(" ");
        status.setFont(Theme.font(12f, Font.PLAIN));
        status.setForeground(Theme.muted());

        list.addListSelectionListener(e -> remove.setEnabled(list.getSelectedValue() != null));

        // 기기 목록 불러오기
        Runnable load = () -> run(status, onLoggedOut, d, api::listDevices, devices -> {
            model.clear();
            for (JsonNode dev : devices) model.addElement(dev);
            status.setText("로그인된 기기 " + model.size() + "대");
        });

        // "원격 로그아웃" 버튼: 확인 창을 띄운 뒤 서버에 기기 삭제 요청
        remove.addActionListener(e -> {
            JsonNode dev = list.getSelectedValue();
            if (dev == null) return;
            String id = dev.path("id").asText();
            boolean self = id.equals(myDeviceId);
            String msg = self ? "현재 기기입니다. 로그아웃하시겠습니까?"
                    : "'" + dev.path("deviceName").asText() + "' 기기를 로그아웃할까요?\n그 기기는 더 이상 클립을 받지 못합니다.";
            if (JOptionPane.showConfirmDialog(d, msg, "원격 로그아웃", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            run(status, onLoggedOut, d, () -> { api.deleteDevice(id); return null; }, ignored -> {
                // 자기 자신을 로그아웃했으면 창을 닫고 로그인 화면으로, 다른 기기였으면 목록만 새로고침
                if (self) { d.dispose(); onLoggedOut.run(); } else load.run();
            });
        });
        reload.addActionListener(e -> load.run());

        // ----- 화면 배치 -----
        // 머리말: 제목 + 설명
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(false);
        JLabel title = new JLabel("기기 관리");
        title.setFont(Theme.font(17f, Font.BOLD));
        JLabel desc = new JLabel("<html>이 계정에 로그인된 PC 목록입니다. 분실했거나 공용 PC에서 로그인했다면 원격 로그아웃하세요.</html>");
        desc.setForeground(Theme.muted());
        desc.setFont(Theme.font(12f, Font.PLAIN));
        header.add(title, BorderLayout.NORTH);
        header.add(desc, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.border()));
        scroll.getViewport().setBackground(UIManager.getColor("List.background"));

        // 아래쪽: 상태 문구(왼쪽) + 버튼들(오른쪽)
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(reload);
        buttons.add(remove);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(status, BorderLayout.WEST);
        footer.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        root.add(header, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.setSize(540, 400);
        d.setLocationRelativeTo(null);
        d.setVisible(true);
        load.run(); // 창을 띄우자마자 목록 불러오기
    }

    /**
     * 네트워크 작업(work)은 백그라운드에서 실행하고, 끝나면 결과를 화면 스레드에서 ui에 넘기는 공통 도우미.
     * 401(인증 끊김)이면 창을 닫고 onLoggedOut을 호출하고, 그 외 오류는 상태줄에 표시한다.
     *
     * @param work 백그라운드에서 할 일 (서버 호출)
     * @param ui   성공 시 화면 스레드에서 할 일 (목록 갱신 등)
     */
    private static <T> void run(JLabel status, Runnable onLoggedOut, JDialog d, Callable<T> work, java.util.function.Consumer<T> ui) {
        status.setForeground(Theme.muted());
        status.setText("불러오는 중...");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }

            @Override protected void done() {
                try {
                    ui.accept(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    if (c instanceof ApiClient.ApiException ae && ae.status == 401) {
                        d.dispose();
                        onLoggedOut.run();
                    } else {
                        status.setForeground(Theme.DANGER);
                        status.setText("실패: " + c.getMessage());
                    }
                }
            }
        }.execute();
    }

    /**
     * 기기 목록 한 칸.
     *
     * <pre>
     * [모니터]  회사PC  [현재 기기]
     *          Windows 11 · 마지막 접속 3분 전
     * </pre>
     */
    private static final class DeviceCell implements ListCellRenderer<JsonNode> {
        private final String myDeviceId;
        private final Theme.RoundPanel panel = new Theme.RoundPanel(new BorderLayout(12, 0));
        private final JLabel icon = new JLabel(new MonitorIcon());
        private final JLabel name = new JLabel();
        private final JLabel sub = new JLabel();
        private final JLabel current = Theme.pill("현재 기기", Theme.ACCENT, Color.WHITE);
        private final JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        DeviceCell(String myDeviceId) {
            this.myDeviceId = myDeviceId;
            panel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            name.setFont(Theme.font(14f, Font.BOLD));
            sub.setFont(Theme.font(12f, Font.PLAIN));
            sub.setForeground(Theme.muted());
            nameRow.setOpaque(false);
            ((FlowLayout) nameRow.getLayout()).setHgap(0);
            JPanel text = new JPanel(new BorderLayout(0, 3));
            text.setOpaque(false);
            text.add(nameRow, BorderLayout.NORTH);
            text.add(sub, BorderLayout.CENTER);
            panel.add(icon, BorderLayout.WEST);
            panel.add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends JsonNode> list, JsonNode dev, int index,
                                                      boolean selected, boolean focus) {
            name.setText(dev.path("deviceName").asText());
            name.setForeground(list.getForeground());
            String os = dev.path("os").asText("");
            sub.setText((os.isEmpty() ? "" : os + "  ·  ") + "마지막 접속 " + Theme.ago(Theme.parse(dev.path("lastSeenAt").asText())));
            nameRow.removeAll();
            nameRow.add(name);
            if (dev.path("id").asText().equals(myDeviceId)) {
                nameRow.add(Box.createHorizontalStrut(8));
                nameRow.add(current);
            }
            panel.fill = selected ? Theme.selected() : null;
            return panel;
        }
    }

    /** 코드로 그린 작은 모니터 아이콘 (이미지 파일 없이). */
    private static final class MonitorIcon implements Icon {
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 둥근 배경 원
            g2.setColor(Theme.selected());
            g2.fillOval(x, y, 36, 36);
            // 화면 + 받침대
            g2.setColor(Theme.ACCENT);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawRoundRect(x + 9, y + 10, 18, 12, 3, 3);
            g2.drawLine(x + 18, y + 22, x + 18, y + 26);
            g2.drawLine(x + 14, y + 26, x + 22, y + 26);
            g2.dispose();
        }

        @Override public int getIconWidth() { return 36; }

        @Override public int getIconHeight() { return 36; }
    }
}
