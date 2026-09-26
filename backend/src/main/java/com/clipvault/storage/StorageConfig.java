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
