package ycscite.io;

import ycscite.analysis.LLRLabeler;
import ycscite.analysis.Pipeline;
import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.Map;

/** Exporters: node/edge/cluster-summary TSV tables and a self-contained interactive HTML view (offline, no CDN). */
public final class Exporters {

    /** 聚类汇总表（CiteSpace 的 cluster summary）：规模/轮廓系数/平均年份/LLR 标签。 */
    public static void writeClusterSummary(Pipeline.Result r, Path tsv) throws IOException {
        StringBuilder sb = new StringBuilder("cluster\tsize\tsilhouette\tmean_year\ttop_terms\n");
        if (r.clusterStats != null) {
            for (var en : r.clusterStats.entrySet()) {
                double[] s = en.getValue();
                sb.append('#').append(en.getKey()).append('\t')
                  .append((int) s[0]).append('\t')
                  .append(String.format("%.4f", s[1])).append('\t')
                  .append(String.format("%.1f", s[2])).append('\t');
                List<LLRLabeler.Scored> ls = r.clusterLabels.get(en.getKey());
                if (ls != null)
                    for (int i = 0; i < ls.size(); i++) {
                        if (i > 0) sb.append("; ");
                        sb.append(ls.get(i).term);
                    }
                sb.append('\n');
            }
        }
        java.nio.file.Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Cluster Explorer 逐年标签（原版 "Clusters: Label Clusters Year by Year"）：
     *  每个聚类 × 每个出现过的年份，给出当年该簇成员中权重前列的节点标签。 */
    public static void writeYearByYearLabels(Pipeline.Result r, Path tsv) throws IOException {
        StringBuilder sb = new StringBuilder("cluster\tyear\ttop_terms\n");
        // cluster -> year -> (node -> weight in year)
        Map<Integer, Map<Integer, java.util.TreeMap<String, Integer>>> byClusterYear = new java.util.TreeMap<>();
        for (GraphNode n : r.network.nodeCollection())
            for (var e : n.countsByYear.entrySet())
                byClusterYear
                        .computeIfAbsent(n.cluster, k -> new TreeMap<>())
                        .computeIfAbsent(e.getKey(), k -> new TreeMap<>())
                        .merge(n.label, e.getValue(), Integer::sum);
        for (var ce : byClusterYear.entrySet())
            for (var ye : ce.getValue().entrySet()) {
                if (ye.getKey() <= 0) continue; // 无年份计数（year=0 占位）不参与逐年标签
                sb.append('#').append(ce.getKey()).append('\t').append(ye.getKey()).append('\t');
                var entries = new ArrayList<>(ye.getValue().entrySet());
                entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (int i = 0; i < Math.min(5, entries.size()); i++) {
                    if (i > 0) sb.append("; ");
                    sb.append(entries.get(i).getKey());
                }
                sb.append('\n');
            }
        java.nio.file.Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
    }

    public static void writeTables(Network g, Map<Integer, List<LLRLabeler.Scored>> labels, Path nodesTsv, Path edgesTsv) throws IOException {
        writeTables(g, nodesTsv, edgesTsv, false);
    }

    static void writeTables(Network g, Path nodesTsv, Path edgesTsv, boolean includeLinkYear) throws IOException {
        StringBuilder nb = new StringBuilder("id\tlabel\tkind\tfreq\tcentrality\tburstness\tsigma\tcluster\tfirst_year\tbursts\n");
        for (GraphNode n : g.nodeCollection()) {
            StringBuilder bursts = new StringBuilder();
            for (int[] b : n.burstIntervals) {
                if (bursts.length() > 0) bursts.append(' ');
                bursts.append(b[0]).append('-').append(b[1]).append(" (").append(b[2]).append(')');
            }
            nb.append(n.id).append('\t').append(n.label).append('\t').append(n.kind).append('\t')
              .append(n.weight).append('\t')
              .append(String.format("%.4f", n.centrality)).append('\t')
              .append(n.burstness).append('\t')
              .append(String.format("%.2f", n.sigma)).append('\t')
              .append(n.cluster).append('\t')
              .append(n.firstYear == Integer.MAX_VALUE ? "" : n.firstYear).append('\t')
              .append(bursts).append('\n');
        }
        Files.writeString(nodesTsv, nb.toString(), StandardCharsets.UTF_8);

        StringBuilder eb = new StringBuilder("source\ttarget\tweight");
        if (includeLinkYear) eb.append("\tfirst_year");
        eb.append('\n');
        for (GraphEdge e : g.edges) {
            eb.append(e.a.id).append('\t').append(e.b.id).append('\t').append(e.weight);
            if (includeLinkYear) eb.append('\t').append(e.firstYear > 0 ? Integer.toString(e.firstYear) : "");
            eb.append('\n');
        }
        Files.writeString(edgesTsv, eb.toString(), StandardCharsets.UTF_8);
    }

    public static void writeHtml(Network g, Map<Integer, List<LLRLabeler.Scored>> labels, Path out) throws IOException {
        StringBuilder nodes = new StringBuilder("[");
        boolean first = true;
        for (GraphNode n : g.nodeCollection()) {
            if (!first) nodes.append(',');
            first = false;
            nodes.append("{\"id\":").append(quote(n.id)).append(",\"label\":").append(quote(n.label))
                 .append(",\"size\":").append(n.weight)
                 .append(",\"cluster\":").append(n.cluster).append('}');
        }
        nodes.append(']');
        StringBuilder edges = new StringBuilder("[");
        first = true;
        for (GraphEdge e : g.edges) {
            if (!first) edges.append(',');
            first = false;
            edges.append("{\"s\":").append(quote(e.a.id)).append(",\"t\":").append(quote(e.b.id))
                 .append(",\"w\":").append(e.weight).append('}');
        }
        edges.append(']');

        String html = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Ycscite — network view</title>
<style>body{margin:0;font:13px system-ui;background:#111;color:#ddd}canvas{display:block}</style>
</head><body>
<canvas id="c"></canvas>
<script>
const NODES = %NODES%;
const EDGES = %EDGES%;
const PAL = ['#4e79a7','#f28e2b','#e15759','#76b7b2','#59a14f','#edc948','#b07aa1','#ff9da7'];
const cv = document.getElementById('c'), cx = cv.getContext('2d');
function resize(){cv.width=innerWidth;cv.height=innerHeight;}
addEventListener('resize',resize);resize();
const R=260,W=cv.width/2,H=cv.height/2;
NODES.forEach((n,i)=>{const a=2*Math.PI*i/NODES.length;n.x=W+R*Math.cos(a);n.y=H+R*Math.sin(a);});
for(let it=0;it<200;it++){ // light force relaxation
  for(let i=0;i<NODES.length;i++)for(let j=i+1;j<NODES.length;j++){
    const a=NODES[i],b=NODES[j];let dx=b.x-a.x,dy=b.y-a.y,d2=dx*dx+dy*dy||1;
    const f=900/d2;dx*=f;dy*=f;a.x+=dx;a.y+=dy;b.x-=dx;b.y-=dy;}
  NODES.forEach(n=>{n.x+=(W-n.x)*0.005;n.y+=(H-n.y)*0.005;});
}
function draw(){
  cx.fillStyle='#111';cx.fillRect(0,0,cv.width,cv.height);
  cx.globalAlpha=.35;cx.strokeStyle='#889';
  EDGES.forEach(e=>{const a=byId(e.s),b=byId(e.t);cx.lineWidth=Math.min(3,e.w*.3);
    cx.beginPath();cx.moveTo(a.x,a.y);cx.lineTo(b.x,b.y);cx.stroke();});
  cx.globalAlpha=1;
  NODES.forEach(n=>{cx.fillStyle=PAL[n.cluster%PAL.length];
    cx.beginPath();cx.arc(n.x,n.y,4+Math.sqrt(n.size)*2.5,0,7);cx.fill();
    if(n.size>2){cx.fillStyle='#ddd';cx.font='10px system-ui';cx.fillText(n.label,n.x+8,n.y+3);}});
}
function byId(id){return NODES.find(n=>n.id===id);}
draw();
</script></body></html>"""
                .replace("%NODES%", nodes.toString())
                .replace("%EDGES%", edges.toString());
        Files.writeString(out, html, StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n").replace("\r", "") + '"';
    }

    // ---------------- 互操作格式导出（原版 ph.java 964-982：UCINET/GEXF/Pajek/邻接矩阵） ----------------

    /** Pajek .net（含时间区间 [firstYear,lastYear]，对应原版 "Pajek .net with time intervals"）。 */
    public static void writePajek(Network g, Path out) throws IOException {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        Map<GraphNode, Integer> idx = new HashMap<>();
        StringBuilder sb = new StringBuilder("*Vertices " + nodes.size() + "\n");
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode n = nodes.get(i);
            idx.put(n, i + 1);
            int fy = n.firstYear == Integer.MAX_VALUE ? 0 : n.firstYear;
            int ly = fy;
            for (int y : n.countsByYear.keySet()) ly = Math.max(ly, y);
            sb.append(i + 1).append(' ').append(quote(n.label)).append(" ic ").append(clusterColorHex(n.cluster))
              .append(' ').append(fy).append(' ').append(ly).append('\n');
        }
        sb.append("*Edges\n");
        for (GraphEdge e : g.edges)
            sb.append(idx.get(e.a)).append(' ').append(idx.get(e.b)).append(' ').append(e.weight).append('\n');
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** GEXF 1.3（Gephi 交换格式；节点带簇属性）。 */
    public static void writeGexf(Network g, Path out) throws IOException {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        Map<GraphNode, Integer> idx = new HashMap<>();
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gexf xmlns=\"http://gexf.net/1.3\" version=\"1.3\">\n");
        sb.append("<graph defaultedgetype=\"undirected\" mode=\"static\">\n");
        sb.append("<attributes class=\"node\">\n<attribute id=\"0\" title=\"cluster\" type=\"integer\"/>\n</attributes>\n");
        sb.append("<nodes>\n");
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode n = nodes.get(i);
            idx.put(n, i);
            sb.append("<node id=\"").append(i).append("\" label=\"").append(xml(n.label))
              .append("\"><attvalues><attvalue for=\"0\" value=\"").append(n.cluster)
              .append("\"/></attvalues></node>\n");
        }
        sb.append("</nodes>\n<edges>\n");
        int eid = 0;
        for (GraphEdge e : g.edges)
            sb.append("<edge id=\"").append(eid++).append("\" source=\"").append(idx.get(e.a))
              .append("\" target=\"").append(idx.get(e.b)).append("\" weight=\"").append(e.weight).append("\"/>\n");
        sb.append("</edges>\n</graph>\n</gexf>\n");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** UCINET DL（全矩阵文本格式）。 */
    public static void writeUcinet(Network g, Path out) throws IOException {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        int n = nodes.size();
        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(nodes.get(i), i);
        double[][] m = new double[n][n];
        for (GraphEdge e : g.edges) {
            int i = idx.get(e.a), j = idx.get(e.b);
            m[i][j] += e.weight;
            m[j][i] += e.weight;
        }
        StringBuilder sb = new StringBuilder("DL n=" + n + " format=fullmatrix symmetric\n");
        sb.append("labels embedded\ndata:\n");
        for (int i = 0; i < n; i++) {
            StringBuilder row = new StringBuilder();
            for (int j = 0; j < n; j++) {
                if (j > 0) row.append(' ');
                row.append((int) m[i][j]);
            }
            sb.append(row).append(" \"").append(nodes.get(i).label).append("\"\n");
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 邻接矩阵 CSV（带标签表头，供 R/Orange/Pandas）。 */
    public static void writeAdjacencyMatrix(Network g, Path out) throws IOException {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        int n = nodes.size();
        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(nodes.get(i), i);
        double[][] m = new double[n][n];
        for (GraphEdge e : g.edges) {
            int i = idx.get(e.a), j = idx.get(e.b);
            m[i][j] += e.weight;
            m[j][i] += e.weight;
        }
        StringBuilder sb = new StringBuilder(",");
        for (GraphNode node : nodes) sb.append(csv(node.label)).append(',');
        sb.append('\n');
        for (int i = 0; i < n; i++) {
            sb.append(csv(nodes.get(i).label));
            for (int j = 0; j < n; j++) sb.append(',').append((int) m[i][j]);
            sb.append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String csv(String s) { return s.contains(",") ? "\"" + s.replace("\"", "\"\"") + "\"" : s; }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String clusterColorHex(int cluster) {
        int h = (cluster * 47) % 360;
        java.awt.Color c = java.awt.Color.getHSBColor(h / 360f, 0.55f, 0.85f);
        return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ---------------- R/Orange 专用 TSV（审计[17][18]，ph.java:950-955） ----------------

    /** R 专用：key,x,y,freq,year,nodeID,clusterID,labelLRR（需坐标；无布局时 xy=0）。 */
    public static void writeNodesForR(Pipeline.Result r, java.nio.file.Path out,
                                      Map<GraphNode, double[]> xy) throws IOException {
        StringBuilder sb = new StringBuilder("key\tx\ty\tfreq\tyear\tnodeID\tclusterID\tlabelLRR\n");
        int i = 0;
        for (GraphNode n : r.network.nodeCollection()) {
            double[] p = xy == null ? null : xy.get(n);
            String lbl = r.clusterLabels == null || r.clusterLabels.get(n.cluster) == null
                    || r.clusterLabels.get(n.cluster).isEmpty() ? "" : r.clusterLabels.get(n.cluster).get(0).term;
            sb.append(n.label).append('\t').append(p == null ? 0 : (int) p[0]).append('\t')
              .append(p == null ? 0 : (int) p[1]).append('\t').append(n.weight).append('\t')
              .append(n.firstYear == Integer.MAX_VALUE ? 0 : n.firstYear).append('\t')
              .append(i++).append('\t').append(n.cluster).append('\t').append(lbl).append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Orange 专用：key,x,y,times_cited,burst,clusterID,labelLRR。 */
    public static void writeNodesForOrange(Pipeline.Result r, java.nio.file.Path out,
                                           Map<GraphNode, double[]> xy) throws IOException {
        StringBuilder sb = new StringBuilder("key\tx\ty\ttimes_cited\tburst\tclusterID\tlabelLRR\n");
        for (GraphNode n : r.network.nodeCollection()) {
            double[] p = xy == null ? null : xy.get(n);
            String lbl = r.clusterLabels == null || r.clusterLabels.get(n.cluster) == null
                    || r.clusterLabels.get(n.cluster).isEmpty() ? "" : r.clusterLabels.get(n.cluster).get(0).term;
            sb.append(n.label).append('\t').append(p == null ? 0 : (int) p[0]).append('\t')
              .append(p == null ? 0 : (int) p[1]).append('\t').append(n.weight).append('\t')
              .append(n.burstness > 0 ? 1 : 0).append('\t').append(n.cluster).append('\t').append(lbl).append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 保存当前节点键为 .list（审计[24]，ph.java:982，与高亮加载对偶）。 */
    public static void writeNodeList(Network g, java.nio.file.Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (GraphNode n : g.nodeCollection()) sb.append(n.label).append('\n');
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 加载用户自定义聚类标签 cluster_labels.tsv（审计[74]，ph.java:658：cluster\tlabel1;label2;...）。 */
    public static Map<Integer, List<LLRLabeler.Scored>> loadUserLabels(java.nio.file.Path tsv) throws IOException {
        Map<Integer, List<LLRLabeler.Scored>> out = new TreeMap<>();
        if (!Files.exists(tsv)) return out;
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("cluster")) continue;
            String[] t = line.split("\t", 2);
            if (t.length < 2) continue;
            try {
                int c = Integer.parseInt(t[0].replace("#", "").trim());
                List<LLRLabeler.Scored> labels = new ArrayList<>();
                for (String term : t[1].split(";\s*"))
                    if (!term.isBlank()) labels.add(new LLRLabeler.Scored(term.trim(), 0));
                if (!labels.isEmpty()) out.put(c, labels);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** 合并两个 CSV（原版 "Merge Network Summaries in CSV"，ph:2187：按 key 列 join）。 */
    public static void mergeCsv(java.nio.file.Path a, java.nio.file.Path b, java.nio.file.Path out,
                                String keyColumn) throws IOException {
        List<String> linesA = Files.readAllLines(a, StandardCharsets.UTF_8);
        List<String> linesB = Files.readAllLines(b, StandardCharsets.UTF_8);
        if (linesA.isEmpty() || linesB.isEmpty()) return;
        String[] headA = linesA.get(0).split("\t");
        String[] headB = linesB.get(0).split("\t");
        int keyA = -1, keyB = -1;
        for (int i = 0; i < headA.length; i++) if (headA[i].equals(keyColumn)) keyA = i;
        for (int i = 0; i < headB.length; i++) if (headB[i].equals(keyColumn)) keyB = i;
        if (keyA < 0 || keyB < 0) return;
        // B → Map
        Map<String, String[]> bMap = new HashMap<>();
        for (int i = 1; i < linesB.size(); i++) {
            String[] t = linesB.get(i).split("\t", -1);
            if (t.length > keyB) bMap.put(t[keyB], t);
        }
        // 合并表头（B 的列追加，跳过 key）
        StringBuilder hdr = new StringBuilder(linesA.get(0));
        for (int i = 0; i < headB.length; i++) if (i != keyB) hdr.append('\t').append(headB[i]);
        StringBuilder sb = new StringBuilder(hdr).append('\n');
        for (int i = 1; i < linesA.size(); i++) {
            String[] t = linesA.get(i).split("\t", -1);
            sb.append(linesA.get(i));
            String[] match = t.length > keyA ? bMap.get(t[keyA]) : null;
            for (int j = 0; j < headB.length; j++) {
                if (j == keyB) continue;
                sb.append('\t').append(match != null && j < match.length ? match[j] : "");
            }
            sb.append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
