package ycscite.io;

import ycscite.analysis.Pipeline;
import java.util.*;

/** Complete version-tolerant codec for the shared analysis options in editable projects. */
public final class PipelineOptionsCodec {
    private PipelineOptionsCodec(){}
    public static Map<String,Object> write(Pipeline.Options o){
        var m=new LinkedHashMap<String,Object>();
        m.put("networkKind",o.networkKind);m.put("topN",o.topN);m.put("selection",o.selection);m.put("topPercent",o.topPercent);
        m.put("similarity",o.similarity);m.put("strictSelection",o.strictSelection);m.put("excludeWords",new ArrayList<>(new TreeSet<>(o.excludeWords)));
        m.put("gindexK",o.gindexK);m.put("minLinkStrength",o.minLinkStrength);m.put("maxLinksPerNode",o.maxLinksPerNode);
        m.put("nounPhraseMin",o.nounPhraseMin);m.put("nounPhraseMax",o.nounPhraseMax);m.put("largestKComponents",o.largestKComponents);
        m.put("singularKeywords",o.singularKeywords);m.put("fromYear",o.fromYear);m.put("toYear",o.toYear);m.put("sliceYears",o.sliceYears);
        m.put("topNPerSlice",o.topNPerSlice);m.put("pruning",o.pruning);m.put("minClusterSize",o.minClusterSize);m.put("burstFromSelectedNetwork",o.burstFromSelectedNetwork);
        m.put("labelsPerCluster",o.labelsPerCluster);m.put("labelSource",o.labelSource);m.put("clustering",o.clustering);m.put("labeling",o.labeling);
        var b=new LinkedHashMap<String,Object>();b.put("relI",o.burstParams.relI);b.put("relJ",o.burstParams.relJ);b.put("relC",o.burstParams.relC);b.put("spanFrom",o.burstParams.spanFrom);b.put("spanTo",o.burstParams.spanTo);b.put("transK",o.burstParams.transK);m.put("burstParams",b);
        return m;
    }
    public static Pipeline.Options read(Map<String,Object> m){
        var o=new Pipeline.Options();
        o.networkKind=text(m,"networkKind",o.networkKind);o.topN=integer(m,"topN",o.topN);o.selection=text(m,"selection",o.selection);o.topPercent=number(m,"topPercent",o.topPercent);
        o.similarity=text(m,"similarity",o.similarity);o.strictSelection=bool(m,"strictSelection",o.strictSelection);if(m.containsKey("excludeWords"))o.excludeWords=new HashSet<>(MiniJson.arr(m.get("excludeWords")).stream().map(String::valueOf).toList());
        o.gindexK=number(m,"gindexK",o.gindexK);o.minLinkStrength=integer(m,"minLinkStrength",o.minLinkStrength);o.maxLinksPerNode=integer(m,"maxLinksPerNode",o.maxLinksPerNode);
        o.nounPhraseMin=integer(m,"nounPhraseMin",o.nounPhraseMin);o.nounPhraseMax=integer(m,"nounPhraseMax",o.nounPhraseMax);o.largestKComponents=integer(m,"largestKComponents",o.largestKComponents);
        o.singularKeywords=bool(m,"singularKeywords",o.singularKeywords);o.fromYear=integer(m,"fromYear",o.fromYear);o.toYear=integer(m,"toYear",o.toYear);o.sliceYears=integer(m,"sliceYears",o.sliceYears);
        o.topNPerSlice=integer(m,"topNPerSlice",o.topNPerSlice);o.pruning=text(m,"pruning",o.pruning);o.minClusterSize=integer(m,"minClusterSize",o.minClusterSize);o.burstFromSelectedNetwork=bool(m,"burstFromSelectedNetwork",o.burstFromSelectedNetwork);
        o.labelsPerCluster=integer(m,"labelsPerCluster",o.labelsPerCluster);o.labelSource=text(m,"labelSource",o.labelSource);o.clustering=text(m,"clustering",o.clustering);o.labeling=text(m,"labeling",o.labeling);
        var b=MiniJson.obj(m.get("burstParams"));o.burstParams.relI=number(b,"relI",o.burstParams.relI);o.burstParams.relJ=number(b,"relJ",o.burstParams.relJ);o.burstParams.relC=integer(b,"relC",o.burstParams.relC);o.burstParams.spanFrom=integer(b,"spanFrom",o.burstParams.spanFrom);o.burstParams.spanTo=integer(b,"spanTo",o.burstParams.spanTo);o.burstParams.transK=number(b,"transK",o.burstParams.transK);
        return o;
    }
    public static Pipeline.Options copy(Pipeline.Options o){return read(write(o));}
    private static String text(Map<String,Object> m,String key,String fallback){return m.containsKey(key)?Objects.toString(m.get(key),fallback):fallback;}
    private static double number(Map<String,Object> m,String key,double fallback){return m.containsKey(key)?MiniJson.num(m.get(key)):fallback;}
    private static int integer(Map<String,Object> m,String key,int fallback){return (int)number(m,key,fallback);}
    private static boolean bool(Map<String,Object> m,String key,boolean fallback){return m.get(key) instanceof Boolean value?value:fallback;}
}
