package com.clipvault.device;

import com.clipvault.auth.AuthUser;
import com.clipvault.auth.JwtService;
import com.clipvault.common.HashUtil;
import com.clipvault.websocket.DeviceSessions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 기기 API: 등록, 목록 조회, 원격 로그아웃.
 *
 * <ul>
 *   <li>{@code POST /api/devices} - 현재 PC를 기기로 등록하고 device 토큰을 발급 (user 토큰으로 호출 가능)</li>
 *   <li>{@code GET /api/devices} - 내 활성 기기 목록 (user 토큰으로도 호출 가능)</li>
 *   <li>{@code DELETE /api/devices/{id}} - 해당 기기 원격 로그아웃 (device 토큰 필요)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    /** 기기 등록 요청. 기기 이름은 필수(최대 100자), OS는 선택(최대 50자). */
    public record RegisterRequest(@NotBlank @Size(max = 100) String deviceName, @Size(max = 50) String os) {
    }

    /** 기기 목록의 한 항목. 토큰 해시 같은 민감한 값은 포함하지 않는다. */
    public record DeviceResponse(UUID id, String deviceName, String os, Instant lastSeenAt, boolean active) {
        /** 엔티티 → 응답 DTO 변환. */
        static DeviceResponse of(Device d) {
            return new DeviceResponse(d.getId(), d.getDeviceName(), d.getOs(), d.getLastSeenAt(), d.isActive());
        }
    }

    /** 기기 등록 응답. 기기 정보 + 이 기기 전용 토큰 쌍(access, refresh). 트레이 앱은 이 토큰들을 저장해 두고 계속 쓴다. */
    public record RegisterResponse(UUID id, String deviceName, String os, Instant lastSeenAt, boolean active,
                                   String accessToken, String refreshToken) {
    }

    private final DeviceRepository devices;
    private final JwtService jwt;
    private final DeviceSessions deviceSessions;

    public DeviceController(DeviceRepository devices, JwtService jwt, DeviceSessions deviceSessions) {
        this.devices = devices;
        this.jwt = jwt;
        this.deviceSessions = deviceSessions;
    }

    /**
     * 기기 등록. 201 Created.
     *
     * <p>순서: ① 기기 행 저장(여기서 ID가 생김) → ② 그 ID를 넣은 device 토큰 발급 → ③ refresh 토큰 해시를 기기에 저장.
     * {@code @Transactional}이라 ③의 변경도 메서드가 끝날 때 자동으로 DB에 반영된다.</p>
     *
     * @param me 요청한 사용자 (user 토큰 또는 device 토큰)
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RegisterResponse register(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody RegisterRequest req) {
        Device d = devices.save(new Device(me.userId(), req.deviceName(), req.os()));
        var pair = jwt.issue(me.userId(), d.getId());
        d.rotateRefreshToken(HashUtil.sha256(pair.refreshToken()));
        return new RegisterResponse(d.getId(), d.getDeviceName(), d.getOs(), d.getLastSeenAt(), d.isActive(),
                pair.accessToken(), pair.refreshToken());
    }

    /** 내 활성 기기 목록 (최근 접속 순). 원격 로그아웃된 기기는 빠진다. */
    @GetMapping
    public List<DeviceResponse> list(@AuthenticationPrincipal AuthUser me) {
        return devices.findByUserIdAndActiveTrueOrderByLastSeenAtDesc(me.userId()).stream().map(DeviceResponse::of).toList();
    }

    /**
     * 원격 로그아웃. 204 No Content.
     *
     * <p>분실한 노트북이나 공용 PC에서 로그인했던 기기를 다른 기기에서 끊을 때 쓴다.</p>
     * <ol>
     *   <li>기기를 비활성화 → 그 기기의 access/refresh 토큰이 즉시 거부된다(REST 요청은 401).</li>
     *   <li>그 기기가 이미 열어 둔 WebSocket 연결도 끊는다 → 새 클립 알림이 더 이상 가지 않는다.
     *       (토큰 검사는 연결할 때 한 번만 하므로, 이걸 안 하면 이미 연결된 소켓은 계속 알림을 받는다.)</li>
     * </ol>
     *
     * @throws ResponseStatusException 404 기기가 없거나 다른 사람의 기기
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void remoteLogout(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        devices.findByIdAndUserId(id, me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"))
                .deactivate();
        deviceSessions.closeDevice(id);
    }
}
