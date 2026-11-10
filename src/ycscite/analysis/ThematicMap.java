package ycscite.analysis;
import ycscite.model.*;
import ycscite.I18n;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Strategic plot of explicitly defined graph-weight metrics; not cluster age or betweenness. */
public final class ThematicMap {
 public record Theme(int cluster,int size,String label,double externalStrength,double internalDensity){}
 public static List<Theme> metrics(Pipeline.Result result){
  Network net=result.network;
  Map<Integer,List<GraphNode>> groups=new TreeMap<>();
  for(var node:net.nodeCollection())if(node.cluster>0)groups.computeIfAbsent(node.cluster,k->new ArrayList<>()).add(node);
  Map<Integer,Double> internal=new HashMap<>(),external=new HashMap<>();
  for(var edge:net.edges){int a=edge.a.cluster,b=edge.b.cluster;if(a==b)internal.merge(a,(double)edge.weight,Double::sum);
   else{external.merge(a,(double)edge.weight,Double::sum);external.merge(b,(double)edge.weight,Double::sum);}}
  var rows=new ArrayList<Theme>();
  for(var e:groups.entrySet()){
   var nodes=e.getValue();nodes.sort(Comparator.comparingInt((GraphNode n)->n.weight).reversed().thenComparing(n->n.id));
   rows.add(new Theme(e.getKey(),nodes.size(),nodes.getFirst().label,external.getOrDefault(e.getKey(),0.0),100*internal.getOrDefault(e.getKey(),0.0)/nodes.size()));
  }
  rows.sort(Comparator.comparingInt(Theme::size).reversed().thenComparingInt(Theme::cluster));return rows;
 }
 public static String table(List<Theme> rows){
  var text=new StringBuilder("cluster\tsize\tlabel\texternal_strength\tinternal_density\n");
  for(var row:rows)text.append(row.cluster).append('\t').append(row.size).append('\t').append(row.label.replace('\t',' ')).append('\t').append(row.externalStrength).append('\t').append(row.internalDensity).append('\n');
  return text.toString();
 }
 public static void render(Pipeline.Result r,Path out)throws java.io.IOException{
  List<Theme> rows=metrics(r);int w=1500,h=1000;
  BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
  g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(Color.WHITE);g.fillRect(0,0,w,h);
  g.setFont(new Font("Microsoft YaHei",Font.BOLD,24));g.setColor(new Color(36,56,76));g.drawString(I18n.text("主题战略图"),90,48);
  int left=135,right=1380,top=105,bottom=865;
  double maxX=Math.max(1,rows.stream().mapToDouble(Theme::externalStrength).max().orElse(1))*1.12;
  double maxY=Math.max(1,rows.stream().mapToDouble(Theme::internalDensity).max().orElse(1))*1.12;
  double midX=rows.stream().mapToDouble(Theme::externalStrength).average().orElse(0),midY=rows.stream().mapToDouble(Theme::internalDensity).average().orElse(0);
  g.setFont(new Font("Microsoft YaHei",Font.PLAIN,14));
  for(int i=0;i<=5;i++){
   int x=left+(right-left)*i/5,y=bottom-(bottom-top)*i/5;g.setColor(new Color(232,237,243));g.drawLine(x,top,x,bottom);g.drawLine(left,y,right,y);
   g.setColor(new Color(117,131,147));g.drawString(String.format(Locale.ROOT,"%.1f",maxX*i/5),x-13,bottom+25);g.drawString(String.format(Locale.ROOT,"%.1f",maxY*i/5),left-65,y+5);
  }
  double splitX=left+(right-left)*midX/maxX,splitY=bottom-(bottom-top)*midY/maxY;
  g.setColor(new Color(143,158,175));g.setStroke(new BasicStroke(1,0,0,1,new float[]{5,5},0));g.draw(new Line2D.Double(splitX,top,splitX,bottom));g.draw(new Line2D.Double(left,splitY,right,splitY));
  g.setColor(new Color(80,105,132));g.setFont(new Font("Microsoft YaHei",Font.PLAIN,17));
  g.drawString(I18n.text("中心性：簇外连接总强度"),490,925);
  var axis=(Graphics2D)g.create();axis.translate(38,650);axis.rotate(-Math.PI/2);axis.drawString(I18n.text("密度：100 × 簇内连接强度 / 词数"),0,0);axis.dispose();
  g.setFont(new Font("Microsoft YaHei",Font.PLAIN,13));g.setColor(new Color(143,153,168));
  g.drawString(I18n.text("专业化主题"),left+10,top+20);g.drawString(I18n.text("驱动主题"),right-100,top+20);
  g.drawString(I18n.text("新兴或衰退主题"),left+10,bottom-12);g.drawString(I18n.text("基础主题"),right-100,bottom-12);
  int maximum=rows.stream().mapToInt(Theme::size).max().orElse(1);List<Rectangle2D> used=new ArrayList<>();int index=0;
  for(var row:rows){
   double x=left+(right-left)*row.externalStrength/maxX,y=bottom-(bottom-top)*row.internalDensity/maxY;
   double radius=7+31*Math.sqrt(row.size/(double)maximum);Color color=Color.getHSBColor((float)(index*.618%1),.55f,.80f);index++;
   g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),120));g.fill(new Ellipse2D.Double(x-radius,y-radius,2*radius,2*radius));g.setColor(color.darker());g.setStroke(new BasicStroke(1));g.draw(new Ellipse2D.Double(x-radius,y-radius,2*radius,2*radius));
   if(index>18)continue;
   g.setFont(new Font("Microsoft YaHei",Font.PLAIN,14));String label="#"+(row.cluster-1)+" "+row.label;
   while(g.getFontMetrics().stringWidth(label)>380&&label.length()>2)label=label.substring(0,label.offsetByCodePoints(label.length(),-2))+"…";
   int width=g.getFontMetrics().stringWidth(label);
   for(int attempt=0;attempt<12;attempt++){
    double lx=Math.clamp(x+radius+5, left,right-width),ly=Math.clamp(y+5+(attempt%2==0?1:-1)*((attempt+1)/2)*18,top+35,bottom-25);
    var rect=new Rectangle2D.Double(lx-3,ly-15,width+6,19);
    if(used.stream().anyMatch(rect::intersects))continue;
    g.setColor(new Color(255,255,255,215));g.fill(rect);g.setColor(new Color(40,60,82));g.drawString(label,(float)lx,(float)ly);used.add(rect);break;
   }
  }
  if(rows.isEmpty()){g.setColor(Color.GRAY);g.drawString(I18n.text("没有可展示的聚类"),500,500);}
  g.dispose();ImageIO.write(image,"png",out.toFile());
 }
}
