package ycscite.io;

import ycscite.Settings;
import ycscite.analysis.*;
import ycscite.model.Project;
import ycscite.ui.GraphCanvas;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** One configured engine for report, CLI, headless, Web and desktop. */
public final class NineFigureReport {
 public record Figure(String key,String title,String file){}
 public static final List<Figure> FIGURES=List.of(
  new Figure("publications","年度发文量趋势","01-publications.png"),new Figure("keywords","关键词共现网络","02-keywords.png"),
  new Figure("clusters","关键词聚类","03-clusters.png"),new Figure("timezone","关键词时区","04-timezone.png"),
  new Figure("timeline","关键词时间线","05-timeline.png"),new Figure("landscape","聚类演化山峰","06-landscape.png"),
  new Figure("bursts","关键词突现 Top 25","07-bursts.png"),new Figure("authors","作者合作网络","08-authors.png"),
  new Figure("institutions","机构合作网络","09-institutions.png"),new Figure("network","网络总览","10-network.png"),
  new Figure("circular","环形网络","11-circular.png"),new Figure("heatmap","聚类年度活动热力图","12-heatmap.png"),
  new Figure("tsne","t-SNE 降维网络","13-tsne.png"),new Figure("umap","UMAP 降维网络","14-umap.png"),
  new Figure("circle_pack","聚类圆形打包","15-circle-pack.png"),new Figure("thematic","主题战略图","16-thematic.png"),
  new Figure("contour","聚类活动等值线","17-contour.png"),new Figure("dual_map","期刊引文流","18-dual-map.png"));
 private NineFigureReport(){}
 public static void write(Project source,Path out,Consumer<String> progress)throws Exception{write(source,out,progress,new Settings());}
 public static void write(Project source,Path out,Consumer<String> progress,Settings settings)throws Exception{
  settings.validate();
  try(var language=ycscite.I18n.language(settings.text("language"))){
  Set<String> chosen=new LinkedHashSet<>(settings.list("charts"));
  for(String key:chosen)if(FIGURES.stream().noneMatch(f->f.key.equals(key)))throw new IllegalArgumentException("Unknown chart: "+key);
  if(chosen.isEmpty())throw new IllegalArgumentException("Choose at least one chart");
  var included=prepare(source,settings);Project p=included.project();int undated=included.undated(),excluded=included.excluded();
  progress.accept((settings.english()?"Records included: ":"纳入文献：")+p.records.size()+" · "+p.minYear+"–"+p.maxYear);
  Files.createDirectories(out);Path data=Files.createDirectories(out.resolve("data"));
  try(var stream=NineFigureReport.class.getResourceAsStream("/ycscite-logo.png")){if(stream!=null)Files.copy(stream,out.resolve("ycscite-logo.png"),StandardCopyOption.REPLACE_EXISTING);}
  Files.writeString(out.resolve("settings.json"),MiniJson.write(settings.map()));
  var results=new LinkedHashMap<String,Pipeline.Result>();
  var keywords=analyze(p,settings,"keyword");results.put("keywords",keywords);tables(data,"keywords",keywords);
  Exporters.writeClusterSummary(keywords,data.resolve("clusters.tsv"));
  NineFigureViews views=new NineFigureViews(p.minYear,p.maxYear,keywords.network,settings);
  views.themeTable(data.resolve("display-clusters.tsv"));
  // Publication counts remain available even when only a network view was selected.
  var counts=new TreeMap<Integer,Integer>();p.records.forEach(r->counts.merge(r.year,1,Integer::sum));
  var countText=new StringBuilder("year\tcount\n");for(int y=p.minYear;y<=p.maxYear;y++)countText.append(y).append('\t').append(counts.getOrDefault(y,0)).append('\n');
  Files.writeString(data.resolve("publications.tsv"),countText);
  Pipeline.Result focus=settings.text("network").equals("keyword")?keywords:analyze(p,settings,settings.text("network"));
  if(focus!=keywords){results.put("focus",focus);tables(data,"focus",focus);}
  ExtensionViews extensions=new ExtensionViews(focus,p.minYear,p.maxYear,settings);
  if(chosen.contains("heatmap")||chosen.contains("contour"))extensions.activityTable(data.resolve("cluster-activity.tsv"));
  List<Map<String,Object>> generated=new ArrayList<>();
  int completed=0;
  for(Figure figure:FIGURES){
   if(!chosen.contains(figure.key))continue;
   progress.accept("FIGURE "+(++completed)+"/"+chosen.size()+" "+settings.t(figure.title));
   Path png=out.resolve(figure.file);
   switch(figure.key){
    case "publications"->views.publications(p,png,data.resolve("publications.tsv"));
    case "keywords"->views.network(keywords,png,settings.t(figure.title),false);
    case "clusters"->views.clusters(keywords,png);
    case "timezone"->views.timezone(keywords,png);
    case "timeline"->views.timeline(keywords,png);
    case "landscape"->views.landscape(png);
    case "bursts"->views.bursts(p,keywords,png,data.resolve("bursts.tsv"));
    case "authors","institutions"->{var r=analyze(p,settings,figure.key.equals("authors")?"author":"institution");results.put(figure.key,r);tables(data,figure.key,r);views.network(r,png,settings.t(figure.title),true);}
    case "network"->views.network(focus,png,settings.t("网络总览"),focus.network.kind.equals(NetworkBuilders.CO_AUTHOR)||focus.network.kind.equals(NetworkBuilders.INSTITUTION));
    case "heatmap"->extensions.heatmap(png);
    case "circular"->extensions.circular(png);
    case "tsne"->extensions.embedding(png,true);
    case "umap"->extensions.embedding(png,false);
    case "circle_pack"->extensions.circlePack(png);
    case "thematic"->extensions.thematic(png,data.resolve("thematic-metrics.tsv"));
    case "contour"->extensions.contour(png);
    case "dual_map"->extensions.citationFlow(p,png,data.resolve("journal-flows.tsv"));
    default->{GraphCanvas.Mode mode=switch(figure.key){case "circular"->GraphCanvas.Mode.CIRCULAR;case "heatmap"->GraphCanvas.Mode.HEATMAP;case "tsne"->GraphCanvas.Mode.TSNE;case "umap"->GraphCanvas.Mode.UMAP;case "circle_pack"->GraphCanvas.Mode.CIRCLE_PACK;default->GraphCanvas.Mode.MERGED;};
      GraphCanvas canvas=canvas(focus,p,mode,settings);
      canvas.snapshotScaled(png,1320,818,settings.integer("image_scale"));}
   }
   Branding.variants(png,settings.bool("logo"));
   generated.add(Map.of("key",figure.key,"number",FIGURES.indexOf(figure)+1,"title",settings.t(figure.title).replace("25",Integer.toString(settings.integer("burst_top"))),"file",figure.file,
    "no_logo_file","no-logo/"+figure.file,"with_logo_file","with-logo/"+figure.file));
  }
  if(settings.list("exports").contains("svg")){
   canvas(focus,p,GraphCanvas.Mode.MERGED,settings).writeSvg(out.resolve("network.svg"),2400,1500);
   Branding.svgVariants(out.resolve("network.svg"),settings.bool("logo"),2400,1500);
  }
  if(settings.list("exports").contains("pajek"))Exporters.writePajek(focus.network,out.resolve("network.net"));
  if(settings.list("exports").contains("gexf"))Exporters.writeGexf(focus.network,out.resolve("network.gexf"));
  if(settings.list("exports").contains("ris"))RisExporter.write(p,out.resolve("references.ris"));
  if(settings.list("exports").contains("records-tsv"))RecordIo.writeTsv(p,out.resolve("records.tsv"));
  if(settings.list("exports").contains("pdf")){
   var paths=generated.stream().map(f->out.resolve(f.get("file").toString())).toList();
   PdfExporter.writeFigures(out.resolve("report.pdf"),paths);
   PdfExporter.writeFigures(out.resolve("with-logo/report.pdf"),generated.stream().map(f->out.resolve(f.get("with_logo_file").toString())).toList());
   PdfExporter.writeFigures(out.resolve("no-logo/report.pdf"),generated.stream().map(f->out.resolve(f.get("no_logo_file").toString())).toList());
  }
  if(settings.list("exports").contains("docx"))DocxExporter.write(out.resolve("report.docx"),focus.network,p.name,List.of("Records: "+p.records.size(),"Years: "+p.minYear+"–"+p.maxYear));
  if(settings.list("exports").contains("sql"))MySqlStore.writeDump(p,focus.network,"ycscite",out.resolve("data.sql"));
  CitationReport.writeTsv(focus.network,0,data.resolve("citation-report.tsv"));
  var dataNames=new LinkedHashSet<String>(List.of("publications.tsv","keywords-nodes.tsv","keywords-edges.tsv","keywords-years.tsv","clusters.tsv","display-clusters.tsv","citation-report.tsv"));
  for(String kind:results.keySet()){dataNames.add(kind+"-nodes.tsv");dataNames.add(kind+"-edges.tsv");dataNames.add(kind+"-years.tsv");}
  if(chosen.contains("thematic"))dataNames.add("thematic-metrics.tsv");
  if(chosen.contains("dual_map"))dataNames.add("journal-flows.tsv");
  if(chosen.contains("heatmap")||chosen.contains("contour"))dataNames.add("cluster-activity.tsv");
  if(chosen.contains("landscape"))dataNames.add("landscape.tsv");if(chosen.contains("bursts"))dataNames.add("bursts.tsv");
  for(String module:settings.list("analysis_modules")){
   String file=switch(module){case "cluster_summary"->"focus-clusters.tsv";case "year_labels"->"year-labels.tsv";case "cluster_links"->"cluster-links.tsv";case "network_growth"->"network-growth.tsv";case "reference_diversity"->"reference-diversity.tsv";case "citation_path"->"citation-path.tsv";case "sentences"->"cluster-sentences.tsv";case "terms_tfidf"->"terms-tfidf.tsv";case "terms_clumping"->"terms-clumping.tsv";case "concept_tree"->"concept-tree.txt";case "narrative"->"narrative.txt";default->"citation-report.tsv";};
   switch(module){
    case "cluster_summary"->Exporters.writeClusterSummary(focus,data.resolve(file));
    case "year_labels"->Exporters.writeYearByYearLabels(focus,data.resolve(file));
    case "cluster_links"->Files.writeString(data.resolve(file),ClusterDependency.toTsv(ClusterDependency.compute(focus.network)));
    case "network_growth"->Files.writeString(data.resolve(file),StructuralVariation.toTsv(StructuralVariation.compute(p,focus.network.kind)));
    case "reference_diversity"->Files.writeString(data.resolve(file),Uncertainty.toTsv(Uncertainty.compute(p)));
    case "citation_path"->Files.writeString(data.resolve(file),MainPath.toTsv(MainPath.compute(p)));
    case "sentences"->Files.writeString(data.resolve(file),SentenceSummarizer.summarize(focus,p,3));
    case "terms_tfidf"->Files.writeString(data.resolve(file),TextSubsystem.rankedTermsTfIdf(p,100));
    case "terms_clumping"->Files.writeString(data.resolve(file),TextSubsystem.rankedTermsClumping(p,100));
    case "concept_tree"->Files.writeString(data.resolve(file),TextSubsystem.conceptTree(focus.network,60,3));
    case "narrative"->Files.writeString(data.resolve(file),TextSubsystem.narrative(focus,p));
   }
   dataNames.add(file);
  }

  var manifest=new LinkedHashMap<String,Object>();manifest.put("schema_version",2);manifest.put("engine_version",ycscite.Cli.VERSION);manifest.put("fingerprint_schema",DataFingerprint.SCHEMA);manifest.put("source_fingerprint",DataFingerprint.of(source));manifest.put("analysis_fingerprint",DataFingerprint.of(p));manifest.put("project",p.name);
  manifest.put("records",p.records.size());manifest.put("deduplicated_records",included.duplicates());manifest.put("undated_records",undated);manifest.put("excluded_by_year",excluded);
  manifest.put("from_year",p.minYear);manifest.put("to_year",p.maxYear);manifest.put("parameters",settings.map());manifest.put("figures",generated);
  var stats=new ArrayList<Map<String,Object>>();for(var e:results.entrySet()){var r=e.getValue();stats.add(Map.of("kind",e.getKey(),"nodes",r.network.nodes.size(),"edges",r.network.edges.size(),"modularity",r.modularity,"silhouette",weightedSilhouette(r)));}
  var artifacts=new ArrayList<Map<String,Object>>();
  String[][] exportFiles={{"svg","network.svg"},{"pdf","report.pdf"},{"docx","report.docx"},{"sql","data.sql"},{"pajek","network.net"},{"gexf","network.gexf"},{"ris","references.ris"},{"records-tsv","records.tsv"}};
  for(var export:exportFiles)if(settings.list("exports").contains(export[0])){
   var artifact=new LinkedHashMap<String,Object>();artifact.put("format",export[0]);artifact.put("file",export[1]);
   if(export[0].equals("svg")||export[0].equals("pdf")){artifact.put("no_logo_file","no-logo/"+export[1]);artifact.put("with_logo_file","with-logo/"+export[1]);}
   artifacts.add(artifact);
  }
  var projectFiles=new ArrayList<Map<String,Object>>();Path projects=Files.createDirectories(out.resolve("projects"));
  for(var entry:results.entrySet()){
   String kind=entry.getKey(),networkKey=kind.equals("authors")?"author":kind.equals("institutions")?"institution":kind.equals("focus")?settings.text("network"):"keyword";
   String file="projects/"+kind+".csl-proj";ProjectStore.save(out.resolve(file),p,entry.getValue(),settings.pipeline(networkKey));
   projectFiles.add(Map.of("kind",kind,"file",file,"network",networkKey,"analysis_fingerprint",DataFingerprint.of(p)));
  }
  manifest.put("project_files",projectFiles);
  manifest.put("artifacts",artifacts);
  manifest.put("data_files",new ArrayList<>(dataNames));
  manifest.put("networks",stats);manifest.put("landscape_metric","annual_keyword_occurrences_normalized_per_cluster");
  Files.writeString(out.resolve("manifest.json"),MiniJson.write(manifest));index(out,p,undated,settings,generated);
  }
 }
 public record Included(Project project,int undated,int excluded,int duplicates){}
 public static Included prepare(Project source,Settings settings){
  Project p=new Project();p.name=source.name;int undated=0,excluded=0;
  for(var record:source.records){
   if(record.year<=0){undated++;continue;}
   if((settings.integer("from_year")>0&&record.year<settings.integer("from_year"))||(settings.integer("to_year")>0&&record.year>settings.integer("to_year"))){excluded++;continue;}
   p.add(record);
  }
  int duplicates=settings.bool("deduplicate")?p.dedup():0;
  if(p.records.isEmpty())throw new IllegalArgumentException("No dated records in the selected year range");
  return new Included(p,undated,excluded,duplicates);
 }
 private static double weightedSilhouette(Pipeline.Result result){double sum=0,count=0;for(var stats:result.clusterStats.values())if(stats.length>1&&Double.isFinite(stats[1])){sum+=stats[0]*stats[1];count+=stats[0];}return count>0?sum/count:0;}
 private static Pipeline.Result analyze(Project p,Settings settings,String kind){return Pipeline.run(p,settings.pipeline(kind));}
 private static GraphCanvas canvas(Pipeline.Result r,Project p,GraphCanvas.Mode mode,Settings s){
  var c=new GraphCanvas();c.setTheme(GraphCanvas.Theme.LIGHT);c.setClusterLabels(r.clusterLabels);c.setNodeScale(s.number("node_scale"));c.setLabelFontSize((float)(12*s.number("label_scale")));
  c.setLabelBudget(s.integer("label_budget"));c.setLinkTransparency((float)s.number("link_opacity"));c.show(r.network,mode,p.name);return c;
 }
 private static void tables(Path dir,String name,Pipeline.Result r)throws Exception{
  Exporters.writeTables(r.network,dir.resolve(name+"-nodes.tsv"),dir.resolve(name+"-edges.tsv"),true);
  var years=new StringBuilder("id\tyear\tcount\n");for(var n:r.network.nodeCollection())for(var e:n.countsByYear.entrySet())years.append(n.id.replace('\t',' ')).append('\t').append(e.getKey()).append('\t').append(e.getValue()).append('\n');
  Files.writeString(dir.resolve(name+"-years.tsv"),years);
 }
 private static String escape(Object o){return Objects.toString(o,"").replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
 private static void index(Path out,Project p,int undated,Settings s,List<Map<String,Object>> figures)throws Exception{
  boolean en=s.english();String title=en?"Ycscite analysis report":"Ycscite 文献分析报告";
  var html=new StringBuilder("<!doctype html><html lang=\""+(en?"en":"zh-CN")+"\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+title+"</title><style>body{font:15px/1.6 system-ui,sans-serif;margin:0;background:#f4f7fa;color:#243a52}main{max-width:1400px;margin:auto;padding:28px}header{display:flex;gap:14px;align-items:center}header img{width:46px}section{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,520px),1fr));gap:18px}figure{background:white;border:1px solid #dfe5ec;border-radius:6px;margin:0;overflow:hidden}figure img{width:100%;height:400px;object-fit:contain}figcaption{padding:14px}a{color:#305f91}p{color:#687c91}</style><main><header><img src=\"ycscite-logo.png\" alt=\"Ycscite\"><h1>"+title+"</h1></header>");
  html.append("<p>").append(p.records.size()).append(en?" included records · ":" 篇纳入文献 · ").append(p.minYear).append("–").append(p.maxYear).append(" · ").append(undated).append(en?" undated records excluded":" 条无年份记录未计入").append("</p>");
  html.append("<p><a href=\"settings.json\">").append(en?"Reproducible settings":"可复用参数").append("</a> · <a href=\"data/publications.tsv\">").append(en?"Annual counts":"年度计数").append("</a> · <a href=\"data/display-clusters.tsv\">").append(en?"Cluster mapping":"聚类映射").append("</a></p><section>");
  html.append("</section><p>").append(en?"Editable projects: ":"可编辑项目：");
  for(String kind:List.of("keywords","authors","institutions","focus"))if(Files.isRegularFile(out.resolve("projects/"+kind+".csl-proj")))html.append("<a download href=\"projects/").append(kind).append(".csl-proj\">").append(en?kind:switch(kind){case "keywords"->"关键词";case "authors"->"作者";case "institutions"->"机构";default->"当前分析网络";}).append("</a> · ");
  html.append("</p><section>");
  for(var f:figures){String file=escape(f.get("file"));html.append("<figure><a href=\"").append(file).append("\"><img loading=\"lazy\" src=\"").append(file).append("\" alt=\"").append(escape(f.get("title"))).append("\"></a><figcaption>").append(escape(f.get("title"))).append(" · <a download href=\"with-logo/").append(file).append("\">").append(en?"With logo":"带标识下载").append("</a> · <a download href=\"no-logo/").append(file).append("\">").append(en?"Without logo":"无标识下载").append("</a></figcaption></figure>");}
  Files.writeString(out.resolve("index.html"),html.append("</section></main></html>").toString());
 }
}
