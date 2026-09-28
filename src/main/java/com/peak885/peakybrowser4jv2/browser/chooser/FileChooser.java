package com.peak885.peakybrowser4jv2.browser.chooser;

import com.peak885.peakybrowser4jv2.browser.image.SvgIconLoader;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.Nullable;
import org.tinylog.Logger;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileSystemView;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public final class FileChooser extends JDialog {

    public enum Mode { OPEN_FILE, SAVE_FILE, SELECT_FOLDER }

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy hh:mm a");

    public static File showOpenDialog(Component parent, String title, ExtensionFilter... filters) {
        return showOpenDialog(parent, title, null, filters);
    }

    public static File showOpenDialog(Component parent, String title, @Nullable File initialDir, ExtensionFilter... filters) {
        FileChooser chooser = new FileChooser(toWindow(parent), Mode.OPEN_FILE, title, initialDir, List.of(filters));
        chooser.setVisible(true);
        return chooser.approved ? chooser.selectedFile : null;
    }

    public static File showSaveDialog(Component parent, String title, @Nullable String initialFileName, ExtensionFilter... filters) {
        return showSaveDialog(parent, title, null, initialFileName, filters);
    }

    public static File showSaveDialog(Component parent, String title, @Nullable File initialDir,
                                      @Nullable String initialFileName, ExtensionFilter... filters) {
        FileChooser chooser = new FileChooser(toWindow(parent), Mode.SAVE_FILE, title, initialDir, List.of(filters));
        if (initialFileName != null && !initialFileName.isBlank()) {
            chooser.fileNameField.setText(initialFileName);
        }
        chooser.setVisible(true);
        return chooser.approved ? chooser.selectedFile : null;
    }

    public static File showFolderDialog(Component parent, String title) {
        return showFolderDialog(parent, title, null);
    }

    public static File showFolderDialog(Component parent, String title, @Nullable File initialDir) {
        FileChooser chooser = new FileChooser(toWindow(parent), Mode.SELECT_FOLDER, title, initialDir, List.of());
        chooser.setVisible(true);
        return chooser.approved ? chooser.selectedFile : null;
    }

    private static Window toWindow(Component c) {
        if (c == null) return null;
        return (c instanceof Window w) ? w : SwingUtilities.getWindowAncestor(c);
    }

    private final Mode mode;
    private final List<ExtensionFilter> filters;
    private final FileSystemView fsv = FileSystemView.getFileSystemView();

    private final Deque<File> backStack = new ArrayDeque<>();
    private final Deque<File> forwardStack = new ArrayDeque<>();
    private File currentDir;
    private ExtensionFilter currentFilter = ExtensionFilter.ALL_FILES;

    private File selectedFile;
    private boolean approved = false;

    private JButton backButton, forwardButton, upButton, refreshButton, newFolderButton;
    private JTextField pathField;
    private JTextField searchField;
    private JCheckBox showHiddenCheckbox;
    private JList<SidebarItem> sidebarList;
    private JTable table;
    private FileTableModel tableModel;
    private TableRowSorter<FileTableModel> sorter;
    private JLabel statusLabel;
    private JTextField fileNameField;
    private JComboBox<ExtensionFilter> filterCombo;
    private JPanel fileNamePanel;
    private JPanel filterPanel;
    private JButton acceptButton, cancelButton;

    public FileChooser(Window owner, Mode mode, String title, @Nullable File initialDirectory, List<ExtensionFilter> filters) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.mode = mode;
        this.filters = filters;

        initComponents();
        layoutComponents();
        registerListeners();
        populateFilterCombo();
        applyModeVisibility();

        File startDir = (initialDirectory != null && initialDirectory.isDirectory())
                ? initialDirectory
                : new File(System.getProperty("user.home"));
        setCurrentDirectoryInternal(startDir);

        setSize(880, 580);
        setMinimumSize(new Dimension(640, 420));
        setLocationRelativeTo(owner);

        getRootPane().registerKeyboardAction(e -> {
            approved = false;
            dispose();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(acceptButton);
    }

    private void initComponents() {
        backButton = toolbarButton(SvgIconLoader.load("/icons/back.svg", 18), "Back");
        forwardButton = toolbarButton(SvgIconLoader.load("/icons/forward.svg", 18), "Forward");
        upButton = toolbarButton(SvgIconLoader.load("/icons/up.svg", 18), "Up one level");
        refreshButton = toolbarButton(SvgIconLoader.load("/icons/reload.svg", 18), "Refresh");

        newFolderButton = new JButton("New Folder");
        newFolderButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        backButton.setEnabled(false);
        forwardButton.setEnabled(false);

        pathField = new JTextField();
        pathField.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search this folder...");
        searchField.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

        showHiddenCheckbox = new JCheckBox("Show hidden files");
        showHiddenCheckbox.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        sidebarList = new JList<>();
        sidebarList.setCellRenderer(new SidebarRenderer());
        sidebarList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sidebarList.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        buildSidebarModel();

        tableModel = new FileTableModel();
        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setColumnSelectionAllowed(false);
        table.setRowSelectionAllowed(true);
        table.setAutoCreateRowSorter(false);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameCellRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(340);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(150);

        // Web-like pointer cursor when hovering over table rows
        table.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else {
                    table.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                table.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }
        });

        sorter = new TableRowSorter<>(tableModel);
        sorter.setComparator(0, (a, b) -> {
            FileEntry fa = (FileEntry) a;
            FileEntry fb = (FileEntry) b;
            if (fa.directory != fb.directory) return fa.directory ? -1 : 1;
            return fa.file.getName().compareToIgnoreCase(fb.file.getName());
        });
        sorter.setSortKeys(java.util.List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        table.setRowSorter(sorter);

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        fileNameField = new JTextField();
        fileNameField.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

        filterCombo = new JComboBox<>();
        filterCombo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        acceptButton = new JButton(acceptLabelForMode());
        acceptButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        cancelButton = new JButton("Cancel");
        cancelButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private JButton toolbarButton(Icon icon, String tooltip) {
        JButton b = new JButton(icon);
        b.setToolTipText(tooltip);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private String acceptLabelForMode() {
        return switch (mode) {
            case OPEN_FILE -> "Open";
            case SAVE_FILE -> "Save";
            case SELECT_FOLDER -> "Select Folder";
        };
    }

    private void layoutComponents() {
        JPanel content = new JPanel(new MigLayout("insets 10, fill, wrap 1", "[grow]", "[]6[grow]8[]8[]"));
        setContentPane(content);

        // --- toolbar row ---
        JPanel toolbar = new JPanel(new MigLayout("insets 0, fillx", "[][][][]10[grow,fill]8[160!]", "[]"));
        toolbar.add(backButton);
        toolbar.add(forwardButton);
        toolbar.add(upButton);
        toolbar.add(refreshButton);
        toolbar.add(pathField, "growx");
        toolbar.add(searchField, "growx");
        content.add(toolbar, "growx");

        // --- main split: sidebar | table ---
        JScrollPane sidebarScroll = new JScrollPane(sidebarList);
        sidebarScroll.setPreferredSize(new Dimension(180, 100));
        JScrollPane tableScroll = new JScrollPane(table);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebarScroll, tableScroll);
        split.setDividerLocation(180);
        split.setResizeWeight(0.0);
        content.add(split, "grow");

        // --- status + hidden-files row ---
        JPanel statusRow = new JPanel(new MigLayout("insets 0, fillx", "[grow][]", "[]"));
        statusRow.add(statusLabel, "growx");
        statusRow.add(showHiddenCheckbox);
        content.add(statusRow, "growx");

        // --- bottom: filename / filter / buttons ---
        JPanel bottom = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow]", "[]4[]4[]"));

        fileNamePanel = new JPanel(new MigLayout("insets 0, fillx", "[100!][grow,fill]", "[]"));
        fileNamePanel.add(new JLabel("File name:"));
        fileNamePanel.add(fileNameField, "growx");
        bottom.add(fileNamePanel, "growx");

        filterPanel = new JPanel(new MigLayout("insets 0, fillx", "[100!][grow,fill]", "[]"));
        filterPanel.add(new JLabel("Files of type:"));
        filterPanel.add(filterCombo, "growx");
        bottom.add(filterPanel, "growx");

        JPanel buttonRow = new JPanel(new MigLayout("insets 0, fillx", "[grow][][]", "[]"));
        buttonRow.add(new JLabel(), "growx");
        buttonRow.add(cancelButton);
        buttonRow.add(acceptButton, "gapleft 6");
        bottom.add(buttonRow, "growx");

        content.add(bottom, "growx");

        JPanel newFolderRow = new JPanel(new MigLayout("insets 0", "[]", "[]"));
        newFolderRow.add(newFolderButton);
        toolbar.add(newFolderRow, "wrap, span");
    }

    private void applyModeVisibility() {
        boolean folderMode = mode == Mode.SELECT_FOLDER;
        fileNamePanel.setVisible(!folderMode);
        filterPanel.setVisible(!folderMode);
    }

    // ---- listeners -----------------------------------------------------------

    private void registerListeners() {
        backButton.addActionListener(e -> navigateBack());
        forwardButton.addActionListener(e -> navigateForward());
        upButton.addActionListener(e -> {
            File parent = currentDir.getParentFile();
            if (parent != null) navigateTo(parent, true);
        });
        refreshButton.addActionListener(e -> loadDirectoryAsync(currentDir));
        newFolderButton.addActionListener(e -> createNewFolder());
        showHiddenCheckbox.addItemListener(e -> loadDirectoryAsync(currentDir));

        pathField.addActionListener(e -> {
            String text = pathField.getText().trim();
            File target = new File(text);
            if (target.isDirectory()) {
                navigateTo(target, true);
            } else {
                Toolkit.getDefaultToolkit().beep();
                pathField.setText(currentDir.getAbsolutePath());
            }
        });

        onChange(searchField, this::applySearchFilter);

        sidebarList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            SidebarItem item = sidebarList.getSelectedValue();
            if (item != null && item.file != null && item.file.isDirectory()) {
                navigateTo(item.file, true);
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    if (viewRow < 0) return;
                    int modelRow = table.convertRowIndexToModel(viewRow);
                    FileEntry fe = tableModel.getEntryAt(modelRow);
                    if (fe.directory) {
                        navigateTo(fe.file, true);
                    } else if (mode == Mode.OPEN_FILE) {
                        accept();
                    } else if (mode == Mode.SAVE_FILE) {
                        fileNameField.setText(fe.file.getName());
                    }
                }
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || mode == Mode.SELECT_FOLDER) return;
            int row = table.getSelectedRow();
            if (row < 0) return;
            FileEntry fe = tableModel.getEntryAt(table.convertRowIndexToModel(row));
            if (!fe.directory) fileNameField.setText(fe.file.getName());
        });

        fileNameField.addActionListener(e -> accept());
        acceptButton.addActionListener(e -> accept());
        cancelButton.addActionListener(e -> {
            approved = false;
            dispose();
        });
    }

    private static void onChange(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { action.run(); }
            public void removeUpdate(DocumentEvent e) { action.run(); }
            public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }

    // ---- navigation ------------------------------------------------------

    private void setCurrentDirectoryInternal(File dir) {
        backStack.clear();
        forwardStack.clear();
        currentDir = dir;
        pathField.setText(dir.getAbsolutePath());
        backButton.setEnabled(false);
        forwardButton.setEnabled(false);
        upButton.setEnabled(dir.getParentFile() != null);
        loadDirectoryAsync(dir);
    }

    private void navigateTo(File dir, boolean pushHistory) {
        if (dir == null || !dir.isDirectory()) return;
        if (pushHistory && currentDir != null && !dir.equals(currentDir)) {
            backStack.push(currentDir);
            forwardStack.clear();
        }
        currentDir = dir;
        pathField.setText(dir.getAbsolutePath());
        backButton.setEnabled(!backStack.isEmpty());
        forwardButton.setEnabled(!forwardStack.isEmpty());
        upButton.setEnabled(dir.getParentFile() != null);
        searchField.setText("");
        loadDirectoryAsync(dir);
    }

    private void navigateBack() {
        if (backStack.isEmpty()) return;
        forwardStack.push(currentDir);
        File dir = backStack.pop();
        currentDir = dir;
        pathField.setText(dir.getAbsolutePath());
        backButton.setEnabled(!backStack.isEmpty());
        forwardButton.setEnabled(true);
        upButton.setEnabled(dir.getParentFile() != null);
        loadDirectoryAsync(dir);
    }

    private void navigateForward() {
        if (forwardStack.isEmpty()) return;
        backStack.push(currentDir);
        File dir = forwardStack.pop();
        currentDir = dir;
        pathField.setText(dir.getAbsolutePath());
        backButton.setEnabled(true);
        forwardButton.setEnabled(!forwardStack.isEmpty());
        upButton.setEnabled(dir.getParentFile() != null);
        loadDirectoryAsync(dir);
    }

    private void loadDirectoryAsync(File dir) {
        if (dir == null) return;
        statusLabel.setText("Loading...");
        table.setEnabled(false);
        new SwingWorker<List<FileEntry>, Void>() {
            @Override
            protected List<FileEntry> doInBackground() {
                boolean showHidden = showHiddenCheckbox.isSelected();
                File[] files = dir.listFiles(f -> showHidden || !f.isHidden());
                List<FileEntry> list = new ArrayList<>();
                if (files != null) {
                    for (File f : files) {
                        list.add(new FileEntry(f, fsv));
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                try {
                    List<FileEntry> list = get();
                    if (currentFilter != null) {
                        list = list.stream()
                                .filter(e -> e.directory || currentFilter.accept(e.file))
                                .collect(Collectors.toList());
                    }
                    tableModel.setEntries(list);
                    statusLabel.setText(list.size() + " item" + (list.size() == 1 ? "" : "s"));
                } catch (Exception ex) {
                    Logger.error(ex, "Failed to list directory {}", dir);
                    statusLabel.setText("Error reading directory");
                    JOptionPane.showMessageDialog(FileChooser.this,
                            "Could not read directory:\n" + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    table.setEnabled(true);
                }
            }
        }.execute();
    }

    private void applySearchFilter() {
        String text = searchField.getText().trim();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
            return;
        }
        String needle = text.toLowerCase(Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends FileTableModel, ? extends Integer> entry) {
                FileEntry fe = entry.getModel().getEntryAt(entry.getIdentifier());
                return fe.file.getName().toLowerCase(Locale.ROOT).contains(needle);
            }
        });
    }

    private void createNewFolder() {
        String name = JOptionPane.showInputDialog(this, "Folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        Path newDir = currentDir.toPath().resolve(name.trim());
        try {
            Files.createDirectory(newDir);
            Logger.info("Created folder {}", newDir);
            loadDirectoryAsync(currentDir);
        } catch (IOException ex) {
            Logger.error(ex, "Failed to create folder {}", newDir);
            JOptionPane.showMessageDialog(this, "Could not create folder:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---- accept / result --------------------------------------------------

    private void accept() {
        switch (mode) {
            case OPEN_FILE -> {
                File target = selectedFromTable();
                if (target == null) {
                    String name = fileNameField.getText().trim();
                    if (!name.isEmpty()) {
                        File candidate = new File(name);
                        target = candidate.isAbsolute() ? candidate : new File(currentDir, name);
                    }
                }
                if (target == null || !target.isFile()) {
                    Toolkit.getDefaultToolkit().beep();
                    return;
                }
                selectedFile = target;
                approved = true;
                dispose();
            }
            case SAVE_FILE -> {
                String name = fileNameField.getText().trim();
                if (name.isEmpty()) {
                    Toolkit.getDefaultToolkit().beep();
                    return;
                }
                File candidate = new File(name);
                File target = candidate.isAbsolute() ? candidate : new File(currentDir, name);
                if (target.exists()) {
                    int r = JOptionPane.showConfirmDialog(this,
                            "\"" + target.getName() + "\" already exists. Overwrite?",
                            "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (r != JOptionPane.YES_OPTION) return;
                }
                selectedFile = target;
                approved = true;
                dispose();
            }
            case SELECT_FOLDER -> {
                File target = currentDir;
                int row = table.getSelectedRow();
                if (row >= 0) {
                    FileEntry fe = tableModel.getEntryAt(table.convertRowIndexToModel(row));
                    if (fe.directory) target = fe.file;
                }
                selectedFile = target;
                approved = true;
                dispose();
            }
        }
    }

    private File selectedFromTable() {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        FileEntry fe = tableModel.getEntryAt(table.convertRowIndexToModel(row));
        return fe.directory ? null : fe.file;
    }

    public boolean isApproved() {
        return approved;
    }

    @Nullable
    public File getSelectedFile() {
        return selectedFile;
    }

    // ---- filter combo -------------------------------------------------------

    private void populateFilterCombo() {
        filterCombo.removeAllItems();
        filterCombo.addItem(ExtensionFilter.ALL_FILES);
        for (ExtensionFilter f : filters) filterCombo.addItem(f);
        filterCombo.setSelectedIndex(0);
        currentFilter = ExtensionFilter.ALL_FILES;
        filterCombo.addActionListener(e -> {
            currentFilter = (ExtensionFilter) filterCombo.getSelectedItem();
            loadDirectoryAsync(currentDir);
        });
    }

    // ---- sidebar --------------------------------------------------------

    private void buildSidebarModel() {
        DefaultListModel<SidebarItem> model = new DefaultListModel<>();
        File home = new File(System.getProperty("user.home"));
        model.addElement(new SidebarItem("Home", home, fsv.getSystemIcon(home)));
        addIfExists(model, "Desktop", new File(home, "Desktop"));
        addIfExists(model, "Documents", new File(home, "Documents"));
        addIfExists(model, "Downloads", new File(home, "Downloads"));
        addIfExists(model, "Pictures", new File(home, "Pictures"));
        for (File root : File.listRoots()) {
            model.addElement(new SidebarItem(fsv.getSystemDisplayName(root), root, fsv.getSystemIcon(root)));
        }
        sidebarList.setModel(model);
    }

    private void addIfExists(DefaultListModel<SidebarItem> model, String label, File file) {
        if (file.isDirectory()) {
            model.addElement(new SidebarItem(label, file, fsv.getSystemIcon(file)));
        }
    }

    private record SidebarItem(String label, File file, Icon icon) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final class SidebarRenderer implements ListCellRenderer<SidebarItem> {
        @Override
        public Component getListCellRendererComponent(JList<? extends SidebarItem> list, SidebarItem value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = new JLabel(value.label(), value.icon(), SwingConstants.LEFT);
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            if (isSelected) {
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            } else {
                label.setBackground(list.getBackground());
                label.setForeground(list.getForeground());
            }
            return label;
        }
    }

    // ---- table model / rendering ------------------------------------------

    private static final class FileEntry {
        final File file;
        final boolean directory;
        final long size;
        final long lastModified;
        final String typeDescription;

        FileEntry(File file, FileSystemView fsv) {
            this.file = file;
            this.directory = file.isDirectory();
            this.size = directory ? -1 : file.length();
            this.lastModified = file.lastModified();
            String type;
            try {
                type = fsv.getSystemTypeDescription(file);
            } catch (Exception ex) {
                type = directory ? "File Folder" : "File";
            }
            this.typeDescription = type != null ? type : (directory ? "File Folder" : "File");
        }
    }

    private static final class FileTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Name", "Size", "Type", "Date Modified"};
        private List<FileEntry> entries = new ArrayList<>();

        void setEntries(List<FileEntry> entries) {
            this.entries = entries;
            fireTableDataChanged();
        }

        FileEntry getEntryAt(int row) {
            return entries.get(row);
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? FileEntry.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            FileEntry e = entries.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> e;
                case 1 -> e.directory ? "" : formatSize(e.size);
                case 2 -> e.typeDescription;
                case 3 -> DATE_FORMAT.format(new Date(e.lastModified));
                default -> "";
            };
        }
    }

    private final class NameCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof FileEntry fe) {
                String name = fe.file.getName();
                label.setText(name.isEmpty() ? fe.file.getPath() : name);
                label.setIcon(fsv.getSystemIcon(fe.file));
            }
            return label;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    // ---- extension filter -----------------------------------------------

    public static final class ExtensionFilter {
        public static final ExtensionFilter ALL_FILES = new ExtensionFilter("All Files", "*");

        private final String description;
        private final Set<String> extensions;

        public ExtensionFilter(String description, String... extensions) {
            this.description = description;
            this.extensions = new LinkedHashSet<>();
            for (String ext : extensions) {
                this.extensions.add(ext.toLowerCase(Locale.ROOT));
            }
        }

        public boolean accept(File file) {
            if (extensions.isEmpty() || extensions.contains("*")) return true;
            String name = file.getName().toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            if (dot < 0) return false;
            return extensions.contains(name.substring(dot + 1));
        }

        @Override
        public String toString() {
            if (extensions.contains("*") || extensions.isEmpty()) return description + " (*.*)";
            return description + " (" + extensions.stream().map(e -> "*." + e).collect(Collectors.joining(", ")) + ")";
        }
    }
}