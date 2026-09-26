package com.clipvault.client.auth;

import com.clipvault.client.network.ApiClient;
import com.clipvault.client.ui.Theme;

import javax.swing.*;
import java.awt.*;
import java.net.InetAddress;
import java.util.List;

/**
 * 로그인 / 회원가입 창.
 *
 * <p>입력 항목: 이메일, 비밀번호, 기기 이름(기본값은 컴퓨터 이름). 서버 주소는 평소엔 숨겨 두고 "서버 설정"을 누르면 나타난다.</p>
 * <ul>
 *   <li><b>로그인</b> 버튼: 로그인 + 이 PC를 기기로 등록</li>
 *   <li><b>회원가입</b> 링크: 계정을 만든 뒤 곧바로 로그인 + 기기 등록까지 한 번에</li>
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
        JDialog d = new JDialog((Frame) null, "ClipVault", true);
        d.setIconImages(List.of(Theme.appIcon(16, false), Theme.appIcon(32, false), Theme.appIcon(64, false)));

        JTextField server = new JTextField(session.server);
        JTextField email = new JTextField(24); // 24칸 너비 → 창 전체 너비의 기준이 된다
        JPasswordField password = new JPasswordField();
        JTextField deviceName = new JTextField(hostname());
        // 입력칸이 비어 있을 때 흐리게 보이는 안내 문구 (FlatLaf 기능)
        email.putClientProperty("JTextField.placeholderText", "you@example.com");
        password.putClientProperty("JTextField.placeholderText", "8자 이상");
        deviceName.putClientProperty("JTextField.placeholderText", "예: 회사PC, 집PC");
        password.putClientProperty("JPasswordField.showRevealButton", true); // 눈 모양 버튼으로 비밀번호 보기
        for (JTextField f : new JTextField[]{server, email, password, deviceName}) {
            f.putClientProperty("FlatLaf.style", "margin: 6,10,6,10"); // 입력칸 안쪽 여백을 넉넉하게
        }

        JLabel status = new JLabel(" "); // 진행 상태/에러 메시지 표시줄
        status.setFont(Theme.font(12f, Font.PLAIN));
        JButton login = new JButton("로그인");
        login.setFont(Theme.font(14f, Font.BOLD));
        login.putClientProperty("FlatLaf.style", "margin: 8,0,8,0");
        // 회원가입은 글자만 있는 링크 모양 버튼
        JButton signup = new JButton("회원가입");
        signup.putClientProperty("JButton.buttonType", "borderless");
        signup.setForeground(Theme.ACCENT);
        signup.setFont(Theme.font(12f, Font.BOLD));
        // 지금 어느 서버에 접속하려는지 항상 보여 준다 (서버를 잘못 골라 "계정이 없다"고 헷갈리는 일을 막기 위해).
        // 주소 입력줄 자체는 접어 두었다가 "변경"을 누를 때만 펼친다 (대부분의 사용자는 바꿀 일이 없음)
        JLabel serverInfo = new JLabel();
        serverInfo.setFont(Theme.font(11f, Font.PLAIN));
        JButton serverToggle = new JButton("변경");
        serverToggle.putClientProperty("JButton.buttonType", "borderless");
        serverToggle.setForeground(Theme.ACCENT);
        serverToggle.setFont(Theme.font(11f, Font.BOLD));
        Runnable refreshServerInfo = () -> {
            String url = server.getText().trim();
            boolean local = isLocal(url);
            serverInfo.setText("접속 서버 · " + host(url) + (local ? "  (이 PC 전용)" : ""));
            // localhost는 다른 PC와 동기화되지 않으므로 주황색으로 눈에 띄게 표시
            serverInfo.setForeground(local ? new Color(0xF59E0B) : Theme.muted());
        };
        refreshServerInfo.run();
        server.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshServerInfo.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshServerInfo.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshServerInfo.run(); }
        });
        JLabel serverLabel = fieldLabel("서버 주소");
        serverLabel.setVisible(false);
        server.setVisible(false);
        // 람다 안에서 값을 바꾸기 위해 배열로 감싼다 (람다는 바깥의 일반 지역변수를 수정할 수 없음)
        boolean[] ok = {false};

        // ----- 화면 배치: 위에서 아래로 한 줄씩 쌓는다 -----
        JPanel root = new JPanel(new GridBagLayout());
        root.setBorder(BorderFactory.createEmptyBorder(28, 32, 20, 32));
        Stack s = new Stack(root);

        // 머리말: 로고 + 앱 이름 + 한 줄 소개
        JLabel logo = new JLabel(new ImageIcon(Theme.appIcon(56, false)));
        s.add(logo, 0, GridBagConstraints.CENTER);
        JLabel title = new JLabel("ClipVault");
        title.setFont(Theme.font(22f, Font.BOLD));
        s.add(title, 10, GridBagConstraints.CENTER);
        JLabel subtitle = new JLabel("복사한 텍스트를 내 모든 PC에서 이어 쓰세요");
        subtitle.setForeground(Theme.muted());
        s.add(subtitle, 4, GridBagConstraints.CENTER);

        s.add(fieldLabel("이메일"), 24, 0);
        s.add(email, 6, 0);
        s.add(fieldLabel("비밀번호"), 14, 0);
        s.add(password, 6, 0);
        s.add(fieldLabel("기기 이름"), 14, 0);
        s.add(deviceName, 6, 0);
        s.add(serverLabel, 14, 0);
        s.add(server, 6, 0);
        s.add(status, 12, 0);
        s.add(login, 6, 0);

        // 아래쪽: "계정이 없으신가요? 회원가입"
        JPanel signupRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        signupRow.setOpaque(false);
        JLabel noAccount = new JLabel("계정이 없으신가요?");
        noAccount.setForeground(Theme.muted());
        noAccount.setFont(Theme.font(12f, Font.PLAIN));
        signupRow.add(noAccount);
        signupRow.add(signup);
        s.add(signupRow, 8, 0);

        // 맨 아래: 구분선 + "접속 서버 · 호스트  변경"
        s.add(new JSeparator(), 10, 0);
        JPanel serverRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        serverRow.setOpaque(false);
        serverRow.add(serverInfo);
        serverRow.add(serverToggle);
        s.add(serverRow, 6, 0);

        d.setContentPane(root);
        d.getRootPane().setDefaultButton(login); // 엔터 키 = 로그인 (FlatLaf가 기본 버튼을 강조색으로 칠해 준다)

        serverToggle.addActionListener(e -> {
            boolean show = !server.isVisible();
            serverLabel.setVisible(show);
            server.setVisible(show);
            serverToggle.setText(show ? "닫기" : "변경");
            if (show) server.requestFocusInWindow();
            d.pack();
        });

        // ----- 버튼 동작 (로그인/회원가입이 거의 같아서 한 번에 처리) -----
        for (JButton b : new JButton[]{login, signup}) {
            boolean isSignup = b == signup;
            b.addActionListener(e -> {
                String url = server.getText().trim().replaceAll("/+$", ""); // 끝의 "/" 제거
                String em = email.getText().trim();
                String pw = new String(password.getPassword());
                String name = deviceName.getText().trim();
                if (url.isEmpty() || em.isEmpty() || pw.isEmpty() || name.isEmpty()) {
                    showStatus(status, "모든 항목을 입력하세요.", true);
                    return;
                }
                session.server = url;
                // 요청 중에 버튼을 또 누르지 못하게 잠근다
                login.setEnabled(false);
                signup.setEnabled(false);
                showStatus(status, isSignup ? "가입 중..." : "로그인 중...", false);
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
                            showStatus(status, errorMessage(c, isSignup, url), true);
                            login.setEnabled(true);
                            signup.setEnabled(true);
                        }
                    }
                }.execute();
            });
        }

        d.pack();                      // 내용에 맞게 창 크기 자동 조절
        d.setResizable(false);
        d.setLocationRelativeTo(null); // 화면 가운데
        d.setVisible(true);            // 창이 닫힐(dispose) 때까지 여기서 멈춘다
        return ok[0];
    }

    /**
     * 실패 원인을 사용자가 이해할 수 있는 문장으로 바꾼다. 어느 서버에서 실패했는지도 함께 알려 준다.
     * (예: 다른 서버에 가입해 놓고 이 서버로 로그인하면 "계정이 없다"가 나오므로 서버 이름이 단서가 된다)
     */
    private static String errorMessage(Throwable c, boolean isSignup, String url) {
        String h = host(url);
        if (c instanceof ApiClient.ApiException ae) {
            if (ae.status == 401) return "계정이 없거나 비밀번호가 틀렸습니다.\n서버: " + h;
            if (ae.status == 409) return "이미 가입된 이메일입니다.\n로그인 버튼을 눌러 주세요.";
            if (ae.status == 400) return isSignup ? "이메일 형식과 비밀번호(8자 이상)를\n확인해 주세요." : "입력값을 확인해 주세요.";
            return "서버 오류가 발생했습니다 (" + ae.status + ").\n잠시 후 다시 시도해 주세요.";
        }
        if (c instanceof java.io.UncheckedIOException || c instanceof IllegalArgumentException) {
            return "서버에 연결할 수 없습니다.\n서버: " + h;
        }
        return "실패: " + c.getMessage();
    }

    /** "https://host:port/..." 에서 사람이 읽기 좋은 "host[:port]" 부분만 꺼낸다. 형식이 이상하면 그대로. */
    private static String host(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            if (u.getHost() == null) return url;
            return u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** 이 PC 자신을 가리키는 주소인지 (다른 PC와는 동기화되지 않는다). */
    private static boolean isLocal(String url) {
        String h = host(url);
        return h.startsWith("localhost") || h.startsWith("127.") || h.startsWith("[::1]");
    }

    /** 입력칸 위의 작은 굵은 라벨. */
    private static JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(12f, Font.BOLD));
        return l;
    }

    /** 상태줄 문구. 에러면 빨간색, 진행 중이면 회색. */
    private static void showStatus(JLabel status, String text, boolean error) {
        status.setForeground(error ? Theme.DANGER : Theme.muted());
        // 여러 줄(\n) 문구를 표시하려고 HTML로 감싼다. 줄은 코드에서 직접 나눠서 한글이 단어 중간에서 끊기지 않게 한다.
        text = "<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>";
        status.setText(text);
    }

    /** GridBagLayout에 컴포넌트를 위에서부터 한 줄씩(가로로 꽉 차게) 쌓는 작은 도우미. */
    private static final class Stack {
        private final JPanel panel;
        private int row;

        Stack(JPanel panel) {
            this.panel = panel;
        }

        /**
         * @param top    위쪽 간격(px)
         * @param anchor 0이면 가로로 꽉 채우고, 아니면 해당 위치(예: CENTER)에 원래 크기로 놓는다
         */
        void add(JComponent c, int top, int anchor) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = row++;
            g.weightx = 1;
            g.insets = new Insets(top, 0, 0, 0);
            if (anchor == 0) {
                g.fill = GridBagConstraints.HORIZONTAL;
            } else {
                g.anchor = anchor;
            }
            panel.add(c, g);
        }
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
