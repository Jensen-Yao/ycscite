package ycscite.io;

import ycscite.model.Record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.nio.file.Files;

/**
 * NSF Awards XML 解析器（原版 "Updated the converter for NSF Awards in XML"）：
 * 根元素 <Award> 列表，取 Title/Date(取年)/Investigator(姓名)/Institution/Abstract/Agency。
 * 无内置 XML 依赖则做轻量文本抽取兜底。
 */
public final class NsfAwardsParser {

    private static int year(String date){
        var match=java.util.regex.Pattern.compile("(?<![0-9])(?:18|19|20|21)[0-9]{2}(?![0-9])").matcher(date);
        return match.find()?Integer.parseInt(match.group()):0;
    }

    public static List<Record> parseFile(Path file) throws IOException {
        String xml = WoSParser.readText(file);
        List<Record> out = new ArrayList<>();
        String upper=xml.toUpperCase(java.util.Locale.ROOT);
        if(upper.contains("<!DOCTYPE")||upper.contains("<!ENTITY"))throw new IOException("External entities and DOCTYPE are not supported in award imports");
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            Document doc = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            NodeList awards = doc.getElementsByTagName("Award");
            for (int i = 0; i < awards.getLength(); i++) {
                Element a = (Element) awards.item(i);
                Record r = new Record();
                r.pt = "G";
                r.title = text(a, "AwardTitle");if(r.title.isBlank())r.title=text(a,"Title");
                r.ut = text(a, "AwardID");
                String date = text(a, "AwardEffectiveDate");if(date.isBlank())date=text(a,"Date");
                r.year=year(date);
                NodeList inv = a.getElementsByTagName("Investigator");
                for (int k = 0; k < inv.getLength(); k++) {
                    Element e = (Element) inv.item(k);
                    String nm = text(e, "FirstName") + " " + text(e, "LastName");
                    if (!nm.isBlank()) { r.authors.add(nm.trim()); r.authorsFull.add(nm.trim()); }
                }
                NodeList inst = a.getElementsByTagName("Institution");
                if (inst.getLength() > 0)
                    r.addresses.add(text((Element) inst.item(0), "Name"));
                r.abstractText = text(a, "AbstractNarration");
                out.add(r);
            }
            if (!out.isEmpty()) return out;
        } catch (Exception domFailed) {
            // 兜底：轻量正则式抽取（无 XML 头或损坏时）
        }
        // 兜底抽取
        for (String block : xml.split("(?=<Award>)")) {
            if (!block.contains("<Title>")&&!block.contains("<AwardTitle>")) continue;
            Record r = new Record();
            r.pt = "G";
            r.title = between(block,"<AwardTitle>","</AwardTitle>");if(r.title.isBlank())r.title=between(block,"<Title>","</Title>");
            r.ut = between(block, "<AwardID>", "</AwardID>");
            String date=between(block,"<AwardEffectiveDate>","</AwardEffectiveDate>");if(date.isBlank())date=between(block,"<Date>","</Date>");r.year=year(date);
            int idx = 0;
            while (true) {
                int s = block.indexOf("<LastName>", idx);
                if (s < 0) break;
                int e = block.indexOf("</LastName>", s);
                r.authors.add(between(block, "<FirstName>", "</FirstName>") + " " + block.substring(s + 10, e));
                idx = e;
            }
            r.authorsFull = new ArrayList<>(r.authors);
            r.abstractText = between(block, "<AbstractNarration>", "</AbstractNarration>");
            if (!r.title.isBlank()) out.add(r);
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        return l.getLength() == 0 ? "" : l.item(0).getTextContent().trim();
    }

    private static String between(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a < 0) return "";
        int b = s.indexOf(close, a);
        return b < 0 ? "" : s.substring(a + open.length(), b).trim();
    }
}
