package com.clipvault.client.crypto;

import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.prefs.Preferences;

/**
 * 이 PC의 볼트 상태: 볼트 키(메모리)와 그 키로 만든 하위 키, 서버 볼트 버전.
 *
 * <p>볼트 키는 윈도우 DPAPI({@link Crypt32Util#cryptProtectData})로 잠가서 로그인 정보와 같은 Preferences(레지스트리)에 둔다.
 * DPAPI로 잠근 값은 같은 윈도우 계정으로 로그인했을 때만 풀리므로, 레지스트리 값만 빼 가서는 쓸 수 없다.
 * 그래서 볼트 암호는 PC마다 처음 한 번만 입력하면 된다. 로그아웃하면 {@link #lock}으로 지운다.</p>
 *
 * <p>여러 스레드(업로드, 받기, 화면)에서 읽으므로 키 묶음을 한 번에 바꿔 끼운다(volatile 참조 하나).</p>
 */
public final class Vault {
    /** 클립 종류별 AAD (서버가 바꿔치기하면 복호화 실패) */
    public static final String TEXT = "text-v1", IMAGE = "image-v1", THUMB = "thumb-v1";

    private static final Preferences PREFS = Preferences.userRoot().node("com/clipvault/client");

    /** 풀린 키 묶음. null = 잠김. */
    private record Keys(byte[] vaultKey, byte[] enc, byte[] hash, int version) {
    }

    private final String prefsKey;
    private volatile Keys keys;

    public Vault() {
        this("vault");
    }

    /** 테스트용: 저장 이름을 바꿔 실제 볼트와 섞이지 않게 한다. */
    Vault(String prefsKey) {
        this.prefsKey = prefsKey;
    }

    public boolean ready() { return keys != null; }

    /** 서버 볼트 버전 (잠김이면 0). 업로드 헤더 X-Vault-Version에 쓴다. */
    public int version() { Keys k = keys; return k == null ? 0 : k.version(); }

    /** 볼트 키 사본 (암호 변경 때 다시 감싸는 데 쓴다). 잠김이면 IllegalStateException. */
    public byte[] vaultKey() { return need().vaultKey().clone(); }

    /** 볼트 키를 받아 쓸 수 있게 하고 DPAPI로 잠가 저장한다. */
    public void unlock(byte[] vaultKey, int version) {
        unlockInMemory(vaultKey, version);
        PREFS.put(prefsKey + ".key", Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(vaultKey)));
        PREFS.putInt(prefsKey + ".version", version);
    }

    /** 저장 없이 메모리에만 (테스트, 그리고 unlock의 앞부분). */
    void unlockInMemory(byte[] vaultKey, int version) {
        keys = new Keys(vaultKey.clone(), VaultCrypto.subKey(vaultKey, VaultCrypto.ENC),
                VaultCrypto.subKey(vaultKey, VaultCrypto.HASH), version);
    }

    /** 저장된 볼트 키를 DPAPI로 풀어 불러온다. 없거나 못 풀면(다른 윈도우 계정, 손상) false. */
    public boolean load() {
        String stored = PREFS.get(prefsKey + ".key", null);
        if (stored == null) return false;
        try {
            unlockInMemory(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored)), PREFS.getInt(prefsKey + ".version", 0));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 잠근다: 메모리의 키를 지우고 저장된 값도 지운다 (로그아웃, 볼트 변경 감지). */
    public void lock() {
        Keys k = keys;
        keys = null;
        if (k != null) {
            Arrays.fill(k.vaultKey(), (byte) 0);
            Arrays.fill(k.enc(), (byte) 0);
            Arrays.fill(k.hash(), (byte) 0);
        }
        PREFS.remove(prefsKey + ".key");
        PREFS.remove(prefsKey + ".version");
    }

    /** 텍스트 → base64 암호문 (서버 content). */
    public String sealText(String text) {
        return Base64.getEncoder().encodeToString(seal(text.getBytes(StandardCharsets.UTF_8), TEXT));
    }

    /** base64 암호문 → 텍스트. 못 풀면 IllegalArgumentException. */
    public String openText(String base64) {
        return new String(open(Base64.getDecoder().decode(base64), TEXT), StandardCharsets.UTF_8);
    }

    public byte[] seal(byte[] plain, String aad) { return VaultCrypto.seal(need().enc(), plain, aad); }

    public byte[] open(byte[] sealed, String aad) { return VaultCrypto.open(need().enc(), sealed, aad); }

    /** 중복 판별 해시 (contentHash). */
    public String hash(byte[] plain) { return VaultCrypto.hmacHex(need().hash(), plain); }

    private Keys need() {
        Keys k = keys;
        if (k == null) throw new IllegalStateException("vault locked");
        return k;
    }
}
