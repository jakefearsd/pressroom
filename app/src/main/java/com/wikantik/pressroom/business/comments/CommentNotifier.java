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
package com.wikantik.pressroom.business.comments;

import com.wikantik.pressroom.pojos.ReaderComment;

/**
 * Told when a comment needs someone's attention: when it is stored
 * {@code PENDING}, so that the weblog's owners can be asked to approve it,
 * and when an author's reply is stored, so that the reader they answered can
 * be told. Called after the comment is stored and committed.
 *
 * <p>An implementation must not throw and must not block the request: the
 * comment is already stored, and a failure to notify loses nothing.
 */
@FunctionalInterface
public interface CommentNotifier {

    /** A comment was stored {@code PENDING}. */
    void notifyHeld(ReaderComment comment);

    /**
     * A reply was stored (always {@code PUBLISHED}); {@code reply.getReplyTo()}
     * is the comment it answers. Does nothing unless overridden, so a
     * notifier that only handles held comments can stay a lambda;
     * {@link MailCommentNotifier}, the one the site runs, mails the reader.
     */
    default void notifyReplied(ReaderComment reply) {
        // nothing to tell by default
    }
}
