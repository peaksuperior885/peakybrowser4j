package com.peak885.peakybrowser4j.browser.util;

public class ColorParser {

    public static float[] parse(String cssColor) {

        if (cssColor == null)
            return white();

        cssColor = cssColor.trim().toLowerCase();

        try {

            // #RGB / #RRGGBB
            if (cssColor.startsWith("#")) {

                String hex = cssColor.substring(1);

                if (hex.length() == 3) {
                    hex =
                            "" + hex.charAt(0) + hex.charAt(0) +
                                    hex.charAt(1) + hex.charAt(1) +
                                    hex.charAt(2) + hex.charAt(2);
                }

                if (hex.length() == 6) {
                    int rgb = Integer.parseInt(hex, 16);

                    return new float[]{
                            ((rgb >> 16) & 0xFF) / 255f,
                            ((rgb >> 8) & 0xFF) / 255f,
                            (rgb & 0xFF) / 255f,
                            1f
                    };
                }
            }

            // rgb(...)
            if (cssColor.startsWith("rgb(")) {

                String[] p = cssColor.substring(4, cssColor.length() - 1).split(",");

                return new float[]{
                        Integer.parseInt(p[0].trim()) / 255f,
                        Integer.parseInt(p[1].trim()) / 255f,
                        Integer.parseInt(p[2].trim()) / 255f,
                        1f
                };
            }

            // rgba(...)
            if (cssColor.startsWith("rgba(")) {

                String[] p = cssColor.substring(5, cssColor.length() - 1).split(",");

                return new float[]{
                        Integer.parseInt(p[0].trim()) / 255f,
                        Integer.parseInt(p[1].trim()) / 255f,
                        Integer.parseInt(p[2].trim()) / 255f,
                        Float.parseFloat(p[3].trim())
                };
            }

            switch (cssColor) {
                case "white":
                    return new float[]{1,1,1,1};

                case "black":
                    return new float[]{0,0,0,1};

                case "red":
                    return new float[]{1,0,0,1};

                case "green":
                    return new float[]{0,1,0,1};

                case "blue":
                    return new float[]{0,0,1,1};

                case "transparent":
                    return new float[]{0,0,0,0};

                case "currentcolor":
                    return new float[]{0,0,0,1}; // placeholder

                case "inherit":
                    return white(); // placeholder until inheritance exists
            }

        } catch (Exception e) {
            System.out.println("Failed to parse color: " + cssColor);
        }

        return white();
    }

    private static float[] white() {
        return new float[]{1f,1f,1f,1f};
    }
}