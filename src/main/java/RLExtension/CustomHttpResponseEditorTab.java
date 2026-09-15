package RLExtension;

import RLExtension.detect.Detected;
import RLExtension.detect.FileType;
import RLExtension.detect.TypeDetector;
import RLExtension.ui.ViewerPanel;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;

import java.awt.Component;

/**
 * Response editor tab that renders downloadable file bodies: PDF documents and CSV/TSV/XLSX/XLS
 * spreadsheets.
 */
public class CustomHttpResponseEditorTab implements ExtensionProvidedHttpResponseEditor {

    private final MontoyaApi api;
    private final ViewerPanel viewer;
    private String caption = "File";

    public CustomHttpResponseEditorTab(MontoyaApi api, EditorCreationContext creationContext) {
        this.api = api;
        this.viewer = new ViewerPanel(
                component -> api.userInterface().applyThemeToComponent(component),
                api.logging()::logToError);

        api.userInterface().applyThemeToComponent(viewer.component());
    }

    @Override
    public void setRequestResponse(HttpRequestResponse httpRequestResponse) {
        if (httpRequestResponse == null || !httpRequestResponse.hasResponse()) {
            viewer.clear();
            return;
        }
        try {
            Detected detected = detect(httpRequestResponse);
            caption = detected.type().caption();
            viewer.show(detected);
        } catch (Exception e) {
            // Never let a malformed body break the editor tab.
            api.logging().logToError("Failed to inspect response body: " + e);
            viewer.clear();
        }
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse httpRequestResponse) {
        try {
            if (httpRequestResponse == null || !httpRequestResponse.hasResponse()) {
                return false;
            }
            HttpResponse response = httpRequestResponse.response();
            FileType type = TypeDetector.sniff(
                    response.body().getBytes(),
                    response.headerValue("Content-Type"),
                    response.headerValue("Content-Disposition"),
                    requestPath(httpRequestResponse));
            return type != FileType.UNKNOWN;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String caption() {
        return caption;
    }

    @Override
    public Component uiComponent() {
        return viewer.component();
    }

    @Override
    public HttpResponse getResponse() {
        return null;
    }

    @Override
    public Selection selectedData() {
        return null;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    // ----------------------------------------------------------------

    private static Detected detect(HttpRequestResponse httpRequestResponse) {
        HttpResponse response = httpRequestResponse.response();
        return TypeDetector.detect(
                response.body().getBytes(),
                response.headerValue("Content-Type"),
                response.headerValue("Content-Disposition"),
                requestPath(httpRequestResponse));
    }

    private static String requestPath(HttpRequestResponse httpRequestResponse) {
        try {
            return httpRequestResponse.request() == null ? null : httpRequestResponse.request().pathWithoutQuery();
        } catch (Exception e) {
            return null; // Some sources hand us a response with no usable request.
        }
    }
}
