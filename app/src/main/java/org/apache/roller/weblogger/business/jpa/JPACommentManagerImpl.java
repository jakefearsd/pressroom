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
package org.apache.roller.weblogger.business.jpa;

import java.util.List;

import jakarta.persistence.TypedQuery;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.CommentManager;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.ReaderComment.Status;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA implementation of {@link CommentManager}.
 */
public class JPACommentManagerImpl implements CommentManager {

    private static final Logger log =
            LoggerFactory.getLogger(JPACommentManagerImpl.class);

    private final JPAPersistenceStrategy strategy;

    protected JPACommentManagerImpl(JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Comment Manager");
        this.strategy = strategy;
    }

    @Override
    public void saveComment(ReaderComment c) throws WebloggerException {
        strategy.store(c);
        // Keep the inverse side coherent: the shared cache may hold the parent
        // without its reply, and nothing else would tell it.
        if (c.getReplyTo() != null) {
            c.getReplyTo().setReply(c);
        }
    }

    @Override
    public void removeComment(ReaderComment c) throws WebloggerException {
        if (c.getReplyTo() != null) {
            c.getReplyTo().setReply(null);
        }
        strategy.remove(c);
    }

    @Override
    public ReaderComment getComment(String id) throws WebloggerException {
        if (id == null) {
            return null;
        }
        return (ReaderComment) strategy.load(ReaderComment.class, id);
    }

    @Override
    public List<ReaderComment> getPublishedForEntry(WeblogEntry entry) throws WebloggerException {
        TypedQuery<ReaderComment> query = strategy.getNamedQuery(
                "ReaderComment.getPublishedForEntry", ReaderComment.class);
        query.setParameter(1, entry);
        return query.getResultList();
    }

    @Override
    public List<ReaderComment> getComments(Weblog weblog, Status status, WeblogEntry entry,
            int offset, int length) throws WebloggerException {
        StringBuilder jpql = new StringBuilder("SELECT c FROM ReaderComment c WHERE c.entry.website = ?1");
        int next = 2;
        if (status != null) {
            jpql.append(" AND c.status = ?").append(next++);
        }
        if (entry != null) {
            jpql.append(" AND c.entry = ?").append(next);
        }
        jpql.append(" ORDER BY c.created DESC, c.id");
        TypedQuery<ReaderComment> query = strategy.getDynamicQuery(jpql.toString(), ReaderComment.class);
        bind(query, weblog, status, entry);
        query.setFirstResult(offset);
        query.setMaxResults(length);
        return query.getResultList();
    }

    @Override
    public long countComments(Weblog weblog, Status status) throws WebloggerException {
        return count("c.entry.website = ?1", weblog, status);
    }

    @Override
    public long countForEntry(WeblogEntry entry, Status status) throws WebloggerException {
        return count("c.entry = ?1", entry, status);
    }

    private long count(String where, Object scope, Status status) throws WebloggerException {
        String jpql = "SELECT COUNT(c) FROM ReaderComment c WHERE " + where
                + (status != null ? " AND c.status = ?2" : "");
        TypedQuery<Long> query = strategy.getDynamicQuery(jpql, Long.class);
        query.setParameter(1, scope);
        if (status != null) {
            query.setParameter(2, status);
        }
        return query.getSingleResult();
    }

    private void bind(TypedQuery<?> query, Weblog weblog, Status status, WeblogEntry entry) {
        int next = 1;
        query.setParameter(next++, weblog);
        if (status != null) {
            query.setParameter(next++, status);
        }
        if (entry != null) {
            query.setParameter(next, entry);
        }
    }

    @Override
    public List<ReaderComment> getPendingForEntryAndAuthor(WeblogEntry entry, User author)
            throws WebloggerException {
        TypedQuery<ReaderComment> query = strategy.getNamedQuery(
                "ReaderComment.getPendingForEntryAndAuthor", ReaderComment.class);
        query.setParameter(1, entry);
        query.setParameter(2, author);
        return query.getResultList();
    }

    @Override
    public List<ReaderComment> getCommentsByAuthor(User author) throws WebloggerException {
        TypedQuery<ReaderComment> query = strategy.getNamedQuery(
                "ReaderComment.getByAuthor", ReaderComment.class);
        query.setParameter(1, author);
        return query.getResultList();
    }

    @Override
    public boolean isTrusted(User author, Weblog weblog) throws WebloggerException {
        return countByStatus(author, weblog, Status.PUBLISHED) > 0
                && countByStatus(author, weblog, Status.HIDDEN) == 0;
    }

    private long countByStatus(User author, Weblog weblog, Status status) throws WebloggerException {
        TypedQuery<Long> query = strategy.getNamedQuery(
                "ReaderComment.countByAuthorWeblogStatus", Long.class);
        query.setParameter(1, author);
        query.setParameter(2, weblog);
        query.setParameter(3, status);
        return query.getSingleResult();
    }

    @Override
    public List<Weblog> getWeblogsWithPublishedCommentsBy(User author) throws WebloggerException {
        TypedQuery<Weblog> query = strategy.getNamedQuery(
                "ReaderComment.getWeblogsPublishedBy", Weblog.class);
        query.setParameter(1, author);
        return query.getResultList();
    }
}
