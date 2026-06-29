package com.peak885.peakybrowser4j.browser;

import com.peak885.peakybrowser4j.browser.bridge.StyleBridge;
import org.mozilla.javascript.*;

public class JsNode extends ScriptableObject {
    private Node node;

    public JsNode(Node node) {
        this.node = node;
    }

    @Override
    public String getClassName() { return "JsNode"; }

    public Object jsFunction_getElementsByClassName(String className) {
        Context ctx = Context.getCurrentContext();
        Scriptable array = ctx.newArray(this, 0);

        int[] index = {0};

        findNodesWithClass(this.node, className, array, index);
        return array;
    }

    private void findNodesWithClass(Node n, String className, Scriptable array, int[] index) {
        if (n.classes != null && n.classes.contains(className)) {
            array.put(index[0]++, array, new JsNode(n));
        }
        for (Node child : n.children) {
            findNodesWithClass(child, className, array, index);
        }
    }

    @Override
    public Object get(String name, Scriptable start) {
        if ("parentElement".equals(name)) {
            return (node.parent != null) ? new JsNode(node.parent) : null;
        }

        if ("style".equals(name)) {
            return new StyleBridge(node.computedStyle);
        }

        if ("getElementsByClassName".equals(name)) {
            return new BaseFunction() {
                @Override
                public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                    return jsFunction_getElementsByClassName((String) args[0]);
                }
            };
        }
        return super.get(name, start);
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        if ("innerText".equals(name)) {
            node.text = value.toString();
            node.markDirty();
        }
        super.put(name, start, value);
    }
}