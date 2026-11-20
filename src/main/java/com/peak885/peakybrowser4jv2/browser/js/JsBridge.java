package com.peak885.peakybrowser4jv2.browser.js;

import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import com.peak885.peakybrowser4jv2.browser.DiagnosticPaths;
import com.peak885.peakybrowser4jv2.browser.http.HttpManager;
import com.peak885.peakybrowser4jv2.browser.image.ImageData;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

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
        // Rhino wraps a Java object anew every time it crosses into JS, so for DOM-like host
        // objects the expando map lives on the host (see ExpandoHost); otherwise a property a
        // script sets through one wrapper (Closure's el[listenerMapKey]) is invisible through
        // the next wrapper of the same node.
        private final Map<String, Object> expando;

        ExpandoAwareJavaObject(Scriptable scope, Object javaObject, TypeInfo staticType) {
            super(scope, javaObject, staticType != null ? staticType : TypeInfo.NONE);
            this.expando = javaObject instanceof ExpandoHost host
                    ? host.expandoMap()
                    : java.util.Collections.synchronizedMap(new HashMap<>());

            // Point the prototype chain at the appropriate DOM constructor prototype so
            // `node instanceof Element` / `instanceof Node` / `instanceof HTMLElement`
            // succeed. reCAPTCHA rejects containers that fail these checks.
            if (scope != null && javaObject instanceof JsNode jsNode) {
                Scriptable proto = findDomPrototype(scope, "Element");
                if (proto == null) proto = findDomPrototype(scope, "Node");
                if (proto != null) setPrototype(proto);
                // nodeType is served from get() as a primitive double — see below.
            } else if (scope != null && javaObject instanceof JsDocument) {
                Scriptable proto = findDomPrototype(scope, "Document");
                if (proto != null) setPrototype(proto);
            }
        }

        /** Return a real JS number for nodeType so {@code nodeType == 1} works. */
        private Object nodeTypeValue() {
            Object javaObj = javaObject;
            if (javaObj instanceof JsNode n) {
                return Double.valueOf(n.getNodeType());
            }
            if (javaObj instanceof JsDocument) {
                return Double.valueOf(9); // DOCUMENT_NODE
            }
            return Double.valueOf(0);
        }

        private static Scriptable findDomPrototype(Scriptable scope, String ctorName) {
            try {
                Object ctor = ScriptableObject.getProperty(scope, ctorName);
                if (ctor instanceof Scriptable ctorObj) {
                    Object proto = ScriptableObject.getProperty(ctorObj, "prototype");
                    if (proto instanceof Scriptable p) return p;
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        @Override
        public boolean has(String name, Scriptable start) {
            if ("nodeType".equals(name) && (javaObject instanceof JsNode || javaObject instanceof JsDocument)) {
                return true;
            }
            return expando.containsKey(name) || super.has(name, start);
        }

        @Override
        public Object get(String name, Scriptable start) {
            // Always return a primitive JS number for nodeType. Critical for
            // reCAPTCHA: `m[7](…, el) && el.nodeType == 1` must succeed.
            if ("nodeType".equals(name) && (javaObject instanceof JsNode || javaObject instanceof JsDocument)) {
                return nodeTypeValue();
            }
            synchronized (expando) {
                if (expando.containsKey(name)) {
                    return expando.get(name);
                }
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
            String[] keys;
            synchronized (expando) {
                keys = expando.keySet().toArray(new String[0]);
            }
            Object[] result = new Object[javaIds.length + keys.length];
            System.arraycopy(javaIds, 0, result, 0, javaIds.length);
            System.arraycopy(keys, 0, result, javaIds.length, keys.length);
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
    // Loop guard: a page that keeps navigating itself to the same URL (e.g. a bot
    // gate we can't pass) must not hammer the server forever.
    private static String lastScriptNavTarget;
    private static int lastScriptNavCount;

    private static synchronized boolean allowScriptNavigation(String target) {
        if (target != null && target.equals(lastScriptNavTarget)) {
            lastScriptNavCount++;
        } else {
            lastScriptNavTarget = target;
            lastScriptNavCount = 1;
        }
        if (lastScriptNavCount > 3) {
            Logger.warn("[JS-NAV] refusing repeated script navigation to {} (loop guard)", target);
            return false;
        }
        return true;
    }

    private final HttpManager http;
    private final Runnable layoutInvalidator;
    private final Object javascriptLock = new Object();
    private final ScheduledExecutorService taskExecutor;
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final AtomicLong nextTaskId = new AtomicLong();
    private final AtomicReference<Element> currentScriptElement = new AtomicReference<>();
    private final AtomicInteger pendingDynamicScripts = new AtomicInteger();
    private java.util.function.Consumer<String> onNavigate;

    private Scriptable scope;
    private JsLocation location;
    private JsHistory history;
    private JsNode.Ctx nodeCtx;
    private JsDocument documentObject;
    private boolean initialized;
    private volatile boolean closed;

    private final BrowserLoader browserLoader;

    // Processed source of every named script this page executed, so an error reported
    // against recaptcha__en.js line 1544 is looked up in THAT script - not in whichever
    // script was running (e.g. a small inline init that merely called into the bundle).
    private final Map<String, String> executedSources = new ConcurrentHashMap<>();
    private final java.util.Set<String> dumpedFailingSources = ConcurrentHashMap.newKeySet();
    private volatile boolean parserFinished;
    private volatile boolean loadEventDispatched;

    public JsBridge(Document document, HttpManager http, Runnable layoutInvalidator) {
        this.document = document;
        this.http = http;
        this.browserLoader = new BrowserLoader(http);
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

            // Inside initialize(), where other global properties are registered:
            ScriptableObject.putProperty(scope, "__nativeImageFetch", new BaseFunction() {
                @Override
                public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                    if (args.length < 3) return Undefined.instance;
                    String url = Context.toString(args[0]);
                    Function onSuccess = (Function) args[1];
                    Function onError = (Function) args[2];

                    taskExecutor.execute(() -> {
                        try {
                            ImageData imageData = browserLoader.loadImage(url);
                            java.awt.image.BufferedImage bufImg = imageData.firstFrame();

                            Context callCx = ContextFactory.getGlobal().enterContext();
                            try {
                                synchronized (javascriptLock) {
                                    onSuccess.call(callCx, scope, scope, new Object[]{
                                            Context.javaToJS(bufImg, scope),
                                            bufImg.getWidth(),
                                            bufImg.getHeight()
                                    });
                                }
                            } finally {
                                Context.exit();
                            }
                        } catch (Exception e) {
                            Context callCx = ContextFactory.getGlobal().enterContext();
                            try {
                                synchronized (javascriptLock) {
                                    onError.call(callCx, scope, scope, new Object[]{e.getMessage()});
                                }
                            } finally {
                                Context.exit();
                            }
                        }
                    });
                    return Undefined.instance;
                }
            });

            nodeCtx = new JsNode.Ctx(layoutInvalidator, invoker);
            nodeCtx.setGlobalScope(scope);
            nodeCtx.setScriptInsertedListener(this::onScriptInserted);

            JsDocument jsDocument =
                    new JsDocument(document, nodeCtx, currentScriptElement);

            documentObject = jsDocument;
            nodeCtx.setDocument(jsDocument);
            jsDocument.setCookieAccess(http.getClient().cookieJar(), getDocumentUrl());

            JsConsole jsConsole = new JsConsole(getDocumentUrl());
            JsNavigator jsNavigator = new JsNavigator();

            location = new JsLocation(getDocumentUrl(), getDocumentUrl(), targetUrl -> {
                if (closed) {
                    return; // page already replaced; a stale timer must not navigate the tab
                }
                Logger.info("[JS-NAV] script requested navigation -> {}", targetUrl);
                if (!allowScriptNavigation(targetUrl)) {
                    return;
                }
                if (onNavigate != null) {
                    SwingUtilities.invokeLater(() -> onNavigate.accept(targetUrl));
                }
            });
            history = new JsHistory(location);

            JsFetch jsFetchHelper =
                    new JsFetch(http, getDocumentUrl());

            putGlobal(cx, "document", jsDocument);
            putGlobal(cx, "console", jsConsole);
            RecaptchaClosureCompat.installEvalPatch(cx, scope);

            // Google's challenge catches errors thrown by its generated eval source and
            // serializes the stack into sg_ss, so execute() never sees the exception and its
            // normal Rhino source-window logger cannot help. Hook eval so that, for big
            // generated sources only, we (1) dump the source to
            // %TEMP%/peakybrowser-debug/eval-N.js, and (2) when the eval throws a
            // "Cannot set/read property X of undefined" TypeError, log the source around
            // the matching `.X` sites (preferring the line named in the stack) and rethrow
            // the original error. Diagnostic only - behaviour is unchanged except for the
            // one listener-cleanup guard kept from the earlier investigation.
            ScriptableObject.putProperty(scope, "__peakyDumpEval", new BaseFunction() {
                @Override
                public Object call(Context c, Scriptable s2, Scriptable thisObj, Object[] args) {
                    try {
                        if (args.length >= 2) {
                            Path dir = DiagnosticPaths.directory();
                            Files.createDirectories(dir);
                            String source = Context.toString(args[1]);
                            Path file = dir.resolve("eval-" + Context.toString(args[0]) + ".js");
                            Files.writeString(file, source, StandardCharsets.UTF_8);
                            Logger.info("[EVAL-DIAG] dumped generated eval source ({} chars) -> {}",
                                    source.length(), file.toAbsolutePath());
                        }
                    } catch (Throwable t) {
                        Logger.warn(t, "[EVAL-DIAG] could not dump eval source");
                    }
                    return Undefined.instance;
                }
            });

            ScriptableObject.putProperty(scope, "getComputedStyle", new BaseFunction() {
                @Override
                public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                    Scriptable style = cx.newObject(scope);
                    ScriptableObject.putProperty(style, "getPropertyValue", new BaseFunction() {
                        @Override
                        public Object call(Context cx2, Scriptable s, Scriptable t, Object[] a) {
                            return "";          // or real values later
                        }
                    });

                    ScriptableObject.putProperty(style, "display", "block");
                    ScriptableObject.putProperty(style, "visibility", "visible");
                    ScriptableObject.putProperty(style, "opacity", "1");
                    ScriptableObject.putProperty(style, "width", "0px");
                    ScriptableObject.putProperty(style, "height", "0px");
                    return style;
                }
            });

            cx.evaluateString(scope, """
                    (function () {
                      var nativeEval = eval;
                      var evalCount = 0;

                      function lineOf(source, index) {
                        var n = 1;
                        for (var i = 0; i < index; i++) {
                          var c = source.charCodeAt(i);
                          if (c === 10 || (c === 13 && source.charCodeAt(i + 1) !== 10)) n++;
                        }
                        return n;
                      }

                      function diagnose(error, source, id) {
                        var msg = String((error && error.message) || error);
                        var name = String((error && error.name) || 'Error');
                        var stack = String((error && error.stack) || '');
                        console.error('[RECAPTCHA-EVAL] generated eval #' + id
                          + ' threw ' + name + ': ' + msg
                          + (stack ? '\\n' + stack.slice(0, 1800) : ''));
                        var m = /Cannot (set|read) propert(?:y|ies) "?([A-Za-z_$][\\w$]*)"? of (undefined|null)/.exec(msg);
                        if (!m) return;
                        var isSet = m[1] === 'set';
                        var needle = '.' + m[2];
                        var sm = /\\(eval\\):(\\d+)/.exec(String(error && error.stack));
                        var targetLine = sm ? parseInt(sm[1], 10) : -1;
                        var hits = [], pos = 0;
                        while (hits.length < 300 && (pos = source.indexOf(needle, pos)) !== -1) {
                          var after = pos + needle.length;
                          if (/[\\w$]/.test(source.charAt(after))) { pos = after; continue; }
                          var j = after;
                          while (/\\s/.test(source.charAt(j))) j++;
                          var assign = source.charAt(j) === '=' && source.charAt(j + 1) !== '=';
                          if (isSet && !assign) { pos = after; continue; }
                          hits.push({ pos: pos, after: after, assign: assign });
                          pos = after;
                        }
                        var shown = 0, fallback = [];
                        for (var h = 0; h < hits.length && shown < 6; h++) {
                          var hit = hits[h];
                          var line = lineOf(source, hit.pos);
                          if (targetLine > 0 && line !== targetLine) { if (fallback.length < 3) fallback.push(hit); continue; }
                          shown++;
                          report(source, hit, line);
                        }
                        if (!shown) {
                          console.warn('[EVAL-DIAG] no ' + needle + ' site on stack line ' + targetLine + '; first candidates:');
                          for (var f = 0; f < fallback.length; f++) report(source, fallback[f], lineOf(source, fallback[f].pos));
                        }
                      }

                      function report(source, hit, line) {
                        var col = hit.pos - Math.max(source.lastIndexOf('\\n', hit.pos), source.lastIndexOf('\\r', hit.pos)) - 1;
                        console.warn('[EVAL-DIAG] site at line ' + line + ', col ' + col + ': '
                          + source.slice(Math.max(0, hit.pos - 220), hit.pos)
                          + '<<' + source.slice(hit.pos, hit.after) + '>>'
                          + source.slice(hit.after, hit.after + 160));
                      }

                      eval = function (source) {
                        if (typeof source !== 'string' || source.length < 20000) {
                          return (0, nativeEval)(source);
                        }
                        var id = ++evalCount;
                        try { if (typeof __peakyDumpEval === 'function') __peakyDumpEval(id, source); } catch (e) {}
                        source = __peakyPatchRecaptchaEval(source);
                        try {
                          return (0, nativeEval)(source);
                        } catch (e) {
                          try { diagnose(e, source, id); } catch (ignored) {}
                          throw e;
                        }
                      };
                    })();
                    """, "peaky-eval-diagnostic", 1, null);
            putGlobal(cx, "navigator", jsNavigator);
            putGlobal(cx, "location", createLocationObject(cx));
            putGlobal(cx, "history", history);

            // window.location only had href/host/... before: scripts calling
            // location.replace()/assign()/reload() (e.g. Google's JS challenge,
            // right after it solves) threw "not a function". All of these just
            // go through the href setter, which fires the navigation callback.
            cx.evaluateString(scope, """
                (function () {
                  var loc = location;
                  var go = function (u) { loc.href = String(u); };
                  loc.replace = go;
                  loc.assign = go;
                  loc.reload = function () { loc.href = loc.href; };
                  loc.toString = function () { return loc.href; };
                })();
                """, "location-methods", 1, null);

            ScriptableObject.putProperty(scope, "window", scope);
            ScriptableObject.putProperty(scope, "self", scope);
            ScriptableObject.putProperty(scope, "top", scope);
            ScriptableObject.putProperty(scope, "parent", scope);

            installBrowserCompat.installBrowserCompatibility(cx, scope);

            // DOM host constructors + Symbol.hasInstance so that
            // `node instanceof Element` / `instanceof Node` succeed for our
            // Rhino-wrapped JsNode objects. reCAPTCHA throws
            // "The bind parameter must be an element or id" without this.
            cx.evaluateString(scope, """
                (function (g) {
                  function isHostNode(x) {
                    try {
                      return x != null
                          && typeof x.nodeType === 'number'
                          && (typeof x.getAttribute === 'function'
                              || typeof x.appendChild === 'function'
                              || typeof x.tagName === 'string');
                    } catch (e) { return false; }
                  }
                  function installCtor(name, test) {
                    if (typeof g[name] !== 'function') {
                      g[name] = function () {
                        throw new TypeError('Illegal constructor');
                      };
                    }
                    if (typeof Symbol !== 'undefined' && Symbol.hasInstance) {
                      try {
                        Object.defineProperty(g[name], Symbol.hasInstance, {
                          configurable: true,
                          value: function (instance) { return test(instance); }
                        });
                      } catch (e) {}
                    }
                  }
                  installCtor('Node', isHostNode);
                  installCtor('Element', function (x) {
                    return isHostNode(x) && x.nodeType === 1;
                  });
                  installCtor('HTMLElement', function (x) {
                    return isHostNode(x) && x.nodeType === 1;
                  });
                  installCtor('HTMLDivElement', function (x) {
                    return isHostNode(x) && x.nodeType === 1
                        && String(x.tagName || '').toUpperCase() === 'DIV';
                  });
                  installCtor('Document', function (x) {
                    try {
                      return x != null && typeof x.getElementById === 'function'
                          && typeof x.createElement === 'function';
                    } catch (e) { return false; }
                  });
                })(typeof globalThis !== 'undefined' ? globalThis : this);
                """, "dom-host-instanceof", 1, null);

            // ------------------------------------------------------------------
            // EventTarget polyfill – required by reCAPTCHA (and many other
            // challenge scripts).  Without addEventListener / attachEvent on
            // window, document and common constructors the script throws:
            //   Error: addEventListener and attachEvent are unavailable.
            // This must run AFTER InstallBrowserCompat and AFTER the Promise
            // polyfill so the methods are visible as ordinary JS properties.
            // ------------------------------------------------------------------
            cx.evaluateString(scope, """
                (function () {
                  function ensureEventTarget(target) {
                    if (!target || (typeof target !== 'object' && typeof target !== 'function')) return;
                    if (typeof target.addEventListener !== 'function') {
                      target.addEventListener = function (type, listener, options) {
                        if (!listener) return;
                        var key = '__peakyListeners_' + String(type);
                        var list = this[key] || (this[key] = []);
                        if (list.indexOf(listener) < 0) list.push(listener);
                      };
                    }
                    if (typeof globalThis.Worker === 'undefined') {
                            globalThis.Worker = function(stringUrl) {
                                this.url = stringUrl;
                                this.onmessage = null;
                            };
                            globalThis.Worker.prototype.postMessage = function(msg) {
                                // Mock implementation or fallback behavior for reCAPTCHA background tasks
                            };
                            globalThis.Worker.prototype.terminate = function() {};
                        }
                        if (typeof globalThis.btoa === 'undefined') {
                                globalThis.btoa = function (input) {
                                    var str = String(input);
                                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                                    var output = '';
                                    for (var block = 0, charCode, i = 0, map = chars;
                                         str.charAt(i | 0) || (map = '=', i % 1);
                                         output += map.charAt(63 & (block >> (8 - (i % 1) * 8)))) {
                                        charCode = str.charCodeAt(i += 3/4);
                                        if (charCode > 0xFF) {
                                            throw new Error("'btoa' failed: The string to be encoded contains characters outside of the Latin1 range.");
                                        }
                                        block = (block << 8) | charCode;
                                    }
                                    return output;
                                };
                            }
                    
                            if (typeof globalThis.atob === 'undefined') {
                                globalThis.atob = function (input) {
                                    var str = String(input).replace(/=+$/, '');
                                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                                    if (str.length % 4 == 1) {
                                        throw new Error("'atob' failed: The string to be decoded is not correctly encoded.");
                                    }
                                    var output = '';
                                    for (var bc = 0, bs = 0, buffer, i = 0;
                                         buffer = str.charAt(i++);
                                         ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer,
                                             bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                                        buffer = chars.indexOf(buffer);
                                    }
                                    return output;
                                };
                            }
                    if (typeof target.removeEventListener !== 'function') {
                      target.removeEventListener = function (type, listener, options) {
                        var list = this['__peakyListeners_' + String(type)];
                        if (list) { var i = list.indexOf(listener); if (i >= 0) list.splice(i, 1); }
                      };
                    }
                    if (typeof target.attachEvent !== 'function') {
                      target.attachEvent = function (type, listener) {
                        var t = (type && String(type).indexOf('on') === 0)
                              ? String(type).slice(2) : type;
                        this.addEventListener(t, listener, false);
                      };
                    }
                    if (typeof target.detachEvent !== 'function') {
                      target.detachEvent = function (type, listener) {
                        var t = (type && String(type).indexOf('on') === 0)
                              ? String(type).slice(2) : type;
                        this.removeEventListener(t, listener, false);
                      };
                    }
                    if (typeof target.dispatchEvent !== 'function') {
                      target.dispatchEvent = function (evt) {
                        if (!evt || !evt.type) return true;
                        var type = String(evt.type), list = this['__peakyListeners_' + type];
                        if (!evt.target) evt.target = this;
                        evt.currentTarget = this;
                        if (list) list.slice().forEach(function (fn) {
                          try { if (typeof fn === 'function') fn.call(target, evt);
                               else if (fn && typeof fn.handleEvent === 'function') fn.handleEvent(evt); }
                          catch (e) { try { console.warn('Event listener failed', e); } catch (_) {} }
                        });
                        var handler = this['on' + type];
                        if (typeof handler === 'function') {
                          try { handler.call(this, evt); }
                          catch (e) { try { console.warn('Event handler failed', e); } catch (_) {} }
                        }
                        return !evt.defaultPrevented;
                      };
                    }
                  }

                  // Global aliases
                  ensureEventTarget(window);
                  ensureEventTarget(self);
                  ensureEventTarget(top);
                  ensureEventTarget(parent);

                  // Our bind compatibility wrapper can be used by Closure as an
                  // event target. Give those callable host wrappers per-instance
                  // listener storage through the normal Function prototype chain.
                  if (typeof Function === 'function' && Function.prototype) {
                    ensureEventTarget(Function.prototype);
                  }

                  // document
                  if (typeof document !== 'undefined') {
                    ensureEventTarget(document);
                  }

                  // Common constructors that reCAPTCHA / challenge scripts
                  // may create instances of and then call addEventListener on.
                  if (typeof Image === 'function') {
                    try {
                      var imgProto = Image.prototype;
                      if (imgProto) ensureEventTarget(imgProto);
                    } catch (e) {}
                  }
                  if (typeof XMLHttpRequest === 'function') {
                    try {
                      var xhrProto = XMLHttpRequest.prototype;
                      if (xhrProto) ensureEventTarget(xhrProto);
                    } catch (e) {}
                  }
                })();
                """, "event-target-polyfill", 1, null);

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

            // Make navigator.userAgentData.getHighEntropyValues return a real
            // Promise (Google / bot-detection scripts always call .then() on it).
            // The Java side already supplies the data map; we just wrap it.
            cx.evaluateString(scope, """
                (function() {
                    try {
                        var nav = navigator;
                        if (!nav || !nav.userAgentData) return;

                        var uad = nav.userAgentData;
                        var original = uad.getHighEntropyValues;

                        if (typeof original === 'function') {
                            uad.getHighEntropyValues = function(hints) {
                                var self = this;
                                return new Promise(function(resolve, reject) {
                                    try {
                                        var values = original.call(self, hints);
                                        // If the Java side already returned a thenable, use it.
                                        if (values && typeof values.then === 'function') {
                                            values.then(resolve, reject);
                                        } else {
                                            resolve(values);
                                        }
                                    } catch (e) {
                                        reject(e);
                                    }
                                });
                            };
                        }

                        // Also ensure brands / mobile / platform are plain data
                        // (some detectors call Object.keys / JSON.stringify).
                        if (typeof uad.toJSON !== 'function') {
                            uad.toJSON = function() {
                                return {
                                    brands: uad.brands,
                                    mobile: uad.mobile,
                                    platform: uad.platform
                                };
                            };
                        }
                    } catch (e) {
                        if (typeof console !== 'undefined' && console.warn) {
                            console.warn('[compat] userAgentData Promise shim failed', e);
                        }
                    }
                })();
                """, "navigator.userAgentData-promise-shim", 1, null);

            // fetch(), timers, etc.
            installAsyncFunctions(jsFetchHelper);

            // Java-implemented globals (setTimeout, fetch, ...) are BaseFunction objects
            // that don't inherit from Function.prototype, so they have no bind/call/apply.
            // Wrap them as ordinary JS functions rather than polluting Object.prototype.
            cx.evaluateString(scope, """
                (function (g) {
                  var names = Object.getOwnPropertyNames(g);
                  for (var i = 0; i < names.length; i++) {
                    var name = names[i], fn;
                    try { fn = g[name]; } catch (e) { continue; }
                    if (typeof fn !== 'function' || typeof fn.bind === 'function') continue;
                    (function (name, nativeFn) {
                      var wrapper = function () {
                        return Function.prototype.apply.call(nativeFn, g, arguments);
                      };
                      try { g[name] = wrapper; } catch (e) {}
                    })(name, fn);
                  }
                })(typeof globalThis !== 'undefined' ? globalThis : this);
                """, "native-function-wrap", 1, null);

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
        execute(script, null);
    }

    private void execute(String script, String scriptUrl) {
        if (script == null || script.isBlank()) {
            return;
        }

        initialize();

        if (!initialized || scope == null) {
            Logger.warn("JavaScript environment is unavailable");
            return;
        }

        final String processed;

        if (shouldSkipPreprocessing(scriptUrl)) {
            processed = RecaptchaClosureCompat.prepareBundle(script, this::installJsPolyfillsAndHelpers);
            Logger.info("[JS] Skipping general preprocessing for: {}", scriptUrl);
        } else {
            processed = JsPreprocessor.process(script);
        }

        if (processed == null || processed.isBlank()) {
            return;
        }

        logScriptWithLineNumbers(processed);
        if (scriptUrl != null) {
            executedSources.put(scriptUrl, processed);
        }

        Context cx = null;
        try {
            cx = ContextFactory.getGlobal().enterContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);

            synchronized (javascriptLock) {
                Script compiled = cx.compileString(
                        processed,
                        scriptUrl != null ? scriptUrl : "page-script",
                        1,
                        null
                );

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

    private void logScriptError(Throwable e, String runningScript) {
        String message = e.getMessage();
        Logger.warn("Rhino JavaScript error: {}",
                message == null ? e.getClass().getSimpleName() : message);

        if (e instanceof org.mozilla.javascript.RhinoException rhino) {
            String sourceName = rhino.sourceName();
            int line = Math.max(1, rhino.lineNumber());
            Logger.warn("Rhino source: {}", sourceName);
            Logger.warn("Rhino line: {}", rhino.lineNumber());

            // The failing function may live in a different script than the one that was
            // running when it was called, so prefer the script the error names.
            String source = sourceName != null ? executedSources.get(sourceName) : null;
            if (source == null) {
                source = runningScript;
            }
            if (source != null && !source.isBlank()) {
                dumpFailingScript(sourceName, source);
                logContextAroundLine(source, line, failingIdentifier(message));
            }
        }

        Logger.warn(e, "JS execution failed");
    }

    /** Pull the identifier out of "X is not a function" / "Cannot read property \"X\"" messages. */
    private static String failingIdentifier(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = Pattern.compile("([A-Za-z_$][\\w$]*) is not a (?:function|constructor)").matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("Cannot (?:read|set) propert(?:y|ies) \"?([A-Za-z_$][\\w$]*)\"?").matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /** Write the script that actually failed to %TEMP%/peakybrowser-debug so it can be inspected/shared. */
    private void dumpFailingScript(String sourceName, String source) {
        String key = sourceName == null ? "page-script" : sourceName;
        if (!dumpedFailingSources.add(key)) {
            return;
        }
        try {
            String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
            if (safe.length() > 80) {
                safe = safe.substring(safe.length() - 80);
            }
            Path dir = DiagnosticPaths.directory();
            Files.createDirectories(dir);
            Path file = dir.resolve("failing-" + safe + ".js");
            Files.writeString(file, source, StandardCharsets.UTF_8);
            Logger.warn("[JS-DIAG] dumped failing script ({} chars) -> {}", source.length(), file.toAbsolutePath());
        } catch (Throwable t) {
            Logger.warn(t, "[JS-DIAG] could not dump failing script");
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private void logContextAroundLine(String script, int lineNumber, String identifier) {
        String[] lines = script.split("\\R", -1);

        Logger.warn("JavaScript around failing line {} (script has {} lines, {} chars):",
                lineNumber, lines.length, script.length());

        if (lineNumber <= lines.length) {
            int start = Math.max(0, lineNumber - 3);
            int end = Math.min(lines.length, lineNumber + 2);
            for (int i = start; i < end; i++) {
                String marker = (i + 1 == lineNumber) ? " >>> " : "     ";
                String text = lines[i].length() > 1500 ? lines[i].substring(0, 1500) + "...(cut)" : lines[i];
                Logger.warn("{}{}: {}", marker, String.format("%04d", i + 1), text);
            }
        } else {
            Logger.warn("Reported line {} is outside this script.", lineNumber);
        }

        if (identifier == null) {
            return;
        }

        // Where is the failing identifier DEFINED, and where is it used on the failing line?
        int definitions = 0;
        int usages = 0;
        int from = 0;
        while (definitions + usages < 40) {
            int index = script.indexOf(identifier, from);
            if (index < 0) {
                break;
            }
            from = index + identifier.length();
            boolean startOk = index == 0 || !isIdentChar(script.charAt(index - 1));
            boolean endOk = from >= script.length() || !isIdentChar(script.charAt(from));
            if (!startOk || !endOk) {
                continue;
            }

            int j = from;
            while (j < script.length() && Character.isWhitespace(script.charAt(j))) {
                j++;
            }
            boolean assigned = j < script.length() && script.charAt(j) == '='
                    && (j + 1 >= script.length() || script.charAt(j + 1) != '=');
            boolean declared = index >= 9 && script.startsWith("function ", index - 9);
            boolean varDecl = index >= 4 && script.startsWith("var ", index - 4);

            int line = 1;
            for (int i = 0; i < index; i++) {
                if (script.charAt(i) == '\n') {
                    line++;
                }
            }

            boolean isDefinition = assigned || declared || varDecl;
            if (isDefinition && definitions < 8) {
                definitions++;
                Logger.warn("[{} DEFINITION {}] line={}: {}", identifier, definitions, line,
                        script.substring(Math.max(0, index - 120), Math.min(script.length(), index + 260)));
            } else if (!isDefinition && line == lineNumber && usages < 4) {
                usages++;
                Logger.warn("[{} USE {}] line={}: {}", identifier, usages, line,
                        script.substring(Math.max(0, index - 200), Math.min(script.length(), index + 120)));
            }
        }

        if (definitions == 0) {
            Logger.warn("[{}] has NO assignment/declaration anywhere in this script - it must come from the "
                    + "environment (a missing global/API) or from another script.", identifier);
        }
    }

    // ---------------------------------------------------------------------
    // Inline scripts
    // ---------------------------------------------------------------------

    // ---------------------------------------------------------------------
    // Dynamically inserted <script> elements
    // ---------------------------------------------------------------------

    private final java.util.concurrent.ExecutorService scriptLoader =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "PeakyBrowser-ScriptLoader");
                t.setDaemon(true);
                return t;
            });

    /**
     * A script element was attached to the live document after load. Inline
     * scripts run right away; external ones are fetched on a worker thread and
     * run when they arrive (like an async script), then fire load/error.
     */
    private void onScriptInserted(Element script) {
        String type = script.attr("type");
        if (!type.isBlank() && !isJavaScriptType(type)) {
            return;
        }
        if (script.hasAttr("data-peaky-script-started")) {
            return;
        }
        script.attr("data-peaky-script-started", "1");

        if (!script.hasAttr("src")) {
            String inline = script.data();
            if (inline != null && !inline.isBlank()) {
                Logger.info("[DYN-SCRIPT] executing inserted inline script ({} chars)", inline.length());
                currentScriptElement.set(script);
                try {
                    execute(inline);
                } finally {
                    currentScriptElement.set(null);
                }
            }
            return;
        }

        final String src = script.attr("src").trim();
        if (src.isBlank()) {
            return;
        }

        final String resolved;
        try {
            resolved = URI.create(getDocumentUrl()).resolve(src).toString();
        } catch (Exception e) {
            Logger.warn(e, "[DYN-SCRIPT] bad src {}", src);
            fireScriptEvent(script, "error");
            return;
        }

        Logger.info("[DYN-SCRIPT] loading {}", resolved);
        pendingDynamicScripts.incrementAndGet();
        scriptLoader.execute(() -> {
            try {
                HttpManager.HttpResponse response = http.get(
                        resolved,
                        "text/javascript,application/javascript,text/ecmascript,application/ecmascript,*/*;q=0.8"
                );
                if (!response.isSuccessful()) {
                    Logger.warn("[DYN-SCRIPT] HTTP {} for {}", response.getStatusCode(), resolved);
                    fireScriptEvent(script, "error");
                    return;
                }
                String code = response.getBodyAsString();
                Logger.info("[DYN-SCRIPT] executing {} ({} chars)", resolved, code == null ? 0 : code.length());
                currentScriptElement.set(script);
                try {
                    execute(code, resolved);
                } finally {
                    currentScriptElement.set(null);
                }
                Logger.info("[DYN-SCRIPT] finished {}", resolved);
                fireScriptEvent(script, "load");
            } catch (Throwable t) {
                Logger.warn(t, "[DYN-SCRIPT] failed {}", resolved);
                fireScriptEvent(script, "error");
            } finally {
                if (pendingDynamicScripts.decrementAndGet() < 0) {
                    pendingDynamicScripts.set(0);
                }
                maybeDispatchPageLoad();
            }
        });
    }

    private void fireScriptEvent(Element script, String type) {
        try {
            synchronized (javascriptLock) {
                if (nodeCtx != null) {
                    nodeCtx.wrap(script).dispatchEvent(type);
                }
            }
        } catch (Throwable t) {
            Logger.warn(t, "[DYN-SCRIPT] could not dispatch {} event", type);
        }
    }

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
                execute(code, sourceName);
            } finally {
                currentScriptElement.set(null);
            }
        }
        if (documentObject != null) {
            documentObject.setReadyState("interactive");
            documentObject.dispatchDOMContentLoaded();
            parserFinished = true;
            maybeDispatchPageLoad();
        }
    }

    /** Fire window/document load once parsing and inserted external scripts finish. */
    private void maybeDispatchPageLoad() {
        if (!parserFinished || pendingDynamicScripts.get() != 0 || loadEventDispatched || closed) return;
        synchronized (javascriptLock) {
            if (!parserFinished || pendingDynamicScripts.get() != 0 || loadEventDispatched || closed) return;
            loadEventDispatched = true;
            if (documentObject != null) {
                documentObject.setReadyState("complete");
                documentObject.dispatchEvent("load", null);
            }
            dispatchWindowEvent("load");
            Logger.info("[JS-LIFECYCLE] document readyState=complete; dispatched window load");
        }
    }

    private void dispatchWindowEvent(String type) {
        Context cx = null;
        try {
            cx = ContextFactory.getGlobal().enterContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            ScriptableObject.putProperty(scope, "__peakyLifecycleEventType", type);
            cx.evaluateString(scope,
                    "window.dispatchEvent({type:String(__peakyLifecycleEventType), target:window});",
                    "window-lifecycle-event", 1, null);
        } catch (Throwable t) {
            Logger.warn(t, "[JS-LIFECYCLE] could not dispatch window {} event", type);
        } finally {
            if (scope != null) ScriptableObject.deleteProperty(scope, "__peakyLifecycleEventType");
            if (cx != null) Context.exit();
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
        ScriptableObject.putProperty(scope, "__nativeXhrSend", nativeXhrSendFunction());
    }

    private org.mozilla.javascript.Function timerFunction(boolean repeating, boolean animationFrame) {
        return new org.mozilla.javascript.BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable ignored, Scriptable thisObj, Object[] args) {
                if (args.length == 0 || !(args[0] instanceof org.mozilla.javascript.Function callback)) {
                    return 0;
                }
                if (closed || taskExecutor.isShutdown()) {
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


    /**
     * Native transport for the XMLHttpRequest polyfill installed by
     * {@link InstallBrowserCompat}. Signature from JS:
     * <pre>
     *   __nativeXhrSend(method, url, headersObj, body, onSuccess, onError)
     * </pre>
     * onSuccess(status, statusText, responseText, responseURL, responseHeadersObj)
     * onError(message)
     */
    private org.mozilla.javascript.Function nativeXhrSendFunction() {
        return new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable ignored, Scriptable thisObj, Object[] args) {
                if (args.length < 6) {
                    throw Context.reportRuntimeError(
                            "TypeError: __nativeXhrSend requires 6 arguments");
                }

                final String method = Context.toString(args[0]);
                final String urlArg = Context.toString(args[1]);
                final Object headersArg = args[2];
                final Object bodyArg = args[3];
                final Object onSuccessArg = args[4];
                final Object onErrorArg = args[5];

                if (!(onSuccessArg instanceof Function) || !(onErrorArg instanceof Function)) {
                    throw Context.reportRuntimeError(
                            "TypeError: __nativeXhrSend callbacks must be functions");
                }

                final Function onSuccess = (Function) onSuccessArg;
                final Function onError = (Function) onErrorArg;

                final Map<String, String> requestHeaders = new LinkedHashMap<>();
                if (headersArg instanceof Scriptable headersObj) {
                    Object[] ids = headersObj.getIds();
                    for (Object id : ids) {
                        if (id instanceof String name) {
                            Object value = ScriptableObject.getProperty(headersObj, name);
                            if (value != null && value != Scriptable.NOT_FOUND) {
                                requestHeaders.put(name, Context.toString(value));
                            }
                        }
                    }
                }

                final String body = (bodyArg == null || bodyArg == Undefined.instance)
                        ? null
                        : Context.toString(bodyArg);

                if (closed || taskExecutor.isShutdown()) {
                    return Undefined.instance;
                }
                taskExecutor.execute(() -> {
                    try {
                        String resolvedUrl;
                        try {
                            resolvedUrl = URI.create(getDocumentUrl()).resolve(urlArg).toString();
                        } catch (Exception e) {
                            resolvedUrl = urlArg;
                        }

                        Request.Builder builder = new Request.Builder().url(resolvedUrl);

                        for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                            try {
                                builder.header(entry.getKey(), entry.getValue());
                            } catch (IllegalArgumentException ignoredHeader) {
                                // Skip invalid header names/values
                            }
                        }

                        String upper = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
                        if ("POST".equals(upper) || "PUT".equals(upper) || "PATCH".equals(upper)) {
                            String contentType = requestHeaders.get("content-type");
                            if (contentType == null) {
                                contentType = requestHeaders.get("Content-Type");
                            }
                            MediaType mediaType = contentType != null
                                    ? MediaType.parse(contentType)
                                    : MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");
                            RequestBody requestBody = RequestBody.create(
                                    body == null ? "" : body,
                                    mediaType
                            );
                            builder.method(upper, requestBody);
                        } else if ("HEAD".equals(upper) || "DELETE".equals(upper) || "OPTIONS".equals(upper)) {
                            builder.method(upper, null);
                        } else {
                            builder.get();
                        }

                        HttpManager.HttpResponse response = http.execute(builder.build());

                        final int status = response.getStatusCode();
                        final String statusText = response.getStatusMessage() != null
                                ? response.getStatusMessage()
                                : "";
                        final String responseText = response.getBodyAsString() != null
                                ? response.getBodyAsString()
                                : "";
                        final String responseURL = response.getFinalUrl() != null
                                ? response.getFinalUrl()
                                : resolvedUrl;
                        final Map<String, String> responseHeaders = response.getHeaders() != null
                                ? response.getHeaders()
                                : Map.of();

                        invokeXhrSuccess(onSuccess, status, statusText, responseText, responseURL, responseHeaders);
                    } catch (Throwable e) {
                        Logger.warn(e, "[XHR] request failed: {} {}", method, urlArg);
                        invokeCallback(onError, new Object[]{
                                e.getMessage() != null ? e.getMessage() : "NetworkError"
                        });
                    }
                });

                return Undefined.instance;
            }
        };
    }

    private void invokeXhrSuccess(
            Function onSuccess,
            int status,
            String statusText,
            String responseText,
            String responseURL,
            Map<String, String> responseHeaders
    ) {
        Context cx = null;
        try {
            cx = ContextFactory.getGlobal().enterContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            synchronized (javascriptLock) {
                Scriptable headersObj = cx.newObject(scope);
                for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                    String name = entry.getKey() == null
                            ? ""
                            : entry.getKey().toLowerCase(Locale.ROOT);
                    ScriptableObject.putProperty(
                            headersObj,
                            name,
                            jsString(cx, entry.getValue())
                    );
                }

                onSuccess.call(cx, scope, scope, new Object[]{
                        status,
                        jsString(cx, statusText),
                        jsString(cx, responseText),
                        jsString(cx, responseURL),
                        headersObj
                });
            }
        } catch (Throwable e) {
            Logger.warn(e, "Asynchronous XHR success callback failed");
        } finally {
            if (cx != null) {
                Context.exit();
            }
        }
    }


    private org.mozilla.javascript.Function fetchFunction(JsFetch jsFetch) {
        return new org.mozilla.javascript.BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable ignored, Scriptable thisObj, Object[] args) {
                if (args.length == 0 || isMissingJsValue(args[0])) {
                    throw Context.reportRuntimeError(
                            "TypeError: Failed to execute 'fetch': at least 1 argument required.");
                }

                // ---- parse input (URL or Request-like object) ----
                String url;
                String method = "GET";
                Map<String, String> headers = new LinkedHashMap<>();
                String body = null;

                Object input = args[0];

                if (input instanceof Scriptable inputObj
                        && ScriptableObject.hasProperty(inputObj, "url")) {

                    // fetch(Request)
                    Object requestUrl = ScriptableObject.getProperty(inputObj, "url");
                    url = Context.toString(requestUrl);

                    Object m = ScriptableObject.getProperty(inputObj, "method");
                    if (!isMissingJsValue(m)) {
                        method = Context.toString(m);
                    }

                    Object h = ScriptableObject.getProperty(inputObj, "headers");
                    if (!isMissingJsValue(h)) {
                        extractHeaders(h, headers);
                    }

                    Object b = ScriptableObject.getProperty(inputObj, "body");
                    if (!isMissingJsValue(b)) {
                        body = Context.toString(b);
                    }

                } else {
                    url = Context.toString(input);
                }

                // ---- parse init options (second argument) ----
                if (args.length > 1 && args[1] instanceof Scriptable init) {

                    Object m = ScriptableObject.getProperty(init, "method");
                    if (!isMissingJsValue(m)) {
                        method = Context.toString(m);
                    }

                    Object h = ScriptableObject.getProperty(init, "headers");
                    if (!isMissingJsValue(h)) {
                        extractHeaders(h, headers);
                    }

                    Object b = ScriptableObject.getProperty(init, "body");
                    if (!isMissingJsValue(b)) {
                        body = Context.toString(b);
                    }
                }

                final String fUrl = url;
                final String fMethod = method;
                final Map<String, String> fHeaders = headers;
                final String fBody = body;

                Logger.info(
                        "[JS fetch] CALL method={} url={} headers={} bodyLen={}",
                        fMethod,
                        DiagnosticPaths.safeUrl(fUrl),
                        fHeaders.size(),
                        fBody == null ? 0 : fBody.length()
                );

                // ---- create Promise ----
                Scriptable deferred = (Scriptable) cx.evaluateString(
                        scope,
                        "(function(){var resolve,reject;var promise=new Promise(function(r,j){resolve=r;reject=j;});return {promise:promise,resolve:resolve,reject:reject};})()",
                        "fetch-promise",
                        1,
                        null
                );

                org.mozilla.javascript.Function resolve =
                        (org.mozilla.javascript.Function)
                                ScriptableObject.getProperty(deferred, "resolve");

                org.mozilla.javascript.Function reject =
                        (org.mozilla.javascript.Function)
                                ScriptableObject.getProperty(deferred, "reject");

                // ---- execute HTTP request asynchronously ----
                taskExecutor.execute(() -> {
                    try {
                        JsFetch.JsResponse response =
                                jsFetch.request(fMethod, fUrl, fHeaders, fBody);

                        resolveFetch(resolve, response);

                    } catch (Exception e) {
                        Logger.warn(e, "[JS fetch] network error for {}",
                                DiagnosticPaths.safeUrl(fUrl));

                        invokeCallback(reject, new Object[]{
                                "NetworkError: Failed to fetch: " + e.getMessage()
                        });
                    }
                });

                return ScriptableObject.getProperty(deferred, "promise");
            }
        };
    }

    private static boolean isMissingJsValue(Object value) {
        return value == null
                || value == Scriptable.NOT_FOUND
                || value == Undefined.instance;
    }

    /**
     * Pull headers out of either a plain object { "Name": "value" }
     * or a Headers-like object that has forEach / entries.
     */
    private void extractHeaders(Object headersObj, Map<String, String> out) {
        if (headersObj == null || headersObj == Undefined.instance) {
            return;
        }
        if (!(headersObj instanceof Scriptable h)) {
            return;
        }

        // Plain object: enumerate own keys
        Object[] ids = ScriptableObject.getPropertyIds(h);
        if (ids != null) {
            for (Object id : ids) {
                String name = Context.toString(id);
                // Skip functions / internal props
                if (name.startsWith("_") || name.equals("forEach")
                        || name.equals("get") || name.equals("set")
                        || name.equals("has") || name.equals("entries")
                        || name.equals("keys") || name.equals("values")) {
                    continue;
                }
                Object value = ScriptableObject.getProperty(h, name);
                if (value != null && value != Undefined.instance
                        && !(value instanceof Function)) {
                    out.put(name, Context.toString(value));
                }
            }
        }
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

        private final String documentLabel;

        public JsConsole(String documentUrl) {
            this.documentLabel = safeDocumentLabel(documentUrl);
        }

        public void log(Object... args)   { print("log", args); }
        public void info(Object... args)  { print("info", args); }
        public void warn(Object... args)  { print("warn", args); }
        public void error(Object... args) { print("error", args); }
        public void debug(Object... args) { print("debug", args); }
        public void trace(Object... args) { print("trace", args); }
        public void dir(Object... args)   { print("dir", args); }

        private void print(String level, Object... args) {
            StringBuilder out = new StringBuilder();
            if (args != null) {
                for (Object arg : args) {
                    if (!out.isEmpty()) out.append(' ');
                    try {
                        out.append(arg == null ? "null" : Context.toString(arg));
                    } catch (RuntimeException conversionFailure) {
                        out.append("[unprintable ")
                                .append(arg == null ? "null" : arg.getClass().getSimpleName())
                                .append(']');
                    }
                }
            }
            String message = redactSensitiveContent(out.toString());
            String prefix = "[JS console." + level + "] [{}] {}";
            switch (level) {
                case "error" -> Logger.error(prefix, documentLabel, message);
                case "warn" -> Logger.warn(prefix, documentLabel, message);
                case "debug", "trace", "dir" -> Logger.debug(prefix, documentLabel, message);
                default -> Logger.info(prefix, documentLabel, message);
            }
        }

        private static String safeDocumentLabel(String documentUrl) {
            try {
                URI uri = URI.create(documentUrl);
                return uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
            } catch (RuntimeException e) {
                return "unknown document";
            }
        }

        private static String redactSensitiveContent(String message) {
            String safe = message.replaceAll("(?i)data:[^\\s\\\"'<>]+", "data:<redacted>");
            safe = safe.replaceAll("(?i)\\bcookie\\s*[=:]\\s*.*", "cookie=<redacted>");
            safe = safe.replaceAll("(?i)(https?://[^\\s?]+)\\?[^\\s]+", "$1?<query-redacted>");
            if (safe.length() > 4096) {
                safe = safe.substring(0, 4096) + "… [truncated]";
            }
            return safe;
        }
    }

    // ---------------------------------------------------------------------
    // Navigator
    // ---------------------------------------------------------------------

    /**
     * Navigator surface used by Google / Cloudflare / bot-detection scripts.
     * Includes a real-looking {@code userAgentData} (UA-CH) implementation so
     * client-hint checks and high-entropy brand probes succeed.
     */
    public static final class JsNavigator {

        // Keep these in sync with the HTTP Client Hints you send and with
        // HttpManager.USER_AGENT (Chrome 120 desktop profile).
        private static final String CHROME_MAJOR = "120";
        private static final String CHROME_FULL  = "120.0.0.0";
        private static final String PLATFORM     = "Windows";
        private static final String PLATFORM_VER = "15.0.0"; // Win11-style
        private static final String ARCH         = "x86";
        private static final String BITNESS      = "64";
        private static final String MODEL        = "";
        private static final boolean MOBILE      = false;

        public String getUserAgent() {
            return com.peak885.peakybrowser4jv2.browser.http.HttpManager.USER_AGENT;
        }

        /** navigator.sendBeacon(url[, data]) - logged, not actually sent. */
        public boolean sendBeacon(Object... args) {
            Logger.info("[BEACON] {}", args != null && args.length > 0 ? String.valueOf(args[0]) : "(no url)");
            return true;
        }

        public String getAppCodeName() {
            return "Mozilla";
        }

        public String getAppName() {
            return "Netscape";
        }

        public String getAppVersion() {
            // Classic reduced form (matches what Chrome reports for appVersion)
            return "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/" + CHROME_FULL + " Safari/537.36";
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
            // Real Chrome returns "Google Inc."
            return "Google Inc.";
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

        /** Chrome returns null for doNotTrack in modern versions. */
        public Object getDoNotTrack() {
            return null;
        }

        /** Critical: automation detectors check this. Must be false. */
        public boolean getWebdriver() {
            return false;
        }

        public int getHardwareConcurrency() {
            return 8;
        }

        public int getDeviceMemory() {
            return 8;
        }

        public int getMaxTouchPoints() {
            return 0;
        }

        public boolean isPdfViewerEnabled() {
            return true;
        }

        /**
         * Empty plugin / mimeType lists are what modern Chrome reports
         * (plugins were removed). Returning empty arrays avoids outdated
         * fingerprint signals.
         */
        public Object[] getPlugins() {
            return new Object[0];
        }

        public Object[] getMimeTypes() {
            return new Object[0];
        }

        /**
         * User-Agent Client Hints API (navigator.userAgentData).
         * This is what Google's challenge scripts and many bot detectors
         * actually inspect after the initial HTML arrives.
         */
        public JsUserAgentData getUserAgentData() {
            return new JsUserAgentData();
        }

        // -----------------------------------------------------------------
        // navigator.userAgentData
        // -----------------------------------------------------------------

        public static final class JsUserAgentData {

            /** Low-entropy brands (always present). */
            public JsBrand[] getBrands() {
                return new JsBrand[] {
                        new JsBrand("Not_A Brand", "8"),
                        new JsBrand("Chromium", CHROME_MAJOR),
                        new JsBrand("Google Chrome", CHROME_MAJOR)
                };
            }

            public boolean isMobile() {
                return MOBILE;
            }

            public String getPlatform() {
                return PLATFORM;
            }

            /**
             * High-entropy values. Google calls this with a list of hints.
             * We return a plain map that Rhino turns into a JS object.
             * Spec requires a Promise; callers that do .then() still work
             * if a minimal Promise polyfill is present (you already have one).
             */
            public Object getHighEntropyValues(Object hints) {
                Map<String, Object> result = new LinkedHashMap<>();

                // Always include the low-entropy ones (spec behaviour).
                result.put("brands", getBrands());
                result.put("mobile", MOBILE);
                result.put("platform", PLATFORM);

                // High-entropy defaults – real Chrome returns these when asked.
                result.put("platformVersion", PLATFORM_VER);
                result.put("architecture", ARCH);
                result.put("bitness", BITNESS);
                result.put("model", MODEL);
                result.put("uaFullVersion", CHROME_FULL);

                result.put("fullVersionList", new JsBrand[] {
                        new JsBrand("Not_A Brand", "10.0.0.0"),
                        new JsBrand("Chromium", CHROME_FULL),
                        new JsBrand("Google Chrome", CHROME_FULL)
                });

                return result;
            }

            /** Chrome also exposes this. */
            public Object toJSON() {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("brands", getBrands());
                json.put("mobile", MOBILE);
                json.put("platform", PLATFORM);
                return json;
            }
        }

        public static final class JsBrand {
            public final String brand;
            public final String version;

            public JsBrand(String brand, String version) {
                this.brand = brand;
                this.version = version;
            }

            public String getBrand() { return brand; }
            public String getVersion() { return version; }
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
        public void reload() { Logger.info("[JS-NAV] location.reload() called but not implemented (ignored)"); }

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

    private boolean shouldSkipPreprocessing(String scriptUrl) {
        return RecaptchaClosureCompat.isRawBundle(scriptUrl);
    }

    private void installJsPolyfillsAndHelpers() {
        Context cx = null;

        try {
            cx = ContextFactory.getGlobal().enterContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);

            installXMLHttpRequest(scope);

            // 0. Install reCAPTCHA lQ compatibility fallback.
            //
            // Some generated Google Closure/reCAPTCHA code can reference the
            // minified helper `lQ` before its real implementation is available.
            // Define it globally so Rhino does not fail with:
            //
            //     TypeError: lQ is not a function, it is undefined.
            //
            // This is intentionally a no-op fallback for now. If reCAPTCHA gets
            // further and expects a return value from lQ(), we can implement the
            // required behaviour based on the next failure.
            cx.evaluateString(
                    scope,
                    """
                    (function (g) {
                        if (typeof g.lQ !== 'function') {
                            g.lQ = function () {
                                return undefined;
                            };
                        }
                    })(typeof globalThis !== 'undefined' ? globalThis : this);
                    """,
                    "recaptcha-lq-compat",
                    1,
                    null
            );

            // 1. DOM host constructors so `x instanceof Element` / `instanceof Node`
            //    succeed for our Rhino-wrapped JsNode objects.
            //
            // reCAPTCHA (and Closure) reject containers that fail this check with:
            //
            //     "The bind parameter must be an element or id".
            cx.evaluateString(
                    scope,
                    """
                    (function (g) {
                        function isHostNode(x) {
                            try {
                                return x != null
                                    && typeof x.nodeType === 'number'
                                    && (
                                        typeof x.getAttribute === 'function'
                                        || typeof x.appendChild === 'function'
                                        || typeof x.tagName === 'string'
                                    );
                            } catch (e) {
                                return false;
                            }
                        }
    
                        function installCtor(name, test) {
                            if (typeof g[name] === 'function') {
                                // Still install hasInstance so existing constructors
                                // accept our Rhino-wrapped host objects.
                            } else {
                                g[name] = function () {
                                    throw new TypeError('Illegal constructor');
                                };
                            }
    
                            if (typeof Symbol !== 'undefined' && Symbol.hasInstance) {
                                try {
                                    Object.defineProperty(
                                        g[name],
                                        Symbol.hasInstance,
                                        {
                                            configurable: true,
                                            value: function (instance) {
                                                return test(instance);
                                            }
                                        }
                                    );
                                } catch (e) {}
                            }
                        }
    
                        installCtor('Node', isHostNode);
    
                        installCtor('Element', function (x) {
                            return isHostNode(x) && x.nodeType === 1;
                        });
    
                        installCtor('HTMLElement', function (x) {
                            return isHostNode(x) && x.nodeType === 1;
                        });
    
                        installCtor('HTMLDivElement', function (x) {
                            return isHostNode(x)
                                && x.nodeType === 1
                                && String(x.tagName || '').toUpperCase() === 'DIV';
                        });
    
                        installCtor('Document', function (x) {
                            try {
                                return x != null
                                    && typeof x.getElementById === 'function'
                                    && typeof x.createElement === 'function';
                            } catch (e) {
                                return false;
                            }
                        });
                    })(typeof globalThis !== 'undefined' ? globalThis : this);
                    """,
                    "dom-host-instanceof",
                    1,
                    null
            );

            // 2. Install reCAPTCHA __pbRecaptchaBind helper.
            cx.evaluateString(
                    scope,
                    """
                    if (typeof __pbRecaptchaBind !== 'function') {
                        __pbRecaptchaBind = function (fn, boundThis) {
                            if (typeof fn !== 'function') {
                                // .bind(...) was rewritten onto something that is NOT a function.
                                // Report each distinct case once, with a stack that names the
                                // bundle line, so the offending call site can be found.
                                try {
                                    var d = typeof fn;
                                    try { d += ' ' + String(fn).slice(0, 100); } catch (e) {}
                                    var seen = (__pbRecaptchaBind.__seen = __pbRecaptchaBind.__seen || {});
                                    if (!seen[d] && Object.keys(seen).length < 12) {
                                        seen[d] = 1;
                                        console.warn('[BIND-DIAG] .bind() on non-function (' + d + ') at: '
                                            + String(new Error().stack).split('\\n').slice(0, 6).join(' | '));
                                    }
                                } catch (e) {}
                                return function () {
                                    return (
                                        typeof fn === 'function'
                                            ? fn
                                            : function () {}
                                    ).apply(boundThis, arguments);
                                };
                            }
    
                            var boundArgs =
                                Array.prototype.slice.call(arguments, 2);
    
                            return function () {
                                var callArgs =
                                    boundArgs.concat(
                                        Array.prototype.slice.call(arguments)
                                    );
    
                                return fn.apply(boundThis, callArgs);
                            };
                        };
                    }
                    """,
                    "recaptcha-bind-compat",
                    1,
                    null
            );

            // 3. Install TextEncoder & TextDecoder polyfills.
            cx.evaluateString(
                    scope,
                    """
                    if (typeof globalThis === 'undefined') {
                        var globalThis = this;
                    }
    
                    if (typeof globalThis.TextEncoder === 'undefined') {
                        globalThis.TextEncoder = function TextEncoder() {};
    
                        globalThis.TextEncoder.prototype.encode = function (str) {
                            str = String(str);
    
                            var arr = new Uint8Array(str.length);
    
                            for (var i = 0; i < str.length; i++) {
                                arr[i] = str.charCodeAt(i) & 0xFF;
                            }
    
                            return arr;
                        };
                    }
    
                    if (typeof globalThis.TextDecoder === 'undefined') {
                        globalThis.TextDecoder = function TextDecoder() {};
    
                        globalThis.TextDecoder.prototype.decode = function (arr) {
                            if (!arr) {
                                return '';
                            }
    
                            var chimera = [];
    
                            for (var i = 0; i < arr.length; i++) {
                                chimera.push(String.fromCharCode(arr[i]));
                            }
    
                            return chimera.join('');
                        };
                    }
                    """,
                    "text-encoder-polyfill",
                    1,
                    null
            );

            // 4. Install MessageChannel, MessagePort, and global postMessage
            //    polyfills.
            cx.evaluateString(
                    scope,
                    """
                    if (typeof globalThis === 'undefined') {
                        var globalThis = this;
                    }
    
                    if (typeof globalThis.postMessage === 'undefined') {
                        globalThis.postMessage = function (
                            message,
                            targetOrigin
                        ) {
                            // Global fallback for worker/window cross-messaging.
                            if (typeof globalThis.onmessage === 'function') {
                                var evt = {
                                    data: message,
                                    origin: targetOrigin || '*'
                                };
    
                                setTimeout(function () {
                                    globalThis.onmessage(evt);
                                }, 0);
                            }
                        };
                    }
    
                    if (typeof globalThis.MessageChannel === 'undefined') {
    
                        // MessagePort is an EventTarget. reCAPTCHA/Closure call:
                        //
                        //     port.addEventListener('message', fn)
                        //
                        // which previously threw:
                        //
                        //     "addEventListener and attachEvent are unavailable."
    
                        var MP = function MessagePort() {
                            this.onmessage = null;
                            this._listeners = [];
                            this._peer = null;
                            this._closed = false;
                        };
    
                        MP.prototype.addEventListener = function (
                            type,
                            listener
                        ) {
                            if (!listener) {
                                return;
                            }
    
                            for (
                                var i = 0;
                                i < this._listeners.length;
                                i++
                            ) {
                                if (
                                    this._listeners[i].type === type
                                    && this._listeners[i].fn === listener
                                ) {
                                    return;
                                }
                            }
    
                            this._listeners.push({
                                type: String(type),
                                fn: listener
                            });
                        };
    
                        MP.prototype.removeEventListener = function (
                            type,
                            listener
                        ) {
                            for (
                                var i = this._listeners.length - 1;
                                i >= 0;
                                i--
                            ) {
                                if (
                                    this._listeners[i].type === type
                                    && this._listeners[i].fn === listener
                                ) {
                                    this._listeners.splice(i, 1);
                                }
                            }
                        };
    
                        MP.prototype.dispatchEvent = function (evt) {
                            if (this._closed || !evt) {
                                return true;
                            }
    
                            var type = String(evt.type || 'message');
    
                            if (!evt.target) {
                                evt.target = this;
                            }
    
                            if (
                                type === 'message'
                                && typeof this.onmessage === 'function'
                            ) {
                                try {
                                    this.onmessage(evt);
                                } catch (e) {
                                    console.warn(
                                        '[MessagePort] onmessage failed: ' + e
                                    );
                                }
                            }
    
                            var snapshot = this._listeners.slice();
    
                            for (
                                var i = 0;
                                i < snapshot.length;
                                i++
                            ) {
                                if (snapshot[i].type !== type) {
                                    continue;
                                }
    
                                try {
                                    var fn = snapshot[i].fn;
    
                                    if (typeof fn === 'function') {
                                        fn.call(this, evt);
                                    } else if (
                                        fn
                                        && typeof fn.handleEvent === 'function'
                                    ) {
                                        fn.handleEvent(evt);
                                    }
                                } catch (e) {
                                    console.warn(
                                        '[MessagePort] listener failed: ' + e
                                    );
                                }
                            }
    
                            return true;
                        };
    
                        MP.prototype.start = function () {};
    
                        MP.prototype.close = function () {
                            this._closed = true;
                            this._listeners = [];
                        };
    
                        MP.prototype.postMessage = function (message) {
                            var peer = this._peer;
    
                            if (!peer || this._closed) {
                                return;
                            }
    
                            setTimeout(function () {
                                peer.dispatchEvent({
                                    type: 'message',
                                    data: message,
                                    ports: [],
                                    source: null,
                                    origin: ''
                                });
                            }, 0);
                        };
    
                        globalThis.MessagePort = MP;
    
                        globalThis.MessageChannel = function MessageChannel() {
                            this.port1 = new MP();
                            this.port2 = new MP();
    
                            this.port1._peer = this.port2;
                            this.port2._peer = this.port1;
                        };
                    }
                    """,
                    "message-channel-polyfill",
                    1,
                    null
            );

            // 5. Closure's goog.events.listen throws when its target has no
            //    addEventListener/attachEvent/addListener.
            //
            // Keep that behaviour but say WHICH object it was, so the missing
            // event-target can be fixed at the source instead of guessed at.
            cx.evaluateString(
                    scope,
                    """
                    if (typeof __peakyListenError !== 'function') {
                        __peakyListenError = function (target) {
                            var info;
    
                            try {
                                var keys = [];
    
                                try {
                                    for (var k in target) {
                                        keys.push(k);
    
                                        if (keys.length >= 12) {
                                            break;
                                        }
                                    }
                                } catch (e) {}
    
                                info =
                                    'typeof=' + typeof target
                                    + ' ctor='
                                    + (
                                        target
                                        && target.constructor
                                        && target.constructor.name
                                    )
                                    + ' str='
                                    + String(target).slice(0, 80)
                                    + ' keys=['
                                    + keys.join(',')
                                    + ']';
    
                            } catch (e) {
                                info = 'unprintable';
                            }
    
                            try {
                                console.warn(
                                    '[LISTEN-DIAG] event target lacks '
                                    + 'addEventListener/attachEvent/addListener: '
                                    + info
                                );
                            } catch (e) {}
    
                            return new Error(
                                'addEventListener and attachEvent are unavailable.'
                            );
                        };
                    }
                    """,
                    "listen-diagnostic",
                    1,
                    null
            );

            Logger.info(
                    "[JS-COMPAT] Successfully installed JS polyfills and reCAPTCHA helpers."
            );

        } catch (Throwable e) {
            Logger.warn(
                    e,
                    "[JS-COMPAT] Failed to install JS polyfills and reCAPTCHA helpers"
            );
        } finally {
            if (cx != null) {
                Context.exit();
            }
        }
    }

    public void setOnNavigate(java.util.function.Consumer<String> listener) {
        this.onNavigate = listener;
    }

    /**
     * Tears this page's JS environment down: cancels every pending timer/interval and stops the
     * worker threads. Call when the page is replaced; otherwise the old page's timers keep firing
     * against a document nobody can see (the duplicated [PROBE] lines in the log).
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        onNavigate = null;
        for (ScheduledFuture<?> future : scheduledTasks.values()) {
            future.cancel(false);
        }
        scheduledTasks.clear();
        executedSources.clear();
        taskExecutor.shutdownNow();
        scriptLoader.shutdownNow();
        Logger.info("[JS] Closed JavaScript environment for {}", documentUrlOrEmpty());
    }

    private void installXMLHttpRequest(ScriptableObject scope) {
        BaseFunction xhrConstructor = new BaseFunction() {

            @Override
            public Object call(
                    Context cx,
                    Scriptable scope,
                    Scriptable thisObj,
                    Object[] args
            ) {
                return construct(cx, scope, args);
            }

            @Override
            public Scriptable construct(
                    Context cx,
                    Scriptable scope,
                    Object[] args
            ) {
                JsXMLHttpRequest xhr =
                        new JsXMLHttpRequest(http, getDocumentUrl());

                XHRScriptable wrapper = new XHRScriptable(xhr);

                wrapper.setParentScope(scope);
                wrapper.setPrototype(
                        ScriptableObject.getObjectPrototype(scope)
                );

                return wrapper;
            }
        };

        /*
         * Do NOT call:
         *
         * xhrConstructor.setFunctionName("XMLHttpRequest");
         *
         * Your Rhino version does not expose setFunctionName().
         */

        ScriptableObject.putProperty(
                scope,
                "XMLHttpRequest",
                xhrConstructor
        );

        Logger.info("[JS] Installed XMLHttpRequest");
    }

    private void installXMLHttpRequest(Scriptable scope) {
        BaseFunction xhrConstructor = new BaseFunction() {

            @Override
            public Object call(
                    Context cx,
                    Scriptable scope,
                    Scriptable thisObj,
                    Object[] args
            ) {
                return construct(cx, scope, args);
            }

            @Override
            public Scriptable construct(
                    Context cx,
                    Scriptable scope,
                    Object[] args
            ) {
                JsXMLHttpRequest xhr =
                        new JsXMLHttpRequest(http, getDocumentUrl());

                XHRScriptable wrapper = new XHRScriptable(xhr);

                wrapper.setParentScope(scope);
                wrapper.setPrototype(
                        ScriptableObject.getObjectPrototype(scope)
                );

                return wrapper;
            }
        };

        ScriptableObject.putProperty(
                scope,
                "XMLHttpRequest",
                xhrConstructor
        );

        Logger.info("[JS] Installed XMLHttpRequest");
    }

    private static final class XHRScriptable extends ScriptableObject {

        private final JsXMLHttpRequest xhr;

        XHRScriptable(JsXMLHttpRequest xhr) {
            this.xhr = xhr;

            put(
                    "open",
                    this,
                    function((cx, scope, thisObj, args) -> {

                        String method =
                                args.length > 0
                                        ? Context.toString(args[0])
                                        : "GET";

                        String url =
                                args.length > 1
                                        ? Context.toString(args[1])
                                        : "";

                        boolean async =
                                args.length > 2
                                        ? Context.toBoolean(args[2])
                                        : true;

                        xhr.open(method, url, async);

                        return Undefined.instance;
                    })
            );

            put(
                    "setRequestHeader",
                    this,
                    function((cx, scope, thisObj, args) -> {

                        if (args.length < 2) {
                            throw Context.reportRuntimeError(
                                    "XMLHttpRequest.setRequestHeader requires 2 arguments"
                            );
                        }

                        xhr.setRequestHeader(
                                Context.toString(args[0]),
                                Context.toString(args[1])
                        );

                        return Undefined.instance;
                    })
            );

            put(
                    "send",
                    this,
                    function((cx, scope, thisObj, args) -> {

                        try {
                            if (args.length == 0
                                    || args[0] == Undefined.instance
                                    || args[0] == null) {

                                xhr.send();

                            } else {

                                xhr.send(
                                        Context.toString(args[0])
                                );
                            }

                            return Undefined.instance;

                        } catch (Throwable e) {
                            throw Context.throwAsScriptRuntimeEx(e);
                        }
                    })
            );

            put(
                    "abort",
                    this,
                    function((cx, scope, thisObj, args) -> {

                        xhr.abort();

                        return Undefined.instance;
                    })
            );

            put(
                    "getResponseHeader",
                    this,
                    function((cx, scope, thisObj, args) -> {

                        if (args.length == 0) {
                            return null;
                        }

                        return xhr.getResponseHeader(
                                Context.toString(args[0])
                        );
                    })
            );

            put(
                    "getAllResponseHeaders",
                    this,
                    function((cx, scope, thisObj, args) -> {

                        return xhr.getAllResponseHeaders();
                    })
            );
        }

        @Override
        public String getClassName() {
            return "XMLHttpRequest";
        }

        @Override
        public Object get(
                String name,
                Scriptable start
        ) {
            return switch (name) {

                case "readyState" ->
                        xhr.getReadyState();

                case "status" ->
                        xhr.getStatus();

                case "statusText" ->
                        xhr.getStatusText();

                case "responseText" ->
                        xhr.getResponseText();

                case "responseURL" ->
                        xhr.getResponseURL();

                case "withCredentials" ->
                        xhr.getWithCredentials();

                case "timeout" ->
                        xhr.getTimeout();

                default ->
                        super.get(name, start);
            };
        }

        @Override
        public void put(
                String name,
                Scriptable start,
                Object value
        ) {
            switch (name) {

                case "withCredentials" ->
                        xhr.setWithCredentials(
                                Context.toBoolean(value)
                        );

                case "timeout" ->
                        xhr.setTimeout(
                                (int) Context.toNumber(value)
                        );

                default ->
                        super.put(name, start, value);
            }
        }

        private static BaseFunction function(
                RhinoFunction implementation
        ) {
            return new BaseFunction() {

                @Override
                public Object call(
                        Context cx,
                        Scriptable scope,
                        Scriptable thisObj,
                        Object[] args
                ) {
                    return implementation.call(
                            cx,
                            scope,
                            thisObj,
                            args
                    );
                }
            };
        }

        @FunctionalInterface
        private interface RhinoFunction {

            Object call(
                    Context cx,
                    Scriptable scope,
                    Scriptable thisObj,
                    Object[] args
            );
        }
    }

    private String documentUrlOrEmpty() {
        try {
            return getDocumentUrl();
        } catch (Throwable ignored) {
            return "";
        }
    }
}