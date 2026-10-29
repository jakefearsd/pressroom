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
package com.wikantik.pressroom.business.jpa;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import org.eclipse.persistence.jpa.JpaEntityManager;
import org.eclipse.persistence.queries.DatabaseQuery;
import org.eclipse.persistence.sessions.Session;
import org.eclipse.persistence.sessions.SessionEvent;
import org.eclipse.persistence.sessions.SessionEventAdapter;

/**
 * Records what a piece of work asks of the persistence layer on this thread.
 *
 * <p>Each query the work runs on this thread's EntityManager is recorded as
 * "kind:Entity" -- {@code ReadObjectQuery} for a by-id find,
 * {@code ReadAllQuery} for a JPQL select, {@code ReportQuery} for a
 * projection -- and each statement that actually reached the database as
 * "SQL:...". A find the shared cache answers is a query with no statement.
 * Queries are raised on the EntityManager's own session, statements on the
 * server session, so the recorder listens on both.
 */
public final class QueryRecorder {

    /** Work that may throw. */
    public interface Work {
        void run() throws Exception;
    }

    private QueryRecorder() {
    }

    public static List<String> during(Work work) throws Exception {
        JpaEntityManager em = strategy().getEntityManager(false).unwrap(JpaEntityManager.class);
        Session session = em.getActiveSession();
        Session server = em.getServerSession();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        SessionEventAdapter recorder = new SessionEventAdapter() {
            @Override
            public void preExecuteQuery(SessionEvent event) {
                DatabaseQuery query = event.getQuery();
                Class<?> type = query.getReferenceClass();
                seen.add(query.getClass().getSimpleName() + ":"
                        + (type == null ? "?" : type.getSimpleName()));
            }

            @Override
            public void preExecuteCall(SessionEvent event) {
                seen.add("SQL:" + event.getCall());
            }
        };
        session.getEventManager().addListener(recorder);
        server.getEventManager().addListener(recorder);
        try {
            work.run();
        } finally {
            session.getEventManager().removeListener(recorder);
            server.getEventManager().removeListener(recorder);
        }
        return List.copyOf(seen);
    }

    /** The queries against {@code type}, of any kind. */
    public static List<String> on(List<String> recorded, Class<?> type) {
        return recorded.stream().filter(q -> q.endsWith(":" + type.getSimpleName())).toList();
    }

    /** The statements that reached the database and name {@code table}. */
    public static List<String> sqlOn(List<String> recorded, String table) {
        return recorded.stream().filter(q -> q.startsWith("SQL:") && q.contains(table)).toList();
    }

    /** The suite's persistence strategy, which every manager shares. */
    public static JPAPersistenceStrategy strategy() throws ReflectiveOperationException {
        Object manager = TestUtils.weblogger().getWeblogManager();
        Field f = JPAWeblogManagerImpl.class.getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(manager);
    }
}
