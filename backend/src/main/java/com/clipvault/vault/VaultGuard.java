package com.clipvault.vault;

import com.clipvault.auth.AuthUser;
import com.clipvault.auth.User;
import com.clipvault.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업로드가 어느 방식인지 판정한다 (헤더 {@value #HEADER}와 사용자의 볼트 버전으로).
 *
 * <table>
 *   <tr><th>헤더</th><th>볼트</th><th>결과</th></tr>
 *   <tr><td>없음</td><td>없음</td><td>false = 옛 방식 (구버전 앱 호환: 서버가 평문을 받아 서버 암호화)</td></tr>
 *   <tr><td>없음</td><td>있음</td><td>426 "앱을 업데이트하세요"</td></tr>
 *   <tr><td>있음 = 현재 버전</td><td>있음</td><td>true = e2e (받은 그대로 저장)</td></tr>
 *   <tr><td>있음 ≠ 현재 버전</td><td>(무관)</td><td>409 "볼트가 바뀌었습니다" (다른 PC에서 초기화됨)</td></tr>
 * </table>
 */
@Component
public class VaultGuard {
    public static final String HEADER = "X-Vault-Version";

    private final UserRepository users;

    public VaultGuard(UserRepository users) {
        this.users = users;
    }

    /** @return true = e2e 업로드, false = 옛 방식. 그 밖은 예외(426/409). */
    public boolean e2e(AuthUser me, Integer header) {
        int current = users.findById(me.userId()).map(User::getVaultVersion).orElse(0);
        if (header == null) {
            if (current > 0) throw new ResponseStatusException(HttpStatus.UPGRADE_REQUIRED, "Update the app to use end-to-end encryption");
            return false;
        }
        if (current == 0 || header != current) throw new ResponseStatusException(HttpStatus.CONFLICT, "Vault changed");
        return true;
    }
}
