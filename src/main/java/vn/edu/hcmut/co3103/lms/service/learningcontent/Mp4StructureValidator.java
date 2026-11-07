package vn.edu.hcmut.co3103.lms.service.learningcontent;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Set;

/** Strict ISO-BMFF box framing, independent of ffprobe's tolerant media demuxing. */
final class Mp4StructureValidator {
    private static final Set<String> CONTAINERS = Set.of(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf",
            "mfra", "udta", "sinf", "schi", "tref", "iprp", "ipco", "meco", "hnti", "hinf", "wave");
    private static final Set<String> OPAQUE_METADATA = Set.of("free", "skip", "uuid");

    private Mp4StructureValidator() { }

    static void validate(Path file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            long fileEnd = input.length();
            if (fileEnd < 8) throw incomplete();
            // Explicit frames avoid Java call-stack exhaustion on nested containers. Every
            // child consumes at least its header, and every region stays within the file.
            var regions = new ArrayDeque<Region>();
            regions.push(new Region(0, fileEnd, Layout.BOXES, -1));
            while (!regions.isEmpty()) {
                Region region = regions.peek();
                if (region.position == region.end) {
                    if (region.expectedEntries >= 0 && region.entries != region.expectedEntries) throw incomplete();
                    regions.pop();
                    continue;
                }
                Box box = readBox(input, region.position, region.end, fileEnd);
                region.position = box.end;
                region.entries++;
                if (region.expectedEntries >= 0 && region.entries > region.expectedEntries) throw incomplete();
                if (region.layout == Layout.OPAQUE_ENTRIES) continue;
                if (region.layout == Layout.SAMPLE_ENTRIES) {
                    long prefix = sampleEntryPrefix(input, box, region.handler);
                    if (prefix >= 0) regions.push(new Region(box.payload + prefix, box.end, Layout.BOXES, -1, region.handler));
                    continue;
                }

                Region children = null;
                if (region.layout == Layout.METADATA_ITEMS && !OPAQUE_METADATA.contains(box.type)) {
                    // An ilst item wraps data/mean/name boxes. Their payloads are opaque.
                    children = new Region(box.payload, box.end, Layout.BOXES, -1, region.handler);
                } else if (box.type.equals("mdia")) {
                    children = new Region(box.payload, box.end, Layout.BOXES, -1, mediaHandler(input, box, fileEnd));
                } else if (CONTAINERS.contains(box.type)) {
                    children = new Region(box.payload, box.end, Layout.BOXES, -1, region.handler);
                } else if (box.type.equals("ilst")) {
                    children = new Region(box.payload, box.end, Layout.METADATA_ITEMS, -1);
                } else if (box.type.equals("meta")) {
                    children = metaChildren(input, box, fileEnd);
                } else if (box.type.equals("stsd") || box.type.equals("dref")) {
                    // FullBox version/flags + entry_count. Media handler determines the
                    // standard sample-entry layout; no coding-name allowlist is involved.
                    if (box.end - box.payload < 8) throw incomplete();
                    input.seek(box.payload + 4);
                    long count = Integer.toUnsignedLong(input.readInt());
                    long start = box.payload + 8;
                    if (count > (box.end - start) / 8) throw incomplete();
                    children = new Region(start, box.end, box.type.equals("stsd")
                            ? Layout.SAMPLE_ENTRIES : Layout.OPAQUE_ENTRIES, count, region.handler);
                }
                if (children != null) regions.push(children);
                // mdat/free and unrecognized extension payloads are not box streams.
            }
        }
    }

    private static String mediaHandler(RandomAccessFile input, Box media, long fileEnd) throws IOException {
        long position = media.payload;
        String handler = null;
        // Scan direct children first so hdlr/minf ordering does not affect validation.
        while (position < media.end) {
            Box child = readBox(input, position, media.end, fileEnd);
            if (child.type.equals("hdlr")) {
                if (handler != null || child.end - child.payload < 24) throw incomplete();
                input.seek(child.payload + 8);
                byte[] type = new byte[4]; input.readFully(type);
                handler = new String(type, StandardCharsets.ISO_8859_1);
            }
            position = child.end;
        }
        if (handler == null) throw incomplete();
        return handler;
    }

    private static long sampleEntryPrefix(RandomAccessFile input, Box box, String handler) throws IOException {
        long prefix = -1;
        if ("vide".equals(handler)) prefix = 78; // VisualSampleEntry, after the box header
        else if ("soun".equals(handler)) {
            if (box.end - box.payload < 28) throw incomplete();
            input.seek(box.payload + 8);
            prefix = switch (input.readUnsignedShort()) {
                case 0 -> 28;
                case 1 -> 44;
                case 2 -> 64;
                default -> -1; // Unrecognized extension layout remains opaque, not rejected.
            };
        }
        if (prefix > box.end - box.payload) throw incomplete();
        return prefix;
    }

    private static Box readBox(RandomAccessFile input, long start, long parentEnd, long fileEnd)
            throws IOException {
        long available = parentEnd - start;
        if (available < 8) throw incomplete();
        input.seek(start);
        long size = Integer.toUnsignedLong(input.readInt());
        byte[] typeBytes = new byte[4]; input.readFully(typeBytes);
        String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
        long header = 8;
        if (size == 1) {
            if (available < 16) throw incomplete();
            size = input.readLong();
            // Unsigned largesize >= 2^63 cannot fit this Java file (or the upload limit).
            if (size < 0) throw incomplete();
            header = 16;
        } else if (size == 0) {
            // ISO-BMFF size zero extends to EOF, not silently to a shorter parent boundary.
            size = fileEnd - start;
        }
        if (type.equals("uuid")) header += 16;
        // Check by subtraction BEFORE addition, including the enclosing box boundary.
        // Once size <= available, start + size cannot overflow or extend past EOF.
        if (size < header || size > available) throw incomplete();
        return new Box(type, start + header, start + size);
    }

    private static Region metaChildren(RandomAccessFile input, Box box, long fileEnd) throws IOException {
        if (box.end - box.payload < 4) throw incomplete();
        // ISO meta is a FullBox; the QuickTime variant starts directly with child boxes.
        // Recognize the latter by complete child framing and its actual HandlerReference
        // box, not by guessing whether a numeric child size resembles a printable fourcc.
        boolean prefixFree = hasQuickTimeHandler(input, box, fileEnd);
        return new Region(box.payload + (prefixFree ? 0 : 4), box.end, Layout.BOXES, -1);
    }

    private static boolean hasQuickTimeHandler(RandomAccessFile input, Box meta, long fileEnd) throws IOException {
        long position = meta.payload;
        boolean handler = false;
        while (position < meta.end) {
            Box child;
            try { child = readBox(input, position, meta.end, fileEnd); }
            catch (IOException notPrefixFree) { return false; }
            if (child.type.equals("hdlr") && child.end - child.payload >= 24) {
                input.seek(child.payload);
                handler |= input.readInt() == 0; // HandlerReference version/flags
            }
            position = child.end;
        }
        return handler;
    }

    private static IOException incomplete() { return new IOException("Incomplete MP4 box structure"); }

    private enum Layout { BOXES, METADATA_ITEMS, OPAQUE_ENTRIES, SAMPLE_ENTRIES }
    private record Box(String type, long payload, long end) { }
    private static final class Region {
        private long position;
        private final long end;
        private final Layout layout;
        private final long expectedEntries;
        private final String handler;
        private long entries;

        private Region(long position, long end, Layout layout, long expectedEntries) {
            this(position, end, layout, expectedEntries, null);
        }

        private Region(long position, long end, Layout layout, long expectedEntries, String handler) {
            this.position = position;
            this.end = end;
            this.layout = layout;
            this.expectedEntries = expectedEntries;
            this.handler = handler;
        }
    }
}
