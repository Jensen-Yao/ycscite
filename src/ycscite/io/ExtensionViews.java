package ycscite.io;

import ycscite.Settings;
import ycscite.analysis.*;
import ycscite.model.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Report-only extension views. The established nine views use their existing renderer. */
final class ExtensionViews {
    private static final int W=1320,H=818;
    private static final Color INK=new Color(40,55,72),MUTED=new Color(106,121,137),GRID=new Color(225,232,238);
    private static final Color[] COLORS={new Color(52,111,155),new Color(48,145,132),new Color(211,149,65),new Color(186,100,100),new Color(115,137,83),new Color(79,151,182),new Color(163,126,87),new Color(105,116,157),new Color(158,143,76),new Color(91,139,123)};
    private static final Comparator<GraphNode> ORDER=Comparator.comparingInt((GraphNode n)->n.weight).reversed().thenComparing(n->n.id);
    private final Settings settings;
    private final Pipeline.Result result;
    private final Network network;
    private final int from,to;
    private final List<Group> groups;
    private record Group(int id,String label,List<GraphNode> nodes,Color color){}
    private record Disk(double x,double y,double radius){}
    ExtensionViews(Pipeline.Result result,int from,int to,Settings settings){
        this.result=result;this.network=result.network;this.from=from;this.to=to;this.settings=settings;
        var map=new TreeMap<Integer,List<GraphNode>>();
        for(var node:network.nodeCollection())map.computeIfAbsent(node.cluster,k->new ArrayList<>()).add(node);
        var sorted=new ArrayList<>(map.entrySet());
        sorted.sort(Comparator.<Map.Entry<Integer,List<GraphNode>>>comparingInt(e->e.getValue().size()).reversed().thenComparingInt(Map.Entry::getKey));
        groups=new ArrayList<>();
        for(var entry:sorted){entry.getValue().sort(ORDER);groups.add(new Group(entry.getKey(),entry.getValue().getFirst().label,entry.getValue(),COLORS[groups.size()%COLORS.length]));}
    }
    private String t(String zh,String en){return settings.english()?en:zh;}
    private Font font(int size){return new Font("Microsoft YaHei",Font.PLAIN,size);}
    private void text(Graphics2D g,String value,double x,double y,int size,Color color){g.setFont(font(size));g.setColor(color);g.drawString(value,(float)x,(float)y);}
    private String fit(Graphics2D g,String value,int width){if(value==null)return "";if(g.getFontMetrics().stringWidth(value)<=width)return value;while(!value.isEmpty()&&g.getFontMetrics().stringWidth(value+"…")>width)value=value.substring(0,value.offsetByCodePoints(value.length(),-1));return value+"…";}
    private BufferedImage image(){return new BufferedImage(W*settings.integer("image_scale"),H*settings.integer("image_scale"),BufferedImage.TYPE_INT_RGB);}
    private Graphics2D graphics(BufferedImage image,String title,String subtitle){
        Graphics2D g=image.createGraphics();g.scale(settings.integer("image_scale"),settings.integer("image_scale"));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);g.fillRect(0,0,W,H);text(g,title,54,48,25,INK);text(g,subtitle,54,77,12,MUTED);
        g.setColor(GRID);g.drawLine(54,96,W-54,96);return g;
    }
    private void save(Graphics2D g,BufferedImage image,Path path)throws IOException{g.dispose();ImageIO.write(image,"png",path.toFile());}
    private String clusterName(Group group){return (group.id>0?"#"+(group.id-1):t("未归类","Unassigned"))+"  "+group.label;}
    private String meta(){return from+"–"+to+"  ·  "+network.nodes.size()+t(" 个节点 · "," nodes · ")+network.edges.size()+t(" 条连接"," links");}
    private void empty(Graphics2D g,String message){text(g,t("暂无可绘制数据","No data to display"),450,360,23,INK);text(g,message,240,397,14,MUTED);}
    private Color color(GraphNode node){return groups.stream().filter(c->c.id==node.cluster).map(Group::color).findFirst().orElse(COLORS[0]);}
    private double radius(GraphNode node){int max=network.nodeCollection().stream().mapToInt(n->n.weight).max().orElse(1);return (2.6+8*Math.sqrt(node.weight/(double)Math.max(1,max)))*settings.number("node_scale");}
    private void legend(Graphics2D g,int x,int y){
        text(g,t("聚类 / 节点数","Cluster / nodes"),x,y,13,INK);int line=0;
        for(var group:groups.stream().limit(12).toList()){
            int yy=y+30+line++*32;g.setColor(group.color);g.fill(new Ellipse2D.Double(x,yy-9,7,7));
            g.setFont(font(11));text(g,fit(g,clusterName(group),170),x+16,yy,11,INK);
            text(g,Integer.toString(group.nodes.size()),x+195,yy,11,MUTED);
        }
        if(groups.size()>12)text(g,t("其余聚类见节点数据表","More clusters in node table"),x,y+line*32+33,11,MUTED);
        int yy=Math.min(688,y+line*32+80);text(g,t("节点大小：词项频次","Node size: occurrence frequency"),x,yy,11,MUTED);
        text(g,t("节点颜色：聚类","Node color: cluster"),x,yy+23,11,MUTED);
    }
    private void nodes(Graphics2D g,Map<GraphNode,Point2D.Double> points,boolean edges){
        if(edges){g.setStroke(new BasicStroke(.7f));for(var edge:network.edges){var a=points.get(edge.a);var b=points.get(edge.b);if(a==null||b==null)continue;var c=edge.a.cluster==edge.b.cluster?color(edge.a):new Color(155,165,176);g.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),(int)(46*settings.number("link_opacity"))));g.draw(new Line2D.Double(a,b));}}
        var ordered=new ArrayList<>(points.keySet());ordered.sort(ORDER.reversed());
        for(var node:ordered){var p=points.get(node);double r=radius(node);g.setColor(color(node));g.fill(new Ellipse2D.Double(p.x-r,p.y-r,2*r,2*r));g.setColor(Color.WHITE);g.setStroke(new BasicStroke(.8f));g.draw(new Ellipse2D.Double(p.x-r,p.y-r,2*r,2*r));}
        ordered.sort(ORDER);List<Rectangle2D> labels=new ArrayList<>();int max=Math.min(settings.integer("label_budget"),24),written=0;
        int size=(int)Math.clamp(12*settings.number("label_scale"),9,22);g.setFont(font(size));
        for(var node:ordered){if(written>=max)break;var p=points.get(node);String label=fit(g,node.label,155);int width=g.getFontMetrics().stringWidth(label);double r=radius(node);
            for(int i=0;i<8;i++){double xx=Math.clamp(p.x+r+5,72,974-width),yy=Math.clamp(p.y+size/3.0+(i%2==0?1:-1)*((i+1)/2)*(size+3),130,716);
                var box=new Rectangle2D.Double(xx-3,yy-size,width+6,size+5);if(labels.stream().anyMatch(box::intersects))continue;
                g.setColor(new Color(255,255,255,220));g.fill(box);text(g,label,xx,yy,size,INK);labels.add(box);written++;break;
            }
        }
    }
    void activityTable(Path path)throws IOException{
        var out=new StringBuilder("cluster_id\tdisplay_id\tlabel\tyear\toccurrences\n");
        for(var group:groups)for(int year=from;year<=to;year++){int count=0;for(var node:group.nodes)count+=node.countsByYear.getOrDefault(year,0);out.append(group.id).append('\t').append(group.id>0?group.id-1:"unassigned").append('\t').append(group.label.replace('\t',' ')).append('\t').append(year).append('\t').append(count).append('\n');}
        Files.writeString(path,out);
    }
    private double[][] activity(int count){double[][] a=new double[count][to-from+1];for(int i=0;i<count;i++)for(var node:groups.get(i).nodes)for(var entry:node.countsByYear.entrySet())if(entry.getKey()>=from&&entry.getKey()<=to)a[i][entry.getKey()-from]+=entry.getValue();return a;}
    private Color heat(double value){double t=Math.clamp(value,0,1);int[][] stops={{247,250,252},{209,230,236},{119,184,196},{46,125,155},{24,73,112}};double position=t*4;int i=Math.min(3,(int)position);double f=position-i;return new Color((int)(stops[i][0]*(1-f)+stops[i+1][0]*f),(int)(stops[i][1]*(1-f)+stops[i+1][1]*f),(int)(stops[i][2]*(1-f)+stops[i+1][2]*f));}
    void heatmap(Path path)throws IOException{
        var image=image();var g=graphics(image,t("聚类年度活动热力图","Annual cluster activity"),t("颜色表示该聚类在每年的词项出现总次数；所有单元格使用同一线性色标。","Color encodes annual term occurrences per cluster, on one shared linear scale."));
        int count=Math.min(18,groups.size()),years=to-from+1;double[][] a=activity(count);double maximum=1;for(var row:a)for(double value:row)maximum=Math.max(maximum,value);
        int left=290,right=1160,top=145,bottom=688;double cw=(right-left)/(double)years,ch=(bottom-top)/(double)Math.max(1,count);
        if(count==0){empty(g,t("当前网络中没有节点，请调整选点条件。","The current network has no nodes. Adjust the selection criteria."));save(g,image,path);return;}
        for(int row=0;row<count;row++){var group=groups.get(row);g.setFont(font(13));text(g,fit(g,clusterName(group),220),54,top+(row+.5)*ch+5,13,INK);
            for(int year=0;year<years;year++){double x=left+year*cw,y=top+row*ch;g.setColor(heat(a[row][year]/maximum));g.fill(new Rectangle2D.Double(x+1,y+1,Math.max(.5,cw-2),ch-2));if(years<=22&&count<=14)text(g,Integer.toString((int)a[row][year]),x+cw/2-7,y+ch/2+4,11,a[row][year]/maximum>.58?Color.WHITE:MUTED);}}
        int step=Math.max(1,(int)Math.ceil(years/18.0));for(int i=0;i<years;i++)if(i%step==0||i==years-1)text(g,Integer.toString(from+i),left+(i+.5)*cw-15,bottom+24,11,MUTED);
        text(g,t("年份","Year"),(left+right)/2.0-15,bottom+51,12,INK);
        int bx=1210,by=top,bh=260;for(int i=0;i<bh;i++){g.setColor(heat(1-i/(double)bh));g.drawLine(bx,by+i,bx+14,by+i);}text(g,Integer.toString((int)maximum),bx-2,by-10,11,MUTED);text(g,"0",bx+2,by+bh+19,11,MUTED);text(g,t("频次","Count"),bx-7,by+bh+45,12,INK);
        text(g,t("按聚类规模排序 · 展示 ","Ranked by cluster size · showing ")+count+" / "+groups.size()+t(" 个聚类 · 全部频次见数据表"," clusters · full counts in the data table"),54,781,12,MUTED);save(g,image,path);
    }
    void circular(Path path)throws IOException{
        var image=image();var g=graphics(image,t("环形聚类网络","Circular cluster network"),meta()+t(" · 节点按聚类分组排列"," · nodes grouped by cluster"));
        if(network.nodes.isEmpty()){empty(g,t("当前网络没有节点。","The current network has no nodes."));save(g,image,path);return;}
        double cx=530,cy=426,r=248;Map<GraphNode,Point2D.Double> points=new LinkedHashMap<>();Map<Group,double[]> arcs=new LinkedHashMap<>();
        double gap=Math.min(.10,.65/groups.size()),available=2*Math.PI-gap*groups.size(),angle=-Math.PI/2;
        for(var group:groups){double span=available*group.nodes.size()/network.nodes.size();arcs.put(group,new double[]{angle,angle+span});for(int i=0;i<group.nodes.size();i++){double theta=angle+span*(i+.5)/group.nodes.size();points.put(group.nodes.get(i),new Point2D.Double(cx+r*Math.cos(theta),cy+r*Math.sin(theta)));}angle+=span+gap;}
        var clip=g.getClip();g.clip(new Rectangle(55,109,910,630));
        for(var edge:network.edges){var a=points.get(edge.a);var b=points.get(edge.b);boolean within=edge.a.cluster==edge.b.cluster;Color c=within?color(edge.a):new Color(135,152,169);g.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),(int)((within?72:22)*settings.number("link_opacity"))));g.setStroke(new BasicStroke((float)Math.min(1.8,.45+Math.log1p(edge.weight)*.28)));g.draw(new QuadCurve2D.Double(a.x,a.y,cx,cy,b.x,b.y));}
        for(var entry:arcs.entrySet()){double start=entry.getValue()[0],end=entry.getValue()[1];g.setColor(entry.getKey().color);g.setStroke(new BasicStroke(5,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND));g.draw(new Arc2D.Double(cx-r-14,cy-r-14,2*r+28,2*r+28,-Math.toDegrees(start),-Math.toDegrees(end-start),Arc2D.OPEN));}
        for(var node:points.keySet()){var p=points.get(node);double nr=Math.min(9,radius(node));g.setColor(color(node));g.fill(new Ellipse2D.Double(p.x-nr,p.y-nr,nr*2,nr*2));g.setColor(Color.WHITE);g.setStroke(new BasicStroke(.6f));g.draw(new Ellipse2D.Double(p.x-nr,p.y-nr,nr*2,nr*2));}
        g.setClip(clip);var important=network.nodeCollection().stream().sorted(ORDER).limit(Math.min(24,settings.integer("label_budget"))).toList();
        for(int side:new int[]{-1,1}){var labels=important.stream().filter(n->(points.get(n).x>=cx?1:-1)==side).sorted(Comparator.comparingDouble(n->points.get(n).y)).toList();double previous=130;
            for(int i=0;i<labels.size();i++){var node=labels.get(i);var p=points.get(node);double yy=Math.clamp(Math.max(p.y,previous+22),145,709-(labels.size()-1-i)*22);previous=yy;int x=side<0?88:818;int end=side<0?242:810;g.setColor(GRID.darker());g.setStroke(new BasicStroke(.7f));g.draw(new Path2D.Double(){ {moveTo(p.x,p.y);lineTo(side<0?255:805,yy);lineTo(end,yy);} });g.setFont(font((int)(12*settings.number("label_scale"))));String label=fit(g,node.label,142);int width=g.getFontMetrics().stringWidth(label);text(g,label,side<0?end-width-5:x,yy+4,g.getFont().getSize(),INK);}}
        legend(g,1020,142);text(g,t("细线表示实际共现关系；曲线仅用于排版，不表示方向。","Curves represent observed undirected links; curvature carries no metric."),54,781,12,MUTED);save(g,image,path);
    }
    void embedding(Path path,boolean tsne)throws IOException{
        var image=image();var g=graphics(image,tsne?t("t-SNE 结构嵌入（实验）","t-SNE structural embedding · experimental"):t("UMAP 近似结构嵌入（实验）","Approximate UMAP structural embedding · experimental"),meta());
        if(network.nodes.isEmpty()){empty(g,t("当前网络没有节点。","The current network has no nodes."));save(g,image,path);return;}
        var nodes=new ArrayList<>(network.nodeCollection());Map<GraphNode,Point2D.Double> raw;
        if(tsne){double[][] coordinates=Tsne.embed(network,settings.integer("tsne_iterations"),settings.number("tsne_perplexity"),settings.integer("embedding_seed"));raw=new LinkedHashMap<>();for(int i=0;i<nodes.size();i++)raw.put(nodes.get(i),new Point2D.Double(coordinates[i][0],coordinates[i][1]));}else raw=Umap.layout(network,settings.integer("embedding_seed"),settings.integer("umap_iterations"));
        double minX=raw.values().stream().mapToDouble(p->p.x).min().orElse(0),maxX=raw.values().stream().mapToDouble(p->p.x).max().orElse(1),minY=raw.values().stream().mapToDouble(p->p.y).min().orElse(0),maxY=raw.values().stream().mapToDouble(p->p.y).max().orElse(1);
        double scale=Math.min(800/Math.max(.001,maxX-minX),480/Math.max(.001,maxY-minY));Map<GraphNode,Point2D.Double> points=new LinkedHashMap<>();for(var node:nodes){var p=raw.get(node);points.put(node,new Point2D.Double(510+(p.x-(minX+maxX)/2)*scale,410+(p.y-(minY+maxY)/2)*scale));}
        g.setColor(GRID);g.drawLine(78,720,977,720);g.drawLine(78,720,78,131);text(g,t("嵌入维度 1","Embedding dimension 1"),410,749,12,MUTED);var axis=(Graphics2D)g.create();axis.rotate(-Math.PI/2,42,500);text(axis,t("嵌入维度 2","Embedding dimension 2"),42,500,12,MUTED);axis.dispose();nodes(g,points,true);legend(g,1020,142);
        text(g,t("结构探索视图 · 轴无物理单位；距离不代表经验证的语义相似度。","Exploratory graph layout · axes have no physical unit; distances are not validated semantic similarity."),54,790,12,MUTED);save(g,image,path);
    }
    private List<Disk> pack(List<Double> radii){
        List<Disk> placed=new ArrayList<>();for(double r:radii){Disk best=null;double score=Double.POSITIVE_INFINITY;
            if(placed.isEmpty()){placed.add(new Disk(0,0,r));continue;}
            for(int ai=0;ai<Math.min(40,placed.size());ai++)for(int i=0;i<32;i++){var anchor=placed.get(ai<8?ai:Math.min(placed.size()-1,(int)((ai-7)*(placed.size()-1)/33.0)));double angle=i*2*Math.PI/32;double x=anchor.x+(anchor.radius+r+1.5)*Math.cos(angle),y=anchor.y+(anchor.radius+r+1.5)*Math.sin(angle);boolean overlap=false;for(var d:placed)if(Math.hypot(x-d.x,y-d.y)<r+d.radius+1){overlap=true;break;}double value=Math.hypot(x,y)+r;if(!overlap&&value<score){score=value;best=new Disk(x,y,r);}}
            if(best==null){double x=placed.stream().mapToDouble(d->d.x+d.radius).max().orElse(0)+r+2;best=new Disk(x,0,r);}placed.add(best);
        }return placed;
    }
    void circlePack(Path path)throws IOException{
        var image=image();var g=graphics(image,t("聚类圆形打包（探索）","Cluster circle packing · exploratory"),meta()+t(" · 小圆面积表示节点频次"," · inner circle area encodes node frequency"));
        var shown=groups.stream().limit(12).toList();if(shown.isEmpty()){empty(g,t("当前网络没有节点。","The current network has no nodes."));save(g,image,path);return;}
        List<List<Disk>> contents=new ArrayList<>();List<Double> radii=new ArrayList<>();for(var group:shown){var packed=pack(group.nodes.stream().map(n->Math.sqrt(Math.max(1,n.weight))).toList());contents.add(packed);radii.add(packed.stream().mapToDouble(d->Math.hypot(d.x,d.y)+d.radius).max().orElse(1)+4);}
        var outer=pack(radii);double minX=outer.stream().mapToDouble(d->d.x-d.radius).min().orElse(0),maxX=outer.stream().mapToDouble(d->d.x+d.radius).max().orElse(1),minY=outer.stream().mapToDouble(d->d.y-d.radius).min().orElse(0),maxY=outer.stream().mapToDouble(d->d.y+d.radius).max().orElse(1);double scale=Math.min(850/(maxX-minX),570/(maxY-minY));double cx=530-(minX+maxX)/2*scale,cy=423-(minY+maxY)/2*scale;
        for(int i=0;i<shown.size();i++){var group=shown.get(i);var d=outer.get(i);double x=cx+d.x*scale,y=cy+d.y*scale,rr=d.radius*scale;g.setColor(new Color(group.color.getRed(),group.color.getGreen(),group.color.getBlue(),18));g.fill(new Ellipse2D.Double(x-rr,y-rr,rr*2,rr*2));g.setStroke(new BasicStroke(1));g.setColor(group.color);g.draw(new Ellipse2D.Double(x-rr,y-rr,rr*2,rr*2));var packed=contents.get(i);for(int j=0;j<packed.size();j++){var small=packed.get(j);double nx=x+small.x*scale,ny=y+small.y*scale,nr=small.radius*scale;g.setColor(new Color(group.color.getRed(),group.color.getGreen(),group.color.getBlue(),160));g.fill(new Ellipse2D.Double(nx-nr,ny-nr,nr*2,nr*2));g.setColor(Color.WHITE);g.setStroke(new BasicStroke(.6f));g.draw(new Ellipse2D.Double(nx-nr,ny-nr,nr*2,nr*2));if(j==0&&nr>17){text(g,group.id>0?"#"+(group.id-1):"—",nx-9,ny+4,12,Color.WHITE);}}}
        legend(g,1020,142);text(g,t("展示规模最大的 ","Showing the largest ")+shown.size()+t(" 个聚类；外圈包含留白，面积不能用来比较聚类频次。"," clusters; outer-circle area includes packing space and does not encode frequency."),54,781,12,MUTED);save(g,image,path);
    }

    void contour(Path path)throws IOException{
        var image=image();var g=graphics(image,t("聚类活动等值线（实验）","Cluster activity contours · experimental"),t("按年份和聚类序号平滑词项频次；聚类轴为分类顺序，不是连续语义空间。","Term counts smoothed over years and cluster order; cluster order is categorical, not semantic distance."));
        int count=Math.min(16,groups.size()),years=to-from+1;
        if(count==0){empty(g,t("当前网络没有节点。","The current network has no nodes."));save(g,image,path);return;}
        double[][] a=activity(count);int nx=140,ny=90;double[][] field=new double[ny][nx];double max=0;
        for(int y=0;y<ny;y++)for(int x=0;x<nx;x++){double xx=(x/(double)(nx-1))*(years-1),yy=y/(double)(ny-1)*(count-1),value=0,norm=0;
            for(int c=Math.max(0,(int)yy-2);c<Math.min(count,(int)yy+4);c++)for(int yr=Math.max(0,(int)xx-3);yr<Math.min(years,(int)xx+5);yr++){double weight=Math.exp(-.5*Math.pow((xx-yr)/.8,2)-.5*Math.pow((yy-c)/.45,2));value+=a[c][yr]*weight;norm+=weight;}field[y][x]=value/Math.max(.0001,norm);max=Math.max(max,field[y][x]);}
        int left=278,right=1160,top=142,bottom=698;double cw=(right-left)/(double)nx,ch=(bottom-top)/(double)ny;
        for(int y=0;y<ny;y++)for(int x=0;x<nx;x++){g.setColor(heat(field[y][x]/Math.max(1,max)));g.fill(new Rectangle2D.Double(left+x*cw,top+y*ch,cw+.3,ch+.3));}
        g.setStroke(new BasicStroke(.65f));g.setColor(new Color(34,76,102,140));
        for(int y=0;y<ny-1;y++)for(int x=0;x<nx-1;x++)for(int level=1;level<6;level++){double threshold=max*level/6;double[] values={field[y][x],field[y][x+1],field[y+1][x+1],field[y+1][x]};double[][] corners={{left+(x+.5)*cw,top+(y+.5)*ch},{left+(x+1.5)*cw,top+(y+.5)*ch},{left+(x+1.5)*cw,top+(y+1.5)*ch},{left+(x+.5)*cw,top+(y+1.5)*ch}};List<Point2D.Double> hits=new ArrayList<>();for(int edge=0;edge<4;edge++){int b=(edge+1)%4;if((values[edge]<threshold)==(values[b]<threshold))continue;double f=(threshold-values[edge])/(values[b]-values[edge]);hits.add(new Point2D.Double(corners[edge][0]+f*(corners[b][0]-corners[edge][0]),corners[edge][1]+f*(corners[b][1]-corners[edge][1])));}for(int i=0;i+1<hits.size();i+=2)g.draw(new Line2D.Double(hits.get(i),hits.get(i+1)));}
        g.setFont(font(12));for(int i=0;i<count;i++)text(g,fit(g,clusterName(groups.get(i)),207),54,top+(i+.5)*(bottom-top)/count+4,12,INK);
        int step=Math.max(1,(int)Math.ceil(years/14.0));for(int i=0;i<years;i++)if(i%step==0||i==years-1)text(g,Integer.toString(from+i),left+i*(right-left)/(double)Math.max(1,years-1)-13,bottom+26,11,MUTED);
        for(int i=0;i<240;i++){g.setColor(heat(1-i/240.0));g.drawLine(1212,top+i,1227,top+i);}text(g,String.format(Locale.ROOT,"%.1f",max),1204,top-12,11,MUTED);text(g,"0",1215,top+263,11,MUTED);text(g,t("平滑频次","Smoothed"),1192,top+289,11,MUTED);
        text(g,t("仅用于活动趋势探索；定量比较请使用年度活动热力图及原始频次表。","For exploratory trends; use the activity heatmap and raw counts for quantitative comparisons."),54,781,12,MUTED);save(g,image,path);
    }
    void thematic(Path path,Path table)throws IOException{
        var rows=ThematicMap.metrics(result);Files.writeString(table,ThematicMap.table(rows));
        var image=image();var g=graphics(image,t("主题战略图","Thematic strategic map"),t("横轴：簇外连接总强度 · 纵轴：100 × 簇内连接强度 / 节点数 · 气泡面积：节点数","X: external link strength · Y: 100 × internal link strength / nodes · bubble area: nodes"));
        if(rows.isEmpty()){empty(g,t("当前网络没有可比较的聚类。","The current network has no comparable clusters."));save(g,image,path);return;}
        int left=108,right=973,top=149,bottom=690;double maxX=Math.max(1,rows.stream().mapToDouble(ThematicMap.Theme::externalStrength).max().orElse(1))*1.18,maxY=Math.max(1,rows.stream().mapToDouble(ThematicMap.Theme::internalDensity).max().orElse(1))*1.18;
        double mx=rows.stream().mapToDouble(ThematicMap.Theme::externalStrength).average().orElse(0),my=rows.stream().mapToDouble(ThematicMap.Theme::internalDensity).average().orElse(0);
        for(int i=0;i<=5;i++){int x=left+i*(right-left)/5,y=bottom-i*(bottom-top)/5;g.setColor(GRID);g.drawLine(x,top,x,bottom);g.drawLine(left,y,right,y);text(g,String.format(Locale.ROOT,"%.0f",maxX*i/5),x-10,bottom+24,11,MUTED);text(g,String.format(Locale.ROOT,"%.0f",maxY*i/5),left-52,y+4,11,MUTED);}
        g.setColor(new Color(140,155,170));g.setStroke(new BasicStroke(1,0,0,1,new float[]{5,5},0));double sx=left+mx/maxX*(right-left),sy=bottom-my/maxY*(bottom-top);g.draw(new Line2D.Double(sx,top,sx,bottom));g.draw(new Line2D.Double(left,sy,right,sy));
        text(g,t("簇外连接总强度","External link strength"),416,746,13,INK);var axis=(Graphics2D)g.create();axis.rotate(-Math.PI/2,35,523);text(axis,t("簇内连接密度","Internal link density"),35,523,13,INK);axis.dispose();
        List<Rectangle2D> used=new ArrayList<>();int maxSize=rows.stream().mapToInt(ThematicMap.Theme::size).max().orElse(1),i=0;
        for(var row:rows){double x=left+row.externalStrength()/maxX*(right-left),y=bottom-row.internalDensity()/maxY*(bottom-top),r=33*Math.sqrt(row.size()/(double)maxSize);Color c=COLORS[i++%COLORS.length];g.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),125));g.fill(new Ellipse2D.Double(x-r,y-r,2*r,2*r));g.setColor(c.darker());g.setStroke(new BasicStroke(1));g.draw(new Ellipse2D.Double(x-r,y-r,2*r,2*r));g.setFont(font((int)(12*settings.number("label_scale"))));String label=fit(g,"#"+(row.cluster()-1)+" "+row.label(),190);int width=g.getFontMetrics().stringWidth(label);
            for(int a=0;a<14;a++){double lx=Math.clamp(x+r+5,left+4,right-width),ly=Math.clamp(y+4+(a%2==0?1:-1)*((a+1)/2)*18,top+15,bottom-12);var box=new Rectangle2D.Double(lx-2,ly-13,width+4,18);if(used.stream().anyMatch(box::intersects))continue;g.setColor(new Color(255,255,255,225));g.fill(box);text(g,label,lx,ly,g.getFont().getSize(),INK);used.add(box);break;}}
        text(g,t("如何阅读","Reading the map"),1020,157,13,INK);String[][] help={{"右上：高密度、高外联","Upper right: dense, connected"},{"左上：高密度、低外联","Upper left: dense, specialized"},{"右下：低密度、高外联","Lower right: connected, sparse"},{"左下：低密度、低外联","Lower left: sparse, peripheral"},{"虚线：本次聚类指标均值","Dashed lines: cluster means"},{"颜色仅区分聚类","Color identifies clusters"},{"坐标不表示主题年龄","Axes do not encode topic age"}};for(int a=0;a<help.length;a++)text(g,t(help[a][0],help[a][1]),1020,193+a*32,11,MUTED);
        text(g,t("这些指标基于当前网络与连接权重；不据此单独判定主题是否新兴或衰退。","Metrics depend on the selected network and weights; this plot alone does not establish emergence or decline."),54,786,12,MUTED);save(g,image,path);
    }
    void citationFlow(Project project,Path path,Path table)throws IOException{
        Map<String,Map<String,Integer>> links=new TreeMap<>();int references=0,parsed=0;
        for(var record:project.records)for(var raw:record.citedRefs){references++;var ref=CitedRef.parse(raw);if(ref==null||ref.source.isBlank()||ref.year<=0||record.source==null||record.source.isBlank())continue;parsed++;links.computeIfAbsent(record.source.strip(),k->new TreeMap<>()).merge(ref.source.strip().toUpperCase(Locale.ROOT),1,Integer::sum);}
        var text=new StringBuilder("citing_journal\tcited_journal\tcitations\n");Map<String,Integer> leftCounts=new TreeMap<>(),rightCounts=new TreeMap<>();for(var entry:links.entrySet())for(var target:entry.getValue().entrySet()){leftCounts.merge(entry.getKey(),target.getValue(),Integer::sum);rightCounts.merge(target.getKey(),target.getValue(),Integer::sum);text.append(entry.getKey().replace('\t',' ')).append('\t').append(target.getKey().replace('\t',' ')).append('\t').append(target.getValue()).append('\n');}Files.writeString(table,text);
        var image=image();var g=graphics(image,t("期刊引文流","Journal citation flows"),t("依据参考文献中的期刊字段统计 · 连线由施引期刊指向被引期刊","Observed reference-journal fields · flows from citing to cited journals"));
        if(parsed==0){empty(g,t("需要施引期刊名称及含年份、期刊字段的参考文献；当前数据无法建立期刊引文流。","Citing-journal names and references with year and journal are required; none are available."));save(g,image,path);return;}
        Comparator<Map.Entry<String,Integer>> order=Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey);var left=leftCounts.entrySet().stream().sorted(order).limit(12).toList();var right=rightCounts.entrySet().stream().sorted(order).limit(12).toList();Map<String,Double> ly=new HashMap<>(),ry=new HashMap<>();for(int i=0;i<left.size();i++)ly.put(left.get(i).getKey(),170+i*43.0);for(int i=0;i<right.size();i++)ry.put(right.get(i).getKey(),170+i*43.0);
        int index=0,displayed=0;for(var entry:left){var c=COLORS[index++%COLORS.length];for(var target:links.get(entry.getKey()).entrySet()){if(!ry.containsKey(target.getKey()))continue;displayed+=target.getValue();double ya=ly.get(entry.getKey()),yb=ry.get(target.getKey());g.setColor(new Color(c.getRed(),c.getGreen(),c.getBlue(),95));g.setStroke(new BasicStroke((float)Math.min(10,1+Math.log1p(target.getValue())*1.3)));g.draw(new CubicCurve2D.Double(426,ya,600,ya,720,yb,894,yb));}}
        text(g,t("施引期刊","Citing journals"),60,132,14,INK);text(g,t("被引期刊","Cited journals"),926,132,14,INK);index=0;
        for(var entry:left){double y=ly.get(entry.getKey());g.setColor(COLORS[index++%COLORS.length]);g.fill(new RoundRectangle2D.Double(418,y-9,8,18,3,3));g.setFont(font(12));text(g,fit(g,entry.getKey(),315),60,y+4,12,INK);text(g,Integer.toString(entry.getValue()),378,y+4,11,MUTED);}
        for(var entry:right){double y=ry.get(entry.getKey());g.setColor(new Color(94,122,144));g.fill(new RoundRectangle2D.Double(894,y-9,8,18,3,3));g.setFont(font(12));text(g,fit(g,entry.getKey(),296),924,y+4,12,INK);text(g,Integer.toString(entry.getValue()),1240,y+4,11,MUTED);}
        text(g,t("可解析引文：","Parsed references: ")+parsed+" / "+references+t(" · 两侧各展示前 12 本期刊 · 图内引文数："," · top 12 journals on each side · citations shown: ")+displayed,54,749,12,MUTED);
        text(g,t("此图展示观测引文流；未使用学科期刊底图，不能解释为原版 JCR 双图叠加。","Observed citation flow without a disciplinary basemap; this is not a JCR dual-map overlay."),54,781,12,MUTED);save(g,image,path);
    }
}
