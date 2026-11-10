package ycscite.io;

import ycscite.model.Network;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** citespace.alias 导入（原版 "Add to the Alias List"，格式 primary#secondary）。 */
public final class AliasFile {

    /** 读取 alias 文件并在网络上执行合并（secondary → primary）。 */
    public static int apply(Network g, Path aliasFile) throws IOException {
        List<String> lines = Files.readAllLines(aliasFile, StandardCharsets.UTF_8);
        int merged = 0;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] pair = line.split("#", 2);
            if (pair.length != 2) continue;
            String primary = pair[0].trim().toUpperCase();
            String secondary = pair[1].trim().toUpperCase();
            if (primary.isEmpty() || secondary.isEmpty()) continue;
            if (g.nodes.containsKey(primary) && g.nodes.containsKey(secondary) && !primary.equals(secondary)) {
                g.mergeNodes(g.nodes.get(secondary), g.nodes.get(primary));
                merged++;
            }
        }
        return merged;
    }

    /** 生成 alias 模板文件（用于用户编辑）。 */
    public static void writeTemplate(Network g, Path out) throws IOException {
        StringBuilder sb = new StringBuilder("# citespace.alias\n# Format: primary#secondary\n");
        sb.append("# Example: BIG DATA#big data\n");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
