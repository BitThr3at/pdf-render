package RLExtension.table;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal reader for the Compound File Binary container (aka OLE2), the wrapper that holds a
 * legacy .xls Workbook stream. Only what is needed to pull one named stream out: header, FAT,
 * MiniFAT and the directory.
 */
final class Cfb {

    private static final int ENDOFCHAIN = 0xFFFFFFFE;
    private static final int FREESECT = 0xFFFFFFFF;
    private static final int HEADER_SIZE = 512;
    private static final int DIRECTORY_ENTRY_SIZE = 128;

    private final byte[] data;
    private final int sectorSize;
    private final int miniSectorSize;
    private final int miniStreamCutoff;
    private final int[] fat;
    private final int[] miniFat;
    private final List<Entry> entries = new ArrayList<>();
    private byte[] miniStream = new byte[0];

    private record Entry(String name, int type, int startSector, long size) {
    }

    Cfb(byte[] data) throws IOException {
        this.data = data;
        require(data.length >= HEADER_SIZE, "file is shorter than a CFB header");

        this.sectorSize = 1 << u16(30);
        this.miniSectorSize = 1 << u16(32);
        this.miniStreamCutoff = i32(56);
        require(sectorSize >= 128 && sectorSize <= 1 << 20, "implausible CFB sector size");
        require(miniSectorSize >= 16 && miniSectorSize <= sectorSize, "implausible CFB mini sector size");

        this.fat = readFat();
        int firstDirSector = i32(48);
        readDirectory(firstDirSector);
        this.miniFat = readMiniFat(i32(60), i32(64));
        readMiniStream();
    }

    /** Returns the contents of the named stream, or null when the container has no such stream. */
    byte[] stream(String name) throws IOException {
        for (Entry entry : entries) {
            if (entry.type == 2 && entry.name.equals(name)) {
                return read(entry);
            }
        }
        return null;
    }

    boolean hasStream(String name) {
        for (Entry entry : entries) {
            if (entry.type == 2 && entry.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- allocation tables

    private int[] readFat() throws IOException {
        int fatSectorCount = i32(44);
        require(fatSectorCount >= 0 && (long) fatSectorCount * sectorSize < data.length + (long) sectorSize,
                "FAT sector count exceeds the file");

        List<Integer> fatSectors = new ArrayList<>();
        for (int i = 0; i < 109 && fatSectors.size() < fatSectorCount; i++) {
            int sector = i32(76 + i * 4);
            if (sector == FREESECT || sector == ENDOFCHAIN) {
                break;
            }
            fatSectors.add(sector);
        }

        // Any remaining FAT sector numbers live in the DIFAT chain.
        int difatSector = i32(68);
        int difatCount = i32(72);
        int entriesPerSector = sectorSize / 4 - 1;
        for (int i = 0; i < difatCount && difatSector != ENDOFCHAIN && difatSector != FREESECT; i++) {
            int base = offsetOf(difatSector);
            for (int j = 0; j < entriesPerSector && fatSectors.size() < fatSectorCount; j++) {
                int sector = i32(base + j * 4);
                if (sector == FREESECT || sector == ENDOFCHAIN) {
                    break;
                }
                fatSectors.add(sector);
            }
            difatSector = i32(base + entriesPerSector * 4);
        }

        int[] table = new int[fatSectors.size() * (sectorSize / 4)];
        int index = 0;
        for (int sector : fatSectors) {
            int base = offsetOf(sector);
            for (int j = 0; j < sectorSize / 4; j++) {
                table[index++] = i32(base + j * 4);
            }
        }
        return table;
    }

    private int[] readMiniFat(int firstSector, int sectorCount) throws IOException {
        if (firstSector == ENDOFCHAIN || sectorCount <= 0) {
            return new int[0];
        }
        byte[] raw = readChain(firstSector, (long) sectorCount * sectorSize);
        int[] table = new int[raw.length / 4];
        for (int i = 0; i < table.length; i++) {
            table[i] = ((raw[i * 4] & 0xFF))
                    | ((raw[i * 4 + 1] & 0xFF) << 8)
                    | ((raw[i * 4 + 2] & 0xFF) << 16)
                    | ((raw[i * 4 + 3] & 0xFF) << 24);
        }
        return table;
    }

    private void readDirectory(int firstSector) throws IOException {
        byte[] directory = readChain(firstSector, Limits.MAX_UNZIPPED_BYTES);
        int count = directory.length / DIRECTORY_ENTRY_SIZE;
        for (int i = 0; i < count; i++) {
            int base = i * DIRECTORY_ENTRY_SIZE;
            int nameBytes = ((directory[base + 64] & 0xFF) | ((directory[base + 65] & 0xFF) << 8));
            int type = directory[base + 66] & 0xFF;
            if (type == 0) {
                continue; // unallocated
            }
            int nameLength = Math.max(0, Math.min(nameBytes, 64) - 2); // drop the UTF-16 null
            String name = new String(directory, base, nameLength, StandardCharsets.UTF_16LE);
            int start = readInt(directory, base + 116);
            long size = readInt(directory, base + 120) & 0xFFFFFFFFL;
            entries.add(new Entry(name, type, start, size));
        }
    }

    private void readMiniStream() throws IOException {
        for (Entry entry : entries) {
            if (entry.type == 5) { // root storage holds the mini stream
                miniStream = readChain(entry.startSector, Math.max(entry.size, 0));
                return;
            }
        }
    }

    // ---------------------------------------------------------------- stream reads

    private byte[] read(Entry entry) throws IOException {
        if (entry.size < miniStreamCutoff && miniFat.length > 0) {
            return readMiniChain(entry.startSector, entry.size);
        }
        return readChain(entry.startSector, entry.size);
    }

    private byte[] readChain(int firstSector, long size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(size > 0 && size < 1 << 20 ? (int) size : 1 << 16);
        int sector = firstSector;
        int guard = 0;
        int maxSectors = fat.length + 1;
        while (sector != ENDOFCHAIN && sector != FREESECT && sector >= 0) {
            require(++guard <= maxSectors, "cyclic FAT chain");
            int offset = offsetOf(sector);
            int length = Math.min(sectorSize, data.length - offset);
            require(length > 0, "FAT chain points past the end of the file");
            out.write(data, offset, length);
            if (out.size() >= Limits.MAX_UNZIPPED_BYTES) {
                break;
            }
            require(sector < fat.length, "FAT chain leaves the allocation table");
            sector = fat[sector];
        }
        return truncate(out.toByteArray(), size);
    }

    private byte[] readMiniChain(int firstSector, long size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(size > 0 && size < 1 << 20 ? (int) size : 4096);
        int sector = firstSector;
        int guard = 0;
        while (sector != ENDOFCHAIN && sector != FREESECT && sector >= 0) {
            require(++guard <= miniFat.length + 1, "cyclic MiniFAT chain");
            int offset = sector * miniSectorSize;
            int length = Math.min(miniSectorSize, miniStream.length - offset);
            require(length > 0, "MiniFAT chain points past the end of the mini stream");
            out.write(miniStream, offset, length);
            require(sector < miniFat.length, "MiniFAT chain leaves the allocation table");
            sector = miniFat[sector];
        }
        return truncate(out.toByteArray(), size);
    }

    private static byte[] truncate(byte[] bytes, long size) {
        if (size <= 0 || size >= bytes.length) {
            return bytes;
        }
        byte[] exact = new byte[(int) size];
        System.arraycopy(bytes, 0, exact, 0, exact.length);
        return exact;
    }

    // ---------------------------------------------------------------- primitives

    private int offsetOf(int sector) throws IOException {
        long offset = HEADER_SIZE + (long) sector * sectorSize;
        require(offset >= 0 && offset < data.length, "sector " + sector + " is outside the file");
        return (int) offset;
    }

    private int u16(int offset) throws IOException {
        require(offset + 1 < data.length, "read past end of file");
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int i32(int offset) throws IOException {
        require(offset + 3 < data.length, "read past end of file");
        return readInt(data, offset);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException("Malformed .xls container: " + message);
        }
    }
}
