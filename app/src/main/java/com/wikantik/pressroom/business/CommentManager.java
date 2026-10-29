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
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;

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

    /**
     * PUBLISHED top-level comments of the entry, oldest first, each with its author and its reply
     * (and the reply's author) fetched; ONE query. The reply is whatever {@link ReaderComment#getReply()}
     * is, in ANY status: consumers show it only when PUBLISHED.
     */
    List<ReaderComment> getPublishedForEntry(WeblogEntry entry) throws WebloggerException;

    /** Any status filter (null = all), newest first, paged; entry may be null. */
    List<ReaderComment> getComments(Weblog weblog, ReaderComment.Status status, WeblogEntry entry,
                                    int offset, int length) throws WebloggerException;

    /** Comments on the weblog in this status (null = all), replies included. */
    long countComments(Weblog weblog, ReaderComment.Status status) throws WebloggerException;

    /**
     * What {@link #getComments} lists, counted: comments on the weblog in this
     * status (null = all), on this entry when it is not null, replies included.
     */
    long countComments(Weblog weblog, ReaderComment.Status status, WeblogEntry entry) throws WebloggerException;

    /**
     * TOP-LEVEL comments on the entry in this status (null = all): replies are not
     * counted, so the number is the one the entry page's heading shows.
     */
    long countForEntry(WeblogEntry entry, ReaderComment.Status status) throws WebloggerException;

    /** The author's PENDING comments on the entry, oldest first. */
    List<ReaderComment> getPendingForEntryAndAuthor(WeblogEntry entry, User author) throws WebloggerException;

    /**
     * The ids of the author's PUBLISHED comments on the entry, top-level and
     * replies alike, oldest first: the ones the page script offers to delete.
     */
    List<String> getPublishedIdsForEntryAndAuthor(WeblogEntry entry, User author) throws WebloggerException;

    /** The author's comments, newest first, on all weblogs. */
    List<ReaderComment> getCommentsByAuthor(User author) throws WebloggerException;

    /** At least one PUBLISHED and no HIDDEN comment by this author on this weblog. */
    boolean isTrusted(User author, Weblog weblog) throws WebloggerException;

    /** The distinct weblogs where this author has a PUBLISHED comment (for cache bumps on rename/delete). */
    List<Weblog> getWeblogsWithPublishedCommentsBy(User author) throws WebloggerException;

    /** Remove every comment on the entry, replies included, so the persistence context stays in step. */
    void removeCommentsForEntry(WeblogEntry entry) throws WebloggerException;

    /** Remove every comment by this author and every reply to one, so the persistence context stays in step. */
    void removeCommentsByAuthor(User author) throws WebloggerException;
}
