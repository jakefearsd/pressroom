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
package org.apache.roller.weblogger.pojos;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the {@code equals}/{@code hashCode} contract on every domain object
 * that hand-writes one.
 *
 * <p>These are JPA entities, and their {@code equals} methods deliberately
 * compare a <em>business key</em> (a weblog's handle, an entry's anchor) rather
 * than the surrogate id. That choice is what makes them safe to put in a
 * {@code Set} before they have been persisted -- and it is also what makes a
 * mistake here expensive: entries live in {@code HashSet}s throughout the
 * rendering code, tags are de-duplicated by {@code equals}, and permissions are
 * compared when merging grants. A broken {@code equals} shows up as a
 * disappearing tag or a duplicated category, a long way from the cause.
 *
 * <p>Each specimen supplies three objects: one, a second that must be equal to
 * it, and a third that must not. Every case is exercised against all four
 * clauses of the {@link Object#equals} contract plus the {@code hashCode}
 * agreement.
 */
class EqualsContractTest {

    /** One class under test: {@code a} and {@code b} must be equal, {@code c} must not. */
    private record Specimen(String name, Object a, Object b, Object c) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }

    private static WeblogEntry entry(String anchor, Weblog weblog) {
        WeblogEntry entry = new WeblogEntry();
        entry.setAnchor(anchor);
        entry.setWebsite(weblog);
        return entry;
    }

    private static WeblogTemplate template(String name, Weblog weblog) {
        WeblogTemplate template = new WeblogTemplate();
        template.setName(name);
        template.setWeblog(weblog);
        return template;
    }

    private static MediaFile mediaFile(String id) {
        MediaFile file = new MediaFile();
        file.setId(id);
        return file;
    }

    private static CustomTemplateRendition rendition(WeblogTemplate owner, String body) {
        CustomTemplateRendition rendition = new CustomTemplateRendition();
        rendition.setWeblogTemplate(owner);
        rendition.setTemplate(body);
        return rendition;
    }

    private static WeblogPermission weblogPermission(String user, String handle, String actions) {
        WeblogPermission perm = new WeblogPermission();
        perm.setUserName(user);
        perm.setObjectId(handle);
        perm.setActions(actions);
        return perm;
    }

    static Stream<Specimen> specimens() throws Exception {
        Weblog blogA = weblog("blog-a");
        Weblog blogB = weblog("blog-b");
        WeblogEntry entryA = entry("first-post", blogA);
        WeblogEntry entryB = entry("second-post", blogA);
        WeblogTemplate templateA = template("Weblog", blogA);
        templateA.setId("template-a");
        WeblogTemplate templateB = template("_day", blogA);
        templateB.setId("template-b");
        MediaFile fileA = mediaFile("file-a");
        MediaFile fileB = mediaFile("file-b");

        return Stream.of(
                new Specimen("Weblog (keyed on handle)",
                        weblog("blog-a"), weblog("blog-a"), weblog("blog-b")),

                new Specimen("WeblogEntry (keyed on anchor + weblog)",
                        entry("first-post", blogA), entry("first-post", blogA),
                        entry("first-post", blogB)),

                new Specimen("WeblogEntryTag (keyed on name + entry)",
                        tag("java", entryA), tag("java", entryA), tag("java", entryB)),

                new Specimen("WeblogEntryTagAggregate (keyed on name + weblog) -- differing name",
                        aggregate("java", blogA), aggregate("java", blogA), aggregate("scala", blogA)),

                new Specimen("WeblogEntryTagAggregate (keyed on name + weblog) -- differing weblog",
                        aggregate("java", blogA), aggregate("java", blogA), aggregate("java", blogB)),

                new Specimen("WeblogEntryAttribute (keyed on name + entry)",
                        attribute("mood", entryA), attribute("mood", entryA), attribute("mood", entryB)),

                new Specimen("WeblogCategory (keyed on name + weblog)",
                        category("General", blogA), category("General", blogA),
                        category("General", blogB)),

                new Specimen("WeblogTemplate (keyed on name + weblog)",
                        template("Weblog", blogA), template("Weblog", blogA),
                        template("Weblog", blogB)),

                new Specimen("User (keyed on userName)",
                        user("alice"), user("alice"), user("bob")),

                new Specimen("UserRole (keyed on userName + role) -- differing role",
                        userRole("alice", "admin"), userRole("alice", "admin"),
                        userRole("alice", "editor")),

                new Specimen("UserRole (keyed on userName + role) -- differing user",
                        userRole("alice", "admin"), userRole("alice", "admin"),
                        userRole("bob", "admin")),

                new Specimen("MediaFile (keyed on id)",
                        mediaFile("file-a"), mediaFile("file-a"), mediaFile("file-b")),

                new Specimen("MediaFileTag (keyed on name + file)",
                        new MediaFileTag("holiday", fileA), new MediaFileTag("holiday", fileA),
                        new MediaFileTag("holiday", fileB)),

                new Specimen("MediaFileDirectory (keyed on id + name + description) -- differing id",
                        directory("dir-1", "photos", "Holiday snaps"),
                        directory("dir-1", "photos", "Holiday snaps"),
                        directory("dir-2", "photos", "Holiday snaps")),

                new Specimen("MediaFileDirectory (keyed on id + name + description) -- differing description",
                        directory("dir-1", "photos", "Holiday snaps"),
                        directory("dir-1", "photos", "Holiday snaps"),
                        directory("dir-1", "photos", "Work screenshots")),

                new Specimen("TaskLock (keyed on name)",
                        taskLock("ScheduledEntriesTask"), taskLock("ScheduledEntriesTask"),
                        taskLock("OtherTask")),

                new Specimen("RuntimeConfigProperty (keyed on name)",
                        new RuntimeConfigProperty("site.name", "Roller"),
                        new RuntimeConfigProperty("site.name", "Something else"),
                        new RuntimeConfigProperty("site.shortName", "Roller")),

                new Specimen("StatCount (keyed on subjectId + typeKey) -- differing type",
                        statCount("blog-a", "hits"), statCount("blog-a", "hits"),
                        statCount("blog-a", "comments")),

                new Specimen("StatCount (keyed on subjectId + typeKey) -- differing subject",
                        statCount("blog-a", "hits"), statCount("blog-a", "hits"),
                        statCount("blog-b", "hits")),

                new Specimen("CustomTemplateRendition (keyed on template id + body) -- differing template",
                        rendition(templateA, "$body"), rendition(templateA, "$body"),
                        rendition(templateB, "$body")),

                new Specimen("CustomTemplateRendition (keyed on template id + body) -- differing body",
                        rendition(templateA, "$body"), rendition(templateA, "$body"),
                        rendition(templateA, "$somethingElse")),

                new Specimen("GlobalPermission (keyed on actions)",
                        new GlobalPermission(List.of(GlobalPermission.LOGIN)),
                        new GlobalPermission(List.of(GlobalPermission.LOGIN)),
                        new GlobalPermission(List.of(GlobalPermission.ADMIN))),

                new Specimen("WeblogPermission (keyed on user + weblog + actions)",
                        weblogPermission("alice", "blog-a", "post"),
                        weblogPermission("alice", "blog-a", "post"),
                        weblogPermission("alice", "blog-a", "admin")),

                new Specimen("WeblogEntryRevision (keyed on id)",
                        revision("revision-a"), revision("revision-a"), revision("revision-b")),

                new Specimen("WeblogPage (keyed on id)",
                        page("page-a"), page("page-a"), page("page-b")),

                new Specimen("WeblogRedirect (keyed on id)",
                        redirect("redirect-a"), redirect("redirect-a"), redirect("redirect-b")),

                new Specimen("ReaderComment (keyed on id)",
                        readerComment("rc-a"), readerComment("rc-a"), readerComment("rc-b")),
                new Specimen("Business (keyed on id)",
                        business("business-a"), business("business-a"), business("business-b")),

                new Specimen("RollerEvent (keyed on id)",
                        event("event-a"), event("event-a"), event("event-b")),

                new Specimen("FormSubmission (keyed on id)",
                        formSubmission("submission-a"), formSubmission("submission-a"),
                        formSubmission("submission-b")),

                new Specimen("UserToken (keyed on id)",
                        userToken("token-a"), userToken("token-a"), userToken("token-b")),

                new Specimen("ApiToken (keyed on id)",
                        apiToken("token-a"), apiToken("token-a"), apiToken("token-b")));
    }

    private static FormSubmission formSubmission(String id) {
        FormSubmission submission = new FormSubmission();
        submission.setId(id);
        return submission;
    }

    private static UserToken userToken(String id) {
        UserToken token = new UserToken();
        token.setId(id);
        return token;
    }

    private static ApiToken apiToken(String id) {
        ApiToken token = new ApiToken();
        token.setId(id);
        return token;
    }

    private static WeblogPage page(String id) {
        WeblogPage page = new WeblogPage();
        page.setId(id);
        return page;
    }

    private static ReaderComment readerComment(String id) {
        ReaderComment comment = new ReaderComment();
        comment.setId(id);
        comment.setBody("hello");
        return comment;
    }

    private static Business business(String id) {
        Business business = new Business();
        business.setId(id);
        business.setName("Acme");
        return business;
    }

    private static WeblogRedirect redirect(String id) {
        WeblogRedirect redirect = new WeblogRedirect();
        redirect.setId(id);
        redirect.setSourcePath("/old");
        redirect.setTargetPath("/new");
        return redirect;
    }

    private static RollerEvent event(String id) {
        RollerEvent event = new RollerEvent();
        event.setId(id);
        return event;
    }

    /**
     * Keyed on the surrogate id alone. A revision has no natural key: two
     * saves can displace byte-identical content, and those are still two
     * distinct revisions with distinct timestamps.
     */
    private static WeblogEntryRevision revision(String id) {
        WeblogEntryRevision revision = new WeblogEntryRevision();
        revision.setId(id);
        return revision;
    }

    private static WeblogEntryTag tag(String name, WeblogEntry entry) {
        WeblogEntryTag tag = new WeblogEntryTag();
        tag.setName(name);
        tag.setWeblogEntry(entry);
        return tag;
    }

    private static WeblogEntryTagAggregate aggregate(String name, Weblog weblog) {
        WeblogEntryTagAggregate aggregate = new WeblogEntryTagAggregate();
        aggregate.setName(name);
        aggregate.setWeblog(weblog);
        return aggregate;
    }

    private static WeblogEntryAttribute attribute(String name, WeblogEntry entry) {
        WeblogEntryAttribute attribute = new WeblogEntryAttribute();
        attribute.setName(name);
        attribute.setEntry(entry);
        return attribute;
    }

    private static WeblogCategory category(String name, Weblog weblog) {
        WeblogCategory category = new WeblogCategory();
        category.setName(name);
        category.setWeblog(weblog);
        return category;
    }

    private static User user(String userName) {
        User user = new User();
        user.setUserName(userName);
        return user;
    }

    private static UserRole userRole(String userName, String role) {
        return new UserRole(userName, role);
    }

    private static MediaFileDirectory directory(String id, String name, String description) {
        MediaFileDirectory dir = new MediaFileDirectory();
        dir.setId(id);
        dir.setName(name);
        dir.setDescription(description);
        return dir;
    }

    private static TaskLock taskLock(String name) {
        TaskLock lock = new TaskLock();
        lock.setName(name);
        return lock;
    }

    private static StatCount statCount(String subjectId, String typeKey) {
        StatCount count = new StatCount(subjectId, "short", "long", typeKey, 42L);
        count.setWeblogHandle(subjectId);
        return count;
    }

    @TestFactory
    Stream<DynamicTest> everyHandWrittenEqualsHonoursTheContract() throws Exception {
        return specimens().map(specimen ->
                DynamicTest.dynamicTest(specimen.name(), () -> assertContract(specimen)));
    }

    private static void assertContract(Specimen specimen) {
        Object a = specimen.a();
        Object b = specimen.b();
        Object c = specimen.c();

        assertEquals(a, a, specimen.name() + ": an object must equal itself (reflexive)");

        assertEquals(a, b,
                specimen.name() + ": two objects with the same business key must be equal. "
                        + "These entities are compared before they have ids, so equality has "
                        + "to come from the business key, not the surrogate key.");
        assertEquals(b, a, specimen.name() + ": equality must hold in both directions (symmetric)");

        assertNotEquals(a, c,
                specimen.name() + ": objects with different business keys must not be equal");
        assertNotEquals(c, a, specimen.name() + ": inequality must hold in both directions");

        assertEquals(a.equals(b), a.equals(b),
                specimen.name() + ": repeated comparisons of unmodified objects must agree "
                        + "(consistent)");

        assertFalse(a.equals(null),
                specimen.name() + ": equals(null) must return false, never throw. JPA and "
                        + "the collections framework both hand null to equals().");

        assertFalse(a.equals("not a " + a.getClass().getSimpleName()),
                specimen.name() + ": equals() must return false for an unrelated type "
                        + "rather than throwing ClassCastException");

        assertEquals(a.hashCode(), b.hashCode(),
                specimen.name() + ": equal objects must have equal hash codes, or they will "
                        + "land in different buckets and both survive in a HashSet");
        assertEquals(a.hashCode(), a.hashCode(),
                specimen.name() + ": hashCode must be stable across calls on an unmodified object");

        // Not required by the contract, but required for the collections to
        // work: a hashCode that ignores the business key (a constant, or one
        // built from a field these specimens share) turns every HashSet of
        // these entities into a linked list and hides ordering bugs.
        assertNotEquals(a.hashCode(), c.hashCode(),
                specimen.name() + ": objects with different business keys must hash "
                        + "differently. If they do not, hashCode is not built from the same "
                        + "fields equals compares.");

        assertFalse(a.toString() == null || a.toString().isBlank(),
                specimen.name() + ": toString() is what the permission checks, the "
                        + "scheduler and the persistence layer log when something goes "
                        + "wrong; an empty one makes those log lines useless");
        assertFalse(a.toString().equals(defaultToString(a)),
                specimen.name() + ": toString() must say something about the object, not "
                        + "fall back to Object's class@hashcode form");

        // The practical consequence of the contract: WeblogEntry.addTag(),
        // MediaFile.addTag() and Weblog.addCategory() all rely on set semantics
        // to reject duplicates.
        Set<Object> set = new HashSet<>();
        set.add(a);
        set.add(b);
        set.add(c);
        assertEquals(2, set.size(),
                specimen.name() + ": a HashSet must collapse the two equal objects and keep "
                        + "the third. Got: " + set);
    }

    private static String defaultToString(Object o) {
        return o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    @Test
    void everyPojoWithAHandWrittenEqualsIsCovered() throws Exception {
        // Guards the guard: if a new entity gains an equals() implementation and
        // nobody adds a specimen, the contract above quietly stops covering it.
        Set<String> covered = specimens()
                .map(specimen -> specimen.a().getClass().getName())
                .collect(java.util.stream.Collectors.toSet());

        List<String> missing = PojoClassScanner.classesDeclaringEquals().stream()
                .map(Class::getName)
                .filter(name -> !covered.contains(name))
                .toList();

        assertTrue(missing.isEmpty(),
                "These classes in org.apache.roller.weblogger.pojos declare their own "
                        + "equals() but have no specimen in EqualsContractTest, so nothing "
                        + "checks their contract. Add one for each:\n  "
                        + String.join("\n  ", missing));
    }

    @Test
    void entitiesKeyedOnABusinessFieldIgnoreTheSurrogateId() throws Exception {
        // Roller assigns a UUID in the field initialiser, so two freshly built
        // copies of "the same" entity always have different ids. If equals()
        // looked at the id, de-duplication would silently stop working.
        Weblog first = weblog("blog-a");
        Weblog second = weblog("blog-a");
        second.setId("a-completely-different-id");

        assertNotEquals(first.getId(), second.getId(), "Precondition: the ids differ");
        assertEquals(first, second,
                "Weblog equality must be by handle, not by the generated id");

        User alice = user("alice");
        User aliceAgain = user("alice");
        aliceAgain.setId("another-id");
        assertEquals(alice, aliceAgain, "User equality must be by userName, not by id");
    }

    @Test
    void weblogEntryEqualityDistinguishesTheOwningWeblog() {
        // Two blogs may legitimately both have an entry anchored "hello-world";
        // they are different entries and must not collapse in a set.
        WeblogEntry mine = entry("hello-world", weblog("blog-a"));
        WeblogEntry yours = entry("hello-world", weblog("blog-b"));

        assertNotEquals(mine, yours,
                "The same anchor on two different weblogs is two different entries");
        assertEquals(2, new HashSet<>(List.of(mine, yours)).size(),
                "Both entries must survive in a set");
    }

    /**
     * The W1 comment subsystem is gone; reader comments (V031) add exactly
     * three settings columns and nothing else on these two entities.
     */
    @Test
    void weblogAndEntryCarryOnlyTheReaderCommentSettings() {
        assertEquals(List.of("commentNotify", "commentsEnabled"), commentFields(Weblog.class));
        assertEquals(List.of("commentsClosed"), commentFields(WeblogEntry.class));
    }

    private static List<String> commentFields(Class<?> c) {
        return Arrays.stream(c.getDeclaredFields())
                .map(Field::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).contains("comment"))
                .sorted().toList();
    }
}
