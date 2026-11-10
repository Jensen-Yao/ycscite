package ycscite.analysis;

import ycscite.model.Project;
import ycscite.model.Record;
import java.util.*;

/** SVO 简化提取（原版 SVO 工具链的零依赖近似）：中文动词表 + 英文常见动词。 */
public final class SvoExtractor {

    public record Svo(String subject, String verb, String object, int year) { }

    static final String[] ZH_VERBS = {"促进","影响","导致","反映","表明","揭示","提出","构建","形成","保护","利用","发展","传承","演变"};
    static final String[] EN_VERBS = {"is","are","was","were","has","have","provides","shows","reveals","suggests","indicates","requires"};

    public static List<Svo> extract(Project p, int limit) {
        List<Svo> out = new ArrayList<>();
        for (Record r : p.records) {
            String text = r.title + " " + r.abstractText;
            if (text.isBlank()) continue;
            // 中文
            for (String verb : ZH_VERBS) {
                int pos = 0;
                while ((pos = text.indexOf(verb, pos)) >= 0 && out.size() < limit) {
                    int s0 = Math.max(0, pos - 6);
                    int e0 = Math.min(text.length(), pos + verb.length() + 6);
                    String subj = text.substring(s0, pos).trim().replaceAll("[。，；、！？…]+$", "");
                    String obj = text.substring(pos + verb.length(), e0).trim().replaceAll("^[。，；、！？…]+", "");
                    if (subj.length() >= 2 && obj.length() >= 2) out.add(new Svo(subj, verb, obj, r.year));
                    pos += verb.length();
                }
                if (out.size() >= limit) break;
            }
            // 英文
            String[] words = text.split("[\s,;:.!?()]+");
            for (int wi = 1; wi < words.length - 1 && out.size() < limit; wi++)
                for (String v : EN_VERBS)
                    if (words[wi].equalsIgnoreCase(v) && words[wi-1].length() >= 3 && words[wi+1].length() >= 3) {
                        out.add(new Svo(words[wi-1], v, words[wi+1], r.year));
                        break;
                    }
        }
        return out;
    }

    public static String toTsv(List<Svo> svos) {
        StringBuilder sb = new StringBuilder("subject\tverb\tobject\tyear\n");
        for (Svo s : svos)
            sb.append(s.subject()).append('\t').append(s.verb()).append('\t')
              .append(s.object()).append('\t').append(s.year()).append('\n');
        return sb.toString();
    }

    /** 全文多目录批处理（原版 CS:826 "Process Multiple Folders of FullText Files"）：扫描目录下全部 .txt 做提取。 */
    public static List<Svo> extractFromDirectory(java.nio.file.Path dir, int limit) throws java.io.IOException {
        List<Svo> out = new ArrayList<>();
        try (var stream = java.nio.file.Files.walk(dir, 2)) {
            stream.filter(f -> f.toString().toLowerCase().endsWith(".txt"))
                  .sorted()
                  .forEach(f -> {
                      if (out.size() >= limit) return;
                      try {
                          String text = java.nio.file.Files.readString(f);
                          // 每个文件当一条"记录"处理
                          for (String verb : ZH_VERBS) {
                              int pos = 0;
                              while ((pos = text.indexOf(verb, pos)) >= 0 && out.size() < limit) {
                                  int s0 = Math.max(0, pos - 6);
                                  int e0 = Math.min(text.length(), pos + verb.length() + 6);
                                  String subj = text.substring(s0, pos).trim().replaceAll("[。，；、！？…]+$", "");
                                  String obj = text.substring(pos + verb.length(), e0).trim().replaceAll("^[。，；、！？…]+", "");
                                  if (subj.length() >= 2 && obj.length() >= 2)
                                      out.add(new Svo(subj, verb, obj, 0));
                                  pos += verb.length();
                              }
                              if (out.size() >= limit) break;
                          }
                      } catch (Exception ignored) { }
                  });
        }
        return out;
    }
}
