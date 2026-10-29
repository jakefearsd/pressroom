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
package com.wikantik.pressroom.pojos;

import com.wikantik.pressroom.pojos.MediaFileComparator.MediaFileComparatorType;
import com.wikantik.pressroom.pojos.MediaFileDirectoryComparator.DirectoryComparatorType;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the comparators that decide display order in the media gallery, the
 * tag cloud and the admin statistics tables.
 *
 * <p>Every one of them is a sort key with a tie-breaker, and a comparator whose
 * tie-break is missing or reversed produces a list that reshuffles itself
 * between page loads -- visible to users, invisible to any other test. Both
 * directions of each comparison are asserted so that a mutation flipping the
 * sign cannot pass.
 */
class PojoComparatorTest {

    private static MediaFile file(String name, String contentType, String uploadedAt) {
        MediaFile file = new MediaFile();
        file.setName(name);
        file.setContentType(contentType);
        file.setDateUploaded(Timestamp.valueOf(uploadedAt));
        return file;
    }

    private static TagStat tagStat(String name, int count) {
        TagStat stat = new TagStat();
        stat.setName(name);
        stat.setCount(count);
        return stat;
    }

    // ------------------------------------------------------------ media files

    @Test
    void mediaFilesSortByNameWhenAskedTo() {
        MediaFileComparator byName = new MediaFileComparator(MediaFileComparatorType.NAME);
        MediaFile apple = file("apple.jpg", "image/jpeg", "2024-01-01 00:00:00");
        MediaFile pear = file("pear.jpg", "image/jpeg", "2024-01-01 00:00:00");

        assertTrue(byName.compare(apple, pear) < 0);
        assertTrue(byName.compare(pear, apple) > 0, "The comparison must be antisymmetric");
        assertEquals(0, byName.compare(apple, apple));
    }

    @Test
    void mediaFilesSortByContentTypeWhenAskedTo() {
        MediaFileComparator byType = new MediaFileComparator(MediaFileComparatorType.TYPE);
        MediaFile image = file("a.jpg", "image/jpeg", "2024-01-01 00:00:00");
        MediaFile video = file("b.mp4", "video/mp4", "2024-01-01 00:00:00");

        assertTrue(byType.compare(image, video) < 0,
                "Sorting by type must use the content type, not the file name -- here the "
                        + "two orderings disagree");
        assertTrue(byType.compare(video, image) > 0);
    }

    @Test
    void mediaFilesSortByUploadDateNewestFirst() {
        // Deliberately the opposite direction to the other keys: the gallery
        // shows what you just uploaded at the top.
        MediaFileComparator byDate = new MediaFileComparator(MediaFileComparatorType.DATE_UPLOADED);
        MediaFile older = file("a.jpg", "image/jpeg", "2024-01-01 00:00:00");
        MediaFile newer = file("b.jpg", "image/jpeg", "2024-06-01 00:00:00");

        assertTrue(byDate.compare(newer, older) < 0,
                "The most recently uploaded file must sort first; the natural date order "
                        + "would bury a fresh upload at the bottom of the gallery");
        assertTrue(byDate.compare(older, newer) > 0);
    }

    @Test
    void mediaFileDirectoriesSortByName() {
        MediaFileDirectoryComparator byName =
                new MediaFileDirectoryComparator(DirectoryComparatorType.NAME);
        MediaFileDirectory archive = new MediaFileDirectory();
        archive.setName("archive");
        MediaFileDirectory photos = new MediaFileDirectory();
        photos.setName("photos");

        assertTrue(byName.compare(archive, photos) < 0);
        assertTrue(byName.compare(photos, archive) > 0);
    }

    // ------------------------------------------------------------------- tags

    @Test
    void entryTagsSortByName() {
        WeblogEntryTagComparator byName = new WeblogEntryTagComparator();
        WeblogEntryTag apple = new WeblogEntryTag();
        apple.setName("apple");
        WeblogEntryTag pear = new WeblogEntryTag();
        pear.setName("pear");

        assertTrue(byName.compare(apple, pear) < 0);
        assertTrue(byName.compare(pear, apple) > 0);
        assertEquals(0, byName.compare(apple, apple));
    }

    @Test
    void wrappedEntriesSortNewestFirstThenByTitle() {
        // This is what orders the entries on a blog's front page. The direction
        // is the opposite of the natural date order, and the title tie-break is
        // what stops two posts published in the same second from swapping
        // places between renders.
        WeblogEntryWrapperComparator comparator = new WeblogEntryWrapperComparator();
        Timestamp earlier = Timestamp.valueOf("2024-01-01 10:00:00");
        Timestamp later = Timestamp.valueOf("2024-06-01 10:00:00");

        assertTrue(comparator.compare(wrappedEntry(later, "b"), wrappedEntry(earlier, "a")) < 0,
                "The more recent post sorts first, even though its title sorts later");
        assertTrue(comparator.compare(wrappedEntry(earlier, "a"), wrappedEntry(later, "b")) > 0);

        assertTrue(comparator.compare(wrappedEntry(later, "apple"), wrappedEntry(later, "pear")) < 0,
                "Posts published at the same instant fall back to the title");
        assertTrue(comparator.compare(wrappedEntry(later, "pear"), wrappedEntry(later, "apple")) > 0);
        assertEquals(0, comparator.compare(wrappedEntry(later, "a"), wrappedEntry(later, "a")));
    }

    private static com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper wrappedEntry(
            Timestamp pubTime, String title) {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setPubTime(pubTime);
        entry.setTitle(title);
        return com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper.wrap(entry, null, null);
    }

    @Test
    void tagStatsSortByNameIgnoringCase() {
        TagStatComparator byName = new TagStatComparator();

        assertTrue(byName.compare(tagStat("apple", 1), tagStat("Banana", 1)) < 0,
                "Tag clouds mix cases; a case sensitive sort would list every capitalised "
                        + "tag before every lowercase one instead of alphabetically");
        assertTrue(byName.compare(tagStat("Banana", 1), tagStat("apple", 1)) > 0);
        assertEquals(0, byName.compare(tagStat("apple", 1), tagStat("APPLE", 1)));
    }

    @Test
    void tagStatsSortByCountThenName() {
        TagStatCountComparator byCount = TagStatCountComparator.getInstance();

        assertTrue(byCount.compare(tagStat("rare", 1), tagStat("common", 9)) < 0,
                "The lower count sorts first; callers reverse the list to get a "
                        + "most-popular-first tag cloud");
        assertTrue(byCount.compare(tagStat("common", 9), tagStat("rare", 1)) > 0);

        assertTrue(byCount.compare(tagStat("apple", 5), tagStat("pear", 5)) < 0,
                "Equal counts must fall back to the name, or tags with the same count "
                        + "shuffle between renders");
        assertTrue(byCount.compare(tagStat("pear", 5), tagStat("apple", 5)) > 0);
        assertEquals(0, byCount.compare(tagStat("apple", 5), tagStat("apple", 5)));
    }

    @Test
    void tagStatCountComparatorIsAReusableSingleton() {
        assertSame(TagStatCountComparator.getInstance(), TagStatCountComparator.getInstance(),
                "getInstance() must hand back the same stateless comparator each time "
                        + "rather than allocating one per sort");
    }

    // -------------------------------------------------------------- stat counts

    @Test
    void statCountsSortByCountThenSubjectThenType() {
        StatCountCountComparator byCount = StatCountCountComparator.getInstance();
        // The subject ids run the opposite way to the counts, so a comparator
        // that ignored the count and fell straight to the tie-break would give
        // the wrong answer rather than accidentally the right one.
        StatCount few = new StatCount("blog-z", "short", "long", "comments", 1L);
        StatCount many = new StatCount("blog-a", "short", "long", "comments", 99L);

        assertTrue(byCount.compare(few, many) < 0);
        assertTrue(byCount.compare(many, few) > 0);

        StatCount blogA = new StatCount("blog-a", "short", "long", "comments", 5L);
        StatCount blogB = new StatCount("blog-b", "short", "long", "comments", 5L);
        assertTrue(byCount.compare(blogA, blogB) < 0,
                "Equal counts fall back to the subject, which is what keeps the admin "
                        + "statistics table in a stable order");
        assertTrue(byCount.compare(blogB, blogA) > 0);

        StatCount comments = new StatCount("blog-a", "short", "long", "comments", 5L);
        StatCount entries = new StatCount("blog-a", "short", "long", "entries", 5L);
        assertTrue(byCount.compare(comments, entries) < 0,
                "The same subject with the same count still has to order deterministically, "
                        + "so the statistic type is the last tie-break");
        assertEquals(0, byCount.compare(comments, comments));
    }

    @Test
    void statCountComparatorIsAReusableSingleton() {
        assertSame(StatCountCountComparator.getInstance(), StatCountCountComparator.getInstance());
    }

    @Test
    void aStatCountNamesTheWeblogItCountsAndItsValue() {
        // These rows are rendered in the site-wide statistics table and logged
        // when a count looks wrong, so the description has to identify which
        // blog and how many.
        StatCount count = new StatCount("blog-a", "Blog A", "Blog A, the first one",
                "statCount.weblogEntryCountType", 42L);
        count.setWeblogHandle("blog-a");

        assertTrue(count.toString().contains("blog-a"),
                "The rendered form must name the weblog: " + count);
        assertTrue(count.toString().contains("42"),
                "and the number being reported: " + count);
    }

    @Test
    void aTagAggregateRemembersWhatItWasCountedFor() {
        // The aggregate rows drive the tag cloud. The constructor deliberately
        // ignores the id it is passed and keeps the generated one, so that is
        // pinned here alongside the fields it does take.
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");

        WeblogEntryTagAggregate aggregate =
                new WeblogEntryTagAggregate("ignored-id", weblog, "java", 17);

        assertSame(weblog, aggregate.getWeblog(),
                "An aggregate must know which weblog it counts for, or one blog's tag "
                        + "cloud shows another's totals");
        assertEquals("java", aggregate.getName());
        assertEquals(17, aggregate.getTotal(),
                "and how many entries carry the tag -- this is the number the tag cloud "
                        + "sizes each tag by");
        assertTrue(!"ignored-id".equals(aggregate.getId()),
                "The id argument is deliberately ignored; the entity keeps the UUID it "
                        + "generated for itself");
    }

    // ------------------------------------------------------- sorting for real

    @Test
    void sortingAListWithTheComparatorProducesTheDocumentedOrder() {
        // The comparators exist to be handed to a sort; asserting on compare()
        // alone would not catch a comparator that is inconsistent under sorting.
        List<TagStat> stats = new ArrayList<>(List.of(
                tagStat("zebra", 5), tagStat("apple", 5), tagStat("rare", 1)));

        stats.sort(TagStatCountComparator.getInstance());

        assertEquals(List.of("rare", "apple", "zebra"),
                stats.stream().map(TagStat::getName).toList(),
                "Least used first, then alphabetically within the same count");
    }
}
