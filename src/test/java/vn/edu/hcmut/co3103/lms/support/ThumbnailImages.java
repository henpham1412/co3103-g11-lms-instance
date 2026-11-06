package vn.edu.hcmut.co3103.lms.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** Small, complete encoded images, including a WebP fixture independent of the Java decoder. */
public final class ThumbnailImages {
    private ThumbnailImages() { }

    public static byte[] png() { return read("png"); }
    public static byte[] jpeg() { return read("jpg"); }
    public static byte[] webp() { return read("webp"); }

    public record InvalidImage(String mimeType, byte[] bytes) { }

    public static List<InvalidImage> invalidImages() {
        byte[] badPng = png();
        badPng[29] ^= 1; // Corrupt an IHDR CRC, which a lenient reader might ignore.
        byte[] badPngPixels = png();
        for (int offset = 8; offset < badPngPixels.length - 12;) {
            int size = ByteBuffer.wrap(badPngPixels, offset, 4).getInt();
            if (badPngPixels[offset + 4] == 'I' && badPngPixels[offset + 5] == 'D') {
                badPngPixels[offset + 8] ^= (byte) 0xff; // Bad compressed data, but valid chunk CRC.
                CRC32 crc = new CRC32();
                crc.update(badPngPixels, offset + 4, size + 4);
                ByteBuffer.wrap(badPngPixels, offset + 8 + size, 4).putInt((int) crc.getValue());
                break;
            }
            offset += size + 12;
        }
        byte[] badJpeg = jpeg();
        for (int i = 0; i < badJpeg.length - 4; i++) {
            if ((badJpeg[i] & 0xff) == 0xff && (badJpeg[i + 1] & 0xff) == 0xda) {
                badJpeg[i + 2] = 0;
                badJpeg[i + 3] = 1; // Invalid start-of-scan segment length; keep the trailer.
                break;
            }
        }
        byte[] badWebp = webp();
        badWebp[12] = 'X'; // Invalid image chunk, preserving the RIFF length.
        return List.of(
                new InvalidImage("image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}),
                new InvalidImage("image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}),
                new InvalidImage("image/webp", new byte[]{'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'}),
                new InvalidImage("image/png", Arrays.copyOf(png(), png().length - 1)),
                new InvalidImage("image/jpeg", Arrays.copyOf(jpeg(), jpeg().length - 1)),
                new InvalidImage("image/webp", Arrays.copyOf(webp(), webp().length - 1)),
                new InvalidImage("image/png", badPng),
                new InvalidImage("image/png", badPngPixels),
                new InvalidImage("image/jpeg", badJpeg),
                new InvalidImage("image/webp", badWebp));
    }

    private static byte[] read(String extension) {
        try (var stream = ThumbnailImages.class.getResourceAsStream("/images/thumbnail-valid." + extension)) {
            if (stream == null) throw new IllegalStateException("Missing image fixture");
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
