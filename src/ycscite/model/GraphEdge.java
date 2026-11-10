package ycscite.model;

/** Undirected weighted edge. */
public final class GraphEdge {
    public final GraphNode a;
    public final GraphNode b;
    public int weight;
    /** First retained year of this link, or 0 for imports without dates. */
    public int firstYear;

    public GraphEdge(GraphNode a, GraphNode b, int weight) {
        this.a = a;
        this.b = b;
        this.weight = weight;
    }

    public GraphNode other(GraphNode n) { return n == a ? b : a; }
}
