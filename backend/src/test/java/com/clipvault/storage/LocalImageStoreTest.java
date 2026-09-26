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
