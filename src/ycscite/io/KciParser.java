package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * KCI（Korean Citation Index）解析器（原版 "Data Source: Korean Citation Index (KCI)"）：
 * KCI 导出为 EndNote %-tag 或 NoteExpress RT 风格——自动识别两种并复用既有解析器。
 */
public final class KciParser {

    public static List<Record> parseFile(Path file) throws IOException {
        String head = WoSParser.readText(file);
        if (head.length() > 4000) head = head.substring(0, 4000);
        if (head.contains("%0 ")) return CnkiParser.parseFile(file);           // EndNote %
        if (head.contains("RT ") && (head.contains("A1 ") || head.contains("T1 ")))
            return CssciParser.parseFile(file);                                 // NoteExpress RT
        // 未知变体按 EndNote 兜底
        return CnkiParser.parseFile(file);
    }
}
