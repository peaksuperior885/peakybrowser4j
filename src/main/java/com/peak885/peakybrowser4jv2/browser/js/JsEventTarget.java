package com.peak885.peakybrowser4jv2.browser.js;

import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JsEventTarget {

    private final JsNode owner;
    private final JsNode.Ctx ctx;

    private final Map<String, List<Listener>> listeners =
            new HashMap<>();

    private final Map<String, Object> handlers =
            new HashMap<>();

    JsEventTarget(
            JsNode owner,
            JsNode.Ctx ctx
    ) {
        this.owner = owner;
        this.ctx = ctx;
    }

    public void addEventListener(
            String type,
            Object listener,
            boolean useCapture
    ) {
        if (type == null
                || type.isBlank()
                || listener == null) {
            return;
        }

        listeners
                .computeIfAbsent(
                        type.toLowerCase(),
                        k -> new ArrayList<>()
                )
                .add(
                        new Listener(
                                listener,
                                useCapture
                        )
                );
    }

    public void removeEventListener(
            String type,
            Object listener
    ) {
        if (type == null || listener == null) {
            return;
        }

        List<Listener> list =
                listeners.get(type.toLowerCase());

        if (list == null) {
            return;
        }

        list.removeIf(
                entry -> entry.listener == listener
        );

        if (list.isEmpty()) {
            listeners.remove(type.toLowerCase());
        }
    }

    public void setHandler(
            String type,
            Object listener
    ) {
        String normalized = normalize(type);

        if (listener == null) {
            handlers.remove(normalized);
        } else {
            handlers.put(normalized, listener);
        }
    }

    public void dispatchEvent(String type) {
        dispatchEvent(type, null);
    }

    public void dispatchEvent(
            String type,
            String key
    ) {
        if (type == null || type.isBlank()) {
            return;
        }

        String normalized = normalize(type);

        JsNode.JsEvent event =
                new JsNode.JsEvent(
                        normalized,
                        owner,
                        key
                );

        /*
         * Property-style handler:
         *
         * element.onclick = function...
         */
        Object handler = handlers.get(normalized);

        if (handler != null) {
            invoke(handler, event);

            if (event.isPropagationStopped()) {
                return;
            }
        }

        List<Listener> list =
                listeners.get(normalized);

        if (list == null || list.isEmpty()) {
            return;
        }

        /*
         * Snapshot so a listener can safely remove itself
         * while dispatching.
         */
        for (Listener entry :
                new ArrayList<>(list)) {

            invoke(entry.listener, event);

            if (event.isPropagationStopped()) {
                break;
            }
        }
    }

    private void invoke(
            Object listener,
            JsNode.JsEvent event
    ) {
        try {
            ctx.invoker().invoke(listener, event);
        } catch (Exception e) {
            Logger.warn(
                    e,
                    "Unhandled error in JS '{}' listener",
                    event.getType()
            );
        }
    }

    private static String normalize(String type) {
        return type == null
                ? ""
                : type.toLowerCase();
    }

    private static final class Listener {

        private final Object listener;
        private final boolean capture;

        private Listener(
                Object listener,
                boolean capture
        ) {
            this.listener = listener;
            this.capture = capture;
        }
    }
}