package ycscite.io;

import ycscite.I18n;
import ycscite.model.Network;
import ycscite.model.GraphNode;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

/** Dependency-free PDF image pages: aspect-preserving geometry and lossless RGB streams. */
public final class PdfExporter {
 private PdfExporter(){}
 private record Raster(int width,int height,byte[] compressed){}
 private static Raster raster(BufferedImage image)throws IOException{
  var buffer=new ByteArrayOutputStream();int width=image.getWidth();byte[] row=new byte[width*3];int[] pixels=new int[width];
  try(var deflater=new DeflaterOutputStream(buffer)){
   for(int y=0;y<image.getHeight();y++){
    image.getRGB(0,y,width,1,pixels,0,width);for(int x=0;x<width;x++){int pixel=pixels[x],alpha=pixel>>>24;for(int channel=0;channel<3;channel++){int component=(pixel>>>(16-channel*8))&255;row[x*3+channel]=(byte)((component*alpha+255*(255-alpha)+127)/255);}}
    deflater.write(row);
   }
  }
  return new Raster(width,image.getHeight(),buffer.toByteArray());
 }
 private static byte[] ascii(String value){return value.getBytes(StandardCharsets.US_ASCII);}
 private static byte[] stream(byte[] bytes,String dictionary)throws IOException{
  var output=new ByteArrayOutputStream();output.write(ascii("<< "+dictionary+" /Length "+bytes.length+" >>\nstream\n"));output.write(bytes);output.write(ascii("\nendstream"));return output.toByteArray();
 }
 private static void writeRasterPages(Path out,List<Raster> pages)throws IOException{
  if(pages.isEmpty())throw new IllegalArgumentException("No figures for PDF");var objects=new ArrayList<byte[]>();objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));var kids=new StringBuilder();for(int i=0;i<pages.size();i++)kids.append(3+3*i).append(" 0 R ");objects.add(ascii("<< /Type /Pages /Kids ["+kids+"] /Count "+pages.size()+" >>"));
  for(int i=0;i<pages.size();i++){
   Raster page=pages.get(i);double width=792,height=width*page.height/page.width;int object=3+3*i;String geometry=String.format(Locale.ROOT,"%.3f %.3f",width,height);
   objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "+geometry+"] /Resources << /XObject << /Im "+(object+2)+" 0 R >> >> /Contents "+(object+1)+" 0 R >>"));
   objects.add(stream(ascii(String.format(Locale.ROOT,"q %.3f 0 0 %.3f 0 0 cm /Im Do Q",width,height)),""));
   objects.add(stream(page.compressed,"/Type /XObject /Subtype /Image /Width "+page.width+" /Height "+page.height+" /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode"));
  }
  var pdf=new ByteArrayOutputStream();pdf.write(ascii("%PDF-1.4\n"));var offsets=new ArrayList<Integer>();offsets.add(0);
  for(int i=0;i<objects.size();i++){offsets.add(pdf.size());pdf.write(ascii((i+1)+" 0 obj\n"));pdf.write(objects.get(i));pdf.write(ascii("\nendobj\n"));}
  int xref=pdf.size();pdf.write(ascii("xref\n0 "+offsets.size()+"\n0000000000 65535 f \n"));for(int i=1;i<offsets.size();i++)pdf.write(ascii(String.format(Locale.ROOT,"%010d 00000 n \n",offsets.get(i))));pdf.write(ascii("trailer\n<< /Size "+offsets.size()+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF"));Files.write(out,pdf.toByteArray());
 }
 public static void writeFigures(Path out,List<Path> figures)throws IOException{
  var pages=new ArrayList<Raster>();for(Path file:figures){var image=ImageIO.read(file.toFile());if(image==null)throw new IOException("Unreadable figure: "+file);try{pages.add(raster(image));}finally{image.flush();}}writeRasterPages(out,pages);
 }
 private static String text(String zh,String en){return I18n.isEnglish()?en:zh;}
 private static String fit(Graphics2D graphics,String value,int maximum){if(value==null)return "";String result=value.replace('\n',' ').replace('\r',' ');if(graphics.getFontMetrics().stringWidth(result)<=maximum)return result;while(!result.isEmpty()&&graphics.getFontMetrics().stringWidth(result+"…")>maximum)result=result.substring(0,result.offsetByCodePoints(result.length(),-1));return result+"…";}
 /** A raster summary keeps original CJK labels, with full values retained in the accompanying data exports. */
 public static BufferedImage summary(Network network,String title){
  var nodes=network.nodeCollection().stream().sorted(Comparator.comparingInt((GraphNode n)->n.weight).reversed().thenComparing(n->n.id)).limit(30).toList();
  int width=1320,height=Math.max(420,200+nodes.size()*23);var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,width,height);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
  g.setFont(new Font("SansSerif",Font.BOLD,25));g.setColor(new Color(40,49,61));g.drawString(fit(g,title,1210),55,48);g.setFont(new Font("SansSerif",Font.PLAIN,17));g.drawString(text("网络指标摘要","Network metric summary")+" · "+network.nodes.size()+text(" 个节点 / "," nodes / ")+network.edges.size()+text(" 条连接"," links"),55,82);
  int[] x={55,800,920,1085,1200};String[] columns={text("节点名称","Node"),text("频次","Frequency"),text("中介中心性","Betweenness"),"Sigma",text("聚类","Cluster")};g.setColor(new Color(239,242,245));g.fillRect(50,105,1220,31);g.setColor(new Color(75,88,104));for(int i=0;i<x.length;i++)g.drawString(columns[i],x[i],127);
  g.setFont(new Font("SansSerif",Font.PLAIN,16));int y=158;if(nodes.isEmpty()){g.setColor(new Color(101,112,126));g.drawString(text("当前网络没有节点","No nodes in the current network"),55,170);}for(var node:nodes){g.setColor(new Color(45,56,70));g.drawString(fit(g,node.userLabel==null?node.label:node.userLabel,720),x[0],y);g.drawString(Integer.toString(node.weight),x[1],y);g.drawString(String.format(Locale.ROOT,"%.4f",node.centrality),x[2],y);g.drawString(String.format(Locale.ROOT,Math.abs(node.sigma)>=100000?"%.3g":"%.2f",node.sigma),x[3],y);g.drawString(node.cluster<=0?"—":Integer.toString(node.cluster),x[4],y);g.setColor(new Color(230,234,239));g.drawLine(50,y+7,1270,y+7);y+=23;}
  g.setColor(new Color(101,112,126));g.setFont(new Font("SansSerif",Font.PLAIN,14));g.drawString(text("按频次展示前 30 个节点；完整名称与指标请导出数据表。— 表示未归类。","Top 30 nodes by frequency; export data tables for full values. — means unassigned."),55,height-21);g.dispose();return image;
 }
 public static void write(Path out,BufferedImage graph,Network network,String title)throws IOException{
  var table=summary(network,title);try{writeRasterPages(out,List.of(raster(graph),raster(table)));}finally{table.flush();}
 }
 public static void variants(Path out,BufferedImage graph,Network network,String title,boolean showLogo)throws IOException{
  var table=summary(network,title);try(var picture=Branding.images(graph);var summary=Branding.images(table)){
   writeRasterPages(Branding.variant(out,false),List.of(raster(picture.plain()),raster(summary.plain())));
   writeRasterPages(Branding.variant(out,true),List.of(raster(picture.branded()),raster(summary.branded())));
   Files.copy(Branding.variant(out,showLogo),out,StandardCopyOption.REPLACE_EXISTING);
  }finally{table.flush();}
 }
}
