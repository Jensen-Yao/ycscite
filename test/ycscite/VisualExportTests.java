package ycscite;

import ycscite.io.*;
import ycscite.model.*;
import ycscite.ui.GraphCanvas;
import ycscite.ui.Strings;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import java.util.zip.InflaterInputStream;

/** Visual export invariants: data/alpha, geometry, locale and saved logo preferences. */
public final class VisualExportTests {
 private record Bitmap(int width,int height,byte[] rgb){}
 private static int integer(String text,String name){var m=Pattern.compile("/"+name+" (\\d+)").matcher(text);if(!m.find())throw new IllegalArgumentException(name);return Integer.parseInt(m.group(1));}
 private static List<Bitmap> bitmaps(Path file)throws Exception{
  byte[] bytes=Files.readAllBytes(file);String text=new String(bytes,StandardCharsets.ISO_8859_1);String[] xref=text.substring(text.lastIndexOf("\nxref\n")+1).split("\n");int count=Integer.parseInt(xref[1].split(" ")[1]);var images=new ArrayList<Bitmap>();
  for(int i=1;i<count;i++){int offset=Integer.parseInt(xref[i+2].substring(0,10));int end=text.indexOf("endobj",offset);String object=text.substring(offset,end);if(!object.contains("/Subtype /Image"))continue;int start=text.indexOf("stream\n",offset)+7;String dictionary=text.substring(offset,start);int length=integer(dictionary,"Length");try(var inflate=new InflaterInputStream(new ByteArrayInputStream(bytes,start,length))){images.add(new Bitmap(integer(dictionary,"Width"),integer(dictionary,"Height"),inflate.readAllBytes()));}}
  return images;
 }
 private static byte[] rgb(BufferedImage image){byte[] bytes=new byte[image.getWidth()*image.getHeight()*3];int i=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int p=image.getRGB(x,y);bytes[i++]=(byte)(p>>>16);bytes[i++]=(byte)(p>>>8);bytes[i++]=(byte)p;}return bytes;}
 private static BufferedImage paint(GraphCanvas canvas){var image=new BufferedImage(canvas.getWidth(),canvas.getHeight(),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();canvas.paint(g);g.dispose();return image;}
 public static void run()throws Exception{
  Path root=Files.createTempDirectory("ycscite-visual-exports-");var alpha=new BufferedImage(61,29,BufferedImage.TYPE_INT_ARGB);int[] pixels=new int[61*29];for(int i=0;i<pixels.length;i++)pixels[i]=((i%256)<<24)|((i*17491)&0xffffff);alpha.setRGB(0,0,61,29,pixels,0,61);
  Path png=root.resolve("alpha.png");Branding.writePng(png,alpha,false);var plain=ImageIO.read(root.resolve("no-logo/alpha.png").toFile());var branded=ImageIO.read(root.resolve("with-logo/alpha.png").toFile());
  TestAll.check("visual.png.source_rgba",Arrays.equals(pixels,plain.getRGB(0,0,61,29,null,0,61))&&Arrays.equals(pixels,branded.getRGB(0,0,61,29,null,0,61)),"transparency and hidden RGB values must remain exact");
  TestAll.check("visual.png.equal_geometry",plain.getWidth()==branded.getWidth()&&plain.getHeight()==branded.getHeight()&&plain.getHeight()==61,"logo choices share dimensions");
  TestAll.check("visual.png.footer_only",!Arrays.equals(plain.getRGB(0,29,61,32,null,0,61),branded.getRGB(0,29,61,32,null,0,61)),"only the separate footer changes");
  TestAll.check("visual.png.primary_choice",Files.mismatch(png,root.resolve("no-logo/alpha.png"))==-1,"primary download follows the selected logo preference");

  Path svg=root.resolve("root.svg");String body="<rect id=\"data\" width=\"300\" height=\"200\" fill=\"red\"/>";Files.writeString(svg,"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"300\" height=\"200\" viewBox=\"0 0 300 200\">"+body+"</svg>");Branding.svgVariants(svg,false,300,200);String clean=Files.readString(root.resolve("no-logo/root.svg"));
  TestAll.check("visual.svg.root_geometry",clean.contains("height=\"260\"")&&clean.contains("viewBox=\"0 0 300 260\""),"SVG footer expands only the root viewport");
  TestAll.check("visual.svg.body_unchanged",clean.contains(body)&&Files.readString(root.resolve("with-logo/root.svg")).contains(body),"child dimensions must not be rewritten");
  Branding.svgVariants(svg,true,300,200);Branding.svgVariants(svg,false,300,200);TestAll.check("visual.svg.repeat_stable",Files.readString(svg).equals(clean),"switching a branded SVG must not add repeated footers");

  var graph=new Network("keyword");var first=graph.node("A","原始名称","keyword");first.weight=3;first.userLabel="中文自定义 & <标签>";first.addYear(2020);var second=graph.node("B","另一节点","keyword");second.weight=2;second.addYear(2021);graph.linkWeighted(first,second,2);graph.minYear=2020;graph.maxYear=2021;
  var canvas=new GraphCanvas();canvas.show(graph,GraphCanvas.Mode.MERGED,"示例");Locale previous=Locale.getDefault();Path localeSvg=root.resolve("locale.svg");try{Locale.setDefault(Locale.GERMANY);canvas.writeSvg(localeSvg,2400,1500);}finally{Locale.setDefault(previous);}
  String vectors=Files.readString(localeSvg);TestAll.check("visual.svg.custom_label",vectors.contains("中文自定义 &amp; &lt;标签>"),"vector output must retain manual labels and escape markup");TestAll.check("visual.svg.locale_invariant",!Pattern.compile("(?:cx|cy|x1|x2|y1|y2|stroke-width)=\"[0-9]+,[0-9]+").matcher(vectors).find(),"SVG coordinates use decimal points in every locale");

  var chart=new BufferedImage(143,79,BufferedImage.TYPE_INT_RGB);for(int y=0;y<79;y++)for(int x=0;x<143;x++)chart.setRGB(x,y,((x*5)&255)<<16|((y*7)&255)<<8|((x+y)*3&255));Path pdf=root.resolve("report.pdf");PdfExporter.variants(pdf,chart,graph,"中文研究图与指标",false);
  var a=bitmaps(root.resolve("no-logo/report.pdf"));var b=bitmaps(root.resolve("with-logo/report.pdf"));int size=143*79*3;
  TestAll.check("visual.pdf.page_count",a.size()==2&&b.size()==2,"graph and CJK summary are retained in both PDF choices");
  TestAll.check("visual.pdf.lossless_graph",Arrays.equals(rgb(chart),Arrays.copyOf(a.getFirst().rgb,size))&&Arrays.equals(rgb(chart),Arrays.copyOf(b.getFirst().rgb,size)),"PDF stores exact image RGB, without JPEG loss");
  boolean sameBodies=true;for(int i=0;i<a.size();i++){int h=a.get(i).height-Math.max(32,a.get(i).width/55);sameBodies&=a.get(i).width==b.get(i).width&&a.get(i).height==b.get(i).height&&Arrays.equals(Arrays.copyOf(a.get(i).rgb,a.get(i).width*h*3),Arrays.copyOf(b.get(i).rgb,b.get(i).width*h*3));}TestAll.check("visual.pdf.footer_only",sameBodies,"PDF logo choice changes no chart or summary pixels");
  String pdfText=new String(Files.readAllBytes(pdf),StandardCharsets.ISO_8859_1);var box=Pattern.compile("/MediaBox \\[0 0 ([0-9.]+) ([0-9.]+)\\]").matcher(pdfText);box.find();double w=Double.parseDouble(box.group(1)),h=Double.parseDouble(box.group(2));TestAll.check("visual.pdf.aspect_ratio",Math.abs(w*a.getFirst().height-h*a.getFirst().width)<.15,"PDF pages retain the image aspect ratio");
  TestAll.check("visual.pdf.primary_choice",Files.mismatch(pdf,root.resolve("no-logo/report.pdf"))==-1,"primary PDF follows the requested preference");
  var zh=PdfExporter.summary(graph,"中文研究图与指标");var questions=PdfExporter.summary(graph,"????????");TestAll.check("visual.pdf.cjk_rendering",!Arrays.equals(rgb(zh),rgb(questions)),"summary text is rasterized as original CJK, not transliterated to question marks");

  var live=new GraphCanvas();final BufferedImage[] before={null},after={null};SwingUtilities.invokeAndWait(()->{live.setSize(900,600);live.show(graph,GraphCanvas.Mode.MERGED,"viewport");});SwingUtilities.invokeAndWait(()->{live.focus("A");before[0]=paint(live);});SwingUtilities.invokeAndWait(()->{var image=live.renderImage(1600,1000);image.flush();});SwingUtilities.invokeAndWait(()->after[0]=paint(live));TestAll.check("visual.canvas.export_keeps_view",live.getWidth()==900&&live.getHeight()==600&&Arrays.equals(rgb(before[0]),rgb(after[0])),"an export must not reset the user's focused viewport, even after queued events");

  Path source=root.resolve("中文资料.tsv");var project=new Project();project.name="中文资料";var record=new ycscite.model.Record();record.year=2020;record.title="文化遗产研究";record.keywords.add("文化遗产");record.keywords.add("知识图谱");project.add(record);RecordIo.writeTsv(project,source);
  Path output=Files.createDirectories(root.resolve("cli"));var sentinel=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);ImageIO.write(sentinel,"png",output.resolve("unrelated.png").toFile());byte[] old=Files.readAllBytes(output.resolve("unrelated.png"));
  int rc=Cli.run(new String[]{"analyze",source.toString(),"--out",output.toString(),"--trend","--svg","--pdf","--no-logo","--lang","en"});TestAll.check("visual.cli.legacy_flags",rc==0&&Files.mismatch(output.resolve("publication-trend.png"),output.resolve("no-logo/publication-trend.png"))==-1&&Files.mismatch(output.resolve("report.pdf"),output.resolve("no-logo/report.pdf"))==-1,"legacy outputs honor --no-logo across PNG/SVG/PDF");
  TestAll.check("visual.cli.unrelated_untouched",Arrays.equals(old,Files.readAllBytes(output.resolve("unrelated.png")))&&!Files.exists(output.resolve("with-logo/unrelated.png")),"never brand unrelated files in a reused output directory");
  Path insufficient=Files.createDirectories(root.resolve("insufficient"));ImageIO.write(sentinel,"png",insufficient.resolve("contour-map.png").toFile());int noData=Cli.run(new String[]{"analyze",source.toString(),"--contour","--no-logo","--out",insufficient.toString()});var emptyView=ImageIO.read(insufficient.resolve("contour-map.png").toFile());TestAll.check("visual.cli.no_stale_empty_view",noData==0&&emptyView.getWidth()==1400&&emptyView.getHeight()==832&&!Files.exists(insufficient.resolve("network.png")),"insufficient data must produce a fresh explanatory view, not rebrand a stale file");
  Path svgOnly=root.resolve("svg-only");int only=Cli.run(new String[]{"analyze",source.toString(),"--out",svgOnly.toString(),"--svg","--no-logo"});TestAll.check("visual.cli.svg_only",only==0&&Files.exists(svgOnly.resolve("network.svg"))&&!Files.exists(svgOnly.resolve("network.png")),"an explicitly requested view must not imply an unrelated image bundle");
  var result=ycscite.analysis.Pipeline.run(project,new Settings().pipeline("keyword"));Path saved=root.resolve("no-logo.csl-proj");ProjectStore.save(saved,project,result,new Settings().pipeline("keyword"),false);Path reopened=root.resolve("reopened");int opened=Cli.run(new String[]{"open",saved.toString(),"--out",reopened.toString(),"--svg","--pdf"});TestAll.check("visual.project.logo_preference",opened==0&&Files.mismatch(reopened.resolve("network.svg"),reopened.resolve("no-logo/network.svg"))==-1&&Files.mismatch(reopened.resolve("report.pdf"),reopened.resolve("no-logo/report.pdf"))==-1&&!ProjectStore.load(saved).exportLogo,"project exports restore the saved logo preference");
  int invalid=Cli.run(new String[]{"analyze",source.toString(),"--logo","--no-logo","--trend","--out",root.resolve("invalid").toString()});TestAll.check("visual.cli.conflict_rejected",invalid==2&&!Files.exists(root.resolve("invalid")),"conflicting logo flags fail without producing outputs");
  Strings.Lang oldLanguage=Strings.getLang();try(var scope=I18n.language("zh")){Cli.run(new String[]{"version","--lang","en"});TestAll.check("visual.cli.language_isolated",!I18n.isEnglish()&&Strings.getLang()==oldLanguage,"legacy request language does not leak into later requests");}
 }
}
