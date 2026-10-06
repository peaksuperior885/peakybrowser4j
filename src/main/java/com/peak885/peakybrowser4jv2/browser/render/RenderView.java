package com.peak885.peakybrowser4jv2.browser.render;

import com.peak885.peakybrowser4jv2.browser.BrowserLoader;
import com.peak885.peakybrowser4jv2.browser.input.Inputs;
import com.peak885.peakybrowser4jv2.browser.js.JsBridge;
import com.peak885.peakybrowser4jv2.browser.render.layout.Box;
import com.peak885.peakybrowser4jv2.browser.render.layout.ElementBox;
import com.peak885.peakybrowser4jv2.browser.render.layout.LayoutEngine;
import com.peak885.peakybrowser4jv2.browser.render.style.StyleResolver;

import org.jsoup.nodes.Document;
import org.tinylog.Logger;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

public final class RenderView
        extends JPanel
        implements Scrollable {

    private final StyleResolver styleResolver =
            new StyleResolver();
    private Consumer<Inputs.FormSubmission> onFormSubmit =
            submission -> {};

    private final LayoutEngine layoutEngine;

    private final Inputs inputs;

    private Box rootBox;
    private Document currentDocument;
    private JsBridge jsBridge;

    private Consumer<String> onNavigate =
            url -> {};

    // Fires whenever a background relayout (triggered below) finishes and
    // is applied - lets BrowserWindow know to e.g. nudge the loading bar.
    private Runnable onRelayoutApplied = () -> {};

    private boolean isLayoutDirty = false;
    private boolean relayoutInFlight = false;
    private final Timer layoutBatchTimer;

    public RenderView(BrowserLoader browserLoader) {

        layoutEngine =
                new LayoutEngine(
                        styleResolver,
                        browserLoader
                );

        setBackground(Color.WHITE);
        setOpaque(true);

        // Layout building can hit the network per <img>/<link rel=stylesheet>
        // (see LayoutEngine/StyleResolver), so this must never run inline on
        // the EDT - it's kicked off as a background task, see scheduleRelayout().
        layoutBatchTimer = new Timer(16, e -> {
            if (isLayoutDirty && currentDocument != null && !relayoutInFlight) {
                isLayoutDirty = false;
                scheduleRelayout();
            }
        });
        layoutBatchTimer.setRepeats(true);
        layoutBatchTimer.start();

        // Inputs owns all mouse/keyboard handling for this surface: link
        // hover/navigation, and dispatching real "click"/"keydown"/"keyup"
        // DOM events into whatever page script is currently loaded.
        inputs = new Inputs(this);
        inputs.setRootBoxSupplier(() -> rootBox);
        inputs.setBridgeSupplier(() -> jsBridge);
        inputs.setOnNavigate(url -> onNavigate.accept(url));
        inputs.setOnFormSubmit(submission -> onFormSubmit.accept(submission)); //<--
        inputs.setOnRepaint(this::repaint);
        inputs.setOnInteractionChange(this::restyleForInteraction);
    }

    /**
     * Updates StyleResolver interaction targets and re-resolves styles on
     * the existing box tree so :hover / :active / :focus rules take effect
     * without a full layout rebuild.
     */
    private void restyleForInteraction() {
        if (rootBox == null) {
            return;
        }
        styleResolver.setHoveredElement(inputs.hoveredElement());
        styleResolver.setActiveElement(inputs.activeElement());
        ElementBox focused = inputs.focusedControl();
        styleResolver.setFocusedElement(
                focused != null ? focused.element() : null
        );
        layoutEngine.restyle(rootBox);
        repaint();
    }

    public void setOnNavigate(
            Consumer<String> onNavigate
    ) {
        this.onNavigate =
                onNavigate == null
                        ? url -> {}
                        : onNavigate;
    }

    public void setOnFormSubmit(
            Consumer<Inputs.FormSubmission> onFormSubmit
    ) {
        this.onFormSubmit =
                onFormSubmit == null
                        ? submission -> {}
                        : onFormSubmit;
    }

    public void setOnRelayoutApplied(Runnable onRelayoutApplied) {
        this.onRelayoutApplied = onRelayoutApplied == null ? () -> {} : onRelayoutApplied;
    }

    /** The live JsBridge for the currently-loaded page, so clicks/keys can reach its JS environment. Pass null when clearing. */
    public void setJsBridge(JsBridge jsBridge) {
        this.jsBridge = jsBridge;
    }

    /**
     * Builds a box tree for the given document/width. Pure and network-bound
     * (images, external stylesheets) but touches no Swing state, so it is
     * safe - and expected - to call this from a background thread. Pair
     * with {@link #applyLoadedPage(LoadedPage)} on the EDT afterwards.
     */
    public Box buildLayout(Document document, float viewportWidth) {
        return layoutEngine.build(document, viewportWidth);
    }

    /** Must be called on the EDT. Installs an already-built page (see {@link #buildLayout}) and repaints. */
    public void applyLoadedPage(LoadedPage page) {
        inputs.clearTextSelection();
        JsBridge previousBridge = this.jsBridge;
        this.currentDocument = page.document();
        this.rootBox = page.rootBox();
        this.jsBridge = page.jsBridge();
        // The page being replaced must stop running: its timers/intervals would otherwise
        // keep firing against a document nobody can see.
        if (previousBridge != null && previousBridge != page.jsBridge()) {
            previousBridge.close();
        }
        styleResolver.setHoveredElement(null);
        styleResolver.setActiveElement(null);
        styleResolver.setFocusedElement(null);

        revalidate();
        repaint();
    }

    /** Marks the current page's layout stale (e.g. after a JS DOM mutation); picked up by the batch timer and rebuilt off the EDT. */
    public void invalidateLayout() {
        isLayoutDirty = true;
    }

    public void clear() {
        inputs.clearTextSelection();
        rootBox = null;
        currentDocument = null;
        if (jsBridge != null) {
            jsBridge.close();
        }
        jsBridge = null;
        isLayoutDirty = false;
        styleResolver.setHoveredElement(null);
        styleResolver.setActiveElement(null);
        styleResolver.setFocusedElement(null);

        revalidate();
        repaint();
    }

    /** Rebuilds the box tree off the EDT and swaps it in once done - used for both JS-triggered relayout and window resize. */
    private void scheduleRelayout() {

        if (currentDocument == null || relayoutInFlight) {
            return;
        }

        relayoutInFlight = true;
        isLayoutDirty = false;

        final Document doc = currentDocument;
        int panelWidth = getWidth();
        final float width = panelWidth > 0 ? panelWidth : 800f;

        new SwingWorker<Box, Void>() {
            @Override
            protected Box doInBackground() {
                return buildLayout(doc, width);
            }

            @Override
            protected void done() {
                relayoutInFlight = false;

                // The page may have navigated away while this was running -
                // don't stomp on whatever loaded in the meantime.
                if (doc != currentDocument) {
                    return;
                }

                try {
                    inputs.clearTextSelection();
                    rootBox = get();
                    revalidate();
                    repaint();
                    onRelayoutApplied.run();
                } catch (Exception e) {
                    Logger.warn(e, "Background relayout failed");
                }
            }
        }.execute();
    }

    @Override
    protected void paintComponent(
            Graphics g
    ) {
        super.paintComponent(g);

        if (rootBox == null) {
            return;
        }

        Graphics2D g2 =
                (Graphics2D) g.create();

        g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );

        rootBox.paint(g2);

        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {

        if (rootBox == null) {
            return new Dimension(
                    800,
                    600
            );
        }

        return new Dimension(
                Math.round(
                        rootBox.borderBoxWidth + 20
                ),
                Math.round(
                        rootBox.totalHeight() + 20
                )
        );
    }

    @Override
    public void setBounds(
            int x,
            int y,
            int width,
            int height
    ) {
        boolean widthChanged =
                getWidth() != width;

        super.setBounds(
                x,
                y,
                width,
                height
        );

        if (widthChanged
                && currentDocument != null) {

            isLayoutDirty = true;
            scheduleRelayout();
        }
    }

    @Override
    public Dimension
    getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
            Rectangle visibleRect,
            int orientation,
            int direction
    ) {
        return 32;
    }

    @Override
    public int getScrollableBlockIncrement(
            Rectangle visibleRect,
            int orientation,
            int direction
    ) {
        return orientation == SwingConstants.VERTICAL
                ? visibleRect.height
                : visibleRect.width;
    }

    @Override
    public boolean
    getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean
    getScrollableTracksViewportHeight() {
        return false;
    }

    /** The result of a fully off-EDT page build: parsed doc, laid-out box tree, and the page's JS environment. */
    public record LoadedPage(Document document, Box rootBox, JsBridge jsBridge) {
    }

    public StyleResolver getStyleResolver() {
        return styleResolver;
    }
}