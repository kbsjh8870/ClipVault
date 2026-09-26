# 이미지 클립보드 동기화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PC에서 복사한 이미지를 텍스트처럼 다른 PC에 동기화한다 (서버 경유, Oracle Object Storage 저장, 목록 썸네일, 10MB 한도).

**Architecture:** 기존 `clips` 테이블에 `type`(TEXT/IMAGE)과 이미지 메타데이터를 추가하고, 이미지 바이트는 AES-GCM으로 암호화해 `ImageStore`(운영 S3, 로컬/테스트 폴더)에 둔다. 업로드·다운로드는 새 `ImageClipController`가 담당하고, 목록·푸시·중복·TTL·삭제는 기존 흐름을 재사용한다. 트레이 앱은 클립보드 이미지를 감지해 PNG로 올리고, 목록에서 썸네일을 보여 주며 클릭 시 원본을 받아 클립보드에 넣는다.

**Tech Stack:** Spring Boot 4.1.1, Spring Data JPA, AWS SDK for Java v2 2.46.7 (S3, url-connection-client), `javax.imageio`, Java Swing (FlatLaf), `java.net.http`.

**Spec:** `docs/superpowers/specs/2026-09-26-image-clipboard-design.md`

## Global Constraints

- 모든 코드 주석은 한국어로, 기존 파일처럼 "왜"를 풀어서 쓴다.
- 이미지 한도: PNG 바이트 기준 10MB = `10 * 1024 * 1024`. 서버 초과 시 `413`.
- 픽셀 한도: 가로×세로 5천만(50_000_000) 초과 시 `400`. PNG가 아니면 `400`.
- 썸네일: 긴 변 최대 240px, 비율 유지, 원본이 작으면 원본 크기. PNG.
- 버킷 객체 이름: `images/{image_key}`, `thumbs/{image_key}`. `image_key`는 무작위 UUID 문자열. 객체는 `AesCipher.encryptBytes` 결과(IV 12바이트 ‖ 암호문).
- 이미지 클립의 `content`: `[이미지 {w}×{h}]` (× 는 U+00D7)를 AES 암호화해 저장.
- 설정 환경변수: `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, 로컬 폴더 `IMAGE_LOCAL_DIR`(기본 `./data/images`). `S3_BUCKET`이 비면 로컬 폴더 저장소.
- DB 컬럼: `type`, `image_key`, `width`, `height`, `image_size` (`size`는 예약어 충돌을 피하려고 `image_size`로 둔다. JSON 필드는 `size`).
- 작업 브랜치: `feat/image-clipboard`. 커밋 메시지·PR 본문에 Claude 관련 문구를 넣지 않는다. 커밋 후 브랜치로 푸시.
- UI(목록 화면) 변경은 커밋 전에 오프스크린 렌더링(`JRootPane.printAll`) 스크린샷으로 사용자 승인을 받는다. 화면 캡처(Robot) 금지.
- 비밀값(S3 키)은 코드·저장소에 넣지 않는다. 운영 값은 사용자가 VM의 `deploy/.env`에 직접 넣는다.

## File Structure

**백엔드** (`backend/src/main/java/com/clipvault/`)
- `common/HashUtil.java` — 수정: `sha256(byte[])` 추가
- `common/AesCipher.java` — 수정: `encryptBytes`/`decryptBytes` 추가, 문자열 메서드가 이를 사용
- `storage/ImageStore.java` — 신규: 저장소 인터페이스 (put/get/delete)
- `storage/LocalImageStore.java` — 신규: 폴더 저장
- `storage/S3ImageStore.java` — 신규: S3 호환 저장
- `storage/StorageConfig.java` — 신규: 설정에 따라 둘 중 하나를 빈으로 등록
- `clip/ClipType.java` — 신규: `TEXT`, `IMAGE`
- `clip/Clip.java` — 수정: 타입·이미지 컬럼, `Clip.image(...)` 팩토리
- `clip/ClipResponse.java` — 수정: 필드 추가, `of(Clip, String)` 팩토리
- `clip/ClipRepository.java` — 수정: `findExpiredImageKeys`
- `clip/ImageBytes.java` — 신규: 헤더로 크기 읽기, 썸네일, PNG 인코딩
- `clip/ImageClipController.java` — 신규: 이미지 업로드/원본/썸네일, 객체 삭제 도우미
- `clip/ClipController.java` — 수정: `ClipResponse.of` 사용, 이미지 클립 삭제 시 객체 삭제
- `clip/ClipCleanupJob.java` — 수정: 만료 이미지 객체 삭제
- `resources/application.yml` — 수정: `clipvault.storage.*`
- `backend/build.gradle` — 수정: AWS SDK

**백엔드 테스트** (`backend/src/test/java/com/clipvault/`)
- `Api.java` — 수정: `postImage`, `png(...)` 도우미
- `common/AesCipherTest.java`, `common/HashUtilTest.java` — 수정
- `storage/LocalImageStoreTest.java`, `storage/StorageConfigTest.java` — 신규
- `clip/ClipApiTest.java` — 수정: `type` 확인, 옛 행(null) 호환
- `clip/ImageClipApiTest.java` — 신규
- `ws/ClipWebSocketTest.java` — 수정: 이미지 푸시
- `resources/application.yml` — 수정: 테스트 로컬 폴더

**트레이 앱** (`tray-client/src/main/java/com/clipvault/client/`)
- `clipboard/Images.java` — 신규: ARGB 변환, 이미지 키, PNG 변환, 한도 상수
- `clipboard/ClipboardWatcher.java` — 수정: 이미지 감지, `writeImage`
- `network/ApiClient.java` — 수정: 바이트 요청, `postImage`/`getImage`/`getThumbnail`
- `ui/ClipListWindow.java` — 수정: 이미지 카드(썸네일), `onPick`이 클립 전체를 받음
- `TrayApp.java` — 수정: 이미지 업로드/알림/붙여넣기/썸네일 캐시
- 테스트: `clipboard/ImagesTest.java` — 신규

**배포/문서**
- `deploy/.env.example`, `deploy/docker-compose.prod.yml`(주석), `docker-compose.yml`(로컬 폴더 경로)
- `docs/API_CONTRACT.md`, `docs/PRD.md`, `docs/TRD.md`, `docs/TASKS.md`, `README.md`

---

### Task 1: 바이트용 해시·암호화

**Files:**
- Modify: `backend/src/main/java/com/clipvault/common/HashUtil.java`
- Modify: `backend/src/main/java/com/clipvault/common/AesCipher.java`
- Test: `backend/src/test/java/com/clipvault/common/HashUtilTest.java`, `AesCipherTest.java`

**Interfaces:**
- Produces: `HashUtil.sha256(byte[]) → String`(소문자 hex), `AesCipher.encryptBytes(byte[]) → byte[]`, `AesCipher.decryptBytes(byte[]) → byte[]` (변조 시 `IllegalStateException`)

- [ ] **Step 1: 실패하는 테스트 작성**

`HashUtilTest`에 추가:
```java
    /** 바이트 버전은 같은 내용의 문자열 버전과 같은 해시를 낸다 */
    @Test
    void bytesMatchStringVersion() {
        assertEquals(HashUtil.sha256("abc"), HashUtil.sha256("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
```

`AesCipherTest`에 추가 (기존 `KEY` 상수 사용):
```java
    /** 바이트 암복호화: 되돌리면 원본, 같은 평문도 매번 다른 암호문(IV 무작위), 길이 = IV 12 + 평문 + 태그 16 */
    @Test
    void bytesRoundTripWithRandomIv() {
        AesCipher cipher = new AesCipher(KEY);
        byte[] plain = {0, 1, 2, (byte) 0xFF, 42};
        byte[] a = cipher.encryptBytes(plain);
        byte[] b = cipher.encryptBytes(plain);
        assertFalse(java.util.Arrays.equals(a, b));
        assertEquals(12 + plain.length + 16, a.length);
        assertArrayEquals(plain, cipher.decryptBytes(a));
    }

    /** 암호문이 1비트라도 바뀌면 복호화가 실패해야 한다 (GCM 무결성 검사) */
    @Test
    void tamperedBytesFailToDecrypt() {
        AesCipher cipher = new AesCipher(KEY);
        byte[] a = cipher.encryptBytes(new byte[]{1, 2, 3});
        a[a.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decryptBytes(a));
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :backend:test --tests '*HashUtilTest' --tests '*AesCipherTest'`
Expected: 컴파일 실패 (`sha256(byte[])`, `encryptBytes` 없음)

- [ ] **Step 3: 구현**

`HashUtil`:
```java
    /** 문자열(UTF-8)의 SHA-256. */
    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 바이트 배열의 SHA-256을 소문자 16진수로. 이미지 중복 판정에 쓴다. */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 자바 런타임에 반드시 포함되어 있어서 실제로는 발생하지 않는다.
            throw new IllegalStateException(e);
        }
    }
```

`AesCipher` (기존 `encrypt`/`decrypt` 본문을 아래로 교체하고 바이트 메서드 추가, 기존 Javadoc은 유지·보강):
```java
    /** 문자열 암호화 → base64(IV ‖ 암호문). DB의 content 컬럼용. */
    public String encrypt(String plain) {
        return Base64.getEncoder().encodeToString(encryptBytes(plain.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@link #encrypt}의 반대. */
    public String decrypt(String cipherText) {
        return new String(decryptBytes(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
    }

    /** 바이트 암호화 → IV(12바이트) ‖ 암호문(GCM 태그 포함). 이미지 파일용 (base64로 늘리지 않고 그대로 저장). */
    public byte[] encryptBytes(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /** {@link #encryptBytes}의 반대. 키가 다르거나 내용이 변조되었으면 IllegalStateException. */
    public byte[] decryptBytes(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :backend:test`
Expected: 전부 PASS (기존 문자열 암호화 테스트 포함)

- [ ] **Step 5: 커밋**
```bash
git add backend/src/main/java/com/clipvault/common backend/src/test/java/com/clipvault/common
git commit -m "feat(backend): 바이트용 SHA-256, AES-GCM 암복호화 추가"
git push
```

---

### Task 2: 이미지 저장소 (ImageStore)

**Files:**
- Create: `backend/src/main/java/com/clipvault/storage/ImageStore.java`, `LocalImageStore.java`, `S3ImageStore.java`, `StorageConfig.java`
- Modify: `backend/build.gradle`, `backend/src/main/resources/application.yml`, `backend/src/test/resources/application.yml`, `docker-compose.yml`, `deploy/.env.example`, `deploy/docker-compose.prod.yml`
- Test: `backend/src/test/java/com/clipvault/storage/LocalImageStoreTest.java`, `StorageConfigTest.java`

**Interfaces:**
- Produces: 빈 `ImageStore` with `void put(String key, byte[] data)`, `byte[] get(String key)` (없으면 null), `void delete(String key)` (없어도 예외 없음). `new StorageConfig().imageStore(bucket, endpoint, region, accessKey, secretKey, localDir)`.

- [ ] **Step 1: 의존성 추가** — `backend/build.gradle`의 `dependencies`에:
```groovy
	// 이미지 저장소(Oracle Object Storage)는 S3 호환 API로 접근한다.
	// 기본 HTTP 클라이언트(Apache, Netty) 대신 가벼운 JDK URLConnection 클라이언트만 쓴다.
	implementation platform('software.amazon.awssdk:bom:2.46.7')
	implementation('software.amazon.awssdk:s3') {
		exclude group: 'software.amazon.awssdk', module: 'apache-client'
		exclude group: 'software.amazon.awssdk', module: 'netty-nio-client'
	}
	implementation 'software.amazon.awssdk:url-connection-client'
```

- [ ] **Step 2: 실패하는 테스트 작성**

`LocalImageStoreTest.java`:
```java
package com.clipvault.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 폴더 저장소: 넣고, 꺼내고, 지우고, 없는 키, 폴더 밖을 가리키는 키 */
class LocalImageStoreTest {
    @TempDir Path dir;

    @Test
    void putGetDelete() {
        LocalImageStore store = new LocalImageStore(dir);
        store.put("images/abc", new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, store.get("images/abc"));
        store.delete("images/abc");
        assertNull(store.get("images/abc"));
    }

    @Test
    void missingKeyReturnsNullAndDeleteIsQuiet() {
        LocalImageStore store = new LocalImageStore(dir);
        assertNull(store.get("thumbs/none"));
        assertDoesNotThrow(() -> store.delete("thumbs/none"));
    }

    @Test
    void keyEscapingRootIsRejected() {
        LocalImageStore store = new LocalImageStore(dir);
        assertThrows(IllegalArgumentException.class, () -> store.put("../evil", new byte[]{1}));
    }
}
```

`StorageConfigTest.java`:
```java
package com.clipvault.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 버킷 설정이 있으면 S3, 없으면 폴더 저장소를 고른다 (네트워크 연결은 하지 않음) */
class StorageConfigTest {
    @Test
    void blankBucketUsesLocal() {
        assertInstanceOf(LocalImageStore.class,
                new StorageConfig().imageStore("", "", "", "", "", "build/tmp-images"));
    }

    @Test
    void bucketUsesS3() {
        assertInstanceOf(S3ImageStore.class, new StorageConfig().imageStore(
                "clipvault-images", "https://ns.compat.objectstorage.ap-chuncheon-1.oraclecloud.com",
                "ap-chuncheon-1", "ak", "sk", "build/tmp-images"));
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :backend:test --tests '*storage*'`
Expected: 컴파일 실패 (클래스 없음)

- [ ] **Step 4: 구현**

`ImageStore.java`:
```java
package com.clipvault.storage;

/**
 * 이미지 파일(이미 암호화된 바이트)을 보관하는 저장소.
 * 운영은 {@link S3ImageStore}(Oracle Object Storage), 로컬 개발·테스트는 {@link LocalImageStore}(폴더).
 * 어느 쪽을 쓸지는 {@link StorageConfig}가 설정을 보고 정한다.
 */
public interface ImageStore {
    /** key 이름으로 저장한다 (있으면 덮어씀). 실패하면 예외. */
    void put(String key, byte[] data);

    /** key의 내용. 없으면 null. */
    byte[] get(String key);

    /** key를 지운다. 없어도 예외를 던지지 않는다. */
    void delete(String key);
}
```

`LocalImageStore.java`:
```java
package com.clipvault.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** 이미지를 로컬 폴더에 파일로 저장한다. key의 "/"는 하위 폴더가 된다 (images/abc → root/images/abc). */
public class LocalImageStore implements ImageStore {
    private final Path root;

    public LocalImageStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] data) {
        try {
            Path p = path(key);
            Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(path(key));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** key를 실제 파일 경로로. "../" 등으로 root 밖을 가리키면 거부한다. */
    private Path path(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("bad key: " + key);
        return p;
    }
}
```

`S3ImageStore.java`:
```java
package com.clipvault.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** S3 호환 버킷(Oracle Object Storage)에 저장한다. 버킷은 비공개로 두고 서버만 키로 접근한다. */
public class S3ImageStore implements ImageStore {
    private final S3Client s3;
    private final String bucket;

    public S3ImageStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] data) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/octet-stream"), RequestBody.fromBytes(data));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key)); // S3는 없는 키 삭제도 성공으로 응답한다
    }
}
```

`StorageConfig.java`:
```java
package com.clipvault.storage;

import java.net.URI;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 이미지 저장소 선택. S3_BUCKET이 설정되어 있으면 Oracle Object Storage(S3 호환), 아니면 로컬 폴더.
 * 로컬 폴더는 개발·테스트용이다. 컨테이너에서 쓰면 컨테이너를 다시 만들 때 이미지가 사라진다.
 */
@Configuration
public class StorageConfig {
    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public ImageStore imageStore(@Value("${clipvault.storage.s3.bucket:}") String bucket,
                                 @Value("${clipvault.storage.s3.endpoint:}") String endpoint,
                                 @Value("${clipvault.storage.s3.region:}") String region,
                                 @Value("${clipvault.storage.s3.access-key:}") String accessKey,
                                 @Value("${clipvault.storage.s3.secret-key:}") String secretKey,
                                 @Value("${clipvault.storage.local-dir:./data/images}") String localDir) {
        if (bucket.isBlank()) {
            log.warn("S3_BUCKET is not set: storing images in local folder {}", localDir);
            return new LocalImageStore(Path.of(localDir));
        }
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                // Oracle의 S3 호환 API는 버킷을 경로에 넣는 방식(https://host/bucket/key)만 지원한다
                .forcePathStyle(true)
                // 최신 SDK가 기본으로 붙이는 추가 체크섬(CRC)은 S3 호환 서비스가 모를 수 있어서, 꼭 필요할 때만 쓴다
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        log.info("Image storage: S3 bucket {} at {}", bucket, endpoint);
        return new S3ImageStore(s3, bucket);
    }
}
```

- [ ] **Step 5: 설정 추가**

`backend/src/main/resources/application.yml`의 `clipvault:` 아래(`clip:` 블록 뒤)에:
```yaml
  storage:
    # 이미지 저장소. S3_BUCKET이 비어 있으면 아래 폴더에 저장한다 (로컬 개발용).
    local-dir: ${IMAGE_LOCAL_DIR:./data/images}
    # Oracle Object Storage (S3 호환). 엔드포인트 예: https://<namespace>.compat.objectstorage.<region>.oraclecloud.com
    s3:
      endpoint: ${S3_ENDPOINT:}
      region: ${S3_REGION:}
      bucket: ${S3_BUCKET:}
      access-key: ${S3_ACCESS_KEY:}
      secret-key: ${S3_SECRET_KEY:}
```

`backend/src/test/resources/application.yml`의 `clipvault:` 아래에:
```yaml
  storage:
    # 테스트는 버킷 없이 build 폴더 아래에 저장한다
    local-dir: build/test-images
```

`docker-compose.yml`의 backend `environment`에 (컨테이너의 /app은 앱 사용자가 쓸 수 없으므로):
```yaml
      IMAGE_LOCAL_DIR: /tmp/clipvault-images
```

`deploy/.env.example` 끝에:
```bash

# 이미지 저장소: Oracle Object Storage (S3 호환). 비워 두면 이미지 업로드가 실패한다(컨테이너 폴더에 쓸 권한 없음).
# 엔드포인트: https://<namespace>.compat.objectstorage.<region>.oraclecloud.com
S3_ENDPOINT=
S3_REGION=ap-chuncheon-1
S3_BUCKET=clipvault-images
# 사용자 프로필 → Customer secret keys 에서 발급
S3_ACCESS_KEY=
S3_SECRET_KEY=
```

`deploy/docker-compose.prod.yml`의 `env_file` 주석을 `# DB_*, JWT_SECRET, CLIP_ENCRYPTION_KEY, S3_*` 로 수정.

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :backend:test`
Expected: 전부 PASS (스프링 컨텍스트가 LocalImageStore 빈으로 뜸)

- [ ] **Step 7: 커밋**
```bash
git add backend docker-compose.yml deploy
git commit -m "feat(backend): 이미지 저장소 추가 (Oracle Object Storage S3 호환 / 로컬 폴더)"
git push
```

---

### Task 3: 클립 타입과 이미지 메타데이터

**Files:**
- Create: `backend/src/main/java/com/clipvault/clip/ClipType.java`
- Modify: `clip/Clip.java`, `clip/ClipResponse.java`, `clip/ClipRepository.java`, `clip/ClipController.java`
- Test: `backend/src/test/java/com/clipvault/clip/ClipApiTest.java`

**Interfaces:**
- Produces: `enum ClipType { TEXT, IMAGE }`; `Clip.image(UUID userId, UUID sourceDeviceId, String encryptedLabel, String contentHash, String imageKey, int width, int height, long size, Instant createdAt, Instant expiresAt)`; `Clip.getType()`(null → TEXT), `getImageKey()`, `getWidth()`, `getHeight()`, `getSize()`; `record ClipResponse(UUID id, ClipType type, String content, String contentHash, UUID sourceDeviceId, Instant createdAt, Instant expiresAt, Integer width, Integer height, Long size)` + `static ClipResponse of(Clip c, String plain)`; `ClipRepository.findExpiredImageKeys(Instant now) → List<String>`

- [ ] **Step 1: 실패하는 테스트 작성** — `ClipApiTest`에서

`createReturns201WithClipResponse` 끝에 추가:
```java
        assertEquals("TEXT", read(body, "$.type"));
        assertNull(read(body, "$.width"));
```

새 테스트 추가:
```java
    /** 이미지 기능 이전에 저장된 행(type 컬럼이 비어 있음)은 TEXT로 응답해야 한다 */
    @Test
    void legacyRowWithoutTypeIsText() throws Exception {
        String id = read(api.createClip(dev.accessToken(), "old row", 201), "$.id");
        jdbc.update("update " + clipTable() + " set type = null where cast(id as varchar) = ?", id);
        api.get("/api/clips", dev.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].type").value("TEXT"));
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :backend:test --tests '*ClipApiTest'`
Expected: FAIL (`$.type` 없음, `type` 컬럼 없음)

- [ ] **Step 3: 구현**

`ClipType.java`:
```java
package com.clipvault.clip;

/** 클립 종류. IMAGE는 내용이 버킷에 있고 DB에는 메타데이터만 있다. */
public enum ClipType {
    TEXT,
    IMAGE
}
```

`Clip.java` — 필드 추가 (import `jakarta.persistence.EnumType`, `jakarta.persistence.Enumerated`):
```java
    /** 클립 종류. 이미지 기능 이전에 저장된 행은 null이며 TEXT로 취급한다 ({@link #getType()}). */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ClipType type;

    /** 버킷 객체 이름용 무작위 키 (images/{key}, thumbs/{key}). 이미지 클립만. */
    @Column(name = "image_key", length = 64)
    private String imageKey;

    /** 원본 이미지 가로/세로(px). 이미지 클립만. */
    private Integer width;
    private Integer height;

    /** 원본 PNG 바이트 수. 이미지 클립만. (컬럼명 size는 DB 예약어와 겹칠 수 있어 image_size) */
    @Column(name = "image_size")
    private Long size;
```
기존 생성자 끝에 `this.type = ClipType.TEXT;` 추가. 팩토리와 게터 추가:
```java
    /** 이미지 클립 생성. content에는 "[이미지 W×H]" 안내 문구의 암호문을 넣는다 (구버전 앱 표시용). */
    public static Clip image(UUID userId, UUID sourceDeviceId, String encryptedLabel, String contentHash,
                             String imageKey, int width, int height, long size, Instant createdAt, Instant expiresAt) {
        Clip c = new Clip(userId, sourceDeviceId, encryptedLabel, contentHash, createdAt, expiresAt);
        c.type = ClipType.IMAGE;
        c.imageKey = imageKey;
        c.width = width;
        c.height = height;
        c.size = size;
        return c;
    }

    public ClipType getType() { return type == null ? ClipType.TEXT : type; }
    public String getImageKey() { return imageKey; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public Long getSize() { return size; }
```

`ClipResponse.java` 전체 교체 (Javadoc 유지·보강):
```java
package com.clipvault.clip;

import java.time.Instant;
import java.util.UUID;

/**
 * 클립 API 응답이자 WebSocket 푸시 메시지.
 * content는 항상 평문. 이미지 클립은 content에 "[이미지 W×H]" 안내 문구가 들어가고
 * width/height/size가 채워진다 (텍스트 클립은 null).
 */
public record ClipResponse(UUID id, ClipType type, String content, String contentHash, UUID sourceDeviceId,
                           Instant createdAt, Instant expiresAt, Integer width, Integer height, Long size) {

    /** 엔티티 + 복호화한 content로 응답을 만든다. */
    public static ClipResponse of(Clip c, String plain) {
        return new ClipResponse(c.getId(), c.getType(), plain, c.getContentHash(), c.getSourceDeviceId(),
                c.getCreatedAt(), c.getExpiresAt(), c.getWidth(), c.getHeight(), c.getSize());
    }
}
```

`ClipController.java`: `toResponse(clip, x)` 호출을 모두 `ClipResponse.of(clip, x)`로 바꾸고 private `toResponse` 메서드 삭제.

`ClipRepository.java`에 추가:
```java
    /** 만료된 이미지 클립들의 버킷 키. 행을 지우기 전에 버킷 객체부터 지우는 데 쓴다. */
    @Query("select c.imageKey from Clip c where c.expiresAt <= :now and c.imageKey is not null")
    List<String> findExpiredImageKeys(@Param("now") Instant now);
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :backend:test`
Expected: 전부 PASS

- [ ] **Step 5: 커밋**
```bash
git add backend/src
git commit -m "feat(backend): 클립 타입(TEXT/IMAGE)과 이미지 메타데이터 컬럼 추가"
git push
```

---

### Task 4: 이미지 업로드·다운로드 API

**Files:**
- Create: `backend/src/main/java/com/clipvault/clip/ImageBytes.java`, `ImageClipController.java`
- Modify: `backend/src/test/java/com/clipvault/Api.java`
- Test: `backend/src/test/java/com/clipvault/clip/ImageClipApiTest.java`

**Interfaces:**
- Consumes: Task 1(`HashUtil.sha256(byte[])`, `AesCipher.encryptBytes/decryptBytes`), Task 2(`ImageStore`), Task 3(`Clip.image`, `ClipResponse.of`, `ClipType`)
- Produces: `POST /api/clips/image`, `GET /api/clips/{id}/image`, `GET /api/clips/{id}/thumbnail`; `ImageClipController.IMAGES = "images/"`, `THUMBS = "thumbs/"`, `static void deleteQuietly(ImageStore store, String imageKey)`; 테스트 도우미 `Api.postImage(String token, byte[] png) → ResultActions`, `Api.png(int w, int h, int rgb) → byte[]`

- [ ] **Step 1: 테스트 도우미** — `Api.java`에 추가 (import `java.awt.image.BufferedImage`, `javax.imageio.ImageIO`, `java.io.ByteArrayOutputStream`):
```java
    /** 이미지 업로드 (본문 = PNG 바이트, Content-Type: image/png). */
    public ResultActions postImage(String token, byte[] png) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/clips/image").contentType(MediaType.IMAGE_PNG).content(png);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    /** 한 가지 색으로 칠한 w×h PNG를 만든다. 색이 다르면 다른 이미지(다른 해시). */
    public static byte[] png(int w, int h, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, rgb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
```

- [ ] **Step 2: 실패하는 테스트 작성** — `ImageClipApiTest.java`:
```java
package com.clipvault.clip;

import com.clipvault.Api;
import com.clipvault.common.HashUtil;
import com.clipvault.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static com.clipvault.Api.png;
import static com.clipvault.Api.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 이미지 클립 API 통합 테스트: 업로드, 중복, 목록, 원본/썸네일, 암호화 저장, 권한, 크기·형식 검사. */
@SpringBootTest
@AutoConfigureMockMvc
class ImageClipApiTest {
    @Autowired MockMvc mvc;
    @Autowired ClipRepository clipRepository;
    @Autowired ImageStore store;
    @Autowired JdbcTemplate jdbc;
    Api api;
    Api.DeviceTokens dev;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        dev = api.newUserWithDevice();
    }

    /** 이미지를 올리고 응답 본문을 돌려준다. */
    private String upload(byte[] png, int expectedStatus) throws Exception {
        return api.postImage(dev.accessToken(), png).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    /** 응답 본문의 바이트 (원본/썸네일 다운로드). */
    private byte[] bytes(String url, String token, int expectedStatus) throws Exception {
        return api.get(url, token).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsByteArray();
    }

    /** 업로드 → 201, 이미지 정보가 채워지고 content는 안내 문구 */
    @Test
    void uploadReturns201WithImageFields() throws Exception {
        byte[] png = png(300, 200, 0xFF0000);
        String body = upload(png, 201);
        assertEquals("IMAGE", read(body, "$.type"));
        assertEquals(300, (int) read(body, "$.width"));
        assertEquals(200, (int) read(body, "$.height"));
        assertEquals(png.length, ((Number) read(body, "$.size")).intValue());
        assertEquals(HashUtil.sha256(png), read(body, "$.contentHash"));
        assertEquals("[이미지 300×200]", read(body, "$.content"));
        assertEquals(dev.deviceId(), read(body, "$.sourceDeviceId"));
    }

    /** 가장 최근 클립과 같은 이미지 → 200, 같은 ID, 새 행 없음 */
    @Test
    void sameImageAsLatestReturns200() throws Exception {
        byte[] png = png(50, 50, 0x00FF00);
        String first = upload(png, 201);
        long count = clipRepository.count();
        String second = upload(png, 200);
        assertEquals(read(first, "$.id").toString(), read(second, "$.id").toString());
        assertEquals(count, clipRepository.count());
    }

    /** 목록에 텍스트와 이미지가 최신순으로 섞여 나온다 */
    @Test
    void listMixesTextAndImageNewestFirst() throws Exception {
        api.createClip(dev.accessToken(), "text first", 201);
        Thread.sleep(20);
        upload(png(10, 10, 0x0000FF), 201);
        String list = api.get("/api/clips", dev.accessToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("IMAGE", read(list, "$[0].type"));
        assertEquals("TEXT", read(list, "$[1].type"));
    }

    /** 원본 다운로드는 올린 바이트와 똑같고 image/png */
    @Test
    void originalMatchesUpload() throws Exception {
        byte[] png = png(64, 32, 0x123456);
        String id = read(upload(png, 201), "$.id");
        api.get("/api/clips/" + id + "/image", dev.accessToken())
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png));
    }

    /** 썸네일은 긴 변 240px로 줄고 비율 유지 (300×200 → 240×160) */
    @Test
    void thumbnailIsScaledTo240() throws Exception {
        String id = read(upload(png(300, 200, 0xABCDEF), 201), "$.id");
        BufferedImage t = ImageIO.read(new ByteArrayInputStream(bytes("/api/clips/" + id + "/thumbnail", dev.accessToken(), 200)));
        assertEquals(240, t.getWidth());
        assertEquals(160, t.getHeight());
    }

    /** 원본이 240px보다 작으면 썸네일도 원본 크기 */
    @Test
    void smallImageThumbnailKeepsSize() throws Exception {
        String id = read(upload(png(100, 50, 0x777777), 201), "$.id");
        BufferedImage t = ImageIO.read(new ByteArrayInputStream(bytes("/api/clips/" + id + "/thumbnail", dev.accessToken(), 200)));
        assertEquals(100, t.getWidth());
        assertEquals(50, t.getHeight());
    }

    /** 저장소에는 암호문만 있다 (PNG 시그니처가 보이면 안 됨) */
    @Test
    void storedObjectsAreEncrypted() throws Exception {
        byte[] png = png(20, 20, 0x999999);
        String id = read(upload(png, 201), "$.id");
        String key = clipRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getImageKey();
        byte[] stored = store.get("images/" + key);
        assertNotNull(stored);
        assertNotNull(store.get("thumbs/" + key));
        assertFalse(Arrays.equals(png, stored));
        assertFalse(stored[1] == 'P' && stored[2] == 'N' && stored[3] == 'G');
    }

    /** 남의 이미지 → 404, 텍스트 클립의 /image → 404 */
    @Test
    void othersImageAndTextClipAre404() throws Exception {
        String id = read(upload(png(10, 10, 0x010101), 201), "$.id");
        Api.DeviceTokens stranger = api.newUserWithDevice();
        bytes("/api/clips/" + id + "/image", stranger.accessToken(), 404);
        bytes("/api/clips/" + id + "/thumbnail", stranger.accessToken(), 404);
        String textId = read(api.createClip(dev.accessToken(), "just text", 201), "$.id");
        bytes("/api/clips/" + textId + "/image", dev.accessToken(), 404);
    }

    /** 10MB 초과 → 413 */
    @Test
    void over10MbIs413() throws Exception {
        api.postImage(dev.accessToken(), new byte[10 * 1024 * 1024 + 1]).andExpect(status().isPayloadTooLarge());
    }

    /** 이미지가 아닌 바이트 → 400 */
    @Test
    void notAnImageIs400() throws Exception {
        api.postImage(dev.accessToken(), "hello".getBytes()).andExpect(status().isBadRequest());
    }

    /** 픽셀이 5천만 개를 넘는 이미지(압축 폭탄 방지) → 400. 1비트 흑백이라 파일은 작다 */
    @Test
    void tooManyPixelsIs400() throws Exception {
        BufferedImage huge = new BufferedImage(8000, 7000, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(huge, "png", out);
        api.postImage(dev.accessToken(), out.toByteArray()).andExpect(status().isBadRequest());
    }

    /** 토큰 없이 → 401 또는 403 (기존 Clips API와 같은 보안 규칙) */
    @Test
    void uploadWithoutTokenIsRejected() throws Exception {
        int s = api.postImage(null, png(5, 5, 0)).andReturn().getResponse().getStatus();
        assertTrue(s == 401 || s == 403, "status " + s);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :backend:test --tests '*ImageClipApiTest'`
Expected: FAIL (엔드포인트 없음 → 404/401 등)

- [ ] **Step 4: 구현**

`ImageBytes.java`:
```java
package com.clipvault.clip;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/** 이미지 바이트 도우미: 헤더만 읽어 크기 확인, 썸네일 만들기, PNG 인코딩. */
final class ImageBytes {
    private ImageBytes() {
    }

    /**
     * PNG의 가로·세로를 헤더만 읽어서 알아낸다 (픽셀을 풀지 않으므로 거대한 이미지도 안전).
     * PNG가 아니거나 읽을 수 없으면 IllegalArgumentException.
     */
    static Dimension dimensions(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IllegalArgumentException("not an image");
            ImageReader r = readers.next();
            try {
                if (!"png".equalsIgnoreCase(r.getFormatName())) throw new IllegalArgumentException("not a png");
                r.setInput(in);
                return new Dimension(r.getWidth(0), r.getHeight(0));
            } finally {
                r.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("not an image", e);
        }
    }

    /** 긴 변이 max px가 되도록 비율을 유지해 줄인 PNG. 원본이 더 작으면 원본 크기 그대로. */
    static byte[] thumbnail(BufferedImage src, int max) {
        double scale = Math.min(1.0, (double) max / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return png(out);
    }

    /** 이미지를 PNG 바이트로. */
    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`ImageClipController.java`:
```java
package com.clipvault.clip;

import com.clipvault.auth.AuthUser;
import com.clipvault.common.AesCipher;
import com.clipvault.common.HashUtil;
import com.clipvault.storage.ImageStore;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 이미지 클립 API.
 *
 * <ul>
 *   <li>POST /api/clips/image : 본문 = PNG 바이트. 검사 → 썸네일 생성 → 암호화해 버킷 저장 → DB 저장 → 푸시</li>
 *   <li>GET /api/clips/{id}/image, /thumbnail : 버킷에서 꺼내 복호화한 PNG</li>
 * </ul>
 *
 * <p>목록, 삭제, 만료는 텍스트와 같은 {@link ClipController}, {@link ClipCleanupJob}이 처리한다.</p>
 */
@RestController
@RequestMapping("/api/clips")
public class ImageClipController {
    private static final Logger log = LoggerFactory.getLogger(ImageClipController.class);

    /** 이미지 한 장 최대 크기 (PNG 바이트) */
    static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 최대 픽셀 수. 작은 파일이 풀면 수 GB가 되는 압축 폭탄을 막는다 */
    static final long MAX_PIXELS = 50_000_000L;
    /** 썸네일 긴 변 (px) */
    static final int THUMB_SIZE = 240;
    /** 버킷 객체 이름 앞부분 */
    public static final String IMAGES = "images/";
    public static final String THUMBS = "thumbs/";

    private final ClipRepository clips;
    private final AesCipher cipher;
    private final ImageStore store;
    private final SimpMessagingTemplate messaging;
    private final Duration ttl;

    public ImageClipController(ClipRepository clips, AesCipher cipher, ImageStore store, SimpMessagingTemplate messaging,
                               @Value("${clipvault.clip.ttl}") Duration ttl) {
        this.clips = clips;
        this.cipher = cipher;
        this.store = store;
        this.messaging = messaging;
        this.ttl = ttl;
    }

    /** 이미지 업로드. 신규 201, 가장 최근 클립과 같은 이미지면 시각만 갱신하고 200. */
    @PostMapping(path = "/image", consumes = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<ClipResponse> upload(@AuthenticationPrincipal AuthUser me, HttpServletRequest req) throws IOException {
        // 1. 크기: 본문을 읽기 전에 Content-Length로 거절하고, 읽을 때도 한도+1바이트까지만 읽는다
        long length = req.getContentLengthLong();
        if (length < 0 || length > MAX_BYTES) throw tooLarge();
        byte[] png = req.getInputStream().readNBytes((int) MAX_BYTES + 1);
        if (png.length > MAX_BYTES) throw tooLarge();

        // 2. 형식·픽셀 수: 헤더만 읽어서 확인 (픽셀을 풀기 전에)
        Dimension dim;
        try {
            dim = ImageBytes.dimensions(png);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must be a PNG image");
        }
        if ((long) dim.width * dim.height > MAX_PIXELS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image has too many pixels");
        }

        // 3. 중복: 가장 최근 클립과 같은 이미지면 시각만 갱신 (버킷 작업 없음)
        String hash = HashUtil.sha256(png);
        Instant now = Instant.now();
        Clip latest = clips.findFirstByUserIdOrderByCreatedAtDesc(me.userId()).orElse(null);
        if (latest != null && latest.getContentHash().equals(hash)) {
            latest.refresh(me.deviceId(), now, now.plus(ttl));
            Clip saved = clips.save(latest);
            return push(me, ClipResponse.of(saved, cipher.decrypt(saved.getContent())), HttpStatus.OK);
        }

        // 4. 썸네일을 만들고 원본·썸네일을 암호화해서 버킷에 저장
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must be a PNG image");
        byte[] thumb = ImageBytes.thumbnail(img, THUMB_SIZE);
        String key = UUID.randomUUID().toString();
        try {
            store.put(IMAGES + key, cipher.encryptBytes(png));
            store.put(THUMBS + key, cipher.encryptBytes(thumb));
        } catch (RuntimeException e) {
            log.error("Image storage failed", e);
            deleteQuietly(store, key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage unavailable");
        }

        // 5. DB 저장. 실패하면 방금 올린 객체를 지워 고아 파일을 남기지 않는다
        String label = "[이미지 " + dim.width + "×" + dim.height + "]";
        Clip saved;
        try {
            saved = clips.save(Clip.image(me.userId(), me.deviceId(), cipher.encrypt(label), hash, key,
                    dim.width, dim.height, png.length, now, now.plus(ttl)));
        } catch (RuntimeException e) {
            deleteQuietly(store, key);
            throw e;
        }
        return push(me, ClipResponse.of(saved, label), HttpStatus.CREATED);
    }

    /** 원본 PNG */
    @GetMapping(path = "/{id}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] image(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return load(me, id, IMAGES);
    }

    /** 썸네일 PNG (긴 변 최대 240px) */
    @GetMapping(path = "/{id}/thumbnail", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] thumbnail(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return load(me, id, THUMBS);
    }

    /** 이미지 클립의 두 객체(원본, 썸네일)를 지운다. 실패해도 예외 없이 로그만 (남은 객체는 버킷 수명 주기 규칙이 정리). */
    public static void deleteQuietly(ImageStore store, String imageKey) {
        for (String prefix : new String[]{IMAGES, THUMBS}) {
            try {
                store.delete(prefix + imageKey);
            } catch (RuntimeException e) {
                log.warn("Failed to delete {}{}", prefix, imageKey, e);
            }
        }
    }

    /** 내 이미지 클립의 객체를 복호화해서 돌려준다. 남의 클립, 텍스트 클립, 객체 없음 → 404. */
    private byte[] load(AuthUser me, UUID id, String prefix) {
        Clip c = clips.findByIdAndUserId(id, me.userId())
                .filter(x -> x.getImageKey() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
        byte[] data = store.get(prefix + c.getImageKey());
        if (data == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
        return cipher.decryptBytes(data);
    }

    /** 내 기기들에 실시간 알림을 보내고 응답한다. */
    private ResponseEntity<ClipResponse> push(AuthUser me, ClipResponse body, HttpStatus status) {
        messaging.convertAndSend("/topic/clips/" + me.userId(), body);
        return ResponseEntity.status(status).body(body);
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image must be at most 10MB");
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :backend:test`
Expected: 전부 PASS

- [ ] **Step 6: 커밋**
```bash
git add backend/src
git commit -m "feat(backend): 이미지 클립 업로드/원본/썸네일 API 추가"
git push
```

---

### Task 5: 삭제·만료 시 객체 정리, 이미지 푸시, API 계약서

**Files:**
- Modify: `backend/src/main/java/com/clipvault/clip/ClipController.java`, `ClipCleanupJob.java`
- Modify: `backend/src/test/java/com/clipvault/clip/ImageClipApiTest.java`, `ws/ClipWebSocketTest.java`
- Modify: `docs/API_CONTRACT.md`

**Interfaces:**
- Consumes: `ImageClipController.deleteQuietly`, `ClipRepository.findExpiredImageKeys`, `ImageStore`
- Produces: `ClipCleanupJob.deleteExpired()` (시그니처·반환값 그대로)

- [ ] **Step 1: 실패하는 테스트 작성**

`ImageClipApiTest`에 추가 (`ClipCleanupJob` 주입: `@Autowired ClipCleanupJob cleanupJob;`, import `java.sql.Timestamp`, `java.time.Instant`):
```java
    /** 이미지 클립을 삭제하면 버킷의 원본·썸네일도 지워진다 */
    @Test
    void deleteRemovesObjects() throws Exception {
        String id = read(upload(png(10, 10, 0x0A0A0A), 201), "$.id");
        String key = clipRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getImageKey();
        api.delete("/api/clips/" + id, dev.accessToken()).andExpect(status().isNoContent());
        assertNull(store.get("images/" + key));
        assertNull(store.get("thumbs/" + key));
    }

    /** 만료 정리 작업이 이미지 행과 버킷 객체를 모두 지운다 */
    @Test
    void cleanupRemovesExpiredObjects() throws Exception {
        String id = read(upload(png(10, 10, 0x0B0B0B), 201), "$.id");
        String key = clipRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getImageKey();
        jdbc.update("update clips set expires_at = ? where cast(id as varchar) = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), id);
        assertTrue(cleanupJob.deleteExpired() >= 1);
        assertTrue(clipRepository.findById(java.util.UUID.fromString(id)).isEmpty());
        assertNull(store.get("images/" + key));
        assertNull(store.get("thumbs/" + key));
    }
```

`ClipWebSocketTest`에 추가:
```java
    /** 이미지 업로드도 같은 topic으로 푸시되고, type/width/height가 담긴다 */
    @Test
    void imageUploadIsPushedWithType() throws Exception {
        String email = Api.uniqueEmail();
        api.signup(email);
        Api.Tokens user = api.login(email);
        Api.DeviceTokens a = api.registerDevice(user, "A");
        Api.DeviceTokens b = api.registerDevice(user, "B");

        Recorder rec = new Recorder();
        StompSession session = connect(a.accessToken(), rec).get(3, TimeUnit.SECONDS);
        session.subscribe("/topic/clips/" + user.userId(), rec);
        Thread.sleep(300); // SUBSCRIBE가 서버 브로커에 등록될 시간

        api.postImage(b.accessToken(), Api.png(40, 30, 0x336699));
        Map<String, Object> msg = rec.messages.poll(3, TimeUnit.SECONDS);
        assertNotNull(msg, "image push not received within 3s");
        assertEquals("IMAGE", msg.get("type"));
        assertEquals(40, msg.get("width"));
        assertEquals(30, msg.get("height"));
        assertEquals(b.deviceId(), msg.get("sourceDeviceId"));
        session.disconnect();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :backend:test --tests '*ImageClipApiTest' --tests '*ClipWebSocketTest'`
Expected: `deleteRemovesObjects`, `cleanupRemovesExpiredObjects` FAIL (객체가 남음). 푸시 테스트는 Task 4 구현으로 이미 PASS일 수 있다 (회귀 방지용으로 유지).

- [ ] **Step 3: 구현**

`ClipController`: 생성자에 `ImageStore store` 추가(필드 `private final ImageStore store;`, import `com.clipvault.storage.ImageStore`), `delete`를:
```java
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        Clip clip = clips.findByIdAndUserId(id, me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clip not found"));
        clips.delete(clip);
        // 이미지 클립이면 버킷의 원본·썸네일도 지운다 (실패해도 행 삭제는 유지, 남은 객체는 수명 주기 규칙이 정리)
        if (clip.getImageKey() != null) ImageClipController.deleteQuietly(store, clip.getImageKey());
    }
```

`ClipCleanupJob`: 생성자에 `ImageStore store` 추가, `deleteExpired`를:
```java
    public int deleteExpired() {
        Instant now = Instant.now();
        // 행을 지우면 버킷 키를 알 수 없으므로 버킷 객체부터 지운다
        for (String key : clips.findExpiredImageKeys(now)) ImageClipController.deleteQuietly(store, key);
        int deleted = clips.deleteExpired(now);
        log.info("Deleted {} expired clips", deleted);
        return deleted;
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :backend:test`
Expected: 전부 PASS

- [ ] **Step 5: API 계약서 갱신** — `docs/API_CONTRACT.md` 3장 Clips 섹션:
  - `POST /api/clips` 아래에 이미지 엔드포인트 3개를 스펙 3.2절 내용 그대로 추가 (413/400/503/404 규칙, PNG만, 10MB, 5천만 픽셀, 중복 200 + 푸시).
  - `DELETE /api/clips/{id}`: "이미지 클립이면 버킷 객체(원본·썸네일)도 삭제" 추가.
  - ClipResponse를 `{id, type, content, contentHash, sourceDeviceId, createdAt, expiresAt, width, height, size}`로 바꾸고 설명 추가: `type`은 `"TEXT"`/`"IMAGE"`(옛 행은 TEXT), 이미지 클립의 content는 `[이미지 W×H]`, 텍스트 클립은 width/height/size가 null, 이미지 `contentHash` = PNG 바이트의 SHA-256.
  - 4장 WebSocket: "이미지 업로드도 같은 topic으로 ClipResponse(type=IMAGE)를 보낸다" 추가.
  - 5장 모듈 시그니처: `HashUtil.sha256(byte[])`, `AesCipher.encryptBytes/decryptBytes`, `com.clipvault.storage.ImageStore`(put/get/delete) 추가.

- [ ] **Step 6: 커밋**
```bash
git add backend/src docs/API_CONTRACT.md
git commit -m "feat(backend): 이미지 클립 삭제·만료 시 버킷 객체 정리, API 계약서 반영"
git push
```

---

### Task 6: 트레이 앱 이미지 도우미 (Images)

**Files:**
- Create: `tray-client/src/main/java/com/clipvault/client/clipboard/Images.java`
- Test: `tray-client/src/test/java/com/clipvault/client/clipboard/ImagesTest.java`

**Interfaces:**
- Produces: `Images.MAX_BYTES = 10L * 1024 * 1024`; `static BufferedImage toArgb(Image)`; `static String key(BufferedImage)` (가로·세로 + 픽셀의 SHA-256 hex); `static byte[] toPng(BufferedImage)`; `static BufferedImage fromPng(byte[])` (실패 시 `UncheckedIOException`)

- [ ] **Step 1: 실패하는 테스트 작성** — `ImagesTest.java`:
```java
package com.clipvault.client.clipboard;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class ImagesTest {
    /** w×h를 한 색으로 칠한 이미지 */
    private static BufferedImage solid(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        return img;
    }

    @Test
    void samePixelsGiveSameKey() {
        assertEquals(Images.key(solid(4, 3, 0xFF112233)), Images.key(solid(4, 3, 0xFF112233)));
    }

    @Test
    void differentPixelOrSizeGivesDifferentKey() {
        BufferedImage a = solid(4, 3, 0xFF112233);
        BufferedImage b = solid(4, 3, 0xFF112233);
        b.setRGB(0, 0, 0xFF000000);
        assertNotEquals(Images.key(a), Images.key(b));
        // 픽셀 수와 색이 같아도 모양이 다르면 다른 이미지
        assertNotEquals(Images.key(solid(4, 3, 0xFF112233)), Images.key(solid(3, 4, 0xFF112233)));
    }

    @Test
    void toArgbConvertsRgbImages() {
        BufferedImage rgb = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        rgb.setRGB(1, 1, 0x00ABCDEF);
        BufferedImage argb = Images.toArgb(rgb);
        assertEquals(BufferedImage.TYPE_INT_ARGB, argb.getType());
        assertEquals(0xFFABCDEF, argb.getRGB(1, 1));
    }

    @Test
    void pngRoundTripKeepsPixels() {
        BufferedImage img = solid(5, 7, 0xFF445566);
        img.setRGB(2, 3, 0x80FF0000); // 반투명 픽셀도 보존
        assertEquals(Images.key(img), Images.key(Images.toArgb(Images.fromPng(Images.toPng(img)))));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :tray-client:test --tests '*ImagesTest'`
Expected: 컴파일 실패 (`Images` 없음)

- [ ] **Step 3: 구현** — `Images.java`:
```java
package com.clipvault.client.clipboard;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 클립보드 이미지 도우미: 형식 통일(ARGB), 같은 이미지인지 판정할 키, PNG 변환. */
public final class Images {
    /** 서버가 받는 최대 크기 (PNG 바이트). 넘으면 업로드하지 않는다. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private Images() {
    }

    /** 어떤 이미지든 픽셀 형식을 ARGB(투명도 포함 32비트)로 통일한다. 키 계산이 형식에 따라 달라지지 않도록. */
    public static BufferedImage toArgb(Image img) {
        if (img instanceof BufferedImage b && b.getType() == BufferedImage.TYPE_INT_ARGB) return b;
        BufferedImage out = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** 가로·세로와 모든 픽셀의 SHA-256. 픽셀이 하나라도 다르면 다른 키. */
    public static String key(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = ByteBuffer.allocate(8 + px.length * 4);
        buf.putInt(w).putInt(h);
        buf.asIntBuffer().put(px);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buf.array()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PNG 바이트로. */
    public static byte[] toPng(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PNG 바이트를 이미지로. 이미지가 아니면 UncheckedIOException. */
    public static BufferedImage fromPng(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) throw new IOException("not an image");
            return img;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```
주의: `buf.asIntBuffer()`는 현재 위치(8바이트 뒤)부터의 뷰라서 가로·세로 뒤에 픽셀이 이어진다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :tray-client:test`
Expected: 전부 PASS

- [ ] **Step 5: 커밋**
```bash
git add tray-client/src
git commit -m "feat(tray-client): 클립보드 이미지 도우미(키, PNG 변환) 추가"
git push
```

---

### Task 7: 클립보드 이미지 감지·쓰기 (ClipboardWatcher)

**Files:**
- Modify: `tray-client/src/main/java/com/clipvault/client/clipboard/ClipboardWatcher.java`
- Modify: `tray-client/src/main/java/com/clipvault/client/TrayApp.java` (생성자 호출만 임시 수정해서 컴파일 유지)
- Check: 스크래치패드 `WatchCheck.java` (커밋하지 않음)

**Interfaces:**
- Consumes: `Images.toArgb`, `Images.key`
- Produces: `new ClipboardWatcher(Consumer<String> onText, BiConsumer<BufferedImage, String> onImage)` — onImage는 (ARGB 이미지, 이미지 키); `void write(String text)`; `String writeImage(BufferedImage img)` → 기록한 이미지 키

- [ ] **Step 1: 구현** — `ClipboardWatcher`의 필드/메서드를 아래로 바꾼다 (클래스 Javadoc에 "텍스트가 없고 이미지만 있으면 이미지로 처리, 텍스트 우선" 추가):
```java
    private final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    private final Consumer<String> onText;
    private final BiConsumer<BufferedImage, String> onImage;
    /** 마지막으로 본 내용의 키. 텍스트는 "t:" + 내용, 이미지는 "i:" + 이미지 키. 같으면 변화 없음으로 본다. */
    private String last;
    /** 이미지 읽기를 몇 번의 폴링 뒤에 할지. 큰 이미지는 읽는 데 오래 걸리므로 간격을 늘린다. */
    private int imageSkip;

    /** 클립보드에서 읽은 새 내용. text와 image 중 하나만 채워진다. */
    private record Content(String key, String text, BufferedImage image) {}

    public ClipboardWatcher(Consumer<String> onText, BiConsumer<BufferedImage, String> onImage) {
        this.onText = onText;
        this.onImage = onImage;
    }

    public void start() {
        // 앱을 켜기 전부터 클립보드에 있던 내용은 업로드하지 않도록, 현재 내용을 "이미 본 것"으로 기록
        Content now = read();
        last = now == null ? null : now.key();
        clipboard.addFlavorListener(e -> check());
        // 1초마다 check() 실행. true = 데몬 스레드(앱이 종료될 때 이 타이머 때문에 프로세스가 안 꺼지는 일이 없도록)
        new Timer("clipboard-poll", true).schedule(new TimerTask() {
            @Override public void run() { check(); }
        }, 1000, 1000);
    }

    /** 텍스트를 클립보드에 넣는다. 우리가 넣은 것이므로 "이미 본 것"으로 기록해 다시 업로드하지 않는다. */
    public synchronized void write(String text) {
        clipboard.setContents(new StringSelection(text), null);
        last = "t:" + text;
    }

    /**
     * 이미지를 클립보드에 넣고, 넣은 직후 다시 읽은 이미지의 키를 "이미 본 것"으로 기록한다.
     * 윈도우 클립보드를 거치며 픽셀(투명도 등)이 달라져도 되돌려 보내지 않기 위해 다시 읽은 값을 쓴다.
     *
     * @return 기록한 이미지 키 (EchoGuard에도 같은 키를 기록하는 데 쓴다)
     */
    public synchronized String writeImage(BufferedImage img) {
        clipboard.setContents(new ImageSelection(img), null);
        String key = Images.key(img);
        try {
            if (clipboard.getData(DataFlavor.imageFlavor) instanceof Image back) key = Images.key(Images.toArgb(back));
        } catch (Exception ignored) {
            // 다시 읽기에 실패하면 넣은 이미지의 키를 그대로 쓴다
        }
        last = "i:" + key;
        return key;
    }

    private synchronized void check() {
        Content now = read();
        if (now == null || now.key().equals(last)) return;
        last = now.key();
        if (now.text() != null) onText.accept(now.text());
        else onImage.accept(now.image(), now.key().substring(2));
    }

    /**
     * 지금 클립보드 내용. 텍스트가 있으면 텍스트(우선), 없고 이미지가 있으면 이미지.
     * 읽을 게 없거나, 이번 폴링에서 이미지 읽기를 건너뛰거나, 다른 프로그램이 점유 중이면 null.
     */
    private Content read() {
        try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                String text = (String) clipboard.getData(DataFlavor.stringFlavor);
                return text == null ? null : new Content("t:" + text, text, null);
            }
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null;
            // 이미지가 클립보드에 남아 있는 동안 매번 다시 읽게 되므로, 읽기가 50ms를 넘으면 3번에 1번만 읽는다
            if (--imageSkip > 0) return null;
            long start = System.nanoTime();
            BufferedImage img = Images.toArgb((Image) clipboard.getData(DataFlavor.imageFlavor));
            String key = Images.key(img);
            imageSkip = System.nanoTime() - start > 50_000_000L ? 3 : 1;
            return new Content("i:" + key, null, img);
        } catch (Exception e) {
            // 다른 프로그램이 클립보드를 잠깐 점유 중이면 예외가 날 수 있다. 다음 폴링(1초 뒤)에 다시 시도하면 된다.
            return null;
        }
    }

    /** 이미지를 클립보드에 넣기 위한 포장. 자바는 텍스트용(StringSelection)만 기본 제공한다. */
    private record ImageSelection(Image image) implements Transferable {
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }

        @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }

        @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
            return image;
        }
    }
```
import 추가: `java.awt.Image`, `java.awt.datatransfer.Transferable`, `java.awt.datatransfer.UnsupportedFlavorException`, `java.awt.image.BufferedImage`, `java.util.function.BiConsumer`. `java.util.Objects` import는 더 이상 안 쓰면 삭제.

`TrayApp`의 watcher 필드를 임시로:
```java
    private final ClipboardWatcher watcher = new ClipboardWatcher(this::onLocalCopy, (img, key) -> { });
```

- [ ] **Step 2: 컴파일·기존 테스트**

Run: `./gradlew :tray-client:test`
Expected: PASS

- [ ] **Step 3: 실제 클립보드로 확인** — 스크래치패드에 `WatchCheck.java`:
```java
import com.clipvault.client.clipboard.ClipboardWatcher;
import com.clipvault.client.clipboard.Images;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.*;

/** 실제 윈도우 클립보드로 이미지 감지, 텍스트 우선, writeImage 후 재업로드 없음, 4K 읽기 시간을 확인한다. */
public class WatchCheck {
    public static void main(String[] a) throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        ClipboardWatcher w = new ClipboardWatcher(t -> events.add("text:" + t), (img, key) -> events.add("image:" + img.getWidth() + "x" + img.getHeight()));
        w.start();
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        // 다른 프로그램이 이미지를 복사한 것처럼: 우리 감시기를 거치지 않고 클립보드에 직접 넣는다
        BufferedImage img = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics(); g.setPaint(new GradientPaint(0, 0, Color.RED, 3840, 2160, Color.BLUE)); g.fillRect(0, 0, 3840, 2160); g.dispose();
        cb.setContents(new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
            public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }
            public Object getTransferData(DataFlavor f) { return img; }
        }, null);
        System.out.println("4K image detected: " + events.poll(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime(); Images.key(Images.toArgb((Image) cb.getData(DataFlavor.imageFlavor)));
        System.out.println("4K read+key ms: " + (System.nanoTime() - t0) / 1_000_000);
        System.out.println("no repeat while unchanged: " + (events.poll(3, TimeUnit.SECONDS) == null));
        cb.setContents(new StringSelection("hello"), null);
        System.out.println("text detected: " + events.poll(3, TimeUnit.SECONDS));
        w.writeImage(Images.toArgb(img));
        System.out.println("writeImage not re-reported: " + (events.poll(3, TimeUnit.SECONDS) == null));
        System.exit(0);
    }
}
```
Run (저장소 루트, Git Bash):
```bash
./gradlew :tray-client:installDist -q
S="$TEMP/claude/E--workspace-clipvault/7414695c-4ad7-4de9-b777-40eb37997caf/scratchpad"
java -cp "tray-client/build/install/tray-client/lib/*" "$S/WatchCheck.java"
```
Expected: `4K image detected: image:3840x2160`, `no repeat while unchanged: true`, `text detected: text:hello`, `writeImage not re-reported: true`. 4K 읽기 시간이 출력된다 (50ms 초과면 자동으로 3초 간격이 적용되는 구조이므로 값만 기록).
주의: 사용자의 클립보드 내용을 덮어쓰므로 실행 전후로 사용자에게 알린다.

- [ ] **Step 4: 커밋**
```bash
git add tray-client/src
git commit -m "feat(tray-client): 클립보드 이미지 감지와 이미지 쓰기 추가"
git push
```

---

### Task 8: ApiClient 이미지 요청

**Files:**
- Modify: `tray-client/src/main/java/com/clipvault/client/network/ApiClient.java`

**Interfaces:**
- Produces: `JsonNode postImage(byte[] png)`, `byte[] getImage(String id)`, `byte[] getThumbnail(String id)` — 401이면 토큰 갱신 후 1회 재시도, 2xx 아니면 `ApiException`, 네트워크 오류는 `UncheckedIOException`

- [ ] **Step 1: 구현** — `ApiClient`의 "내부 동작" 부분을 아래로 교체하고, 클립 섹션에 공개 메서드 3개 추가 (import `java.util.function.Function`):

클립 섹션에 추가:
```java
    /** 이미지 업로드 (PNG 바이트). 큰 파일이라 60초까지 기다린다. */
    public JsonNode postImage(byte[] png) {
        return authed(t -> json(exchange("POST", "/api/clips/image",
                HttpRequest.BodyPublishers.ofByteArray(png), "image/png", t, LONG)));
    }

    /** 이미지 클립의 원본 PNG. */
    public byte[] getImage(String id) {
        return authed(t -> exchange("GET", "/api/clips/" + enc(id) + "/image", HttpRequest.BodyPublishers.noBody(), null, t, LONG));
    }

    /** 이미지 클립의 썸네일 PNG (긴 변 최대 240px). */
    public byte[] getThumbnail(String id) {
        return authed(t -> exchange("GET", "/api/clips/" + enc(id) + "/thumbnail", HttpRequest.BodyPublishers.noBody(), null, t, SHORT));
    }
```

내부 동작 교체:
```java
    /** 일반 요청 제한 시간 */
    private static final Duration SHORT = Duration.ofSeconds(15);
    /** 이미지 업로드/다운로드 제한 시간 */
    private static final Duration LONG = Duration.ofSeconds(60);

    /** 토큰이 필요한 JSON 요청. */
    private JsonNode authed(String method, String path, Object body) {
        return authed(t -> send(method, path, body, t));
    }

    /**
     * 토큰이 필요한 요청. 401을 받으면 토큰을 갱신하고 한 번만 다시 시도한다.
     *
     * @param call 토큰을 받아 실제 요청을 보내는 함수
     */
    private <T> T authed(Function<String, T> call) {
        String token = session.accessToken;
        try {
            return call.apply(token);
        } catch (ApiException e) {
            if (e.status != 401) throw e;
            synchronized (this) {
                // 기다리는 동안 다른 스레드가 이미 갱신했을 수 있다. 토큰이 그대로일 때만 갱신한다.
                // 갱신이 실패하면(로그인 필요) 원래의 401 예외를 그대로 던진다.
                if (Objects.equals(token, session.accessToken) && !refresh()) throw e;
            }
            return call.apply(session.accessToken);
        }
    }

    /** JSON 요청을 보내고 JSON 응답을 돌려준다. body가 null이면 본문 없음. */
    private JsonNode send(String method, String path, Object body, String token) {
        try {
            HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            return json(exchange(method, path, pub, body == null ? null : "application/json", token, SHORT));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 실제 HTTP 요청. 응답 본문을 바이트로 돌려준다.
     *
     * @param contentType 본문 형식. null이면 Content-Type 헤더 없음
     * @param token       넣을 access 토큰. null이면 Authorization 헤더 없음
     * @throws ApiException 2xx가 아닌 응답. 서버가 준 에러 메시지({"message": ...})를 담는다
     */
    private byte[] exchange(String method, String path, HttpRequest.BodyPublisher body, String contentType,
                            String token, Duration timeout) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(session.server + path))
                    .timeout(timeout)
                    .method(method, body);
            if (contentType != null) b.header("Content-Type", contentType);
            if (token != null) b.header("Authorization", "Bearer " + token);
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                // 서버의 에러 JSON에서 message만 꺼낸다. JSON이 아니면 본문 전체를 메시지로 쓴다.
                String text = new String(res.body(), StandardCharsets.UTF_8);
                String msg = text;
                try { msg = JSON.readTree(text).path("message").asText(text); } catch (IOException ignored) { }
                throw new ApiException(res.statusCode(), msg);
            }
            return res.body();
        } catch (IOException e) {
            // 네트워크 오류(서버 꺼짐, 연결 끊김 등)
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            // 스레드 중단 요청을 받으면 중단 표시를 복구하고 빠져나간다 (자바 관례)
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 응답 바이트를 JSON으로. 비어 있으면(204 등) JSON null 노드. */
    private static JsonNode json(byte[] body) {
        try {
            return body.length == 0 ? JSON.nullNode() : JSON.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```
(`enc` 메서드는 그대로 둔다. 기존 `send`/`authed`의 호출부 시그니처는 바뀌지 않는다.)

- [ ] **Step 2: 컴파일·테스트**

Run: `./gradlew :tray-client:test`
Expected: PASS (동작 검증은 Task 11의 E2E에서)

- [ ] **Step 3: 커밋**
```bash
git add tray-client/src
git commit -m "feat(tray-client): ApiClient에 이미지 업로드/다운로드 추가"
git push
```

---

### Task 9: TrayApp 이미지 흐름 (업로드, 알림, 붙여넣기, 썸네일)

**Files:**
- Modify: `tray-client/src/main/java/com/clipvault/client/TrayApp.java`
- Modify: `tray-client/src/main/java/com/clipvault/client/ui/ClipListWindow.java` (시그니처만: onPick → `Consumer<JsonNode>`, `Thumbs` 인자 추가. 화면 변경은 Task 10)

**Interfaces:**
- Consumes: `ClipboardWatcher(onText, onImage)`, `writeImage`, `Images.*`, `ApiClient.postImage/getImage/getThumbnail`
- Produces: `ClipListWindow.Thumbs` 함수형 인터페이스 `Image get(String clipId, Runnable onReady)`; `ClipListWindow.show(JsonNode clips, String myDeviceId, Consumer<JsonNode> onPick, Consumer<JsonNode> onDelete, Thumbs thumbs)`

- [ ] **Step 1: ClipListWindow 시그니처** — 클래스에 추가:
```java
    /**
     * 썸네일 공급자. 캐시에 있으면 바로 돌려주고, 없으면 null을 돌려주면서 백그라운드로 받아 온 뒤 onReady를 부른다
     * (onReady는 목록을 다시 그리게 한다).
     */
    @FunctionalInterface
    public interface Thumbs {
        Image get(String clipId, Runnable onReady);
    }
```
`show`를 `show(JsonNode clips, String myDeviceId, Consumer<JsonNode> onPick, Consumer<JsonNode> onDelete, Thumbs thumbs)`로 바꾸고, Javadoc의 onPick 설명을 "사용자가 고른 클립(JSON 전체) - 텍스트/이미지에 따라 클립보드에 넣는 일은 호출한 쪽이 한다"로 수정. pick 람다에서 `if (c != null) onPick.accept(c);`. (thumbs는 Task 10에서 렌더러에 연결)

- [ ] **Step 2: TrayApp 구현**

import 추가: `com.clipvault.client.clipboard.Images`, `java.awt.image.BufferedImage`, `java.util.Map`, `java.util.Set`, `java.util.concurrent.ConcurrentHashMap`.

필드:
```java
    private final ClipboardWatcher watcher = new ClipboardWatcher(this::onLocalCopy, this::onLocalImage);
    /** 받아 온 썸네일 (클립 id → 이미지). 앱이 켜져 있는 동안만 메모리에 둔다. */
    private final Map<String, Image> thumbs = new ConcurrentHashMap<>();
    /** 지금 받는 중인 썸네일 id (같은 썸네일을 동시에 여러 번 요청하지 않도록) */
    private final Set<String> thumbsLoading = ConcurrentHashMap.newKeySet();
```

클래스 Javadoc 부품 목록의 ClipboardWatcher 줄을 `로컬 Ctrl+C 감지 → {@link #onLocalCopy}(텍스트), {@link #onLocalImage}(이미지)`로 수정.

`onLocalCopy` 아래에 추가:
```java
    /**
     * 로컬에서 새 이미지가 복사됨 (ClipboardWatcher가 호출, key = 이미지 키).
     * PNG로 바꿔 10MB를 넘으면 올리지 않고 한 번 알린다 (같은 이미지는 EchoGuard가 다시 거르므로 알림도 한 번).
     */
    private void onLocalImage(BufferedImage img, String key) {
        if (paused || !loggedIn || !guard.shouldUpload("img:" + key)) return;
        async(() -> {
            byte[] png = Images.toPng(img);
            if (png.length > Images.MAX_BYTES) {
                SwingUtilities.invokeLater(() -> icon.displayMessage("ClipVault",
                        "이미지가 10MB를 넘어 동기화하지 않았습니다.", TrayIcon.MessageType.WARNING));
                return;
            }
            api.postImage(png);
        });
    }
```

`showClips`의 `ClipListWindow.show(...)` 호출을:
```java
                ClipListWindow.show(clips, session.deviceId, this::pick,
                        clip -> async(() -> api.deleteClip(clip.path("id").asText())), // 삭제는 서버에 요청만 보낸다
                        this::thumbnail);
```

`showClips` 아래에 추가:
```java
    /** 목록에서 고른 클립을 로컬 클립보드에 넣는다. 텍스트는 바로, 이미지는 원본을 받아 온 뒤. */
    private void pick(JsonNode clip) {
        if ("IMAGE".equals(clip.path("type").asText())) {
            pickImage(clip.path("id").asText());
            return;
        }
        String text = clip.path("content").asText();
        // 순서가 중요: 먼저 EchoGuard에 기록한 뒤 클립보드에 쓴다.
        // 그래야 감시기가 변화를 감지했을 때 "서버에서 받은 것"이라 업로드하지 않는다.
        guard.markApplied(text);
        watcher.write(text);
    }

    /** 이미지 원본을 받아 클립보드에 넣는다. 실패하면 알림 (401은 async가 로그인 화면으로 보낸다). */
    private void pickImage(String id) {
        async(() -> {
            try {
                BufferedImage img = Images.fromPng(api.getImage(id));
                SwingUtilities.invokeLater(() -> guard.markApplied("img:" + watcher.writeImage(img)));
            } catch (RuntimeException e) {
                if (e instanceof ApiClient.ApiException a && a.status == 401) throw a;
                System.err.println("Image download failed: " + e);
                SwingUtilities.invokeLater(() -> icon.displayMessage("ClipVault",
                        "이미지를 가져오지 못했습니다.", TrayIcon.MessageType.ERROR));
            }
        });
    }

    /** 목록 창의 썸네일 공급자 ({@link ClipListWindow.Thumbs}). 없으면 백그라운드로 받고 다 받으면 onReady. */
    private Image thumbnail(String id, Runnable onReady) {
        Image t = thumbs.get(id);
        if (t == null && thumbsLoading.add(id)) {
            async(() -> {
                try {
                    thumbs.put(id, Images.fromPng(api.getThumbnail(id)));
                    SwingUtilities.invokeLater(onReady);
                } finally {
                    thumbsLoading.remove(id);
                }
            });
        }
        return t;
    }
```

`onPush`의 미리보기 계산을:
```java
        String preview;
        if ("IMAGE".equals(clip.path("type").asText())) {
            preview = "새 이미지 · " + clip.path("width").asInt() + "×" + clip.path("height").asInt();
        } else {
            preview = clip.path("content").asText().strip();
            if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
        }
```

- [ ] **Step 3: 컴파일·테스트**

Run: `./gradlew :tray-client:test`
Expected: PASS

- [ ] **Step 4: 커밋**
```bash
git add tray-client/src
git commit -m "feat(tray-client): 이미지 업로드, 이미지 알림, 목록에서 이미지 붙여넣기 연결"
git push
```

---

### Task 10: 목록 이미지 카드 (썸네일) — UI 검토 필수

**Files:**
- Modify: `tray-client/src/main/java/com/clipvault/client/ui/ClipListWindow.java`
- Check: 스크래치패드 `ListShot.java` (커밋하지 않음)

**Interfaces:**
- Consumes: `Thumbs`

- [ ] **Step 1: 구현**

`show`에서 렌더러 생성을 `list.setCellRenderer(new ClipCell(myDeviceId, hover, thumbs, list::repaint));`로.

클래스 Javadoc과 `ClipCell` Javadoc 그림에 이미지 카드 추가:
```
     * ┌──────────────────────────────────────┐
     * │ ┌────────────┐                        │
     * │ │  썸네일      │                        │  ← 이미지 클립: 썸네일 (받기 전엔 회색 자리)
     * │ └────────────┘                        │
     * │ 이미지 · 1920×1080  ·  3분 전 · [다른 기기] │
     * └──────────────────────────────────────┘
```

`emptyState`의 안내 문구를 `"다른 PC에서 텍스트나 이미지를 복사(Ctrl+C)해 보세요"`로.

`TrashIcon` 아래에 추가 (import `java.awt.geom.RoundRectangle2D`):
```java
    /**
     * 썸네일 아이콘. 원본 비율대로 최대 220×90 안에 맞춰 그리고, 썸네일이 아직 없으면 같은 크기의 회색 자리를 그린다.
     * 받기 전후 크기가 같아서 목록이 덜컥거리지 않는다.
     */
    private static final class ThumbIcon implements Icon {
        static final int MAX_W = 220, MAX_H = 90;
        Image image;
        int w = MAX_W, h = MAX_H;

        /** 그릴 이미지(없으면 null)와 원본 크기로 표시 크기를 정한다. */
        void set(Image image, int srcW, int srcH) {
            this.image = image;
            double s = Math.min(1.0, Math.min((double) MAX_W / Math.max(1, srcW), (double) MAX_H / Math.max(1, srcH)));
            w = Math.max(1, (int) Math.round(srcW * s));
            h = Math.max(1, (int) Math.round(srcH * s));
        }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.clip(new RoundRectangle2D.Float(x, y, w, h, 8, 8)); // 모서리를 둥글게
            if (image == null) {
                g2.setColor(Theme.border());
                g2.fillRect(x, y, w, h);
            } else {
                g2.drawImage(image, x, y, w, h, null);
            }
            g2.dispose();
        }

        @Override public int getIconWidth() { return w; }

        @Override public int getIconHeight() { return h; }
    }
```

`ClipCell`에 필드·생성자 인자 추가:
```java
        private final Thumbs thumbs;
        private final Runnable repaint;
        private final ThumbIcon thumbIcon = new ThumbIcon();

        ClipCell(String myDeviceId, int[] hover, Thumbs thumbs, Runnable repaint) {
            this.myDeviceId = myDeviceId;
            this.hover = hover;
            this.thumbs = thumbs;
            this.repaint = repaint;
            // (기존 생성자 본문 그대로)
        }
```

`getListCellRendererComponent`의 첫 두 줄(내용·미리보기)과 `time.setText(...)`를 교체:
```java
            String ago = Theme.ago(Theme.parse(clip.path("createdAt").asText()));
            if ("IMAGE".equals(clip.path("type").asText())) {
                // 이미지: 썸네일(없으면 회색 자리, 받아지면 repaint로 다시 그려짐) + "이미지 · W×H"
                int w = clip.path("width").asInt(), h = clip.path("height").asInt();
                thumbIcon.set(thumbs.get(clip.path("id").asText(), repaint), w, h);
                text.setText(null);
                text.setIcon(thumbIcon);
                time.setText("이미지 · " + w + "×" + h + "  ·  " + ago + "  ·  ");
            } else {
                // 줄바꿈/연속 공백을 한 칸으로 합치고, 길면 잘라서 "…" (실제로 복사되는 값은 원문 그대로)
                String s = clip.path("content").asText().replaceAll("\\s+", " ").strip();
                text.setIcon(null);
                text.setText(s.length() > 48 ? s.substring(0, 48) + "…" : s);
                time.setText(ago + "  ·  ");
            }
```
(`text.setForeground(...)` 이하 나머지는 그대로)

- [ ] **Step 2: 컴파일**

Run: `./gradlew :tray-client:test :tray-client:installDist`
Expected: PASS

- [ ] **Step 3: 오프스크린 스크린샷** — 스크래치패드 `ListShot.java`:
```java
import com.clipvault.client.network.ApiClient;
import com.clipvault.client.ui.ClipListWindow;
import com.clipvault.client.ui.Theme;
import com.fasterxml.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;

/** 이미지 카드가 들어간 최근 클립 목록을 화면 캡처 없이(printAll) PNG로 저장한다. args[0] = 출력 파일 */
public class ListShot {
    public static void main(String[] a) throws Exception {
        Theme.setup();
        String now = Instant.now().minusSeconds(120).toString(), older = Instant.now().minusSeconds(900).toString();
        JsonNode clips = ApiClient.JSON.readTree("""
            [{"id":"1","type":"IMAGE","content":"[이미지 1920×1080]","sourceDeviceId":"other","createdAt":"%s","width":1920,"height":1080},
             {"id":"2","type":"IMAGE","content":"[이미지 800×1200]","sourceDeviceId":"me","createdAt":"%s","width":800,"height":1200},
             {"id":"3","type":"TEXT","content":"회의 링크 https://meet.example.com/abc-defg-hij","sourceDeviceId":"other","createdAt":"%s"}]
            """.formatted(now, now, older));
        // 1번은 썸네일이 받아진 상태, 2번은 아직 받는 중(회색 자리)
        BufferedImage thumb = new BufferedImage(240, 135, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumb.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x6366F1), 240, 135, new Color(0x22D3EE)));
        g.fillRect(0, 0, 240, 135);
        g.dispose();
        SwingUtilities.invokeAndWait(() -> ClipListWindow.show(clips, "me", c -> { }, c -> { },
                (id, ready) -> "1".equals(id) ? thumb : null));
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog d && d.isShowing()) {
                        JRootPane root = d.getRootPane();
                        BufferedImage img = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
                        root.printAll(img.createGraphics());
                        ImageIO.write(img, "png", new File(a[0]));
                        d.dispose();
                    }
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.exit(0);
    }
}
```
Run:
```bash
S="$TEMP/claude/E--workspace-clipvault/7414695c-4ad7-4de9-b777-40eb37997caf/scratchpad"
java -cp "tray-client/build/install/tray-client/lib/*" "$S/ListShot.java" "$S/clips-image.png"
```
Expected: `clips-image.png` 생성. Read 도구로 확인 후 SendUserFile로 사용자에게 보낸다.

- [ ] **Step 4: 사용자 승인 대기** — 스크린샷을 보여 주고 승인(또는 수정 요청)을 받기 전에는 커밋하지 않는다. 수정 요청이 오면 반영 후 Step 3 반복.

- [ ] **Step 5: 커밋** (승인 후)
```bash
git add tray-client/src
git commit -m "feat(tray-client): 최근 클립 목록에 이미지 썸네일 카드 추가"
git push
```

---

### Task 11: 2기기 E2E (로컬 Docker)

**Files:**
- Modify: 스크래치패드 `E2E.java` (커밋하지 않음)

**Interfaces:**
- Consumes: `ApiClient.postImage/getImage/getThumbnail/listClips`, `Images.toPng/fromPng`

- [ ] **Step 1: 이미지 시나리오 추가** — `E2E.java`에서 "history newest-first" 확인 바로 뒤에 추가 (import `com.clipvault.client.clipboard.Images`, `java.awt.*`, `java.awt.image.BufferedImage`, `java.util.Arrays`):
```java
        // --- 이미지 ---
        BufferedImage pic = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = pic.createGraphics();
        pg.setPaint(new GradientPaint(0, 0, Color.ORANGE, 640, 360, Color.MAGENTA));
        pg.fillRect(0, 0, 640, 360);
        pg.dispose();
        byte[] png = Images.toPng(pic);
        inboxA.clear();
        long it0 = System.nanoTime();
        JsonNode up = b.postImage(png);
        JsonNode gotImg = inboxA.poll(3, TimeUnit.SECONDS);
        long ims = (System.nanoTime() - it0) / 1_000_000;
        check("B image upload pushed to A (< 3s)", gotImg != null && "IMAGE".equals(gotImg.path("type").asText()) && ims < 3000, ims + "ms");
        check("pushed image has size 640x360", gotImg != null && gotImg.path("width").asInt() == 640 && gotImg.path("height").asInt() == 360, "");
        String imgId = up.path("id").asText();
        check("A downloads identical original", Arrays.equals(png, a.getImage(imgId)), "");
        BufferedImage th = Images.fromPng(a.getThumbnail(imgId));
        check("thumbnail is 240x135", th.getWidth() == 240 && th.getHeight() == 135, th.getWidth() + "x" + th.getHeight());
        check("image is newest in history", "IMAGE".equals(a.listClips(20).get(0).path("type").asText()), "");
        check("same image re-upload is duplicate", b.postImage(png).path("id").asText().equals(imgId), "");
```

- [ ] **Step 2: 로컬 서버로 실행**

Run (저장소 루트):
```bash
docker compose up -d --build
./gradlew :tray-client:installDist -q
S="$TEMP/claude/E--workspace-clipvault/7414695c-4ad7-4de9-b777-40eb37997caf/scratchpad"
javac -cp "tray-client/build/install/tray-client/lib/*" -d "$S/e2eclasses" "$S/E2E.java"
java -cp "$S/e2eclasses;tray-client/build/install/tray-client/lib/*" -Dclipvault.server=http://localhost:8080 E2E
```
Expected: 모든 check가 PASS (기존 텍스트 시나리오 포함). 실패 시 `docker compose logs backend`로 원인 확인.

- [ ] **Step 3: 정리**

Run: `docker compose down`
(커밋할 파일 없음. 결과는 PR 본문에 기록)

---

### Task 12: 문서 갱신과 PR

**Files:**
- Modify: `docs/PRD.md`, `docs/TRD.md`, `docs/TASKS.md`, `README.md`

- [ ] **Step 1: 문서 수정**
  - `docs/PRD.md`: 범위(기능) 목록에 "이미지 클립보드 동기화 (스크린샷 등, 10MB 이하, 목록 썸네일)" 추가, 범위 밖에서 이미지 항목이 있으면 제거하고 "이미지 파일 복사(탐색기), 기타 파일"을 범위 밖으로 명시. 수정일 갱신.
  - `docs/TRD.md`: 기술 스택 표에 "이미지 저장소 | Oracle Object Storage (S3 호환, AWS SDK v2) | 무료 20GB, 서버 경유·AES-GCM 암호화" 추가. 폴더 구조에 `storage/` 추가. 데이터 모델에 clips 추가 컬럼(type, image_key, width, height, image_size). 배포 절에 버킷·수명 주기 규칙·`S3_*` 환경변수. 수정일 갱신.
  - `docs/TASKS.md`: 백로그 "이미지 클립보드 지원" 체크, 계획 외 추가 구현에 "이미지 클립보드 동기화 (v1.2.0 예정)" 추가.
  - `README.md`: 주요 기능에 "이미지 동기화: 스크린샷 등 복사한 이미지도 동기화, 목록에서 썸네일로 확인 (10MB 이하)" 추가, 아키텍처 Mermaid에 Object Storage 노드(backend → Object Storage) 추가, 배포 절에 `S3_*` 설정 언급.

- [ ] **Step 2: 전체 테스트**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 3: 커밋·PR**
```bash
git add docs README.md
git commit -m "docs: 이미지 클립보드 동기화 반영 (PRD/TRD/TASKS/README)"
git push
gh pr create --base main --head feat/image-clipboard --title "feat: 이미지 클립보드 동기화" --body-file <스크래치패드의 pr.md>
```
PR 본문(한국어, Claude 문구 없음): 개요, 동작(업로드/목록/붙여넣기), 서버 변경(API·저장소·DB 컬럼), 트레이 앱 변경, 테스트 결과(단위·E2E 수치), **머지 전 필요한 작업(버킷·`.env`)**, 구버전 앱 동작.

- [ ] **Step 4: CI 확인** — `mcp__ccd_pr__get_status`로 상태 확인 (직접 폴링하지 않음).

---

### Task 13: 운영 설정, 배포 검증, 머지

**Files:** 없음 (사용자 작업 + 검증)

- [ ] **Step 1: 사용자에게 버킷 설정 안내** (사용자가 직접 수행, 키 값은 받지 않는다)
  1. Oracle 콘솔 → Storage → Buckets → Create Bucket: 이름 `clipvault-images`, Standard, 비공개(기본값)
  2. 버킷 → Lifecycle Policy Rules → Create Rule: Delete, 8일
  3. 오른쪽 위 프로필 → My profile → Customer secret keys → Generate: Access Key와 Secret을 복사 (Secret은 한 번만 보임)
  4. Tenancy details(또는 버킷 상세)에서 Object Storage Namespace 확인, 리전 식별자 확인(예: `ap-chuncheon-1`)
  5. VM에서 `nano ~/ClipVault/deploy/.env`에 `S3_ENDPOINT=https://<namespace>.compat.objectstorage.<region>.oraclecloud.com`, `S3_REGION`, `S3_BUCKET=clipvault-images`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` 추가

- [ ] **Step 2: 사용자 완료 확인 후 머지** — 사용자가 "머지해줘"라고 하면 `gh pr merge <번호> --merge --delete-branch`, 로컬 main 갱신. deploy.yml이 백엔드를 배포한다.

- [ ] **Step 3: 배포 서버 검증**
  - 배포 후 `docker compose logs backend`에 `Image storage: S3 bucket clipvault-images` 로그가 있는지 사용자에게 확인 요청 (또는 사용자 동의 하에 확인 명령 안내)
  - E2E를 배포 서버로 실행:
```bash
S="$TEMP/claude/E--workspace-clipvault/7414695c-4ad7-4de9-b777-40eb37997caf/scratchpad"
java -cp "$S/e2eclasses;tray-client/build/install/tray-client/lib/*" -Dclipvault.server=https://161-33-167-228.sslip.io E2E
```
  Expected: 모든 check PASS → 실제 Oracle 버킷 저장·조회가 동작함 (S3ImageStore 수동 검증 완료)

- [ ] **Step 4: 결과 보고** — 사용자에게 결과를 알리고, v1.2.0 릴리스는 요청 시에만 태그 푸시.
