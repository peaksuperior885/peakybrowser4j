package com.peak885.peakybrowser4jv2.browser;

import com.peak885.peakybrowser4jv2.browser.image.IconUtils;
import com.peak885.peakybrowser4jv2.browser.image.SvgIconLoader;
import com.peak885.peakybrowser4jv2.browser.internal.InternalPageManager;
import com.peak885.peakybrowser4jv2.browser.js.JsBridge;
import com.peak885.peakybrowser4jv2.browser.loading.LoadingBar;
import com.peak885.peakybrowser4jv2.browser.render.RenderView;
import net.miginfocom.swing.MigLayout;
import org.jsoup.nodes.Document;
import org.tinylog.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class BrowserWindow {

    private final JFrame frame;
    private final JTextField addressBar;
    private final BrowserLoader loader;
    private final JLabel statusLabel;
    private final LoadingBar loadingBar;

    private final JButton backButton;
    private final JButton forwardButton;
    private final JButton reloadButton;
    private final JButton homeButton;

    // ── Window controls ───────────────────────────────────────────────────
    private JButton minimizeBtn;
    private JButton maximizeBtn;
    private JButton closeWindowBtn;

    private boolean isMaximized = false;
    private Rectangle normalBounds;
    private Point dragOffset;

    // ── Resize support (undecorated frame) ─────────────────────────────────
    private static final int RESIZE_BORDER = 6;

    private int resizeDir = 0;          // bitmask: N=1 S=2 W=4 E=8
    private Point resizeStartScreen;
    private Rectangle resizeStartBounds;
    private boolean resizing = false;

    // ── Tab system ──────────────────────────────────────────────────────────
    private final JPanel tabBar;
    private final JPanel contentArea;

    private final List<BrowserTab> tabs = new ArrayList<>();

    private BrowserTab activeTab;

    private final AtomicInteger tabCounter =
            new AtomicInteger(1);

    private final String homeUrl;

    private static final Color TAB_BG =
            new Color(28, 30, 36);

    private static final Color TAB_ACTIVE_BG =
            new Color(40, 44, 52);

    private static final Color TAB_HOVER_BG =
            new Color(48, 52, 62);

    private static final Color TAB_BORDER =
            new Color(55, 60, 72);

    private static final Color TOOLBAR_TOP =
            new Color(55, 35, 75);

    private static final Color TOOLBAR_BOTTOM =
            new Color(25, 45, 85);

    private static final Color ACCENT =
            new Color(100, 160, 255);

    private static final Color STATUS_BG =
            new Color(22, 24, 30);

    private static final Color STATUS_FG =
            new Color(160, 170, 185);

    private static final Color ADDRESS_BG =
            new Color(35, 38, 48);

    private static final Color ADDRESS_BORDER =
            new Color(70, 75, 90);

    private static final Color ADDRESS_FG =
            new Color(220, 225, 235);

    private static final Color BUTTON_NORMAL =
            new Color(45, 50, 62);

    private static final Color BUTTON_HOVER =
            new Color(60, 66, 80);

    private static final Color BUTTON_PRESSED =
            new Color(35, 40, 50);

    private static final Color WIN_BTN_FG =
            new Color(160, 168, 185);

    private static final Color WIN_BTN_HOVER =
            new Color(50, 54, 66);

    private static final Color DANGER =
            new Color(232, 80, 90);

    public BrowserWindow(String initialUrl) {

        this.homeUrl =
                "https://google.com";

        frame =
                new JFrame(
                        "PeakBrowser4J v2"
                );

        // Undecorated so we own the chrome
        frame.setUndecorated(true);

        frame.setDefaultCloseOperation(
                JFrame.EXIT_ON_CLOSE
        );

        frame.setSize(
                1280,
                860
        );

        frame.setMinimumSize(
                new Dimension(
                        800,
                        550
                )
        );

        frame.setLocationRelativeTo(null);

        Icon svgIcon = SvgIconLoader.load("/icons/peaky-icon.svg", 32);

        Image appIcon = IconUtils.iconToImage(svgIcon);

        frame.setIconImage(appIcon);

        if (Taskbar.isTaskbarSupported()) {
            try {
                Taskbar.getTaskbar().setIconImage(appIcon);
            } catch (UnsupportedOperationException | SecurityException e) {
            }
        }

        // ── Browser services ──────────────────────────────────────────────

        loader =
                new BrowserLoader();

        // ── Tab bar (includes window controls, Chrome-style) ─────────────

        tabBar =
                new JPanel(
                        new MigLayout(
                                "insets 6 8 0 8, gapx 4, aligny bottom",
                                "[grow][]",
                                "[]"
                        )
                ) {

                    @Override
                    protected void paintComponent(
                            Graphics g
                    ) {
                        Graphics2D g2 =
                                (Graphics2D) g.create();

                        g2.setColor(
                                TAB_BG
                        );

                        g2.fillRect(
                                0,
                                0,
                                getWidth(),
                                getHeight()
                        );

                        g2.setColor(
                                TAB_BORDER
                        );

                        g2.drawLine(
                                0,
                                getHeight() - 1,
                                getWidth(),
                                getHeight() - 1
                        );

                        g2.dispose();
                    }
                };

        tabBar.setOpaque(
                false
        );

        // Drag the window by empty areas of the tab bar
        MouseAdapter dragListener =
                new MouseAdapter() {

                    @Override
                    public void mousePressed(
                            MouseEvent e
                    ) {
                        if (SwingUtilities.isLeftMouseButton(e)
                                && !isMaximized) {

                            // Offset relative to the frame so it works from any child component
                            Point frameLoc =
                                    frame.getLocationOnScreen();

                            dragOffset =
                                    new Point(
                                            e.getXOnScreen() - frameLoc.x,
                                            e.getYOnScreen() - frameLoc.y
                                    );
                        }
                    }

                    @Override
                    public void mouseDragged(
                            MouseEvent e
                    ) {
                        if (dragOffset != null
                                && !isMaximized) {

                            frame.setLocation(
                                    e.getXOnScreen() - dragOffset.x,
                                    e.getYOnScreen() - dragOffset.y
                            );
                        }
                    }

                    @Override
                    public void mouseReleased(
                            MouseEvent e
                    ) {
                        dragOffset = null;
                    }

                    @Override
                    public void mouseClicked(
                            MouseEvent e
                    ) {
                        if (e.getClickCount() == 2
                                && SwingUtilities.isLeftMouseButton(e)) {

                            toggleMaximize();
                        }
                    }
                };

        tabBar.addMouseListener(
                dragListener
        );

        tabBar.addMouseMotionListener(
                dragListener
        );

        JButton newTabBtn =
                createNewTabButton();

        // Left side: tabs + new-tab button will be inserted before the controls
        // We keep a spacer panel that grows, then the controls on the far right.
        JPanel tabsContainer =
                new JPanel(
                        new MigLayout(
                                "insets 0, gapx 4, aligny bottom",
                                "",
                                "[]"
                        )
                );

        tabsContainer.setOpaque(
                false
        );

        // Allow dragging when clicking empty space in the tabs container too
        tabsContainer.addMouseListener(
                dragListener
        );

        tabsContainer.addMouseMotionListener(
                dragListener
        );

        tabsContainer.add(
                newTabBtn,
                "w 32!, h 28!"
        );

        // Window controls (right side of tab bar)
        Icon minIcon =
                SvgIconLoader.load(
                        "/icons/minimize.svg",
                        12
                );

        Icon maxIcon =
                SvgIconLoader.load(
                        "/icons/maximize.svg",
                        12
                );

        Icon closeIcon =
                SvgIconLoader.load(
                        "/icons/close.svg",
                        12
                );

        minimizeBtn =
                createWindowButton(
                        minIcon,
                        "Minimize",
                        false
                );

        maximizeBtn =
                createWindowButton(
                        maxIcon,
                        "Maximize",
                        false
                );

        closeWindowBtn =
                createWindowButton(
                        closeIcon,
                        "Close",
                        true
                );

        minimizeBtn.addActionListener(
                e -> frame.setState(
                        Frame.ICONIFIED
                )
        );

        maximizeBtn.addActionListener(
                e -> toggleMaximize()
        );

        closeWindowBtn.addActionListener(
                e -> {
                    frame.dispose();
                    System.exit(0);
                }
        );

        JPanel controls =
                new JPanel(
                        new MigLayout(
                                "insets 0, gapx 0"
                        )
                );

        controls.setOpaque(
                false
        );

        controls.add(
                minimizeBtn,
                "w 46!, h 32!"
        );

        controls.add(
                maximizeBtn,
                "w 46!, h 32!"
        );

        controls.add(
                closeWindowBtn,
                "w 46!, h 32!"
        );

        tabBar.add(
                tabsContainer,
                "growx"
        );

        tabBar.add(
                controls,
                "gapleft 8"
        );

        // ── Main toolbar ──────────────────────────────────────────────────

        JPanel toolbar =
                new JPanel(
                        new MigLayout(
                                "insets 8 12 8 12, gapx 6, aligny center",
                                "[][][][] 14 [grow, fill] 8 []",
                                "[]"
                        )
                ) {

                    @Override
                    protected void paintComponent(
                            Graphics g
                    ) {
                        Graphics2D g2 =
                                (Graphics2D) g.create();

                        g2.setRenderingHint(
                                RenderingHints.KEY_RENDERING,
                                RenderingHints.VALUE_RENDER_QUALITY
                        );

                        GradientPaint gp =
                                new GradientPaint(
                                        0,
                                        0,
                                        TOOLBAR_TOP,
                                        0,
                                        getHeight(),
                                        TOOLBAR_BOTTOM
                                );

                        g2.setPaint(gp);

                        g2.fillRect(
                                0,
                                0,
                                getWidth(),
                                getHeight()
                        );

                        g2.dispose();
                    }
                };

        // Keep YOUR existing toolbar SVGs
        Icon backIcon =
                SvgIconLoader.load(
                        "/icons/back.svg"
                );

        Icon forwardIcon =
                SvgIconLoader.load(
                        "/icons/forward.svg"
                );

        Icon reloadIcon =
                SvgIconLoader.load(
                        "/icons/reload.svg"
                );

        Icon homeIcon =
                SvgIconLoader.load(
                        "/icons/home.svg"
                );

        backButton =
                createToolButton(
                        backIcon,
                        "Back (Alt+←)",
                        KeyEvent.VK_LEFT
                );

        forwardButton =
                createToolButton(
                        forwardIcon,
                        "Forward (Alt+→)",
                        KeyEvent.VK_RIGHT
                );

        reloadButton =
                createToolButton(
                        reloadIcon,
                        "Reload (Ctrl+R)",
                        KeyEvent.VK_R
                );

        homeButton =
                createToolButton(
                        homeIcon,
                        "Home",
                        KeyEvent.VK_H
                );

        backButton.setEnabled(
                false
        );

        forwardButton.setEnabled(
                false
        );

        // ── Address bar ───────────────────────────────────────────────────

        addressBar =
                new JTextField();

        addressBar.putClientProperty(
                "JTextField.placeholderText",
                "Search or enter address"
        );

        addressBar.setFont(
                new Font(
                        "Segoe UI",
                        Font.PLAIN,
                        14
                )
        );

        addressBar.setMargin(
                new Insets(
                        7,
                        14,
                        7,
                        14
                )
        );

        addressBar.setForeground(
                ADDRESS_FG
        );

        addressBar.setCaretColor(
                ADDRESS_FG
        );

        addressBar.setBackground(
                ADDRESS_BG
        );

        addressBar.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(
                                ADDRESS_BORDER,
                                1,
                                true
                        ),
                        new EmptyBorder(
                                1,
                                2,
                                1,
                                2
                        )
                )
        );

        JButton goButton =
                new JButton("Go");

        styleFlatButton(
                goButton,
                ACCENT,
                Color.WHITE
        );

        goButton.setMargin(
                new Insets(
                        7,
                        18,
                        7,
                        18
                )
        );

        goButton.setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
        );

        toolbar.add(backButton);
        toolbar.add(forwardButton);
        toolbar.add(reloadButton);
        toolbar.add(homeButton);

        toolbar.add(
                addressBar,
                "growx, h 36!"
        );

        toolbar.add(
                goButton,
                "h 36!"
        );

        // ── Loading bar ───────────────────────────────────────────────────

        loadingBar =
                new LoadingBar();

        // ── Content area ──────────────────────────────────────────────────

        contentArea =
                new JPanel(
                        new BorderLayout()
                );

        contentArea.setBackground(
                Color.WHITE
        );

        // ── Status bar ────────────────────────────────────────────────────

        statusLabel =
                new JLabel("Ready");

        statusLabel.setBorder(
                new EmptyBorder(
                        5,
                        14,
                        5,
                        14
                )
        );

        statusLabel.setFont(
                new Font(
                        "Segoe UI",
                        Font.PLAIN,
                        12
                )
        );

        statusLabel.setForeground(
                STATUS_FG
        );

        JPanel statusBar =
                new JPanel(
                        new BorderLayout()
                );

        statusBar.setBorder(
                BorderFactory.createMatteBorder(
                        1,
                        0,
                        0,
                        0,
                        TAB_BORDER
                )
        );

        statusBar.setBackground(
                STATUS_BG
        );

        statusBar.add(
                statusLabel,
                BorderLayout.CENTER
        );

        // ── Root layout ───────────────────────────────────────────────────

        JPanel root =
                new JPanel(
                        new MigLayout(
                                "fill, insets 0, gap 0",
                                "[grow]",
                                "[]0[]0[]0[grow]0[]"
                        )
                );

        root.setBackground(
                new Color(
                        22,
                        24,
                        30
                )
        );

        // Thin outer border so undecorated window still has an edge
        root.setBorder(
                BorderFactory.createLineBorder(
                        TAB_BORDER,
                        1
                )
        );

        contentArea.setBackground(
                new Color(
                        22,
                        24,
                        30
                )
        );

        root.add(
                tabBar,
                "growx, wrap"
        );

        root.add(
                toolbar,
                "growx, wrap"
        );

        root.add(
                loadingBar,
                "growx, wrap"
        );

        root.add(
                contentArea,
                "grow, wrap"
        );

        root.add(
                statusBar,
                "growx"
        );

        frame.setContentPane(
                root
        );

        installResizeSupport(root);

        // ── Actions ───────────────────────────────────────────────────────

        Action goAction =
                new AbstractAction() {

                    @Override
                    public void actionPerformed(
                            ActionEvent e
                    ) {
                        if (activeTab != null) {
                            loadInTab(
                                    activeTab,
                                    addressBar
                                            .getText()
                                            .trim()
                            );
                        }
                    }
                };

        goButton.addActionListener(
                goAction
        );

        addressBar.addActionListener(
                goAction
        );

        reloadButton.addActionListener(
                e -> {
                    if (activeTab != null
                            && activeTab.currentUrl != null) {

                        loadInTab(
                                activeTab,
                                activeTab.currentUrl
                        );
                    }
                }
        );

        homeButton.addActionListener(
                e -> {
                    if (activeTab != null) {
                        loadInTab(
                                activeTab,
                                homeUrl
                        );
                    }
                }
        );

        backButton.addActionListener(
                e -> {
                    if (activeTab != null) {
                        navigateHistory(
                                activeTab,
                                -1
                        );
                    }
                }
        );

        forwardButton.addActionListener(
                e -> {
                    if (activeTab != null) {
                        navigateHistory(
                                activeTab,
                                1
                        );
                    }
                }
        );

        // ── Keyboard shortcuts ────────────────────────────────────────────

        InputMap im =
                frame.getRootPane()
                        .getInputMap(
                                JComponent.WHEN_IN_FOCUSED_WINDOW
                        );

        ActionMap am =
                frame.getRootPane()
                        .getActionMap();

        im.put(
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_L,
                        KeyEvent.CTRL_DOWN_MASK
                ),
                "focusAddress"
        );

        am.put(
                "focusAddress",
                new AbstractAction() {

                    @Override
                    public void actionPerformed(
                            ActionEvent e
                    ) {
                        addressBar
                                .requestFocusInWindow();

                        addressBar.selectAll();
                    }
                }
        );

        im.put(
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_T,
                        KeyEvent.CTRL_DOWN_MASK
                ),
                "newTab"
        );

        am.put(
                "newTab",
                new AbstractAction() {

                    @Override
                    public void actionPerformed(
                            ActionEvent e
                    ) {
                        openNewTab(
                                homeUrl
                        );
                    }
                }
        );

        im.put(
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_W,
                        KeyEvent.CTRL_DOWN_MASK
                ),
                "closeTab"
        );

        am.put(
                "closeTab",
                new AbstractAction() {

                    @Override
                    public void actionPerformed(
                            ActionEvent e
                    ) {
                        if (activeTab != null) {
                            closeTab(
                                    activeTab
                            );
                        }
                    }
                }
        );

        im.put(
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_TAB,
                        KeyEvent.CTRL_DOWN_MASK
                ),
                "nextTab"
        );

        am.put(
                "nextTab",
                new AbstractAction() {

                    @Override
                    public void actionPerformed(
                            ActionEvent e
                    ) {
                        cycleTab(1);
                    }
                }
        );

        im.put(
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_TAB,
                        KeyEvent.CTRL_DOWN_MASK
                                | KeyEvent.SHIFT_DOWN_MASK
                ),
                "prevTab"
        );

        am.put(
                "prevTab",
                new AbstractAction() {

                    @Override
                    public void actionPerformed(
                            ActionEvent e
                    ) {
                        cycleTab(-1);
                    }
                }
        );

        im.put(
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_F11,
                        0
                ),
                "toggleMaximize"
        );

        am.put(
                "toggleMaximize",
                new AbstractAction() {

                    @Override
                    public void actionPerformed(
                            ActionEvent e
                    ) {
                        toggleMaximize();
                    }
                }
        );

        // ── Open first tab ────────────────────────────────────────────────

        openNewTab(
                initialUrl
        );
    }

    // ── Window controls helpers ───────────────────────────────────────────

    private JButton createWindowButton(
            Icon icon,
            String tooltip,
            boolean isClose
    ) {
        JButton btn =
                new JButton(icon) {

                    @Override
                    protected void paintComponent(
                            Graphics g
                    ) {
                        Graphics2D g2 =
                                (Graphics2D) g.create();

                        g2.setRenderingHint(
                                RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON
                        );

                        if (getModel().isPressed()) {

                            g2.setColor(
                                    BUTTON_PRESSED
                            );

                            g2.fillRect(
                                    0,
                                    0,
                                    getWidth(),
                                    getHeight()
                            );

                        } else if (getModel().isRollover()) {

                            if (isClose) {

                                g2.setColor(
                                        DANGER
                                );

                            } else {

                                g2.setColor(
                                        WIN_BTN_HOVER
                                );
                            }

                            g2.fillRect(
                                    0,
                                    0,
                                    getWidth(),
                                    getHeight()
                            );
                        }

                        g2.dispose();

                        super.paintComponent(g);
                    }
                };

        btn.setToolTipText(
                tooltip
        );

        btn.setFocusable(
                false
        );

        btn.setContentAreaFilled(
                false
        );

        btn.setOpaque(
                false
        );

        btn.setBorder(
                BorderFactory.createEmptyBorder()
        );

        btn.setForeground(
                WIN_BTN_FG
        );

        btn.setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
        );

        return btn;
    }

    // ── Resize support ────────────────────────────────────────────────────

    private void installResizeSupport(JPanel root) {
        // AWTEventListener sees every mouse event in the window hierarchy,
        // so resize works even when the cursor is over child components
        // (status bar, content area, toolbar, etc.).
        long mask =
                AWTEvent.MOUSE_MOTION_EVENT_MASK
                        | AWTEvent.MOUSE_EVENT_MASK;

        Toolkit.getDefaultToolkit().addAWTEventListener(
                event -> {
                    if (!(event instanceof MouseEvent me)) {
                        return;
                    }

                    if (!(me.getSource() instanceof Component src)) {
                        return;
                    }

                    // Only handle events that belong to this frame
                    if (!SwingUtilities.isDescendingFrom(src, frame)
                            && src != frame) {
                        return;
                    }

                    // Don't interfere while maximized
                    if (isMaximized) {
                        if (resizing) {
                            endResize();
                        }
                        return;
                    }

                    int id =
                            me.getID();

                    if (id == MouseEvent.MOUSE_MOVED) {
                        if (resizing) {
                            return;
                        }

                        Point p =
                                SwingUtilities.convertPoint(
                                        src,
                                        me.getPoint(),
                                        frame
                                );

                        int dir =
                                getResizeDirection(p);

                        Cursor c =
                                cursorForDir(dir);

                        // Only force the cursor when we're on a resize edge;
                        // otherwise leave it alone so buttons / text fields
                        // keep their normal cursors.
                        if (dir != 0) {
                            frame.setCursor(c);
                        } else if (frame.getCursor().getType()
                                != Cursor.DEFAULT_CURSOR
                                && frame.getCursor().getType()
                                != Cursor.HAND_CURSOR
                                && frame.getCursor().getType()
                                != Cursor.TEXT_CURSOR) {
                            frame.setCursor(
                                    Cursor.getDefaultCursor()
                            );
                        }

                    } else if (id == MouseEvent.MOUSE_PRESSED) {
                        if (!SwingUtilities.isLeftMouseButton(me)) {
                            return;
                        }

                        Point p =
                                SwingUtilities.convertPoint(
                                        src,
                                        me.getPoint(),
                                        frame
                                );

                        int dir =
                                getResizeDirection(p);

                        if (dir != 0) {
                            resizing = true;
                            resizeDir = dir;
                            resizeStartScreen =
                                    me.getLocationOnScreen();
                            resizeStartBounds =
                                    frame.getBounds();
                        }

                    } else if (id == MouseEvent.MOUSE_DRAGGED) {
                        if (!resizing
                                || resizeStartScreen == null
                                || resizeStartBounds == null) {
                            return;
                        }

                        int dx =
                                me.getXOnScreen()
                                        - resizeStartScreen.x;

                        int dy =
                                me.getYOnScreen()
                                        - resizeStartScreen.y;

                        Rectangle b =
                                new Rectangle(
                                        resizeStartBounds
                                );

                        Dimension min =
                                frame.getMinimumSize();

                        if ((resizeDir & 4) != 0) {          // West
                            int newW =
                                    b.width - dx;

                            if (newW >= min.width) {
                                b.x = b.x + dx;
                                b.width = newW;
                            }
                        }

                        if ((resizeDir & 8) != 0) {          // East
                            int newW =
                                    b.width + dx;

                            if (newW >= min.width) {
                                b.width = newW;
                            }
                        }

                        if ((resizeDir & 1) != 0) {          // North
                            int newH =
                                    b.height - dy;

                            if (newH >= min.height) {
                                b.y = b.y + dy;
                                b.height = newH;
                            }
                        }

                        if ((resizeDir & 2) != 0) {          // South
                            int newH =
                                    b.height + dy;

                            if (newH >= min.height) {
                                b.height = newH;
                            }
                        }

                        frame.setBounds(b);

                    } else if (id == MouseEvent.MOUSE_RELEASED) {
                        if (resizing) {
                            endResize();
                        }
                    }
                },
                mask
        );
    }

    private void endResize() {
        resizing = false;
        resizeDir = 0;
        resizeStartScreen = null;
        resizeStartBounds = null;
        frame.setCursor(
                Cursor.getDefaultCursor()
        );
    }

    private int getResizeDirection(Point pInFrame) {
        int w =
                frame.getWidth();

        int h =
                frame.getHeight();

        int x =
                pInFrame.x;

        int y =
                pInFrame.y;

        int dir = 0;

        if (y >= 0 && y < RESIZE_BORDER) {
            dir |= 1;   // North
        }

        if (y >= h - RESIZE_BORDER && y < h) {
            dir |= 2;   // South
        }

        if (x >= 0 && x < RESIZE_BORDER) {
            dir |= 4;   // West
        }

        if (x >= w - RESIZE_BORDER && x < w) {
            dir |= 8;   // East
        }

        return dir;
    }

    private Cursor cursorForDir(int dir) {
        return switch (dir) {
            case 1 ->                      // N
                    Cursor.getPredefinedCursor(
                            Cursor.N_RESIZE_CURSOR
                    );
            case 2 ->                      // S
                    Cursor.getPredefinedCursor(
                            Cursor.S_RESIZE_CURSOR
                    );
            case 4 ->                      // W
                    Cursor.getPredefinedCursor(
                            Cursor.W_RESIZE_CURSOR
                    );
            case 8 ->                      // E
                    Cursor.getPredefinedCursor(
                            Cursor.E_RESIZE_CURSOR
                    );
            case 1 | 4 ->                  // NW
                    Cursor.getPredefinedCursor(
                            Cursor.NW_RESIZE_CURSOR
                    );
            case 1 | 8 ->                  // NE
                    Cursor.getPredefinedCursor(
                            Cursor.NE_RESIZE_CURSOR
                    );
            case 2 | 4 ->                  // SW
                    Cursor.getPredefinedCursor(
                            Cursor.SW_RESIZE_CURSOR
                    );
            case 2 | 8 ->                  // SE
                    Cursor.getPredefinedCursor(
                            Cursor.SE_RESIZE_CURSOR
                    );
            default ->
                    Cursor.getDefaultCursor();
        };
    }

    /**
     * Toggle maximize / restore.
     * Uses the GraphicsConfiguration of the monitor the window is currently on,
     * so maximizing on a secondary display stays on that display.
     */
    private void toggleMaximize() {

        if (isMaximized) {

            frame.setBounds(
                    normalBounds
            );

            isMaximized = false;

            maximizeBtn.setIcon(
                    SvgIconLoader.load(
                            "/icons/maximize.svg",
                            12
                    )
            );

            maximizeBtn.setToolTipText(
                    "Maximize"
            );

        } else {

            normalBounds =
                    frame.getBounds();

            // Use the screen the window is currently displayed on
            GraphicsConfiguration gc =
                    frame.getGraphicsConfiguration();

            Rectangle screenBounds =
                    gc.getBounds();

            Insets screenInsets =
                    Toolkit.getDefaultToolkit()
                            .getScreenInsets(gc);

            int x =
                    screenBounds.x
                            + screenInsets.left;

            int y =
                    screenBounds.y
                            + screenInsets.top;

            int w =
                    screenBounds.width
                            - screenInsets.left
                            - screenInsets.right;

            int h =
                    screenBounds.height
                            - screenInsets.top
                            - screenInsets.bottom;

            frame.setBounds(
                    x,
                    y,
                    w,
                    h
            );

            isMaximized = true;

            maximizeBtn.setIcon(
                    SvgIconLoader.load(
                            "/icons/restore.svg",
                            12
                    )
            );

            maximizeBtn.setToolTipText(
                    "Restore"
            );
        }
    }

    // ── Downloads ─────────────────────────────────────────────────────────

    /**
     * Starts a browser download using the shared HttpManager.
     *
     * @param url URL to download
     * @param suggestedFileName optional filename, may be null
     */
    public void download(
            String url,
            String suggestedFileName
    ) {
        if (url == null || url.isBlank()) {
            Logger.warn(
                    "[DOWNLOAD] Ignoring empty URL"
            );
            return;
        }

        loader
                .getDownloadManager()
                .download(
                        frame,
                        url,
                        suggestedFileName
                );
    }

    /**
     * Starts a browser download and lets the DownloadManager
     * determine the filename from the URL.
     */
    public void download(
            String url
    ) {
        download(
                url,
                null
        );
    }

    // ── Tab management ────────────────────────────────────────────────────

    private void openNewTab(
            String url
    ) {
        BrowserTab tab =
                new BrowserTab(
                        "New Tab "
                                + tabCounter.getAndIncrement()
                );

        tabs.add(tab);

        // Insert the new tab button just before the new-tab "+" button
        // (tabsContainer's last component is the "+" button)
        Component[] comps =
                ((JPanel) tabBar.getComponent(0)).getComponents();

        // tabsContainer is the first child of tabBar
        JPanel tabsContainer =
                (JPanel) tabBar.getComponent(0);

        // Insert before the last child (the "+" button)
        tabsContainer.add(
                tab.tabButton,
                tabsContainer.getComponentCount() - 1
        );

        tabsContainer.revalidate();
        tabsContainer.repaint();

        tabBar.revalidate();
        tabBar.repaint();

        switchToTab(tab);

        loadInTab(
                tab,
                url
        );
    }

    private void closeTab(
            BrowserTab tab
    ) {
        if (tabs.size() <= 1) {

            tab.renderView.clear();

            tab.title = "New Tab";
            tab.currentUrl = null;

            tab.tabLabel.setText(
                    tab.title
            );

            tab.tabLabel.setIcon(null);

            addressBar.setText("");

            statusLabel.setText(
                    "Ready"
            );

            frame.setTitle(
                    "PeakBrowser4J v2"
            );

            return;
        }

        int idx =
                tabs.indexOf(tab);

        tabs.remove(tab);

        JPanel tabsContainer =
                (JPanel) tabBar.getComponent(0);

        tabsContainer.remove(
                tab.tabButton
        );

        tabsContainer.revalidate();
        tabsContainer.repaint();

        tabBar.revalidate();
        tabBar.repaint();

        int newIdx =
                Math.min(
                        idx,
                        tabs.size() - 1
                );

        switchToTab(
                tabs.get(newIdx)
        );
    }

    private void navigateHistory(
            BrowserTab tab,
            int direction
    ) {
        int targetIndex =
                tab.historyIndex
                        + direction;

        if (targetIndex < 0
                || targetIndex >= tab.history.size()) {
            return;
        }

        tab.historyIndex =
                targetIndex;

        tab.navigatingHistory =
                true;

        loadInTab(
                tab,
                tab.history.get(targetIndex),
                false
        );
    }

    private void updateNavigationButtons() {
        if (activeTab == null) {
            backButton.setEnabled(false);
            forwardButton.setEnabled(false);
            return;
        }

        backButton.setEnabled(
                activeTab.historyIndex > 0
        );

        forwardButton.setEnabled(
                activeTab.historyIndex >= 0
                        && activeTab.historyIndex
                        < activeTab.history.size() - 1
        );
    }

    private void switchToTab(
            BrowserTab tab
    ) {
        if (activeTab == tab) {
            return;
        }

        if (activeTab != null) {
            activeTab.setActive(false);
        }

        activeTab = tab;

        activeTab.setActive(true);

        contentArea.removeAll();

        contentArea.add(
                tab.scrollPane,
                BorderLayout.CENTER
        );

        contentArea.revalidate();
        contentArea.repaint();

        addressBar.setText(
                tab.currentUrl != null
                        ? tab.currentUrl
                        : ""
        );

        statusLabel.setText(
                tab.statusText
        );

        frame.setTitle(
                tab.title
                        + " – PeakBrowser4J v2"
        );

        updateNavigationButtons();
    }

    private void cycleTab(
            int direction
    ) {
        if (tabs.isEmpty()) {
            return;
        }

        int idx =
                tabs.indexOf(activeTab);

        int next =
                (idx + direction + tabs.size())
                        % tabs.size();

        switchToTab(
                tabs.get(next)
        );
    }

    // ── Navigation ────────────────────────────────────────────────────────

    private void loadInTab(
            BrowserTab tab,
            String url
    ) {
        loadInTab(
                tab,
                url,
                true
        );
    }

    private void loadInTab(
            BrowserTab tab,
            String url,
            boolean addToHistory
    ) {
        if (url == null
                || url.isBlank()) {
            return;
        }

        boolean internalPage =
                InternalPageManager.isInternal(url);

        if (!internalPage
                && !url.startsWith("http://")
                && !url.startsWith("https://")) {

            url =
                    "https://" + url;
        }

        String displayUrl = url;

        if (internalPage) {
            String resourcePath =
                    InternalPageManager.resolve(url);

            var resource =
                    getClass().getResource(resourcePath);

            if (resource == null) {
                throw new IllegalStateException(
                        "Internal page resource not found: "
                                + resourcePath
                );
            }

            url = resource.toExternalForm();
        }

        if (addToHistory) {

            if (tab.historyIndex
                    < tab.history.size() - 1) {

                tab.history.subList(
                        tab.historyIndex + 1,
                        tab.history.size()
                ).clear();
            }

            if (tab.history.isEmpty()
                    || !tab.history
                    .get(
                            tab.history.size() - 1
                    )
                    .equals(displayUrl)) {

                tab.history.add(displayUrl);

                tab.historyIndex =
                        tab.history.size() - 1;
            }
        }

        tab.currentUrl =
                displayUrl;

        addressBar.setText(
                displayUrl
        );

        tab.statusText =
                "Loading " + url + " …";

        statusLabel.setText(
                tab.statusText
        );

        updateNavigationButtons();

        loadingBar.start();

        String finalUrl =
                url;

        float viewportWidth =
                tab.renderView.getWidth() > 0
                        ? tab.renderView.getWidth()
                        : 900f;

        new SwingWorker<
                RenderView.LoadedPage,
                Integer
                >() {

            @Override
            protected RenderView.LoadedPage
            doInBackground()
                    throws Exception {

                Document doc =
                        loader.load(
                                finalUrl
                        );

                publish(30);

                JsBridge bridge =
                        new JsBridge(
                                doc,
                                loader.getHttpManager(),
                                tab.renderView
                                        ::invalidateLayout
                        );

                bridge.setOnNavigate(url -> {
                    loadInTab(tab, url);
                });

                bridge.executeInlineScripts();

                publish(60);

                var rootBox =
                        tab.renderView.buildLayout(
                                doc,
                                viewportWidth
                        );

                publish(90);

                return new RenderView.LoadedPage(
                        doc,
                        rootBox,
                        bridge
                );
            }

            @Override
            protected void process(
                    List<Integer> chunks
            ) {
                if (!chunks.isEmpty()
                        && activeTab == tab) {

                    loadingBar.setProgress(
                            chunks.get(
                                    chunks.size() - 1
                            )
                    );
                }
            }

            @Override
            protected void done() {
                try {

                    RenderView.LoadedPage page =
                            get();

                    Document doc =
                            page.document();

                    String title =
                            doc.title().isBlank()
                                    ? "Untitled"
                                    : doc.title();

                    tab.title =
                            title.length() > 28
                                    ? title.substring(
                                    0,
                                    26
                            ) + "…"
                                    : title;

                    tab.tabLabel.setText(
                            tab.title
                    );

                    tab.statusText =
                            "Loaded · " + title;

                    tab.renderView.applyLoadedPage(
                            page
                    );

                    if (activeTab == tab) {

                        frame.setTitle(
                                title
                                        + " – PeakBrowser4J v2"
                        );

                        statusLabel.setText(
                                tab.statusText
                        );

                        loadingBar.finish();

                        updateNavigationButtons();
                    }

                    String faviconUrl =
                            loader.parseFaviconUrl(
                                    doc,
                                    finalUrl
                            );

                    if (faviconUrl != null) {

                        new SwingWorker<
                                Image,
                                Void
                                >() {

                            @Override
                            protected Image
                            doInBackground() {

                                try {

                                    var imgData =
                                            loader.loadImage(
                                                    faviconUrl
                                            );

                                    if (imgData != null
                                            && !imgData.frames()
                                            .isEmpty()) {

                                        return imgData
                                                .firstFrame()
                                                .getScaledInstance(
                                                        16,
                                                        16,
                                                        Image.SCALE_SMOOTH
                                                );
                                    }

                                } catch (Exception ignored) {
                                }

                                return null;
                            }

                            @Override
                            protected void done() {

                                try {

                                    Image iconImg =
                                            get();

                                    if (iconImg != null) {

                                        tab.tabLabel.setIcon(
                                                new ImageIcon(
                                                        iconImg
                                                )
                                        );

                                        tab.tabButton
                                                .revalidate();

                                        tab.tabButton
                                                .repaint();
                                    }

                                } catch (Exception ignored) {
                                }
                            }
                        }.execute();
                    }

                    Logger.info(
                            "Rendered: {}",
                            title
                    );

                } catch (Exception e) {
                    // Unwrap the ExecutionException if thrown by SwingWorker.get()
                    Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;

                    // Check if this was actually a request to download a file
                    if (cause instanceof BrowserLoader.DownloadRequiredException) {
                        tab.statusText = "Downloading...";

                        if (activeTab == tab) {
                            statusLabel.setText(tab.statusText);
                            loadingBar.stop();
                        }

                        // Trigger your download manager using the URL we were loading
                        download(finalUrl);
                        return;
                    }

                    // Otherwise, handle it as a normal page load error
                    tab.renderView.clear();

                    tab.statusText =
                            "Error · "
                                    + cause.getMessage();

                    Logger.error(
                            cause,
                            "Failed to load {}",
                            finalUrl
                    );

                    if (activeTab == tab) {

                        statusLabel.setText(
                                tab.statusText
                        );

                        loadingBar.stop();

                        JOptionPane.showMessageDialog(
                                frame,
                                "Failed to load page:\n"
                                        + cause.getMessage(),
                                "Load Error",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                }
            }
        }.execute();
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private JButton createNewTabButton() {

        JButton btn =
                new JButton("+") {

                    @Override
                    protected void paintComponent(
                            Graphics g
                    ) {
                        Graphics2D g2 =
                                (Graphics2D) g.create();

                        g2.setRenderingHint(
                                RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON
                        );

                        if (getModel().isRollover()) {

                            g2.setColor(
                                    TAB_HOVER_BG
                            );

                            g2.fillRoundRect(
                                    0,
                                    0,
                                    getWidth(),
                                    getHeight(),
                                    8,
                                    8
                            );
                        }

                        g2.dispose();

                        super.paintComponent(g);
                    }
                };

        btn.setFont(
                new Font(
                        "Segoe UI",
                        Font.BOLD,
                        16
                )
        );

        btn.setForeground(
                new Color(
                        80,
                        85,
                        95
                )
        );

        btn.setFocusable(false);
        btn.setContentAreaFilled(false);

        btn.setBorder(
                BorderFactory.createEmptyBorder(
                        2,
                        6,
                        2,
                        6
                )
        );

        btn.setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
        );

        btn.setToolTipText(
                "New Tab (Ctrl+T)"
        );

        btn.addActionListener(
                e -> openNewTab(homeUrl)
        );

        return btn;
    }

    private JButton createToolButton(
            Icon icon,
            String tooltip,
            int mnemonic
    ) {
        JButton btn =
                new JButton(icon) {

                    @Override
                    protected void paintComponent(
                            Graphics g
                    ) {
                        Graphics2D g2 =
                                (Graphics2D) g.create();

                        g2.setRenderingHint(
                                RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON
                        );

                        if (getModel().isPressed()) {

                            g2.setColor(
                                    BUTTON_PRESSED
                            );

                        } else if (
                                getModel().isRollover()
                                        && isEnabled()
                        ) {

                            g2.setColor(
                                    BUTTON_HOVER
                            );

                        } else {

                            g2.setColor(
                                    BUTTON_NORMAL
                            );
                        }

                        g2.fillRoundRect(
                                0,
                                0,
                                getWidth(),
                                getHeight(),
                                12,
                                12
                        );

                        g2.dispose();

                        super.paintComponent(g);
                    }
                };

        btn.setToolTipText(
                tooltip
        );

        btn.setMnemonic(
                mnemonic
        );

        btn.setFocusable(false);

        btn.setContentAreaFilled(false);

        btn.setOpaque(false);

        btn.setBorder(
                BorderFactory.createEmptyBorder(
                        6,
                        10,
                        6,
                        10
                )
        );

        btn.setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
        );

        return btn;
    }

    private void styleFlatButton(
            JButton button,
            Color bg,
            Color fg
    ) {
        button.setFocusable(false);

        button.setBackground(
                bg
        );

        button.setForeground(
                fg
        );

        button.setFont(
                new Font(
                        "Segoe UI",
                        Font.BOLD,
                        13
                )
        );

        button.setBorder(
                BorderFactory.createEmptyBorder(
                        4,
                        14,
                        4,
                        14
                )
        );

        button.setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
        );
    }

    public void show() {

        frame.setVisible(
                true
        );

        SwingUtilities.invokeLater(
                () -> {
                    addressBar
                            .requestFocusInWindow();

                    addressBar.selectAll();
                }
        );
    }

    /**
     * Public API – loads the given URL
     * in the currently active tab.
     */
    public void load(
            String url
    ) {
        if (activeTab != null) {

            loadInTab(
                    activeTab,
                    url
            );

        } else {

            openNewTab(
                    url
            );
        }
    }

    // ── Inner class: Browser tab ──────────────────────────────────────────

    private class BrowserTab {

        String title;
        String currentUrl;

        String statusText =
                "Ready";

        final List<String> history =
                new ArrayList<>();

        int historyIndex =
                -1;

        boolean navigatingHistory;

        final RenderView renderView;
        final JScrollPane scrollPane;

        final JPanel tabButton;
        final JLabel tabLabel;

        final JButton closeBtn;

        BrowserTab(
                String initialTitle
        ) {
            title =
                    initialTitle;

            renderView =
                    createRenderView();

            scrollPane =
                    createScrollPane();

            tabLabel =
                    createTabLabel();

            closeBtn =
                    createCloseButton();

            tabButton =
                    createTabButton();

            tabButton.add(
                    tabLabel,
                    "growx, wmin 0"
            );

            tabButton.add(
                    closeBtn,
                    "shrink 0"
            );

            tabButton.setPreferredSize(
                    new Dimension(
                            160,
                            32
                    )
            );

            tabButton.setMaximumSize(
                    new Dimension(
                            200,
                            32
                    )
            );
        }

        private RenderView createRenderView() {

            RenderView view =
                    new RenderView(
                            loader
                    );

            view.setOnNavigate(
                    url -> loadInTab(
                            this,
                            url
                    )
            );

            view.setOnFormSubmit(
                    submission -> {

                        Logger.info(
                                "Form submit: {} {}",
                                submission.method(),
                                submission.action()
                        );

                        String method =
                                submission.method() == null
                                        ? "GET"
                                        : submission
                                        .method()
                                        .trim()
                                        .toUpperCase();

                        if ("GET".equals(method)) {

                            String action =
                                    submission.action();

                            if (action == null
                                    || action.isBlank()) {
                                return;
                            }

                            String body =
                                    submission.body();

                            if (body != null
                                    && !body.isBlank()) {

                                action +=
                                        action.contains("?")
                                                ? "&" + body
                                                : "?" + body;
                            }

                            Logger.info(
                                    "Form GET URL: {}",
                                    action
                            );

                            loadInTab(
                                    this,
                                    action
                            );

                            return;
                        }

                        if (!"POST".equals(method)) {

                            Logger.warn(
                                    "Unsupported form method: {}",
                                    submission.method()
                            );

                            return;
                        }

                        String action =
                                submission.action();

                        if (action == null
                                || action.isBlank()) {
                            return;
                        }

                        BrowserTab currentTab =
                                this;

                        currentTab.statusText =
                                "Submitting form …";

                        if (activeTab
                                == currentTab) {

                            statusLabel.setText(
                                    currentTab.statusText
                            );

                            loadingBar.start();
                        }

                        new SwingWorker<
                                RenderView.LoadedPage,
                                Integer
                                >() {

                            @Override
                            protected RenderView.LoadedPage
                            doInBackground()
                                    throws Exception {

                                Document doc =
                                        loader.submitForm(
                                                submission
                                        );

                                publish(40);

                                JsBridge bridge =
                                        new JsBridge(
                                                doc,
                                                loader
                                                        .getHttpManager(),
                                                currentTab
                                                        .renderView
                                                        ::invalidateLayout
                                        );

                                bridge
                                        .executeInlineScripts();

                                publish(65);

                                float viewportWidth =
                                        currentTab
                                                .renderView
                                                .getWidth() > 0
                                                ? currentTab
                                                .renderView
                                                .getWidth()
                                                : 900f;

                                var rootBox =
                                        currentTab
                                                .renderView
                                                .buildLayout(
                                                        doc,
                                                        viewportWidth
                                                );

                                publish(90);

                                return new RenderView.LoadedPage(
                                        doc,
                                        rootBox,
                                        bridge
                                );
                            }

                            @Override
                            protected void process(
                                    List<Integer> chunks
                            ) {
                                if (!chunks.isEmpty()
                                        && activeTab
                                        == currentTab) {

                                    loadingBar
                                            .setProgress(
                                                    chunks.get(
                                                            chunks.size()
                                                                    - 1
                                                    )
                                            );
                                }
                            }

                            @Override
                            protected void done() {

                                try {

                                    RenderView.LoadedPage page =
                                            get();

                                    Document doc =
                                            page.document();

                                    String title =
                                            doc.title().isBlank()
                                                    ? "Untitled"
                                                    : doc.title();

                                    currentTab.title =
                                            title.length() > 28
                                                    ? title.substring(
                                                    0,
                                                    26
                                            ) + "…"
                                                    : title;

                                    currentTab.tabLabel
                                            .setText(
                                                    currentTab.title
                                            );

                                    currentTab.statusText =
                                            "Loaded · "
                                                    + title;

                                    currentTab.renderView
                                            .applyLoadedPage(
                                                    page
                                            );

                                    if (activeTab
                                            == currentTab) {

                                        frame.setTitle(
                                                title
                                                        + " – PeakBrowser4J v2"
                                        );

                                        statusLabel
                                                .setText(
                                                        currentTab
                                                                .statusText
                                                );

                                        loadingBar
                                                .finish();

                                        updateNavigationButtons();
                                    }

                                    Logger.info(
                                            "Rendered form response: {}",
                                            title
                                    );

                                } catch (Exception e) {

                                    currentTab.renderView
                                            .clear();

                                    currentTab.statusText =
                                            "Form error · "
                                                    + e.getMessage();

                                    Logger.error(
                                            e,
                                            "Failed to submit form to {}",
                                            submission.action()
                                    );

                                    if (activeTab
                                            == currentTab) {

                                        statusLabel
                                                .setText(
                                                        currentTab
                                                                .statusText
                                                );

                                        loadingBar.stop();

                                        JOptionPane.showMessageDialog(
                                                frame,
                                                "Form submission failed:\n"
                                                        + e.getMessage(),
                                                "Form Error",
                                                JOptionPane.ERROR_MESSAGE
                                        );
                                    }
                                }
                            }
                        }.execute();
                    });

            return view;
        }

        private JScrollPane createScrollPane() {

            JScrollPane scroll =
                    new JScrollPane(
                            renderView
                    );

            scroll.setBorder(
                    BorderFactory.createEmptyBorder()
            );

            scroll.getVerticalScrollBar()
                    .setUnitIncrement(
                            16
                    );

            return scroll;
        }

        private JLabel createTabLabel() {

            JLabel label =
                    new JLabel(
                            title
                    );

            label.setFont(
                    new Font(
                            "Segoe UI",
                            Font.PLAIN,
                            12
                    )
            );

            label.setForeground(
                    new Color(
                            177,
                            213,
                            255
                    )
            );

            return label;
        }

        private JButton createCloseButton() {

            JButton button =
                    new JButton("×");

            button.setFont(
                    new Font(
                            "Segoe UI",
                            Font.PLAIN,
                            14
                    )
            );

            button.setForeground(
                    new Color(
                            120,
                            125,
                            135
                    )
            );

            button.setFocusable(false);

            button.setContentAreaFilled(
                    false
            );

            button.setBorder(
                    BorderFactory.createEmptyBorder(
                            0,
                            4,
                            0,
                            2
                    )
            );

            button.setCursor(
                    Cursor.getPredefinedCursor(
                            Cursor.HAND_CURSOR
                    )
            );

            button.setPreferredSize(
                    new Dimension(
                            18,
                            18
                    )
            );

            button.addActionListener(
                    e -> closeTab(
                            this
                    )
            );

            return button;
        }

        private JPanel createTabButton() {

            JPanel panel =
                    new JPanel(
                            new MigLayout(
                                    "insets 5 8 5 6, gapx 4, aligny center",
                                    "[grow, fill][]",
                                    "[]"
                            )
                    ) {

                        private boolean hovered;

                        {
                            addMouseListener(
                                    new MouseAdapter() {

                                        @Override
                                        public void mouseEntered(
                                                MouseEvent e
                                        ) {
                                            hovered = true;
                                            repaint();
                                        }

                                        @Override
                                        public void mouseExited(
                                                MouseEvent e
                                        ) {
                                            hovered = false;
                                            repaint();
                                        }

                                        @Override
                                        public void mouseClicked(
                                                MouseEvent e
                                        ) {
                                            if (e.getButton()
                                                    == MouseEvent.BUTTON1) {

                                                switchToTab(
                                                        BrowserTab.this
                                                );

                                            } else if (
                                                    e.getButton()
                                                            == MouseEvent.BUTTON2) {

                                                closeTab(
                                                        BrowserTab.this
                                                );
                                            }
                                        }
                                    }
                            );
                        }

                        @Override
                        protected void paintComponent(
                                Graphics g
                        ) {
                            super.paintComponent(
                                    g
                            );

                            Graphics2D g2 =
                                    (Graphics2D) g.create();

                            g2.setRenderingHint(
                                    RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON
                            );

                            if (activeTab
                                    == BrowserTab.this) {

                                g2.setColor(
                                        TAB_ACTIVE_BG
                                );

                            } else if (hovered) {

                                g2.setColor(
                                        TAB_HOVER_BG
                                );

                            } else {

                                g2.setColor(
                                        TAB_BG
                                );
                            }

                            g2.fillRoundRect(
                                    0,
                                    0,
                                    getWidth(),
                                    getHeight() + 4,
                                    10,
                                    10
                            );

                            if (activeTab
                                    != BrowserTab.this) {

                                g2.setColor(
                                        TAB_BORDER
                                );

                                g2.drawLine(
                                        0,
                                        getHeight() - 1,
                                        getWidth(),
                                        getHeight() - 1
                                );
                            }

                            g2.dispose();
                        }
                    };

            panel.setOpaque(
                    false
            );

            panel.setCursor(
                    Cursor.getPredefinedCursor(
                            Cursor.HAND_CURSOR
                    )
            );

            return panel;
        }

        void setActive(
                boolean active
        ) {
            tabLabel.setFont(
                    new Font(
                            "Segoe UI",
                            active
                                    ? Font.BOLD
                                    : Font.PLAIN,
                            12
                    )
            );

            tabButton.repaint();
        }
    }
}