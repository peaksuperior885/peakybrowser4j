package com.peak885.peakybrowser4jv2.browser.js;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

public final class InstallBrowserCompat {

    public void installBrowserCompatibility(Context cx, Scriptable scope) {
        installClosureCompat(scope);
        installEventTarget(cx, scope);
        installUrl(cx, scope);
        installSearchParams(cx, scope);
        installTimers(cx, scope);
        installPromises(cx, scope);
        installFetchCompatibility(cx, scope);
        installWindowEvents(cx, scope);
        installObservers(cx, scope);
        installMiscellaneous(cx, scope);
        installExceptionHandling(cx, scope);
        installDomConstructors(cx, scope);
    }

    // ---------------------------------------------------------------------
    // EventTarget
    // ---------------------------------------------------------------------

    private void installEventTarget(Context cx, Scriptable scope) {
        String source = """
            if (typeof Event === 'undefined') {
                function Event(type, options) {
                    this.type = String(type || '');
                    this.bubbles = !!(options && options.bubbles);
                    this.cancelable = !!(options && options.cancelable);
                    this.defaultPrevented = false;
                    this.target = null;
                    this.currentTarget = null;
                }

                Event.prototype.preventDefault = function() {
                    if (this.cancelable)
                        this.defaultPrevented = true;
                };
            }

            if (typeof CustomEvent === 'undefined') {
                function CustomEvent(type, options) {
                    Event.call(this, type, options);
                    this.detail = options && options.detail !== undefined
                        ? options.detail
                        : null;
                }

                CustomEvent.prototype = Object.create(Event.prototype);
                CustomEvent.prototype.constructor = CustomEvent;
            }

            if (typeof EventTarget === 'undefined') {
                function EventTarget() {
                    this.__listeners = {};
                }

                EventTarget.prototype.addEventListener = function(type, listener) {
                    if (!type || typeof listener !== 'function')
                        return;

                    if (!this.__listeners[type])
                        this.__listeners[type] = [];

                    if (this.__listeners[type].indexOf(listener) < 0)
                        this.__listeners[type].push(listener);
                };

                EventTarget.prototype.removeEventListener = function(type, listener) {
                    var list = this.__listeners[type];

                    if (!list)
                        return;

                    for (var i = list.length - 1; i >= 0; i--) {
                        if (list[i] === listener)
                            list.splice(i, 1);
                    }
                };

                EventTarget.prototype.dispatchEvent = function(event) {
                    if (!event || !event.type)
                        return true;

                    var list = this.__listeners[event.type];

                    if (!list)
                        return !event.defaultPrevented;

                    event.target = event.target || this;
                    event.currentTarget = this;

                    var copy = list.slice();

                    for (var i = 0; i < copy.length; i++) {
                        try {
                            copy[i].call(this, event);
                        } catch (e) {
                            if (typeof console !== 'undefined' &&
                                console.error) {
                                console.error(
                                    'Unhandled event listener error:',
                                    e
                                );
                            }
                        }
                    }

                    return !event.defaultPrevented;
                };
            }
            """;

        cx.evaluateString(scope, source, "browser-event-target", 1, null);
    }

    // ---------------------------------------------------------------------
    // URL
    // ---------------------------------------------------------------------

    private void installUrl(Context cx, Scriptable scope) {
        String source = """
            if (typeof URL === 'undefined') {
                function URL(url, base) {
                    var value = String(url);

                    try {
                        var uri = base == null
                            ? new java.net.URI(value)
                            : new java.net.URI(String(base)).resolve(value);

                        this.href = String(uri.toString());
                        this.protocol = uri.getScheme() == null ? '' : String(uri.getScheme()) + ':';
                        this.host = uri.getRawAuthority() == null ? '' : String(uri.getRawAuthority());
                        this.hostname = uri.getHost() == null ? '' : String(uri.getHost());
                        this.port = uri.getPort() < 0 ? '' : String(uri.getPort());
                        this.pathname = uri.getRawPath() == null || String(uri.getRawPath()) === ''
                            ? (this.host ? '/' : '')
                            : String(uri.getRawPath());
                        this.search = uri.getRawQuery() == null ? '' : '?' + String(uri.getRawQuery());
                        this.hash = uri.getRawFragment() == null ? '' : '#' + String(uri.getRawFragment());
                        this.username = uri.getRawUserInfo() == null ? '' : String(uri.getRawUserInfo()).split(':')[0];
                        this.password = uri.getRawUserInfo() == null || String(uri.getRawUserInfo()).indexOf(':') < 0
                            ? '' : String(uri.getRawUserInfo()).substring(String(uri.getRawUserInfo()).indexOf(':') + 1);
                        this.origin = this.protocol && this.host ? this.protocol + '//' + this.host : 'null';
                    } catch (e) {
                        throw new TypeError('Invalid URL: ' + value);
                    }
                }

                URL.prototype.toString = function() {
                    return this.href;
                };

                URL.prototype.toJSON = function() {
                    return this.href;
                };

                URL.canParse = function(url, base) {
                    try {
                        new URL(url, base);
                        return true;
                    } catch (e) {
                        return false;
                    }
                };

                URL.parse = function(url, base) {
                    try {
                        return new URL(url, base);
                    } catch (e) {
                        return null;
                    }
                };
            }
            """;

        cx.evaluateString(scope, source, "browser-url", 1, null);
    }

    // ---------------------------------------------------------------------
    // URLSearchParams
    // ---------------------------------------------------------------------

    private void installSearchParams(Context cx, Scriptable scope) {
        String source = """
            if (typeof URLSearchParams === 'undefined') {

                function URLSearchParams(init) {
                    this._entries = [];

                    if (init == null)
                        return;

                    if (typeof init === 'string') {
                        var text = init;

                        if (text.charAt(0) === '?')
                            text = text.substring(1);

                        if (!text)
                            return;

                        var parts = text.split('&');

                        for (var i = 0; i < parts.length; i++) {
                            var part = parts[i];

                            if (!part)
                                continue;

                            var eq = part.indexOf('=');

                            var name;
                            var value;

                            if (eq < 0) {
                                name = part;
                                value = '';
                            } else {
                                name = part.substring(0, eq);
                                value = part.substring(eq + 1);
                            }

                            this.append(
                                this._decode(name),
                                this._decode(value)
                            );
                        }

                        return;
                    }

                    if (Array.isArray(init)) {
                        for (var i = 0; i < init.length; i++) {
                            if (init[i] && init[i].length >= 2) {
                                this.append(
                                    init[i][0],
                                    init[i][1]
                                );
                            }
                        }

                        return;
                    }

                    if (typeof init === 'object') {
                        var keys = Object.keys(init);

                        for (var i = 0; i < keys.length; i++) {
                            this.append(
                                keys[i],
                                init[keys[i]]
                            );
                        }
                    }
                }

                URLSearchParams.prototype._decode = function(value) {
                    try {
                        return decodeURIComponent(
                            String(value).replace(/\\+/g, ' ')
                        );
                    } catch (e) {
                        return String(value);
                    }
                };

                URLSearchParams.prototype._encode = function(value) {
                    return encodeURIComponent(
                        String(value)
                    ).replace(/%20/g, '+');
                };

                URLSearchParams.prototype.append = function(name, value) {
                    this._entries.push([
                        String(name),
                        String(value)
                    ]);
                };

                URLSearchParams.prototype.set = function(name, value) {
                    name = String(name);
                    value = String(value);

                    var result = [];
                    var found = false;

                    for (var i = 0; i < this._entries.length; i++) {
                        var pair = this._entries[i];

                        if (pair[0] === name) {
                            if (!found) {
                                result.push([name, value]);
                                found = true;
                            }
                        } else {
                            result.push(pair);
                        }
                    }

                    if (!found)
                        result.push([name, value]);

                    this._entries = result;
                };

                URLSearchParams.prototype.get = function(name) {
                    name = String(name);

                    for (var i = 0; i < this._entries.length; i++) {
                        if (this._entries[i][0] === name)
                            return this._entries[i][1];
                    }

                    return null;
                };

                URLSearchParams.prototype.getAll = function(name) {
                    name = String(name);
                    var result = [];

                    for (var i = 0; i < this._entries.length; i++) {
                        if (this._entries[i][0] === name)
                            result.push(this._entries[i][1]);
                    }

                    return result;
                };

                URLSearchParams.prototype.has = function(name) {
                    return this.get(name) !== null;
                };

                URLSearchParams.prototype.delete = function(name) {
                    name = String(name);

                    var result = [];

                    for (var i = 0; i < this._entries.length; i++) {
                        if (this._entries[i][0] !== name)
                            result.push(this._entries[i]);
                    }

                    this._entries = result;
                };

                URLSearchParams.prototype.sort = function() {
                    this._entries.sort(function(a, b) {
                        return a[0] < b[0] ? -1 :
                               a[0] > b[0] ? 1 : 0;
                    });
                };

                URLSearchParams.prototype.forEach = function(callback, thisArg) {
                    for (var i = 0; i < this._entries.length; i++) {
                        callback.call(
                            thisArg,
                            this._entries[i][1],
                            this._entries[i][0],
                            this
                        );
                    }
                };

                URLSearchParams.prototype.toString = function() {
                    var result = [];

                    for (var i = 0; i < this._entries.length; i++) {
                        var pair = this._entries[i];

                        result.push(
                            this._encode(pair[0]) +
                            '=' +
                            this._encode(pair[1])
                        );
                    }

                    return result.join('&');
                };
            }
            """;

        cx.evaluateString(
                scope,
                source,
                "browser-url-search-params",
                1,
                null
        );
    }

    // ---------------------------------------------------------------------
    // Timers
    // ---------------------------------------------------------------------

    private void installTimers(Context cx, Scriptable scope) {
        String source = """
            if (typeof performance === 'undefined') {
                performance = {
                    now: function() {
                        return Date.now();
                    }
                };
            }
            """;

        cx.evaluateString(
                scope,
                source,
                "browser-timers",
                1,
                null
        );
    }

    // ---------------------------------------------------------------------
    // Promise
    // ---------------------------------------------------------------------

    private void installPromises(Context cx, Scriptable scope) {
        String source = """
    this.Promise = function(executor) {
        this._state = 'pending';
        this._value = undefined;
        this._handlers = [];

        var self = this;

        function resolve(value) {
            if (self._state !== 'pending')
                return;

            self._state = 'fulfilled';
            self._value = value;
            self._flush();
        }

        function reject(reason) {
            if (self._state !== 'pending')
                return;

            self._state = 'rejected';
            self._value = reason;
            self._flush();
        }

        this._resolve = resolve;
        this._reject = reject;

        if (typeof executor !== 'function')
            throw new TypeError(
                'Promise resolver is not a function'
            );

        try {
            executor(resolve, reject);
        } catch (e) {
            reject(e);
        }
    };

    Promise.prototype._flush = function() {
        var handlers = this._handlers.slice();
        this._handlers = [];

        for (var i = 0; i < handlers.length; i++)
            this._runHandler(handlers[i]);
    };

    Promise.prototype._runHandler = function(handler) {
        var callback =
            this._state === 'fulfilled'
                ? handler.onFulfilled
                : handler.onRejected;

        var nextResolve = handler.resolve;
        var nextReject = handler.reject;

        try {
            if (typeof callback !== 'function') {
                if (this._state === 'fulfilled')
                    nextResolve(this._value);
                else
                    nextReject(this._value);

                return;
            }

            var result = callback(this._value);

            if (result && typeof result.then === 'function') {
                result.then(nextResolve, nextReject);
            } else {
                nextResolve(result);
            }
        } catch (e) {
            nextReject(e);
        }
    };

    Promise.prototype.then = function(onFulfilled, onRejected) {
        var self = this;

        return new Promise(function(resolve, reject) {
            self._handlers.push({
                onFulfilled: onFulfilled,
                onRejected: onRejected,
                resolve: resolve,
                reject: reject
            });

            if (self._state !== 'pending')
                self._flush();
        });
    };

    Promise.prototype.catch = function(onRejected) {
        return this.then(null, onRejected);
    };

    Promise.resolve = function(value) {
        return new Promise(function(resolve) {
            resolve(value);
        });
    };

    Promise.reject = function(reason) {
        return new Promise(function(resolve, reject) {
            reject(reason);
        });
    };
    """;

        cx.evaluateString(
                scope,
                source,
                "browser-promise",
                1,
                null
        );
    }

    // ---------------------------------------------------------------------
    // Fetch shell
    // ---------------------------------------------------------------------

    private void installFetchCompatibility(Context cx, Scriptable scope) {
        String source = """
            if (typeof Headers === 'undefined') {

                function Headers(init) {
                    this._headers = {};

                    if (init) {
                        var keys = Object.keys(init);

                        for (var i = 0; i < keys.length; i++) {
                            this.set(
                                keys[i],
                                init[keys[i]]
                            );
                        }
                    }
                }

                Headers.prototype.set = function(name, value) {
                    this._headers[String(name).toLowerCase()] =
                        String(value);
                };

                Headers.prototype.get = function(name) {
                    var value =
                        this._headers[String(name).toLowerCase()];

                    return value === undefined
                        ? null
                        : value;
                };

                Headers.prototype.has = function(name) {
                    return this.get(name) !== null;
                };

                Headers.prototype.delete = function(name) {
                    delete this._headers[
                        String(name).toLowerCase()
                    ];
                };

                Headers.prototype.forEach = function(callback, thisArg) {
                    var keys = Object.keys(this._headers);

                    for (var i = 0; i < keys.length; i++) {
                        callback.call(
                            thisArg,
                            this._headers[keys[i]],
                            keys[i],
                            this
                        );
                    }
                };
            }

            if (typeof Request === 'undefined') {
                function Request(input, init) {
                    init = init || {};

                    this.url = String(input);
                    this.method =
                        String(init.method || 'GET').toUpperCase();

                    this.headers =
                        init.headers instanceof Headers
                            ? init.headers
                            : new Headers(init.headers);

                    this.body =
                        init.body === undefined
                            ? null
                            : init.body;

                    this.credentials =
                        init.credentials || 'same-origin';

                    this.mode =
                        init.mode || 'cors';
                }
            }

            if (typeof Response === 'undefined') {
                function Response(body, init) {
                    init = init || {};

                    this._body =
                        body == null
                            ? ''
                            : String(body);

                    this.status =
                        init.status === undefined
                            ? 200
                            : init.status;

                    this.statusText =
                        init.statusText || '';

                    this.ok =
                        this.status >= 200 &&
                        this.status < 300;

                    this.url =
                        init.url || '';

                    this.headers =
                        init.headers instanceof Headers
                            ? init.headers
                            : new Headers(init.headers);
                }

                // text()/json() return their value directly rather than a
                // Promise. JsPreprocessor strips "await" from page scripts
                // (there's no real event loop to suspend on), so
                // "await response.text()" becomes "response.text()" -- if
                // that returned a Promise, the caller would be left holding
                // the Promise object itself instead of the string/object it
                // wanted. See the note on JsBridge.fetchFunction() for the
                // full explanation.
                Response.prototype.text = function() {
                    return this._body;
                };

                Response.prototype.json = function() {
                    var data = JSON.parse(this.text());
                    if (data && typeof data === 'object') {
                        // Lets `.json().then(data => ...)`-style chains
                        // (written for a real Promise) keep working even
                        // though the value is already available.
                        data.then = function(onFulfilled) {
                            return typeof onFulfilled === 'function' ? onFulfilled(data) : data;
                        };
                        data.catch = function() {
                            return data;
                        };
                    }
                    return data;
                };

                // Same reasoning as text()/json() above: fetch() itself
                // returns a Response directly rather than a Promise, so a
                // real `.then()` chain on the fetch() call needs a
                // synthetic then()/catch() to keep working too.
                Response.prototype.then = function(onFulfilled) {
                    return typeof onFulfilled === 'function' ? onFulfilled(this) : this;
                };

                Response.prototype.catch = function() {
                    return this;
                };

                Response.prototype.clone = function() {
                    return new Response(
                        this._body,
                        {
                            status: this.status,
                            statusText: this.statusText,
                            url: this.url,
                            headers: this.headers
                        }
                    );
                };
            }
            """;

        cx.evaluateString(
                scope,
                source,
                "browser-fetch-types",
                1,
                null
        );
    }

    // ---------------------------------------------------------------------
    // Window events
    // ---------------------------------------------------------------------

    private void installWindowEvents(Context cx, Scriptable scope) {
        String source = """
            if (typeof addEventListener === 'undefined') {
                var __windowTarget = new EventTarget();

                addEventListener =
                    __windowTarget.addEventListener.bind(
                        __windowTarget
                    );

                removeEventListener =
                    __windowTarget.removeEventListener.bind(
                        __windowTarget
                    );

                dispatchEvent =
                    __windowTarget.dispatchEvent.bind(
                        __windowTarget
                    );
            }

            if (typeof window !== 'undefined') {
                window.addEventListener = addEventListener;
                window.removeEventListener = removeEventListener;
                window.dispatchEvent = dispatchEvent;
            }

            try {
                if (document &&
                    typeof document.readyState === 'undefined') {

                    Object.defineProperty(
                        document,
                        'readyState',
                        {
                            value: 'complete',
                            writable: false,
                            configurable: true
                        }
                    );
                }
            } catch (e) {}
            """;

        cx.evaluateString(
                scope,
                source,
                "browser-window-events",
                1,
                null
        );
    }

    // ---------------------------------------------------------------------
    // Observer compatibility
    // ---------------------------------------------------------------------

    private void installObservers(Context cx, Scriptable scope) {
        String source = """
            if (typeof MutationObserver === 'undefined') {
                MutationObserver = function(callback) {
                    this.callback = callback;
                };

                MutationObserver.prototype.observe = function(
                    target,
                    options
                ) {};

                MutationObserver.prototype.disconnect = function() {};

                MutationObserver.prototype.takeRecords = function() {
                    return [];
                };
            }

            if (typeof IntersectionObserver === 'undefined') {
                IntersectionObserver = function(callback) {
                    this.callback = callback;
                };

                IntersectionObserver.prototype.observe = function(
                    target
                ) {};

                IntersectionObserver.prototype.unobserve = function(
                    target
                ) {};

                IntersectionObserver.prototype.disconnect = function() {};
            }

            if (typeof ResizeObserver === 'undefined') {
                ResizeObserver = function(callback) {
                    this.callback = callback;
                };

                ResizeObserver.prototype.observe = function(target) {};
                ResizeObserver.prototype.unobserve = function(target) {};
                ResizeObserver.prototype.disconnect = function() {};
            }
            """;

        cx.evaluateString(
                scope,
                source,
                "browser-observers",
                1,
                null
        );
    }

    // ---------------------------------------------------------------------
    // Miscellaneous
    // ---------------------------------------------------------------------

    private void installMiscellaneous(Context cx, Scriptable scope) {
        String source = """
            if (typeof alert === 'undefined') {
                alert = function(message) {
                    if (typeof console !== 'undefined' &&
                        console.log) {
                        console.log(
                            '[JS alert]',
                            message == null ? '' : String(message)
                        );
                    }
                };
            }

            if (typeof confirm === 'undefined') {
                confirm = function(message) {
                    return false;
                };
            }

            if (typeof prompt === 'undefined') {
                prompt = function(message, defaultValue) {
                    return defaultValue == null
                        ? null
                        : String(defaultValue);
                };
            }

            if (typeof matchMedia === 'undefined') {
                matchMedia = function(query) {
                    return {
                        matches: false,
                        media: String(query || ''),
                        onchange: null,

                        addListener: function() {},
                        removeListener: function() {},

                        addEventListener: function() {},
                        removeEventListener: function() {},

                        dispatchEvent: function() {
                            return false;
                        }
                    };
                };
            }

            if (typeof customElements === 'undefined') {
                customElements = {
                    define: function() {},
                    get: function() {
                        return undefined;
                    },
                    whenDefined: function() {
                        return Promise.resolve();
                    }
                };
            }
                if (typeof CSS === 'undefined') {
                    CSS = {
                        supports: function(propertyOrCondition, value) {
                            // We don't have a real CSS engine to ask, so this is a
                            // pragmatic stand-in: claim support for anything so feature-
                            // gated sites take their "modern browser" code path instead
                            // of silently falling back to a degraded one.
                            return true;
                        },
                
                        escape: function(value) {
                            var s = String(value == null ? '' : value);
                            var out = '';
                            for (var i = 0; i < s.length; i++) {
                                var ch = s.charAt(i);
                                var code = s.charCodeAt(i);
                
                                if (code === 0) {
                                    out += '\\uFFFD';
                                } else if ((code >= 1 && code <= 31) || code === 127) {
                                    out += '\\\\' + code.toString(16) + ' ';
                                } else if (/[a-zA-Z0-9_-]/.test(ch)) {
                                    out += ch;
                                } else if (code >= 0x80) {
                                    out += ch;
                                } else {
                                    out += '\\\\' + ch;
                                }
                            }
                            return out;
                        }
                    };
                }
            """;

        cx.evaluateString(
                scope,
                source,
                "browser-misc",
                1,
                null
        );
    }

    // ---------------------------------------------------------------------
    // DOM constructor stand-ins (for `instanceof` checks only)
    // ---------------------------------------------------------------------
    //
    // Our elements are wrapped Java objects (JsNode), not real Rhino
    // prototype-chain instances, so a native `instanceof HTMLInputElement`
    // would always be false. A lot of page/vendor scripts (Cloudflare's
    // Turnstile among them) guard against exactly this by checking
    // `Ctor[Symbol.hasInstance]` before falling back to native `instanceof`:
    //
    //     function w(e, t) {
    //         return t != null && t[Symbol.hasInstance]
    //             ? !!t[Symbol.hasInstance](e)
    //             : e instanceof t;
    //     }
    //
    // So we don't need real subclassing — just a constructor function per
    // type with a Symbol.hasInstance that duck-types against tagName/nodeType.
    private void installDomConstructors(Context cx, Scriptable scope) {
        String source = """
            function __tagIs(obj, tag) {
                return obj != null
                    && typeof obj.tagName === 'string'
                    && obj.tagName.toUpperCase() === tag;
            }

            if (typeof Element === 'undefined') {
                Element = function() {};
                Element[Symbol.hasInstance] = function(obj) {
                    return obj != null && typeof obj.tagName === 'string';
                };
            }

            if (typeof HTMLElement === 'undefined') {
                HTMLElement = function() {};
                HTMLElement[Symbol.hasInstance] = Element[Symbol.hasInstance];
            }

            function __defineHtmlCtor(name, tag) {
                if (typeof globalThis[name] === 'undefined') {
                    var ctor = function() {};
                    ctor[Symbol.hasInstance] = function(obj) {
                        return __tagIs(obj, tag);
                    };
                    globalThis[name] = ctor;
                }
            }

            __defineHtmlCtor('HTMLInputElement', 'INPUT');
            __defineHtmlCtor('HTMLButtonElement', 'BUTTON');
            __defineHtmlCtor('HTMLSelectElement', 'SELECT');
            __defineHtmlCtor('HTMLTextAreaElement', 'TEXTAREA');
            __defineHtmlCtor('HTMLAnchorElement', 'A');
            __defineHtmlCtor('HTMLScriptElement', 'SCRIPT');
            __defineHtmlCtor('HTMLDivElement', 'DIV');
            __defineHtmlCtor('HTMLIFrameElement', 'IFRAME');
            __defineHtmlCtor('HTMLFormElement', 'FORM');
            __defineHtmlCtor('HTMLImageElement', 'IMG');

            // window.Image — used heavily by Google for beacons / preloads.
            // new Image() must return a usable object with a writable .src.
            if (typeof Image === 'undefined') {
                Image = function(width, height) {
                    this.tagName = 'IMG';
                    this.nodeName = 'IMG';
                    this.width = width || 0;
                    this.height = height || 0;
                    this.naturalWidth = 0;
                    this.naturalHeight = 0;
                    this.complete = false;
                    this.onload = null;
                    this.onerror = null;
                    this.onabort = null;
                    var _src = '';
                    var self = this;
                    Object.defineProperty(this, 'src', {
                        get: function() { return _src; },
                        set: function(v) {
                            _src = v == null ? '' : String(v);
                            // Fire onload asynchronously so page scripts that
                            // set img.onload = ...; img.src = ... still work.
                            if (typeof setTimeout === 'function') {
                                setTimeout(function() {
                                    self.complete = true;
                                    self.naturalWidth = self.width || 1;
                                    self.naturalHeight = self.height || 1;
                                    if (typeof self.onload === 'function') {
                                        try { self.onload.call(self); } catch (e) {}
                                    }
                                }, 0);
                            }
                        },
                        enumerable: true,
                        configurable: true
                    });
                };
                Image.prototype = HTMLImageElement.prototype || {};
                Image[Symbol.hasInstance] = HTMLImageElement[Symbol.hasInstance];
                // Alias used by some scripts
                if (typeof HTMLImageElement !== 'undefined') {
                    // keep both names
                }
            }

            if (typeof ShadowRoot === 'undefined') {
                ShadowRoot = function() {};
                ShadowRoot[Symbol.hasInstance] = function(obj) {
                    return obj != null && obj.jsShadowRootNode === true;
                };
            }

            if (typeof DOMException === 'undefined') {
                DOMException = function(message, name) {
                    this.message = message || '';
                    this.name = name || 'Error';
                };
                DOMException.prototype = Object.create(Error.prototype);
                DOMException.prototype.constructor = DOMException;
            }

            if (typeof PerformanceResourceTiming === 'undefined') {
                PerformanceResourceTiming = function() {};
            }

            if (typeof performance === 'undefined') {
                performance = {
                    now: function() {
                        return Date.now();
                    },
                    getEntriesByType: function(type) {
                        return [];
                    },
                    getEntriesByName: function(name) {
                        return [];
                    },
                    mark: function() {},
                    measure: function() {}
                };
            }
            """;

        cx.evaluateString(
                scope,
                source,
                "browser-dom-ctors",
                1,
                null
        );
    }

    private void installExceptionHandling(Context cx, Scriptable scope) {
        String source = """
    if (typeof __browserDumpException !== 'function') {
        __browserDumpException = function(error) {
            if (typeof console !== 'undefined' &&
                console &&
                typeof console.error === 'function') {
                console.error('[JS exception]', error);
            }
        };
    }

    if (typeof _DumpException !== 'function') {
        _DumpException = __browserDumpException;
    }

    if (typeof gbar_ === 'undefined' || !gbar_) {
        gbar_ = {};
    }

    if (typeof gbar_._DumpException !== 'function') {
        gbar_._DumpException = __browserDumpException;
    }
    """;

        cx.evaluateString(
                scope,
                source,
                "browser-exception-handling",
                1,
                null
        );
    }

    public static void installClosureCompat(Scriptable scope) {
        String script = """
        if (typeof $jscomp === 'undefined') {
            var $jscomp = {};
        }

        if (typeof $jscomp.inherits !== 'function') {
            $jscomp.inherits = function(child, parent) {
                child.prototype = Object.create(parent.prototype);
                child.prototype.constructor = child;
            };
        }

        if (typeof $jscomp.makeIterator !== 'function') {
            $jscomp.makeIterator = function(iterable) {
                if (iterable == null) {
                    return {
                        next: function() {
                            return {done: true};
                        }
                    };
                }

                var index = 0;

                return {
                    next: function() {
                        if (index >= iterable.length) {
                            return {done: true};
                        }

                        return {
                            done: false,
                            value: iterable[index++]
                        };
                    }
                };
            };
        }
    """;

        Context context = Context.getCurrentContext();
        context.evaluateString(scope, script, "closure-compat", 1, null);
    }
}