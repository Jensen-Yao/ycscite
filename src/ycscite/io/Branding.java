package ycscite.io;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.regex.*;

/** Logo variants share the exact source pixels; branding occupies a separate footer. */
public final class Branding {
 private Branding(){}
 public record Images(BufferedImage plain,BufferedImage branded) implements AutoCloseable{
  public BufferedImage selected(boolean logo){return logo?branded:plain;}
  public void close(){plain.flush();branded.flush();}
 }
 public static Images images(BufferedImage source)throws IOException{
  int width=source.getWidth(),height=source.getHeight(),footer=Math.max(32,width/55);
  boolean alpha=source.getColorModel().hasAlpha();int type=alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB;
  var plain=new BufferedImage(width,height+footer,type);var branded=new BufferedImage(width,height+footer,type);
  if(!alpha)for(var image:new BufferedImage[]{plain,branded}){var background=image.createGraphics();background.setColor(Color.WHITE);background.fillRect(0,0,width,height+footer);background.dispose();}
  int[] row=new int[width];for(int y=0;y<height;y++){source.getRGB(0,y,width,1,row,0,width);plain.setRGB(0,y,width,1,row,0,width);branded.setRGB(0,y,width,1,row,0,width);}
  Graphics2D g=branded.createGraphics();
  try(var stream=Branding.class.getResourceAsStream("/ycscite-logo.png")){
   if(stream!=null){var logo=ImageIO.read(stream);if(logo!=null){int size=footer-4;g.drawImage(logo,9,height+2,size,size,null);logo.flush();}}
  }
  g.setColor(new Color(112,125,140));g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,footer/4)));g.drawString("Ycscite",footer+12,branded.getHeight()-footer/3);g.dispose();
  return new Images(plain,branded);
 }
 public static Path variant(Path file,boolean logo)throws IOException{
  Path absolute=file.toAbsolutePath().normalize();Path directory=Files.createDirectories(absolute.getParent().resolve(logo?"with-logo":"no-logo"));return directory.resolve(absolute.getFileName());
 }
 public static void writePng(Path out,BufferedImage source,boolean logo)throws IOException{
  try(var pair=images(source)){ImageIO.write(pair.plain(),"png",variant(out,false).toFile());ImageIO.write(pair.branded(),"png",variant(out,true).toFile());ImageIO.write(pair.selected(logo),"png",out.toFile());}
 }
 public static void variants(Path out,boolean logo)throws IOException{
  var source=ImageIO.read(out.toFile());if(source==null)throw new IOException("Unreadable image: "+out);
  try{writePng(out,source,logo);}finally{source.flush();}
 }
 private static final Pattern SVG_ROOT=Pattern.compile("<svg\\b[^>]*>",Pattern.DOTALL);
 private static String attribute(String root,String name,String value){
  Pattern pattern=Pattern.compile("(?<![\\w:-])"+Pattern.quote(name)+"\\s*=\\s*([\"'])(.*?)\\1",Pattern.DOTALL);Matcher found=pattern.matcher(root);
  String replacement=name+"=\""+value+"\"";return found.find()?found.replaceFirst(Matcher.quoteReplacement(replacement)):root.substring(0,root.length()-1)+" "+replacement+">";
 }
 public static void svgVariants(Path out,boolean logo,int width,int height)throws IOException{
  String source=Files.readString(out);Matcher match=SVG_ROOT.matcher(source);if(!match.find())throw new IOException("SVG root is missing");
  String root=match.group();
  if(root.contains("data-ycscite-footer=")){
   Matcher body=Pattern.compile("data-ycscite-body-height=\"(\\d+)\"").matcher(root);if(!body.find()||Integer.parseInt(body.group(1))!=height)throw new IOException("SVG body dimensions do not match the recorded source");
   source=source.replaceAll("(?s)<g id=\"ycscite-brand-footer\">.*?</g>","");match=SVG_ROOT.matcher(source);if(!match.find())throw new IOException("SVG root is missing");root=match.group();
  }
  root=attribute(root,"height",Integer.toString(height+60));root=attribute(root,"viewBox","0 0 "+width+" "+(height+60));root=attribute(root,"data-ycscite-footer","v1");root=attribute(root,"data-ycscite-body-height",Integer.toString(height));
  String plain=source.substring(0,match.start())+root+source.substring(match.end());int close=plain.lastIndexOf("</svg>");if(close<0)throw new IOException("SVG closing element is missing");
  String image="";try(var stream=Branding.class.getResourceAsStream("/ycscite-logo.png")){if(stream!=null)image="<image x=\"8\" y=\""+(height+2)+"\" width=\"50\" height=\"50\" href=\"data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(stream.readAllBytes())+"\"/>";}
  String mark="<g id=\"ycscite-brand-footer\">"+image+"<text x=\"64\" y=\""+(height+35)+"\" font-size=\"14\" fill=\"#758296\">Ycscite</text></g>";
  String branded=plain.substring(0,close)+mark+plain.substring(close);
  Files.writeString(variant(out,false),plain);Files.writeString(variant(out,true),branded);Files.writeString(out,logo?branded:plain);
 }
}
