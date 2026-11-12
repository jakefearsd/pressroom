/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package com.wikantik.pressroom.business.related;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The box a cross-blog Nearby query is bounded by (0.9.1 spec, criterion 7):
 * it must hold every point in range, or the query hides a neighbour, and
 * little else, or the row limit is spent on posts too far away.
 */
class GeoBoxTest {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** The point {@code km} from a place on a bearing: {latitude, longitude}. */
    private static double[] destination(double lat, double lng, double bearingDegrees, double km) {
        double d = km / EARTH_RADIUS_KM;
        double bearing = Math.toRadians(bearingDegrees);
        double lat1 = Math.toRadians(lat);
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(bearing));
        double lng2 = Math.toRadians(lng) + Math.atan2(Math.sin(bearing) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
        double degrees = Math.toDegrees(lng2);
        return new double[] {Math.toDegrees(lat2), ((degrees + 540) % 360) - 180};
    }

    private static void assertHoldsEverythingInRange(double lat, double lng) {
        GeoBox box = GeoBox.around(lat, lng, 80);
        for (int bearing = 0; bearing < 360; bearing += 5) {
            for (double km : new double[] {1, 40, 79.9}) {
                double[] point = destination(lat, lng, bearing, km);
                assertTrue(box.contains(point[0], point[1]), "(" + lat + "," + lng + ") bearing " + bearing
                        + " at " + km + " km: " + point[0] + "," + point[1] + " outside " + box);
            }
        }
    }

    @Test
    void holdsEveryPointWithinTheDistance() {
        assertHoldsEverythingInRange(38.874, -121.133);
        assertHoldsEverythingInRange(0, 0);
        assertHoldsEverythingInRange(-54.8, -68.3);
        assertHoldsEverythingInRange(69.65, 18.96);
    }

    @Test
    void holdsLittleBeyondTheDistance() {
        GeoBox box = GeoBox.around(38.874, -121.133, 80);
        for (int bearing = 0; bearing < 360; bearing += 5) {
            double[] point = destination(38.874, -121.133, bearing, 125);
            assertFalse(box.contains(point[0], point[1]), "bearing " + bearing + " at 125 km inside " + box);
        }
        assertFalse(box.crossesAntimeridian());
    }

    @Test
    void crossesTheAntimeridianAsTwoRuns() {
        GeoBox box = GeoBox.around(-17.7, 179.9, 80);
        assertTrue(box.crossesAntimeridian(), box.toString());
        assertTrue(box.contains(-17.7, -179.9), "just across the line");
        assertTrue(box.contains(-17.7, 179.5));
        assertFalse(box.contains(-17.7, 0));
        assertFalse(box.contains(-17.7, 170));
        assertHoldsEverythingInRange(-17.7, 179.9);
        assertHoldsEverythingInRange(-17.7, -179.9);
    }

    @Test
    void nearAPoleTakesEveryLongitudeAndStopsAtThePole() {
        GeoBox box = GeoBox.around(89.9, 10, 80);
        assertEquals(90.0, box.north());
        assertEquals(-180.0, box.west());
        assertEquals(180.0, box.east());
        assertTrue(box.contains(89.95, -170));
        assertFalse(box.contains(80, 10));
        assertEquals(-90.0, GeoBox.around(-89.9, 10, 80).south());
    }
}
