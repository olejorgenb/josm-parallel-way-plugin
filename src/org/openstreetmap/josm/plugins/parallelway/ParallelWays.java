/*
 * Encoding: UTF-8
 * Licence:  GPL v2 or later
 * Author:   Ole Jørgen Brønner <olejorgen@yahoo.no>, 2011
 */

package org.openstreetmap.josm.plugins.parallelway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.CombineWayAction;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Geometry;

// ParallelPath better name?
public class ParallelWays {
    final List<Way> ways;
    private final List<Node> sortedNodes;

    private final int nodeCount;

    private final EastNorth[] pts;
    private final EastNorth[] normals;

    public ParallelWays(Collection<Way> sourceWays, boolean copyTags, int refWayIndex) {
        // Possible/sensible to use PrimetiveDeepCopy here?

        //// Make a deep copy of the ways, keeping the copied ways connected
        // TODO: This assumes the first/last nodes of the ways are the only possible shared nodes.
        HashMap<Node, Node> splitNodeMap = new HashMap<Node, Node>(sourceWays.size());
        for (Way w : sourceWays) {
            if (!splitNodeMap.containsKey(w.firstNode())) {
                splitNodeMap.put(w.firstNode(), copyNode(w.firstNode(), copyTags));
            }
            if (!splitNodeMap.containsKey(w.lastNode())) {
                splitNodeMap.put(w.lastNode(), copyNode(w.lastNode(), copyTags));
            }
        }
        ways = new ArrayList<Way>(sourceWays.size());
        for (Way w : sourceWays) {
            Way wCopy = new Way();
            wCopy.addNode(splitNodeMap.get(w.firstNode()));
            for (int i = 1; i < w.getNodesCount() - 1; i++) {
                wCopy.addNode(copyNode(w.getNode(i), copyTags));
            }
            wCopy.addNode(splitNodeMap.get(w.lastNode()));
            if (copyTags) {
                wCopy.setKeys(w.getKeys());
            }
            ways.add(wCopy);
        }
        sourceWays = null; // Ensure that we only use the copies from now

        //// Find a linear ordering of the nodes. Fail if there isn't one.
        CombineWayAction.NodeGraph nodeGraph = CombineWayAction.NodeGraph.createUndirectedGraphFromNodeWays(ways);
        sortedNodes = nodeGraph.buildSpanningPath();
        if (sortedNodes == null)
            throw new IllegalArgumentException("Ways must have spanning path"); // Create a dedicated exception?

        //// Ugly method of ensuring that the offset isn't inverted. I'm sure there is a better and more elegant way, but I'm starting to get sleepy, so I do this for now.
        {
            Way refWay = ways.get(refWayIndex);
            boolean refWayReversed = false;
            if (isClosedPath()) { // Nodes occur more than once in the list
                if (refWay.firstNode() == sortedNodes.get(0) && refWay.lastNode() == sortedNodes.get(0)) {
                    refWayReversed = sortedNodes.get(1) != refWay.getNode(1);
                } else if (refWay.lastNode() == sortedNodes.get(0)) {
                    refWayReversed =
                            sortedNodes.get(sortedNodes.size() - 1) != refWay.getNode(refWay.getNodesCount() - 1);
                } else if (refWay.firstNode() == sortedNodes.get(0)) {
                    refWayReversed = sortedNodes.get(1) != refWay.getNode(1);
                } else {
                    refWayReversed =
                            sortedNodes.indexOf(refWay.firstNode()) > sortedNodes.indexOf(refWay.lastNode());
                }

            } else {
                refWayReversed = sortedNodes.indexOf(refWay.firstNode()) > sortedNodes.indexOf(refWay.lastNode());
            }
            if (refWayReversed) {
                Collections.reverse(sortedNodes); // need to keep the orientation of the reference way.
                System.err.println("reversed!");
            }
        }

        //// Initialize the required parameters. (segment normals, etc.)
        nodeCount = sortedNodes.size();
        pts = new EastNorth[nodeCount];
        normals = new EastNorth[nodeCount - 1];
        int i = 0;
        for (Node n : sortedNodes) {
            EastNorth t = n.getEastNorth();
            pts[i] = t;
            i++;
        }
        for (i = 0; i < nodeCount - 1; i++) {
            double dx = pts[i + 1].getX() - pts[i].getX();
            double dy = pts[i + 1].getY() - pts[i].getY();
            double len = Math.sqrt(dx * dx + dy * dy);
            normals[i] = new EastNorth(-dy / len, dx / len);
        }
    }

    public boolean isClosedPath() {
        return sortedNodes.get(0) == sortedNodes.get(sortedNodes.size() - 1);
    }

    /**
     * Offsets the way(s) d units. Positive d means to the left (relative to the reference way)
     * @param d
     */
    public void changeOffset(double d) {
        //// This is the core algorithm:
        /* 1. Calculate a parallel line, offset by 'd', to each segment in
         *    the path
         * 2. Find the intersection of lines belonging to neighboring
         *    segments. These become the new node positions
         * 3. Do some special casing for closed paths
         *
         * Simple and probably not even close to optimal performance wise
         */

        EastNorth[] ppts = new EastNorth[nodeCount];

        EastNorth prevA = add(pts[0], mul(normals[0], d));
        EastNorth prevB = add(pts[1], mul(normals[0], d));
        for (int i = 1; i < nodeCount - 1; i++) {
            EastNorth A = add(pts[i], mul(normals[i], d));
            EastNorth B = add(pts[i + 1], mul(normals[i], d));
            if (Geometry.segmentsParallel(A, B, prevA, prevB)) {
                ppts[i] = A;
            } else {
                ppts[i] = Geometry.getLineLineIntersection(A, B, prevA, prevB);
            }
            prevA = A;
            prevB = B;
        }
        if (isClosedPath()) {
            EastNorth A = add(pts[0], mul(normals[0], d));
            EastNorth B = add(pts[1], mul(normals[0], d));
            if (Geometry.segmentsParallel(A, B, prevA, prevB)) {
                ppts[0] = A;
            } else {
                ppts[0] = Geometry.getLineLineIntersection(A, B, prevA, prevB);
            }
            ppts[nodeCount - 1] = ppts[0];
        } else {
            ppts[0] = add(pts[0], mul(normals[0], d));
            ppts[nodeCount - 1] = add(pts[nodeCount - 1], mul(normals[nodeCount - 2], d));
        }

        for (int i = 0; i < nodeCount; i++) {
            sortedNodes.get(i).setEastNorth(ppts[i]);
        }
    }

    public void commit() {
        SequenceCommand undoCommand = new SequenceCommand("Make parallel way(s)", makeAddWayAndNodesCommandList());
        Main.main.undoRedo.add(undoCommand);
    }

    private List<Command> makeAddWayAndNodesCommandList() {
        ArrayList<Command> commands = new ArrayList<Command>(sortedNodes.size() + ways.size());
        for (int i = 0; i < sortedNodes.size() - 1; i++) {
            commands.add(new AddCommand(sortedNodes.get(i)));
        }
        if (!isClosedPath()) {
            commands.add(new AddCommand(sortedNodes.get(sortedNodes.size() - 1)));
        }
        for (Way w : ways) {
            commands.add(new AddCommand(w));
        }
        return commands;
    }

    static private Node copyNode(Node source, boolean copyTags) {
        if (copyTags)
            return new Node(source, true);
        else {
            Node n = new Node();
            n.setCoor(source.getCoor());
            return n;
        }
    }

    // We need either a dedicated vector type, or operations such as these
    // added to EastNorth...
    static private EastNorth mul(EastNorth en, double f) {
        return new EastNorth(en.getX() * f, en.getY() * f);
    }

    static private EastNorth add(EastNorth a, EastNorth b) {
        return new EastNorth(a.east() + b.east(), a.north() + b.north());
    }
}