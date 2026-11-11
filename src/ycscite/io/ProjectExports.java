package ycscite.io;

import ycscite.Cli;
import ycscite.I18n;
import ycscite.ui.GraphCanvas;
import ycscite.ui.Strings;
import java.nio.file.*;
import java.util.*;

/** GUI-free project inspection and export. No pipeline recomputation or window creation. */
public final class ProjectExports {
    private ProjectExports(){}
    private static final Set<String> KEYS=Set.of("action","path","output","formats","view","language","logo","width","height");
    private static String string(Map<String,Object> values,String key,String fallback){Object value=values.get(key);if(value==null)return fallback;if(!(value instanceof String text)||text.isBlank())throw new IllegalArgumentException(key+" must be a nonempty string");return text;}
    public static Object dispatch(Map<String,Object> params)throws Exception{
        for(String key:params.keySet())if(!KEYS.contains(key))throw new IllegalArgumentException("Unknown project parameter: "+key);
        String action=string(params,"action","inspect");Path path=Path.of(string(params,"path","")).toAbsolutePath().normalize();if(!Files.isRegularFile(path))throw new IllegalArgumentException("Project file does not exist: "+path);
        var saved=ProjectStore.load(path);if(saved.result==null)throw new IllegalArgumentException("The project has no analyzed network");
        return switch(action){case "inspect"->inspect(path,saved);case "export"->export(path,saved,params);default->throw new IllegalArgumentException("Project action must be inspect or export");};
    }
    private static Map<String,Object> inspect(Path path,ProjectStore.Bundle saved){
        var result=new LinkedHashMap<String,Object>();result.put("path",path.toString());result.put("format",ProjectStore.FORMAT);result.put("project",saved.project.name);result.put("records",saved.project.records.size());
        result.put("analysis_fingerprint",DataFingerprint.of(saved.project));result.put("network_fingerprint",ProjectStore.networkFingerprint(saved.result.network));result.put("network",saved.result.network.kind);
        result.put("nodes",saved.result.network.nodes.size());result.put("edges",saved.result.network.edges.size());result.put("modularity",saved.result.modularity);result.put("options",PipelineOptionsCodec.write(saved.options));
        result.put("has_saved_views",!saved.presentation.isEmpty());result.put("saved_views",new ArrayList<>(MiniJson.obj(saved.presentation.get("canvases")).keySet()));result.put("active_view",saved.presentation.get("active_view"));result.put("export_logo",saved.exportLogo);return result;
    }
    public static GraphCanvas canvas(ProjectStore.Bundle saved,GraphCanvas.Mode mode){
        var canvas=new GraphCanvas();canvas.setTheme(GraphCanvas.Theme.LIGHT);canvas.setClusterLabels(saved.result.clusterLabels);
        if(saved.viewDefaults!=null){var settings=saved.viewDefaults;canvas.setNodeScale(settings.number("node_scale"));canvas.setLabelFontSize((float)(12*settings.number("label_scale")));canvas.setLabelBudget(settings.integer("label_budget"));canvas.setLinkTransparency((float)settings.number("link_opacity"));canvas.setLabelShadow(false);}
        var views=MiniJson.obj(saved.presentation.get("canvases"));Map<String,Object> state=views.containsKey(mode.name())?MiniJson.obj(views.get(mode.name())):null;
        canvas.show(saved.result.network,mode,saved.project.name+" — "+saved.result.network.kind,state);return canvas;
    }
    private static Object export(Path project,ProjectStore.Bundle saved,Map<String,Object> params)throws Exception{
        String language=string(params,"language",Objects.toString(saved.presentation.get("language"),saved.viewDefaults!=null?saved.viewDefaults.text("language"):"zh"));if(!Set.of("zh","en").contains(language))throw new IllegalArgumentException("language must be zh or en");
        String selected=string(params,"view","saved");String key=selected.equalsIgnoreCase("saved")?Objects.toString(saved.presentation.get("active_view"),"MERGED"):selected.toUpperCase(Locale.ROOT).replace('-','_');
        if(!ViewState.MODES.contains(key))throw new IllegalArgumentException("Unknown project view: "+selected);var mode=GraphCanvas.Mode.valueOf(key);
        int width=params.containsKey("width")?ViewState.integer(params.get("width"),"width",64,8000):1600,height=params.containsKey("height")?ViewState.integer(params.get("height"),"height",64,8000):1000;
        if((long)width*height>40_000_000)throw new IllegalArgumentException("Image dimensions exceed 40 million pixels");
        Object requested=params.getOrDefault("formats",List.of("png"));if(!(requested instanceof List<?> formats)||formats.isEmpty()||new HashSet<>(formats).size()!=formats.size()||formats.stream().anyMatch(f->!(f instanceof String)||!Set.of("png","svg","pdf","tsv").contains(f)))throw new IllegalArgumentException("formats must be a nonempty list of png, svg, pdf, tsv without duplicates");
        if(params.containsKey("logo")&&!(params.get("logo") instanceof Boolean))throw new IllegalArgumentException("logo must be boolean");boolean logo=params.get("logo") instanceof Boolean value?value:saved.exportLogo==null||saved.exportLogo;
        Path out=Path.of(string(params,"output","ycscite-project-export")).toAbsolutePath().normalize();if(out.equals(project)||out.startsWith(project))throw new IllegalArgumentException("Output must be a directory separate from the project file");
        var views=MiniJson.obj(saved.presentation.get("canvases"));boolean restored=views.containsKey(mode.name());int visible=0;var artifacts=new ArrayList<Map<String,Object>>();var previous=Strings.getLang();
        try(var scope=I18n.language(language)){
            Strings.setLang(language.equals("en")?Strings.Lang.EN:Strings.Lang.ZH);
            boolean graphical=formats.stream().anyMatch(format->!format.equals("tsv"));var canvas=graphical?canvas(saved,mode):null;
            var visibility=restored?MiniJson.obj(views.get(mode.name())):Map.<String,Object>of();var hidden=new HashSet<>(MiniJson.arr(visibility.get("hidden")));int cutoff=visibility.get("year_cutoff") instanceof Number n?n.intValue():Integer.MAX_VALUE;
            visible=(int)saved.result.network.nodeCollection().stream().filter(node->!hidden.contains(node.id)&&(node.firstYear==Integer.MAX_VALUE?0:node.firstYear)<=cutoff).count();Files.createDirectories(out);
            for(Object format:formats)switch((String)format){
                case "png"->{Path file=out.resolve("network.png");var image=canvas.renderImage(width,height);try{Branding.writePng(file,image,logo);}finally{image.flush();}artifacts.add(artifact("png","network.png",true));}
                case "svg"->{var vector=canvas;if(!ViewState.SPATIAL.contains(mode.name())){
                    vector=canvas(saved,GraphCanvas.Mode.MERGED);if(restored){var state=vector.captureState();var original=MiniJson.obj(views.get(mode.name()));for(String field:List.of("display","hidden","highlights","year_cutoff","overlays","overlay_keys"))state.put(field,original.get(field));vector.restoreState(state);}
                }Path file=out.resolve("network.svg");vector.writeSvg(file,width,height);Branding.svgVariants(file,logo,width,height);artifacts.add(artifact("svg","network.svg",true));}
                case "pdf"->{Path file=out.resolve("report.pdf");var image=canvas.renderImage(width,height);try{PdfExporter.variants(file,image,saved.result.network,saved.project.name,logo);}finally{image.flush();}artifacts.add(artifact("pdf","report.pdf",true));}
                case "tsv"->{Exporters.writeTables(saved.result.network,saved.result.clusterLabels,out.resolve("nodes.tsv"),out.resolve("edges.tsv"));Exporters.writeClusterSummary(saved.result,out.resolve("clusters.tsv"));for(String file:List.of("nodes.tsv","edges.tsv","clusters.tsv"))artifacts.add(artifact("tsv",file,false));}
            }
        }finally{Strings.setLang(previous);}
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schema_version",1);manifest.put("engine_version",Cli.VERSION);manifest.put("project_file",project.toString());manifest.put("project",saved.project.name);manifest.put("analysis_fingerprint",DataFingerprint.of(saved.project));manifest.put("network_fingerprint",ProjectStore.networkFingerprint(saved.result.network));manifest.put("view",mode.name());manifest.put("restored_saved_view",restored);manifest.put("language",language);manifest.put("logo",logo);manifest.put("width",width);manifest.put("height",height);manifest.put("nodes",saved.result.network.nodes.size());manifest.put("visible_nodes",visible);manifest.put("edges",saved.result.network.edges.size());manifest.put("modularity",saved.result.modularity);manifest.put("artifacts",artifacts);manifest.put("table_scope","complete-analyzed-network");manifest.put("raster_scope","full-figure-at-saved-node-positions");manifest.put("svg_scope",ViewState.SPATIAL.contains(mode.name())?"saved-or-computed-network-layout":"network-overview");
        Files.writeString(out.resolve("project-export.json"),MiniJson.write(manifest));return Map.of("output",out.toString(),"manifest",manifest);
    }
    private static Map<String,Object> artifact(String format,String file,boolean variants){var item=new LinkedHashMap<String,Object>();item.put("format",format);item.put("file",file);if(variants){item.put("no_logo_file","no-logo/"+file);item.put("with_logo_file","with-logo/"+file);}return item;}
}
