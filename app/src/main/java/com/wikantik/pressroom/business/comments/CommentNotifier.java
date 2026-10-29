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
 * Told when a comment is stored {@code PENDING}, so that the weblog's owners
 * can be asked to approve it. Called after the comment is stored and flushed;
 * published comments and replies are never passed here.
 *
 * <p>An implementation must not throw and must not block the request: the
 * comment is already stored, and a failure to notify loses nothing.
 */
public interface CommentNotifier {

    /** A comment was stored {@code PENDING}. */
    void notifyHeld(ReaderComment comment);
}
