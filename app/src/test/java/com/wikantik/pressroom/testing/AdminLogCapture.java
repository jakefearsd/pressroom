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
package com.wikantik.pressroom.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/** Captures the {@code pressroom.admin} logger at INFO for one test, then puts its configuration back. */
public final class AdminLogCapture implements AutoCloseable {

    private static final String LOGGER = "pressroom.admin";
    private static final String APPENDER = "admin-capture";

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final AbstractAppender appender;
    private final LoggerConfig previous;

    private AdminLogCapture() {
        appender = new AbstractAppender(APPENDER, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        previous = config.getLoggers().get(LOGGER);
        if (previous != null) {
            config.removeLogger(LOGGER);
        }
        LoggerConfig capture = new LoggerConfig(LOGGER, Level.INFO, false);
        capture.addAppender(appender, Level.INFO, null);
        config.addLogger(LOGGER, capture);
        ctx.updateLoggers();
    }

    public static AdminLogCapture start() {
        return new AdminLogCapture();
    }

    /** The formatted lines logged so far, in order. */
    public List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    @Override
    public void close() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        config.removeLogger(LOGGER);
        if (previous != null) {
            config.addLogger(LOGGER, previous);
        }
        ctx.updateLoggers();
        appender.stop();
    }
}
