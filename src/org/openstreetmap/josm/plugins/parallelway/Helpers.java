/*
 * Encoding: UTF-8
 * Licence:  GPL v2 or later
 * Author:   Ole Jørgen Brønner <olejorgen@yahoo.no>, 2011
 */

package org.openstreetmap.josm.plugins.parallelway;

import org.openstreetmap.josm.data.coor.EastNorth;

public class Helpers {
    public static EastNorth closestPointToLine(EastNorth lineP1, EastNorth lineP2, EastNorth point) {
        double ldx = lineP2.getX() - lineP1.getX();
        double ldy = lineP2.getY() - lineP1.getY();

        if (ldx == 0 && ldy == 0) //segment zero length
            return lineP1;

        double pdx = point.getX() - lineP1.getX();
        double pdy = point.getY() - lineP1.getY();

        double offset = (pdx * ldx + pdy * ldy) / (ldx * ldx + ldy * ldy);
        return new EastNorth(lineP1.getX() + ldx * offset, lineP1.getY() + ldy * offset);
    }
}
