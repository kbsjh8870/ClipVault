package com.clipvault.auth;

import com.clipvault.common.HashUtil;
import com.clipvault.device.DeviceRepository;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 인증 API: 회원가입, 로그인, 토큰 갱신.
 *
 * <p>전체 로그인 흐름 (트레이 앱 기준)</p>
 * <ol>
 *   <li>{@code POST /api/auth/signup} - 계정 생성 (최초 1회)</li>
 *   <li>{@code POST /api/auth/login} - 이메일/비밀번호로 로그인 → <b>user accessToken</b> 발급</li>
 *   <li>{@code POST /api/devices} - user 토큰으로 현재 PC를 기기로 등록 → <b>device 토큰 쌍(access + refresh)</b> 발급</li>
 *   <li>이후 모든 요청은 device accessToken 사용. 만료(15분)되면 {@code POST /api/auth/refresh}로 새 토큰 쌍을 받는다.</li>
 * </ol>
 *
 * <p>이 컨트롤러의 URL(/api/auth/**)은 SecurityConfig에서 토큰 없이 접근 가능하도록 열어 두었다.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // ----- 요청/응답 DTO -----
    // record는 필드, 생성자, getter를 자동으로 만들어 주는 간단한 데이터 클래스다.
    // 필드에 붙은 @NotBlank, @Email, @Size는 컨트롤러 메서드의 @Valid와 함께 동작하며, 어기면 400 에러가 난다.

    /** 회원가입 요청. 이메일 형식이어야 하고 비밀번호는 8~100자. */
    public record SignupRequest(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 100) String password) {
    }

    /** 회원가입 응답. 비밀번호 관련 정보는 절대 돌려주지 않는다. */
    public record SignupResponse(UUID id, String email) {
    }

    /** 로그인 요청. */
    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    /** 토큰 갱신 요청. 기기 등록 때(또는 직전 갱신 때) 받은 refresh 토큰을 보낸다. */
    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /**
     * 로그인 응답. user accessToken만 준다(refresh 토큰 없음).
     * 이 토큰은 기기 등록 용도로 잠깐만 쓰고 버리는 토큰이라 갱신할 필요가 없다.
     */
    public record LoginResponse(UUID userId, String accessToken) {
    }

    /** 토큰 갱신 응답. 새 access 토큰과 새 refresh 토큰을 함께 준다(refresh 토큰도 매번 교체). */
    public record TokenResponse(UUID userId, String accessToken, String refreshToken) {
    }

    private final UserRepository users;
    private final DeviceRepository devices;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;

    /** 생성자 주입: 스프링이 필요한 객체(빈)를 자동으로 넣어 준다. */
    public AuthController(UserRepository users, DeviceRepository devices, PasswordEncoder passwordEncoder, JwtService jwt) {
        this.users = users;
        this.devices = devices;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
    }

    /**
     * 회원가입. 성공하면 201 Created.
     *
     * @throws ResponseStatusException 409 이미 가입된 이메일
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        // 비밀번호는 BCrypt로 해시해서 저장한다. 원문은 어디에도 남기지 않는다.
        User user = users.save(new User(req.email(), passwordEncoder.encode(req.password())));
        return new SignupResponse(user.getId(), user.getEmail());
    }

    /**
     * 로그인. 성공하면 user accessToken을 준다.
     *
     * <p>보안상 "이메일이 없음"과 "비밀번호가 틀림"을 구분하지 않고 같은 401 메시지를 준다.
     * 구분해서 알려 주면 공격자가 어떤 이메일이 가입되어 있는지 알아낼 수 있기 때문이다.</p>
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByEmail(req.email())
                // 입력한 비밀번호를 해시값과 비교 (BCrypt는 해시를 풀 수 없으므로 matches로 비교해야 한다)
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        return new LoginResponse(user.getId(), jwt.issueAccess(user.getId()));
    }

    /**
     * 토큰 갱신(refresh token rotation).
     *
     * <p>access 토큰은 15분이면 만료된다. 그때마다 다시 로그인하지 않도록,
     * refresh 토큰(30일)을 보내면 새 access + 새 refresh 토큰을 발급한다.</p>
     *
     * <p><b>rotation(교체) 방식</b>: 한 번 쓴 refresh 토큰은 즉시 폐기된다.
     * DB에는 "현재 유효한 refresh 토큰의 해시" 하나만 저장해 두고, 요청으로 온 토큰의 해시와 비교한다.
     * 누군가 refresh 토큰을 훔쳐서 먼저 써 버리면 원래 주인의 토큰은 더 이상 맞지 않게 되어 이상 징후를 알 수 있다.</p>
     *
     * <p>다음 중 하나라도 해당하면 401:</p>
     * <ul>
     *   <li>토큰 서명이 틀리거나 만료됨, 또는 access 토큰을 refresh 자리에 보냄</li>
     *   <li>기기 정보(deviceId)가 없는 토큰</li>
     *   <li>기기가 원격 로그아웃되어 비활성 상태</li>
     *   <li>이미 한 번 사용되어 교체된 옛 refresh 토큰</li>
     * </ul>
     *
     * <p>{@code @Transactional}: 메서드가 끝날 때 device의 변경(새 해시 저장)이 자동으로 DB에 반영된다.</p>
     */
    @PostMapping("/refresh")
    @Transactional
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        // 모든 실패 상황에서 같은 응답을 쓰기 위해 미리 만들어 둔다.
        var invalid = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        AuthUser principal;
        try {
            // 서명, 만료 시간, 토큰 종류(typ=refresh)를 검사
            principal = jwt.parse(req.refreshToken(), JwtService.REFRESH);
        } catch (JwtException | IllegalArgumentException e) {
            throw invalid;
        }
        if (principal.deviceId() == null) {
            throw invalid;
        }
        var device = devices.findById(principal.deviceId())
                // 활성 기기이고, 토큰의 사용자와 기기 주인이 같아야 한다
                .filter(d -> d.isActive() && d.getUserId().equals(principal.userId()))
                // DB에 저장된 "현재 유효한 refresh 토큰" 해시와 일치해야 한다 (옛 토큰 재사용 차단)
                .filter(d -> HashUtil.sha256(req.refreshToken()).equals(d.getRefreshTokenHash()))
                .orElseThrow(() -> invalid);
        // 새 토큰 쌍 발급 후, 새 refresh 토큰의 해시로 교체 저장 → 방금 받은 옛 토큰은 이제 무효
        var pair = jwt.issue(principal.userId(), device.getId());
        device.rotateRefreshToken(HashUtil.sha256(pair.refreshToken()));
        return new TokenResponse(principal.userId(), pair.accessToken(), pair.refreshToken());
    }
}
