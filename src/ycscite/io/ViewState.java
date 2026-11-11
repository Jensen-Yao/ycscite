package ycscite.io;

import ycscite.model.Network;
import java.util.*;

/** Versioned, data-only canvas state. Validation precedes mutation or rendering. */
public final class ViewState {
    public static final Set<String> MODES=Set.of("TIMELINE","TIMEZONE","MERGED","TSNE","CIRCULAR","HEATMAP","LANDSCAPE","UMAP","CIRCLE_PACK");
    public static final Set<String> SPATIAL=Set.of("TIMELINE","TIMEZONE","MERGED","TSNE","CIRCULAR","UMAP","CIRCLE_PACK");
    private static final Set<String> FIELDS=Set.of("schema_version","network_fingerprint","mode","title","positions","bands","display","camera","hidden","focus","selected","expanded","highlight_cluster","highlights","overlays","overlay_keys","parameter_lines","year_cutoff");
    private static final Set<String> BOOLEANS=Set.of("fisheye","convex_hull","original_style","weighted_links","curved_links","cluster_links","effects_3d","link_labels","cluster_ids","citation_counts","cluster_label_colors","single_color_links","publication_types","label_shadow","transparent");
    private static final Map<String,String[]> CHOICES=Map.of("theme",new String[]{"DARK","LIGHT","SEPIA"},"size_mode",new String[]{"FREQ","SIGMA","CENTRALITY","DEGREE","EIGEN","PAGERANK","ENTROPY","RELATIVE_ENTROPY","INFO_BIAS","UNIFORM"},"shape",new String[]{"CIRCLE","DIAMOND","SQUARE"},"layout",new String[]{"LINLOG","KAMADA_KAWAI","FORCE_DIRECTED"});
    private static final Map<String,double[]> RANGES=Map.ofEntries(Map.entry("label_budget",new double[]{0,Integer.MAX_VALUE}),Map.entry("font_size",new double[]{6,24}),Map.entry("label_angle",new double[]{-180,180}),Map.entry("node_scale",new double[]{.25,4}),Map.entry("link_opacity",new double[]{0,1}),Map.entry("kcore",new double[]{0,100000}),Map.entry("fill_pattern",new double[]{0,3}),Map.entry("label_attempts",new double[]{10,1000}),Map.entry("fisheye_x",new double[]{-1e6,1e6}),Map.entry("fisheye_y",new double[]{-1e6,1e6}),Map.entry("fisheye_strength",new double[]{0,100}));
    private static final Set<String> INTEGERS=Set.of("label_budget","label_angle","kcore","fill_pattern","label_attempts");
    private static final Set<String> COLORS=Set.of("cluster_label_bg","article_label_bg","term_label_bg","overlay_label_bg");
    private ViewState(){}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException("Invalid saved view: "+reason);}
    public static Map<String,Object> object(Object value,String field){if(!(value instanceof Map<?,?> map))throw invalid(field+" must be an object");for(Object key:map.keySet())if(!(key instanceof String))throw invalid(field+" keys must be strings");return MiniJson.obj(value);}
    private static void only(Map<String,Object> map,Set<String> allowed,String field){for(String key:map.keySet())if(!allowed.contains(key))throw invalid(field+" has unknown field "+key);}
    public static double number(Object value,String field,double min,double max){if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw invalid(field+" must be finite in "+min+".."+max);return n.doubleValue();}
    public static int integer(Object value,String field,int min,int max){double n=number(value,field,min,max);if(n!=Math.rint(n))throw invalid(field+" must be an integer");return (int)n;}
    public static String text(Object value,String field){if(!(value instanceof String s)||s.length()>65536)throw invalid(field+" must be a bounded string");return s;}
    private static List<Object> list(Object value,String field,int max){if(!(value instanceof List<?> values)||values.size()>max)throw invalid(field+" must be a bounded array");return MiniJson.arr(value);}
    private static void pair(Object value,String field){var a=list(value,field,2);if(a.size()!=2)throw invalid(field+" needs two coordinates");number(a.get(0),field,-1e6,1e6);number(a.get(1),field,-1e6,1e6);}
    private static void node(Object value,String field,Network graph){if(value!=null&&!graph.nodes.containsKey(text(value,field)))throw invalid(field+" references an absent node");}
    public static Map<String,Object> checked(Object value,Network graph,String mode){
        var m=object(value,"view");only(m,FIELDS,"view");if(integer(m.get("schema_version"),"schema_version",1,1)!=1)throw invalid("unknown schema");
        String selected=text(m.get("mode"),"mode");if(!MODES.contains(selected)||mode!=null&&!mode.equals(selected))throw invalid("mode does not match the requested canvas");
        if(!ProjectStore.networkFingerprint(graph).equals(m.get("network_fingerprint")))throw invalid("the layout belongs to a different analyzed network");text(m.get("title"),"title");
        var positions=object(m.get("positions"),"positions");if(SPATIAL.contains(selected)&&positions.size()!=graph.nodes.size())throw invalid("node positions are incomplete");
        for(var p:positions.entrySet()){node(p.getKey(),"position",graph);pair(p.getValue(),"position");}
        var bands=object(m.get("bands"),"bands");if(bands.size()>graph.nodes.size())throw invalid("too many cluster bands");for(var band:bands.entrySet()){try{Integer.parseInt(band.getKey());}catch(NumberFormatException error){throw invalid("invalid cluster band");}pair(band.getValue(),"band");}
        var display=object(m.get("display"),"display");var displayFields=new HashSet<>(BOOLEANS);displayFields.addAll(CHOICES.keySet());displayFields.addAll(RANGES.keySet());displayFields.addAll(COLORS);only(display,displayFields,"display");if(!display.keySet().equals(displayFields))throw invalid("display options are incomplete");
        for(String key:BOOLEANS)if(!(display.get(key) instanceof Boolean))throw invalid(key+" must be boolean");
        for(var entry:CHOICES.entrySet())if(!Arrays.asList(entry.getValue()).contains(text(display.get(entry.getKey()),entry.getKey())))throw invalid("unknown "+entry.getKey());
        for(var entry:RANGES.entrySet()){double n=number(display.get(entry.getKey()),entry.getKey(),entry.getValue()[0],entry.getValue()[1]);if(INTEGERS.contains(entry.getKey())&&n!=Math.rint(n))throw invalid(entry.getKey()+" must be integral");}
        for(String key:COLORS)if(display.get(key)!=null)integer(display.get(key),key,Integer.MIN_VALUE,Integer.MAX_VALUE);
        var camera=object(m.get("camera"),"camera");only(camera,Set.of("scale","center_x","center_y"),"camera");number(camera.get("scale"),"camera scale",.001,200);number(camera.get("center_x"),"camera x",-1e6,1e6);number(camera.get("center_y"),"camera y",-1e6,1e6);
        var hidden=list(m.get("hidden"),"hidden",graph.nodes.size());if(new HashSet<>(hidden).size()!=hidden.size())throw invalid("hidden nodes contain duplicates");for(Object id:hidden)node(id,"hidden",graph);
        for(String key:List.of("focus","selected","expanded"))node(m.get(key),key,graph);
        integer(m.get("highlight_cluster"),"highlight_cluster",Integer.MIN_VALUE,Integer.MAX_VALUE);integer(m.get("year_cutoff"),"year_cutoff",0,Integer.MAX_VALUE);
        for(String key:List.of("highlights","overlay_keys","parameter_lines"))for(Object item:list(m.get(key),key,100000))text(item,key);
        for(Object edge:list(m.get("overlays"),"overlays",100000)){var ends=list(edge,"overlay",2);if(ends.size()!=2)throw invalid("overlay requires two endpoints");for(Object item:ends)text(item,"overlay endpoint");}
        return MiniJson.obj(MiniJson.parse(MiniJson.write(m)));
    }
    public static Map<String,Object> editor(Object value,Network graph){
        var m=object(value,"presentation");only(m,Set.of("schema_version","language","active_view","sidebar_tab","canvases"),"presentation");integer(m.get("schema_version"),"presentation schema",1,1);if(m.containsKey("sidebar_tab"))integer(m.get("sidebar_tab"),"sidebar_tab",0,3);
        if(!Set.of("zh","en").contains(text(m.get("language"),"language")))throw invalid("unknown presentation language");String active=text(m.get("active_view"),"active_view");var views=object(m.get("canvases"),"canvases");if(!views.containsKey(active)||!MODES.contains(active)||views.size()>MODES.size())throw invalid("active view is unavailable");
        for(var entry:views.entrySet())checked(entry.getValue(),graph,entry.getKey());return MiniJson.obj(MiniJson.parse(MiniJson.write(m)));
    }
}
