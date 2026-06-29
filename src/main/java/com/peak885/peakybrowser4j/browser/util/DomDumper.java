package com.peak885.peakybrowser4j.browser.util;

import com.peak885.peakybrowser4j.browser.Node;
import java.io.FileWriter;
import java.io.IOException;

public class DomDumper {
    public static void dump(Node root, String filename) {
        try (FileWriter writer = new FileWriter(System.getProperty("user.home") + "/Downloads/" + filename)) {
            writeNode(writer, root, 0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeNode(FileWriter writer, Node node, int depth) throws IOException {
        String indent = "  ".repeat(depth);
        writer.write(indent + "Node: <" + node.tag + "> | Classes: " + node.classes + "\n");
        writer.write(indent + "  Styles: " + node.computedStyle + "\n");
        writer.write(indent + "  Bounds: " + node.width + "x" + node.height + " at (" + node.x + "," + node.y + ")\n");

        for (Node child : node.children) {
            writeNode(writer, child, depth + 1);
        }
    }
}