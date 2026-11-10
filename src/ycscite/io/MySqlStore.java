package ycscite.io;

import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;
import ycscite.model.Project;
import ycscite.model.Record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

/**
 * MySQL 存储对齐：① 生成完整 SQL dump（records/nodes/edges 三表 + 数据），可直接
 * `mysql -u user -D citespace < dump.sql` 导入；② 若 classpath 提供 MySQL Connector/J
 * （用户提供 jar 时反射可用），`storeToServer` 直接建库执行同样语句。
 */
public final class MySqlStore {

    public static String dump(Project p, Network g, String dbName) {
        StringBuilder sql = new StringBuilder();
        sql.append("-- Ycscite MySQL dump\nCREATE DATABASE IF NOT EXISTS `")
           .append(escDb(dbName)).append("` CHARACTER SET utf8mb4;\nUSE `").append(escDb(dbName)).append("`;\n\n");

        sql.append("CREATE TABLE IF NOT EXISTS records (\n"
                + "  id INT AUTO_INCREMENT PRIMARY KEY,\n  title TEXT, source VARCHAR(512), year INT,\n"
                + "  doi VARCHAR(255), ut VARCHAR(255), times_cited INT,\n"
                + "  authors TEXT, keywords TEXT, cited_refs LONGTEXT\n) CHARACTER SET utf8mb4;\n");
        sql.append("CREATE TABLE IF NOT EXISTS nodes (\n"
                + "  id VARCHAR(255) PRIMARY KEY, label VARCHAR(512), kind VARCHAR(32),\n"
                + "  freq INT, centrality DOUBLE, pagerank DOUBLE, sigma DOUBLE,\n"
                + "  burstness DOUBLE, half_life INT, cluster INT\n) CHARACTER SET utf8mb4;\n");
        sql.append("CREATE TABLE IF NOT EXISTS edges (\n"
                + "  src VARCHAR(255), dst VARCHAR(255), weight INT,\n"
                + "  KEY (src), KEY (dst)\n) CHARACTER SET utf8mb4;\n\n");

        for (Record r : p.records)
            sql.append("INSERT INTO records (title, source, year, doi, ut, times_cited, authors, keywords, cited_refs) VALUES ('")
                .append(esc(r.title)).append("','").append(esc(r.source)).append("',").append(r.year).append(",'")
                .append(esc(r.doi)).append("','").append(esc(r.ut)).append("',").append(r.timesCited).append(",'")
                .append(esc(String.join("; ", r.allAuthors()))).append("','")
                .append(esc(String.join("; ", r.allKeywords()))).append("','")
                .append(esc(String.join("\n", r.citedRefs))).append("');\n");
        for (GraphNode n : g.nodeCollection())
            sql.append("INSERT INTO nodes (id, label, kind, freq, centrality, pagerank, sigma, burstness, half_life, cluster) VALUES ('")
                .append(esc(n.id)).append("','").append(esc(n.label)).append("','").append(esc(n.kind)).append("',")
                .append(n.weight).append(",").append(n.centrality).append(",").append(n.pageRank).append(",")
                .append(n.sigma).append(",").append(n.burstness).append(",").append(n.halfLife).append(",")
                .append(n.cluster).append(");\n");
        for (GraphEdge e : g.edges)
            sql.append("INSERT INTO edges (src, dst, weight) VALUES ('")
                .append(esc(e.a.id)).append("','").append(esc(e.b.id)).append("',").append(e.weight).append(");\n");
        return sql.toString();
    }

    public static void writeDump(Project p, Network g, String dbName, Path out) throws IOException {
        Files.writeString(out, dump(p, g, dbName), StandardCharsets.UTF_8);
    }

    /** classpath 上是否存在 MySQL Connector/J（UI 提示用）。 */
    public static boolean driverAvailable() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 直连 MySQL 服务器执行 dump（要求 classpath 上的 mysql-connector-j 由用户提供：
     * java -cp out;mysql-connector-j-x.jar ...）。返回执行的语句数，驱动缺失时抛出说明性异常。
     */
    public static int storeToServer(String jdbcUrl, String user, String password, Project p, Network g, String dbName)
            throws Exception {
        List<String> statements;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("classpath 未提供 MySQL Connector/J（com.mysql.cj.jdbc.Driver）。"
                    + "请将 mysql-connector-j jar 加入 -cp 后重试，或使用 SQL dump 文件方式。", e);
        }
        String sql = dump(p, g, dbName);
        statements = splitStatements(sql);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             Statement st = conn.createStatement()) {
            for (String s : statements) st.execute(s);
        }
        return statements.size();
    }

    public static List<String> splitStatements(String sql) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' ) {
                cur.append(c);
                if (inSQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') { cur.append('\''); i++; }
                else inSQuote = !inSQuote;
            } else if (c == '\\' && inSQuote && i + 1 < sql.length()) {
                cur.append(c).append(sql.charAt(++i));
            } else if (c == ';' && !inSQuote) {
                if (!cur.toString().isBlank()) out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString());
        return out;
    }


    /** 执行查询（原版 "Find Clusters (Advanced using MySQL)" 的查询通道；需 classpath 提供 JDBC 驱动）。 */
    public static List<String[]> query(String jdbcUrl, String user, String password, String sql) throws Exception {
        List<String[]> rows = new java.util.ArrayList<>();
        try (Connection con = DriverManager.getConnection(jdbcUrl, user, password);
             Statement st = con.createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            String[] head = new String[cols];
            for (int c = 1; c <= cols; c++) head[c - 1] = rs.getMetaData().getColumnLabel(c);
            rows.add(head);
            while (rs.next()) {
                String[] row = new String[cols];
                for (int c = 1; c <= cols; c++) row[c - 1] = String.valueOf(rs.getObject(c));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String escDb(String s) { return s.replaceAll("[^A-Za-z0-9_]", ""); }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "''").replace("\r", " ").replace("\n", "\\n");
    }
}
