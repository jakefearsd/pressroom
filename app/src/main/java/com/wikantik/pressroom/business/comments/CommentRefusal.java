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

/**
 * Why a comment was not stored. Each surface maps a code to its own answer
 * (the posting endpoint to an HTTP status, the session endpoint to a reason).
 */
public enum CommentRefusal {
    /** No account at all. */
    NOT_SIGNED_IN,
    /** The account lacks the global {@code comment} action. */
    NO_PERMISSION,
    /** The account is disabled, or no longer exists, when re-read. */
    DISABLED,
    /** The entry is unknown or not {@code PUBLISHED}; or, for a reply, the parent is unknown. */
    NOT_FOUND,
    /** The entry is {@code comments_closed}. */
    CLOSED,
    /** The weblog does not have {@code comments_enabled}. */
    COMMENTS_OFF,
    /** The weblog has a custom domain; posting is not offered there in v1. */
    CUSTOM_DOMAIN,
    /** The body is empty after trimming. */
    EMPTY_BODY,
    /** The body is longer than {@link CommentRules#MAX_BODY} characters after trimming. */
    BODY_TOO_LONG,
    /** A reply that is not an owner's reply to a published, unanswered top-level comment on the same entry. */
    BAD_REPLY,
    /** The account has posted its share for now. */
    RATE_LIMITED
}
