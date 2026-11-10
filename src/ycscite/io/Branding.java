package ycscite.io;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
/** Both downloads share exactly the same data pixels. Branding is confined to an added footer. */
public final class Branding {
 private Branding(){}
 public static void svgVariants(Path svg,boolean showLogo,int width,int height)throws Exception{
  String source=Files.readString(svg);int footer=60;
  String clean=source.replace("height=\""+height+"\"","height=\""+(height+footer)+"\"")
   .replace("viewBox=\"0 0 "+width+" "+height+"\"","viewBox=\"0 0 "+width+" "+(height+footer)+"\"");
  String mark="";
  try(var stream=Branding.class.getResourceAsStream("/ycscite-logo.png")){
   if(stream!=null)mark="<image x=\"8\" y=\""+(height+2)+"\" width=\"50\" height=\"50\" href=\"data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(stream.readAllBytes())+"\"/><text x=\"64\" y=\""+(height+35)+"\" font-size=\"14\" fill=\"#758296\">Ycscite</text>";
  }
  String branded=clean.replace("</svg>",mark+"</svg>");
  Files.createDirectories(svg.getParent().resolve("no-logo"));Files.createDirectories(svg.getParent().resolve("with-logo"));
  Files.writeString(svg.getParent().resolve("no-logo").resolve(svg.getFileName()),clean);
  Files.writeString(svg.getParent().resolve("with-logo").resolve(svg.getFileName()),branded);
  Files.writeString(svg,showLogo?branded:clean);
 }
 public static void variants(Path png,boolean showLogo)throws Exception{
  BufferedImage source=ImageIO.read(png.toFile());
  if(source==null)throw new java.io.IOException("Unreadable image: "+png);
  int footer=Math.max(32,source.getWidth()/55);
  BufferedImage clean=new BufferedImage(source.getWidth(),source.getHeight()+footer,BufferedImage.TYPE_INT_RGB);
  Graphics2D g=clean.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,clean.getWidth(),clean.getHeight());g.drawImage(source,0,0,null);g.dispose();source.flush();
  Path cleanDir=Files.createDirectories(png.getParent().resolve("no-logo"));
  ImageIO.write(clean,"png",cleanDir.resolve(png.getFileName()).toFile());
  BufferedImage branded=new BufferedImage(clean.getWidth(),clean.getHeight(),BufferedImage.TYPE_INT_RGB);
  g=branded.createGraphics();g.drawImage(clean,0,0,null);
  try(var stream=Branding.class.getResourceAsStream("/ycscite-logo.png")){
   if(stream!=null){var logo=ImageIO.read(stream);int size=footer-4;g.drawImage(logo,9,clean.getHeight()-footer+2,size,size,null);}
  }
  g.setColor(new Color(112,125,140));g.setFont(new Font("SansSerif",Font.PLAIN,Math.max(10,footer/4)));
  g.drawString("Ycscite",footer+12,branded.getHeight()-footer/3);g.dispose();
  Path brandedDir=Files.createDirectories(png.getParent().resolve("with-logo"));
  ImageIO.write(branded,"png",brandedDir.resolve(png.getFileName()).toFile());
  ImageIO.write(showLogo?branded:clean,"png",png.toFile());clean.flush();branded.flush();
 }
}
