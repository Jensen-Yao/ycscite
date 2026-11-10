package ycscite.io;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zero-dependency Word (.docx) report writer: OOXML zip with
 * [Content_Types].xml, _rels/.rels and word/document.xml (UTF-8, 中文无碍).
 */
public final class DocxExporter {

    public static void write(Path out, Network g, String title, List<String> paragraphs) throws IOException {
        StringBuilder body = new StringBuilder();
        p(body, title, true);
        for (String s : paragraphs) p(body, s, false);
        for (GraphNode n : topNodes(g, 30))
            p(body, String.format("%-24s freq=%-5d centrality=%.3f sigma=%.2f cluster=#%d",
                    n.label, n.weight, n.centrality, n.sigma, n.cluster), false);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out), StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>");
            entry(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            entry(zip, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body>" + body + "</w:body></w:document>");
        }
    }

    private static void p(StringBuilder body, String text, boolean bold) {
        body.append("<w:p><w:pPr>").append(bold ? "<w:b/>" : "").append("</w:pPr><w:r><w:t xml:space=\"preserve\">")
            .append(xml(text)).append("</w:t></w:r></w:p>");
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static List<GraphNode> topNodes(Network g, int n) {
        List<GraphNode> list = new ArrayList<>(g.nodeCollection());
        list.sort((a, b) -> Integer.compare(b.weight, a.weight));
        return list.subList(0, Math.min(n, list.size()));
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
