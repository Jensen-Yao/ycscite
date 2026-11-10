package ycscite.io;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.awt.geom.Point2D;
import java.util.*;

/** Seeded disk layout with damped link springs. The seed positions keep small components spread
 * throughout the picture and prevent the force pass from collapsing a hub's entire neighborhood. */
final class ReportNetworkLayout {
    private ReportNetworkLayout() {}

    static Map<GraphNode, Point2D.Double> whole(Network net, boolean collaboration, boolean institution) {
        double rx = collaboration ? (institution ? 363 : 397) : 449;
        double ry = collaboration ? 354 : 359;
        double cx = 695, cy = 418;
        var points = layout(net, new ArrayList<>(net.nodeCollection()), cx, cy, rx, ry,
                collaboration ? .040 : .070, collaboration ? .37 : .47, collaboration ? .097 : .060, 41L);
        // The relaxed positions have no preferred rotation. Keep a consistent page direction
        // for the main pair of labels, even when they change with the input data.
        if (!collaboration) spreadHubs(net, points, cx, cy, rx, ry);
        orient(points, cx, cy, rx, ry, institution ? 2.93 : collaboration ? 2.18 : 1.34, !collaboration);
        return points;
    }

    /** Use physical node extents to reserve room for the important hubs and their labels. */
    private static void spreadHubs(Network net, Map<GraphNode, Point2D.Double> points,
                                   double cx, double cy, double rx, double ry) {
        var nodes = new ArrayList<>(points.keySet());
        int n = nodes.size();
        if (n < 2) return;
        double[] x = new double[n], y = new double[n], dx = new double[n], dy = new double[n], mass = new double[n];
        Map<GraphNode, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            var p = points.get(nodes.get(i));
            x[i] = p.x - cx; y[i] = p.y - cy;
            mass[i] = 1 + Math.min(14, .72 * Math.sqrt(nodes.get(i).weight));
            index.put(nodes.get(i), i);
        }
        int steps = (int)Math.clamp(95_000_000L / n / n, 24L, 210L);
        for (int step = 0; step < steps; step++) {
            Arrays.fill(dx, 0); Arrays.fill(dy, 0);
            for (int i = 0; i < n; i++) for (int j = 0; j < i; j++) {
                double vx = x[i] - x[j], vy = y[i] - y[j];
                double d2 = Math.max(4, vx * vx + vy * vy);
                double f = 40 * mass[i] * mass[j] / (d2 * Math.sqrt(d2));
                f = Math.min(.75, f);
                if (nodes.get(i).weight >= 20 && nodes.get(j).weight >= 20) {
                    double distance = Math.sqrt(d2);
                    double clearance = 46 + 8 * (Math.sqrt(nodes.get(i).weight) + Math.sqrt(nodes.get(j).weight));
                    if (distance < clearance) f += .13 * (clearance / distance - 1);
                }
                dx[i] += f * vx; dy[i] += f * vy;
                dx[j] -= f * vx; dy[j] -= f * vy;
            }
            for (var e : net.edges) {
                int a = index.get(e.a), b = index.get(e.b);
                double vx = x[b] - x[a], vy = y[b] - y[a], d = Math.max(1, Math.hypot(vx, vy));
                double desired = 48 + 4 * (mass[a] + mass[b]);
                double f = .006 * (1 + .3 * Math.log1p(e.weight)) * (1 - desired / d);
                dx[a] += f * vx; dy[a] += f * vy;
                dx[b] -= f * vx; dy[b] -= f * vy;
            }
            for (int i = 0; i < n; i++) {
                double radial = Math.hypot(x[i] / rx, y[i] / ry);
                double boundary = Math.max(0, radial - .91) * .14;
                dx[i] -= x[i] * boundary; dy[i] -= y[i] * boundary;
                double cool = 1 - .7 * step / steps;
                x[i] += Math.clamp(dx[i], -8, 8) * cool;
                y[i] += Math.clamp(dy[i], -8, 8) * cool;
            }
        }
        double bound = .01;
        for (int i = 0; i < n; i++) bound = Math.max(bound, Math.hypot(x[i] / rx, y[i] / ry));
        for (int i = 0; i < n; i++) {
            double radial = Math.hypot(x[i] / rx, y[i] / ry) / bound;
            double expand = Math.pow(Math.max(.03, radial), -.18) / bound;
            points.get(nodes.get(i)).setLocation(cx + x[i] * expand, cy + y[i] * expand);
        }
    }

    static Map<GraphNode, Point2D.Double> cluster(Network net, List<GraphNode> members,
                                                 double x, double y, double rx, double ry, int index) {
        return layout(net, members, x, y, rx, ry, .10, .61, .069, 159L + index);
    }

    private record Link(int a, int b, double length, double pull) {}

    private static void orient(Map<GraphNode, Point2D.Double> points, double cx, double cy,
                               double rx, double ry, double target, boolean placeThirdOnRight) {
        if (points.size() < 3) return;
        var ordered = points.values().iterator();
        var first = ordered.next();
        var second = ordered.next();
        var third = ordered.next();
        double dx = (first.x - second.x) / rx, dy = (first.y - second.y) / ry;
        if (Math.hypot(dx, dy) < .008) return;
        double side = dy * (third.x - (first.x + second.x) / 2) / rx
                - dx * (third.y - (first.y + second.y) / 2) / ry;
        double flip = placeThirdOnRight && side < 0 ? -1 : 1;
        double angle = target - Math.atan2(dy, dx * flip);
        double c = Math.cos(angle), s = Math.sin(angle);
        for (var p : points.values()) {
            double x = (p.x - cx) / rx * flip, y = (p.y - cy) / ry;
            p.setLocation(cx + rx * (c * x - s * y), cy + ry * (s * x + c * y));
        }
    }

    private static Map<GraphNode, Point2D.Double> layout(Network net, List<GraphNode> input, double cx, double cy,
                                                         double rx, double ry, double pull, double rest, double anchor,
                                                         long seed) {
        List<GraphNode> nodes = input.stream().sorted(Comparator.comparingInt((GraphNode n) -> n.weight)
                .reversed().thenComparing(n -> n.id)).toList();
        Map<GraphNode, Point2D.Double> result = new LinkedHashMap<>();
        int n = nodes.size();
        if (n == 0) return result;
        if (n == 1) { result.put(nodes.getFirst(), new Point2D.Double(cx, cy)); return result; }
        Random random = new Random(seed);
        double[] x = new double[n], y = new double[n], homeX = new double[n], homeY = new double[n];
        double minR = Math.min(rx, ry);
        double gap = Math.min(17, 1.02 * minR / Math.sqrt(n));
        double seedGap = gap * gap * .64;
        Map<GraphNode, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(nodes.get(i), i);
            for (int tries = 0; tries < 32; tries++) {
                double t = random.nextDouble() * 2 * Math.PI;
                double radius = Math.sqrt(random.nextDouble()) * (i < 4 ? .77 : 1);
                x[i] = rx * Math.cos(t) * radius;
                y[i] = ry * Math.sin(t) * radius;
                boolean free = true;
                for (int j = 0; j < i; j++) {
                    double dx = x[i] - x[j], dy = y[i] - y[j];
                    if (dx * dx + dy * dy < seedGap) { free = false; break; }
                }
                if (free) break;
            }
            homeX[i] = x[i];
            homeY[i] = y[i];
        }
        List<Link> links = new ArrayList<>();
        int[] degree = new int[n];
        for (var e : net.edges) {
            Integer a = index.get(e.a), b = index.get(e.b);
            if (a == null || b == null) continue;
            double initial = Math.hypot(x[a] - x[b], y[a] - y[b]);
            double length = minR * rest / Math.pow(Math.max(e.weight, 1), .20) + .16 * initial;
            links.add(new Link(a, b, length, pull * (1 + .32 * Math.log1p(Math.max(e.weight, 1)))));
            degree[a]++;
            degree[b]++;
        }
        double[] dx = new double[n], dy = new double[n];
        int steps = (int)Math.clamp(22_000_000L / n / n, 6L, 72L);
        for (int step = 0; step < steps; step++) {
            for (int i = 0; i < n; i++) {
                dx[i] = (homeX[i] - x[i]) * anchor;
                dy[i] = (homeY[i] - y[i]) * anchor;
            }
            for (int i = 0; i < n; i++) for (int j = 0; j < i; j++) {
                double vx = x[i] - x[j], vy = y[i] - y[j], d2 = vx * vx + vy * vy;
                if (d2 >= gap * gap || d2 < 1e-10) continue;
                double f = .27 * (gap / Math.sqrt(d2) - 1);
                dx[i] += f * vx; dy[i] += f * vy;
                dx[j] -= f * vx; dy[j] -= f * vy;
            }
            for (Link link : links) {
                int a = link.a, b = link.b;
                double vx = x[b] - x[a], vy = y[b] - y[a];
                double d = Math.max(.0001, Math.hypot(vx, vy));
                double f = link.pull * (1 - link.length / d);
                dx[a] += f * vx; dy[a] += f * vy;
                dx[b] -= f * vx; dy[b] -= f * vy;
            }
            double temp = 1.0 - .78 * step / steps;
            for (int i = 0; i < n; i++) {
                double damping = Math.max(1, Math.sqrt(degree[i]) * .86);
                x[i] += Math.clamp(dx[i] / damping, -14, 14) * temp;
                y[i] += Math.clamp(dy[i] / damping, -14, 14) * temp;
                double radial = Math.hypot(x[i] / rx, y[i] / ry);
                if (radial > 1) { x[i] /= radial; y[i] /= radial; }
            }
        }
        for (int i = 0; i < n; i++) result.put(nodes.get(i), new Point2D.Double(cx + x[i], cy + y[i]));
        return result;
    }
}
