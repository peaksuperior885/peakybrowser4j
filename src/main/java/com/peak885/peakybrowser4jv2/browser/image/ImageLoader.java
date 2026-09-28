package com.peak885.peakybrowser4jv2.browser.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;

public final class ImageLoader {

    public ImageData load(InputStream input, String contentType) throws IOException {
        if (contentType != null &&
                contentType.toLowerCase().startsWith("image/gif")) {

            return GifImageLoader.load(input);
        }

        BufferedImage image = ImageIO.read(input);

        if (image == null) {
            throw new IOException("Unsupported image format");
        }

        return ImageData.staticImage(image);
    }
}