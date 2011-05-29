/*
 * Encoding: UTF-8
 * Licence:  GPL v2 or later
 * Author:   Ole Jørgen Brønner <olejorgen@yahoo.no>, 2011
 */

package org.openstreetmap.josm.plugins.parallelway;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class ParallelWayPlugin extends Plugin {

    public ParallelWayPlugin(PluginInformation info) {
        super(info);
    }
    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (oldFrame == null && newFrame != null) {
            Main.map.addMapMode(new IconToggleButton(new ParallelWayMode(Main.map)));
        }
    }
}
