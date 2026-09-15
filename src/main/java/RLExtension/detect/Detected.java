package RLExtension.detect;

/**
 * Outcome of sniffing a response body: what it is, and the bytes to hand the renderer
 * (already un-gzipped when the body arrived compressed).
 */
public final class Detected {

    public static final Detected NONE = new Detected(FileType.UNKNOWN, new byte[0], null, false, 0);

    private final FileType type;
    private final byte[] body;
    private final String fileName;
    private final boolean decompressed;
    private final int originalLength;

    public Detected(FileType type, byte[] body, String fileName, boolean decompressed, int originalLength) {
        this.type = type;
        this.body = body;
        this.fileName = fileName;
        this.decompressed = decompressed;
        this.originalLength = originalLength;
    }

    public FileType type() {
        return type;
    }

    public byte[] body() {
        return body;
    }

    /** Filename from Content-Disposition or the request path, or null when unknown. */
    public String fileName() {
        return fileName;
    }

    /** True when the body was gzip/deflate encoded and has been expanded. */
    public boolean decompressed() {
        return decompressed;
    }

    /** Length of the body as it appeared on the wire. */
    public int originalLength() {
        return originalLength;
    }

    public boolean isRenderable() {
        return type != FileType.UNKNOWN;
    }
}
