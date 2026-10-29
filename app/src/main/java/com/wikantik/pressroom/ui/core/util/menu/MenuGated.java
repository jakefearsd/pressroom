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

import java.util.List;

/**
 * The visibility gate that a menu tab and a menu tab item both carry.
 *
 * <p>{@link ParsedTab} and {@link ParsedTabItem} have always declared the same
 * four accessors and been filtered by the same three rules, but with no type
 * saying so the filtering was written twice in {@code MenuHelper.buildMenu} --
 * which is most of why that method reached cyclomatic complexity 30, and why
 * the two copies had drifted apart on how a failed permission lookup is
 * handled.
 */
interface MenuGated {

    /** Property that must be on for this element to appear, or null. */
    String getEnabledProperty();

    /** Property that must be off for this element to appear, or null. */
    String getDisabledProperty();

    /** Global permission actions the user must hold, or null/empty for none. */
    List<String> getGlobalPermissionActions();

    /** Weblog permission actions the user must hold, or null/empty for none. */
    List<String> getWeblogPermissionActions();
}
