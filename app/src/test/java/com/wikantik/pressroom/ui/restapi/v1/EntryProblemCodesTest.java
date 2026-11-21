package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import com.wikantik.pressroom.ui.controllers.EntryFieldRules;
import com.wikantik.pressroom.business.EntryPins;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.RevisionKeepLimitException;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.JsonLdType;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.json;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.field;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every refusal of the entry routes carries its own code, with the English
 * it always had (spec 2026-11-20-api-contract-design, Task 2, criteria 1 and
 * 3): the code asserted beside the byte-for-byte detail, and for a field
 * error its own {@code errors[].code}.
 */
class EntryProblemCodesTest {

    private static final String E1 = "/v1/weblogs/myblog/entries/e1";
    private static final String STORED = "The pier. The beach. The beach.";

    private EntriesWriteApiFixture fx;
    private WeblogEntry stored;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
        stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(fx.weblog);
        stored.setTitle("Harbour");
        stored.setText(STORED);
        stored.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(fx.entries.getWeblogEntry("e1")).thenReturn(stored);
    }

    private JsonNode create(String body) throws Exception {
        return fx.refused(post("/v1/weblogs/myblog/entries").content(body));
    }

    private JsonNode update(String body) throws Exception {
        return fx.refused(patch(E1).content(body));
    }

    private JsonNode refusedWith(org.springframework.test.web.servlet.ResultMatcher status,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return json(fx.send(request).andExpect(status));
    }

    private static String edits(int count) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add("{\"find\":\"a\",\"replace\":\"b\"}");
        }
        return "\"edits\":[" + String.join(",", items) + "]";
    }

    // ------------------------------------------------------------ lookups and state

    @Test
    void anUnknownEntryIsEntryNotFound() throws Exception {
        problem(refusedWith(status().isNotFound(), patch("/v1/weblogs/myblog/entries/nope").content("{}")),
                "entry.notFound", "No such entry.");
    }

    @Test
    void noActionWeblogIsWeblogNotFound() throws Exception {
        JsonNode problem = json(fx.mvc().perform(post("/v1/weblogs/myblog/entries")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound()));

        problem(problem, "weblog.notFound", "No such weblog.");
    }

    @Test
    void noSignedInUserIsNotAuthenticated() throws Exception {
        JsonNode problem = json(fx.mvc().perform(post("/v1/weblogs/myblog/entries")
                .requestAttr("actionWeblog", fx.weblog)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()));

        problem(problem, "auth.notAuthenticated", "Not authenticated.");
    }

    @Test
    void aTrashedEntryCannotBeEdited() throws Exception {
        stored.setStatus(WeblogEntry.PubStatus.TRASHED);

        problem(refusedWith(status().isConflict(), patch(E1).content("{}")),
                "entry.trashed", "Entry is trashed. Restore it before editing.");
    }

    @Test
    void theTrashRoutesNameWhatIsWrong() throws Exception {
        stored.setStatus(WeblogEntry.PubStatus.TRASHED);
        problem(refusedWith(status().isConflict(), delete(E1)),
                "entry.alreadyTrashed", "Entry is already trashed.");

        stored.setStatus(WeblogEntry.PubStatus.DRAFT);
        problem(refusedWith(status().isConflict(), post(E1 + "/restore")),
                "entry.notInTrash", "Entry is not trashed.");
        problem(refusedWith(status().isConflict(), post(E1 + "/delete-forever")),
                "entry.deleteForeverNeedsTrash", "Entry is not trashed; trash it first.");
    }

    @Test
    void anUnknownRevisionIsRevisionNotFound() throws Exception {
        problem(refusedWith(status().isNotFound(), post(E1 + "/revisions/nope/keep")),
                "revision.notFound", "No such revision.");
    }

    @Test
    void anEleventhKeptRevisionIsRevisionKeepLimit() throws Exception {
        WeblogEntryRevision revision = new WeblogEntryRevision();
        revision.setId("r1");
        revision.setWeblogEntry(stored);
        when(fx.entries.getRevision("r1")).thenReturn(revision);
        doThrow(new RevisionKeepLimitException()).when(fx.entries).setRevisionKept(revision, true);

        problem(refusedWith(status().isConflict(), post(E1 + "/revisions/r1/keep")),
                "revision.keepLimit", "At most 10 kept revisions for one item.");
    }

    // ------------------------------------------------------------ several fields

    @Test
    void severalBadFieldsAreInvalidFieldsEachWithItsOwnCode() throws Exception {
        JsonNode problem = create("{}");

        problem(problem, "request.invalidFields", "title is required. text is required.");
        field(problem, "title", "field.required", "title is required.");
        field(problem, "text", "field.required", "text is required.");
    }

    @Test
    void aCreateSendingUpdateOnlyFieldsNamesEachWithItsOwnCode() throws Exception {
        JsonNode problem = create("{\"title\":\"t\",\"text\":\"x\",\"revisionNote\":\"n\","
                + "\"expectedContentHash\":\"h\"}");

        assertEquals("request.invalidFields", problem.get("code").asString());
        field(problem, "revisionNote", "revisionNote.onCreate",
                "revisionNote applies only to updates; a create leaves no revision.");
        field(problem, "expectedContentHash", "field.updateOnly", "expectedContentHash applies only to updates.");
    }

    // ------------------------------------------------------------ fields

    @Test
    void clearOnACreateIsFieldUpdateOnly() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"clear\":[\"summary\"]}"),
                "clear", "field.updateOnly", "clear applies only to updates.");
    }

    @Test
    void aBlankStatusIsFieldRequired() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"status\":\"\"}"),
                "status", "field.required", "status is required.");
    }

    @Test
    void anUnknownStatusIsStatusUnknown() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"status\":\"NOPE\"}"),
                "status", "status.unknown", "Unknown status 'NOPE'.");
    }

    @Test
    void aTrashedStatusIsNotWritable() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"status\":\"TRASHED\"}"), "status",
                "entry.status.trashNotWritable", "Use DELETE to trash an entry and POST .../restore to bring it back.");
    }

    @Test
    void aValueOverItsColumnIsFieldTooLong() throws Exception {
        String note = "n".repeat(ColumnLimits.REVISION_NOTE + 1);

        oneField(update("{\"revisionNote\":\"" + note + "\"}"), "revisionNote", "field.tooLong",
                "'revisionNote' must be " + ColumnLimits.REVISION_NOTE + " characters or fewer.");
    }

    @Test
    void aMalformedExpectedContentHashIsEntryExpectedContentHashInvalid() throws Exception {
        oneField(update("{\"expectedContentHash\":\"xyz\"}"), "expectedContentHash",
                "entry.expectedContentHashInvalid",
                "expectedContentHash must be the contentHash an entry is read with: 64 hex digits.");
    }

    @Test
    void anUnknownFieldInFieldsIsFieldsUnknownOrUnknownMany() throws Exception {
        String names = String.join(", ", EntryFields.NAMES);

        oneField(fx.refused(post("/v1/weblogs/myblog/entries").param("fields", "bogus")
                        .content("{\"title\":\"t\",\"text\":\"x\"}")),
                "fields", "fields.unknown", "Unknown field 'bogus'; a field is one of: " + names + ".");
        oneField(fx.refused(post("/v1/weblogs/myblog/entries").param("fields", "bogus", "nope")
                        .content("{\"title\":\"t\",\"text\":\"x\"}")),
                "fields", "fields.unknownMany",
                "Unknown fields 'bogus', 'nope'; a field is one of: " + names + ".");
    }

    @Test
    void anUnknownCategoryIsCategoryUnknownAndABlankOneFieldBlank() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"category\":\"Travle\"}"), "category",
                "category.unknown", "Unknown category 'Travle'; it is one of: Food, Travel.");
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"category\":\"  \"}"), "category",
                "field.blank", "category cannot be blank.");
    }

    @Test
    void aCanonicalUrlThatIsNotAbsoluteIsCanonicalUrlNotAbsolute() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"canonicalUrl\":\"javascript:alert(1)\"}"),
                "canonicalUrl", "canonicalUrl.notAbsolute", "canonicalUrl must be an absolute http or https URL.");
    }

    @Test
    void anImageIdThatIsNoPublicImageIsImageNotPublic() throws Exception {
        when(fx.weblogger.getMediaFileManager()).thenReturn(mock(MediaFileManager.class));

        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"featuredImageId\":\"missing\"}"), "featuredImageId",
                "image.notPublic", "featuredImageId must be the id of an image in this weblog's media, "
                        + "outside any private directory.");
    }

    @Test
    void aStrandThatIsNoStrandPageIsStrandNotAStrandPage() throws Exception {
        when(fx.weblogger.getWeblogPageManager()).thenReturn(mock(WeblogPageManager.class));

        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"strand\":\"nope\"}"), "strand",
                "strand.notAStrandPage", "strand must be the id of one of this blog's strand pages; "
                        + "pages list shows which pages are strands.");
    }

    @Test
    void aFourthPinIsPinnedLimit() throws Exception {
        when(fx.entries.getPinnedEntryCount(fx.weblog)).thenReturn((long) EntryPins.MAX_PER_WEBLOG);

        oneField(update("{\"pinned\":true}"), "pinned", "pinned.limit", "This blog already has "
                + EntryPins.MAX_PER_WEBLOG + " pinned posts, the most it allows. Unpin one first.");
    }

    // ------------------------------------------------------------ times, location

    @Test
    void scheduledWithNoPubTimeIsPubTimeRequiredForScheduled() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"status\":\"SCHEDULED\"}"), "pubTime",
                "pubTime.requiredForScheduled", "pubTime is required when status is SCHEDULED.");
    }

    @Test
    void aPubTimeThatDoesNotParseIsPubTimeInvalid() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"pubTime\":\"soon\"}"), "pubTime", "pubTime.invalid",
                "pubTime must be a wall-clock time in the weblog's zone, for example 2026-03-01T09:30, "
                        + "or an instant such as 2026-03-01T08:30:00Z.");
    }

    @Test
    void anEventTimeThatIsBlankOrDoesNotParseIsEventTimeInvalid() throws Exception {
        String detail = " must be a wall-clock time in the weblog's zone, for example 2026-08-02T19:30, "
                + "or an instant such as 2026-08-02T17:30:00Z.";

        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"eventStart\":\"nonsense\"}"), "eventStart",
                "eventTime.invalid", "eventStart" + detail);
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"eventEnd\":\" \"}"), "eventEnd",
                "eventTime.invalid", "eventEnd" + detail);
    }

    @Test
    void anEventEndingBeforeItStartsIsEventEndBeforeStart() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"eventStart\":\"2026-08-02T19:30\","
                + "\"eventEnd\":\"2026-08-01T10:00\"}"), "eventEnd", "eventEnd.beforeStart",
                "eventEnd must not be before eventStart.");
    }

    @Test
    void anUnknownJsonLdTypeIsJsonLdTypeUnknown() throws Exception {
        String names = Arrays.stream(JsonLdType.values()).map(Enum::name).collect(Collectors.joining(", "));

        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"jsonLdType\":\"NOPE\"}"), "jsonLdType",
                "jsonLdType.unknown", "Unknown jsonLdType 'NOPE'; it is one of " + names + ".");
    }

    @Test
    void coordinatesOffTheGlobeAreGeoOutOfRange() throws Exception {
        JsonNode problem = create("{\"title\":\"t\",\"text\":\"x\",\"geoLatitude\":91,\"geoLongitude\":181}");

        assertEquals("request.invalidFields", problem.get("code").asString());
        field(problem, "geoLatitude", "geo.outOfRange", "geoLatitude must be between -90 and 90.");
        field(problem, "geoLongitude", "geo.outOfRange", "geoLongitude must be between -180 and 180.");
    }

    // ------------------------------------------------------------ anchors

    @Test
    void aMalformedAnchorIsAnchorInvalidForm() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"Torrey Pines\"}"), "anchor",
                "anchor.invalidForm", "anchor must be lower-case letters and digits in words joined by one '-' "
                        + "or '_', at most " + EntryFieldRules.ANCHOR_MAX_LENGTH
                        + " characters, for example torrey-pines.");
    }

    @Test
    void anUpdateChangingTheAnchorIsAnchorImmutable() throws Exception {
        oneField(update("{\"anchor\":\"other-place\"}"), "anchor", "anchor.immutable",
                "anchor is set when an entry is created and cannot change.");
    }

    @Test
    void anAnchorAnotherEntryHoldsIsAnchorInUseOrHeldByTrashedEntry() throws Exception {
        WeblogEntry holder = new WeblogEntry();
        holder.setId("holder");
        holder.setWebsite(fx.weblog);
        holder.setAnchor("torrey-pines");
        holder.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        when(fx.entries.getWeblogEntryByAnchor(fx.weblog, "torrey-pines")).thenReturn(holder);
        String body = "{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"torrey-pines\"}";

        oneField(refusedWith(status().isConflict(), post("/v1/weblogs/myblog/entries").content(body)),
                "anchor", "anchor.inUse", "anchor 'torrey-pines' is already used by another entry.");

        holder.setStatus(WeblogEntry.PubStatus.TRASHED);
        oneField(refusedWith(status().isConflict(), post("/v1/weblogs/myblog/entries").content(body)),
                "anchor", "anchor.heldByTrashedEntry",
                "anchor 'torrey-pines' is held by an entry in the trash; delete it forever (delete_forever) "
                        + "to free it.");
    }

    // ------------------------------------------------------------ clear

    @Test
    void clearNamingWhatCannotBeClearedIsClearNotClearable() throws Exception {
        oneField(update("{\"clear\":[\"bogus\"]}"), "clear", "clear.notClearable",
                "\"bogus\" cannot be cleared. Clearable: " + String.join(", ", EntryDtos.ENTRY_CLEARABLE) + ".");
    }

    @Test
    void clearingOneOfAPairIsClearTogetherOnly() throws Exception {
        oneField(update("{\"clear\":[\"geoLatitude\"]}"), "clear", "clear.togetherOnly",
                "geoLatitude and geoLongitude are cleared together.");
    }

    @Test
    void aFieldSentAndClearedIsClearSetAndCleared() throws Exception {
        oneField(update("{\"summary\":\"x\",\"clear\":[\"summary\"]}"), "clear", "clear.setAndCleared",
                "summary is both set and cleared.");
    }

    // ------------------------------------------------------------ edits

    @Test
    void editsWithTextIsEditsWithText() throws Exception {
        oneField(update("{\"text\":\"x\"," + edits(1) + "}"), "edits", "edits.withText",
                "Give text or edits, not both.");
    }

    @Test
    void moreEditsThanOneRequestAppliesIsEditsTooMany() throws Exception {
        oneField(update("{" + edits(51) + "}"), "edits", "edits.tooMany",
                "At most 50 edits are applied in one request; this one has 51.");
    }

    @Test
    void eachMalformedEditHasItsOwnCode() throws Exception {
        oneField(update("{\"edits\":[null]}"), "edits[0]", "edits.empty",
                "edits[0] is empty; an edit is {find, replace}.");
        oneField(update("{\"edits\":[{\"replace\":\"x\"}]}"), "edits[0]", "edits.noFind",
                "edits[0] has no find; give the exact text to change.");
        oneField(update("{\"edits\":[{\"find\":\"The pier.\"}]}"), "edits[0]", "edits.noReplace",
                "edits[0] has no replace; send \"\" to delete the text found.");
        oneField(update("{\"edits\":[{\"find\":\"zzz\",\"replace\":\"x\"}]}"), "edits[0]", "edits.notFound",
                "edits[0] not found in the entry's text.");
        oneField(update("{\"edits\":[{\"find\":\"The beach.\",\"replace\":\"x\"}]}"), "edits[0]",
                "edits.ambiguous", "edits[0] found 2 times; include more surrounding text so it matches once.");
    }

    @Test
    void editsOnACreateIsEditsOnCreate() throws Exception {
        oneField(create("{\"title\":\"t\",\"text\":\"x\"," + edits(1) + "}"), "edits", "edits.onCreate",
                "edits apply only to updates; a create sends text.");
    }

    @Test
    void editsOnAPreviewWithNoEntryIsEditsPreviewNeedsEntry() throws Exception {
        oneField(fx.refused(post("/v1/weblogs/myblog/entries/preview").content("{" + edits(1) + "}")),
                "edits", "edits.previewNeedsEntry",
                "edits change an existing entry's text; preview with its id, or send text.");
    }
}
