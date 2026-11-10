package ycscite.io;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free PDF report writer (PDF 1.4): page 1 = full-page network image (JPEG,
 * DCTDecode), page 2 = summary table (Helvetica, WinAnsi; non-Latin-1 characters are
 * transliterated to '?').
 */
public final class PdfExporter {

    /** One undistorted image page per selected chart; CJK labels remain exactly as rendered. */
    public static void writeFigures(Path out, List<Path> figures) throws IOException {
        if (figures.isEmpty()) throw new IllegalArgumentException("No figures for PDF");
        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII));
        StringBuilder kids = new StringBuilder();
        for (int i=0;i<figures.size();i++) kids.append(3+3*i).append(" 0 R ");
        objects.add(("<< /Type /Pages /Kids ["+kids+"] /Count "+figures.size()+" >>").getBytes(StandardCharsets.US_ASCII));
        for (int i=0;i<figures.size();i++) {
            BufferedImage image=ImageIO.read(figures.get(i).toFile());
            if(image==null)throw new IOException("Unreadable figure: "+figures.get(i));
            double width=792, height=width*image.getHeight()/image.getWidth();
            int page=3+3*i, content=page+1, bitmap=page+2;
            String geometry=String.format(java.util.Locale.ROOT,"%.3f %.3f",width,height);
            objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "+geometry+"] /Resources << /XObject << /Im "+bitmap+" 0 R >> >> /Contents "+content+" 0 R >>").getBytes(StandardCharsets.US_ASCII));
            byte[] drawing=String.format(java.util.Locale.ROOT,"q %.3f 0 0 %.3f 0 0 cm /Im Do Q",width,height).getBytes(StandardCharsets.US_ASCII);
            objects.add(concat(("<< /Length "+drawing.length+" >>\nstream\n").getBytes(StandardCharsets.US_ASCII),drawing,"\nendstream".getBytes(StandardCharsets.US_ASCII)));
            ByteArrayOutputStream jpg=new ByteArrayOutputStream();ImageIO.write(image,"jpg",jpg);
            byte[] bytes=jpg.toByteArray();
            objects.add(concat(("<< /Type /XObject /Subtype /Image /Width "+image.getWidth()+" /Height "+image.getHeight()+" /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "+bytes.length+" >>\nstream\n").getBytes(StandardCharsets.US_ASCII),bytes,"\nendstream".getBytes(StandardCharsets.US_ASCII)));
            image.flush();
        }
        ByteArrayOutputStream pdf=new ByteArrayOutputStream();
        pdf.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets=new ArrayList<>();offsets.add(0);
        for(int i=0;i<objects.size();i++){offsets.add(pdf.size());pdf.write(((i+1)+" 0 obj\n").getBytes(StandardCharsets.US_ASCII));pdf.write(objects.get(i));pdf.write("\nendobj\n".getBytes(StandardCharsets.US_ASCII));}
        int xref=pdf.size();pdf.write(("xref\n0 "+offsets.size()+"\n0000000000 65535 f \n").getBytes(StandardCharsets.US_ASCII));
        for(int i=1;i<offsets.size();i++)pdf.write(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offsets.get(i)).getBytes(StandardCharsets.US_ASCII));
        pdf.write(("trailer\n<< /Size "+offsets.size()+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        Files.write(out,pdf.toByteArray());
    }

    public static void write(Path out, BufferedImage graphImage, Network g, String title) throws IOException {
        // --- page-1 image as JPEG ---
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(graphImage, "jpg", jpg);
        byte[] img = jpg.toByteArray();

        StringBuilder text = new StringBuilder("BT\n/F1 12 Tf\n14 TL\n40 800 Td\n");
        text.append(esc(title)).append(" Tj T*\n");
        text.append("/F1 10 Tf\n12 TL\n40 780 Td\n");
        for (String line : summaryLines(g)) text.append(esc(line)).append(" Tj T*\n");
        text.append("ET\n");

        // --- assemble objects ---
        List<byte[]> objs = new ArrayList<>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.ISO_8859_1));                     // 1
        objs.add(("<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>").getBytes(StandardCharsets.ISO_8859_1));     // 2
        objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Im1 5 0 R >> >> /Contents 4 0 R >>"
                .getBytes(StandardCharsets.ISO_8859_1));                                                          // 3
        byte[] c1 = "q 532 0 0 712 40 40 cm /Im1 Do Q".getBytes(StandardCharsets.ISO_8859_1);
        objs.add(concat("<< /Length ".getBytes(StandardCharsets.ISO_8859_1),
                String.valueOf(c1.length).getBytes(StandardCharsets.ISO_8859_1),
                " >>\nstream\n".getBytes(StandardCharsets.ISO_8859_1),
                c1, "\nendstream".getBytes(StandardCharsets.ISO_8859_1)));                                        // 4
        objs.add(concat(("<< /Type /XObject /Subtype /Image /Width " + graphImage.getWidth()
                        + " /Height " + graphImage.getHeight()
                        + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ")
                        .getBytes(StandardCharsets.ISO_8859_1),
                String.valueOf(img.length).getBytes(StandardCharsets.ISO_8859_1),
                " >>\nstream\n".getBytes(StandardCharsets.ISO_8859_1),
                img, "\nendstream".getBytes(StandardCharsets.ISO_8859_1)));                                       // 5
        objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 8 0 R >> >> /Contents 7 0 R >>"
                .getBytes(StandardCharsets.ISO_8859_1));                                                          // 6
        byte[] c2 = text.toString().getBytes(StandardCharsets.ISO_8859_1);
        objs.add(concat("<< /Length ".getBytes(StandardCharsets.ISO_8859_1),
                String.valueOf(c2.length).getBytes(StandardCharsets.ISO_8859_1),
                " >>\nstream\n".getBytes(StandardCharsets.ISO_8859_1),
                c2, "\nendstream".getBytes(StandardCharsets.ISO_8859_1)));                                        // 7
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
                .getBytes(StandardCharsets.ISO_8859_1));                                                          // 8

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1));
        long[] offsets = new long[objs.size() + 1];
        for (int i = 0; i < objs.size(); i++) {
            offsets[i + 1] = pdf.size();
            pdf.writeBytes(String.valueOf(i + 1).getBytes(StandardCharsets.ISO_8859_1));
            pdf.writeBytes(" 0 obj\n".getBytes(StandardCharsets.ISO_8859_1));
            pdf.writeBytes(objs.get(i));
            pdf.writeBytes("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        long xrefPos = pdf.size();
        pdf.writeBytes(("xref\n0 " + (objs.size() + 1) + "\n").getBytes(StandardCharsets.ISO_8859_1));
        pdf.writeBytes("0000000000 65535 f \n".getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 1; i <= objs.size(); i++)
            pdf.writeBytes((String.format("%010d 00000 n \n", offsets[i])).getBytes(StandardCharsets.ISO_8859_1));
        pdf.writeBytes(("trailer\n<< /Size " + (objs.size() + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xrefPos + "\n%%EOF").getBytes(StandardCharsets.ISO_8859_1));
        Files.write(out, pdf.toByteArray());
    }

    private static List<String> summaryLines(Network g) {
        List<String> lines = new ArrayList<>();
        lines.add("Ycscite - network summary");
        lines.add("network: " + g.kind + "   nodes: " + g.nodes.size() + "   links: " + g.edges.size());
        lines.add("");
        lines.add(String.format("%-24s %6s %8s %8s %6s", "node", "freq", "centr.", "sigma", "clstr"));
        g.nodeCollection().stream()
                .sorted((a, b) -> Integer.compare(b.weight, a.weight))
                .limit(30)
                .forEach(n -> lines.add(String.format("%-24s %6d %8.3f %8.2f %6d",
                        trunc(n.label, 24), n.weight, n.centrality, n.sigma, n.cluster)));
        return lines;
    }

    private static String trunc(String s, int len) { return s.length() <= len ? s : s.substring(0, len - 1) + "~"; }

    private static String winAnsi(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray())
            sb.append(c >= 32 && c <= 255 && c != 127 ? c : '?');
        return sb.toString();
    }

    private static String esc(String s) { return "(" + winAnsi(s).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"; }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, off, p.length); off += p.length; }
        return out;
    }
}
