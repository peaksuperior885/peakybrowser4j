package com.peak885.peakybrowser4j.browser.engine;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonObject;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.reader.CSSReader;
import com.peak885.peakybrowser4j.browser.*;
import com.peak885.peakybrowser4j.browser.css.CssLoader;
import com.peak885.peakybrowser4j.browser.css.StyleEngine;
import com.peak885.peakybrowser4j.browser.css.UserAgentStyles;
import com.peak885.peakybrowser4j.browser.image.ImageSystem;
import com.peak885.peakybrowser4j.browser.media.VideoEngine;
import com.peak885.peakybrowser4j.browser.util.DomDumper;
import com.sun.net.httpserver.HttpServer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL;
import org.tinylog.Logger;

import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class BrowserEngine {

    private long window;
    private long vg;

    private final PageLoader loader = new PageLoader();
    private final HtmlParser parser = new HtmlParser(loader);
    private final LayoutEngine layoutEngine = new LayoutEngine();
    private final StyleEngine styleEngine = new StyleEngine();

    private Renderer renderer;

    private final Object lock = new Object();

    private Node page;
    private volatile boolean needsRelayout = false;

    private float scrollY = 0f;
    private float loadProgress = 0f;

    private double mouseX = 0;
    private double mouseY = 0;

    private boolean draggingScrollbar = false;

    private String initialUrl;

    private final NVGColor tmpColor = NVGColor.create();
    private long handCursor;
    private long arrowCursor;

    private String getScriptsFromNode(Node root) {
        return parser.getCollectedScripts();
    }

    public void run(String initialUrl) {
        this.initialUrl = initialUrl;
        init();
        loop();
        cleanup();
    }

    private void init() {
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW init failed");
        }

        window = glfwCreateWindow(1200, 800, "PeakyBrowser4J", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Window creation failed");
        }

        handCursor = glfwCreateStandardCursor(GLFW_HAND_CURSOR);
        arrowCursor = glfwCreateStandardCursor(GLFW_ARROW_CURSOR);

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);

        GL.createCapabilities();

        vg = NanoVGGL3.nvgCreate(
                NanoVGGL3.NVG_ANTIALIAS |
                        NanoVGGL3.NVG_STENCIL_STROKES
        );

        if (vg == NULL) {
            throw new RuntimeException("Failed to create NanoVG context");
        }

        FontManager.loadFonts(vg);

        ImageSystem.init(loader, vg);

        renderer = new Renderer(vg, loader, this.window);

        setupCallbacks();

        try {
            startDevToolsServer();
        } catch (Exception e) {
            Logger.error("Failed to start DevTools server: {}", e.getMessage());
        }

        new Thread(() -> loadPage(initialUrl), "PageLoader").start();
    }

    private void setupCallbacks() {
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> needsRelayout = true);

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button != GLFW_MOUSE_BUTTON_LEFT) return;

            if (action == GLFW_PRESS) {
                // Start scrollbar drag if clicking on the right edge
                draggingScrollbar = (mouseX > 1200 - 12);
            }
            else if (action == GLFW_RELEASE) {
                draggingScrollbar = false;
                handleMouseClick(mouseX, mouseY + scrollY);
            }
        });

        glfwSetCursorPosCallback(window, (win, x, y) -> {
            mouseX = x;
            mouseY = y;

            if (draggingScrollbar) {
                int[] h = new int[1];
                glfwGetWindowSize(win, null, h);
                synchronized (lock) {
                    if (page != null) {
                        float ratio = (float) y / h[0];
                        scrollY = ratio * (page.height - h[0]);
                        scrollY = Math.max(0, Math.min(scrollY, page.height - h[0]));
                    }
                }
            }
        });

        glfwSetScrollCallback(window, (win, xoff, yoff) -> {
            scrollY -= (float) yoff * 50;
            scrollY = Math.max(0, scrollY);
        });
    }

    private void handleMouseClick(double x, double y) {
        synchronized (lock) {
            if (page == null) return;

            float docX = (float) x;
            float docY = (float) (y + scrollY);

            Node clickedNode = findNodeAt(page, docX, docY);
            if (clickedNode == null) return;

            if ("video".equals(clickedNode.tag)) {
                renderer.handleVideoClick(clickedNode, (float) x, (float) y, scrollY);
            } else if (clickedNode.href != null) {
                new Thread(() -> loadPage(clickedNode.href), "PageLoader").start();
            }
        }
    }

    private Node findNodeAt(Node node, float x, float y) {
        if (x < node.x || x > node.x + node.width ||
                y < node.y || y > node.y + node.height) {
            return null;
        }

        // Search children first (front-most)
        for (int i = node.children.size() - 1; i >= 0; i--) {
            Node found = findNodeAt(node.children.get(i), x, y);
            if (found != null) {
                return found;
            }
        }

        if ("video".equals(node.tag) || node.isVideoNode)
            return node;

        if ("a".equals(node.tag))
            return node;

        return null;
    }

    public void loadPage(String url) {
        loadProgress = 0f;
        Logger.info("Loading: {}", url);

        String content = loader.load(url);
        loadProgress = 0.25f;

        Node newPage;

        try {
            if (content != null && content.trim().startsWith("{")) {
                Jankson jankson = Jankson.builder().build();
                JsonObject json = jankson.load(content);

                newPage = new Node();
                JsonToNodeConverter.populateNodes(json, newPage);

            } else {
                newPage = parser.parse(content);
            }
        } catch (Exception e) {
            Logger.error("Page parse failed: {}", e.getMessage());
            e.printStackTrace();
            return;
        }

        if (newPage == null) {
            Logger.error("Parser returned null page!");
            return;
        }

        loadProgress = 0.5f;

        CssLoader cssLoader = new CssLoader();
        CascadingStyleSheet sheet = null;

        try {
            String uaCss = getUserAgentCss();
            if (uaCss == null || uaCss.isEmpty()) {
                Logger.warn("UA CSS empty, using fallback");
                uaCss = UserAgentStyles.CSS;
            }

            String pageCss = parser.getCollectedCss();
            if (pageCss == null) pageCss = "";

            String combinedCss = uaCss + "\n" + pageCss;

            Logger.info("CSS Pipeline: UA={} chars, Page={} chars, Total={} chars",
                    uaCss.length(), pageCss.length(), combinedCss.length());

            sheet = cssLoader.load(combinedCss);

            if (sheet != null && sheet.getStyleRuleCount() > 0) {
                Logger.info("CSS Pipeline: Parsed {} rules from stylesheet",
                        sheet.getStyleRuleCount());
            } else {
                Logger.warn("CSS stylesheet empty, using blank sheet");
                sheet = new CascadingStyleSheet();
            }

        } catch (Exception e) {
            Logger.error("CSS pipeline failed: {}", e.getMessage());
            e.printStackTrace();
            sheet = new CascadingStyleSheet();
        }

        loadProgress = 0.6f;

        try {
            styleEngine.applyStyles(newPage, sheet);
            Logger.info("Styles applied to root: bg={}, color={}, fontSize={}",
                    Arrays.toString(newPage.computedStyle.backgroundColor),
                    Arrays.toString(newPage.computedStyle.textColor),
                    newPage.computedStyle.fontSize);
        } catch (Exception e) {
            Logger.error("Style application failed: {}", e.getMessage());
            e.printStackTrace();
        }

        loadProgress = 0.7f;

        try {
            int[] w = new int[1];
            int[] h = new int[1];
            glfwGetFramebufferSize(window, w, h);

            float layoutWidth = Math.max(800f, w[0] > 0 ? w[0] - 80 : 1200f - 80);

            Logger.info("Computing layout: window={}x{}, layout width={}",
                    w[0], h[0], layoutWidth);

            this.layoutEngine.computeLayout(newPage, 8, 8, layoutWidth);

            Logger.info("Layout complete: page width={}, height={}, children={}",
                    newPage.width, newPage.height, newPage.children.size());

            if (newPage.width <= 0 || newPage.height <= 0) {
                Logger.error("CRITICAL: Root node has invalid dimensions: {}x{}",
                        newPage.width, newPage.height);
            }
        } catch (Exception e) {
            Logger.error("Layout computation failed: {}", e.getMessage());
            e.printStackTrace();
        }

        loadProgress = 0.85f;

        try {
            String script = getScriptsFromNode(newPage);
            if (!script.isEmpty()) {
                parser.executeScripts(newPage, script, url);
            }
        } catch (Exception e) {
            Logger.error("JS Execution failed: {}", e.getMessage());
        }

        try {
            DomDumper.dump(newPage, "debug_dom.txt");
        } catch (Exception e) {
            Logger.error("DOM dump failed: {}", e.getMessage());
        }

        synchronized (lock) {
            page = newPage;
            scrollY = 0f;
            needsRelayout = false;
        }

        loadProgress = 1.0f;
        Logger.info("Page loaded successfully");
    }

    private String getUserAgentCss() {
        try (var stream = getClass().getResourceAsStream("/css/user-agent.css")) {
            if (stream == null) {
                Logger.warn("User-agent stylesheet not found in resources");
                return null;
            }
            return new String(stream.readAllBytes());
        } catch (Exception e) {
            Logger.error("Failed to load UA stylesheet: {}", e.getMessage());
            return null;
        }
    }

    private void loop() {
        glfwMakeContextCurrent(window);

        int WINDOW_W = 1200;
        int WINDOW_H = 800;

        while (!glfwWindowShouldClose(window)) {
            // Only get size ONCE per frame
            int[] w = new int[1], h = new int[1];
            glfwGetFramebufferSize(window, w, h);

            if (w[0] <= 0) w[0] = WINDOW_W;
            if (h[0] <= 0) h[0] = WINDOW_H;

            glViewport(0, 0, w[0], h[0]);
            glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

            if (System.currentTimeMillis() % 1000 < 20) {
                long memoryUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
                Logger.info("Current Heap Usage: {} MB", memoryUsed);
            }

            float currentLayoutWidth = Math.max(800f, w[0] > 0 ? w[0] - 80 : 1200f - 80);

            synchronized (lock) {
                if (page != null) {
                    if (needsRelayout || page.isDirty) {
                        System.out.println("BEFORE layout: root width=" + page.width);
                        System.out.println("currentLayoutWidth=" + currentLayoutWidth);
                        System.out.println("w[0]=" + w[0] + ", h[0]=" + h[0]);

                        this.layoutEngine.computeLayout(page, 0, 0, currentLayoutWidth);

                        System.out.println("AFTER layout: root width=" + page.width);
                        needsRelayout = false;
                        page.isDirty = false;

                        logVideoNodes(page);
                    }
                }
            }

            renderer.hoveringLink = false;
            nvgBeginFrame(vg, w[0], h[0], 1);

            ImageSystem.processGpuUploads();
            ImageSystem.cleanup(vg, 30_000);

            if (page != null) {
                renderer.scrollY = scrollY;
                renderer.render(page, (float) mouseX, (float) mouseY, w[0], h[0]);

                boolean isInteractive = renderer.isMouseOverInteractive(page, (float)mouseX, (float)mouseY, scrollY);
                glfwSetCursor(window, isInteractive ? handCursor : arrowCursor);
            }

            if (loadProgress < 1f) {
                tmpColor.r(0.3f).g(0.7f).b(1f).a(1f);
                nvgBeginPath(vg);
                nvgRect(vg, 0, 0, w[0] * loadProgress, 3);
                nvgFillColor(vg, tmpColor);
                nvgFill(vg);
            }

            drawScrollbar(w[0], h[0], page);
            nvgEndFrame(vg);

            glfwSetCursor(window, renderer.hoveringLink ? handCursor : arrowCursor);

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    private void logVideoNodes(Node node) {
        if (node.isVideoNode) {
            System.out.println("VIDEO NODE: " + node.width + "x" + node.height +
                    ", pos=(" + node.x + "," + node.y + ")");
        }
        node.children.forEach(this::logVideoNodes);
    }

    private void drawScrollbar(int w, int h, Node snapshot) {

        if (snapshot == null || snapshot.height <= h) return;

        float trackW = 12f;
        float thumbH = (h / snapshot.height) * h;
        float thumbY = (scrollY / snapshot.height) * h;

        tmpColor.r(0.15f).g(0.15f).b(0.15f).a(1f);

        nvgBeginPath(vg);
        nvgRect(vg, w - trackW, 0, trackW, h);
        nvgFillColor(vg, tmpColor);
        nvgFill(vg);

        tmpColor.r(0.4f).g(0.4f).b(0.4f).a(0.8f);

        nvgBeginPath(vg);
        nvgRect(vg, w - trackW, thumbY, trackW, thumbH);
        nvgFillColor(vg, tmpColor);
        nvgFill(vg);
    }

    private void startDevToolsServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8888), 0);

        // 1. Endpoint for the DOM data
        server.createContext("/api/dom", exchange -> {
            String response;
            synchronized(lock) {
                response = (page != null) ? page.toJson().toJson() : "{}";
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // Important for local CORS
            exchange.sendResponseHeaders(200, response.length());
            try (var os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // 2. Endpoint for the DevTools UI (The HTML file)
        server.createContext("/", exchange -> {
            String html = """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { background: #1e1e1e; color: #d4d4d4; font-family: sans-serif; padding: 20px; }
                    .node { margin-left: 20px; border-left: 1px solid #444; padding-left: 10px; cursor: pointer; }
                    .node:hover { background: #2a2a2a; }
                    .tag { color: #569cd6; font-weight: bold; }
                    .text { color: #ce9178; }
                </style>
            </head>
            <body>
                <h1>PeakyBrowser4J DevTools</h1>
                <button onclick="refreshDom()">Refresh Tree</button>
                <div id="dom-tree"></div>
                <script>
                    async function refreshDom() {
                        const res = await fetch('/api/dom');
                        const data = await res.json();
                        const container = document.getElementById('dom-tree');
                        container.innerHTML = '';
                        container.appendChild(renderNode(data));
                    }
                    function renderNode(node) {
                        const div = document.createElement('div');
                        div.className = 'node';
                        div.innerHTML = `<span class="tag">&lt;${node.tag}&gt;</span> <span class="text">${node.text || ''}</span>`;
                        if (node.children) node.children.forEach(c => div.appendChild(renderNode(c)));
                        return div;
                    }
                    refreshDom();
                </script>
            </body>
            </html>
            """;
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, html.length());
            try (var os = exchange.getResponseBody()) {
                os.write(html.getBytes());
            }
        });

        server.start();
        Logger.info("DevTools available at http://localhost:8888");
    }

    private void cleanup() {

        ImageSystem.clearAll(vg);

        if (renderer != null && renderer.activeVideoPlayers != null) {
            renderer.activeVideoPlayers.forEach((node, videoTexture) -> {
                videoTexture.dispose(vg);
            });
        }

        ImageSystem.clearAll(vg);

        if (handCursor != NULL) {
            glfwDestroyCursor(handCursor);
        }

        if (arrowCursor != NULL) {
            glfwDestroyCursor(arrowCursor);
        }

        // 2. Then: delete NanoVG context
        if (vg != NULL) {
            NanoVGGL3.nvgDelete(vg);
        }

        // 3. Then: destroy window
        if (window != NULL) {
            glfwDestroyWindow(window);
        }

        glfwTerminate();
        System.exit(0);
    }
}