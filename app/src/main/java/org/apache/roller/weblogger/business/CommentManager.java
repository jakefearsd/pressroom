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
package org.apache.roller.weblogger.business;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * Stores comments by signed-in readers and the owner's replies. Like the
 * other managers it does not flush; callers do.
 */
public interface CommentManager {

    /** Store a comment (insert or update). */
    void saveComment(ReaderComment c) throws WebloggerException;

    /** Delete a comment; its reply goes with it (cascade). */
    void removeComment(ReaderComment c) throws WebloggerException;

    /** The comment with this id, or null when absent. */
    ReaderComment getComment(String id) throws WebloggerException;

    /** PUBLISHED top-level comments of the entry, oldest first, each with its PUBLISHED reply fetched; ONE query.
     * Only the comments this returns are guaranteed to have getReply() limited to a PUBLISHED reply;
     * on a comment obtained any other way getReply() is the reply in whatever status it has.
     * The query refreshes managed instances: flush pending changes first or they are lost. */
    List<ReaderComment> getPublishedForEntry(WeblogEntry entry) throws WebloggerException;

    /** Any status filter (null = all), newest first, paged; entry may be null. */
    List<ReaderComment> getComments(Weblog weblog, ReaderComment.Status status, WeblogEntry entry,
                                    int offset, int length) throws WebloggerException;

    /** Comments on the weblog in this status (null = all). */
    long countComments(Weblog weblog, ReaderComment.Status status) throws WebloggerException;

    /** Comments on the entry in this status (null = all). */
    long countForEntry(WeblogEntry entry, ReaderComment.Status status) throws WebloggerException;

    /** The author's PENDING comments on the entry, oldest first. */
    List<ReaderComment> getPendingForEntryAndAuthor(WeblogEntry entry, User author) throws WebloggerException;

    /** The author's comments, newest first, on all weblogs. */
    List<ReaderComment> getCommentsByAuthor(User author) throws WebloggerException;

    /** At least one PUBLISHED and no HIDDEN comment by this author on this weblog. */
    boolean isTrusted(User author, Weblog weblog) throws WebloggerException;

    /** The distinct weblogs where this author has a PUBLISHED comment (for cache bumps on rename/delete). */
    List<Weblog> getWeblogsWithPublishedCommentsBy(User author) throws WebloggerException;
}
