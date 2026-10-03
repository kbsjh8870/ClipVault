package com.clipvault.settings;

import com.clipvault.auth.AuthUser;
import com.clipvault.auth.User;
import com.clipvault.auth.UserRepository;
import com.clipvault.clip.Clip;
import com.clipvault.clip.ClipRepository;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사용자 설정 API. device 토큰이 필요하다(SecurityConfig의 anyRequest 규칙). 설정은 기기가 아니라 사용자 단위다.
 *
 * <ul>
 *   <li>{@code GET /api/settings} - 현재 설정</li>
 *   <li>{@code PUT /api/settings} - 설정 변경</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    /** 설정 응답/요청. clipTtlDays = 클립 보관 기간(일), {@link User#TTL_CHOICES} 중 하나. */
    public record Settings(int clipTtlDays) {
    }

    private final UserRepository users;
    private final ClipRepository clips;

    public SettingsController(UserRepository users, ClipRepository clips) {
        this.users = users;
        this.clips = clips;
    }

    @GetMapping
    public Settings get(@AuthenticationPrincipal AuthUser me) {
        return new Settings(user(me).getClipTtlDays());
    }

    /**
     * 설정 변경. 204 No Content.
     *
     * <p><b>기존 클립에도 적용</b>: 고정 안 된 클립의 만료 시각을 모두 "생성 시각 + 새 보관 기간"으로 다시 잡는다
     * (이미지도 같다). 기간을 줄이면 그보다 오래된 클립은 바로 목록에서 빠지고 다음 정리 배치 때 지워진다.
     * 민감한 내용을 오래 두기 싫어서 줄인 사용자에게 옛 클립이 계속 남아 있으면 안 되기 때문이다.
     * 고정 클립은 원래 만료되지 않으므로 건드리지 않는다(해제할 때 새 기간이 적용된다).</p>
     *
     * @throws ResponseStatusException 400 허용되지 않은 보관 기간
     */
    // ponytail(의도적 단순화): 사용자의 클립을 전부 읽어 자바에서 고친다. 7~30일치라 수백 건 수준이고,
    // DB마다 다른 날짜 연산 SQL을 쓰지 않아도 된다. 클립이 수만 건이 되면 UPDATE 쿼리 한 번으로 바꿀 것.
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void put(@AuthenticationPrincipal AuthUser me, @RequestBody Settings req) {
        if (!User.TTL_CHOICES.contains(req.clipTtlDays())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clipTtlDays must be one of " + User.TTL_CHOICES);
        }
        User user = user(me);
        user.setClipTtlDays(req.clipTtlDays());
        users.save(user);
        Duration ttl = Duration.ofDays(req.clipTtlDays());
        for (Clip c : clips.findByUserIdAndPinnedFalse(me.userId())) {
            c.expireAfter(ttl);
        }
        // @Transactional 안에서 읽은 엔티티라 값만 바꾸면 커밋할 때 UPDATE가 나간다 (save 불필요)
    }

    /** 토큰의 사용자. 토큰은 유효한데 사용자가 없으면(탈퇴 직후 등) 404. */
    private User user(AuthUser me) {
        return users.findById(me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
