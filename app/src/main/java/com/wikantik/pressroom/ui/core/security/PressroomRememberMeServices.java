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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


public class PressroomRememberMeServices extends TokenBasedRememberMeServices {

    private static final Logger log = LoggerFactory.getLogger(PressroomRememberMeServices.class);

    public PressroomRememberMeServices(UserDetailsService userDetailsService) {
        
        super(WebloggerConfig.getProperty("rememberme.key", "springRocks"), userDetailsService);
        
        log.debug("initializing: PressroomRememberMeServices");

        if (WebloggerConfig.getBooleanProperty("rememberme.enabled") && "springRocks".equals(getKey())) {
            throw new RuntimeException(
                "If remember-me is to be enabled, rememberme.key must be specified in the pressroom " +
                    "properties file. Make sure it is a secret and make sure it is NOT springRocks");
        }

        log.debug("initialized: PressroomRememberMeServices with key");
    }

    /**
     * Calculates the digital signature to be put in the cookie. Default value is
     * SHA-512 ("username:tokenExpiryTime:password:key")
     */
    @Override
    protected String makeTokenSignature(long tokenExpiryTime, String username, String password) {

        String data = username + ":" + tokenExpiryTime + ":" + password + ":" + getKey();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required by Spec.", e);
        }

        return new String(Hex.encode(digest.digest(data.getBytes(StandardCharsets.UTF_8))));
    }
}
