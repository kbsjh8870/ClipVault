package com.clipvault.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.formdev.flatlaf.icons.FlatSearchIcon;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 트레이 아이콘 근처에 뜨는 "최근 클립" 팝업 목록.
 *
 * <p>각 항목은 두 줄짜리 카드다: 첫 줄은 내용 미리보기, 둘째 줄은 "3분 전 · 다른 기기" 같은 정보.
 * 항목을 클릭하거나 방향키로 고른 뒤 Enter를 누르면 그 텍스트가 로컬 클립보드에 복사된다(바로 Ctrl+V 가능).
 * 항목에 마우스를 올리면 오른쪽에 휴지통 아이콘이 나타나고, 그걸 누르거나 Delete 키를 누르면 서버에서 삭제된다.
 * 휴지통 왼쪽의 핀을 누르면 고정(즐겨찾기)된다: 고정한 클립은 만료되지 않고 목록 맨 위에 모인다 (최대 10개).
 * 목록은 {@link #PAGE}개씩 받아 오고, 더 있으면 맨 끝의 "더 보기" 줄로 다음 페이지를 이어 붙인다.
 * 팝업이 떠 있는 동안 다른 PC에서 복사한 클립은 {@link #push}로 바로 목록에 들어온다.
 * Esc를 누르거나 다른 곳을 클릭하면(포커스를 잃으면) 닫힌다.</p>
 *
 * <p>위쪽 검색칸에 글자를 치면 그 글자가 들어 있는 텍스트 클립만 남는다. 창이 뜨면 검색칸에 포커스가 있어서
 * 바로 타이핑할 수 있고, 방향키/Enter/Delete/Esc는 검색칸이 목록 대신 처리한다.</p>
 *
 * <p>화면 스레드(EDT)에서 호출해야 한다.</p>
 */
public class ClipListWindow {
    /** 현재 떠 있는 팝업. 트레이를 여러 번 클릭해도 팝업이 하나만 뜨도록 기억해 둔다. */
    private static JDialog open;

    private static final int WIDTH = 380;

    /**
     * 썸네일 공급자. 캐시에 있으면 바로 돌려주고, 없으면 null을 돌려주면서 백그라운드로 받아 온 뒤 onReady를 부른다
     * (onReady는 목록을 다시 그리게 한다).
     */
    @FunctionalInterface
    public interface Thumbs {
        /** clip = 클립 JSON 전체 (e2e 여부를 보고 복호화해야 해서 id만으로는 부족하다). */
        Image get(JsonNode clip, Runnable onReady);
    }

    /**
     * "더 보기"로 다음 페이지를 받아 오는 함수. 백그라운드에서 받아 온 뒤 화면 스레드에서 onPage를 부른다.
     * 실패하면 onPage에 null을 넘긴다 (더 보기 줄이 다시 눌릴 수 있는 상태로 돌아간다).
     */
    @FunctionalInterface
    public interface Pager {
        void load(String before, Consumer<JsonNode> onPage);
    }

    /** 한 번에 받아 오는 클립 수. 받은 개수가 이만큼이면 더 있을 수 있다고 보고 "더 보기" 줄을 둔다. */
    public static final int PAGE = 50;
    /** 사용자당 고정할 수 있는 클립 수 (서버 제한과 같다). */
    static final int MAX_PINNED = 10;
    /** 목록 맨 끝의 "더 보기" 줄을 나타내는 표시용 항목 (진짜 클립이 아니다). */
    private static final JsonNode MORE = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    /** 검색 결과가 하나도 없을 때 목록 맨 위에 넣는 "검색 결과 없음" 안내 줄 (진짜 클립이 아니다). */
    private static final JsonNode EMPTY = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    /**
     * 떠 있는 팝업에 실시간으로 새 클립을 넣는 함수. 팝업이 없으면 null.
     * 팝업이 떠 있는 동안 다른 PC에서 복사한 클립이 창을 다시 열지 않아도 목록에 나타나게 한다 ({@link #push}).
     */
    private static Consumer<JsonNode> pushTo;

    /**
     * 팝업을 띄운다.
     *
     * @param pinned     고정한 클립 목록(JSON 배열). 목록 맨 위에 모인다
     * @param recent     최근 클립 첫 페이지(JSON 배열, 최신순, 최대 {@link #PAGE}개)
     * @param myDeviceId 이 PC의 기기 ID ("이 PC"/"다른 기기" 표시용)
     * @param onPick     사용자가 고른 클립(JSON 전체) - 텍스트/이미지에 따라 클립보드에 넣는 일은 호출한 쪽이 한다
     * @param onDelete   사용자가 항목을 삭제했을 때 호출 (삭제한 클립 전달) - 서버 삭제 요청은 호출한 쪽이 한다.
     *                   목록에서는 즉시 빠진다(서버 응답을 기다리지 않음)
     * @param onPin      사용자가 핀을 눌렀을 때 호출 (클립, 고정 여부) - 서버 요청은 호출한 쪽이 한다. 목록은 즉시 바뀐다
     * @param pager      "더 보기"를 누르면 다음 페이지를 받아 오는 함수
     * @param thumbs     이미지 클립의 썸네일을 가져오는 함수
     */
    public static void show(JsonNode pinned, JsonNode recent, String myDeviceId, Consumer<JsonNode> onPick,
                            Consumer<JsonNode> onDelete, BiConsumer<JsonNode, Boolean> onPin, Pager pager, Thumbs thumbs) {
        if (open != null) open.dispose(); // 이미 떠 있던 팝업은 닫고 새로 띄운다
        // all = 받아 온 전체 클립(고정 먼저, 그다음 최신순), model = 검색어에 맞는 것만 (목록에 보이는 것) + 끝에 "더 보기"
        List<JsonNode> all = new ArrayList<>();
        merge(all, pinned);
        merge(all, recent);
        // 더 보기 상태: cursor = 다음 페이지 기준 시각(마지막으로 받은 페이지의 마지막 항목), more = 더 있을 수 있음, loading = 받는 중
        String[] cursor = {recent.isEmpty() ? null : recent.get(recent.size() - 1).path("createdAt").asText()};
        boolean[] more = {recent.size() >= PAGE};
        boolean[] loading = {false};
        DefaultListModel<JsonNode> model = new DefaultListModel<>();
        all.forEach(model::addElement);
        if (more[0]) model.addElement(MORE);

        JList<JsonNode> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        // 키보드 입력은 전부 검색칸이 받는다 (방향키/Enter/Delete/Esc는 검색칸이 목록 대신 처리). 목록은 마우스 전용.
        list.setFocusable(false);
        // hover[0] = 마우스가 올라가 있는 항목 번호 (-1 = 없음), hover[1] = 그 항목의 어느 버튼 위인지 (0 없음, TRASH, PIN)
        int[] hover = {-1, 0};
        list.setCellRenderer(new ClipCell(myDeviceId, hover, loading, thumbs, list::repaint));
        // 칸 너비를 고정해야 긴 텍스트가 가로 스크롤을 만들지 않고 "…"으로 잘린다
        list.setFixedCellWidth(WIDTH - 24);

        JDialog d = new JDialog((Frame) null, "최근 클립");
        open = d;
        d.setUndecorated(true); // 제목 표시줄 없는 팝업 모양
        d.setAlwaysOnTop(true); // 다른 창 위에 표시
        JLabel count = Theme.pill(all.size() + "개", Theme.selected(), Theme.ACCENT);
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "검색");
        search.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
        search.putClientProperty("JTextField.showClearButton", true);
        JLabel hint = new JLabel(HINT);
        // 개수 배지: 검색 중이면 "보이는 개수 / 전체", 아니면 "전체개" ("더 보기"/"검색 결과 없음" 줄은 세지 않는다)
        Runnable updateCount = () -> {
            int shown = 0;
            for (int k = 0; k < model.size(); k++) if (isClip(model.get(k))) shown++;
            count.setText(search.getText().isBlank() ? all.size() + "개" : shown + " / " + all.size() + "개");
        };
        // 목록을 all과 검색어로 다시 채운다. prefer가 목록에 있으면 그걸, 없으면 첫 번째로 고를 수 있는 항목을 선택한다.
        Consumer<JsonNode> rebuild = prefer -> {
            model.clear();
            for (JsonNode c : all) if (matches(c, search.getText())) model.addElement(c);
            // 검색했는데 맞는 게 없으면 안내 줄 (클립이 아예 없는 경우는 "비어 있음" 화면이 따로 있다)
            if (model.isEmpty() && !search.getText().isBlank()) model.addElement(EMPTY);
            if (more[0]) model.addElement(MORE); // 검색 중에도 둔다: 더 받아 와서 더 넓게 검색할 수 있게
            hover[0] = -1;
            int i = prefer == null ? -1 : model.indexOf(prefer);
            if (i >= 0) list.ensureIndexIsVisible(i);
            else i = model.isEmpty() ? -1 : model.get(0) == EMPTY ? model.size() - 1 : 0; // 안내 줄은 고르지 않는다 (있으면 "더 보기")
            if (i >= 0 && model.get(i) != EMPTY) list.setSelectedIndex(i); else list.clearSelection();
            updateCount.run();
        };
        // 검색어가 바뀔 때마다 목록을 다시 채우고 첫 항목을 선택한다 (바로 Enter로 복사할 수 있게)
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuild.accept(null); }
            @Override public void removeUpdate(DocumentEvent e) { rebuild.accept(null); }
            @Override public void changedUpdate(DocumentEvent e) { rebuild.accept(null); }
        });
        // 더 보기: 다음 페이지를 받아 all에 이어 붙이고, 새로 받은 첫 항목을 선택한다 (키보드로 이어서 내려갈 수 있게)
        Runnable loadMore = () -> {
            if (loading[0] || !more[0]) return;
            loading[0] = true;
            list.repaint(); // "불러오는 중…" 표시
            pager.load(cursor[0], page -> {
                loading[0] = false;
                if (page == null) { list.repaint(); return; } // 실패: 다시 누를 수 있게 둔다
                if (!page.isEmpty()) cursor[0] = page.get(page.size() - 1).path("createdAt").asText();
                more[0] = page.size() >= PAGE;
                int before = all.size();
                merge(all, page);
                rebuild.accept(all.size() > before ? firstNew(all, page) : null);
            });
        };
        // 선택 처리: "더 보기"면 다음 페이지, 클립이면 팝업을 닫고 고른 클립을 넘긴다
        Runnable pick = () -> {
            JsonNode c = list.getSelectedValue();
            if (c == MORE) { loadMore.run(); return; }
            if (c == null || c == EMPTY) return; // 고른 게 없으면(검색 결과 없음) 아무것도 안 한다
            d.dispose();
            onPick.accept(c);
        };
        // 고정/해제: 목록에서 바로 바꾸고(고정은 맨 위로) 호출한 쪽에 알린다.
        java.util.function.IntConsumer togglePin = i -> {
            if (i < 0 || i >= model.size()) return;
            JsonNode c = model.get(i);
            if (!isClip(c)) return;
            boolean on = !c.path("pinned").asBoolean();
            if (on && all.stream().filter(x -> x.path("pinned").asBoolean()).count() >= MAX_PINNED) {
                flash(hint, "고정은 최대 " + MAX_PINNED + "개까지 할 수 있어요");
                return;
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) c).put("pinned", on);
            all.remove(c);
            merge(all, List.of(c)); // 고정 여부에 맞는 자리로 다시 넣는다
            rebuild.accept(c);
            onPin.accept(c, on);
        };
        // ----- 본문: 목록 또는 "비어 있음" 안내. 클립을 다 지우거나 빈 창에 새 클립이 들어오면 서로 바뀐다 -----
        JPanel root = new JPanel(new BorderLayout());
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 6, 8, 6));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JComponent emptyView = emptyState();
        JPanel right = new JPanel(new GridBagLayout()); // 개수 배지 (세로 가운데 정렬용)
        right.setOpaque(false);
        right.add(count);
        // true = 목록 화면, false = "비어 있음" 화면. 개수 배지와 검색칸은 목록 화면에서만 보인다.
        // 창이 이미 떠 있으면 아래쪽(트레이 쪽) 위치를 유지한 채 크기만 바꾼다.
        Consumer<Boolean> showBody = listMode -> {
            int bottom = d.getY() + d.getHeight();
            root.remove(listMode ? emptyView : scroll);
            // 목록 실제 높이만큼 늘리되 최대 420px, 그 이상은 세로 스크롤
            scroll.setPreferredSize(new Dimension(WIDTH, Math.min(list.getPreferredSize().height, 420) + 10));
            root.add(listMode ? scroll : emptyView, BorderLayout.CENTER);
            right.setVisible(listMode);
            search.setVisible(listMode);
            d.pack();
            if (d.isVisible()) {
                d.setLocation(d.getX(), bottom - d.getHeight());
                if (listMode) search.requestFocusInWindow();
            }
        };
        // 실시간 새 클립: all에 넣고(같은 클립이면 갱신되어 제자리로) 선택은 그대로 둔다. 빈 창이었으면 목록 화면으로.
        pushTo = clip -> {
            boolean wasEmpty = all.isEmpty() && !more[0];
            merge(all, List.of(clip));
            rebuild.accept(list.getSelectedValue());
            if (wasEmpty) showBody.accept(true);
        };
        // 삭제 처리: 목록에서 바로 빼고, 개수 배지를 고치고, 호출한 쪽에 알린다. 다 지우면 "비어 있음" 화면으로 바꾼다.
        java.util.function.IntConsumer delete = i -> {
            if (i < 0 || i >= model.size() || !isClip(model.get(i))) return;
            JsonNode c = model.remove(i);
            all.remove(c);
            hover[0] = -1;
            onDelete.accept(c);
            updateCount.run();
            if (all.isEmpty() && !more[0]) {
                showBody.accept(false);
            } else if (!model.isEmpty()) {
                list.setSelectedIndex(Math.min(i, model.size() - 1)); // 키보드로 연달아 지울 수 있게 다음 항목 선택
            }
        };
        MouseAdapter mouse = new MouseAdapter() {
            /** 클릭 = 선택, 단 휴지통 영역(오른쪽 끝)을 누르면 삭제 (빈 공간 클릭은 무시) */
            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i < 0 || !list.getCellBounds(i, i).contains(e.getPoint())) return;
                switch (zone(list, i, e.getPoint())) {
                    case TRASH -> delete.accept(i);
                    case PIN -> togglePin.accept(i);
                    default -> pick.run();
                }
            }

            /** 마우스가 움직이면 올라가 있는 항목(과 어느 버튼 위인지)을 기억해서 배경과 아이콘을 칠한다 */
            @Override public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                int t = i >= 0 ? zone(list, i, e.getPoint()) : 0;
                if (i != hover[0] || t != hover[1]) { hover[0] = i; hover[1] = t; list.repaint(); }
                list.setToolTipText(t == TRASH ? "삭제"
                        : t == PIN ? (model.get(i).path("pinned").asBoolean() ? "고정 해제" : "고정") : null);
            }

            @Override public void mouseExited(MouseEvent e) {
                hover[0] = -1;
                list.repaint();
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        // ----- 키보드: 검색칸에 포커스가 있는 채로 목록을 조작한다 -----
        // 위/아래 = 선택 이동
        bind(search, KeyEvent.VK_UP, () -> move(list, -1));
        bind(search, KeyEvent.VK_DOWN, () -> move(list, 1));
        // Enter = 선택
        bind(search, KeyEvent.VK_ENTER, pick);
        // Ctrl+P = 선택한 클립 고정/해제 (마우스의 핀 버튼과 같다)
        bind(search, KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK), () -> togglePin.accept(list.getSelectedIndex()));
        // Delete = 검색어가 비어 있으면 선택한 항목 삭제, 검색어가 있으면 원래대로 글자 지우기
        Action deleteChar = search.getActionMap().get(DefaultEditorKit.deleteNextCharAction);
        bind(search, KeyEvent.VK_DELETE, () -> {
            if (search.getText().isEmpty()) delete.accept(list.getSelectedIndex());
            else deleteChar.actionPerformed(new java.awt.event.ActionEvent(search, 0, null));
        });
        // Esc = 검색어가 있으면 지우기, 없으면 닫기
        bind(search, KeyEvent.VK_ESCAPE, () -> {
            if (search.getText().isEmpty()) d.dispose(); else search.setText("");
        });
        // 팝업 밖을 클릭해서 포커스를 잃으면 닫기
        d.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { d.dispose(); }
        });
        // 닫히면 실시간 넣기도 끊는다 (새 팝업이 이미 떠서 open이 바뀌었으면 건드리지 않는다)
        d.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { if (open == d) pushTo = null; }
        });

        // ----- 머리말: "최근 클립" + 개수, 안내 문구 -----
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        JLabel title = new JLabel("최근 클립");
        title.setFont(Theme.font(15f, Font.BOLD));
        title.setIcon(new ImageIcon(Theme.appIcon(18, false)));
        title.setIconTextGap(8);
        header.add(title, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        hint.setFont(Theme.font(11f, Font.PLAIN));
        hint.setForeground(Theme.muted());
        hint.setBorder(BorderFactory.createEmptyBorder(4, 26, 0, 0));
        JPanel south = new JPanel(new BorderLayout(0, 10));
        south.setOpaque(false);
        south.add(hint, BorderLayout.NORTH);
        south.add(search, BorderLayout.SOUTH);
        header.add(south, BorderLayout.SOUTH);

        root.setBorder(BorderFactory.createLineBorder(Theme.border())); // 팝업 가장자리 얇은 선
        root.add(header, BorderLayout.NORTH);
        d.setContentPane(root);
        showBody.accept(!model.isEmpty()); // 클립이 없으면 "비어 있음" 화면 (pack 포함)

        // 위치: 마우스 포인터(= 방금 클릭한 트레이 아이콘) 바로 위에 띄우되, 화면 밖이나 작업표시줄 위로 삐져나가지 않게 조정
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle screen = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc); // 작업표시줄이 차지하는 영역
        Point m = MouseInfo.getPointerInfo() != null ? MouseInfo.getPointerInfo().getLocation() : new Point(screen.width, screen.height);
        // 가로: 마우스 중심 기준, 화면 좌우 경계 안으로 제한
        int x = Math.max(screen.x + in.left, Math.min(m.x - d.getWidth() / 2, screen.x + screen.width - in.right - d.getWidth()));
        // 세로: 마우스 위쪽에 붙이되, 화면 위아래 경계 안으로 제한
        int y = Math.max(screen.y + in.top, Math.min(m.y - d.getHeight(), screen.y + screen.height - in.bottom - d.getHeight()));
        d.setLocation(x, y);
        d.setVisible(true);
        d.toFront();
        if (!model.isEmpty()) list.setSelectedIndex(0);
        search.requestFocusInWindow(); // 바로 타이핑해서 검색하거나 방향키/Enter로 조작할 수 있게 포커스
    }

    /**
     * 검색어에 맞는 클립인지. 텍스트 클립의 내용에 검색어가 들어 있으면(대소문자 무시) 맞다.
     * 이미지는 검색할 글자가 없으므로 검색어가 있으면 빠진다. 검색어가 비어 있으면 전부 맞다.
     */
    static boolean matches(JsonNode clip, String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        return !"IMAGE".equals(clip.path("type").asText())
                && clip.path("content").asText().toLowerCase(Locale.ROOT).contains(q);
    }

    /**
     * 떠 있는 최근 클립 팝업에 실시간으로 들어온 클립을 넣는다 (화면 스레드에서 호출).
     *
     * @return 팝업이 떠 있어서 목록에 넣었으면 true (호출한 쪽은 "읽지 않음" 수를 늘리지 않아도 된다)
     */
    public static boolean push(JsonNode clip) {
        if (pushTo == null) return false;
        pushTo.accept(clip);
        return true;
    }

    /** 진짜 클립인지 ("더 보기", "검색 결과 없음" 줄이 아닌지). */
    private static boolean isClip(JsonNode c) {
        return c != MORE && c != EMPTY;
    }

    /** 수식 키 없는 키 하나를 동작에 연결한다 (컴포넌트에 포커스가 있을 때). */
    private static void bind(JComponent c, int key, Runnable r) {
        bind(c, KeyStroke.getKeyStroke(key, 0), r);
    }

    /** 키 조합 하나를 동작에 연결한다 (컴포넌트에 포커스가 있을 때). */
    private static void bind(JComponent c, KeyStroke ks, Runnable r) {
        String name = "clipvault." + ks;
        c.getInputMap().put(ks, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        });
    }

    /** 선택을 위(-1)/아래(+1)로 한 칸 옮기고 보이게 스크롤한다. 끝에서는 멈춘다. */
    private static void move(JList<?> list, int step) {
        int n = list.getModel().getSize();
        int first = n > 0 && list.getModel().getElementAt(0) == EMPTY ? 1 : 0; // 안내 줄은 건너뛴다
        if (n <= first) return;
        int i = Math.max(first, Math.min(n - 1, list.getSelectedIndex() + step));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private static final String HINT = "클릭 / Enter: 복사  ·  핀 / Ctrl+P: 고정  ·  휴지통 / Delete: 삭제";

    /**
     * all에 clips를 넣는다. 같은 ID가 이미 있으면 새 값으로 바꾸고, 전체를 "고정 먼저, 그다음 최신순"으로 정렬한다.
     * (고정 목록과 최근 목록에 같은 클립이 둘 다 들어 있어도 한 번만 보이게 하기 위함)
     */
    static void merge(List<JsonNode> all, Iterable<JsonNode> clips) {
        for (JsonNode c : clips) {
            String id = c.path("id").asText();
            all.removeIf(x -> x.path("id").asText().equals(id));
            all.add(c);
        }
        all.sort(Comparator.comparing((JsonNode c) -> !c.path("pinned").asBoolean())
                .thenComparing(c -> Theme.parse(c.path("createdAt").asText()), Comparator.nullsLast(Comparator.reverseOrder())));
    }

    /** page 중 all에 들어간 첫 항목 (정렬 뒤 순서 기준). 더 보기 후 선택할 항목. */
    private static JsonNode firstNew(List<JsonNode> all, JsonNode page) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        page.forEach(c -> ids.add(c.path("id").asText()));
        return all.stream().filter(c -> !c.path("pinned").asBoolean() && ids.contains(c.path("id").asText()))
                .findFirst().orElse(null);
    }

    /** 안내 문구 자리에 잠깐(3초) 다른 문구를 보여 준다. */
    private static void flash(JLabel hint, String text) {
        hint.setText(text);
        hint.setForeground(Theme.DANGER);
        Timer t = new Timer(3000, e -> {
            hint.setText(HINT);
            hint.setForeground(Theme.muted());
        });
        t.setRepeats(false);
        t.start();
    }

    /** 휴지통 영역 너비(px). 각 칸의 오른쪽 끝 이만큼이 삭제 버튼 역할을 한다. */
    private static final int TRASH_W = 40;
    /** 핀 영역 너비(px). 휴지통 바로 왼쪽 이만큼이 고정 버튼 역할을 한다. */
    private static final int PIN_W = 26;
    /** 칸 안의 버튼 영역 번호 ({@link #zone}의 결과) */
    private static final int TRASH = 1, PIN = 2;

    /** 마우스 위치가 i번째 칸의 어느 버튼 위인지: 휴지통(오른쪽 끝), 핀(그 왼쪽), 둘 다 아니면 0. "더 보기" 줄에는 버튼이 없다. */
    private static int zone(JList<JsonNode> list, int i, Point p) {
        Rectangle r = list.getCellBounds(i, i);
        JsonNode c = list.getModel().getElementAt(i);
        if (r == null || !r.contains(p) || !isClip(c)) return 0;
        int right = r.x + r.width;
        if (p.x >= right - TRASH_W) return TRASH;
        if (p.x >= right - TRASH_W - PIN_W) return PIN;
        return 0;
    }

    /** 코드로 그린 작은 핀(압정) 아이콘. 고정된 클립은 속을 채워 그린다. */
    private static final class PinIcon implements Icon {
        Color color = Color.GRAY;
        boolean filled;

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.rotate(Math.toRadians(45), x + 8, y + 8); // 비스듬히 꽂힌 모양
            java.awt.geom.Path2D head = new java.awt.geom.Path2D.Float();
            head.moveTo(x + 5, y + 2);                   // 머리 (위가 넓은 사다리꼴)
            head.lineTo(x + 11, y + 2);
            head.lineTo(x + 10, y + 7);
            head.lineTo(x + 12.5, y + 9.5);
            head.lineTo(x + 3.5, y + 9.5);
            head.lineTo(x + 6, y + 7);
            head.closePath();
            if (filled) g2.fill(head);
            g2.draw(head);
            g2.drawLine(x + 8, y + 10, x + 8, y + 15);   // 바늘
            g2.dispose();
        }

        @Override public int getIconWidth() { return 16; }

        @Override public int getIconHeight() { return 16; }
    }

    /** 코드로 그린 작은 휴지통 아이콘. 색은 상황에 따라(평소 회색, 마우스 올리면 빨강) 바꿔 그린다. */
    private static final class TrashIcon implements Icon {
        Color color = Color.GRAY;

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x + 2, y + 4, x + 14, y + 4);        // 뚜껑
            g2.drawLine(x + 6, y + 4, x + 6, y + 2);         // 손잡이
            g2.drawLine(x + 6, y + 2, x + 10, y + 2);
            g2.drawLine(x + 10, y + 2, x + 10, y + 4);
            g2.drawRoundRect(x + 4, y + 5, 8, 10, 2, 2);     // 통
            g2.drawLine(x + 7, y + 8, x + 7, y + 12);        // 세로줄
            g2.drawLine(x + 9, y + 8, x + 9, y + 12);
            g2.dispose();
        }

        @Override public int getIconWidth() { return 16; }

        @Override public int getIconHeight() { return 16; }
    }

    /**
     * 썸네일 아이콘. 원본 비율대로 최대 220×90 안에 맞춰 그리고, 썸네일이 아직 없으면 같은 크기의 회색 자리를 그린다.
     * 받기 전후 크기가 같아서 목록이 덜컥거리지 않는다.
     */
    private static final class ThumbIcon implements Icon {
        static final int MAX_W = 220, MAX_H = 90;
        Image image;
        int w = MAX_W, h = MAX_H;

        /** 그릴 이미지(없으면 null)와 원본 크기로 표시 크기를 정한다. */
        void set(Image image, int srcW, int srcH) {
            this.image = image;
            double s = Math.min(1.0, Math.min((double) MAX_W / Math.max(1, srcW), (double) MAX_H / Math.max(1, srcH)));
            w = Math.max(1, (int) Math.round(srcW * s));
            h = Math.max(1, (int) Math.round(srcH * s));
        }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.clip(new RoundRectangle2D.Float(x, y, w, h, 8, 8)); // 모서리를 둥글게
            if (image == null) {
                g2.setColor(Theme.border());
                g2.fillRect(x, y, w, h);
            } else {
                g2.drawImage(image, x, y, w, h, null);
            }
            g2.dispose();
        }

        @Override public int getIconWidth() { return w; }

        @Override public int getIconHeight() { return h; }
    }

    /** 클립이 하나도 없을 때 보여 줄 안내. */
    private static JComponent emptyState() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(24, 16, 32, 16));
        p.setPreferredSize(new Dimension(WIDTH, 150));
        JLabel big = new JLabel("아직 클립이 없어요");
        big.setFont(Theme.font(14f, Font.BOLD));
        JLabel small = new JLabel("다른 PC에서 텍스트나 이미지를 복사(Ctrl+C)해 보세요");
        small.setForeground(Theme.muted());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        p.add(new JLabel(new ImageIcon(Theme.appIcon(40, false))), g);
        g.insets = new Insets(12, 0, 0, 0);
        p.add(big, g);
        g.insets = new Insets(4, 0, 0, 0);
        p.add(small, g);
        return p;
    }

    /**
     * 목록 한 칸을 그리는 렌더러. JList는 칸마다 이 컴포넌트를 "도장"처럼 재사용해서 그린다.
     *
     * <pre>
     * ┌──────────────────────────────────────┐
     * │ 회의 링크 https://meet.example.com/…  │  ← 내용 (줄바꿈은 공백으로, 길면 …)
     * │ ┌────────────┐                        │
     * │ │  썸네일      │                        │  ← 이미지 클립: 썸네일 (받기 전엔 회색 자리)
     * │ └────────────┘                        │
     * │ 이미지 · 1920×1080  ·  3분 전 · [다른 기기] │
     * └──────────────────────────────────────┘
     * </pre>
     *
     * <p>오른쪽 끝에는 핀(고정)과 휴지통 자리가 있다. 휴지통과 고정 안 된 핀은 마우스가 올라간 칸에서만 보이고,
     * 고정된 클립의 핀은 항상 보인다. 목록 맨 끝의 "더 보기" 줄({@link #MORE})은 따로 그린다.</p>
     */
    private static final class ClipCell implements ListCellRenderer<JsonNode> {
        private final String myDeviceId;
        private final int[] hover;
        private final Theme.RoundPanel panel = new Theme.RoundPanel(new BorderLayout(0, 4));
        private final JLabel text = new JLabel();
        private final JLabel time = new JLabel();
        private final JLabel mine = Theme.pill("이 PC", Theme.dark ? new Color(0x3F3F46) : new Color(0xE4E4E7), Theme.muted());
        private final JLabel other = Theme.pill("다른 기기", Theme.selected(), Theme.ACCENT);
        private final JPanel meta = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        private final TrashIcon trashIcon = new TrashIcon();
        /** 오른쪽 휴지통 자리. 마우스가 올라간 칸에서만 아이콘을 보여 주고, 평소엔 빈 자리로 두어 글자 폭이 흔들리지 않게 한다. */
        private final JLabel trash = new JLabel();
        private final PinIcon pinIcon = new PinIcon();
        /** 휴지통 왼쪽 핀 자리. 휴지통처럼 평소엔 빈 자리로 둔다 (고정된 클립은 항상 표시). */
        private final JLabel pin = new JLabel();
        private final Thumbs thumbs;
        private final Runnable repaint;
        private final ThumbIcon thumbIcon = new ThumbIcon();
        /** "더 보기" 줄 */
        private final Theme.RoundPanel morePanel = new Theme.RoundPanel(new BorderLayout());
        private final JLabel moreLabel = new JLabel("", SwingConstants.CENTER);
        private final boolean[] loading;
        /** "검색 결과 없음" 줄 */
        private final JPanel emptyRow = new JPanel(new BorderLayout());
        private final JLabel emptyLabel = new JLabel("", SwingConstants.CENTER);

        ClipCell(String myDeviceId, int[] hover, boolean[] loading, Thumbs thumbs, Runnable repaint) {
            this.myDeviceId = myDeviceId;
            this.hover = hover;
            this.loading = loading;
            this.thumbs = thumbs;
            this.repaint = repaint;
            morePanel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            moreLabel.setFont(Theme.font(12f, Font.BOLD));
            moreLabel.setForeground(Theme.ACCENT);
            morePanel.add(moreLabel, BorderLayout.CENTER);
            emptyRow.setOpaque(false);
            emptyRow.setBorder(BorderFactory.createEmptyBorder(22, 14, 22, 14));
            emptyLabel.setFont(Theme.font(12f, Font.PLAIN));
            emptyLabel.setForeground(Theme.muted());
            emptyRow.add(emptyLabel, BorderLayout.CENTER);
            panel.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
            text.setFont(Theme.font(13f, Font.PLAIN));
            time.setFont(Theme.font(11f, Font.PLAIN));
            time.setForeground(Theme.muted());
            meta.setOpaque(false);
            ((FlowLayout) meta.getLayout()).setHgap(0);
            JPanel textCol = new JPanel(new BorderLayout(0, 4));
            textCol.setOpaque(false);
            textCol.add(text, BorderLayout.CENTER);
            textCol.add(meta, BorderLayout.SOUTH);
            trash.setPreferredSize(new Dimension(TRASH_W - 14, 16));
            trash.setHorizontalAlignment(SwingConstants.RIGHT);
            pin.setPreferredSize(new Dimension(PIN_W, 16));
            pin.setHorizontalAlignment(SwingConstants.RIGHT);
            JPanel buttons = new JPanel(new BorderLayout());
            buttons.setOpaque(false);
            buttons.add(pin, BorderLayout.WEST);
            buttons.add(trash, BorderLayout.EAST);
            panel.add(textCol, BorderLayout.CENTER);
            panel.add(buttons, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends JsonNode> list, JsonNode clip, int index,
                                                      boolean selected, boolean focus) {
            if (clip == EMPTY) {
                emptyLabel.setText(list.getModel().getSize() > 1 ? "일치하는 클립이 없어요 · 더 보기로 이전 클립도 찾아보세요" : "일치하는 클립이 없어요");
                return emptyRow;
            }
            if (clip == MORE) {
                moreLabel.setText(loading[0] ? "불러오는 중…" : "더 보기");
                morePanel.fill = selected ? Theme.selected() : index == hover[0] ? Theme.hover() : null;
                return morePanel;
            }
            String ago = Theme.ago(Theme.parse(clip.path("createdAt").asText()));
            if ("IMAGE".equals(clip.path("type").asText())) {
                // 이미지: 썸네일(없으면 회색 자리, 받아지면 repaint로 다시 그려짐) + "이미지 · W×H"
                int w = clip.path("width").asInt(), h = clip.path("height").asInt();
                thumbIcon.set(thumbs.get(clip, repaint), w, h);
                text.setText(null);
                text.setIcon(thumbIcon);
                time.setText("이미지 · " + w + "×" + h + "  ·  " + ago + "  ·  ");
            } else {
                // 줄바꿈/연속 공백을 한 칸으로 합치고, 길면 잘라서 "…" (실제로 복사되는 값은 원문 그대로)
                String s = clip.path("content").asText().replaceAll("\\s+", " ").strip();
                text.setIcon(null);
                text.setText(s.length() > 48 ? s.substring(0, 48) + "…" : s);
                time.setText(ago + "  ·  ");
            }
            text.setForeground(list.getForeground());
            boolean fromMe = clip.path("sourceDeviceId").asText().equals(myDeviceId);
            meta.removeAll();
            meta.add(time);
            meta.add(fromMe ? mine : other);
            // 휴지통: 마우스가 올라간 칸에만 표시, 휴지통 바로 위면 빨간색
            boolean hovered = index == hover[0];
            trashIcon.color = hovered && hover[1] == 1 ? Theme.DANGER : Theme.muted();
            trash.setIcon(hovered ? trashIcon : null);
            // 핀: 고정된 클립은 항상(강조색, 속 채움), 아니면 마우스가 올라간 칸에만.
            boolean pinned = clip.path("pinned").asBoolean();
            pinIcon.filled = pinned;
            pinIcon.color = pinned || (hovered && hover[1] == PIN) ? Theme.ACCENT : Theme.muted();
            pin.setIcon(pinned || hovered ? pinIcon : null);
            // 선택 > 마우스오버 > 없음 순서로 배경 결정
            panel.fill = selected ? Theme.selected() : index == hover[0] ? Theme.hover() : null;
            return panel;
        }
    }
}
