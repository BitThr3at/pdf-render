package RLExtension.render;

import RLExtension.table.Sheet;
import RLExtension.table.Workbook;
import RLExtension.ui.Ui;
import RLExtension.ui.VectorIcon;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Spreadsheet view for CSV/TSV/XLSX/XLS: one tab per sheet, sortable and filterable, with the
 * original row numbers down the side so a filtered view still tells you where a row came from.
 */
public final class TableRenderer {

    private final JPanel root = new JPanel(new BorderLayout());
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextField filter = Ui.textField(18, "Filter rows");
    private final JCheckBox headerRow = new JCheckBox("First row is header", true);
    private final JLabel counts = Ui.subtle("");
    private final JLabel notice = Ui.subtle("");
    private final List<SheetView> views = new ArrayList<>();
    private Component content;

    public TableRenderer() {
        root.add(buildToolBar(), BorderLayout.NORTH);

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        headerRow.addActionListener(event -> {
            for (SheetView view : views) {
                view.setHeaderRow(headerRow.isSelected());
            }
            updateCounts();
        });
        tabs.addChangeListener(event -> {
            applyFilter();
            updateCounts();
        });
    }

    public Component component() {
        return root;
    }

    public void setWorkbook(Workbook workbook) {
        views.clear();
        tabs.removeAll();
        for (Sheet sheet : workbook.sheets()) {
            views.add(new SheetView(sheet, headerRow.isSelected()));
        }

        setContent(buildContent(workbook));
        notice.setText(buildNotice(workbook));
        notice.setVisible(!notice.getText().isEmpty());

        applyFilter();
        updateCounts();
    }

    public void clear() {
        views.clear();
        tabs.removeAll();
        setContent(null);
        counts.setText("");
        notice.setVisible(false);
    }

    /** A lone sheet is shown bare; a tab strip for one tab is just wasted vertical space. */
    private Component buildContent(Workbook workbook) {
        if (views.size() == 1) {
            return views.get(0).component();
        }
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (int i = 0; i < views.size(); i++) {
            tabs.addTab(workbook.sheets().get(i).name(), views.get(i).component());
        }
        return tabs;
    }

    private void setContent(Component content) {
        if (this.content != null) {
            root.remove(this.content);
        }
        this.content = content;
        if (content != null) {
            root.add(content, BorderLayout.CENTER);
        }
        root.revalidate();
        root.repaint();
    }

    private String buildNotice(Workbook workbook) {
        List<String> messages = new ArrayList<>(workbook.warnings());
        for (SheetView view : views) {
            if (view.sheet.truncated()) {
                messages.add("\"" + view.sheet.name() + "\" truncated at the row/column cap");
            }
        }
        return String.join(" · ", messages);
    }

    // ---------------------------------------------------------------- toolbar

    private JComponent buildToolBar() {
        JPanel bar = Ui.strip();
        bar.setLayout(new BorderLayout(6, 0));

        JButton clearFilter = Ui.iconButton(VectorIcon.Glyph.CLEAR, "Clear filter");
        clearFilter.addActionListener(event -> filter.setText(""));

        JButton copyCsv = Ui.iconButton(VectorIcon.Glyph.SAVE, "Copy visible rows as CSV");
        copyCsv.addActionListener(event -> copyVisibleAsCsv());

        headerRow.setFocusable(false);

        JPanel left = Ui.strip();
        left.add(filter);
        left.add(clearFilter);
        left.add(Ui.separator());
        left.add(headerRow);
        left.add(copyCsv);

        JPanel right = Ui.strip();
        right.add(notice);
        right.add(counts);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return bar;
    }

    private void applyFilter() {
        SheetView view = currentView();
        if (view == null) {
            return;
        }
        String text = filter.getText();
        if (text == null || text.isEmpty()) {
            view.sorter.setRowFilter(null);
        } else {
            // Literal substring match, case-insensitive: a filter box is not a regex prompt.
            view.sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
        }
        view.refreshRowHeader();
        updateCounts();
    }

    private void updateCounts() {
        SheetView view = currentView();
        if (view == null) {
            counts.setText("");
            return;
        }
        int shown = view.table.getRowCount();
        int total = view.model.getRowCount();
        String rows = shown == total
                ? total + (total == 1 ? " row" : " rows")
                : shown + " of " + total + " rows";
        counts.setText(rows + " · " + view.model.getColumnCount() + " cols");
    }

    private SheetView currentView() {
        if (views.size() == 1) {
            return views.get(0); // Shown without a tab strip, so the tabbed pane has no selection.
        }
        int index = tabs.getSelectedIndex();
        return index >= 0 && index < views.size() ? views.get(index) : null;
    }

    /** Copies the rows currently visible in the selected sheet as CSV. */
    public void copyVisibleAsCsv() {
        SheetView view = currentView();
        if (view == null) {
            return;
        }
        StringBuilder csv = new StringBuilder();
        for (int column = 0; column < view.table.getColumnCount(); column++) {
            csv.append(column == 0 ? "" : ",").append(quote(view.table.getColumnName(column)));
        }
        csv.append('\n');
        for (int row = 0; row < view.table.getRowCount(); row++) {
            for (int column = 0; column < view.table.getColumnCount(); column++) {
                Object value = view.table.getValueAt(row, column);
                csv.append(column == 0 ? "" : ",").append(quote(value == null ? "" : value.toString()));
            }
            csv.append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(csv.toString()), null);
    }

    private static String quote(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return needsQuotes ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    // ---------------------------------------------------------------- one sheet

    private static final class SheetView {

        private final Sheet sheet;
        private final SheetTableModel model;
        private final JTable table;
        private final TableRowSorter<SheetTableModel> sorter;
        private final JScrollPane scrollPane;
        private final JTable rowHeader;

        SheetView(Sheet sheet, boolean headerRow) {
            this.sheet = sheet;
            this.model = new SheetTableModel(sheet, headerRow);
            this.table = new JTable(model);
            this.sorter = new TableRowSorter<>(model);

            for (int column = 0; column < model.getColumnCount(); column++) {
                sorter.setComparator(column, SPREADSHEET_ORDER);
            }
            table.setRowSorter(sorter);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.setAutoCreateColumnsFromModel(true);
            table.setCellSelectionEnabled(true);
            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            table.setShowGrid(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.setFillsViewportHeight(true);
            table.setDefaultRenderer(Object.class, new CellRenderer());

            this.rowHeader = buildRowHeader();
            this.scrollPane = new JScrollPane(table);
            scrollPane.setRowHeaderView(rowHeader);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.getViewport().setBackground(UIManager.getColor("Table.background"));

            sizeColumns();
            sorter.addRowSorterListener(event -> refreshRowHeader());
        }

        Component component() {
            return scrollPane;
        }

        void setHeaderRow(boolean headerRow) {
            model.setHeaderRow(headerRow);
            sizeColumns();
            refreshRowHeader();
        }

        void refreshRowHeader() {
            ((AbstractTableModel) rowHeader.getModel()).fireTableDataChanged();
            rowHeader.setPreferredScrollableViewportSize(
                    new Dimension(rowHeaderWidth(), rowHeader.getPreferredSize().height));
            scrollPane.revalidate();
        }

        /**
         * A one-column table pinned to the left showing each row's position in the source sheet,
         * so sorting or filtering does not hide where a row actually lives.
         */
        private JTable buildRowHeader() {
            AbstractTableModel headerModel = new AbstractTableModel() {
                @Override
                public int getRowCount() {
                    return table.getRowCount();
                }

                @Override
                public int getColumnCount() {
                    return 1;
                }

                @Override
                public Object getValueAt(int rowIndex, int columnIndex) {
                    int modelRow = table.convertRowIndexToModel(rowIndex);
                    return String.valueOf(model.sourceRowNumber(modelRow));
                }
            };

            JTable header = new JTable(headerModel);
            header.setRowHeight(table.getRowHeight());
            header.setShowGrid(false);
            header.setFocusable(false);
            header.setEnabled(false);
            header.setIntercellSpacing(new Dimension(0, 0));
            header.setBackground(Ui.stripe());

            DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
            renderer.setHorizontalAlignment(SwingConstants.RIGHT);
            renderer.setForeground(Ui.subtleForeground());
            renderer.setBackground(Ui.stripe());
            renderer.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 6));
            header.getColumnModel().getColumn(0).setCellRenderer(renderer);
            header.setPreferredScrollableViewportSize(new Dimension(46, 0));
            return header;
        }

        private int rowHeaderWidth() {
            int digits = Math.max(2, String.valueOf(Math.max(1, model.getRowCount())).length());
            return 18 + digits * 8;
        }

        /** Widths come from a sample of the data rather than the whole sheet, which can be huge. */
        private void sizeColumns() {
            FontMetrics metrics = table.getFontMetrics(table.getFont());
            int sampleRows = Math.min(model.getRowCount(), 200);
            for (int column = 0; column < table.getColumnModel().getColumnCount(); column++) {
                int width = metrics.stringWidth(model.getColumnName(column)) + 30;
                for (int row = 0; row < sampleRows; row++) {
                    Object value = model.getValueAt(row, column);
                    if (value != null) {
                        width = Math.max(width, metrics.stringWidth(value.toString()) + 18);
                    }
                }
                TableColumn tableColumn = table.getColumnModel().getColumn(column);
                tableColumn.setPreferredWidth(Math.max(64, Math.min(width, 420)));
            }
        }
    }

    /** Numbers sort as numbers; everything else sorts case-insensitively. */
    private static final Comparator<Object> SPREADSHEET_ORDER = (left, right) -> {
        String a = left == null ? "" : left.toString();
        String b = right == null ? "" : right.toString();
        Double numberA = asNumber(a);
        Double numberB = asNumber(b);
        if (numberA != null && numberB != null) {
            return Double.compare(numberA, numberB);
        }
        if (numberA != null) {
            return -1;
        }
        if (numberB != null) {
            return 1;
        }
        return a.compareToIgnoreCase(b);
    };

    private static Double asNumber(String value) {
        String trimmed = value.trim().replace(",", "");
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Zebra striping plus right-aligned numbers, both derived from the active theme. */
    private static final class CellRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            String text = value == null ? "" : value.toString();
            setHorizontalAlignment(asNumber(text) != null ? SwingConstants.RIGHT : SwingConstants.LEADING);
            setToolTipText(text.isEmpty() ? null : text);
            if (!isSelected) {
                Color background = row % 2 == 0 ? UIManager.getColor("Table.background") : Ui.stripe();
                setBackground(background == null ? Color.WHITE : background);
            }
            return component;
        }
    }

    /** Presents a {@link Sheet} to Swing, optionally treating its first row as column headings. */
    private static final class SheetTableModel extends AbstractTableModel {

        private final Sheet sheet;
        private boolean headerRow;

        SheetTableModel(Sheet sheet, boolean headerRow) {
            this.sheet = sheet;
            this.headerRow = headerRow && !sheet.rows().isEmpty();
        }

        void setHeaderRow(boolean value) {
            this.headerRow = value && !sheet.rows().isEmpty();
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            int rows = sheet.rows().size();
            return headerRow ? Math.max(0, rows - 1) : rows;
        }

        @Override
        public int getColumnCount() {
            return Math.max(sheet.columnCount(), 1);
        }

        @Override
        public String getColumnName(int column) {
            if (headerRow) {
                String name = sheet.cell(0, column);
                if (!name.isEmpty()) {
                    return name;
                }
            }
            return columnLetters(column);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return sheet.cell(headerRow ? rowIndex + 1 : rowIndex, columnIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return Object.class;
        }

        /** 1-based position of a model row within the source sheet. */
        int sourceRowNumber(int modelRow) {
            return (headerRow ? modelRow + 2 : modelRow + 1);
        }

        private static String columnLetters(int column) {
            StringBuilder letters = new StringBuilder();
            int value = column;
            do {
                letters.insert(0, (char) ('A' + value % 26));
                value = value / 26 - 1;
            } while (value >= 0);
            return letters.toString();
        }
    }
}
