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

    @Test
    void imagesDoNotMatchText() {
        assertFalse(ClipListWindow.matches(JSON.createObjectNode().put("type", "IMAGE"), "a"));
    }
}
