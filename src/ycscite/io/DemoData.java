package ycscite.io;

import ycscite.model.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Synthetic WoS-format dataset with three thematic clusters and one bursty keyword,
 * used by the self-test and the demo mode (deterministic: fixed seed).
 */
public final class DemoData {

    public static Project generate(int records) {
        Random rnd = new Random(42);
        Project p = new Project();
        p.name = "demo-science-mapping";

        String[][] themes = {
            {"deep learning", "neural networks", "representation learning", "backpropagation"},
            {"bibliometrics", "citation analysis", "science mapping", "co-citation"},
            {"climate change", "global warming", "carbon emissions", "sea level rise"},
        };
        String[][] authors = {
            {"LECUN Y", "BENGIO Y", "HINTON G", "SCHMIDHUBER J"},
            {"CHEN C", "BORNER K", "BOYACK K", "VAN ECK NJ"},
            {"HANSEN J", "IPCC", "STEFFEN W", "LENTON TM"},
        };
        String[] shared = {"machine learning", "data science"};
        String burstWord = "large language models";

        for (int i = 0; i < records; i++) {
            int theme = i % 3;
            int year = 2014 + (i * 10 / records) % 11; // 2014..2024 spread
            StringBuilder sb = new StringBuilder();
            sb.append("PT J\n");
            int na = 1 + rnd.nextInt(3);
            List<String> au = new ArrayList<>();
            for (int a = 0; a < na; a++) au.add(authors[theme][rnd.nextInt(authors[theme].length)]);
            sb.append("AU ").append(String.join("; ", au)).append('\n');
            sb.append("AF ").append(String.join("; ", au)).append('\n');
            sb.append("TI ").append("Study ").append(i).append(" on ").append(themes[theme][0]).append('\n');
            sb.append("SO J SOURCE ").append(theme + 1).append('\n');
            List<String> kws = new ArrayList<>();
            kws.add(themes[theme][rnd.nextInt(themes[theme].length)]);
            kws.add(themes[theme][rnd.nextInt(themes[theme].length)]);
            if (rnd.nextDouble() < 0.6) kws.add(shared[rnd.nextInt(shared.length)]);
            if (year >= 2020 && year <= 2023 && rnd.nextDouble() < 0.8) kws.add(burstWord);
            if (rnd.nextDouble() < 0.2) kws.add(themes[(theme + 1) % 3][0]); // cross-theme noise
            sb.append("DE ").append(String.join(";", distinct(kws))).append('\n');
            sb.append("AB An abstract about ").append(themes[theme][0]).append(".\n");
            StringBuilder cr = new StringBuilder();
            for (int c = 0; c < 4 + rnd.nextInt(6); c++) {
                String a = authors[rnd.nextInt(3)][rnd.nextInt(4)];
                int cy = 1990 + rnd.nextInt(30);
                cr.append(c == 0 ? "CR " : "   ")
                  .append(a).append(", ").append(cy).append(", J SOURCE ").append(rnd.nextInt(3) + 1)
                  .append(", V").append(rnd.nextInt(20)).append(", P").append(rnd.nextInt(300)).append('\n');
            }
            sb.append(cr);
            sb.append("C1 [1] UNIV A, DEPT PHYS, CITY ").append(theme + 1).append(", COUNTRY ").append(theme + 1).append('\n');
            sb.append("PY ").append(year).append('\n');
            sb.append("TC ").append(rnd.nextInt(50)).append('\n');
            sb.append("UT WOS:000").append(String.format("%010d", i)).append('\n');
            sb.append("ER\n\n");
            try {
                Path tmp = Files.createTempFile("citespace-clone-demo", ".txt");
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                p.addAll(WoSParser.parseFile(tmp));
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return p;
    }

    private static List<String> distinct(List<String> in) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(in));
    }
}
