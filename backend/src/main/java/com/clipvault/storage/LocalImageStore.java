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
