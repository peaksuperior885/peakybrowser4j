package com.peak885.peakybrowser4jv2.browser.js;

import java.util.Map;

/**
 * Implemented by Java objects that scripts see as DOM-like host objects
 * (nodes, document).
 *
 * Rhino creates a NEW wrapper every time one of these objects is returned
 * to JavaScript, so an "expando" property a script assigns (Closure's
 * goog.events stores its listener map as el[someKey] = ...) must live on
 * the host object itself, not on the wrapper - otherwise the next wrapper of
 * the same node can't see it and Closure code later dereferences undefined.
 */
interface ExpandoHost {
    Map<String, Object> expandoMap();
}