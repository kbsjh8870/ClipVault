package com.clipvault.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClipListWindowTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode text(String content) {
        return JSON.createObjectNode().put("type", "TEXT").put("content", content);
    }

    @Test
    void emptyQueryMatchesEverything() {
        JsonNode image = JSON.createObjectNode().put("type", "IMAGE");
        assertTrue(ClipListWindow.matches(text("abc"), ""));
        assertTrue(ClipListWindow.matches(image, "  "));
    }

    @Test
    void matchesSubstringIgnoringCase() {
        assertTrue(ClipListWindow.matches(text("회의 링크 https://Meet.example.com"), "meet"));
        assertTrue(ClipListWindow.matches(text("회의 링크"), " 링크 "), "앞뒤 공백은 무시");
        assertFalse(ClipListWindow.matches(text("회의 링크"), "주소"));
    }

    private static JsonNode clip(String id, String createdAt, boolean pinned) {
        return JSON.createObjectNode().put("id", id).put("createdAt", createdAt).put("pinned", pinned);
    }

    private static java.util.List<String> ids(java.util.List<JsonNode> all) {
        return all.stream().map(c -> c.path("id").asText()).toList();
    }

    /** 고정 먼저(그 안에서 최신순), 그다음 최신순. 고정 목록과 최근 목록에 같은 클립이 있으면 한 번만 */
    @Test
    void mergePutsPinnedFirstAndDedupes() {
        java.util.List<JsonNode> all = new java.util.ArrayList<>();
        ClipListWindow.merge(all, java.util.List.of(
                clip("p-old", "2026-09-01T00:00:00Z", true), clip("p-new", "2026-09-20T00:00:00Z", true)));
        ClipListWindow.merge(all, java.util.List.of(
                clip("r2", "2026-09-27T10:00:00.5Z", false), clip("p-new", "2026-09-20T00:00:00Z", true),
                clip("r1", "2026-09-27T09:00:00Z", false)));
        assertEquals(java.util.List.of("p-new", "p-old", "r2", "r1"), ids(all));

        // 더 보기로 받은 페이지는 뒤에 이어 붙는다
        ClipListWindow.merge(all, java.util.List.of(clip("r0", "2026-09-26T00:00:00Z", false)));
        assertEquals(java.util.List.of("p-new", "p-old", "r2", "r1", "r0"), ids(all));

        // 고정을 풀면 날짜 자리로 내려간다
        ((com.fasterxml.jackson.databind.node.ObjectNode) all.get(0)).put("pinned", false);
        ClipListWindow.merge(all, java.util.List.of(all.get(0)));
        assertEquals(java.util.List.of("p-old", "r2", "r1", "r0", "p-new"), ids(all));
    }

    @Test
    void imagesDoNotMatchText() {
        assertFalse(ClipListWindow.matches(JSON.createObjectNode().put("type", "IMAGE"), "a"));
    }
}
