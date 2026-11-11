package ycscite;
import java.util.*;
/** Interface/report translations; original bibliographic terms are deliberately preserved. */
public final class I18n {
 private I18n(){}
 private static final ThreadLocal<String> LANGUAGE=ThreadLocal.withInitial(()->"zh");
 public static boolean isEnglish(){return LANGUAGE.get().equals("en");}
 public static String text(String value){return LANGUAGE.get().equals("en")?english(value):value;}
 public static Scope language(String lang){return new Scope(lang);}
 public static final class Scope implements AutoCloseable{
  private final String previous;
  private Scope(String language){previous=LANGUAGE.get();LANGUAGE.set(language);}
  public void close(){LANGUAGE.set(previous);}
 }

 private static final Map<String,String> EN=new LinkedHashMap<>();
 static {
  EN.put("（本数据未检测到突现词）","No keyword bursts were detected");
  String[][] entries={
   {"年度发文量趋势","Annual publication trend"},{"关键词共现网络","Keyword co-occurrence network"},
   {"关键词聚类","Keyword clusters"},{"关键词时区","Keyword time zone"},{"关键词时间线","Keyword timeline"},
   {"聚类演化山峰","Cluster evolution landscape"},{"关键词突现 Top 25","Top 25 keyword bursts"},
   {"作者合作网络","Co-authorship network"},{"机构合作网络","Institution collaboration network"},
   {"年份（年）","Year"},{"发文量（篇）","Publications"},{"关键词","Keyword"},{"首现","First seen"},
   {"强度","Strength"},{"开始","Begin"},{"结束","End"},{"年份","Year"},
   {"没有可展示的聚类","No clusters to display"},{"没有可绘制的数据","No data to draw"},{"未检测到关键词突现","No keyword bursts detected"},
   {"各簇年度关键词频次（各行按自身峰值归一化）","Annual keyword occurrences (normalized within each cluster)"},
   {"Ycscite 九图报告","Ycscite analysis report"},{"无剪枝","No pruning"},{"无年份","Undated"}
  };
  EN.put("t-SNE 降维网络","t-SNE network");EN.put("UMAP 降维网络","UMAP network");EN.put("主题地图","Thematic map");EN.put("中心性","Centrality");EN.put("密度","Density");
  EN.put("主题战略图","Thematic strategic map");EN.put("中心性：簇外连接总强度","Centrality: total external link strength");
  EN.put("密度：100 × 簇内连接强度 / 词数","Density: 100 × internal link strength / terms");
  EN.put("专业化主题","Niche themes");EN.put("驱动主题","Motor themes");EN.put("新兴或衰退主题","Emerging or declining themes");EN.put("基础主题","Basic themes");
  EN.put("Dual-Map Overlays（左：施引期刊 · 右：被引对象）","Dual-map overlay · citing journals to cited journals");
  EN.put("施引期刊","Citing journals");EN.put("被引期刊","Cited journals");
  EN.put("年份（节点年轮颜色）","Year (node rings)");EN.put("紫环 = 高中介中心性","Purple ring = high betweenness");EN.put("红环 = 突现","Red ring = burst");EN.put("聚类（LLR 标签）","Clusters (LLR labels)");
  EN.put("期刊引文流","Journal citation flows");EN.put("聚类活动等值线","Cluster activity contours");EN.put("聚类年度活动热力图","Annual cluster activity heatmap");EN.put("网络总览","Network overview");EN.put("环形网络","Circular network");EN.put("共现矩阵热力图","Co-occurrence heatmap");
  EN.put("聚类圆形打包","Cluster circle packing");EN.put("主题战略图","Thematic map");EN.put("网络等高线","Network contours");EN.put("期刊双图叠加","Journal dual-map overlay");
  for(var pair:entries)EN.put(pair[0],pair[1]);
 }
 public static String english(String source){
  if(source==null)return "";
  String exact=EN.get(source);if(exact!=null)return exact;
  // Only renderer metadata patterns are translated; document titles and keyword labels stay intact.
  return source.replace("关键词突现 Top ","Top keyword bursts · ").replace("（每年一片）"," (one-year slices)").replace(" 节点 / "," nodes / ")
   .replace(" 边 · 密度 "," edges · density ").replace("模块度 Q=","Modularity Q=").replace("轨迹：施引→被引（期刊名匹配底图，共 ","Matched citing-to-cited journal flows: ").replace(" 对）"," pairs")
   .replace(" · 显示 "," · shown ").replace(" 节点"," nodes");
 }
}
