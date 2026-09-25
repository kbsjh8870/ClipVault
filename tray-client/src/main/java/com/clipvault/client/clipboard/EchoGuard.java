package com.clipvault.client.clipboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 로컬 클립보드가 바뀌었을 때 "이걸 서버에 올려야 하나?"를 판단하는 문지기.
 *
 * <p><b>막아야 하는 상황 1 - 무한 핑퐁(ping-pong)</b></p>
 * <ol>
 *   <li>집PC에서 목록의 클립을 클릭 → 로컬 클립보드에 그 텍스트를 넣는다.</li>
 *   <li>클립보드 감시기(ClipboardWatcher)가 "클립보드가 바뀌었다!"고 감지한다.</li>
 *   <li>막지 않으면 이 텍스트를 다시 서버에 업로드 → 다른 PC에 또 알림 → ... 끝없이 반복될 수 있다.</li>
 * </ol>
 * <p>그래서 서버에서 받아 로컬에 넣은 텍스트는 {@link #markApplied}로 표시해 두고, 일정 시간(window) 동안은 업로드하지 않는다.</p>
 *
 * <p><b>막아야 하는 상황 2 - 연속 중복</b>: 방금 올린 것과 똑같은 텍스트는 다시 올리지 않는다.</p>
 *
 * <p>테스트하기 쉽도록 현재 시각을 {@link Clock}으로 주입받는다. (테스트에서는 시간을 마음대로 앞으로 돌릴 수 있는 가짜 시계를 넣는다.)</p>
 */
public class EchoGuard {
    private final Clock clock;
    /** markApplied 후 업로드를 막아 두는 시간. 트레이 앱은 5초로 쓴다. */
    private final Duration window;
    /** 서버에서 받아 로컬에 반영한 텍스트 → 반영한 시각. */
    private final Map<String, Instant> applied = new HashMap<>();
    /** 마지막으로 업로드를 허락한 텍스트. */
    private String lastUploaded;

    public EchoGuard(Clock clock, Duration window) {
        this.clock = clock;
        this.window = window;
    }

    /**
     * 서버에서 받은 클립을 로컬 클립보드에 넣을 때 호출한다. (클립보드에 넣기 "전에" 호출해야 감시기보다 먼저 기록된다)
     * 이후 window 시간 동안 이 텍스트는 업로드 대상에서 빠진다.
     */
    public synchronized void markApplied(String text) {
        if (text != null) applied.put(text, clock.instant());
    }

    /**
     * 이 텍스트를 서버에 업로드해도 되는지 판단한다.
     *
     * <p>false를 돌려주는 경우</p>
     * <ul>
     *   <li>null이거나 공백뿐인 텍스트</li>
     *   <li>window 시간 안에 markApplied된 텍스트 (서버에서 받아 온 것 → 되돌려 보내지 않음)</li>
     *   <li>직전에 업로드를 허락한 텍스트와 똑같음</li>
     * </ul>
     * <p>true를 돌려줄 때는 그 텍스트를 "직전 업로드"로 기록해 둔다.</p>
     */
    public synchronized boolean shouldUpload(String text) {
        if (text == null || text.isBlank()) return false;
        Instant now = clock.instant();
        // window가 지난 기록은 지운다 (지금 시각이 "반영 시각 + window"보다 뒤면 만료)
        applied.values().removeIf(at -> now.isAfter(at.plus(window)));
        if (applied.containsKey(text)) return false;
        if (text.equals(lastUploaded)) return false;
        lastUploaded = text;
        return true;
    }
}
