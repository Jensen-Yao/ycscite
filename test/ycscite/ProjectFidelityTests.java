package ycscite;

import ycscite.analysis.*;
import ycscite.io.*;
import ycscite.model.*;
import java.nio.file.*;
import java.util.*;

/** Data and editable-project invariants, independent of GUI availability. */
public final class ProjectFidelityTests {
 public static void run()throws Exception{
  Path root=Files.createTempDirectory("ycscite-fidelity-");var project=DemoData.generate(24);
  var record=project.records.getFirst();record.title="标题\t带制表符\n第二行\\t文本\r\nEND;";record.abstractText="引号\"与反斜杠\\\\以及\n换行";
  record.authors=new ArrayList<>(List.of("Smith; Jr., Jane","  保留空白  ","名字\tA","名字\nB"));record.authorsFull=new ArrayList<>(List.of("完整姓名;分号"));
  record.keywords.add("a;b");record.addresses.add("机构\n学院;实验室");record.citedRefs.add("AUTHOR, 2024, Journal; Series\tA\nB");record.categories.add("Social Sciences; Interdisciplinary");record.categories.add("中文学科");
  String identity=DataFingerprint.of(project);Path tsv=root.resolve("roundtrip.tsv");RecordIo.writeTsv(project,tsv);var parsed=RecordIo.readTsv(tsv);
  TestAll.check("fidelity.tsv.all_fields",identity.equals(DataFingerprint.of(parsed)),"record TSV must preserve all parsed source fields");
  TestAll.check("fidelity.tsv.lists",parsed.records.getFirst().authors.equals(record.authors)&&parsed.records.getFirst().citedRefs.equals(record.citedRefs),"semicolons and control characters are content, not extra values");
  TestAll.check("fidelity.tsv.years",parsed.minYear==project.minYear&&parsed.maxYear==project.maxYear,"record TSV must restore the range");
  TestAll.check("fidelity.tsv.auto",Cli.detectFormat(tsv).equals("records-tsv")&&Cli.loadInputs(List.of(tsv.toString()),"auto").records.size()==project.records.size(),"versioned TSV auto detection");
  var legacy=RecordIo.readTsv(Path.of("examples/demo-records.tsv"));TestAll.check("fidelity.tsv.legacy",legacy.records.size()==72&&legacy.records.getFirst().allKeywords().size()>1,"legacy 17-column TSV remains readable");
  var lines=Files.readAllLines(tsv);var cells=lines.get(1).split("\t",-1);cells[1]="[4]";lines.set(1,String.join("\t",cells));Path invalid=root.resolve("invalid.tsv");Files.write(invalid,lines);
  boolean rejected=false;try{RecordIo.readTsv(invalid);}catch(java.io.IOException error){rejected=error.getMessage().contains("row 2");}TestAll.check("fidelity.tsv.invalid_array",rejected,"invalid multivalue fields must identify the bad row");

  var weighted=new Network("weighted path");var wa=weighted.node("a","a","keyword");var wb=weighted.node("b","b","keyword");var wc=weighted.node("c","c","keyword");weighted.linkWeighted(wa,wb,9);weighted.linkWeighted(wb,wc,1);PageRank.compute(weighted);
  double center=.9/1.85,heavy=.05+.85*center*.9,light=.05+.85*center*.1;
  TestAll.check("fidelity.pagerank.weighted_closed_form",Math.abs(wb.pageRank-center)<1e-9&&Math.abs(wa.pageRank-heavy)<1e-9&&Math.abs(wc.pageRank-light)<1e-9,"weighted transitions must match the three-node stationary solution");
  double[] originalRanks={wa.pageRank,wb.pageRank,wc.pageRank};for(var edge:weighted.edges)edge.weight*=1000;PageRank.compute(weighted);
  TestAll.check("fidelity.pagerank.weight_scale",Math.abs(wa.pageRank-originalRanks[0])<1e-12&&Math.abs(wb.pageRank-originalRanks[1])<1e-12&&Math.abs(wc.pageRank-originalRanks[2])<1e-12,"a global weight unit change must not affect ranks");
  weighted.node("isolated","isolated","keyword");for(var edge:weighted.edges)edge.weight=2_000_000_000;PageRank.compute(weighted);double sum=weighted.nodeCollection().stream().mapToDouble(n->n.pageRank).sum();
  TestAll.check("fidelity.pagerank.large_weights",weighted.nodeCollection().stream().allMatch(n->Double.isFinite(n.pageRank)&&n.pageRank>=0)&&Math.abs(sum-1)<1e-12,"normalized similarity weights must not produce NaN or infinity");

  var settings=new Settings(Map.ofEntries(Map.entry("selection","gindex"),Map.entry("g_index_k",25),Map.entry("top_percent",37),Map.entry("similarity","equivalence"),Map.entry("max_links",4),Map.entry("keep_isolated",true),Map.entry("noun_phrase_max",90),Map.entry("exclude_words",List.of("exclude-me")),Map.entry("labeling","mi"),Map.entry("label_source","tka"),Map.entry("burst_transition",.71)));
  var options=settings.pipeline("keyword");var result=Pipeline.run(project,options);
  var node=result.network.nodeCollection().iterator().next();node.bookmarked=true;node.annotation="人工批注\n第二行";node.userLabel="自定义名称";node.degreeCentrality=.123456789;node.eigenCentrality=.987654321;
  var extra=new GraphNode("EXCLUDED","Excluded burst term","keyword");extra.weight=9;extra.addYear(2001);extra.addYear(2023);extra.burstIntervals.add(new int[]{2022,2023,1});extra.intervalStrengths.add(2.5);result.burstRows.add(extra);result.paramLines.add("Selection source: report settings");
  var graphCopy=result.network.deepCopy();var copiedNode=graphCopy.nodes.get(node.id);TestAll.check("fidelity.undo.node_fields",copiedNode.bookmarked&&copiedNode.annotation.equals(node.annotation)&&copiedNode.userLabel.equals(node.userLabel)&&copiedNode.degreeCentrality==node.degreeCentrality&&copiedNode.eigenCentrality==node.eigenCentrality,"undo graph copies must retain manual edits and extra metrics");
  var undo=ProjectStore.copyAnalysis(result);undo.network.removeNode(undo.network.nodes.get(node.id));undo.burstRows.getLast().annotation="copy-only";TestAll.check("fidelity.undo.analysis_state",result.network.nodes.containsKey(node.id)&&!result.burstRows.getLast().annotation.equals("copy-only")&&undo.modularity==result.modularity&&undo.clusterLabels.keySet().equals(result.clusterLabels.keySet())&&undo.clusterStats.keySet().equals(result.clusterStats.keySet()),"an independent undo state must preserve and isolate graph, labels and quality metrics");
  Path saved=root.resolve("editable.csl-proj");ProjectStore.save(saved,project,result,options);var loaded=ProjectStore.load(saved);
  TestAll.check("fidelity.project.records",DataFingerprint.of(loaded.project).equals(identity),"project categories and all source records must survive");
  TestAll.check("fidelity.project.options",PipelineOptionsCodec.write(options).equals(PipelineOptionsCodec.write(loaded.options)),"every pipeline setting must survive a project save");
  var optionFields=new TreeSet<String>();for(var field:Pipeline.Options.class.getFields())optionFields.add(field.getName());TestAll.check("fidelity.project.option_coverage",optionFields.equals(PipelineOptionsCodec.write(options).keySet()),optionFields.toString());
  var burstFields=new TreeSet<String>();for(var field:Burst.Params.class.getFields())burstFields.add(field.getName());TestAll.check("fidelity.project.burst_option_coverage",burstFields.equals(MiniJson.obj(PipelineOptionsCodec.write(options).get("burstParams")).keySet()),burstFields.toString());
  var restored=loaded.result.network.nodes.get(node.id);TestAll.check("fidelity.project.edits",restored.bookmarked&&restored.annotation.equals(node.annotation)&&restored.userLabel.equals(node.userLabel),"manual node edits must not disappear");
  TestAll.check("fidelity.project.centralities",restored.degreeCentrality==node.degreeCentrality&&restored.eigenCentrality==node.eigenCentrality,"all computed metrics must survive");
  TestAll.check("fidelity.project.burst_rows",loaded.result.burstRows.stream().anyMatch(n->n.id.equals(extra.id)&&n.countsByYear.equals(extra.countsByYear)&&n.intervalStrengths.equals(extra.intervalStrengths)),"burst rows outside the displayed network must survive");
  TestAll.check("fidelity.project.parameter_lines",loaded.result.paramLines.equals(result.paramLines),"analysis metadata must survive");
  var copy=PipelineOptionsCodec.copy(options);copy.excludeWords.add("copy-only");copy.burstParams.transK=99;TestAll.check("fidelity.project.options_deep_copy",!options.excludeWords.contains("copy-only")&&options.burstParams.transK!=99,"editor changes must not mutate a retained options object");
  var late=new Project();var only=new ycscite.model.Record();only.year=2020;only.title="Kept";only.doi="10.example/one";late.add(only);var duplicate=new ycscite.model.Record();duplicate.year=1990;duplicate.doi=only.doi;late.add(duplicate);late.dedup();TestAll.check("fidelity.dedup.year_range",late.minYear==2020&&late.maxYear==2020,"deduplication must not retain a removed outlier year");
  var selection=NineFigureReport.prepare(project,new Settings(Map.of("from_year",project.maxYear,"to_year",project.maxYear)));TestAll.check("fidelity.selection.source_unchanged",DataFingerprint.of(project).equals(identity)&&selection.project().records.stream().allMatch(r->r.year==project.maxYear),"filtering must not rewrite the original corpus");
  var network=new Network("test");var timeNode=network.node("n","n","keyword");timeNode.addYear(2000);timeNode.addYear(2024);network.minYear=2000;network.maxYear=2024;var one=new Pipeline.Result();one.network=network;one.clusterLabels=Map.of();one.clusterStats=Map.of();
  Path years=root.resolve("year-range.csl-proj");ProjectStore.save(years,late,one,new Pipeline.Options());var json=MiniJson.obj(MiniJson.parse(Files.readString(years)));var analysis=MiniJson.obj(json.get("analysis"));analysis.remove("netMinYear");analysis.remove("netMaxYear");Files.writeString(years,MiniJson.write(json));var range=ProjectStore.load(years).result.network;TestAll.check("fidelity.project.legacy_year_range",range.minYear==2000&&range.maxYear==2024,"legacy network bounds use every occurrence year");
 }
}
