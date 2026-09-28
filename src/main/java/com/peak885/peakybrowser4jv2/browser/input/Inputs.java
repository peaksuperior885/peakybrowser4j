package com.peak885.peakybrowser4jv2.browser.input;

import com.peak885.peakybrowser4jv2.browser.js.JsBridge;
import com.peak885.peakybrowser4jv2.browser.render.layout.Box;
import com.peak885.peakybrowser4jv2.browser.render.layout.ElementBox;
import com.peak885.peakybrowser4jv2.browser.render.layout.InlineBox;

import org.jsoup.nodes.Element;
import org.tinylog.Logger;

import javax.swing.JComponent;
import java.awt.Cursor;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Central input controller for the render surface.
 */
public final class Inputs implements MouseListener, MouseMotionListener {

    private final JComponent surface;

    private Supplier<Box> rootBoxSupplier = () -> null;
    private Supplier<JsBridge> bridgeSupplier = () -> null;

    /**
     * Called after focus/value changes so the surface can repaint.
     */
    private Runnable onRepaint = () -> {};

    /**
     * Called when :hover / :active / :focus targets change so styles can
     * be re-resolved against interactive pseudo-classes.
     */
    private Runnable onInteractionChange = () -> {};

    private int mouseX;
    private int mouseY;

    private boolean leftDown;
    private boolean rightDown;

    private Element hoveredElement;
    private Element activeElement;
    private ElementBox focusedControl;

    private BiConsumer<Integer, Integer> onLeftClick;
    private Consumer<String> onNavigate = href -> {};
    private Consumer<FormSubmission> onFormSubmit = submission -> {};

    public Inputs(JComponent surface) {
        this.surface = surface;

        surface.addMouseListener(this);
        surface.addMouseMotionListener(this);
        surface.setFocusable(true);

        surface.addKeyListener(new KeyAdapter() {

            @Override
            public void keyPressed(KeyEvent e) {
                if (handleControlKey(e)) {
                    e.consume();
                    return;
                }

                dispatchKey("keydown", e);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                dispatchKey("keyup", e);
            }

            @Override
            public void keyTyped(KeyEvent e) {
                if (handleControlTyped(e)) {
                    e.consume();
                }
            }
        });
    }

    public void setRootBoxSupplier(Supplier<Box> rootBoxSupplier) {
        this.rootBoxSupplier =
                rootBoxSupplier == null ? () -> null : rootBoxSupplier;
    }

    public void setBridgeSupplier(Supplier<JsBridge> bridgeSupplier) {
        this.bridgeSupplier =
                bridgeSupplier == null ? () -> null : bridgeSupplier;
    }

    public void setOnRepaint(Runnable onRepaint) {
        this.onRepaint =
                onRepaint == null ? () -> {} : onRepaint;
    }

    public void setOnInteractionChange(Runnable onInteractionChange) {
        this.onInteractionChange =
                onInteractionChange == null ? () -> {} : onInteractionChange;
    }

    public Element hoveredElement() {
        return hoveredElement;
    }

    public Element activeElement() {
        return activeElement;
    }

    public void setOnLeftClick(BiConsumer<Integer, Integer> handler) {
        this.onLeftClick = handler;
    }

    public void setOnNavigate(Consumer<String> handler) {
        this.onNavigate =
                handler == null ? href -> {} : handler;
    }

    public void setOnFormSubmit(Consumer<FormSubmission> handler) {
        this.onFormSubmit =
                handler == null ? submission -> {} : handler;
    }

    public int mouseX() {
        return mouseX;
    }

    public int mouseY() {
        return mouseY;
    }

    public boolean leftDown() {
        return leftDown;
    }

    public boolean rightDown() {
        return rightDown;
    }

    public ElementBox focusedControl() {
        return focusedControl;
    }

    // ---------------------------------------------------------------------
    // Mouse
    // ---------------------------------------------------------------------

    @Override
    public void mouseMoved(MouseEvent e) {
        updatePosition(e);
        updateHover();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        updatePosition(e);
        updateHover();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        updatePosition(e);

        if (e.getButton() == MouseEvent.BUTTON1) {
            leftDown = true;
            Element target = findElementAt(
                    rootBoxSupplier.get(), null, mouseX, mouseY);
            if (activeElement != target) {
                activeElement = target;
                onInteractionChange.run();
            }
        }

        if (e.getButton() == MouseEvent.BUTTON3) {
            rightDown = true;
        }

        surface.requestFocusInWindow();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        updatePosition(e);

        if (e.getButton() == MouseEvent.BUTTON1) {
            leftDown = false;
            if (activeElement != null) {
                activeElement = null;
                onInteractionChange.run();
            }
        }

        if (e.getButton() == MouseEvent.BUTTON3) {
            rightDown = false;
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        updatePosition(e);

        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }

        if (onLeftClick != null) {
            onLeftClick.accept(mouseX, mouseY);
        }

        Box root = rootBoxSupplier.get();

        // -------------------------------------------------------------
        // 1. Links
        // -------------------------------------------------------------

        InlineBox link = findLinkAt(root, mouseX, mouseY);

        if (link != null) {
            onNavigate.accept(link.href);
            clearFocus();
            return;
        }

        // -------------------------------------------------------------
        // 2. Form controls
        // -------------------------------------------------------------

        ElementBox control = findElementBoxAt(root, mouseX, mouseY);

        if (control != null) {
            setFocus(control);
            Element element = control.element();

            if (element != null && element.hasAttr("onclick")) {
                String onclick = element.attr("onclick");
                if (onclick.contains("location.href")) {
                    try {
                        int start = onclick.indexOf("'") + 1;
                        int end = onclick.lastIndexOf("'");
                        if (start > 0 && end > start) {
                            String targetUrl = onclick.substring(start, end);
                            onNavigate.accept(targetUrl);
                            onRepaint.run();
                            return;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (control.isButtonLike()) {

                String tag = element.tagName();
                String type = element.attr("type");

                boolean submitsForm =
                        "submit".equalsIgnoreCase(type)
                                || (
                                "button".equalsIgnoreCase(tag)
                                        && !type.isBlank()
                                        && "submit".equalsIgnoreCase(type)
                        );

                if (submitsForm) {
                    Element form = element.closest("form");

                    if (form != null) {
                        submitForm(form);
                        onRepaint.run();
                        return;
                    }
                }

                JsBridge bridge = bridgeSupplier.get();

                if (bridge != null) {
                    bridge.dispatchClick(element);
                }
            }

            onRepaint.run();
            return;
        }

        // -------------------------------------------------------------
        // 3. Generic DOM click
        // -------------------------------------------------------------

        clearFocus();

        Element target =
                findElementAt(root, null, mouseX, mouseY);

        if (target != null) {
            JsBridge bridge = bridgeSupplier.get();

            if (bridge != null) {
                bridge.dispatchClick(target);
            }
        }

        onRepaint.run();
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        updatePosition(e);
    }

    @Override
    public void mouseExited(MouseEvent e) {
        updatePosition(e);

        surface.setCursor(
                Cursor.getDefaultCursor()
        );

        boolean changed = hoveredElement != null || activeElement != null;
        hoveredElement = null;
        activeElement = null;
        if (changed) {
            onInteractionChange.run();
        }
    }

    // ---------------------------------------------------------------------
    // Keyboard → form controls
    // ---------------------------------------------------------------------

    private boolean handleControlKey(KeyEvent e) {
        if (focusedControl == null
                || !focusedControl.isTextual()) {
            return false;
        }

        int code = e.getKeyCode();

        if (code == KeyEvent.VK_BACK_SPACE) {
            String value = focusedControl.value();

            if (!value.isEmpty()) {
                focusedControl.setValue(
                        value.substring(0, value.length() - 1)
                );

                fireInputEvent();
                onRepaint.run();
            }

            return true;
        }

        if (code == KeyEvent.VK_ENTER) {
            Element element = focusedControl.element();

            if (element != null
                    && "textarea".equalsIgnoreCase(element.tagName())) {

                focusedControl.setValue(
                        focusedControl.value() + "\n"
                );

                fireInputEvent();
                onRepaint.run();

                return true;
            }

            if (element != null) {
                Element form = element.closest("form");

                if (form != null) {
                    submitForm(form);
                    onRepaint.run();
                    return true;
                }
            }

            return true;
        }

        if (code == KeyEvent.VK_ESCAPE) {
            clearFocus();
            onRepaint.run();
            return true;
        }

        return false;
    }

    private boolean handleControlTyped(KeyEvent e) {
        if (focusedControl == null
                || !focusedControl.isTextual()) {
            return false;
        }

        char c = e.getKeyChar();

        if (c == KeyEvent.CHAR_UNDEFINED
                || Character.isISOControl(c)) {
            return false;
        }

        focusedControl.setValue(
                focusedControl.value() + c
        );

        fireInputEvent();
        onRepaint.run();

        return true;
    }

    private void fireInputEvent() {
        JsBridge bridge = bridgeSupplier.get();

        if (bridge != null && focusedControl != null) {
            bridge.dispatchKey(
                    "input",
                    focusedControl.value()
            );
        }
    }

    private void setFocus(ElementBox control) {
        if (focusedControl != null
                && focusedControl != control) {
            focusedControl.setFocused(false);
        }

        boolean changed = focusedControl != control;
        focusedControl = control;

        if (control != null) {
            control.setFocused(true);
        }

        if (changed) {
            onInteractionChange.run();
        }
    }

    private void clearFocus() {
        if (focusedControl != null) {
            focusedControl.setFocused(false);
            focusedControl = null;
            onInteractionChange.run();
        }
    }

    // ---------------------------------------------------------------------
    // Keyboard → page JS
    // ---------------------------------------------------------------------

    private void dispatchKey(String type, KeyEvent e) {
        JsBridge bridge = bridgeSupplier.get();

        if (bridge != null) {
            bridge.dispatchKey(
                    type,
                    KeyEvent.getKeyText(e.getKeyCode())
            );
        }
    }

    // ---------------------------------------------------------------------
    // Form submission
    // ---------------------------------------------------------------------

    private void submitForm(Element form) {
        if (form == null) {
            return;
        }

        String baseUri = form.baseUri();

        // Only let JavaScript intercept submissions from local HTML pages.
        // Real websites (Google, etc.) use the normal native form path.
        if (baseUri != null && baseUri.startsWith("file:")) {
            JsBridge bridge = bridgeSupplier.get();

            if (bridge != null && bridge.dispatchSubmit(form)) {
                Logger.info(
                        "[INPUT] Local form submission prevented by JavaScript"
                );
                return;
            }
        }

        String action = form.absUrl("action");

        if (action.isBlank()) {
            action = form.baseUri();
        }

        if (action.isBlank()) {
            return;
        }

        String method = form.attr("method").trim();

        if (method.isBlank()) {
            method = "GET";
        }

        method = method.toUpperCase();

        StringBuilder body = new StringBuilder();

        for (Element input : form.select("input[name]")) {
            String name = input.attr("name");

            if (name.isBlank()) {
                continue;
            }

            Logger.info(
                    "[INPUT] Form field: " + name + "=" + input.val()
            );

            String type = input.attr("type");

            // Submit buttons themselves are not successful controls
            // unless they were specifically the submitter.
            if ("submit".equalsIgnoreCase(type)
                    || "button".equalsIgnoreCase(type)
                    || "reset".equalsIgnoreCase(type)) {
                continue;
            }

            // Unchecked checkbox/radio inputs are not submitted.
            if ("checkbox".equalsIgnoreCase(type)
                    || "radio".equalsIgnoreCase(type)) {

                if (!input.hasAttr("checked")) {
                    continue;
                }
            }

            if (body.length() > 0) {
                body.append('&');
            }

            body.append(
                    URLEncoder.encode(
                            name,
                            StandardCharsets.UTF_8
                    )
            );

            body.append('=');

            body.append(
                    URLEncoder.encode(
                            input.val(),
                            StandardCharsets.UTF_8
                    )
            );
        }

        FormSubmission submission =
                new FormSubmission(
                        action,
                        method,
                        body.toString()
                );

        onFormSubmit.accept(submission);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void updatePosition(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
    }

    private void updateHover() {
        Box root = rootBoxSupplier.get();

        InlineBox link =
                findLinkAt(root, mouseX, mouseY);

        ElementBox control =
                findElementBoxAt(root, mouseX, mouseY);

        boolean clickable =
                link != null
                        || (control != null
                        && control.isButtonLike());

        surface.setCursor(
                Cursor.getPredefinedCursor(
                        clickable
                                ? Cursor.HAND_CURSOR
                                : Cursor.DEFAULT_CURSOR
                )
        );

        Element next =
                findElementAt(
                        root,
                        null,
                        mouseX,
                        mouseY
                );

        if (hoveredElement != next) {
            hoveredElement = next;
            onInteractionChange.run();
        }
    }

    private InlineBox findLinkAt(
            Box box,
            float px,
            float py
    ) {
        if (box == null) {
            return null;
        }

        if (box instanceof InlineBox inline) {
            if (inline.isLink()
                    && inline.containsPoint(px, py)) {
                return inline;
            }
        }

        for (Box child : box.children) {
            InlineBox found =
                    findLinkAt(child, px, py);

            if (found != null) {
                return found;
            }
        }

        return null;
    }

    /**
     * Depth-first search for the innermost ElementBox
     * under the supplied point.
     */
    private ElementBox findElementBoxAt(Box box, float px, float py) {
        if (box == null) {
            return null;
        }

        // 1. Normal children first (innermost wins)
        for (Box child : box.children) {
            ElementBox found = findElementBoxAt(child, px, py);
            if (found != null) {
                return found;
            }
        }

        // 2. CRITICAL: form controls / inline-blocks live in blockContent, not children
        if (box instanceof InlineBox inline
                && inline.isInlineBlock()
                && inline.blockContent() != null) {

            ElementBox found = findElementBoxAt(inline.blockContent(), px, py);
            if (found != null) {
                return found;
            }
        }

        // 3. This box itself
        if (!(box instanceof ElementBox eb)) {
            return null;
        }

        boolean within =
                px >= eb.borderBoxX
                        && px <= eb.borderBoxX + eb.borderBoxWidth
                        && py >= eb.borderBoxY
                        && py <= eb.borderBoxY + eb.borderBoxHeight;

        if (!within) {
            return null;
        }

        return eb;
    }

    private Element findElementAt(
            Box box,
            Element inherited,
            float px,
            float py
    ) {
        if (box == null) {
            return null;
        }

        Element current =
                box.sourceElement != null
                        ? box.sourceElement
                        : inherited;

        for (Box child : box.children) {
            Element found =
                    findElementAt(
                            child,
                            current,
                            px,
                            py
                    );

            if (found != null) {
                return found;
            }
        }

        boolean withinBounds =
                px >= box.borderBoxX
                        && px <= box.borderBoxX + box.borderBoxWidth
                        && py >= box.borderBoxY
                        && py <= box.borderBoxY + box.borderBoxHeight;

        return withinBounds ? current : null;
    }

    // ---------------------------------------------------------------------
    // Form submission data
    // ---------------------------------------------------------------------

    public record FormSubmission(
            String action,
            String method,
            String body
    ) {}
}