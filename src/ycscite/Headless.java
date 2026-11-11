package ycscite;

import ycscite.io.*;
import ycscite.model.Project;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Newline-delimited JSON service. No windows, dialogs, browser or stdout progress. */
public final class Headless {
 private Headless(){}
 public static int stdio()throws Exception{
  var protocol=System.out;
  try(var reader=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
   String line;
   while((line=reader.readLine())!=null){
    if(line.isBlank())continue;
    Map<String,Object> request=Map.of();Map<String,Object> response;
    try{request=MiniJson.obj(MiniJson.parse(line));response=execute(request);}
    catch(Exception e){response=error(request.get("id"),"invalid_request",e.getMessage());}
    protocol.println(MiniJson.write(response));protocol.flush();
   }
  }
  return 0;
 }
 public static Map<String,Object> execute(Map<String,Object> request){
  Object id=request.get("id");
  try{
   Object value=dispatch(Objects.toString(request.get("method"),""),MiniJson.obj(request.get("params")));
   var response=new LinkedHashMap<String,Object>();response.put("id",id);
   boolean failed=value instanceof Map<?,?> result && result.get("exit_code") instanceof Number code && code.intValue()!=0;
   response.put("ok",!failed);response.put("result",value);if(failed)response.put("error",Map.of("code","cli_failed","message","Legacy CLI returned a nonzero exit code"));return response;
  }catch(Exception e){return error(id,"execution_failed",Objects.toString(e.getMessage(),e.getClass().getSimpleName()));}
 }
 private static Map<String,Object> error(Object id,String code,String message){
  var m=new LinkedHashMap<String,Object>();m.put("id",id);m.put("ok",false);m.put("error",Map.of("code",code,"message",Objects.toString(message,"Unknown error")));return m;
 }
 public static Object dispatch(String method,Map<String,Object> params)throws Exception{
  return switch(method){
   case "capabilities"->capabilities();
   case "inspect"->inspect(params);
   case "project"->ProjectExports.dispatch(params);
   case "validate_settings"->new Settings(MiniJson.obj(params.get("settings"))).map();
   case "report","analyze"->{
    var overrides=new LinkedHashMap<>(MiniJson.obj(params.get("settings")));
    if(method.equals("analyze")&&!overrides.containsKey("charts"))overrides.put("charts",List.of("network"));
    var settings=new Settings(overrides);
    Project project=Cli.loadInputs(inputs(params),settings.text("format"));
    Path output=Path.of(Objects.toString(params.get("output"),"ycscite-report")).toAbsolutePath().normalize();
    NineFigureReport.write(project,output,message->System.err.println(message),settings);
    yield Map.of("output",output.toString(),"manifest",MiniJson.parse(Files.readString(output.resolve("manifest.json"))));
   }
   case "batch"->{
    var jobs=MiniJson.arr(params.get("jobs"));if(jobs.isEmpty())throw new IllegalArgumentException("batch requires jobs");
    var results=new ArrayList<Map<String,Object>>();int index=0;
    for(Object job:jobs){var req=new LinkedHashMap<>(MiniJson.obj(job));req.putIfAbsent("id",index++);results.add(execute(req));}
    yield Map.of("jobs",results,"succeeded",results.stream().filter(r->Boolean.TRUE.equals(r.get("ok"))).count(),"failed",results.stream().filter(r->!Boolean.TRUE.equals(r.get("ok"))).count());
   }
   case "cli"->legacy(params);
   default->throw new IllegalArgumentException("Unknown method: "+method);
  };
 }
 private static List<String> inputs(Map<String,Object> params){
  var items=MiniJson.arr(params.get("inputs"));
  if(items.isEmpty())throw new IllegalArgumentException("inputs must contain directories or files");
  return items.stream().map(v->{if(!(v instanceof String text))throw new IllegalArgumentException("inputs must contain strings");return text;}).toList();
 }
 private static Object inspect(Map<String,Object> params)throws Exception{
  String format=Objects.toString(params.get("format"),"auto");var paths=Cli.expandInputs(inputs(params),format);
  Project p=Cli.loadInputs(paths,format);
  int dated=0,keywords=0,authors=0,addresses=0,references=0;var years=new TreeMap<Integer,Integer>();
  for(var r:p.records){if(r.year>0){dated++;years.merge(r.year,1,Integer::sum);}if(!r.allKeywords().isEmpty())keywords++;if(!r.allAuthors().isEmpty())authors++;if(!r.addresses.isEmpty())addresses++;if(!r.citedRefs.isEmpty())references++;}
  var files=new ArrayList<Map<String,Object>>();for(String path:paths)files.add(Map.of("path",path,"format",format.equals("auto")?Cli.detectFormat(Path.of(path)):format));
  var counts=new LinkedHashMap<String,Object>();counts.put("project",p.name);counts.put("source_fingerprint",DataFingerprint.of(p));counts.put("fingerprint_schema",DataFingerprint.SCHEMA);counts.put("records",p.records.size());counts.put("dated_records",dated);counts.put("undated_records",p.records.size()-dated);
  counts.put("from_year",years.isEmpty()?null:years.firstKey());counts.put("to_year",years.isEmpty()?null:years.lastKey());counts.put("years",years.entrySet().stream().map(e->Map.of("year",e.getKey(),"count",e.getValue())).toList());
  counts.put("records_with_keywords",keywords);counts.put("records_with_authors",authors);counts.put("records_with_institutions",addresses);counts.put("records_with_references",references);counts.put("files",files);
  return counts;
 }
 private static Object legacy(Map<String,Object> params)throws Exception{
  var args=MiniJson.arr(params.get("arguments")).stream().map(Object::toString).toArray(String[]::new);
  if(args.length==0||Set.of("headless","gui","--gui").contains(args[0]))throw new IllegalArgumentException("Specify a non-interactive CLI command");
  var stdout=System.out;var errors=System.err;var buffer=new ByteArrayOutputStream();var errorBuffer=new ByteArrayOutputStream();int code;
  try(var capture=new PrintStream(buffer,true,StandardCharsets.UTF_8);var captureErrors=new PrintStream(errorBuffer,true,StandardCharsets.UTF_8)){
   System.setOut(capture);System.setErr(captureErrors);try{code=Cli.run(args);}finally{System.setOut(stdout);System.setErr(errors);}
  }
  return Map.of("exit_code",code,"stdout",buffer.toString(StandardCharsets.UTF_8),"stderr",errorBuffer.toString(StandardCharsets.UTF_8));
 }
 public static Map<String,Object> capabilities(){
  var formats=new LinkedHashMap<String,String>();
  formats.put("wos","Web of Science text");formats.put("scopus","Scopus CSV");formats.put("cnki","CNKI EndNote");formats.put("cssci","CNKI/CSSCI RefWorks");
  formats.put("pubmed","PubMed MEDLINE");formats.put("pubmed-xml","PubMed XML");formats.put("ris","RIS / ProQuest");formats.put("bibtex","BibTeX");
  formats.put("dimensions","Dimensions CSV");formats.put("derwent","Derwent patent TSV");formats.put("kci","KCI CSV/TSV");formats.put("nsf","NSF awards XML");formats.put("records-tsv","Ycscite records TSV");
  var charts=NineFigureReport.FIGURES.stream().map(f->Map.of("key",f.key(),"title_zh",f.title(),"title_en",I18n.english(f.title()),"file",f.file())).toList();
  var inventory=new LinkedHashMap<String,Object>(Map.of("schema_version",2,"version",Cli.VERSION,"methods",List.of("capabilities","inspect","validate_settings","report","analyze","project","batch","cli"),"formats",formats,
   "networks",List.of("keyword","term","author","institution","cocitation","coupling","aca","jca","country","category"),
   "charts",charts,"default_settings",Settings.DEFAULTS,"legacy_commands",List.of("analyze","convert","batch","visualize","open","search","openalex","video","sql","doctor"),
   "modules",List.of("centrality","pagerank","burst","modularity","spectral","em","llr","lsi","tfidf","mutual_information","kcore","structural_variation","uncertainty","main_path","thematic_map","dual_map","contours","terms","svo","citation_report","pajek","gexf","ucinet","ris","svg","pdf","docx","sql","project_persistence")));
  inventory.put("analysis_modules",List.of(
   Map.of("key","citation","title_zh","节点指标表","title_en","Node metrics"),
   Map.of("key","cluster_summary","title_zh","聚类摘要与质量","title_en","Cluster summary and quality"),
   Map.of("key","year_labels","title_zh","逐年聚类标注","title_en","Year-by-year cluster labels"),
   Map.of("key","cluster_links","title_zh","簇间无向连接表","title_en","Undirected cluster links"),
   Map.of("key","network_growth","title_zh","年度新增节点与边","title_en","Annual network growth"),
   Map.of("key","reference_diversity","title_zh","引文分布分散度（简化）","title_en","Reference diversity (simplified)"),
   Map.of("key","citation_path","title_zh","引文路径（实验性）","title_en","Citation path (experimental)"),
   Map.of("key","sentences","title_zh","聚类摘要句（抽取式）","title_en","Extractive cluster sentences"),
   Map.of("key","terms_tfidf","title_zh","TF-IDF 术语表","title_en","TF-IDF terms"),
   Map.of("key","terms_clumping","title_zh","Clumping 术语表","title_en","Clumping terms"),
   Map.of("key","concept_tree","title_zh","术语概念树（启发式）","title_en","Concept tree (heuristic)"),
   Map.of("key","narrative","title_zh","结构化叙事（模板式）","title_en","Structured narrative (template)")));
  inventory.put("project_export",Map.of("actions",List.of("inspect","export"),"views",java.util.stream.Stream.concat(java.util.stream.Stream.of("saved"),ViewState.MODES.stream().sorted().map(name->name.toLowerCase(java.util.Locale.ROOT))).toList(),"formats",List.of("png","svg","pdf","tsv"),"default_formats",List.of("png"),"default_view","saved","default_width",1600,"default_height",1000,"max_pixels",40_000_000,"recomputes_analysis",false));
  inventory.put("method_notes",List.of("Reference diversity is not full citation-context NLP uncertainty.","Network growth counts new nodes/edges; it is not a validated reproduction of all CiteSpace SVA measures.","Undirected cluster links do not infer dependency direction.","Citation paths and heuristic summaries require independent scientific validation."));
  return inventory;
 }
}
