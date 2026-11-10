package ycscite.io;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-dependency Motion-JPEG AVI writer for the timeline-evolution video export:
 * RIFF(AVI ) → LIST hdrl(avih,strh,strf) → LIST movi(00dc JPEG frames) → idx1.
 */
public final class AviWriter implements AutoCloseable {

    private final OutputStream out;
    private final java.io.File file;
    private final int width, height, fps;
    private final List<long[]> index = new ArrayList<>(); // offset, size
    private long bytesWritten = 4;                        // after "RIFF" size field
    private int frames = 0;

    public AviWriter(Path path, int width, int height, int fps) throws IOException {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.file = path.toFile();
        this.out = Files.newOutputStream(path);
        out.write('R'); out.write('I'); out.write('F'); out.write('F');
        out.write(new byte[4]); // RIFF size patched on close
        out.write('A'); out.write('V'); out.write('I'); out.write(' ');
        bytesWritten += 4;
        writeHeader();
        out.write('L'); out.write('I'); out.write('S'); out.write('T');
        writeInt(4); bytesWritten += 8;
        out.write('m'); out.write('o'); out.write('v'); out.write('i'); bytesWritten += 4;
    }

    private void writeHeader() throws IOException {
        int usPerFrame = 1_000_000 / fps;
        byte[] avih = new byte[56];
        putInt(avih, 0, usPerFrame);
        putInt(avih, 12, 0x10);                 // flags AVIF_HASINDEX
        putInt(avih, 16, framesPlaceholder());  // total frames (patch not needed for players; use 0 ok)
        putInt(avih, 20, 1);                    // streams
        putInt(avih, 32, width);
        putInt(avih, 36, height);
        chunk("avih", avih);

        byte[] strh = new byte[56];
        strh[0] = 'v'; strh[1] = 'i'; strh[2] = 'd'; strh[3] = 's';
        strh[4] = 'M'; strh[5] = 'J'; strh[6] = 'P'; strh[7] = 'G';
        putInt(strh, 20, 1);                    // initial frame
        putInt(strh, 24, usPerFrame);
        putInt(strh, 32, 0x10);                 // quality-ish / flags
        putShort(strh, 52, (short) 0);          // rcFrame stubs left 0
        chunk("strh", strh);

        byte[] strf = new byte[40];
        putInt(strf, 0, 40);                    // BITMAPINFOHEADER size
        putInt(strf, 4, width);
        putInt(strf, 8, height);
        putShort(strf, 12, (short) 1);
        putShort(strf, 14, (short) 24);         // bits per pixel
        putInt(strf, 20, width * height * 3);   // image size approx
        chunk("strf", strf);
    }

    private int framesPlaceholder() { return 0; }

    public void addFrame(BufferedImage img) throws IOException {
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", jpg);
        byte[] data = jpg.toByteArray();
        long offset = bytesWritten;             // offset within RIFF payload, relative to "movi" list start handled by players as absolute-from-movi; store payload offset
        index.add(new long[]{offset, data.length + 8});
        out.write('0'); out.write('0'); out.write('d'); out.write('c');
        writeInt(data.length);
        out.write(data);
        if ((data.length & 1) == 1) out.write(0);
        bytesWritten += 8 + data.length + (data.length & 1);
        frames++;
    }

    @Override
    public void close() throws IOException {
        // idx1
        out.write('i'); out.write('d'); out.write('x'); out.write('1');
        ByteArrayOutputStream idx = new ByteArrayOutputStream();
        for (long[] e : index) {
            idx.write('0'); idx.write('0'); idx.write('d'); idx.write('c');
            byte[] b = new byte[12];
            putInt(b, 0, 0x10);                 // AVIIF_KEYFRAME
            putInt(b, 4, (int) e[0]);
            putInt(b, 8, (int) e[1]);
            idx.write(b);
        }
        byte[] idxBytes = idx.toByteArray();
        writeInt(idxBytes.length);
        out.write(idxBytes);
        if ((idxBytes.length & 1) == 1) out.write(0);
        bytesWritten += 8 + idxBytes.length + (idxBytes.length & 1);

        out.close();
        // patch RIFF size
        long riffSize = bytesWritten;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.seek(4);
            raf.writeInt((int) riffSize);
        }
    }

    public int framesWritten() { return frames; }

    // ---- byte helpers ----
    private void chunk(String fourcc, byte[] payload) throws IOException {
        out.write(fourcc.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeInt(payload.length);
        out.write(payload);
        if ((payload.length & 1) == 1) out.write(0);
        bytesWritten += 8 + payload.length + (payload.length & 1);
    }

    private void writeInt(int v) throws IOException {
        out.write(v & 0xFF); out.write((v >> 8) & 0xFF); out.write((v >> 16) & 0xFF); out.write((v >> 24) & 0xFF);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8); b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }

    private static void putShort(byte[] b, int off, short v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
    }
}
