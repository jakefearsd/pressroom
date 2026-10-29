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

package com.wikantik.pressroom.ui.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;


public class PressroomRememberMeAuthenticationProvider extends RememberMeAuthenticationProvider {
    private static final Logger log = LoggerFactory.getLogger(PressroomRememberMeAuthenticationProvider.class);


    public PressroomRememberMeAuthenticationProvider() {
        
        super(WebloggerConfig.getProperty("rememberme.key", "springRocks"));
        
        log.debug("initializing: PressroomRememberMeAuthenticationProvider");

        if (WebloggerConfig.getBooleanProperty("rememberme.enabled") && "springRocks".equals(getKey())) {
            throw new RuntimeException(
                "If remember-me is to be enabled, rememberme.key must be specified in the roller " +
                "properties file. Make sure it is a secret and make sure it is NOT springRocks");
        }

        log.debug("initialized: PressroomRememberMeAuthenticationProvider with key: {}", getKey());
    }
}


