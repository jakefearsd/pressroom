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
 * What came of a post or a reply: the stored comment, or why nothing was
 * stored. Exactly one of the two is non-null.
 */
public record CommentOutcome(ReaderComment comment, CommentRefusal refusal) {

    /** @throws IllegalArgumentException unless exactly one of the two is non-null */
    public CommentOutcome {
        if ((comment == null) == (refusal == null)) {
            throw new IllegalArgumentException("an outcome is a stored comment or a refusal, exactly one");
        }
    }

    /** True when the comment was stored. */
    public boolean ok() {
        return refusal == null;
    }

    /** A stored comment. */
    public static CommentOutcome stored(ReaderComment comment) {
        return new CommentOutcome(comment, null);
    }

    /** Nothing stored, for this reason. */
    public static CommentOutcome refused(CommentRefusal refusal) {
        return new CommentOutcome(null, refusal);
    }
}
