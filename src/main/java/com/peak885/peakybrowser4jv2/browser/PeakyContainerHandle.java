package com.peak885.peakybrowser4jv2.browser;

import org.spongepowered.asm.launch.platform.container.IContainerHandle;

import java.util.Collection;
import java.util.Collections;

public final class PeakyContainerHandle implements IContainerHandle {

    private static final String ID = "peakybrowser";

    @Override
    public String getAttribute(String name) {
        // Mixin looks up mixin configs / agent attributes via this.
        // Returning null for unknown keys is fine; empty id was the crash.
        if ("MixinConfigs".equals(name)) {
            return "peakybrowser.mixins.json";
        }
        return null;
    }

    @Override
    public Collection<IContainerHandle> getNestedContainers() {
        return Collections.emptyList();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDescription() {
        return "PeakyBrowser4J V2";
    }
}
