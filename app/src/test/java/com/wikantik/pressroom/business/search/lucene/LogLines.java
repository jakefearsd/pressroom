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
package com.wikantik.pressroom.business.search.lucene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** What one class's logger emits during a test, with the level and throwable kept. */
final class LogLines implements AutoCloseable {

    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final Logger logger;
    private final Level previousLevel;
    private final AbstractAppender appender;

    private LogLines(Class<?> source) {
        logger = (Logger) LogManager.getLogger(source);
        previousLevel = logger.getLevel();
        appender = new AbstractAppender("log-lines-" + source.getSimpleName(), null, null, false,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
    }

    static LogLines of(Class<?> source) {
        return new LogLines(source);
    }

    /** The formatted messages logged at exactly this level, in order. */
    List<String> at(Level level) {
        synchronized (events) {
            return events.stream().filter(e -> e.getLevel() == level)
                    .map(e -> e.getMessage().getFormattedMessage()).toList();
        }
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }
}
