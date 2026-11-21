/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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

package com.wikantik.pressroom.ui.core.util.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * A Menu of MenuTab objects, built only by {@link MenuHelper}. A JavaBean, not
 * a record: {@code weblog.vm}'s menu macros and the JSPs read {@code tabs},
 * {@code items}, {@code key} and {@code action} through getters, and Velocity
 * prints a record accessor's reference as literal text.
 */
public class Menu {

    /** Which screens a menu's actions belong to, so a client can build a link without guessing. */
    public enum Surface {
        /** The weblog-scoped authoring screens ({@code editor} menu). */
        AUTHORING,
        /** The site-wide administration screens ({@code admin} menu). */
        ADMIN
    }

    private final Surface surface;
    private final List<MenuTab> tabs = new ArrayList<>();

    Menu(Surface surface) {
        this.surface = surface;
    }

    void addTab(MenuTab tab) {
        this.tabs.add(tab);
    }

    public List<MenuTab> getTabs() {
        return Collections.unmodifiableList(tabs);
    }

    public Surface getSurface() {
        return surface;
    }

}
