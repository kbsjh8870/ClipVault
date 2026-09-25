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

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    public record RegisterRequest(@NotBlank @Size(max = 100) String deviceName, @Size(max = 50) String os) {
    }

    public record DeviceResponse(UUID id, String deviceName, String os, Instant lastSeenAt, boolean active) {
        static DeviceResponse of(Device d) {
            return new DeviceResponse(d.getId(), d.getDeviceName(), d.getOs(), d.getLastSeenAt(), d.isActive());
        }
    }

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

    @GetMapping
    public List<DeviceResponse> list(@AuthenticationPrincipal AuthUser me) {
        return devices.findByUserIdAndActiveTrueOrderByLastSeenAtDesc(me.userId()).stream().map(DeviceResponse::of).toList();
    }

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
