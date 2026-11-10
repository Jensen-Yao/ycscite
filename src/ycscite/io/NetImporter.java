package ycscite.io;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 网络文件导入（原版 "Visualize Pajek Network (.net)" / "Visualize Network (GraphML)"）。 */
public final class NetImporter {

    /** Pajek .net：*Vertices / *Edges / *Arcs 三段。 */
    public static Network readPajek(Path in) throws IOException {
        Network g = new Network("Imported Pajek");
        Map<Integer, GraphNode> byId = new HashMap<>();
        String section = "";
        for (String line : Files.readAllLines(in, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("%")) continue;
            String upper = t.toUpperCase();
            if (upper.startsWith("*VERTICES")) { section = "V"; continue; }
            if (upper.startsWith("*EDGES") || upper.startsWith("*ARCS")) { section = "E"; continue; }
            if (upper.startsWith("*")) { section = ""; continue; }
            if ("V".equals(section)) {
                // 格式: 1 "label" [x y z] [ic color] [bc color] [shape]
                String[] parts = t.split("\s+", 2);
                int id = 0;
                try { id = Integer.parseInt(parts[0]); } catch (NumberFormatException e) { continue; }
                String label = parts.length > 1 ? parts[1].replaceAll("\"", "").trim() : "V" + id;
                if (label.isEmpty()) label = "V" + id;
                byId.put(id, g.node("V" + id, label, "imported"));
            } else if ("E".equals(section)) {
                String[] parts = t.split("\s+");
                if (parts.length < 2) continue;
                try {
                    GraphNode a = byId.get(Integer.parseInt(parts[0]));
                    GraphNode b = byId.get(Integer.parseInt(parts[1]));
                    int w = parts.length > 2 ? (int) Double.parseDouble(parts[2]) : 1;
                    if (a != null && b != null) g.linkWeighted(a, b, w);
                } catch (NumberFormatException ignored) { }
            }
        }
        return g;
    }

    /** GraphML：XML 节点/边（key 追加，非嵌套流式解析——零依赖近似）。 */
    public static Network readGraphMl(Path in) throws IOException {
        Network g = new Network("Imported GraphML");
        String xml = Files.readString(in, StandardCharsets.UTF_8);
        Map<String, GraphNode> byId = new HashMap<>();
        // 节点：<node id="..." [label="..."]/>
        var nodeM = java.util.regex.Pattern.compile("<node\s+id=\"([^\"]+)\"(?:\s+label=\"([^\"]*)\")?[^>]*/?>").matcher(xml);
        while (nodeM.find()) {
            String id = nodeM.group(1);
            String label = nodeM.group(2) != null ? nodeM.group(2) : id;
            byId.put(id, g.node(id, label, "imported"));
        }
        // 边：<edge source="..." target="..." [weight="..."]/>
        var edgeM = java.util.regex.Pattern.compile("<edge\s+source=\"([^\"]+)\"\s+target=\"([^\"]+)\"(?:\s+weight=\"([^\"]+)\")?[^>]*/?>").matcher(xml);
        while (edgeM.find()) {
            GraphNode a = byId.get(edgeM.group(1)), b = byId.get(edgeM.group(2));
            int w = edgeM.group(3) != null ? (int) Double.parseDouble(edgeM.group(3)) : 1;
            if (a != null && b != null) g.linkWeighted(a, b, w);
        }
        return g;
    }
}
