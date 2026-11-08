package vn.edu.hcmut.co3103.lms.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import vn.edu.hcmut.co3103.lms.entity.Material;

public final class MaterialFiles {
    private MaterialFiles() { }

    public static byte[] read(String name) {
        try (var input = MaterialFiles.class.getResourceAsStream("/learningcontent/" + name)) {
            if (input == null) throw new IllegalArgumentException("Missing material fixture: " + name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public record InvalidFile(String name, Material.MaterialType type, byte[] bytes) {
        @Override public String toString() { return name; }
    }

    public static List<InvalidFile> incompleteMp4Files() {
        byte[] valid = read("valid.mp4");
        byte[] tail = Arrays.copyOf(valid, valid.length + 8);
        // A free box declaring 100 bytes has only its eight-byte header available.
        tail[valid.length + 3] = 100;
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, tail, valid.length + 4, 4);
        byte[] sampleOverrun = valid.clone();
        // The fixture's esds child starts at 607 inside its mp4v sample entry.
        if (!new String(valid, 611, 4, StandardCharsets.US_ASCII).equals("esds")) {
            throw new IllegalStateException("Unexpected MP4 fixture layout");
        }
        java.nio.ByteBuffer.wrap(sampleOverrun).putInt(607, 10_000);
        return List.of(video("mp4-minus-one", Arrays.copyOf(valid, valid.length - 1)),
                video("mp4-minus-eight", Arrays.copyOf(valid, valid.length - 8)),
                video("mp4-incomplete-tail-box", tail), video("mp4-sample-entry-child-overrun", sampleOverrun));
    }

    public static List<InvalidFile> invalidFiles() {
        return List.of(
                video("audio-only-mp4", read("audio-only.mp4")),
                video("audio-with-cover-mp4", read("audio-with-cover.mp4")),
                video("mp4-header-only", new byte[]{0,0,0,16,'f','t','y','p','i','s','o','m',0,0,0,0}),
                video("webm-header-only", new byte[]{0x1a,0x45,(byte)0xdf,(byte)0xa3,(byte)0x87,
                        0x42,(byte)0x82,(byte)0x84,'w','e','b','m'}),
                video("truncated-mp4", half(read("valid.mp4"))),
                video("truncated-webm", half(read("valid.webm"))),
                video("truncated-webm-last-byte", Arrays.copyOf(read("valid.webm"), read("valid.webm").length - 1)),
                video("matroska", read("unsupported.mkv")),
                new InvalidFile("pdf-header-only", Material.MaterialType.DOCUMENT, "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)),
                new InvalidFile("pdf-garbage", Material.MaterialType.DOCUMENT, "%PDF-1.7 garbage %%EOF".getBytes(StandardCharsets.US_ASCII)),
                new InvalidFile("truncated-pdf", Material.MaterialType.DOCUMENT, half(read("valid.pdf"))),
                new InvalidFile("truncated-encrypted-pdf", Material.MaterialType.DOCUMENT, half(read("valid-encrypted.pdf"))),
                new InvalidFile("invalid-pdf-content", Material.MaterialType.DOCUMENT, read("invalid-pdf-content.pdf")),
                new InvalidFile("invalid-docx-xml", Material.MaterialType.DOCUMENT, fakeOffice("word/document.xml")),
                new InvalidFile("invalid-pptx-xml", Material.MaterialType.DOCUMENT, fakeOffice("ppt/presentation.xml")));
    }

    private static InvalidFile video(String name, byte[] bytes) {
        return new InvalidFile(name, Material.MaterialType.VIDEO, bytes);
    }

    private static byte[] half(byte[] bytes) { return Arrays.copyOf(bytes, bytes.length / 2); }

    private static byte[] fakeOffice(String part) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (String name : List.of("[Content_Types].xml", part)) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write("not XML".getBytes(StandardCharsets.US_ASCII));
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
}
