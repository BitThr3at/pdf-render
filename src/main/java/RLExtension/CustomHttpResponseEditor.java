package RLExtension;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;

/** Hands Burp a fresh file viewer for each response editor it builds. */
public class CustomHttpResponseEditor implements HttpResponseEditorProvider {

    private final MontoyaApi api;

    public CustomHttpResponseEditor(MontoyaApi api) {
        this.api = api;
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext editorCreationContext) {
        return new CustomHttpResponseEditorTab(api, editorCreationContext);
    }
}
