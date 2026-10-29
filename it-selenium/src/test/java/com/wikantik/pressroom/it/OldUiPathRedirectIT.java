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
package com.wikantik.pressroom.it;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.WebDriverRunner.url;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;

/**
 * The admin UI moved from its old path to /pressroom-ui/; the old path keeps
 * answering with a permanent redirect, query string intact. The base URL
 * includes the context path, so one test covers both context paths.
 */
class OldUiPathRedirectIT extends PressroomIT {

    @Test
    void theOldAdminPathLandsOnTheNewOneWithItsQuery() {
        openPath("/pressroom-ui/login.rol?x=1"); // rebrand:keep
        assertEquals(baseUrl() + "/pressroom-ui/login.rol?x=1", url());
        $("#loginForm").should(exist);
    }
}
