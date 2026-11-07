package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Mp4StructureValidatorTest {
    @TempDir Path directory;

    @ParameterizedTest(name = "{0}")
    @MethodSource("completeStructures")
    void acceptsCompleteBoxLayoutsWithoutInterpretingCodecPayload(String name, byte[] bytes) throws IOException {
        Path file = Files.write(directory.resolve("complete.mp4"), bytes);
        assertDoesNotThrow(() -> Mp4StructureValidator.validate(file));
    }

    static Stream<Arguments> completeStructures() {
        byte[] free = box("free", new byte[]{1, 2, 3});
        byte[] handler = concat(new byte[8], "mdir".getBytes(StandardCharsets.US_ASCII), new byte[13]);
        return Stream.of(
                Arguments.of("32-bit boxes", concat(free, box("abcd", new byte[]{9}))),
                Arguments.of("64-bit box", extended("free", new byte[]{1})),
                Arguments.of("zero-size mdat extends to EOF", concat(free, header("mdat", 0), new byte[]{1, 2})),
                Arguments.of("nested zero-size box ends at EOF", box("moov", box("trak", concat(header("free", 0), new byte[]{1})))),
                Arguments.of("nested extended box", box("moov", extended("free", new byte[]{1}))),
                Arguments.of("ISO FullBox meta", box("meta", concat(new byte[4], free))),
                Arguments.of("ISO meta extended child", box("meta", concat(new byte[4], extended("free", new byte[]{1})))),
                Arguments.of("QuickTime prefix-free meta", box("meta", concat(box("hdlr", handler), free))),
                Arguments.of("QuickTime meta zero-size handler", box("meta", concat(header("hdlr", 0), handler))),
                Arguments.of("QuickTime meta extended handler", box("meta", extended("hdlr", handler))),
                Arguments.of("QuickTime metadata extension before handler", box("meta", concat(box("abcd", new byte[]{1}), box("hdlr", handler)))),
                Arguments.of("ISO meta zero-size child", box("meta", concat(new byte[4], header("free", 0), new byte[]{1}))),
                Arguments.of("metadata item box", box("meta", concat(new byte[4], box("ilst", box("\u00a9too", box("data", new byte[8])))))),
                Arguments.of("unknown codec sample entry is opaque", box("stsd", concat(fullCount(1), box("zzzz", header("free", 100))))),
                Arguments.of("data reference entries", box("dref", concat(fullCount(1), box("url ", new byte[4])))),
                Arguments.of("UUID normal header", box("uuid", new byte[18])),
                Arguments.of("UUID extended header", extended("uuid", new byte[17])),
                Arguments.of("UUID zero-size header", concat(header("uuid", 0), new byte[16])),
                Arguments.of("fragment containers", concat(box("moov", box("mvex", box("trex", new byte[8]))),
                        box("moof", box("traf", box("tfhd", new byte[8]))), box("mdat", new byte[]{1}))),
                Arguments.of("unknown visual codec uses standard entry layout", media("vide", box("zzzz", concat(new byte[78], box("zzcf", new byte[]{1}))), true)),
                Arguments.of("handler may follow sample table", media("vide", box("avc1", concat(new byte[78], box("avcC", new byte[]{1}))), false)),
                Arguments.of("audio version zero", media("soun", audio(0, 28, box("esds", new byte[4])), true)),
                Arguments.of("audio version one", media("soun", audio(1, 44, box("wave", box("esds", new byte[4]))), true)),
                Arguments.of("audio version two", media("soun", audio(2, 64, box("chan", new byte[4])), true)),
                Arguments.of("future audio layout remains opaque", media("soun", audio(3, 28, new byte[]{1}), true)),
                Arguments.of("unknown extension is not recursively scanned", box("abcd", header("free", 100))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteStructures")
    void rejectsIncompleteHeadersAndParentOverrunsWithoutOverflow(String name, byte[] bytes) throws IOException {
        Path file = Files.write(directory.resolve("incomplete.mp4"), bytes);
        assertThrows(IOException.class, () -> Mp4StructureValidator.validate(file));
    }

    static Stream<Arguments> incompleteStructures() {
        return Stream.of(
                Arguments.of("empty", new byte[0]),
                Arguments.of("partial header", new byte[7]),
                Arguments.of("32-bit size below header", header("free", 7)),
                Arguments.of("incomplete extended header", concat(header("free", 1), new byte[7])),
                Arguments.of("extended size below header", concat(header("free", 1), longBytes(15))),
                Arguments.of("unsigned 64-bit high bit", concat(header("free", 1), longBytes(Long.MIN_VALUE))),
                Arguments.of("64-bit declared size exceeds file", concat(header("free", 1), longBytes(Long.MAX_VALUE))),
                Arguments.of("unsigned 32-bit size exceeds file", header("free", 0xffff_ffffL)),
                Arguments.of("tail box overrun", header("free", 100)),
                Arguments.of("nested child overrun", box("moov", header("trak", 100))),
                Arguments.of("nested partial tail header", box("moov", concat(box("free", new byte[0]), new byte[1]))),
                Arguments.of("nested incomplete 64-bit header", box("moov", concat(header("free", 1), new byte[4]))),
                Arguments.of("nested size zero cannot overrun parent", concat(box("moov", header("free", 0)), box("mdat", new byte[0]))),
                Arguments.of("UUID missing user type", header("uuid", 8)),
                Arguments.of("extended UUID missing user type", extended("uuid", new byte[15])),
                Arguments.of("meta missing FullBox prefix", box("meta", new byte[2])),
                Arguments.of("ISO meta overrun cannot become QuickTime fallback", box("meta", concat(new byte[4], header("free", 100)))),
                Arguments.of("large meta size cannot masquerade as a fourcc", box("meta", concat(new byte[4], header("free", 0x41424344L)))),
                Arguments.of("large meta size cannot masquerade as handler", box("meta", concat(new byte[4], header("free", 0x68646c72L)))),
                Arguments.of("QuickTime meta overrun", box("meta", header("free", 100))),
                Arguments.of("sample table incomplete prefix", box("stsd", new byte[4])),
                Arguments.of("sample entry count exceeds available entries", box("stsd", concat(fullCount(2), box("avc1", new byte[0])))),
                Arguments.of("unlisted extra sample entry", box("stsd", concat(fullCount(1), box("avc1", new byte[0]), box("zzzz", new byte[0])))),
                Arguments.of("sample entry boundary exceeds stsd", box("stsd", concat(fullCount(1), header("avc1", 100)))),
                Arguments.of("data reference entry overrun", box("dref", concat(fullCount(1), header("url ", 100)))),
                Arguments.of("visual codec child overrun", media("vide", box("mp4v", concat(new byte[78], header("esds", 10_000))), true)),
                Arguments.of("visual fixed fields truncated", media("vide", box("zzzz", new byte[77]), true)),
                Arguments.of("visual codec child header undersized", media("vide", box("avc1", concat(new byte[78], header("avcC", 4))), true)),
                Arguments.of("audio version zero fixed fields truncated", media("soun", audio(0, 27, new byte[0]), true)),
                Arguments.of("audio version one fixed fields truncated", media("soun", audio(1, 43, new byte[0]), true)),
                Arguments.of("audio version two fixed fields truncated", media("soun", audio(2, 63, new byte[0]), true)),
                Arguments.of("audio codec child overrun", media("soun", audio(0, 28, header("esds", 100)), true)),
                Arguments.of("missing media handler", box("mdia", box("minf", box("stbl", box("stsd", concat(fullCount(1), box("avc1", new byte[78]))))))),
                Arguments.of("duplicate media handler", box("mdia", concat(handler("vide"), handler("soun")))),
                Arguments.of("nested metadata data box overrun", box("ilst", box("\u00a9too", header("data", 100)))));
    }

    @Test
    void handlesDeepContainersWithoutRecursiveJavaCalls() throws IOException {
        byte[] bytes = box("free", new byte[0]);
        for (int index = 0; index < 2_000; index++) bytes = box("udta", bytes);
        Path file = Files.write(directory.resolve("nested.mp4"), bytes);
        assertDoesNotThrow(() -> Mp4StructureValidator.validate(file));
    }

    private static byte[] box(String type, byte[] payload) { return concat(header(type, payload.length + 8L), payload); }
    private static byte[] extended(String type, byte[] payload) { return concat(header(type, 1), longBytes(payload.length + 16L), payload); }
    private static byte[] fullCount(int count) { return ByteBuffer.allocate(8).putInt(0).putInt(count).array(); }
    private static byte[] longBytes(long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
    private static byte[] handler(String type) {
        return box("hdlr", concat(new byte[8], type.getBytes(StandardCharsets.US_ASCII), new byte[13]));
    }
    private static byte[] media(String type, byte[] entry, boolean handlerFirst) {
        byte[] minf = box("minf", box("stbl", box("stsd", concat(fullCount(1), entry))));
        return box("mdia", handlerFirst ? concat(handler(type), minf) : concat(minf, handler(type)));
    }
    private static byte[] audio(int version, int prefix, byte[] children) {
        byte[] fixed = new byte[prefix];
        ByteBuffer.wrap(fixed).putShort(8, (short) version);
        return box("mp4a", concat(fixed, children));
    }
    private static byte[] header(String type, long size) {
        return ByteBuffer.allocate(8).putInt((int) size).put(type.getBytes(StandardCharsets.ISO_8859_1)).array();
    }
    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        ByteBuffer result = ByteBuffer.allocate(length);
        for (byte[] part : parts) result.put(part);
        return result.array();
    }
}
