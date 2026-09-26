package com.clipvault.client.hotkey;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.*;

class HotKeysTest {
    private static KeyStroke ks(int key, int mods) {
        return KeyStroke.getKeyStroke(key, mods);
    }

    @Test
    void defaultsAreValid() {
        for (HotKeys.Action a : HotKeys.Action.values()) {
            assertTrue(HotKeys.valid(KeyStroke.getKeyStroke(a.defaultKey)), a.name());
        }
    }

    @Test
    void needsModifierAndSupportedKey() {
        int cas = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
        assertTrue(HotKeys.valid(ks(KeyEvent.VK_C, cas)));
        assertTrue(HotKeys.valid(ks(KeyEvent.VK_7, InputEvent.CTRL_DOWN_MASK)));
        assertTrue(HotKeys.valid(ks(KeyEvent.VK_F9, InputEvent.ALT_DOWN_MASK)));
        assertFalse(HotKeys.valid(ks(KeyEvent.VK_C, 0)), "수식 키 없이 글자만 누르면 타이핑을 막는다");
        assertFalse(HotKeys.valid(ks(KeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK)), "윈도우 키 코드가 다른 키");
        assertFalse(HotKeys.valid(ks(KeyEvent.VK_SHIFT, InputEvent.SHIFT_DOWN_MASK)), "수식 키 단독");
        assertFalse(HotKeys.valid(null));
    }

    @Test
    void winModifiersMapToRegisterHotKeyFlags() {
        // MOD_ALT=1, MOD_CONTROL=2, MOD_SHIFT=4
        assertEquals(1 | 2 | 4, HotKeys.winModifiers(ks(KeyEvent.VK_C,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(2, HotKeys.winModifiers(ks(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)));
    }

    @Test
    void textIsHumanReadable() {
        assertEquals("Ctrl+Alt+Shift+C", HotKeys.text(KeyStroke.getKeyStroke("ctrl alt shift C")));
        assertEquals("없음", HotKeys.text(null));
    }
}
