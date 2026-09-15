# PDF Render - A BurpSuite Extension

BurpSuite extension that renders downloadable file bodies in a custom response tab, so you can read
what a target actually served without saving it out and opening another application.

## Supported formats

| Format | Notes |
| --- | --- |
| PDF | Rendered with ICEpdf: page navigation, zoom, fit, rotate, text selection, find, thumbnails and bookmarks |
| XLSX | Read directly from the OOXML container — sheets, shared strings, formats and cached formula values |
| XLS | Legacy BIFF8 workbooks (see limitations below) |
| CSV | Delimiter detected automatically (`,` `;` `\t` `|`), RFC 4180 quoting, BOM and UTF-16 aware |
| TSV | Tab-separated variant |

Detection is by magic bytes first, with `Content-Type`, `Content-Disposition` and the request path
used only as tie-breakers. `gzip`/`deflate` bodies are expanded before sniffing, so a compressed
download is still recognised.

## The viewer

* Header strip shows the detected type, filename, size, whether the body arrived compressed, and a
  **Save** button that writes the (decompressed) body to disk.
* PDFs get a flat toolbar drawn with vector icons that follow Burp's active theme instead of
  ICEpdf's stock toolbars. Annotation and form editing are switched off — this is a read-only view
  of somebody else's file.
* Render quality is raised above ICEpdf's speed-tuned defaults: image interpolation goes from
  nearest-neighbour to bicubic, with quality rendering, alpha and colour hints. Scanned pages and
  raster logos stop looking stair-stepped, at a measured cost of ~3 ms per page repaint. Vector text
  is unchanged (it already renders antialiased at the display's scale factor), and
  `org.icepdf.core.imageReference` is left alone on purpose — `smoothScaled` pre-downsamples and
  softens detail. Any of these system properties already set in the host JVM are respected.
* Spreadsheets open in a sortable, filterable grid with one tab per sheet, source row numbers down
  the left margin, numeric-aware sorting, and **Copy visible rows as CSV**.

## Limitations

* Spreadsheet parsing is deliberately dependency-free (JDK only), which keeps the packaged JAR at
  roughly 18 MB — the same size as before these formats were added.
* Formulas are not evaluated. The value the producing application cached alongside the formula is
  shown, which is what a viewer wants; a file written without cached values shows those cells empty.
* Legacy `.xls` support is best-effort and covers the common cell records. Password-protected
  workbooks are rejected rather than partially parsed, and Excel 5/95 files render with a warning.
* Parsing is capped to protect Burp's heap against hostile files: 32 MB per spreadsheet,
  200,000 rows, 4,096 columns, and a zip expansion limit. Anything cut short is reported in the
  viewer rather than silently truncated.

## Build

```bash
mvn clean install
```

The shaded JAR is written to `target/ViewPdfFinal-1.0-SNAPSHOT.jar`.
