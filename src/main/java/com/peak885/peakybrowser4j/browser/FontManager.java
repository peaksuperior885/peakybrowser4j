package com.peak885.peakybrowser4j.browser;

import org.lwjgl.nanovg.NanoVG;
import org.tinylog.Logger;
import java.nio.file.*;

public class FontManager {
    public static final String SEGOE_UI = "segoeui";

    public static void loadFonts(long vg) {
        load(vg, SEGOE_UI, "/fonts/segoeui.ttf");
    }

    private static void load(long vg, String name, String resourcePath) {
        try (var stream = FontManager.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                Logger.error("Font not found: {}", resourcePath);
                return;
            }
            Path temp = Files.createTempFile(name, ".ttf");
            Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING);
            int id = NanoVG.nvgCreateFont(vg, name, temp.toAbsolutePath().toString());

            if (id == -1) Logger.error("Failed to register font: {}", name);
            else Logger.info("Font '{}' registered successfully.", name);
        } catch (Exception e) {
            Logger.error("Error loading font {}: ", name, e);
        }
    }
}