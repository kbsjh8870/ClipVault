package com.clipvault.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link HashUtil} 단위 테스트. */
class HashUtilTest {
    /** 널리 알려진 SHA-256 정답값(표준 테스트 벡터)과 비교해서 해시 계산이 정확한지 확인한다. */
    @Test
    void sha256KnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HashUtil.sha256("abc"));
        // 빈 문자열의 SHA-256
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", HashUtil.sha256(""));
    }
}
