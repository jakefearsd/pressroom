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

/**
 * A latitude/longitude box that holds every point within a distance of a
 * place: the cheap first cut a query makes before the exact distance is
 * worked out ({@link RelatedPicker}). Degrees. {@code west > east} means the
 * box crosses the antimeridian: longitudes from {@code west} up to 180 and
 * from -180 up to {@code east}.
 */
public record GeoBox(double south, double north, double west, double east) {

    /** Kilometres per degree of latitude (and of longitude at the equator). */
    private static final double KM_PER_DEGREE = 111.195;

    private static final double MAX_LATITUDE = 90.0;

    private static final double MAX_LONGITUDE = 180.0;

    /** A little slack, so rounding never leaves a point in range outside the box. */
    private static final double SLACK = 1.01;

    /** The box around a place that holds everything within {@code km} of it. */
    public static GeoBox around(double latitude, double longitude, double km) {
        double reach = km * SLACK;
        double south = latitude - reach / KM_PER_DEGREE;
        double north = latitude + reach / KM_PER_DEGREE;
        if (south <= -MAX_LATITUDE || north >= MAX_LATITUDE) {
            // the circle holds a pole, so it holds every longitude
            return new GeoBox(Math.max(south, -MAX_LATITUDE), Math.min(north, MAX_LATITUDE),
                    -MAX_LONGITUDE, MAX_LONGITUDE);
        }
        // the meridians the circle touches, which lie farther apart than its width at this latitude
        double span = Math.toDegrees(Math.asin(
                Math.sin(Math.toRadians(reach / KM_PER_DEGREE)) / Math.cos(Math.toRadians(latitude))));
        double west = longitude - span;
        double east = longitude + span;
        return new GeoBox(south, north,
                west < -MAX_LONGITUDE ? west + 2 * MAX_LONGITUDE : west,
                east > MAX_LONGITUDE ? east - 2 * MAX_LONGITUDE : east);
    }

    /** True when the box runs across longitude 180. */
    public boolean crossesAntimeridian() {
        return west > east;
    }

    /** True when the point is inside the box. */
    public boolean contains(double latitude, double longitude) {
        if (latitude < south || latitude > north) {
            return false;
        }
        return crossesAntimeridian()
                ? longitude >= west || longitude <= east
                : longitude >= west && longitude <= east;
    }
}
