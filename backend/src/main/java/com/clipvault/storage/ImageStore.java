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
