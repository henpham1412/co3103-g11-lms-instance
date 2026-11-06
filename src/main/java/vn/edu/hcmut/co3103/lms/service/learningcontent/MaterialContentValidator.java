package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.cos.COSName;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.InflaterInputStream;

/** Parses local uploads; it never fetches an external material URL. */
@Component
public class MaterialContentValidator {
    private static final int PROBE_OUTPUT_LIMIT = 64 * 1024;
    private final String ffprobe;

    public MaterialContentValidator(@Value("${lms.storage.ffprobe-executable:ffprobe}") String ffprobe) {
        this.ffprobe = ffprobe;
    }

    public String documentType(Path file) throws IOException {
        byte[] prefix;
        try (InputStream input = Files.newInputStream(file)) { prefix = input.readNBytes(5); }
        try {
            if (Arrays.equals(prefix, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
                try (var source = new RandomAccessReadBufferedFile(file);
                     PDDocument document = new PDFParser(source).parse(false)) {
                    // Parse incrementally instead of retaining every page token in memory.
                    // Loading only the catalog would miss broken compressed content streams.
                    for (var page : document.getPages()) {
                        var streams = page.getContentStreams();
                        while (streams.hasNext()) {
                            var stream = streams.next();
                            // PDFBox deliberately tolerates damaged Flate payloads even in a
                            // strictly parsed document. Verify the zlib stream independently,
                            // after any preceding ASCII85/ASCIIHex filters have been decoded.
                            if (stream.getFilters().contains(COSName.FLATE_DECODE)
                                    || stream.getFilters().contains(COSName.FL)) {
                                try (InputStream encoded = stream.createInputStream(List.of("FlateDecode", "Fl"));
                                     var strict = new InflaterInputStream(encoded)) {
                                    strict.transferTo(OutputStream.nullOutputStream());
                                }
                            }
                            // PDPage's combined-stream convenience reader tolerates damaged
                            // streams. Decode each stream directly so failures reach validation.
                            try (InputStream decoded = stream.createInputStream()) {
                                decoded.transferTo(OutputStream.nullOutputStream());
                            }
                        }
                        PDFStreamParser parser = new PDFStreamParser(page);
                        try { while (parser.parseNextToken() != null) { /* validate content */ } }
                        finally { parser.close(); }
                    }
                    return "application/pdf";
                } catch (InvalidPasswordException passwordRequired) {
                    // A PDF recognized by the strict parser as password protected remains a
                    // supported document. Uploading it does not decrypt its page contents.
                    return "application/pdf";
                }
            }
            // OPC validates the ZIP/package and content types; the specific document parser
            // then validates its XML model. Mere entry names cannot establish an Office file.
            if (prefix.length >= 2 && prefix[0] == 'P' && prefix[1] == 'K') {
                try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
                    String type = pkg.getParts().stream()
                            .filter(part -> part.getPartName().getName().equals("/word/document.xml")
                                    || part.getPartName().getName().equals("/ppt/presentation.xml"))
                            .map(part -> part.getContentType()).findFirst().orElse("");
                    if (type.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")) {
                        try (XWPFDocument document = new XWPFDocument(pkg)) {
                            if (document.getDocument().getBody() == null) throw unsupported();
                            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                        }
                    }
                    if (type.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml")) {
                        try (XMLSlideShow presentation = new XMLSlideShow(pkg)) {
                            presentation.getSlides().forEach(slide -> slide.getXmlObject());
                            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                        }
                    }
                }
            }
        } catch (Exception invalid) {
            throw unsupported();
        }
        throw unsupported();
    }

    public String videoType(Path file) throws IOException {
        // Full demuxing counts actual packets, instead of accepting only ftyp/EBML headers or
        // track declarations. No codec allowlist is used: any video codec in MP4/WebM is eligible.
        Process process = new ProcessBuilder(ffprobe, "-v", "error",
                "-protocol_whitelist", "file", "-format_whitelist", "mov,mp4,m4a,3gp,3g2,mj2,matroska,webm",
                "-count_packets", "-show_entries",
                "format=format_name:format_tags=major_brand:stream=codec_type,nb_read_packets:stream_disposition=attached_pic",
                "-of", "json", "-i", file.toAbsolutePath().toString()).start();
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = readers.submit(() -> readProbeOutput(process.getInputStream()));
            var errors = readers.submit(() -> readProbeOutput(process.getErrorStream()));
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) throw unsupported();
                byte[] json = output.get(5, TimeUnit.SECONDS);
                byte[] stderr = errors.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0 || stderr.length != 0 || json.length > PROBE_OUTPUT_LIMIT) {
                    throw unsupported();
                }
                JsonNode result = JsonMapper.builder().build().readTree(json);
                boolean video = false;
                for (JsonNode stream : result.path("streams")) {
                    if ("video".equals(stream.path("codec_type").asString())
                            && stream.path("disposition").path("attached_pic").asInt(0) == 0
                            && Long.parseLong(stream.path("nb_read_packets").asString("0")) > 0) video = true;
                }
                if (!video) throw unsupported();
                String format = result.path("format").path("format_name").asString("");
                JsonNode tags = result.path("format").path("tags");
                String brand = tags.path("major_brand").asString("").strip();
                if (Arrays.asList(format.split(",")).contains("mp4") && !brand.isEmpty()
                        && !brand.equals("qt") && !brand.startsWith("3g") && !brand.startsWith("mj")) {
                    return "video/mp4";
                }
                if (Arrays.asList(format.split(",")).contains("webm") && hasWebmDocType(file)) {
                    return "video/webm";
                }
                throw unsupported();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORAGE_FAILED");
            } catch (Exception invalid) {
                throw unsupported();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }

    private byte[] readProbeOutput(InputStream input) throws IOException {
        try (input) { return input.readNBytes(PROBE_OUTPUT_LIMIT + 1); }
    }

    // ffprobe uses one demuxer for Matroska and WebM. Read the actual EBML DocType element
    // structurally to distinguish them; searching arbitrary bytes for the string is unsafe.
    private boolean hasWebmDocType(Path file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            if (ebmlInteger(input, true) != 0x1a45dfa3L) return false;
            long size = ebmlInteger(input, false);
            if (size > input.length() - input.getFilePointer()) return false;
            long end = input.getFilePointer() + size;
            boolean webm = false;
            while (input.getFilePointer() < end) {
                long id = ebmlInteger(input, true);
                long length = ebmlInteger(input, false);
                if (length > end - input.getFilePointer()) return false;
                if (id == 0x4282) {
                    if (webm || length != 4) return false;
                    byte[] value = new byte[4]; input.readFully(value);
                    if (!Arrays.equals(value, "webm".getBytes(StandardCharsets.US_ASCII))) return false;
                    webm = true;
                } else input.seek(input.getFilePointer() + length);
            }
            return webm && input.getFilePointer() == end;
        }
    }

    private long ebmlInteger(RandomAccessFile input, boolean identifier) throws IOException {
        int first = input.readUnsignedByte();
        if (first == 0) throw new IOException("Invalid EBML integer");
        int mask = 0x80, width = 1;
        while ((first & mask) == 0) { mask >>>= 1; width++; }
        if ((identifier && width > 4) || width > 8) throw new IOException("Invalid EBML integer");
        long value = identifier ? first : first & (mask - 1);
        for (int index = 1; index < width; index++) value = (value << 8) | input.readUnsignedByte();
        return value;
    }

    private ResponseStatusException unsupported() {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
    }
}
