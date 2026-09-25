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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SignupRequest(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 100) String password) {
    }

    public record SignupResponse(UUID id, String email) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LoginResponse(UUID userId, String accessToken) {
    }

    public record TokenResponse(UUID userId, String accessToken, String refreshToken) {
    }

    private final UserRepository users;
    private final DeviceRepository devices;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;

    public AuthController(UserRepository users, DeviceRepository devices, PasswordEncoder passwordEncoder, JwtService jwt) {
        this.users = users;
        this.devices = devices;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = users.save(new User(req.email(), passwordEncoder.encode(req.password())));
        return new SignupResponse(user.getId(), user.getEmail());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByEmail(req.email())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        return new LoginResponse(user.getId(), jwt.issueAccess(user.getId()));
    }

    @PostMapping("/refresh")
    @Transactional
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        var invalid = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        AuthUser principal;
        try {
            principal = jwt.parse(req.refreshToken(), JwtService.REFRESH);
        } catch (JwtException | IllegalArgumentException e) {
            throw invalid;
        }
        if (principal.deviceId() == null) {
            throw invalid;
        }
        var device = devices.findById(principal.deviceId())
                .filter(d -> d.isActive() && d.getUserId().equals(principal.userId()))
                .filter(d -> HashUtil.sha256(req.refreshToken()).equals(d.getRefreshTokenHash()))
                .orElseThrow(() -> invalid);
        var pair = jwt.issue(principal.userId(), device.getId());
        device.rotateRefreshToken(HashUtil.sha256(pair.refreshToken()));
        return new TokenResponse(principal.userId(), pair.accessToken(), pair.refreshToken());
    }
}
