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
 * Tab in a Menu, built only by {@link MenuHelper}; read through getters (see {@link Menu}).
 */
public class MenuTab {

    private final String key;
    private String action;
    private boolean selected;
    private final List<MenuTabItem> items = new ArrayList<>();

    MenuTab(String key) {
        this.key = key;
    }

    void addItem(MenuTabItem item) {
        this.items.add(item);
    }

    public String getKey() {
        return key;
    }

    public String getAction() {
        return action;
    }

    void withAction(String url) {
        this.action = url;
    }

    public boolean isSelected() {
        return selected;
    }

    void select() {
        this.selected = true;
    }

    public List<MenuTabItem> getItems() {
        return Collections.unmodifiableList(items);
    }

}
