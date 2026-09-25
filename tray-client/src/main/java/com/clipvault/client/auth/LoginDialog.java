package com.clipvault.client.auth;

import com.clipvault.client.network.ApiClient;

import javax.swing.*;
import java.awt.*;
import java.net.InetAddress;

/**
 * 로그인 / 회원가입 창.
 *
 * <p>입력 항목: 서버 주소, 이메일, 비밀번호, 기기 이름(기본값은 컴퓨터 이름).</p>
 * <ul>
 *   <li><b>로그인</b> 버튼: 로그인 + 이 PC를 기기로 등록</li>
 *   <li><b>회원가입</b> 버튼: 계정을 만든 뒤 곧바로 로그인 + 기기 등록까지 한 번에</li>
 * </ul>
 *
 * <p>모달 창이라 창이 닫힐 때까지 {@link #show}가 돌아오지 않는다. 반드시 화면 스레드(EDT)에서 호출해야 한다.</p>
 */
public class LoginDialog {

    /**
     * 로그인 창을 띄운다.
     *
     * @return 로그인과 기기 등록에 성공하면 true, 사용자가 그냥 창을 닫으면 false
     */
    public static boolean show(Session session, ApiClient api) {
        // true = 모달: 이 창이 떠 있는 동안 다른 창은 조작할 수 없고, setVisible(true)가 창이 닫힐 때까지 멈춰 있다
        JDialog d = new JDialog((Frame) null, "ClipVault 로그인", true);
        JTextField server = new JTextField(session.server, 24);
        JTextField email = new JTextField(24);
        JPasswordField password = new JPasswordField(24);
        JTextField deviceName = new JTextField(hostname(), 24);
        JLabel status = new JLabel(" "); // 진행 상태/에러 메시지 표시줄
        JButton login = new JButton("로그인");
        JButton signup = new JButton("회원가입");
        // 람다 안에서 값을 바꾸기 위해 배열로 감싼다 (람다는 바깥의 일반 지역변수를 수정할 수 없음)
        boolean[] ok = {false};

        // ----- 화면 배치 -----
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6)); // 2열 표: [라벨][입력칸]
        form.add(new JLabel("서버")); form.add(server);
        form.add(new JLabel("이메일")); form.add(email);
        form.add(new JLabel("비밀번호")); form.add(password);
        form.add(new JLabel("기기 이름")); form.add(deviceName);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(signup);
        buttons.add(login);
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(form, BorderLayout.CENTER);
        root.add(status, BorderLayout.NORTH);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(login); // 엔터 키 = 로그인

        // ----- 버튼 동작 (로그인/회원가입이 거의 같아서 한 번에 처리) -----
        for (JButton b : new JButton[]{login, signup}) {
            boolean isSignup = b == signup;
            b.addActionListener(e -> {
                String url = server.getText().trim().replaceAll("/+$", ""); // 끝의 "/" 제거
                String em = email.getText().trim();
                String pw = new String(password.getPassword());
                String name = deviceName.getText().trim();
                if (url.isEmpty() || em.isEmpty() || pw.isEmpty() || name.isEmpty()) {
                    status.setText("모든 항목을 입력하세요.");
                    return;
                }
                session.server = url;
                // 요청 중에 버튼을 또 누르지 못하게 잠근다
                login.setEnabled(false);
                signup.setEnabled(false);
                status.setText(isSignup ? "가입 중..." : "로그인 중...");
                // 네트워크 요청은 SwingWorker로 백그라운드에서 실행 (화면이 멈추지 않도록)
                new SwingWorker<Void, Void>() {
                    /** 백그라운드 스레드에서 실행 */
                    @Override protected Void doInBackground() {
                        if (isSignup) api.signup(em, pw);
                        api.loginAndRegister(em, pw, name);
                        return null;
                    }

                    /** 끝나면 화면 스레드에서 실행 → 결과에 따라 창을 닫거나 에러 표시 */
                    @Override protected void done() {
                        try {
                            get(); // 백그라운드에서 예외가 났으면 여기서 다시 던져진다
                            ok[0] = true;
                            d.dispose(); // 창 닫기 → show()의 setVisible(true)가 풀린다
                        } catch (Exception ex) {
                            Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                            status.setText(c instanceof ApiClient.ApiException ae && ae.status == 401
                                    ? "이메일 또는 비밀번호가 올바르지 않습니다." : "실패: " + c.getMessage());
                            login.setEnabled(true);
                            signup.setEnabled(true);
                        }
                    }
                }.execute();
            });
        }

        d.pack();                     // 내용에 맞게 창 크기 자동 조절
        d.setLocationRelativeTo(null); // 화면 가운데
        d.setVisible(true);            // 창이 닫힐(dispose) 때까지 여기서 멈춘다
        return ok[0];
    }

    /** 기기 이름 기본값으로 쓸 컴퓨터 이름. 못 구하면 윈도우 환경변수 COMPUTERNAME, 그것도 없으면 "my-pc". */
    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String n = System.getenv("COMPUTERNAME");
            return n != null ? n : "my-pc";
        }
    }
}
