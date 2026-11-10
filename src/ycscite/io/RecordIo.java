package ycscite.io;

import ycscite.model.Project;
import ycscite.model.Record;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Versioned record TSV: scalar control characters are escaped; multivalue cells are JSON arrays.
 * Original 17-column semicolon-separated files remain readable.
 */
public final class RecordIo {
    static final String HEADER = "PT\tAU\tAF\tTI\tSO\tDE\tID\tAB\tC1\tCR\tPY\tVL\tBP\tDI\tUT\tTC\tWC";
    private static final String HEADER_V2 = HEADER+"\tYCSCITE_TSV_V2";
    private RecordIo() {}
    public static void writeTsv(Project project,Path out)throws IOException {
        var text=new StringBuilder(HEADER_V2).append('\n');
        for(var r:project.records)text.append(escape(r.pt)).append('\t').append(list(r.authors)).append('\t').append(list(r.authorsFull))
            .append('\t').append(escape(r.title)).append('\t').append(escape(r.source)).append('\t').append(list(r.keywords))
            .append('\t').append(list(r.keywordsPlus)).append('\t').append(escape(r.abstractText)).append('\t').append(list(r.addresses))
            .append('\t').append(list(r.citedRefs)).append('\t').append(r.year).append('\t').append(escape(r.volume))
            .append('\t').append(escape(r.beginPage)).append('\t').append(escape(r.doi)).append('\t').append(escape(r.ut))
            .append('\t').append(r.timesCited).append('\t').append(list(r.categories)).append("\t2\n");
        Files.writeString(out,text.toString(),StandardCharsets.UTF_8);
    }
    public static Project readTsv(Path in)throws IOException {
        var project=new Project();project.name=in.getFileName().toString().replaceAll("\\.[^.]+$","");
        var lines=Files.readAllLines(in,StandardCharsets.UTF_8);if(lines.isEmpty())return project;
        String header=lines.getFirst().replaceFirst("^\\uFEFF","");boolean version2=header.equals(HEADER_V2);
        if(!version2&&!header.equals(HEADER))throw new IOException("Unrecognized Ycscite record TSV header");
        for(int i=1;i<lines.size();i++){
            if(lines.get(i).isBlank())continue;
            String[] cells=lines.get(i).split("\t",-1);
            if(version2&&(cells.length!=18||!cells[17].equals("2")))throw new IOException("Invalid record TSV v2 row "+(i+1));
            try{
                var r=new Record();r.pt=scalar(cells,0,version2);r.authors=values(cells,1,version2);r.authorsFull=values(cells,2,version2);
                r.title=scalar(cells,3,version2);r.source=scalar(cells,4,version2);r.keywords=values(cells,5,version2);r.keywordsPlus=values(cells,6,version2);
                r.abstractText=scalar(cells,7,version2);r.addresses=values(cells,8,version2);r.citedRefs=values(cells,9,version2);r.year=number(cells,10,version2);
                r.volume=scalar(cells,11,version2);r.beginPage=scalar(cells,12,version2);r.doi=scalar(cells,13,version2);r.ut=scalar(cells,14,version2);
                r.timesCited=number(cells,15,version2);r.categories=values(cells,16,version2);project.add(r);
            }catch(IllegalArgumentException error){throw new IOException("Invalid record TSV row "+(i+1)+": "+error.getMessage(),error);}
        }
        return project;
    }
    private static String list(List<String> values){return MiniJson.write(values);}
    private static String escape(String value){return value==null?"":value.replace("\\","\\\\").replace("\t","\\t").replace("\r","\\r").replace("\n","\\n");}
    private static String scalar(String[] cells,int index,boolean version2){
        if(index>=cells.length)return "";String value=cells[index];if(!version2)return value.replace("\\t","\t").trim();
        var decoded=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='\\'&&i+1<value.length()){
            char next=value.charAt(++i);switch(next){case '\\'->decoded.append('\\');case 't'->decoded.append('\t');case 'n'->decoded.append('\n');case 'r'->decoded.append('\r');default->decoded.append('\\').append(next);}
        }else decoded.append(c);}return decoded.toString();
    }
    private static List<String> values(String[] cells,int index,boolean version2){
        if(index>=cells.length)return new java.util.ArrayList<>();
        if(!version2)return new java.util.ArrayList<>(java.util.Arrays.stream(scalar(cells,index,false).split(";",-1)).map(String::trim).filter(s->!s.isBlank()).toList());
        Object parsed=MiniJson.parse(cells[index]);if(!(parsed instanceof List<?> list)||list.stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("Multivalue fields must be JSON string arrays");
        return new java.util.ArrayList<>(list.stream().map(String.class::cast).toList());
    }
    private static int number(String[] cells,int index,boolean version2){String value=scalar(cells,index,version2);if(!version2){try{return Integer.parseInt(value.replaceAll("[^0-9]",""));}catch(NumberFormatException ignored){return 0;}}return Integer.parseInt(value);}
}
