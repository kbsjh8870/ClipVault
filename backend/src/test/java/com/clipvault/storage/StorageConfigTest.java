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
