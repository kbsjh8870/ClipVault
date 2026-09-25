package com.clipvault.client.ui;

import com.clipvault.client.network.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
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
     * 목록의 한 줄. JList는 toString()으로 글자를 표시하므로 label을 돌려준다.
     *
     * @param id    기기 ID (삭제 요청에 사용)
     * @param label 화면에 보여 줄 문구
     */
    private record Row(String id, String label) {
        @Override public String toString() { return label; }
    }

    /**
     * 기기 관리 창을 띄운다.
     *
     * @param myDeviceId  이 PC의 기기 ID ("[현재 기기]" 표시와, 자기 자신을 로그아웃하는 경우 구분용)
     * @param onLoggedOut 현재 기기를 로그아웃했거나 인증이 끊겼을 때(EDT에서) 호출 → 로그인 화면으로
     */
    public static void show(ApiClient api, String myDeviceId, Runnable onLoggedOut) {
        JDialog d = new JDialog((Frame) null, "기기 관리");
        DefaultListModel<Row> model = new DefaultListModel<>();
        JList<Row> list = new JList<>(model);
        JButton remove = new JButton("원격 로그아웃");
        JButton reload = new JButton("새로고침");
        JLabel status = new JLabel(" ");

        // 기기 목록 불러오기 → 각 기기를 "이름 (OS) — 마지막 접속 시각 [현재 기기]" 형태로 표시
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

        // "원격 로그아웃" 버튼: 확인 창을 띄운 뒤 서버에 기기 삭제 요청
        remove.addActionListener(e -> {
            Row r = list.getSelectedValue();
            if (r == null) return;
            boolean self = r.id().equals(myDeviceId);
            String msg = self ? "현재 기기입니다. 로그아웃하시겠습니까?" : "'" + r.label() + "' 기기를 로그아웃할까요?";
            if (JOptionPane.showConfirmDialog(d, msg, "원격 로그아웃", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            run(status, onLoggedOut, d, () -> { api.deleteDevice(r.id()); return null; }, ignored -> {
                // 자기 자신을 로그아웃했으면 창을 닫고 로그인 화면으로, 다른 기기였으면 목록만 새로고침
                if (self) { d.dispose(); onLoggedOut.run(); } else load.run();
            });
        });
        reload.addActionListener(e -> load.run());

        // ----- 화면 배치 -----
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
