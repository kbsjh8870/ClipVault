package com.clipvault.client.auth;

import com.clipvault.client.network.ApiClient;

import javax.swing.*;
import java.awt.*;
import java.net.InetAddress;

/** Modal login (and signup) dialog. Call on the EDT; returns true once this device is registered. */
public class LoginDialog {

    public static boolean show(Session session, ApiClient api) {
        JDialog d = new JDialog((Frame) null, "ClipVault 로그인", true);
        JTextField server = new JTextField(session.server, 24);
        JTextField email = new JTextField(24);
        JPasswordField password = new JPasswordField(24);
        JTextField deviceName = new JTextField(hostname(), 24);
        JLabel status = new JLabel(" ");
        JButton login = new JButton("로그인");
        JButton signup = new JButton("회원가입");
        boolean[] ok = {false};

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
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
        d.getRootPane().setDefaultButton(login);

        for (JButton b : new JButton[]{login, signup}) {
            boolean isSignup = b == signup;
            b.addActionListener(e -> {
                String url = server.getText().trim().replaceAll("/+$", "");
                String em = email.getText().trim();
                String pw = new String(password.getPassword());
                String name = deviceName.getText().trim();
                if (url.isEmpty() || em.isEmpty() || pw.isEmpty() || name.isEmpty()) {
                    status.setText("모든 항목을 입력하세요.");
                    return;
                }
                session.server = url;
                login.setEnabled(false);
                signup.setEnabled(false);
                status.setText(isSignup ? "가입 중..." : "로그인 중...");
                new SwingWorker<Void, Void>() {
                    @Override protected Void doInBackground() {
                        if (isSignup) api.signup(em, pw);
                        api.loginAndRegister(em, pw, name);
                        return null;
                    }

                    @Override protected void done() {
                        try {
                            get();
                            ok[0] = true;
                            d.dispose();
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

        d.pack();
        d.setLocationRelativeTo(null);
        d.setVisible(true); // blocks until disposed
        return ok[0];
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String n = System.getenv("COMPUTERNAME");
            return n != null ? n : "my-pc";
        }
    }
}
