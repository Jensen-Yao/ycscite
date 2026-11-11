package ycscite;
import ycscite.io.*;
import ycscite.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Cross-format, protocol, configuration and download invariants for the product entry points. */
public final class ProductTests {
 public static void run()throws Exception{
  ProjectFidelityTests.run();
  VisualExportTests.run();
  PresentationTests.run();
  Path root=Files.createTempDirectory("ycscite-product-");
  Path mixed=Files.createDirectory(root.resolve("mixed"));
  Files.writeString(mixed.resolve("note.txt"),"README, not bibliographic data");
  Files.writeString(mixed.resolve("a.bib"),"@article{a, title={中文文化遗产}, author={Li, Ming}, year={2020}, keywords={文化遗产;旅游}, journal={Journal}}\n");
  Files.writeString(mixed.resolve("b.ris"),"TY  - JOUR\nTI  - Heritage mapping\nAU  - Smith, John\nPY  - 2021\nKW  - heritage\nER  - \n");
  Path award=root.resolve("award.xml");
  Files.writeString(award,"<root><Award><AwardTitle>Award sample</AwardTitle><AwardEffectiveDate>10/01/2021</AwardEffectiveDate><AwardID>123</AwardID></Award></root>");
  TestAll.check("product.nsf.xml.auto",Cli.detectFormat(award).equals("nsf")&&Cli.loadInputs(List.of(award.toString()),"auto").records.getFirst().year==2021,"award tag aliases and date formats");
  Path gbk=root.resolve("gbk.txt");
  Files.write(gbk,"RT Journal Article\nSR 1\nT1 文化遗产保护\nYR 2021\nK1 文化遗产;保护\n\n".getBytes(java.nio.charset.Charset.forName("GB18030")));
  TestAll.check("product.encoding.gbk",Cli.loadReportInputs(List.of(gbk.toString())).records.getFirst().title.contains("文化遗产"),"Legacy CNKI Chinese encoding");
  var p=Cli.loadReportInputs(List.of(mixed.toString()));
  TestAll.check("product.mixed.formats",p.records.size()==2&&p.minYear==2020&&p.maxYear==2021,p.records.size()+" records");
  RecordIo.writeTsv(p,root.resolve("records.tsv"));
  TestAll.check("product.records.auto",Cli.detectFormat(root.resolve("records.tsv")).equals("records-tsv")&&Cli.loadInputs(List.of(root.resolve("records.tsv").toString()),"auto").records.size()==2,"TSV round trip");
  String identity=DataFingerprint.of(p);var same=Cli.loadReportInputs(List.of(mixed.toString()));
  TestAll.check("product.fingerprint.repeatable",identity.equals(DataFingerprint.of(same)),"same parsed records must keep their identity");
  same.records.getFirst().authorsFull.add("New full author name");
  TestAll.check("product.fingerprint.full_author",!identity.equals(DataFingerprint.of(same)),"full author names affect analysis and must affect identity");
  same=Cli.loadReportInputs(List.of(mixed.toString()));same.records.getFirst().keywords.add("Changed keyword");
  TestAll.check("product.fingerprint.keyword",!identity.equals(DataFingerprint.of(same)),"source edits cannot reuse a fresh-result badge");
  Path afOnly=root.resolve("af-only.txt");Files.writeString(afOnly,"PT J\nAF Doe, Jane\nTI Full names only\nPY 2020\nER\nEF\n");
  var afInspection=Headless.execute(Map.of("method","inspect","params",Map.of("inputs",List.of(afOnly.toString()))));
  TestAll.check("product.inspect.full_authors",MiniJson.num(MiniJson.obj(afInspection.get("result")).get("records_with_authors"))==1,"import coverage must use the same full-name fallback as the author analysis");
  int rejected=0;
  for(var bad:List.of(Map.<String,Object>of("slice_years",0),Map.<String,Object>of("slice_years",1.5),Map.<String,Object>of("from_year",2025,"to_year",2020),Map.<String,Object>of("unknown",1),Map.<String,Object>of("exports",List.of("exe")))){
   try{new Settings(bad);}catch(IllegalArgumentException e){rejected++;}
  }
  TestAll.check("product.settings.reject",rejected==5,"invalid values accepted");
  var inspect=Headless.execute(Map.of("id",1,"method","inspect","params",Map.of("inputs",List.of(mixed.toString()))));
  TestAll.check("product.inspect",Boolean.TRUE.equals(inspect.get("ok"))&&MiniJson.num(MiniJson.obj(inspect.get("result")).get("records"))==2,inspect.toString());
  var caps=Headless.capabilities();
  TestAll.check("product.capabilities",MiniJson.arr(caps.get("charts")).size()==18&&MiniJson.obj(caps.get("formats")).size()>=13,"capability inventory");
  TestAll.check("product.json.integer_ids",MiniJson.write(MiniJson.parse("9007199254740993")).equals("9007199254740993"),"request ID precision");
  var eq=new Network("equivalence");var eqA=eq.node("a","a","keyword");var eqB=eq.node("b","b","keyword");var eqC=eq.node("c","c","keyword");
  eq.linkWeighted(eqA,eqB,3);eq.linkWeighted(eqA,eqC,1);eq.linkWeighted(eqB,eqC,1);eq.transformSimilarity("equivalence");
  TestAll.check("product.equivalence",eq.edges.getFirst().weight==563&&eq.edges.get(1).weight==125,"w^2 / degree products, scaled1000");
  var small=new Network("test");var a=small.node("a","A","keyword");var b=small.node("b","B","keyword");var c=small.node("c","C","keyword");var d=small.node("d","D","keyword");
  a.cluster=b.cluster=1;c.cluster=d.cluster=2;small.linkWeighted(a,b,3);small.linkWeighted(c,d,5);small.linkWeighted(b,c,2);
  var result=new ycscite.analysis.Pipeline.Result();result.network=small;
  var metrics=ycscite.analysis.ThematicMap.metrics(result);
  TestAll.check("product.thematic.metrics",metrics.getFirst().externalStrength()==2&&metrics.getFirst().internalDensity()==150&&metrics.getLast().internalDensity()==250,"external/internal metrics");
  var demo=DemoData.generate(35);
  var record=new ycscite.model.Record();record.year=2021;record.title="文化遗产保护";record.keywords.add("文化遗产");record.keywords.add("heritage");demo.add(record);
  var low=ycscite.analysis.Pipeline.run(demo,new Settings(Map.of("g_index_k",1)).pipeline("keyword"));
  var high=ycscite.analysis.Pipeline.run(demo,new Settings(Map.of("g_index_k",25)).pipeline("keyword"));
  TestAll.check("product.params.change.network",low.network.nodes.size()<high.network.nodes.size(),low.network.nodes.size()+" vs "+high.network.nodes.size());
  var excluded=ycscite.analysis.Pipeline.run(demo,new Settings(Map.of("per_slice",0,"exclude_words",List.of("HERITAGE"))).pipeline("keyword"));
  TestAll.check("product.params.exclude.global",excluded.network.nodeCollection().stream().noneMatch(n->n.label.equalsIgnoreCase("heritage"))&&excluded.network.edges.stream().allMatch(e->excluded.network.nodes.containsKey(e.a.id)&&excluded.network.nodes.containsKey(e.b.id)),"exclusion must remove links and match case");
  var settings=new Settings(Map.of("language","en","logo",false,"image_scale",1,
    "charts",List.of("network","circular","heatmap","tsne","umap","circle_pack","thematic","contour","dual_map"),
    "exports",List.of("png","pdf","svg","docx","gexf","pajek","ris","records-tsv")));
  Path out=root.resolve("outputs");NineFigureReport.write(demo,out,message->{},settings);
  var manifest=MiniJson.obj(MiniJson.parse(Files.readString(out.resolve("manifest.json"))));
  TestAll.check("product.report.provenance",DataFingerprint.of(demo).equals(manifest.get("source_fingerprint"))&&Cli.VERSION.equals(manifest.get("engine_version")),"report provenance must identify the original corpus and engine");
  TestAll.check("product.more.charts",MiniJson.arr(manifest.get("figures")).size()==9&&Files.exists(out.resolve("18-dual-map.png")),"extra charts missing");
  long activities=Files.readAllLines(out.resolve("data/cluster-activity.tsv")).stream().skip(1).map(line->line.split("\\t")).mapToLong(row->Long.parseLong(row[4])).sum();
  long yearly=Files.readAllLines(out.resolve("data/keywords-years.tsv")).stream().skip(1).map(line->line.split("\\t")).mapToLong(row->Long.parseLong(row[2])).sum();
  TestAll.check("product.heatmap.raw_counts",activities==yearly,"heatmap counts must equal source node-year occurrences");
  var absent=new Project();var missingReference=new ycscite.model.Record();missingReference.year=2022;missingReference.source="Observed journal";missingReference.keywords.add("This is not a cited journal");absent.add(missingReference);
  Path absentOut=root.resolve("no-references");NineFigureReport.write(absent,absentOut,message->{},new Settings(Map.of("charts",List.of("dual_map","contour"),"image_scale",1)));
  TestAll.check("product.journal.no_invented_flows",Files.readAllLines(absentOut.resolve("data/journal-flows.tsv")).size()==1,"keywords must not become cited journals");
  TestAll.check("product.empty.extension_images",Files.exists(absentOut.resolve("17-contour.png"))&&Files.exists(absentOut.resolve("18-dual-map.png")),"empty extensions must render a readable no-data result");
  var single=Headless.execute(Map.of("method","analyze","params",Map.of("inputs",List.of(mixed.toString()),"output",root.resolve("single-view").toString(),"settings",Map.of("image_scale",1))));
  TestAll.check("product.analyze.single_default",Boolean.TRUE.equals(single.get("ok"))&&MiniJson.arr(MiniJson.obj(MiniJson.obj(single.get("result")).get("manifest")).get("figures")).size()==1,"analyze must not generate a four-view bundle");
  var blobs=new Network("weighted clusters");var blobNodes=new ArrayList<GraphNode>();for(int i=0;i<12;i++)blobNodes.add(blobs.node("n"+i,"n"+i,"keyword"));for(int i=0;i<12;i++)for(int j=i+1;j<12;j++)if(i/6==j/6)blobs.linkWeighted(blobNodes.get(i),blobNodes.get(j),10);blobs.linkWeighted(blobNodes.get(5),blobNodes.get(6),1);
  double[][] embedding=ycscite.analysis.Tsne.embed(blobs,700,3,42);double within=0,between=0;int wn=0,bn=0;for(int i=0;i<12;i++)for(int j=i+1;j<12;j++){double distance=Math.hypot(embedding[i][0]-embedding[j][0],embedding[i][1]-embedding[j][1]);if(i/6==j/6){within+=distance;wn++;}else{between+=distance;bn++;}}
  TestAll.check("product.tsne.neighborhood",within/wn<between/bn,"weighted within-cluster distances should remain closer: "+within/wn+" / "+between/bn);
  var clean=ImageIO.read(out.resolve("no-logo/10-network.png").toFile());var branded=ImageIO.read(out.resolve("with-logo/10-network.png").toFile());
  int footer=Math.max(32,clean.getWidth()/55),height=clean.getHeight()-footer;
  boolean identical=Arrays.equals(clean.getRGB(0,0,clean.getWidth(),height,null,0,clean.getWidth()),branded.getRGB(0,0,branded.getWidth(),height,null,0,branded.getWidth()));
  TestAll.check("product.logo.body",identical,"logo must not cover data");
  TestAll.check("product.logo.default",Arrays.equals(Files.readAllBytes(out.resolve("10-network.png")),Files.readAllBytes(out.resolve("no-logo/10-network.png"))),"no-logo default ignored");
  TestAll.check("product.extra.exports",Files.exists(out.resolve("report.pdf"))&&Files.exists(out.resolve("report.docx"))&&Files.exists(out.resolve("network.svg"))&&Files.exists(out.resolve("references.ris")),"export outputs");
  TestAll.check("product.pdf.pages",new String(Files.readAllBytes(out.resolve("report.pdf")),java.nio.charset.StandardCharsets.ISO_8859_1).contains("/Count 9"),"PDF page count");
  TestAll.check("product.params.saved",MiniJson.obj(MiniJson.parse(Files.readString(out.resolve("settings.json")))).get("language").equals("en"),"saved settings");
  TestAll.check("product.language.titles",MiniJson.arr(manifest.get("figures")).stream().map(MiniJson::obj).anyMatch(f->"Network overview".equals(f.get("title"))),"English captions");
  TestAll.check("product.language.data",Files.readString(out.resolve("data/keywords-nodes.tsv")).contains("文化遗产"),"bibliographic terms must stay intact");
  var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Djava.awt.headless=true","-Dstdout.encoding=UTF-8","-cp",System.getProperty("java.class.path"),"ycscite.Cli","headless").start();
  try(var input=process.getOutputStream()){
   input.write(("{\"id\":1,\"method\":\"capabilities\"}\n{bad\n{\"id\":2,\"method\":\"does-not-exist\"}\n{\"id\":3,\"method\":\"capabilities\"}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  var output=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return process.getInputStream().readAllBytes();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
  boolean ended=process.waitFor(30,TimeUnit.SECONDS);if(!ended)process.destroyForcibly();
  var lines=new String(output.get(5,TimeUnit.SECONDS),java.nio.charset.StandardCharsets.UTF_8).lines().toList();
  TestAll.check("product.headless.stream",ended&&process.exitValue()==0&&lines.size()==4&&lines.stream().allMatch(l->MiniJson.parse(l)instanceof Map),lines.toString());
  TestAll.check("product.headless.recovery",Boolean.FALSE.equals(MiniJson.obj(MiniJson.parse(lines.get(1))).get("ok"))&&MiniJson.num(MiniJson.obj(MiniJson.parse(lines.getLast())).get("id"))==3,"NDJSON error recovery");
  clean.flush();branded.flush();
 }
}
