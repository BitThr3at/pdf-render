package RLExtension;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.logging.Logging;

public class Montoya implements BurpExtension {

    MontoyaApi api;
    Logging logging;

    @Override
    public void initialize(MontoyaApi api) {
        // Save a reference to the MontoyaApi object
        this.api = api;
        // api.logging() returns an object that we can use to print messages to stdout and stderr
        this.logging = api.logging();

        // Set the name of the extension
        api.extension().setName("PDF Render");

        //Log custom message to the output tab
        api.logging().logToOutput("The extension was successfully loaded.");
        api.logging().logToOutput("");
        api.logging().logToOutput("===============================================");
        api.logging().logToOutput(" ____  ____  _____   ____                _           \n" +
                "|  _ \\|  _ \\|  ___| |  _ \\ ___ _ __   __| | ___ _ __ \n" +
                "| |_) | | | | |_    | |_) / _ \\ '_ \\ / _` |/ _ \\ '__|\n" +
                "|  __/| |_| |  _|   |  _ <  __/ | | | (_| |  __/ |   \n" +
                "|_|   |____/|_|     |_| \\_\\___|_| |_|\\__,_|\\___|_|   ");
        api.logging().logToOutput("");
        api.logging().logToOutput("===============================================");
        api.logging().logToOutput("");
        api.logging().logToOutput("Renders PDF, CSV, TSV, XLSX and XLS response bodies in a custom editor tab.");
        api.logging().logToOutput("");
        api.logging().logToOutput("==========License==========\n" +
                "This plugin use the library ICEpdf licensed under Apache License 2.0\n" +
                " - http://www.icesoft.org/java/projects/ICEpdf/overview.jsf\n" +
                "Spreadsheet parsing (CSV/TSV/XLSX/XLS) is implemented on the JDK, with no extra libraries.\n");

        // Pass the API instance to CustomHttpResponseEditor
        CustomHttpResponseEditor customHttpRequestResponseEditor = new CustomHttpResponseEditor(api);
        api.userInterface().registerHttpResponseEditorProvider(customHttpRequestResponseEditor);
    }
}
