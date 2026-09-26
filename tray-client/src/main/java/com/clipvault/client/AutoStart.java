package com.clipvault.client;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

/**
 * 윈도우 시작 시 자동 실행. 현재 사용자 레지스트리의 Run 키에 exe 경로를 적어 두면 로그인할 때 윈도우가 실행해 준다.
 *
 * <p>현재 사용자(HKCU) 키라서 관리자 권한이 필요 없다. 자동 업데이트는 같은 폴더 안에서 교체하므로 경로가 바뀌지 않는다.
 * jpackage exe로 실행할 때만 동작한다. IDE나 gradle로 실행하면 등록할 exe가 없다.</p>
 */
final class AutoStart {
    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String NAME = "ClipVault";
    /** jpackage 런처가 넣어 주는 exe 경로. IDE 실행이면 null. */
    private static final String EXE = System.getProperty("jpackage.app-path");

    private AutoStart() {
    }

    /** 등록할 수 있는 환경인지 (jpackage exe로 실행 중인지). */
    static boolean available() {
        return EXE != null;
    }

    /** 자동 실행이 켜져 있는지. */
    static boolean enabled() {
        return available() && Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME);
    }

    /** 자동 실행을 켜거나 끈다. 경로에 공백이 있어도 되도록 따옴표로 감싼다. */
    static void set(boolean on) {
        if (!available()) return;
        if (on) {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, "\"" + EXE + "\"");
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME);
        }
    }

    /** 켜져 있으면 지금 exe 경로로 다시 적는다. 사용자가 앱 폴더를 다른 곳으로 옮겨도 다음 부팅부터 맞는 경로로 실행된다. */
    static void refresh() {
        if (enabled()) set(true);
    }
}
