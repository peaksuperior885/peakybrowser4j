package com.peak885.peakybrowser4jv2.browser.js;

import com.peak885.peakybrowser4jv2.browser.http.HttpManager;
import com.peak885.peakybrowser4jv2.browser.js.processing.JsPreprocessor;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.mozilla.javascript.*;
import org.mozilla.javascript.lc.type.TypeInfo;
import org.mozilla.javascript.lc.type.impl.BasicClassTypeInfo;
import org.tinylog.Logger;

import javax.swing.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class JsBridge {

    /**
     * Rhino's default WrapFactory wraps java.lang.String/Number/Boolean
     * values returned from our Java bridge classes (JsNode, JsLocation,
     * JsFetch responses, etc.) as full NativeJavaObject instances instead
     * of converting them to native JS primitives. When page JavaScript then
     * calls a String method with JS-typed arguments -- e.g.
     * someElement.className.replace(/foo/, "bar") -- Rhino resolves that
     * against java.lang.String's *Java* overloads (replace(CharSequence,
     * CharSequence) vs replace(char,char)) and can't disambiguate a JS
     * regex/string argument pair, throwing an EvaluatorException.
     *
     * Disabling javaPrimitiveWrap makes Strings/Numbers/Booleans cross the
     * bridge as ordinary JS primitives, so JS's own String.prototype methods
     * are used instead.
     */
    private static final class PrimitiveUnwrappingContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            WrapFactory wrapFactory = new WrapFactory() {
                // Rhino 1.9: the Class overload just forwards to the TypeInfo
                // overload. Override the TypeInfo form so ExpandoAware is used.
                @Override
                public Scriptable wrapAsJavaObject(Context cx, Scriptable scope,
                                                   Object javaObject, TypeInfo staticType) {
                    return new ExpandoAwareJavaObject(scope, javaObject, staticType);
                }

                @Override
                public Scriptable wrapAsJavaObject(Context cx, Scriptable scope,
                                                   Object javaObject, Class<?> staticType) {
                    return new ExpandoAwareJavaObject(scope, javaObject, convertType(staticType));
                }
            };
            wrapFactory.setJavaPrimitiveWrap(false);
            cx.setWrapFactory(wrapFactory);
            return cx;
        }
    }

    private static final class ExpandoAwareJavaObject extends NativeJavaObject {
        private final Map<String, Object> expando = new HashMap<>();

        ExpandoAwareJavaObject(Scriptable scope, Object javaObject, TypeInfo staticType) {
            super(scope, javaObject, staticType != null ? staticType : TypeInfo.NONE);
        }

        @Override
        public boolean has(String name, Scriptable start) {
            return expando.containsKey(name) || super.has(name, start);
        }

        @Override
        public Object get(String name, Scriptable start) {
            if (expando.containsKey(name)) {
                return expando.get(name);
            }
            return super.get(name, start);
        }

        @Override
        public void put(String name, Scriptable start, Object value) {
            // Prefer Java bean/field when it exists; otherwise store as expando
            // so scripts can attach arbitrary properties (e.g. document.__gwbp).
            if (super.has(name, start)) {
                try {
                    super.put(name, start, value);
                    return;
                } catch (RuntimeException ignored) {
                    // Fall through to expando
                }
            }
            expando.put(name, value);
        }

        @Override
        public void delete(String name) {
            expando.remove(name);
            try {
                super.delete(name);
            } catch (RuntimeException ignored) {
                // ignore
            }
        }

        @Override
        public Object[] getIds() {
            Object[] javaIds = super.getIds();
            Object[] result = new Object[javaIds.length + expando.size()];
            System.arraycopy(javaIds, 0, result, 0, javaIds.length);
            int i = javaIds.length;
            for (String key : expando.keySet()) {
                result[i++] = key;
            }
            return result;
        }
    }

    private static <T> TypeInfo convertType(Class<T> clazz) {
        if (clazz == null) {
            return TypeInfo.NONE;
        }
        return new BasicClassTypeInfo(clazz);
    }

    static {
        if (!ContextFactory.hasExplicitGlobal()) {
            ContextFactory.initGlobal(new PrimitiveUnwrappingContextFactory());
        }
    }

    private final Document document;
    private final InstallBrowserCompat installBrowserCompat;
    private final HttpManager http;
    private final Runnable layoutInvalidator;
    private final Object javascriptLock = new Object();
    private final ScheduledExecutorService taskExecutor;
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final AtomicLong nextTaskId = new AtomicLong();
    private final AtomicReference<Element> currentScriptElement = new AtomicReference<>();
    private java.util.function.Consumer<String> onNavigate;

    private Scriptable scope;
    private JsLocation location;
    private JsHistory history;
    private JsNode.Ctx nodeCtx;
    private JsDocument documentObject;
    private boolean initialized;

    public JsBridge(Document document, HttpManager http, Runnable layoutInvalidator) {
        this.document = document;
        this.http = http;
        this.layoutInvalidator = layoutInvalidator != null
                ? () -> javax.swing.SwingUtilities.invokeLater(layoutInvalidator)
                : () -> {};
        this.installBrowserCompat = new InstallBrowserCompat();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "PeakyBrowser-JS");
            thread.setDaemon(true);
            return thread;
        };
        this.taskExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public JsBridge(Document document, HttpManager http) {
        this(document, http, null);
    }

    // ---------------------------------------------------------------------
    // Initialization
    // ---------------------------------------------------------------------

    private void initialize() {
        if (initialized) {
            return;
        }

        Context cx = null;
        try {
            cx = ContextFactory.getGlobal().enterContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);

            // This is the ONE real global scope.
            scope = cx.initStandardObjects();

            // Install browser JSON API into the actual browser scope.
            new JsJson().install(cx, scope);

            final Scriptable rhinoScope = scope;

            JsNode.FunctionInvoker invoker = (listener, event) -> {
                if (listener instanceof org.mozilla.javascript.Function jsFn) {
                    Context callCx = null;
                    try {
                        callCx = ContextFactory.getGlobal().enterContext();
                        callCx.setLanguageVersion(Context.VERSION_ES6);
                        callCx.setOptimizationLevel(-1);

                        Object jsEvent = Context.javaToJS(event, rhinoScope);

                        synchronized (javascriptLock) {
                            jsFn.call(
                                    callCx,
                                    rhinoScope,
                                    rhinoScope,
                                    new Object[]{jsEvent}
                            );
                        }

                    } finally {
                        if (callCx != null) {
                            Context.exit();
                        }
                    }

                } else if (listener instanceof Runnable r) {
                    r.run();
                }
            };

            nodeCtx = new JsNode.Ctx(layoutInvalidator, invoker);

            JsDocument jsDocument =
                    new JsDocument(document, nodeCtx, currentScriptElement);

            documentObject = jsDocument;

            JsConsole jsConsole = new JsConsole();
            JsNavigator jsNavigator = new JsNavigator();

            location = new JsLocation(getDocumentUrl(), getDocumentUrl(), targetUrl -> {
                if (onNavigate != null) {
                    SwingUtilities.invokeLater(() -> onNavigate.accept(targetUrl));
                }
            });
            history = new JsHistory(location);

            JsFetch jsFetchHelper =
                    new JsFetch(http, getDocumentUrl());

            putGlobal(cx, "document", jsDocument);
            putGlobal(cx, "console", jsConsole);
            putGlobal(cx, "navigator", jsNavigator);
            putGlobal(cx, "location", createLocationObject(cx));
            putGlobal(cx, "history", history);

            ScriptableObject.putProperty(scope, "window", scope);
            ScriptableObject.putProperty(scope, "self", scope);
            ScriptableObject.putProperty(scope, "top", scope);
            ScriptableObject.putProperty(scope, "parent", scope);

            installBrowserCompat.installBrowserCompatibility(cx, scope);

            // FontFaceSet stub so document.fonts.load(...) does not throw.
            // Must run after Promise polyfill (installPromises). Google's first
            // inline script calls this for webfont preload.
            jsDocument.fontsStub = cx.evaluateString(scope, """
                (function() {
                    function resolved() {
                        return Promise.resolve([]);
                    }
                    return {
                        load: function(font, text) { return resolved(); },
                        check: function(font, text) { return true; },
                        ready: Promise.resolve(),
                        status: 'loaded',
                        size: 0,
                        add: function() {},
                        delete: function() { return false; },
                        clear: function() {},
                        has: function() { return false; },
                        forEach: function() {},
                        values: function() { return [][Symbol.iterator](); },
                        keys: function() { return [][Symbol.iterator](); },
                        entries: function() { return [][Symbol.iterator](); },
                        addEventListener: function() {},
                        removeEventListener: function() {},
                        dispatchEvent: function() { return true; }
                    };
                })()
                """, "document.fonts", 1, null);

            // fetch(), timers, etc.
            installAsyncFunctions(jsFetchHelper);

            initialized = true;

            Logger.info("JavaScript environment initialized");

        } catch (Throwable e) {
            Logger.warn(e, "Failed to initialize JavaScript environment");

        } finally {
            if (cx != null) {
                Context.exit();
            }
        }
    }

    private void putGlobal(Context cx, String name, Object value) {
        ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope));
    }

    // ---------------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------------

    public void execute(String script) {
        if (script == null || script.isBlank()) {
            return;
        }

        initialize();

        if (!initialized || scope == null) {
            Logger.warn("JavaScript environment is unavailable");
            return;
        }

        final String processed = JsPreprocessor.process(script);
        if (processed == null || processed.isBlank()) {
            return;
        }

        logScriptWithLineNumbers(processed);

        Context cx = null;
        try {
            cx = ContextFactory.getGlobal().enterContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);

            synchronized (javascriptLock) {
                Script compiled = cx.compileString(processed, "page-script", 1, null);
                compiled.exec(cx, scope);
            }

        } catch (Throwable e) {
            logScriptError(e, processed);
        } finally {
            if (cx != null) {
                Context.exit();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Real input → DOM events
    // ---------------------------------------------------------------------

    public void dispatchClick(Element element) {
        initialize();
        if (!initialized || element == null || nodeCtx == null) {
            return;
        }
        nodeCtx.wrap(element).dispatchEvent("click");
    }

    public void dispatchKey(String type, String key) {
        initialize();
        if (!initialized || documentObject == null) {
            return;
        }
        documentObject.dispatchEvent(type, key);
    }

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    private void logScriptWithLineNumbers(String script) {
        String[] lines = script.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            Logger.debug("JS {:04d}: {}", i + 1, lines[i]);
        }
    }

    private void logScriptError(Throwable e, String script) {
        String message = e.getMessage();
        Logger.warn("Rhino JavaScript error: {}",
                message == null ? e.getClass().getSimpleName() : message);

        if (e instanceof org.mozilla.javascript.RhinoException rhino) {
            Logger.warn("Rhino source: {}", rhino.sourceName());
            Logger.warn("Rhino line: {}", rhino.lineNumber());
            if (script != null && !script.isBlank()) {
                logContextAroundLine(script, Math.max(1, rhino.lineNumber()));
            }
        }

        Logger.warn(e, "JS execution failed");
    }

    private void logContextAroundLine(String script, int lineNumber) {
        String[] lines = script.split("\\R", -1);
        int start = Math.max(0, lineNumber - 4);
        int end = Math.min(lines.length, lineNumber + 3);

        Logger.warn("JavaScript around failing line:");
        for (int i = start; i < end; i++) {
            String marker = (i + 1 == lineNumber) ? " >>> " : "     ";
            Logger.warn("{}{:04d}: {}", marker, i + 1, lines[i]);
        }
    }

    // ---------------------------------------------------------------------
    // Inline scripts
    // ---------------------------------------------------------------------

    public void executeInlineScripts() {
        initialize();
        if (!initialized) {
            return;
        }

        Elements scripts = document.select("script");
        Logger.info("Executing {} JavaScript block(s)", scripts.size());

        for (Element script : scripts) {
            String type = script.attr("type");
            if (!type.isBlank() && !isJavaScriptType(type)) {
                continue;
            }

            String code;
            String sourceName = "inline script";
            if (script.hasAttr("src")) {
                String src = script.attr("src").trim();
                if (src.isBlank()) {
                    continue;
                }
                try {
                    String resolved = URI.create(getDocumentUrl()).resolve(src).toString();

                    if (resolved.regionMatches(true, 0, "file:", 0, 5)) {
                        Path path = Path.of(URI.create(resolved));

                        code = Files.readString(
                                path,
                                StandardCharsets.UTF_8
                        );

                        sourceName = resolved;

                        Logger.info("[LOCAL] Loaded external script: {}", path);
                    } else {
                        HttpManager.HttpResponse response = http.get(
                                resolved,
                                "text/javascript,application/javascript,text/ecmascript,application/ecmascript,*/*;q=0.8"
                        );

                        if (!response.isSuccessful()) {
                            Logger.warn(
                                    "Skipping external script {}: HTTP {} {}",
                                    resolved,
                                    response.getStatusCode(),
                                    response.getStatusMessage()
                            );
                            continue;
                        }

                        code = response.getBodyAsString();
                        sourceName = resolved;
                    }
                } catch (Exception e) {
                    Logger.warn(e, "Failed to load external script {}", src);
                    continue;
                }
            } else {
                code = script.data();
            }
            if (code == null || code.isBlank()) {
                continue;
            }
            Logger.info("Executing {}", sourceName);
            currentScriptElement.set(script);
            try {
                execute(code);
            } finally {
                currentScriptElement.set(null);
            }
        }
        if (documentObject != null) {
            documentObject.dispatchDOMContentLoaded();
        }
    }

    private void installAsyncFunctions(JsFetch jsFetchHelper) {
        ScriptableObject.putProperty(scope, "setTimeout", timerFunction(false, false));
        ScriptableObject.putProperty(scope, "setInterval", timerFunction(true, false));
        ScriptableObject.putProperty(scope, "requestAnimationFrame", timerFunction(false, true));
        ScriptableObject.putProperty(scope, "clearTimeout", cancelTimerFunction());
        ScriptableObject.putProperty(scope, "clearInterval", cancelTimerFunction());
        ScriptableObject.putProperty(scope, "cancelAnimationFrame", cancelTimerFunction());
        ScriptableObject.putProperty(scope, "fetch", fetchFunction(jsFetchHelper));
    }

    private org.mozilla.javascript.Function timerFunction(boolean repeating, boolean animationFrame) {
        return new org.mozilla.javascript.BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable ignored, Scriptable thisObj, Object[] args) {
                if (args.length == 0 || !(args[0] instanceof org.mozilla.javascript.Function callback)) {
                    return 0;
                }
                long delay = animationFrame ? 16L : delayMillis(args);
                long id = nextTaskId.incrementAndGet();
                Object[] callbackArgs = animationFrame
                        ? new Object[0]
                        : Arrays.copyOfRange(args, 2, args.length);
                Runnable task = animationFrame
                        ? () -> invokeCallback(callback, new Object[] { System.currentTimeMillis() })
                        : () -> invokeCallback(callback, callbackArgs);
                ScheduledFuture<?> future = repeating
                        ? taskExecutor.scheduleAtFixedRate(task, delay, Math.max(1L, delay), TimeUnit.MILLISECONDS)
                        : taskExecutor.schedule(() -> { try { task.run(); } finally { scheduledTasks.remove(id); } }, delay, TimeUnit.MILLISECONDS);
                scheduledTasks.put(id, future);
                return id;
            }
        };
    }

    private org.mozilla.javascript.Function cancelTimerFunction() {
        return new org.mozilla.javascript.BaseFunction() {
            @Override public Object call(Context cx, Scriptable ignored, Scriptable thisObj, Object[] args) {
                if (args.length > 0) {
                    double number = Context.toNumber(args[0]);
                    ScheduledFuture<?> future = Double.isFinite(number)
                            ? scheduledTasks.remove((long) number)
                            : null;
                    if (future != null) future.cancel(false);
                }
                return null;
            }
        };
    }

    private long delayMillis(Object[] args) {
        if (args.length < 2) return 0L;
        double delay = Context.toNumber(args[1]);
        return Double.isFinite(delay) ? Math.max(0L, (long) delay) : 0L;
    }

    private void invokeCallback(org.mozilla.javascript.Function callback, Object[] args) {
        Context cx = null;
        try {
            cx = ContextFactory.getGlobal().enterContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            synchronized (javascriptLock) { callback.call(cx, scope, scope, args); }
        } catch (Throwable e) {
            Logger.warn(e, "Asynchronous JavaScript callback failed");
        } finally { if (cx != null) Context.exit(); }
    }

    private org.mozilla.javascript.Function fetchFunction(JsFetch jsFetch) {
        return new org.mozilla.javascript.BaseFunction() {
            @Override public Object call(Context cx, Scriptable ignored, Scriptable thisObj, Object[] args) {
                if (args.length == 0 || args[0] == null) throw Context.reportRuntimeError("TypeError: Failed to execute 'fetch': at least 1 argument required.");
                String url = Context.toString(args[0]);
                Scriptable deferred = (Scriptable) cx.evaluateString(scope,
                        "(function(){var resolve,reject;var promise=new Promise(function(r,j){resolve=r;reject=j;});return {promise:promise,resolve:resolve,reject:reject};})()",
                        "fetch-promise", 1, null);
                org.mozilla.javascript.Function resolve = (org.mozilla.javascript.Function) ScriptableObject.getProperty(deferred, "resolve");
                org.mozilla.javascript.Function reject = (org.mozilla.javascript.Function) ScriptableObject.getProperty(deferred, "reject");
                taskExecutor.execute(() -> {
                    try { JsFetch.JsResponse response = jsFetch.get(url); resolveFetch(resolve, response); }
                    catch (Exception e) { invokeCallback(reject, new Object[]{"NetworkError: Failed to fetch: " + e.getMessage()}); }
                });
                return ScriptableObject.getProperty(deferred, "promise");
            }
        };
    }

    private void resolveFetch(
            org.mozilla.javascript.Function resolve,
            JsFetch.JsResponse response
    ) {
        taskExecutor.execute(() -> {
            synchronized (javascriptLock) {
                Context cx = null;
                try {
                    cx = ContextFactory.getGlobal().enterContext();
                    cx.setLanguageVersion(Context.VERSION_ES6);
                    cx.setOptimizationLevel(-1);

                    Scriptable responseObject = createResponse(cx, response);

                    Logger.info(
                            "[JS fetch] Resolving Promise: status={}, ok={}",
                            ScriptableObject.getProperty(responseObject, "status"),
                            ScriptableObject.getProperty(responseObject, "ok")
                    );

                    // 1. Resolve the Promise
                    resolve.call(cx, scope, scope, new Object[]{responseObject});

                    // 2. Force Rhino to drain the microtask / Promise job queue
                    //    This is what makes async/await continuations actually run.
                    cx.evaluateString(scope,
                            "(function(){" +
                                    "  var p = Promise.resolve();" +
                                    "  for (var i = 0; i < 20; i++) p = p.then(function(){});" +
                                    "})();",
                            "promise-flush", 1, null);

                } catch (Throwable e) {
                    Logger.warn(e, "Could not resolve fetch promise");
                } finally {
                    if (cx != null) {
                        Context.exit();
                    }
                }
            }
        });
    }

    private Scriptable createResponse(
            Context cx,
            JsFetch.JsResponse response
    ) {
        Object responseCtor =
                ScriptableObject.getProperty(scope, "Response");

        if (!(responseCtor instanceof Function constructor)) {
            throw Context.reportRuntimeError(
                    "Response constructor is unavailable"
            );
        }

        Scriptable responseObject = cx.newObject(scope);

        Object prototype =
                ScriptableObject.getProperty(
                        (Scriptable) responseCtor,
                        "prototype"
                );

        if (prototype instanceof Scriptable prototypeObject) {
            responseObject.setPrototype(prototypeObject);
        }

        Scriptable init = cx.newObject(scope);
        Scriptable headers = cx.newObject(scope);

        for (Map.Entry<String, String> entry
                : response.getHeaders().entrySet()) {

            ScriptableObject.putProperty(
                    headers,
                    entry.getKey(),
                    jsString(cx, entry.getValue())
            );
        }

        ScriptableObject.putProperty(
                responseObject,
                "_body",
                jsString(cx, response.getText())
        );

        ScriptableObject.putProperty(
                responseObject,
                "status",
                response.getStatus()
        );

        ScriptableObject.putProperty(
                responseObject,
                "statusText",
                jsString(cx, response.getStatusText())
        );

        ScriptableObject.putProperty(
                responseObject,
                "url",
                jsString(cx, response.getUrl())
        );

        ScriptableObject.putProperty(
                responseObject,
                "ok",
                response.getStatus() >= 200 &&
                        response.getStatus() < 300
        );

        ScriptableObject.putProperty(
                responseObject,
                "headers",
                headers
        );

        ScriptableObject.putProperty(responseObject, "json", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object body = ScriptableObject.getProperty(thisObj, "_body");
                String bodyStr = Context.toString(body);

                // Create a real Promise that resolves to the parsed JSON
                Scriptable deferred = (Scriptable) cx.evaluateString(scope,
                        "(function(){var resolve,reject;var promise=new Promise(function(r,j){resolve=r;reject=j;});return {promise:promise,resolve:resolve,reject:reject};})()",
                        "response-json-promise", 1, null);

                org.mozilla.javascript.Function resolve =
                        (org.mozilla.javascript.Function) ScriptableObject.getProperty(deferred, "resolve");
                org.mozilla.javascript.Function reject =
                        (org.mozilla.javascript.Function) ScriptableObject.getProperty(deferred, "reject");

                try {
                    Object jsonCtor = ScriptableObject.getProperty(scope, "JSON");
                    if (jsonCtor instanceof Scriptable jsonObject) {
                        Object parseFn = ScriptableObject.getProperty(jsonObject, "parse");
                        if (parseFn instanceof org.mozilla.javascript.Function parse) {
                            Object parsed = parse.call(cx, scope, jsonObject, new Object[]{bodyStr});
                            resolve.call(cx, scope, scope, new Object[]{parsed});
                        } else {
                            reject.call(cx, scope, scope, new Object[]{"JSON.parse is unavailable"});
                        }
                    } else {
                        reject.call(cx, scope, scope, new Object[]{"JSON is unavailable"});
                    }
                } catch (Throwable e) {
                    reject.call(cx, scope, scope, new Object[]{e.getMessage()});
                }

                return ScriptableObject.getProperty(deferred, "promise");
            }
        });

        ScriptableObject.putProperty(responseObject, "text", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object body = ScriptableObject.getProperty(thisObj, "_body");

                Scriptable deferred = (Scriptable) cx.evaluateString(scope,
                        "(function(){var resolve,reject;var promise=new Promise(function(r,j){resolve=r;reject=j;});return {promise:promise,resolve:resolve,reject:reject};})()",
                        "response-text-promise", 1, null);

                org.mozilla.javascript.Function resolve =
                        (org.mozilla.javascript.Function) ScriptableObject.getProperty(deferred, "resolve");

                resolve.call(cx, scope, scope, new Object[]{body});

                return ScriptableObject.getProperty(deferred, "promise");
            }
        });

        return responseObject;
    }

    private boolean isJavaScriptType(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toLowerCase();
        return normalized.equals("text/javascript")
                || normalized.equals("application/javascript")
                || normalized.equals("application/x-javascript")
                || normalized.equals("text/ecmascript")
                || normalized.equals("application/ecmascript")
                || normalized.equals("module");
    }

    // ---------------------------------------------------------------------
    // Console
    // ---------------------------------------------------------------------

    public static final class JsConsole {

        public void log(Object... args)   { print("log", args); }
        public void info(Object... args)  { print("info", args); }
        public void warn(Object... args)  { print("warn", args); }
        public void error(Object... args) { print("error", args); }
        public void debug(Object... args) { print("debug", args); }
        public void trace(Object... args) { print("trace", args); }
        public void dir(Object... args)   { print("dir", args); }

        private void print(String level, Object... args) {
            StringBuilder out = new StringBuilder("[JS console.").append(level).append("] ");
            if (args != null) {
                for (Object arg : args) {
                    out.append(arg == null ? "null" : Context.toString(arg)).append(' ');
                }
            }
            System.out.println(out.toString().trim());
        }
    }

    // ---------------------------------------------------------------------
    // Navigator
    // ---------------------------------------------------------------------

    public static final class JsNavigator {

        public String getUserAgent() {
            return com.peak885.peakybrowser4jv2.browser.http.HttpManager.USER_AGENT;
        }

        public String getAppCodeName() {
            return "Mozilla";
        }

        public String getAppName() {
            return "Netscape";
        }

        public String getAppVersion() {
            return "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 PeakyBrowser/2.0";
        }

        public String getPlatform() {
            return "Win32";
        }

        public String getProduct() {
            return "Gecko";
        }

        public String getProductSub() {
            return "20030107";
        }

        public String getVendor() {
            return "Peak885";
        }

        public String getVendorSub() {
            return "";
        }

        public String getLanguage() {
            return "en-US";
        }

        public String[] getLanguages() {
            return new String[] { "en-US", "en" };
        }

        public boolean isOnLine() {
            return true;
        }

        public boolean isCookieEnabled() {
            return true;
        }

        public boolean getDoNotTrack() {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Location
    // ---------------------------------------------------------------------

    public static final class JsLocation {

        private String href;
        private final String documentUrl;
        private final java.util.function.Consumer<String> navigationCallback;

        public JsLocation(String href) {
            this.href = href == null ? "about:blank" : href;
            this.documentUrl = this.href;
            this.navigationCallback = null;
        }

        public JsLocation(String href, String documentUrl, java.util.function.Consumer<String> navigationCallback) {
            this.href = href == null ? "about:blank" : href;
            this.documentUrl = documentUrl == null ? "about:blank" : documentUrl;
            this.navigationCallback = navigationCallback;
        }

        public String getHref() { return href; }

        public void setHref(String newHref) {
            if (newHref == null) return;
            try {
                this.href = URI.create(documentUrl).resolve(newHref).toString();
            } catch (Exception e) {
                this.href = newHref;
            }

            if (navigationCallback != null) {
                navigationCallback.accept(this.href);
            }
        }

        public void setHrefSilently(String newHref) {
            if (newHref == null) return;

            try {
                this.href = URI.create(documentUrl).resolve(newHref).toString();
            } catch (Exception e) {
                this.href = newHref;
            }
        }

        public String getProtocol() {
            try {
                String scheme = URI.create(href).getScheme();
                return scheme == null ? "" : scheme + ":";
            } catch (Exception ignored) {
                return "";
            }
        }

        public String getHost() {
            try {
                String auth = URI.create(href).getRawAuthority();
                return auth == null ? "" : auth;
            } catch (Exception ignored) {
                return "";
            }
        }

        public String getHostname() {
            try {
                String host = URI.create(href).getHost();
                return host == null ? "" : host;
            } catch (Exception ignored) {
                return "";
            }
        }

        public String getPort() {
            try {
                int port = URI.create(href).getPort();
                return port < 0 ? "" : String.valueOf(port);
            } catch (Exception ignored) {
                return "";
            }
        }

        public String getPathname() {
            try {
                String path = URI.create(href).getRawPath();
                return path == null ? "" : path;
            } catch (Exception ignored) {
                return "";
            }
        }

        public String getSearch() {
            try {
                String query = URI.create(href).getRawQuery();
                return query == null || query.isEmpty() ? "" : "?" + query;
            } catch (Exception ignored) {
                return "";
            }
        }

        public String getHash() {
            try {
                String fragment = URI.create(href).getRawFragment();
                return fragment == null || fragment.isEmpty() ? "" : "#" + fragment;
            } catch (Exception ignored) {
                return "";
            }
        }

        public String getOrigin() {
            try {
                URI uri = URI.create(href);
                if (uri.getScheme() == null || uri.getAuthority() == null) {
                    return "null";
                }
                return uri.getScheme() + "://" + uri.getAuthority();
            } catch (Exception ignored) {
                return "null";
            }
        }

        public void assign(String url) { setHref(url); }
        public void replace(String url) { setHref(url); }
        public void reload() { /* navigation later */ }

        @Override
        public String toString() {
            return href;
        }
    }

    // ---------------------------------------------------------------------
    // History
    // ---------------------------------------------------------------------

    public static final class JsHistory {

        private final JsLocation location;
        private int length = 1;
        private Object state;

        public JsHistory(JsLocation location) {
            this.location = location;
        }

        public int getLength() { return length; }
        public Object getState() { return state; }

        public void replaceState(Object state, String title, Object url) {
            this.state = state;
            if (url != null) {
                location.setHrefSilently(String.valueOf(url));
            }
        }

        public void pushState(Object state, String title, Object url) {
            this.state = state;
            if (url != null) {
                location.setHrefSilently(String.valueOf(url));
            }
            length++;
        }

        public void back() {}
        public void forward() {}
        public void go(int delta) {}
    }

    private Scriptable createLocationObject(Context cx) {
        ScriptableObject jsLocation = new ScriptableObject() {
            @Override
            public String getClassName() {
                return "Location";
            }
        };

        // Real JavaScript getter/setter for window.location.href
        jsLocation.defineProperty(
                "href",
                () -> location.getHref(),
                value -> location.setHref(Context.toString(value)),
                ScriptableObject.PERMANENT
        );

        ScriptableObject.putProperty(
                jsLocation,
                "protocol",
                location.getProtocol()
        );

        ScriptableObject.putProperty(
                jsLocation,
                "host",
                location.getHost()
        );

        ScriptableObject.putProperty(
                jsLocation,
                "hostname",
                location.getHostname()
        );

        ScriptableObject.putProperty(
                jsLocation,
                "port",
                location.getPort()
        );

        ScriptableObject.putProperty(
                jsLocation,
                "pathname",
                location.getPathname()
        );

        ScriptableObject.putProperty(
                jsLocation,
                "search",
                location.getSearch()
        );

        ScriptableObject.putProperty(
                jsLocation,
                "hash",
                location.getHash()
        );

        ScriptableObject.putProperty(
                jsLocation,
                "origin",
                location.getOrigin()
        );

        return jsLocation;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private String getDocumentUrl() {
        String baseUri = document.baseUri();
        if (baseUri == null || baseUri.isBlank()) {
            return "about:blank";
        }
        return baseUri;
    }

    private Object jsString(Context cx, String value) {
        if (value == null) {
            return null;
        }

        Object stringCtor = ScriptableObject.getProperty(scope, "String");

        if (!(stringCtor instanceof Function constructor)) {
            throw Context.reportRuntimeError("String constructor is unavailable");
        }

        return constructor.call(
                cx,
                scope,
                scope,
                new Object[]{value}
        );
    }

    public boolean dispatchSubmit(Element form) {
        if (form == null || nodeCtx == null) {
            return false;
        }

        JsNode formNode = nodeCtx.wrap(form);

        if (formNode == null) {
            return false;
        }

        formNode.dispatchEvent("submit");

        return true;
    }

    public void setOnNavigate(java.util.function.Consumer<String> listener) {
        this.onNavigate = listener;
    }
}