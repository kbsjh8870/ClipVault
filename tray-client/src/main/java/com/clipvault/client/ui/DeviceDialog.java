package com.clipvault.client.ui;

import com.clipvault.client.network.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.Callable;

/** Lists my active devices; "원격 로그아웃" deactivates one. Call on the EDT. */
public class DeviceDialog {
    private record Row(String id, String label) {
        @Override public String toString() { return label; }
    }

    /** onLoggedOut runs (on the EDT) if the current device was removed or auth was lost. */
    public static void show(ApiClient api, String myDeviceId, Runnable onLoggedOut) {
        JDialog d = new JDialog((Frame) null, "기기 관리");
        DefaultListModel<Row> model = new DefaultListModel<>();
        JList<Row> list = new JList<>(model);
        JButton remove = new JButton("원격 로그아웃");
        JButton reload = new JButton("새로고침");
        JLabel status = new JLabel(" ");

        Runnable load = () -> run(status, onLoggedOut, d, api::listDevices, devices -> {
            model.clear();
            for (JsonNode dev : devices) {
                String id = dev.path("id").asText();
                model.addElement(new Row(id, "%s (%s) — 마지막 접속 %s%s".formatted(
                        dev.path("deviceName").asText(), dev.path("os").asText(),
                        dev.path("lastSeenAt").asText("-"), id.equals(myDeviceId) ? "  [현재 기기]" : "")));
            }
            status.setText(model.size() + "대");
        });

        remove.addActionListener(e -> {
            Row r = list.getSelectedValue();
            if (r == null) return;
            boolean self = r.id().equals(myDeviceId);
            String msg = self ? "현재 기기입니다. 로그아웃하시겠습니까?" : "'" + r.label() + "' 기기를 로그아웃할까요?";
            if (JOptionPane.showConfirmDialog(d, msg, "원격 로그아웃", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            run(status, onLoggedOut, d, () -> { api.deleteDevice(r.id()); return null; }, ignored -> {
                if (self) { d.dispose(); onLoggedOut.run(); } else load.run();
            });
        });
        reload.addActionListener(e -> load.run());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(reload);
        buttons.add(remove);
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(new JScrollPane(list), BorderLayout.CENTER);
        root.add(status, BorderLayout.NORTH);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.setSize(520, 300);
        d.setLocationRelativeTo(null);
        d.setVisible(true);
        load.run();
    }

    private static <T> void run(JLabel status, Runnable onLoggedOut, JDialog d, Callable<T> work, java.util.function.Consumer<T> ui) {
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
                        status.setText("실패: " + c.getMessage());
                    }
                }
            }
        }.execute();
    }
}
