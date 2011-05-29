/*
 * Encoding: UTF-8
 * Licence:  GPL v2 or later
 * Author:   Ole Jørgen Brønner <olejorgen@yahoo.no>, 2011
 */

package org.openstreetmap.josm.plugins.parallelway;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.CombineWayAction;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

//// TODO: (list below)
/* == Functionality ==
 *
 * 1. Use selected nodes as split points for the selected ways.
 *
 * The ways containing the selected nodes will be split and only the "inner"
 * parts will be copied
 *
 * 2. Enter exact offset
 *
 * 3. Improve snapping
 *
 * Need at least a setting for step length
 *
 * 4. Visual cues? Highlight source path, draw offset line, etc?
 *
 * 5. Cursors (Half-done)
 *
 * 6. (long term) Parallelize and adjust offsets of existing ways
 *
 * == Code quality ==
 *
 * a) The mode, flags, and modifiers might be updated more than necessary.
 *
 * Not a performance problem, but better if they where more centralized
 *
 * b) Extract generic MapMode services into a super class and/or utility class
 *
 * c) Maybe better to simply draw our own source way highlighting?
 *
 * Current code doesn't not take into account that ways might been highlighted
 * by other than us. Don't think that situation should ever happen though.
 */

/**
 * MapMode for making parallel ways.
 *
 * All calculations are done in projected coordinates.
 *
 * @author Ole Jørgen Brønner (olejorgenb)
 */
public class ParallelWayMode extends MapMode implements AWTEventListener, MapViewPaintable {

    private static final long serialVersionUID = 1L;

    private enum Mode {
        dragging, normal
    }

    //// Preferences and flags
    // See updateModeLocalPreferences for defaults
    private Mode mode;
    private boolean copyTags;
    private boolean copyTagsDefault;

    private boolean snap;
    private boolean snapDefault;

    private double snapThreshold;

    private ModifiersSpec snapModifierCombo;
    private ModifiersSpec copyTagsModifierCombo;
    private ModifiersSpec addToSelectionModifierCombo;
    private ModifiersSpec toggleSelectedModifierCombo;
    private ModifiersSpec setSelectedModifierCombo;

    private int initialMoveDelay;

    private final MapView mv;

    private boolean ctrl;
    private boolean alt;
    private boolean shift;

    // Mouse tracking state
    private Point mousePressedPos;
    private boolean mouseIsDown;
    private long mousePressedTime;
    private boolean mouseHasBeenDragged;

    private WaySegment referenceSegment;
    private ParallelWays pWays;
    LinkedHashSet<Way> sourceWays;
    private EastNorth helperLineStart;
    private EastNorth helperLineEnd;

    public ParallelWayMode(MapFrame mapFrame) {
        super(tr("Parallel"), "parallel", tr("Makes a paralell copy of the selected way(s)"), Shortcut
                .registerShortcut("mapmode:parallel", tr("Mode: {0}", tr("Parallel")), KeyEvent.VK_P,
                        Shortcut.GROUP_EDIT, Shortcut.SHIFT_DEFAULT), mapFrame, ImageProvider.getCursor("normal",
                        "selection"));
        putValue("help", ht("/Action/Parallel"));
        mv = mapFrame.mapView;
        updateModeLocalPreferences();
    }

    @Override
    public void enterMode() {
        // super.enterMode() updates the status line and cursor so we need our state to be set correctly
        setMode(Mode.normal);
        pWays = null;
        updateAllPreferences(); // All default values should've been set now

        super.enterMode();

        mv.addMouseListener(this);
        mv.addMouseMotionListener(this);
        mv.addTemporaryLayer(this);

        //// Needed to update the mouse cursor if modifiers are changed when the mouse is motionless
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.KEY_EVENT_MASK);
        } catch (SecurityException ex) {
        }
        sourceWays = new LinkedHashSet<Way>(getCurrentDataSet().getSelectedWays());
        for (Way w : sourceWays) {
            w.setHighlighted(true);
        }
    }

    @Override
    public void exitMode() {
        super.exitMode();
        mv.removeMouseListener(this);
        mv.removeMouseMotionListener(this);
        mv.removeTemporaryLayer(this);
        Main.map.statusLine.setDist(-1);
        Main.map.statusLine.repaint();
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        } catch (SecurityException ex) {
        }
        removeWayHighlighting(sourceWays);
        pWays = null;
        sourceWays = null;
        referenceSegment = null;
    }

    @Override
    public String getModeHelpText() {
        // TODO: add more detailed feedback based on modifier state.
        // TODO: dynamic messages based on preferences. (Could be problematic translation wise)
        switch (mode) {
        case normal:
            return tr("Select ways as in Select mode. Drag selected ways or a single way to create a parallel copy (Alt toggles tag preservation)");
        case dragging:
            return tr("Hold Ctrl to toggle snapping");
        }
        return ""; // impossible ..
    }

    // Separated due to "race condition" between default values
    private void updateAllPreferences() {
        updateModeLocalPreferences();
        // @formatter:off
        initialMoveDelay = Main.pref.getInteger("edit.initial-move-delay", -1 /* default set in owner */);
        // @formatter:on
    }

    private void updateModeLocalPreferences() {
        // @formatter:off
        snapThreshold   = Main.pref.getDouble (prefKey("snap-threshold"), 0.35);
        snapDefault     = Main.pref.getBoolean(prefKey("snap-default"),      true);
        copyTagsDefault = Main.pref.getBoolean(prefKey("copy-tags-default"), true);

        snapModifierCombo           = new ModifiersSpec(getStringPref("snap-modifier-combo",             "?sC"));
        copyTagsModifierCombo       = new ModifiersSpec(getStringPref("copy-tags-modifier-combo",        "As?"));
        addToSelectionModifierCombo = new ModifiersSpec(getStringPref("add-to-selection-modifier-combo", "aSc"));
        toggleSelectedModifierCombo = new ModifiersSpec(getStringPref("toggle-selection-modifier-combo", "asC"));
        setSelectedModifierCombo    = new ModifiersSpec(getStringPref("set-selection-modifier-combo",    "asc"));
        // @formatter:on
    }

    @Override
    public boolean layerIsSupported(Layer layer) {
        return layer instanceof OsmDataLayer;
    }

    @Override
    public void eventDispatched(AWTEvent e) {
        if (Main.map == null || mv == null || !mv.isActiveLayerDrawable())
            return;

        // Should only get InputEvents due to the mask in enterMode
        if (updateModifiersState((InputEvent) e)) {
            updateStatusLine();
            updateCursor();
        }
    }

    private boolean updateModifiersState(InputEvent e) {
        boolean oldAlt = alt, oldShift = shift, oldCtrl = ctrl;
        alt = (e.getModifiers() & (ActionEvent.ALT_MASK | InputEvent.ALT_GRAPH_MASK)) != 0;
        ctrl = (e.getModifiers() & ActionEvent.CTRL_MASK) != 0;
        shift = (e.getModifiers() & ActionEvent.SHIFT_MASK) != 0;
        boolean changed = (oldAlt != alt || oldShift != shift || oldCtrl != ctrl);
        return changed;
    }

    private void updateCursor() {
        Cursor newCursor = null;
        switch (mode) {
        case normal:
            if (matchesCurrentModifiers(setSelectedModifierCombo)) {
                newCursor = ImageProvider.getCursor("normal", "selection");
            } else if (matchesCurrentModifiers(addToSelectionModifierCombo)) {
                newCursor = ImageProvider.getCursor("normal", "selection_add_element");
            } else if (matchesCurrentModifiers(toggleSelectedModifierCombo)) {
                newCursor = ImageProvider.getCursor("normal", "selection_toggle_element");
            } else {
                // TODO: set to a cursor indicating an error
            }
            break;
        case dragging:
            if (snap) {
                // TODO: snapping cursor?
                newCursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
            } else {
                newCursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
            }
        }
        if (newCursor != null) {
            mv.setNewCursor(newCursor, this);
        }
    }

    private void setMode(Mode mode) {
        this.mode = mode;
        updateCursor();
        updateStatusLine();
    }

    private boolean isValidModifierCombination() {
        // TODO: implement to give feedback on invalid modifier combination
        return true;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        updateModifiersState(e);
        // Other buttons are off limit, but we still get events.
        if (e.getButton() != MouseEvent.BUTTON1)
            return;

        if (!mv.isActiveLayerVisible())
            return;
        if (!mv.isActiveLayerDrawable())
            return;
        if (!(Boolean) this.getValue("active"))
            return;

        updateFlagsOnlyChangeableOnPress();
        updateFlagsChangeableAlways();

        // Since the created way is left selected, we need to unselect again here
        if (pWays != null && pWays.ways != null) {
            getCurrentDataSet().clearSelection(pWays.ways);
            pWays = null;
        }

        mouseIsDown = true;
        mousePressedPos = e.getPoint();
        mousePressedTime = System.currentTimeMillis();

    }

    @Override
    public void mouseClicked(MouseEvent e) {
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        updateModifiersState(e);
        // Other buttons are off limit, but we still get events.
        if (e.getButton() != MouseEvent.BUTTON1)
            return;
        System.err.println("release");

        if (!mouseHasBeenDragged) {
            // use point from press or click event? (or are these always the same)
            Way nearestWay = mv.getNearestWay(e.getPoint(), OsmPrimitive.isSelectablePredicate);
            if (nearestWay == null) {
                if (matchesCurrentModifiers(setSelectedModifierCombo)) {
                    clearSourceWays();
                }
                resetMouseTrackingState();
                return;
            }
            boolean isSelected = nearestWay.isSelected();
            if (matchesCurrentModifiers(addToSelectionModifierCombo)) {
                if (!isSelected) {
                    addSourceWay(nearestWay);
                }
            } else if (matchesCurrentModifiers(toggleSelectedModifierCombo)) {
                if (isSelected) {
                    removeSourceWay(nearestWay);
                } else {
                    addSourceWay(nearestWay);
                }
            } else if (matchesCurrentModifiers(setSelectedModifierCombo)) {
                clearSourceWays();
                addSourceWay(nearestWay);
            } // else -> invalid modifier combination
        } else if (mode == Mode.dragging) {
            clearSourceWays();
        }

        setMode(Mode.normal);
        resetMouseTrackingState();
        mv.repaint();
    }

    private void removeWayHighlighting(Collection<Way> ways) {
        if (ways == null)
            return;
        for (Way w : ways) {
            w.setHighlighted(false);
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        // WTF.. the event passed here doesn't have button info?
        // Since we get this event from other buttons too, we must check that
        // _BUTTON1_ is down.
        if (!mouseIsDown)
            return;

        boolean modifiersChanged = updateModifiersState(e);
        updateFlagsChangeableAlways();

        if (modifiersChanged) {
            // Since this could be remotely slow, do it conditionally
            updateStatusLine();
            updateCursor();
        }

        if ((System.currentTimeMillis() - mousePressedTime) < initialMoveDelay)
            return;
        // Assuming this event only is emitted when the mouse has moved
        // Setting this after the check above means we tolerate clicks with some movement
        mouseHasBeenDragged = true;

        Point p = e.getPoint();
        if (mode == Mode.normal) {
            // Should we ensure that the copyTags modifiers are still valid?

            // Important to use mouse position from the press, since the drag
            // event can come quite late
            if (!isModifiersValidForDragMode())
                return;
            if (!initParallelWays(mousePressedPos, copyTags)) {
                // TODO: Not ideal feedback. Maybe changing the cursor could be a good mechanism?
                JOptionPane.showMessageDialog(
                        Main.parent,
                        tr("ParallelWayAction\n" +
                                "The ways selected must form a simple branchless path"),
                        tr("Make parallel way error"),
                        JOptionPane.INFORMATION_MESSAGE);
                // The error dialog prevents us from getting the mouseReleased event
                resetMouseTrackingState();
                return;
            }
            setMode(Mode.dragging);
        }

        //// Calculate distance to the reference line
        EastNorth enp = mv.getEastNorth((int) p.getX(), (int) p.getY());
        EastNorth nearestPointOnRefLine = Helpers.closestPointToLine(referenceSegment.getFirstNode().getEastNorth(),
                referenceSegment.getSecondNode().getEastNorth(), enp);

        double d = enp.distance(nearestPointOnRefLine);
        // TODO: abuse of isToTheRightSideOfLine function.
        boolean toTheRight = Geometry.isToTheRightSideOfLine(referenceSegment.getFirstNode(),
                referenceSegment.getFirstNode(), referenceSegment.getSecondNode(), new Node(enp));

        if (snap) {
            // TODO: Very simple snapping
            // - Snap steps and/or threshold relative to the distance?
            long closestWholeUnit = Math.round(d);
            if (Math.abs(closestWholeUnit - d) < snapThreshold) {
                d = closestWholeUnit;
            } else {
                d = closestWholeUnit + Math.signum(closestWholeUnit - d) * -0.5;
            }
        }
        helperLineStart = nearestPointOnRefLine;
        helperLineEnd = enp;
        if (toTheRight) {
            d = -d;
        }
        pWays.changeOffset(d);

        Main.map.statusLine.setDist(Math.abs(d));
        Main.map.statusLine.repaint();
        mv.repaint();
    }

    private boolean matchesCurrentModifiers(ModifiersSpec spec) {
        return spec.matchWithKnown(alt, shift, ctrl);
    }

    // annoying missing basic geometry capabilities..
    static public class Vector {
        public double x, y;

        public Vector(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public Vector(double x1, double y1, double x2, double y2) {
            x = x2 - x1;
            y = y2 - y1;
        }

        public Vector normalize() {
            return null;
        }

        public double length() {
            return Math.sqrt(x * x + y * y);
        }
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        if (mode == Mode.dragging) {
            // sanity checks
            if (Main.map.mapView == null)
                return;

            Stroke refLineStroke = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 10.0f, new float[] {
                    2f, 2f }, 0f);
            g.setStroke(refLineStroke);
            g.setColor(Color.RED);
            Point p1 = mv.getPoint(referenceSegment.getFirstNode().getEastNorth());
            Point p2 = mv.getPoint(referenceSegment.getSecondNode().getEastNorth());
            g.drawLine(p1.x, p1.y, p2.x, p2.y);

            Stroke helpLineStroke = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL);
            g.setStroke(helpLineStroke);
            g.setColor(Color.RED);
            p1 = mv.getPoint(helperLineStart);
            p2 = mv.getPoint(helperLineEnd);
            g.drawLine(p1.x, p1.y, p2.x, p2.y);
        }

    }

    private boolean isModifiersValidForDragMode() {
        return (!alt && !shift && !ctrl) || matchesCurrentModifiers(snapModifierCombo)
                || matchesCurrentModifiers(copyTagsModifierCombo);
    }

    private void updateFlagsOnlyChangeableOnPress() {
        copyTags = copyTagsDefault != matchesCurrentModifiers(copyTagsModifierCombo);
    }

    private void updateFlagsChangeableAlways() {
        snap = snapDefault != matchesCurrentModifiers(snapModifierCombo);
    }

    private void addSourceWay(Way w) {
        assert (sourceWays != null);
        getCurrentDataSet().addSelected(w);
        w.setHighlighted(true);
        sourceWays.add(w);
    }

    private void removeSourceWay(Way w) {
        assert (sourceWays != null);
        getCurrentDataSet().clearSelection(w);
        w.setHighlighted(false);
        sourceWays.remove(w);
    }

    private void clearSourceWays() {
        assert (sourceWays != null);
        if (sourceWays == null)
            return;
        getCurrentDataSet().clearSelection(sourceWays);
        for (Way w : sourceWays) {
            w.setHighlighted(false);
        }
        sourceWays.clear();
    }

    private void resetMouseTrackingState() {
        mouseIsDown = false;
        mousePressedPos = null;
        mouseHasBeenDragged = false;
    }

    // TODO: rename
    private boolean initParallelWays(Point p, boolean copyTags) {
        referenceSegment = mv.getNearestWaySegment(p, Way.isUsablePredicate, true);
        if (referenceSegment == null)
            return false;

        // The collection returned is very inefficient so we collect it in an ArrayList
        // Not sure if the list is iterated multiple times any more...
        if (!sourceWays.contains(referenceSegment.way)) {
            clearSourceWays();
            addSourceWay(referenceSegment.way);
        }

        try {
            int referenceWayIndex = -1;
            int i = 0;
            for (Way w : sourceWays) {
                if (w == referenceSegment.way) {
                    referenceWayIndex = i;
                    break;
                }
            }
            pWays = new ParallelWays(sourceWays, copyTags, referenceWayIndex);
            pWays.commit(null);
            getCurrentDataSet().setSelected(pWays.ways);
            return true;
        } catch (IllegalArgumentException e) {
            //            System.err.println(e);
            pWays = null;
            return false;
        }
    }

    // TODO: 'ParallelPath' better name?
    static final class ParallelWays {
        private final List<Way> ways;
        private final List<Node> sortedNodes;

        private final int nodeCount;

        private final EastNorth[] pts;
        private final EastNorth[] normals;

        public ParallelWays(Collection<Way> sourceWays, boolean copyTags, int refWayIndex) {
            // Possible/sensible to use PrimetiveDeepCopy here?

            //// Make a deep copy of the ways, keeping the copied ways connected
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

        // Draw helper lines instead like DrawAction ExtrudeAction?
        public void commit(DataSet ds) {
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

    static final public class ModifiersSpec {
        static public final int ON = 1, OFF = 0, UNKNOWN = 2;
        public int alt = UNKNOWN;
        public int shift = UNKNOWN;
        public int ctrl = UNKNOWN;

        /**
         *  'A' = Alt, 'S' = Shift, 'C' = Ctrl
         *  Lowercase signifies off and '?' means unknown/optional.
         *  Order is Alt, Shift, Ctrl
         * @param str
         */
        public ModifiersSpec(String str) {
            assert (str.length() == 3);
            char a = str.charAt(0);
            char s = str.charAt(1);
            char c = str.charAt(2);
            // @formatter:off
            alt   = (a == '?' ? UNKNOWN : (a == 'A' ? ON : OFF));
            shift = (s == '?' ? UNKNOWN : (s == 'S' ? ON : OFF));
            ctrl  = (c == '?' ? UNKNOWN : (c == 'C' ? ON : OFF));
            // @formatter:on
        }

        public ModifiersSpec(final int alt, final int shift, final int ctrl) {
            this.alt = alt;
            this.shift = shift;
            this.ctrl = ctrl;
        }

        public boolean matchWithKnown(final int knownAlt, final int knownShift, final int knownCtrl) {
            return match(alt, knownAlt) && match(shift, knownShift) && match(ctrl, knownCtrl);
        }

        public boolean matchWithKnown(final boolean knownAlt, final boolean knownShift, final boolean knownCtrl) {
            return match(alt, knownAlt) && match(shift, knownShift) && match(ctrl, knownCtrl);
        }

        private boolean match(final int a, final int knownValue) {
            assert (knownValue == ON | knownValue == OFF);
            return a == knownValue || a == UNKNOWN;
        }

        private boolean match(final int a, final boolean knownValue) {
            return a == (knownValue ? ON : OFF);
        }
        // does java have built in 3-state support?
    }

    private String prefKey(String subKey) {
        return "edit.make-parallel-way-action." + subKey;
    }

    private String getStringPref(String subKey, String def) {
        return Main.pref.get(prefKey(subKey), def);
    }

    private String getStringPref(String subKey) {
        return getStringPref(subKey, null);
    }
}
