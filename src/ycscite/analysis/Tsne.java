package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;
import java.util.*;

/** Exact t-SNE objective on weighted graph shortest-path distances, not text embeddings.
 * Conditional affinities and gradient follow van der Maaten & Hinton, JMLR 9 (2008),
 * equations 1, 4 and 5. Graph distance is this application's explicit input model.
 */
public final class Tsne {
    private record Neighbor(int index,double distance){}
    public static double[][] embed(Network graph,int iterations){return embed(graph,iterations,30,42);}
    public static double[][] embed(Network graph,int iterations,double perplexity,long seed){
        List<GraphNode> nodes=new ArrayList<>(graph.nodeCollection());int n=nodes.size();
        if(n==0)return new double[0][0];if(n==1)return new double[][]{{0,0}};
        double[][] distance=distances(graph,nodes),p=affinities(distance,Math.clamp(perplexity,1,n-1));
        Random random=new Random(seed);double[][] y=new double[n][2],velocity=new double[n][2],gains=new double[n][2],previous=new double[n][2],gradient=new double[n][2],numerator=new double[n][n];
        for(int i=0;i<n;i++)for(int d=0;d<2;d++){y[i][d]=random.nextGaussian()*1e-4;gains[i][d]=1;}
        double learningRate=Math.max(10,n/12.0);int exaggerationSteps=Math.min(100,iterations/3);
        for(int iter=0;iter<iterations;iter++){
            double total=0;for(int i=0;i<n;i++){Arrays.fill(gradient[i],0);for(int j=i+1;j<n;j++){double dx=y[i][0]-y[j][0],dy=y[i][1]-y[j][1];double q=1/(1+dx*dx+dy*dy);numerator[i][j]=numerator[j][i]=q;total+=2*q;}}
            double exaggeration=iter<exaggerationSteps?4:1;
            for(int i=0;i<n;i++)for(int j=i+1;j<n;j++){double q=numerator[i][j];double factor=4*(exaggeration*p[i][j]-q/Math.max(1e-20,total))*q;for(int d=0;d<2;d++){double force=factor*(y[i][d]-y[j][d]);gradient[i][d]+=force;gradient[j][d]-=force;}}
            double[] mean=new double[2];double momentum=iter<exaggerationSteps?.5:.8;
            // All gradients use one immutable coordinate snapshot; update only after the full pass.
            for(int i=0;i<n;i++)for(int d=0;d<2;d++){double grad=gradient[i][d];gains[i][d]=Math.signum(grad)!=Math.signum(previous[i][d])?Math.min(5,gains[i][d]+.2):Math.max(.01,gains[i][d]*.8);velocity[i][d]=momentum*velocity[i][d]-learningRate*gains[i][d]*grad;y[i][d]+=velocity[i][d];previous[i][d]=grad;mean[d]+=y[i][d]/n;}
            for(var point:y)for(int d=0;d<2;d++)point[d]-=mean[d];
        }
        // Normalize once, using a shared scale to preserve the optimized aspect ratio.
        double maximum=0;for(var point:y)for(double value:point)maximum=Math.max(maximum,Math.abs(value));
        if(maximum>0)for(var point:y)for(int d=0;d<2;d++)point[d]/=maximum;
        return y;
    }
    private static double[][] distances(Network graph,List<GraphNode> nodes){
        int n=nodes.size();Map<GraphNode,Integer> index=new HashMap<>();List<List<Neighbor>> adjacent=new ArrayList<>();
        for(int i=0;i<n;i++){index.put(nodes.get(i),i);adjacent.add(new ArrayList<>());}
        for(var edge:graph.edges){int a=index.get(edge.a),b=index.get(edge.b);double distance=1/Math.sqrt(Math.max(1,edge.weight));adjacent.get(a).add(new Neighbor(b,distance));adjacent.get(b).add(new Neighbor(a,distance));}
        double[][] result=new double[n][n];double max=1;
        for(int start=0;start<n;start++){
            double[] row=result[start];Arrays.fill(row,Double.POSITIVE_INFINITY);row[start]=0;
            var queue=new PriorityQueue<Neighbor>(Comparator.comparingDouble(Neighbor::distance));queue.add(new Neighbor(start,0));
            while(!queue.isEmpty()){var current=queue.remove();if(current.distance>row[current.index])continue;for(var edge:adjacent.get(current.index)){double next=current.distance+edge.distance;if(next<row[edge.index]){row[edge.index]=next;queue.add(new Neighbor(edge.index,next));}}}
            for(double value:row)if(Double.isFinite(value))max=Math.max(max,value);
        }
        for(var row:result)for(int j=0;j<n;j++)if(!Double.isFinite(row[j]))row[j]=2*max;
        return result;
    }
    private static double[][] affinities(double[][] distances,double perplexity){
        int n=distances.length;double[][] conditional=new double[n][n];double target=Math.log(perplexity);
        for(int i=0;i<n;i++){
            double beta=1,lower=0,upper=Double.POSITIVE_INFINITY,min=Double.POSITIVE_INFINITY;for(int j=0;j<n;j++)if(i!=j)min=Math.min(min,distances[i][j]*distances[i][j]);
            for(int step=0;step<60;step++){
                double sum=0,weighted=0;for(int j=0;j<n;j++)if(i!=j){double d=distances[i][j]*distances[i][j]-min;double value=Math.exp(-beta*d);conditional[i][j]=value;sum+=value;weighted+=d*value;}
                double entropy=Math.log(Math.max(sum,1e-300))+beta*weighted/Math.max(sum,1e-300),difference=entropy-target;
                for(int j=0;j<n;j++)conditional[i][j]/=Math.max(sum,1e-300);
                if(Math.abs(difference)<1e-6)break;
                if(difference>0){lower=beta;beta=Double.isInfinite(upper)?2*beta:(beta+upper)/2;}else{upper=beta;beta=(beta+lower)/2;}
            }
        }
        double[][] p=new double[n][n];for(int i=0;i<n;i++)for(int j=i+1;j<n;j++)p[i][j]=p[j][i]=Math.max(1e-12,(conditional[i][j]+conditional[j][i])/(2*n));return p;
    }
}
