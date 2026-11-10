package ycscite;

import ycscite.analysis.Pipeline;
import ycscite.analysis.Labelers;
import java.nio.file.*;
import java.util.*;
import ycscite.io.MiniJson;

/** Serializable settings shared by batch, headless, CLI and the report renderer. */
public final class Settings {
    private final Map<String,Object> values = new LinkedHashMap<>();
    public static final Map<String,Object> DEFAULTS;
    static {
        var d=new LinkedHashMap<String,Object>();
        d.put("format","auto"); d.put("language","zh"); d.put("logo",true);
        d.put("network","keyword"); d.put("from_year",0); d.put("to_year",0); d.put("slice_years",1);
        d.put("selection","gindex"); d.put("g_index_k",25.0); d.put("top_n",0); d.put("per_slice",25); d.put("top_percent",50.0);
        d.put("pruning","none"); d.put("similarity","raw"); d.put("clustering","modularity"); d.put("labeling","llr");
        d.put("label_source","k"); d.put("min_cluster",1); d.put("min_link",1); d.put("max_links",0);
        d.put("largest_components",0); d.put("noun_phrase_min",0); d.put("noun_phrase_max",0);
        d.put("keep_isolated",true); d.put("deduplicate",false); d.put("singular_keywords",false);
        d.put("burst_transition",.45); d.put("burst_min_years",2); d.put("burst_top",25);
        d.put("image_scale",4); d.put("node_scale",1.0); d.put("label_scale",1.0); d.put("link_opacity",1.0); d.put("label_budget",23);
        d.put("tsne_perplexity",15.0);d.put("tsne_iterations",600);d.put("umap_iterations",300);d.put("embedding_seed",42);
        d.put("charts",List.of("publications","keywords","clusters","timezone","timeline","landscape","bursts","authors","institutions"));
        d.put("analysis_modules",List.of("citation"));
        d.put("exports",List.of("png","tsv","html")); d.put("exclude_words",List.of());
        DEFAULTS=Collections.unmodifiableMap(d);
    }
    public Settings(){values.putAll(DEFAULTS);}
    public Settings(Map<String,Object> overrides){this();for(var e:overrides.entrySet()){
        if(!DEFAULTS.containsKey(e.getKey()))throw new IllegalArgumentException("Unknown setting: "+e.getKey());
        values.put(e.getKey(),e.getValue());
    }validate();}
    public static Settings read(Path path)throws Exception{return new Settings(MiniJson.obj(MiniJson.parse(Files.readString(path))));}
    public Map<String,Object> map(){return Collections.unmodifiableMap(values);}
    public String text(String key){return Objects.toString(values.get(key),"");}
    public int integer(String key){return (int)number(key);}
    public double number(String key){Object v=values.get(key);if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException(key+" must be finite numeric");return n.doubleValue();}
    public boolean bool(String key){Object v=values.get(key);if(!(v instanceof Boolean b))throw new IllegalArgumentException(key+" must be boolean");return b;}
    public List<String> list(String key){Object v=values.get(key);if(!(v instanceof List<?> a))throw new IllegalArgumentException(key+" must be a list");return a.stream().map(o->{if(!(o instanceof String s))throw new IllegalArgumentException(key+" must contain strings");return s;}).toList();}
    public boolean english(){return text("language").equals("en");}
    private void choice(String key,String...choices){if(Arrays.stream(choices).noneMatch(text(key)::equals))throw new IllegalArgumentException(key+" must be one of "+Arrays.toString(choices));}
    private void range(String key,double lo,double hi){double n=number(key);if(n<lo||n>hi)throw new IllegalArgumentException(key+" must be "+lo+".."+hi);}
    public void validate(){
        choice("language","zh","en");choice("selection","gindex","topn","topnpct");choice("pruning","none","mst","pathfinder");
        choice("clustering","modularity","spectral","em");choice("labeling","llr","lsi","isikw","tfidf","mi","kcore");
        choice("network","keyword","term","author","institution","cocitation","coupling","aca","jca","country","category");
        choice("format","auto","wos","scopus","pubmed","cnki","cssci","derwent","ris","proquest","dimensions","kci","nsf","bibtex","bib","pubmed-xml","records-tsv");
        choice("similarity","raw","cosine","dice","jaccard","inclusion","equivalence");choice("label_source","k","t","a","wc","r","tka");
        for(String k:List.of("from_year","to_year"))range(k,0,3000);
        if(integer("from_year")>0&&integer("to_year")>0&&integer("from_year")>integer("to_year"))throw new IllegalArgumentException("from_year must not exceed to_year");
        range("slice_years",1,100);range("g_index_k",1,10000);range("top_percent",.01,100);
        for(String k:List.of("top_n","per_slice","max_links","largest_components","noun_phrase_min","noun_phrase_max","label_budget"))range(k,0,100000);
        for(String k:List.of("min_cluster","min_link","burst_min_years","burst_top"))range(k,1,10000);
        if(integer("noun_phrase_max")>0&&integer("noun_phrase_min")>integer("noun_phrase_max"))throw new IllegalArgumentException("noun_phrase_min must not exceed noun_phrase_max");
        range("tsne_perplexity",1,200);range("tsne_iterations",100,3000);range("umap_iterations",100,3000);range("embedding_seed",0,Integer.MAX_VALUE);
        range("burst_transition",0,100);range("image_scale",1,5);range("node_scale",.25,4);range("label_scale",.5,3);range("link_opacity",0,1);
        for(String k:List.of("logo","keep_isolated","deduplicate","singular_keywords"))bool(k);
        for(String k:List.of("charts","exports","exclude_words","analysis_modules"))list(k);
        for(String k:List.of("g_index_k","from_year","to_year","slice_years","top_n","per_slice","min_cluster","min_link","max_links","largest_components","noun_phrase_min","noun_phrase_max","burst_min_years","burst_top","image_scale","label_budget","tsne_iterations","umap_iterations","embedding_seed"))
            if(number(k)!=Math.rint(number(k)))throw new IllegalArgumentException(k+" must be an integer");
        var charts=list("charts");
        if(charts.isEmpty()||new HashSet<>(charts).size()!=charts.size())throw new IllegalArgumentException("charts must be a nonempty list without duplicates");
        var allowedCharts=ycscite.io.NineFigureReport.FIGURES.stream().map(ycscite.io.NineFigureReport.Figure::key).collect(java.util.stream.Collectors.toSet());
        for(String chart:charts)if(!allowedCharts.contains(chart))throw new IllegalArgumentException("Unknown chart: "+chart);
        for(String module:list("analysis_modules"))if(!Set.of("citation","cluster_summary","year_labels","cluster_links","network_growth","reference_diversity","citation_path","sentences","terms_tfidf","terms_clumping","concept_tree","narrative").contains(module))throw new IllegalArgumentException("Unknown analysis module: "+module);
        for(String export:list("exports"))if(!Set.of("png","tsv","html","svg","pdf","docx","sql","pajek","gexf","ris","records-tsv").contains(export))throw new IllegalArgumentException("Unknown export: "+export);
    }
    public Pipeline.Options pipeline(String kind){
        var o=Cli.options(kind==null?text("network"):kind,integer("top_n"),text("clustering"),text("labeling"));
        o.fromYear=integer("from_year");o.toYear=integer("to_year")==0?9999:integer("to_year");o.sliceYears=integer("slice_years");
        o.topNPerSlice=integer("per_slice");o.selection=text("selection");o.gindexK=number("g_index_k");o.topPercent=number("top_percent");
        o.pruning=text("pruning");o.similarity=text("similarity");o.minClusterSize=integer("min_cluster");o.minLinkStrength=integer("min_link");
        o.maxLinksPerNode=integer("max_links");o.largestKComponents=integer("largest_components");o.nounPhraseMin=integer("noun_phrase_min");o.nounPhraseMax=integer("noun_phrase_max");
        o.strictSelection=bool("keep_isolated");o.singularKeywords=bool("singular_keywords");o.burstParams.transK=number("burst_transition");
        o.burstFromSelectedNetwork=true;o.excludeWords=new HashSet<>(list("exclude_words"));
        o.labelSource=switch(text("label_source")){case "t"->Labelers.SRC_T;case "a"->Labelers.SRC_A;case "wc"->Labelers.SRC_WC;case "r"->Labelers.SRC_R;case "tka"->Labelers.SRC_TKA;default->Labelers.SRC_K;};
        return o;
    }
    public String t(String chinese){return english()?I18n.english(chinese):chinese;}
}
