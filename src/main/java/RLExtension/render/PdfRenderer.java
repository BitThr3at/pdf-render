package RLExtension.render;

import RLExtension.ui.Ui;
import RLExtension.ui.VectorIcon;
import org.icepdf.core.util.GraphicsRenderingHints;
import org.icepdf.ri.common.SwingController;
import org.icepdf.ri.common.SwingViewBuilder;
import org.icepdf.ri.common.views.DocumentViewController;
import org.icepdf.ri.common.views.DocumentViewModel;
import org.icepdf.ri.util.ViewerPropertiesManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;

/**
 * PDF viewing on top of ICEpdf, with ICEpdf's own toolbars switched off and replaced by a flat
 * toolbar of our own. The controls are handed to {@link SwingController}, which then owns their
 * actions and enabled/selected state — so page numbers and zoom stay in sync without us
 * listening for document events.
 */
public final class PdfRenderer {

    private static final float[] ZOOM_LEVELS =
            {0.10f, 0.25f, 0.50f, 0.75f, 1.00f, 1.25f, 1.50f, 2.00f, 3.00f, 4.00f, 8.00f};

    private static boolean renderQualityConfigured;

    private final SwingController controller = new SwingController();
    private final JPanel root = new JPanel(new BorderLayout());
    private boolean documentOpen;

    public PdfRenderer() {
        configureRenderQuality();
        buildViewer();
    }

    /**
     * ICEpdf's stock screen hints are tuned for speed: image interpolation defaults to
     * nearest-neighbour, which leaves embedded rasters (scanned pages, logos) visibly jagged.
     * Page content is painted straight into the Swing {@code Graphics2D} using these hints on
     * every repaint, so raising them improves the live view and not just exports.
     * <p>
     * Deliberately left alone: {@code org.icepdf.core.imageReference}. Setting it to
     * {@code smoothScaled} pre-downsamples images and measurably softens detail — bicubic
     * interpolation on its own gives the sharper result. Vector text is unaffected either way;
     * it already renders antialiased at the display's scale factor.
     */
    private static synchronized void configureRenderQuality() {
        if (renderQualityConfigured) {
            return;
        }
        renderQualityConfigured = true;

        setIfUnset("org.icepdf.core.screen.interpolation", "VALUE_INTERPOLATION_BICUBIC");
        setIfUnset("org.icepdf.core.screen.render", "VALUE_RENDER_QUALITY");
        setIfUnset("org.icepdf.core.screen.alphaInterpolation", "VALUE_ALPHA_INTERPOLATION_QUALITY");
        setIfUnset("org.icepdf.core.screen.colorRender", "VALUE_COLOR_RENDER_QUALITY");

        // The hint set is built once and cached, so it has to be rebuilt after changing properties.
        GraphicsRenderingHints.getDefault().reset();
    }

    /** Leaves any value the host JVM already set in place. */
    private static void setIfUnset(String property, String value) {
        if (System.getProperty(property) == null) {
            System.setProperty(property, value);
        }
    }

    public Component component() {
        return root;
    }

    /** Opens a PDF. Must run on the EDT; ICEpdf paints pages lazily so this returns quickly. */
    public void load(byte[] body, String fileName) throws IOException {
        close();
        String name = fileName == null || fileName.isEmpty() ? "response.pdf" : fileName;
        controller.openDocument(body, 0, body.length, name, null);
        if (controller.getDocument() == null) {
            throw new IOException("ICEpdf could not parse the document (it may be corrupt or encrypted)");
        }
        documentOpen = true;
        controller.setPageFitMode(DocumentViewController.PAGE_FIT_WINDOW_WIDTH, false);
    }

    public void close() {
        if (documentOpen) {
            controller.closeDocument();
            documentOpen = false;
        }
    }

    public int pageCount() {
        return documentOpen && controller.getDocument() != null ? controller.getDocument().getNumberOfPages() : 0;
    }

    // ---------------------------------------------------------------- construction

    private void buildViewer() {
        ViewerPropertiesManager properties = configureProperties();

        SwingViewBuilder factory = new SwingViewBuilder(controller, properties);
        JSplitPane documentPane = factory.buildUtilityAndDocumentSplitPane(true);

        root.add(buildToolBar(), BorderLayout.NORTH);
        root.add(documentPane, BorderLayout.CENTER);

        // Read-only viewer: no annotation editing from a proxied response.
        controller.getDocumentViewController().setAnnotationCallback(null);
        controller.setDocumentToolMode(DocumentViewModel.DISPLAY_TOOL_TEXT_SELECTION);
    }

    /**
     * Turns off every ICEpdf toolbar and the annotation/signature utility tabs. The toolbars are
     * what looked dated, and annotation editing has no place in a response viewer.
     */
    private static ViewerPropertiesManager configureProperties() {
        ViewerPropertiesManager properties = ViewerPropertiesManager.getInstance();

        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_UTILITY, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_PAGENAV, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_ZOOM, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_FIT, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_ROTATE, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_TOOL, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_SEARCH, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_FULL_SCREEN, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_ANNOTATION, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_TOOLBAR_FORMS, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_STATUSBAR, false);

        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_UTILITYPANE_ANNOTATION, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_UTILITYPANE_ANNOTATION_FLAGS, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_UTILITYPANE_ANNOTATION_MARKUP, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_UTILITYPANE_ANNOTATION_DESTINATIONS, false);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_UTILITYPANE_SIGNATURES, false);

        // Kept: bookmarks, thumbnails, attachments and the search results pane.
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_UTILITYPANE_SEARCH, true);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_UTILITYPANE_THUMBNAILS, true);
        properties.setBoolean(ViewerPropertiesManager.PROPERTY_SHOW_UTILITYPANE_BOOKMARKS, true);

        return properties;
    }

    private JPanel buildToolBar() {
        JPanel bar = Ui.strip();

        JButton firstPage = Ui.iconButton(VectorIcon.Glyph.FIRST_PAGE, "First page");
        JButton previousPage = Ui.iconButton(VectorIcon.Glyph.PREVIOUS_PAGE, "Previous page");
        JButton nextPage = Ui.iconButton(VectorIcon.Glyph.NEXT_PAGE, "Next page");
        JButton lastPage = Ui.iconButton(VectorIcon.Glyph.LAST_PAGE, "Last page");
        controller.setFirstPageButton(firstPage);
        controller.setPreviousPageButton(previousPage);
        controller.setNextPageButton(nextPage);
        controller.setLastPageButton(lastPage);

        JTextField pageNumber = new JTextField(3);
        pageNumber.setHorizontalAlignment(SwingConstants.CENTER);
        pageNumber.setToolTipText("Go to page");
        JLabel pageCount = Ui.subtle("/ -");
        controller.setCurrentPageNumberTextField(pageNumber);
        controller.setNumberOfPagesLabel(pageCount);

        JButton zoomOut = Ui.iconButton(VectorIcon.Glyph.ZOOM_OUT, "Zoom out");
        JButton zoomIn = Ui.iconButton(VectorIcon.Glyph.ZOOM_IN, "Zoom in");
        JComboBox<String> zoom = new JComboBox<>(zoomLabels());
        zoom.setEditable(true);
        zoom.setFocusable(false);
        zoom.setPreferredSize(new Dimension(78, zoom.getPreferredSize().height));
        controller.setZoomOutButton(zoomOut);
        controller.setZoomInButton(zoomIn);
        controller.setZoomComboBox(zoom, ZOOM_LEVELS);

        JToggleButton fitWidth = Ui.iconToggle(VectorIcon.Glyph.FIT_WIDTH, "Fit width");
        JToggleButton fitPage = Ui.iconToggle(VectorIcon.Glyph.FIT_PAGE, "Fit page");
        JToggleButton actualSize = Ui.iconToggle(VectorIcon.Glyph.ACTUAL_SIZE, "Actual size");
        controller.setFitWidthButton(fitWidth);
        controller.setFitHeightButton(fitPage);
        controller.setFitActualSizeButton(actualSize);

        JButton rotateLeft = Ui.iconButton(VectorIcon.Glyph.ROTATE_LEFT, "Rotate left");
        JButton rotateRight = Ui.iconButton(VectorIcon.Glyph.ROTATE_RIGHT, "Rotate right");
        controller.setRotateLeftButton(rotateLeft);
        controller.setRotateRightButton(rotateRight);

        JToggleButton singlePage = Ui.iconToggle(VectorIcon.Glyph.SINGLE_PAGE, "Single page");
        JToggleButton continuous = Ui.iconToggle(VectorIcon.Glyph.CONTINUOUS, "Continuous");
        JToggleButton twoUp = Ui.iconToggle(VectorIcon.Glyph.TWO_UP, "Two pages");
        controller.setPageViewSinglePageNonConButton(singlePage);
        controller.setPageViewSinglePageConButton(continuous);
        controller.setPageViewFacingPageConButton(twoUp);

        JTextField find = Ui.textField(12, "Find in document");
        JButton findPrevious = Ui.iconButton(VectorIcon.Glyph.PREVIOUS_MATCH, "Previous match");
        JButton findNext = Ui.iconButton(VectorIcon.Glyph.NEXT_MATCH, "Next match");
        find.addActionListener(event -> runSearch(find.getText()));
        findPrevious.addActionListener(event -> controller.previousSearchResult());
        findNext.addActionListener(event -> controller.nextSearchResult());

        add(bar, firstPage, previousPage);
        bar.add(pageNumber);
        bar.add(pageCount);
        add(bar, nextPage, lastPage);
        bar.add(Ui.separator());
        add(bar, zoomOut);
        bar.add(zoom);
        add(bar, zoomIn);
        bar.add(Ui.separator());
        add(bar, fitWidth, fitPage, actualSize);
        bar.add(Ui.separator());
        add(bar, singlePage, continuous, twoUp);
        bar.add(Ui.separator());
        add(bar, rotateLeft, rotateRight);
        bar.add(Ui.separator());
        bar.add(find);
        add(bar, findPrevious, findNext);

        bar.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        return bar;
    }

    private void runSearch(String term) {
        if (term == null || term.isBlank()) {
            return;
        }
        controller.showSearchPanel(term);
    }

    private static void add(JPanel bar, Component... components) {
        for (Component component : components) {
            bar.add(component);
        }
    }

    private static String[] zoomLabels() {
        String[] labels = new String[ZOOM_LEVELS.length];
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            labels[i] = Math.round(ZOOM_LEVELS[i] * 100) + "%";
        }
        return labels;
    }
}
