package ycscite.io;

import ycscite.model.Project;
import ycscite.model.Record;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** RIS 导出（原版 "Save References as RIS"，ph.java:956）。 */
public final class RisExporter {
    public static void write(Project p, Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Record r : p.records) {
            sb.append("TY  - JOUR\n");
            for (String a : r.allAuthors()) sb.append("AU  - ").append(a).append('\n');
            sb.append("TI  - ").append(r.title).append('\n');
            sb.append("JO  - ").append(r.source).append('\n');
            if (r.year > 0) sb.append("PY  - ").append(r.year).append('\n');
            if (!r.volume.isEmpty()) sb.append("VL  - ").append(r.volume).append('\n');
            if (!r.beginPage.isEmpty()) sb.append("SP  - ").append(r.beginPage).append('\n');
            if (!r.doi.isEmpty()) sb.append("DO  - ").append(r.doi).append('\n');
            if (!r.ut.isEmpty()) sb.append("ID  - ").append(r.ut).append('\n');
            for (String k : r.allKeywords()) sb.append("KW  - ").append(k).append('\n');
            if (!r.abstractText.isEmpty()) sb.append("AB  - ").append(r.abstractText).append('\n');
            sb.append("ER  - \n\n");
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
