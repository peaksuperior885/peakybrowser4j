package com.peak885.peakybrowser4jv2.browser.js;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Wrapper;

import java.util.Map;

public final class JsJson {

    private final Gson gson = new Gson();

    /**
     * Installs the browser-compatible JSON object into the Rhino scope.
     *
     * Provides:
     *   JSON.parse(string)
     *   JSON.stringify(value)
     */
    public void install(Context cx, Scriptable scope) {
        Scriptable json = cx.newObject(scope);

        /*
         * JSON.parse()
         */
        ScriptableObject.putProperty(
                json,
                "parse",
                new BaseFunction() {
                    @Override
                    public Object call(
                            Context context,
                            Scriptable thisObj,
                            Scriptable functionScope,
                            Object[] args
                    ) {
                        if (args.length == 0) {
                            throw Context.reportRuntimeError(
                                    "SyntaxError: JSON.parse requires a string"
                            );
                        }

                        String source = Context.toString(args[0]);

                        try {
                            JsonElement element =
                                    JsonParser.parseString(source);

                            /*
                             * IMPORTANT:
                             *
                             * Build Rhino objects directly instead of
                             * converting the JSON tree to Java Maps,
                             * Object[], and Strings first.
                             */
                            return toJavaScriptValue(
                                    context,
                                    functionScope,
                                    element
                            );

                        } catch (Exception e) {
                            throw Context.reportRuntimeError(
                                    "SyntaxError: " + e.getMessage()
                            );
                        }
                    }
                }
        );

        /*
         * JSON.stringify()
         */
        ScriptableObject.putProperty(
                json,
                "stringify",
                new BaseFunction() {
                    @Override
                    public Object call(
                            Context context,
                            Scriptable thisObj,
                            Scriptable functionScope,
                            Object[] args
                    ) {
                        if (args.length == 0
                                || args[0] == null
                                || args[0]
                                == org.mozilla.javascript.Undefined.instance) {
                            return org.mozilla.javascript.Undefined.instance;
                        }

                        try {
                            JsonElement element =
                                    fromJavaScript(args[0]);

                            return gson.toJson(element);

                        } catch (Exception e) {
                            throw Context.reportRuntimeError(
                                    "TypeError: " + e.getMessage()
                            );
                        }
                    }
                }
        );

        ScriptableObject.putProperty(
                scope,
                "JSON",
                json
        );
    }

    /**
     * Converts Gson's JSON tree directly into Rhino JavaScript values.
     *
     * This is deliberately NOT implemented using Context.javaToJS()
     * on a Java Map/Object[] graph. Doing that can expose Java String
     * instances to JavaScript and cause Rhino to resolve Java overloads
     * such as String.replace(...).
     */
    private Object toJavaScriptValue(
            Context cx,
            Scriptable scope,
            JsonElement element
    ) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        /*
         * Primitive values
         */
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive =
                    element.getAsJsonPrimitive();

            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }

            if (primitive.isString()) {
                /*
                 * This String is returned as a primitive value from the
                 * JSON conversion rather than being wrapped inside a
                 * Java collection/object.
                 *
                 * The Java primitive wrapping behavior is also disabled
                 * in JsBridge's Rhino Context.
                 */
                return primitive.getAsString();
            }

            if (primitive.isNumber()) {
                Number number =
                        primitive.getAsNumber();

                if (number instanceof Integer
                        || number instanceof Long
                        || number instanceof Short
                        || number instanceof Byte) {
                    return number.longValue();
                }

                return number.doubleValue();
            }
        }

        /*
         * JSON arrays
         */
        if (element.isJsonArray()) {
            JsonArray source =
                    element.getAsJsonArray();

            Scriptable array =
                    cx.newArray(
                            scope,
                            source.size()
                    );

            for (int i = 0; i < source.size(); i++) {
                ScriptableObject.putProperty(
                        array,
                        i,
                        toJavaScriptValue(
                                cx,
                                scope,
                                source.get(i)
                        )
                );
            }

            return array;
        }

        /*
         * JSON objects
         */
        if (element.isJsonObject()) {
            JsonObject source =
                    element.getAsJsonObject();

            Scriptable object =
                    cx.newObject(scope);

            for (Map.Entry<String, JsonElement> entry
                    : source.entrySet()) {

                ScriptableObject.putProperty(
                        object,
                        entry.getKey(),
                        toJavaScriptValue(
                                cx,
                                scope,
                                entry.getValue()
                        )
                );
            }

            return object;
        }

        return null;
    }

    /**
     * Converts a Rhino JavaScript value into a Gson JSON tree.
     */
    private JsonElement fromJavaScript(Object value) {
        if (value == null
                || value
                == org.mozilla.javascript.Undefined.instance) {
            return JsonNull.INSTANCE;
        }

        /*
         * Unwrap Java-backed values when necessary.
         */
        if (value instanceof Wrapper wrapper) {
            value = wrapper.unwrap();
        }

        /*
         * Rhino objects and arrays.
         */
        if (value instanceof Scriptable scriptable) {
            return scriptableToJson(scriptable);
        }

        /*
         * Primitive JavaScript values.
         */
        return gson.toJsonTree(value);
    }

    /**
     * Converts a Rhino Scriptable object into a Gson JSON object/array.
     */
    private JsonElement scriptableToJson(Scriptable object) {

        /*
         * JavaScript arrays need to remain JSON arrays rather than
         * becoming objects with numeric property names.
         */
        if (object instanceof org.mozilla.javascript.NativeArray array) {
            JsonArray result = new JsonArray();

            long length = array.getLength();

            for (long i = 0; i < length; i++) {
                Object value =
                        ScriptableObject.getProperty(
                                array,
                                (int) i
                        );

                result.add(
                        fromJavaScript(value)
                );
            }

            return result;
        }

        /*
         * Normal JavaScript object.
         */
        JsonObject result = new JsonObject();

        for (Object id : object.getIds()) {
            if (!(id instanceof String key)) {
                continue;
            }

            Object property =
                    ScriptableObject.getProperty(
                            object,
                            key
                    );

            /*
             * Functions are not JSON serializable.
             */
            if (property
                    instanceof org.mozilla.javascript.Function) {
                continue;
            }

            result.add(
                    key,
                    fromJavaScript(property)
            );
        }

        return result;
    }
}