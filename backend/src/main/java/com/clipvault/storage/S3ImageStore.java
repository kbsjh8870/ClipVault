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
