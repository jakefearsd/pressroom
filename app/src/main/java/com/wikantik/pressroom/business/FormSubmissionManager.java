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
package com.wikantik.pressroom.business;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.FormSubmission;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * Interface to contact-form inquiry bookkeeping.
 *
 * <p>A {@link FormSubmission} is persisted BEFORE any notification email is
 * attempted -- see the header comment on
 * {@code V015__form_submissions_and_tokens.sql} for why: if SMTP is down the
 * lead survives, which for a business running on leads is the failure that
 * matters.
 *
 * <p>The field length constants below are the last line of defence: the
 * controller checks them first, but a cap that only lives in the controller
 * is not a cap.
 */
public interface FormSubmissionManager {

    int MAX_NAME = 255;
    int MAX_EMAIL = 255;
    int MAX_SUBJECT = 255;
    int MAX_MESSAGE = 4000;

    /**
     * Persist a submission, stamping {@code created} when it is not already
     * set.
     */
    void save(FormSubmission submission) throws WebloggerException;

    /**
     * Look up a submission by id.
     */
    FormSubmission get(String id) throws WebloggerException;

    /**
     * The submissions belonging to a weblog, newest first.
     */
    List<FormSubmission> getSubmissions(Weblog weblog, int offset, int max) throws WebloggerException;

    /**
     * How many submissions a weblog has.
     */
    int getCount(Weblog weblog) throws WebloggerException;

    /**
     * Remove one submission.
     */
    void remove(FormSubmission submission) throws WebloggerException;

    /**
     * Remove all submissions belonging to a weblog.
     */
    void removeSubmissions(Weblog weblog) throws WebloggerException;
}
