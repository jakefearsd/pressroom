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

import java.sql.Timestamp;
import java.util.List;

import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.FormSubmissionManager;
import com.wikantik.pressroom.pojos.FormSubmission;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * JPA implementation of {@link FormSubmissionManager}.
 */
public class JPAFormSubmissionManagerImpl implements FormSubmissionManager {

    private static final Logger log = LoggerFactory.getLogger(JPAFormSubmissionManagerImpl.class);

    private final JPAPersistenceStrategy strategy;

    protected JPAFormSubmissionManagerImpl(JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Form Submission Manager");
        this.strategy = strategy;
    }

    @Override
    public void save(FormSubmission submission) throws WebloggerException {
        if (submission.getWeblog() == null) {
            throw new WebloggerException("submission requires a weblog");
        }
        requireLength("name", submission.getName(), MAX_NAME, true);
        requireLength("email", submission.getEmail(), MAX_EMAIL, true);
        requireLength("subject", submission.getSubject(), MAX_SUBJECT, false);
        requireLength("message", submission.getMessage(), MAX_MESSAGE, true);
        if (submission.getCreated() == null) {
            submission.setCreated(new Timestamp(System.currentTimeMillis()));
        }
        strategy.store(submission);
    }

    private static void requireLength(String field, String value, int max,
            boolean required) throws WebloggerException {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new WebloggerException(field + " is required");
            }
            return;
        }
        if (value.length() > max) {
            throw new WebloggerException(field + " exceeds " + max + " characters");
        }
    }

    @Override
    public FormSubmission get(String id) throws WebloggerException {
        return (FormSubmission) strategy.load(FormSubmission.class, id);
    }

    @Override
    public List<FormSubmission> getSubmissions(Weblog weblog, int offset, int max) throws WebloggerException {
        TypedQuery<FormSubmission> query = strategy.getNamedQuery(
                "FormSubmission.getByWeblog", FormSubmission.class);
        query.setParameter(1, weblog);
        if (offset > 0) {
            query.setFirstResult(offset);
        }
        if (max >= 0) {
            query.setMaxResults(max);
        }
        return query.getResultList();
    }

    @Override
    public int getCount(Weblog weblog) throws WebloggerException {
        TypedQuery<Long> query = strategy.getNamedQuery(
                "FormSubmission.countByWeblog", Long.class);
        query.setParameter(1, weblog);
        return query.getSingleResult().intValue();
    }

    @Override
    public void remove(FormSubmission submission) throws WebloggerException {
        strategy.remove(submission);
    }

    @Override
    public void removeSubmissions(Weblog weblog) throws WebloggerException {
        Query removeSubmissions = strategy.getNamedUpdate("FormSubmission.removeByWeblog");
        removeSubmissions.setParameter(1, weblog);
        removeSubmissions.executeUpdate();
    }
}
