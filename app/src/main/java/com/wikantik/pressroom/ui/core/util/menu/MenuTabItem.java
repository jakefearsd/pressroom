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


/**
 * Items in a Tab, built only by {@link MenuHelper}; read through getters (see {@link Menu}).
 */
public class MenuTabItem {

    private final String key;
    private final String action;
    private boolean selected;

    MenuTabItem(String key, String action) {
        this.key = key;
        this.action = action;
    }

    public String getKey() {
        return key;
    }

    public String getAction() {
        return action;
    }

    public boolean isSelected() {
        return selected;
    }

    void select() {
        this.selected = true;
    }

}
