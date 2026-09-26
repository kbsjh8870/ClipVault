package com.clipvault.client.update;

import com.clipvault.client.network.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GitHub 릴리스를 확인해서 새 버전을 받아 설치하는 자동 업데이트 도구.
 *
 * <p><b>흐름</b></p>
 * <ol>
 *   <li>{@link #check()}: GitHub의 최신 릴리스 태그(v1.2.3)를 지금 버전과 비교한다.</li>
 *   <li>{@link #install}: 새 zip을 받아 체크섬(SHA-256)을 확인하고, 앱 폴더 옆에 풀어 둔다.
 *       그다음 교체 스크립트(update.ps1)를 띄운다. 호출한 쪽은 곧바로 앱을 종료해야 한다.</li>
 *   <li>스크립트: 앱이 꺼지길 기다렸다가 기존 폴더를 새 폴더로 바꾸고 앱을 다시 켠다.
 *       (실행 중인 exe는 윈도우가 잠가 두기 때문에 앱이 자기 자신을 덮어쓸 수 없어서 이렇게 한다)</li>
 * </ol>
 *
 * <p>jpackage로 만든 exe에서 실행할 때만 동작한다. IDE나 gradle로 실행하면 버전 정보가 없어서
 * {@link #enabled()}가 false다.</p>
 *
 * <p>ponytail: 체크섬은 다운로드 손상만 막는다. GitHub 계정이 털리면 막지 못하므로,
 * 코드 서명 인증서를 도입하면 서명 검증을 추가한다.</p>
 */
public class Updater {
    private static final String LATEST = "https://api.github.com/repos/kbsjh8870/ClipVault/releases/latest";
    private static final String ZIP = "ClipVault-windows.zip";

    /** 새 버전 정보. version은 "1.2.3"처럼 앞의 v를 뗀 값. */
    public record Release(String version, URI zip, URI sha256, URI page) {}

    /** GitHub 다운로드 링크는 실제 파일 서버로 리다이렉트되므로 따라가도록 설정한다. */
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 지금 실행 중인 앱 버전 (jpackage exe가 넣어 주는 값). IDE 실행이면 null. */
    public final String current = System.getProperty("jpackage.app-version");
    /** ClipVault.exe 파일 경로. IDE 실행이면 null. */
    private final Path exe = System.getProperty("jpackage.app-path") == null
            ? null : Path.of(System.getProperty("jpackage.app-path")).toAbsolutePath();

    /** exe로 실행 중일 때만 업데이트를 확인한다. */
    public boolean enabled() {
        return current != null && exe != null;
    }

    /** 새 버전이 있으면 그 정보를, 없으면(또는 확인 실패 시) null을 돌려준다. 네트워크를 쓰므로 EDT에서 부르지 말 것. */
    public Release check() {
        if (!enabled()) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            JsonNode json = ApiClient.JSON.readTree(res.body());
            String version = json.path("tag_name").asText().replaceFirst("^v", "");
            if (!isNewer(version, current)) return null;
            URI zip = null, sha = null;
            for (JsonNode a : json.path("assets")) {
                String name = a.path("name").asText();
                URI url = URI.create(a.path("browser_download_url").asText());
                if (name.equals(ZIP)) zip = url;
                if (name.equals(ZIP + ".sha256")) sha = url;
            }
            if (zip == null || sha == null) return null; // 파일이 아직 안 올라간 릴리스
            return new Release(version, zip, sha, URI.create(json.path("html_url").asText()));
        } catch (Exception e) {
            System.err.println("Update check failed: " + e);
            return null;
        }
    }

    /** 앱 폴더를 교체할 수 있는지 (앱 폴더의 상위 폴더에 쓰기 권한이 있는지). Program Files 등이면 false. */
    public boolean canReplace() {
        if (!enabled()) return false;
        try {
            Files.delete(Files.createTempFile(appDir().getParent(), ".clipvault", ".tmp"));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 새 버전을 받아 검증하고, 앱 폴더 옆에 풀어 둔 뒤 교체 스크립트를 띄운다.
     * 이 메서드가 정상적으로 끝나면 호출한 쪽은 바로 앱을 종료해야 한다 (스크립트가 종료를 기다린다).
     * 실패하면 IOException을 던지고, 기존 앱은 그대로다.
     */
    public void install(Release r) throws IOException, InterruptedException {
        Path appDir = appDir();
        // 같은 드라이브에 풀어야 스크립트가 폴더를 "이동"이 아니라 "이름 변경"으로 빠르게 바꿀 수 있다
        Path staging = appDir.resolveSibling(appDir.getFileName() + ".update");
        deleteTree(staging);
        Files.createDirectories(staging);
        Path newApp;
        try {
            newApp = stage(r, staging, appDir);
        } catch (IOException | InterruptedException | RuntimeException e) {
            try {
                deleteTree(staging); // 받다 만 파일은 치운다
            } catch (IOException ignored) {
            }
            throw e;
        }
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", staging.resolve("update.ps1").toString(),
                "-ProcId", Long.toString(ProcessHandle.current().pid()),
                "-AppDir", appDir.toString(),
                "-NewDir", newApp.toString(),
                "-Staging", staging.toString(),
                "-Exe", exe.getFileName().toString())
                // 앱이 먼저 꺼져도 스크립트가 출력 파이프 때문에 멈추지 않도록 로그 파일로 돌린다
                .redirectErrorStream(true)
                .redirectOutput(appDir.resolveSibling(appDir.getFileName() + ".update.log").toFile())
                .start();
    }

    /** 새 zip을 staging에 받아 체크섬을 확인하고 풀어 둔다. 교체 스크립트도 같이 넣는다. 풀린 새 앱 폴더를 돌려준다. */
    private Path stage(Release r, Path staging, Path appDir) throws IOException, InterruptedException {
        Path zip = staging.resolve(ZIP);
        HttpResponse<Path> res = http.send(HttpRequest.newBuilder(r.zip()).build(), HttpResponse.BodyHandlers.ofFile(zip));
        if (res.statusCode() != 200) throw new IOException("download failed: " + res.statusCode());
        // .sha256 파일 형식: "<해시>  ClipVault-windows.zip"
        String expected = http.send(HttpRequest.newBuilder(r.sha256()).build(), HttpResponse.BodyHandlers.ofString())
                .body().trim().split("\\s+")[0];
        if (!sha256(zip).equalsIgnoreCase(expected)) throw new IOException("checksum mismatch");

        unzip(zip, staging);
        Files.delete(zip);
        Path newApp = staging.resolve(appDir.getFileName()); // zip 안에는 ClipVault/ 폴더가 들어 있다
        if (!Files.exists(newApp.resolve(exe.getFileName()))) throw new IOException("unexpected zip layout");

        try (InputStream in = Updater.class.getResourceAsStream("update.ps1")) {
            Files.copy(in, staging.resolve("update.ps1"));
        }
        return newApp;
    }

    /** ClipVault.exe가 들어 있는 폴더 */
    private Path appDir() {
        return exe.getParent();
    }

    // --- 아래는 단위 테스트를 위해 패키지 공개로 둔 순수 함수들 ---

    /** "1.10.0"이 "1.9.3"보다 새로운지처럼, 점으로 나눈 숫자를 앞에서부터 비교한다. 숫자가 아니면 false. */
    static boolean isNewer(String latest, String current) {
        String num = "\\d{1,9}(\\.\\d{1,9})*"; // "1.1.0-beta" 같은 형식은 건너뛴다
        if (!latest.matches(num) || !current.matches(num)) return false;
        String[] a = latest.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** 파일의 SHA-256을 소문자 16진수로. */
    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** zip을 dest에 푼다. "../"처럼 dest 밖을 가리키는 항목(zip slip)이 있으면 거부한다. */
    static void unzip(Path zip, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                Path p = root.resolve(e.getName()).normalize();
                if (!p.startsWith(root)) throw new IOException("bad zip entry: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(p);
                } else {
                    Files.createDirectories(p.getParent());
                    Files.copy(in, p);
                }
            }
        }
    }

    /** 폴더를 통째로 지운다 (없으면 아무것도 안 함). */
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
