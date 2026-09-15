package RLExtension.ui;

import RLExtension.detect.Detected;
import RLExtension.detect.FileType;
import RLExtension.render.PdfRenderer;
import RLExtension.render.TableRenderer;
import RLExtension.table.CsvParser;
import RLExtension.table.Limits;
import RLExtension.table.Workbook;
import RLExtension.table.XlsReader;
import RLExtension.table.XlsxReader;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The whole tab: a header strip describing the file, and a card layout that swaps between the PDF
 * viewer, the spreadsheet viewer and a status message.
 * <p>
 * Renderers are created on first use — building an ICEpdf viewer for every Repeater tab that never
 * shows a PDF is wasted work.
 */
public final class ViewerPanel {

    private static final String CARD_MESSAGE = "message";
    private static final String CARD_PDF = "pdf";
    private static final String CARD_TABLE = "table";

    private final Consumer<Component> themer;
    private final Consumer<String> errorLog;

    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel cards = new JPanel(new CardLayout());
    private final MessagePanel message = new MessagePanel();

    private final Ui.Badge typeBadge = new Ui.Badge("FILE", true);
    private final JLabel fileName = new JLabel();
    private final JLabel meta = Ui.subtle("");
    private final Ui.Badge gzipBadge = new Ui.Badge("gzip", false);

    private PdfRenderer pdfRenderer;
    private TableRenderer tableRenderer;
    private Detected current = Detected.NONE;

    /** Guards against a slow parse landing after the tab has moved on to another response. */
    private int generation;

    public ViewerPanel(Consumer<Component> themer, Consumer<String> errorLog) {
        this.themer = themer;
        this.errorLog = errorLog;

        cards.add(message.component(), CARD_MESSAGE);
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(cards, BorderLayout.CENTER);
        showMessage("No response", "");
    }

    public Component component() {
        return root;
    }

    /** Renders the detected file. Call on the EDT. */
    public void show(Detected detected) {
        current = detected;
        generation++;

        updateHeader(detected);
        if (!detected.isRenderable()) {
            showMessage("Nothing to render", "");
            return;
        }

        if (detected.type().isTabular()) {
            showTable(detected, generation);
        } else {
            showPdf(detected);
        }
    }

    public void clear() {
        generation++;
        current = Detected.NONE;
        if (pdfRenderer != null) {
            pdfRenderer.close();
        }
        if (tableRenderer != null) {
            tableRenderer.clear();
        }
        showMessage("No response", "");
    }

    // ---------------------------------------------------------------- header

    private Component buildHeader() {
        JButton save = Ui.iconButton(VectorIcon.Glyph.SAVE, "Save file…");
        save.addActionListener(event -> saveToDisk());

        JPanel left = Ui.strip();
        left.add(typeBadge);
        left.add(fileName);
        left.add(gzipBadge);
        left.add(meta);

        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.add(left, BorderLayout.WEST);
        bar.add(save, BorderLayout.EAST);
        Ui.underline(bar);
        return bar;
    }

    private void updateHeader(Detected detected) {
        typeBadge.setText(detected.type().caption().toUpperCase(Locale.ROOT));
        fileName.setText(detected.fileName() == null ? "(unnamed)" : detected.fileName());
        gzipBadge.setVisible(detected.decompressed());
        meta.setText(describeSize(detected));
    }

    private static String describeSize(Detected detected) {
        String size = humanBytes(detected.body().length);
        return detected.decompressed()
                ? size + " expanded from " + humanBytes(detected.originalLength())
                : size;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024d);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024d));
    }

    // ---------------------------------------------------------------- renderers

    private void showPdf(Detected detected) {
        try {
            pdfRenderer().load(detected.body(), detected.fileName());
            showCard(CARD_PDF);
        } catch (Exception e) {
            log("Failed to render PDF: " + e);
            showMessage("Could not render this PDF", reason(e));
        }
    }

    /** Spreadsheets are parsed off the EDT: a large workbook would otherwise freeze Burp's UI. */
    private void showTable(Detected detected, int requestGeneration) {
        if (detected.body().length > Limits.MAX_TABULAR_BYTES) {
            showMessage("File is too large to open as a table",
                    humanBytes(detected.body().length) + " exceeds the "
                            + humanBytes(Limits.MAX_TABULAR_BYTES) + " parse limit — use Save to inspect it offline.");
            return;
        }
        showMessage("Reading " + detected.type().caption() + "…", "");

        new SwingWorker<Workbook, Void>() {
            @Override
            protected Workbook doInBackground() throws Exception {
                return parse(detected);
            }

            @Override
            protected void done() {
                if (requestGeneration != generation) {
                    return; // A newer response replaced this one while we were parsing.
                }
                try {
                    Workbook workbook = get();
                    if (workbook.isEmpty()) {
                        showMessage("No sheets in this file", "");
                        return;
                    }
                    tableRenderer().setWorkbook(workbook);
                    showCard(CARD_TABLE);
                } catch (Exception e) {
                    log("Failed to render " + detected.type().caption() + ": " + e);
                    showMessage("Could not read this " + detected.type().caption(), reason(e));
                }
            }
        }.execute();
    }

    private static Workbook parse(Detected detected) throws Exception {
        String name = sheetName(detected);
        return switch (detected.type()) {
            case XLSX -> XlsxReader.parse(detected.body());
            case XLS -> XlsReader.parse(detected.body());
            case TSV -> CsvParser.parse(detected.body(), '\t', name);
            default -> CsvParser.parse(detected.body(), name);
        };
    }

    private static String sheetName(Detected detected) {
        String name = detected.fileName();
        return name == null || name.isEmpty() ? "Data" : name;
    }

    private PdfRenderer pdfRenderer() {
        if (pdfRenderer == null) {
            pdfRenderer = new PdfRenderer();
            cards.add(pdfRenderer.component(), CARD_PDF);
            themer.accept(pdfRenderer.component());
        }
        return pdfRenderer;
    }

    private TableRenderer tableRenderer() {
        if (tableRenderer == null) {
            tableRenderer = new TableRenderer();
            cards.add(tableRenderer.component(), CARD_TABLE);
            themer.accept(tableRenderer.component());
        }
        return tableRenderer;
    }

    private void showCard(String name) {
        if (!CARD_PDF.equals(name) && pdfRenderer != null) {
            pdfRenderer.close(); // Release the parsed document when it is no longer on screen.
        }
        ((CardLayout) cards.getLayout()).show(cards, name);
    }

    private void showMessage(String title, String detail) {
        message.show(title, detail);
        showCard(CARD_MESSAGE);
    }

    // ---------------------------------------------------------------- save

    private void saveToDisk() {
        if (!current.isRenderable() || current.body().length == 0) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save response body");
        chooser.setSelectedFile(new File(suggestedName()));
        if (chooser.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        try {
            Files.write(target.toPath(), current.body());
        } catch (Exception e) {
            log("Failed to save " + target + ": " + e);
            showMessage("Could not save the file", reason(e));
        }
    }

    private String suggestedName() {
        String name = current.fileName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return "response" + defaultExtension(current.type());
    }

    private static String defaultExtension(FileType type) {
        return switch (type) {
            case PDF -> ".pdf";
            case XLSX -> ".xlsx";
            case XLS -> ".xls";
            case TSV -> ".tsv";
            case CSV -> ".csv";
            default -> ".bin";
        };
    }

    private void log(String text) {
        if (errorLog != null) {
            errorLog.accept(text);
        }
    }

    private static String reason(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String text = cause.getMessage();
        return text == null || text.isEmpty() ? cause.getClass().getSimpleName() : text;
    }
}
