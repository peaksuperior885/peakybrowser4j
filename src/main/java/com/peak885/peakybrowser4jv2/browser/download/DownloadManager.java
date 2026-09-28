package com.peak885.peakybrowser4jv2.browser.download;

import com.peak885.peakybrowser4jv2.browser.chooser.FileChooser;
import com.peak885.peakybrowser4jv2.browser.http.HttpManager;
import com.peak885.peakybrowser4jv2.browser.image.SvgIconLoader;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.Nullable;
import org.tinylog.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class DownloadManager {

    private final HttpManager http;

    public DownloadManager(HttpManager http) {
        this.http = http;
    }

    public void download(Component parent, String url, @Nullable String suggestedFileName) {
        if (url == null || url.isBlank()) {
            Logger.warn("[DOWNLOAD] Refusing to download empty URL");
            return;
        }

        String fileName = (suggestedFileName == null || suggestedFileName.isBlank())
                ? guessFileName(url)
                : suggestedFileName;

        File target = FileChooser.showSaveDialog(parent, "Save File", null, fileName);
        if (target == null) {
            Logger.info("[DOWNLOAD] User cancelled save dialog: {}", url);
            return;
        }

        // Ensure parent directory exists
        File parentDir = target.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parentDir.mkdirs();
        }

        DownloadDialog dialog = new DownloadDialog(parent, target.getName());
        File finalTarget = target;

        // Keep a reference so cancel can close the live Response
        AtomicReference<Response> liveResponse = new AtomicReference<>();

        Thread downloadThread = new Thread(() -> {
            try {
                downloadToFile(url, finalTarget, dialog, liveResponse);
                if (!dialog.isCancelled()) {
                    SwingUtilities.invokeLater(dialog::completed);
                    Logger.info("[DOWNLOAD] Completed: {} -> {}", url, finalTarget.getAbsolutePath());
                }
            } catch (Exception e) {
                if (dialog.isCancelled()) {
                    Logger.info("[DOWNLOAD] Cancelled: {}", url);
                    deletePartialFile(finalTarget);
                    SwingUtilities.invokeLater(dialog::cancelled);
                } else {
                    Logger.error(e, "[DOWNLOAD] Failed: {}", url);
                    deletePartialFile(finalTarget);
                    SwingUtilities.invokeLater(() -> dialog.failed(e));
                }
            } finally {
                Response r = liveResponse.getAndSet(null);
                if (r != null) {
                    r.close();
                }
            }
        }, "PeakyBrowser-Download");

        downloadThread.setDaemon(true);
        downloadThread.start();

        // Wire cancel → close the stream so the read() unblocks
        dialog.setOnCancel(() -> {
            Response r = liveResponse.get();
            if (r != null) {
                r.close();          // aborts the body stream
            }
            downloadThread.interrupt();
        });

        dialog.showDialog();
    }

    public void download(Component parent, String url) {
        download(parent, url, null);
    }

    private void downloadToFile(
            String url,
            File target,
            DownloadDialog dialog,
            AtomicReference<Response> liveResponse
    ) throws IOException {

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", com.peak885.peakybrowser4jv2.browser.http.HttpManager.USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        Logger.info("[DOWNLOAD] Starting: {}", url);

        Response response = http.executeStreaming(request);
        liveResponse.set(response);

        try {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Response has no body");
            }

            long totalBytes = body.contentLength();
            SwingUtilities.invokeLater(() -> dialog.setTotalBytes(totalBytes));

            try (InputStream input = body.byteStream();
                 OutputStream output = Files.newOutputStream(
                         target.toPath(),
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {

                byte[] buffer = new byte[64 * 1024];
                long downloadedBytes = 0;
                int read;

                while ((read = input.read(buffer)) != -1) {
                    if (dialog.isCancelled() || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }

                    output.write(buffer, 0, read);
                    downloadedBytes += read;

                    final long current = downloadedBytes;
                    SwingUtilities.invokeLater(() -> {
                        if (!dialog.isCancelled()) {
                            dialog.setDownloadedBytes(current);
                        }
                    });
                }

                output.flush();
            }
        } finally {
            // Response is closed in the finally of the download thread
        }
    }

    private void deletePartialFile(File file) {
        try {
            if (file != null && file.exists()) {
                Files.deleteIfExists(file.toPath());
            }
        } catch (IOException e) {
            Logger.warn("[DOWNLOAD] Could not delete partial file: {}", file);
        }
    }

    private String guessFileName(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null && !path.isBlank()) {
                String name = new File(path).getName();
                if (!name.isBlank()) {
                    int q = name.indexOf('?');
                    if (q > 0) name = name.substring(0, q);
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return "download";
    }

    public HttpManager getHttpManager() {
        return http;
    }

    // -------------------------------------------------------------------------
    // Enhanced Download dialog with SVG Support & Modern UI
    // -------------------------------------------------------------------------

    private static final class DownloadDialog {

        private final JDialog dialog;
        private final JLabel iconLabel;
        private final JLabel fileLabel;
        private final JLabel statusLabel;
        private final JLabel sizeLabel;
        private final JProgressBar progressBar;
        private final JButton cancelButton;

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private long totalBytes = -1;
        private Runnable onCancel;

        // Load SVGs via your SvgIconLoader (Ensure these paths match your resource structure)
        private final Icon downloadIcon = SvgIconLoader.load("/icons/download.svg", 32);
        private final Icon successIcon  = SvgIconLoader.load("/icons/check.svg", 32);
        private final Icon errorIcon    = SvgIconLoader.load("/icons/error.svg", 32);
        private final Icon cancelIcon   = SvgIconLoader.load("/icons/cancel.svg", 32);

        private DownloadDialog(Component parent, String fileName) {
            Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);

            dialog = new JDialog(owner, "Downloading File", Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

            // Initialize UI components with modern font scaling
            iconLabel = new JLabel(downloadIcon);

            fileLabel = new JLabel(fileName);
            fileLabel.setFont(fileLabel.getFont().deriveFont(Font.BOLD, 13f));

            statusLabel = new JLabel("Starting download...");
            statusLabel.setForeground(new Color(100, 100, 100));

            sizeLabel = new JLabel("Preparing...");
            sizeLabel.setFont(sizeLabel.getFont().deriveFont(11f));
            sizeLabel.setForeground(new Color(120, 120, 120));

            progressBar = new JProgressBar(0, 100);
            progressBar.setValue(0);
            progressBar.setStringPainted(true);
            progressBar.setPreferredSize(new Dimension(300, 22));

            cancelButton = new JButton("Cancel");
            cancelButton.setFocusPainted(false);
            cancelButton.addActionListener(e -> cancel());

            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    if (!cancelled.get() && cancelButton.isEnabled()
                            && !"Close".equals(cancelButton.getText())) {
                        cancel();
                    } else {
                        dialog.dispose();
                    }
                }
            });

            // Modern container setup with clean padding (EmptyBorder)
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(new EmptyBorder(16, 16, 16, 16));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 8, 4, 8);

            // Row 0: Icon (spanning multiple rows) & File Name
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridheight = 2;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            panel.add(iconLabel, gbc);

            gbc.gridx = 1;
            gbc.gridy = 0;
            gbc.gridheight = 1;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(fileLabel, gbc);

            // Row 1: Status Label
            gbc.gridy = 1;
            panel.add(statusLabel, gbc);

            // Row 2: Progress Bar
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.gridwidth = 2;
            gbc.insets = new Insets(12, 8, 4, 8);
            panel.add(progressBar, gbc);

            // Row 3: Size Info & Cancel Button wrapper layout
            gbc.gridy = 3;
            gbc.insets = new Insets(4, 8, 0, 8);

            JPanel bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(sizeLabel, BorderLayout.WEST);
            bottomPanel.add(cancelButton, BorderLayout.EAST);

            panel.add(bottomPanel, gbc);

            dialog.setContentPane(panel);
            dialog.pack();
            dialog.setResizable(false);
            dialog.setLocationRelativeTo(parent);
        }

        void setOnCancel(Runnable onCancel) {
            this.onCancel = onCancel;
        }

        private void showDialog() {
            SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        }

        private void setTotalBytes(long totalBytes) {
            this.totalBytes = totalBytes;
            if (totalBytes <= 0) {
                progressBar.setIndeterminate(true);
                progressBar.setString("Downloading...");
                sizeLabel.setText("Unknown file size");
            }
        }

        private void setDownloadedBytes(long downloadedBytes) {
            if (cancelled.get()) return;

            if (totalBytes > 0) {
                int percent = (int) Math.min(100, (downloadedBytes * 100L) / totalBytes);
                progressBar.setIndeterminate(false);
                progressBar.setValue(percent);
                progressBar.setString(percent + "%");
                sizeLabel.setText(formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes));
            } else {
                progressBar.setIndeterminate(true);
                sizeLabel.setText(formatBytes(downloadedBytes) + " downloaded");
            }
            statusLabel.setText("Downloading...");
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;

            cancelButton.setEnabled(false);
            cancelButton.setText("Cancelling...");
            statusLabel.setText("Cancelling download...");

            if (onCancel != null) {
                onCancel.run();
            }
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private void completed() {
            iconLabel.setIcon(successIcon);
            progressBar.setIndeterminate(false);
            progressBar.setValue(100);
            progressBar.setString("Complete");
            statusLabel.setText("Download finished successfully");
            cancelButton.setText("Close");
            replaceButtonAction(dialog::dispose);
            dialog.pack();
        }

        private void cancelled() {
            iconLabel.setIcon(cancelIcon);
            progressBar.setIndeterminate(false);
            progressBar.setValue(0);
            progressBar.setString("Cancelled");
            statusLabel.setText("Download was cancelled");
            sizeLabel.setText("Partial file removed");
            cancelButton.setText("Close");
            replaceButtonAction(dialog::dispose);
            dialog.pack();
        }

        private void failed(Exception exception) {
            iconLabel.setIcon(errorIcon);
            progressBar.setIndeterminate(false);
            progressBar.setValue(0);
            progressBar.setString("Failed");
            statusLabel.setText("Download failed");
            String message = exception.getMessage();
            sizeLabel.setText(message == null ? "Unknown error" : message);
            cancelButton.setText("Close");
            replaceButtonAction(dialog::dispose);
            dialog.pack();
        }

        private void replaceButtonAction(Runnable action) {
            for (var listener : cancelButton.getActionListeners()) {
                cancelButton.removeActionListener(listener);
            }
            cancelButton.setEnabled(true);
            cancelButton.addActionListener(e -> action.run());
        }

        private static String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024L * 1024L)
                return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}