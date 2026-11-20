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

/* Created on Jul 16, 2003 */

package com.wikantik.pressroom.business.search.lucene;

import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.util.BytesRef;
import com.wikantik.pressroom.business.discovery.DiscoveryEligibility;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntryTag;
import com.wikantik.pressroom.util.Utilities;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * Builds the Lucene documents for entries and pages, and decides which pages
 * are indexed at all. Split out of the operation base class so a search
 * operation does not inherit writer code.
 */
final class IndexDocuments {

    private IndexDocuments() {
    }

    static Document entry(LuceneIndexManager manager, WeblogEntry data) {

        Document doc = new Document();

        // keyword
        doc.add(new StringField(FieldConstants.ID, data.getId(),
                Field.Store.YES));
        // every post query filters on it, so pages never reach post results
        doc.add(new StringField(FieldConstants.KIND, FieldConstants.KIND_ENTRY, Field.Store.NO));

        // keyword, case kept (IndexUtil.handleKey: handles are case-sensitive);
        // also a doc value, so the site view counts hits per blog without
        // reading up to 500 stored documents
        String handle = IndexUtil.handleKey(data.getWebsite().getHandle());
        doc.add(new StringField(FieldConstants.WEBSITE_HANDLE, handle, Field.Store.YES));
        doc.add(new SortedDocValuesField(FieldConstants.WEBSITE_HANDLE, new BytesRef(handle)));

        // No author login: nothing searches by author, and public search
        // could enumerate logins through "username:a*" (code review,
        // 2026-11-03).

        // text
        doc.add(new TextField(FieldConstants.TITLE, data.getTitle(),
                Field.Store.YES));

        // keyword needs to be in lower case as we are used in a term; the
        // column is nullable, and a post without one simply has no locale term
        if (data.getLocale() != null) {
            doc.add(new StringField(FieldConstants.LOCALE, data.getLocale()
                    .toLowerCase(Locale.ROOT), Field.Store.YES));
        }

        // The text a reader sees (shortcodes expanded, markdown rendered, tags
        // stripped); stored, because snippets are cut from it.
        doc.add(new TextField(FieldConstants.CONTENT,
                SearchableText.of(data, manager.weblogger().getEntryRenderer()),
                Field.Store.YES));

        if (data.getSummary() != null) {
            // decoded as the body is: "Fish &amp; chips" is not the word "amp"
            doc.add(new TextField(FieldConstants.SUMMARY,
                    StringEscapeUtils.unescapeHtml4(Utilities.removeHTML(data.getSummary(), true)),
                    Field.Store.NO));
        }

        if (!data.getTags().isEmpty()) {
            doc.add(new TextField(FieldConstants.TAGS,
                    data.getTags().stream().map(WeblogEntryTag::getName).sorted()
                            .collect(Collectors.joining(" ")),
                    Field.Store.NO));
        }

        // a missing name or tagline is no words, never the word "null"
        Weblog blog = data.getWebsite();
        doc.add(new TextField(FieldConstants.BLOG,
                (Objects.toString(blog.getName(), "") + " " + Objects.toString(blog.getTagline(), "")).strip(),
                Field.Store.NO));

        // the entry's half of the "more from the site" rule; the blog's half
        // is decided at query time
        if (DiscoveryEligibility.isEligible(data)) {
            doc.add(new StringField(FieldConstants.SITE, FieldConstants.SITE_V, Field.Store.NO));
        }

        // keyword
        doc.add(new StringField(FieldConstants.UPDATED, data.getUpdateTime()
                .toString(), Field.Store.YES));

        // keyword
        if (data.getPubTime() != null) {
            // SearchOperation sorts results by date
            doc.add(new SortedDocValuesField(FieldConstants.PUBLISHED, new BytesRef(data.getPubTime().toString())));
            // the recency factor reads this one
            doc.add(new NumericDocValuesField(FieldConstants.PUBLISHED_NUM, data.getPubTime().getTime()));
        }

        // index Category, needs to be in lower case as it is used in a term
        WeblogCategory categorydata = data.getCategory();
        if (categorydata != null) {
            doc.add(new StringField(FieldConstants.CATEGORY, categorydata
                    .getName().toLowerCase(Locale.ROOT), Field.Store.YES));
            doc.add(new TextField(FieldConstants.CATEGORY_TEXT, categorydata.getName(), Field.Store.NO));
        }

        return doc;
    }

    /**
     * Whether a page belongs in the index: PUBLISHED and not {@code noindex}.
     * The one rule a page save and a rebuild both apply.
     */
    static boolean isIndexable(WeblogPage page) {
        return page != null && page.getStatus() == WeblogPage.PubStatus.PUBLISHED
                && !Boolean.TRUE.equals(page.getNoindex());
    }

    /**
     * A page's document: its id and kind, its blog's handle (the scope
     * filters), and its title and text in the page fields
     * ({@link FieldConstants#PAGE_TITLE}). No publish time, site flag,
     * category or locale: the page query reads none of them.
     */
    static Document page(LuceneIndexManager manager, WeblogPage page) {
        Document doc = new Document();
        doc.add(new StringField(FieldConstants.ID, page.getId(), Field.Store.YES));
        doc.add(new StringField(FieldConstants.KIND, FieldConstants.KIND_PAGE, Field.Store.NO));
        // as a post's handle, doc value included: Lucene refuses a document
        // whose field differs in kind from the same field already indexed
        String handle = IndexUtil.handleKey(page.getWeblog().getHandle());
        doc.add(new StringField(FieldConstants.WEBSITE_HANDLE, handle, Field.Store.YES));
        doc.add(new SortedDocValuesField(FieldConstants.WEBSITE_HANDLE, new BytesRef(handle)));
        // page titles are stored raw (entry titles are escaped); a missing
        // one is no words, never the word "null"
        doc.add(new TextField(FieldConstants.PAGE_TITLE, Objects.toString(page.getTitle(), ""), Field.Store.YES));
        doc.add(new TextField(FieldConstants.PAGE_CONTENT,
                SearchableText.of(page, manager.weblogger().getEntryRenderer()), Field.Store.YES));
        return doc;
    }
}
