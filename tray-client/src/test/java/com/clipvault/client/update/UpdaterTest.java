package com.clipvault.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class UpdaterTest {

    @Test
    void comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("1.0.2", "1.0.1"));
        assertTrue(Updater.isNewer("1.10.0", "1.9.9")); // 문자열 비교였다면 틀렸을 경우
        assertTrue(Updater.isNewer("2.0", "1.9.9"));
        assertFalse(Updater.isNewer("1.0.1", "1.0.1"));
        assertFalse(Updater.isNewer("1.0.0", "1.0.1"));
        assertFalse(Updater.isNewer("1.0", "1.0.0"));
        assertFalse(Updater.isNewer("1.1.0-beta", "1.0.0")); // 숫자가 아니면 업데이트하지 않는다
    }

    @Test
    void sha256OfFile(@TempDir Path dir) throws IOException {
        Path f = Files.writeString(dir.resolve("a.txt"), "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256(f));
    }

    @Test
    void unzipsFolders(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, "ClipVault/", "ClipVault/ClipVault.exe", "ClipVault/app/tray-client.jar");
        Path out = dir.resolve("out");
        Updater.unzip(zip, out);
        assertEquals("ClipVault/ClipVault.exe", Files.readString(out.resolve("ClipVault/ClipVault.exe")));
        assertTrue(Files.exists(out.resolve("ClipVault/app/tray-client.jar")));
    }

    @Test
    void rejectsZipSlip(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, "../evil.txt");
        assertThrows(IOException.class, () -> Updater.unzip(zip, dir.resolve("out")));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }

    /** 이름들로 zip을 만든다. "/"로 끝나면 폴더, 아니면 내용이 자기 이름인 파일. */
    private static Path zip(Path dir, String... names) throws IOException {
        Path zip = dir.resolve("t.zip");
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os)) {
            for (String n : names) {
                z.putNextEntry(new ZipEntry(n));
                if (!n.endsWith("/")) z.write(n.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return zip;
    }
}
