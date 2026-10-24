package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import java.util.Set;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * An entry write reports every bad field in one 400, and a refused write
 * changes nothing (spec 2026-10-13, section 1; acceptance criteria 1, 2, 3, 5).
 */
class EntriesWriteApiFieldErrorsTest {

    private EntriesWriteApiFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
    }

    @Test
    void aCreateWithTwoBadFieldsIsOne400NamingBothAndSavesNothing() throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"category\":\"Travle\",\"geoLatitude\":91}"));

        assertEquals(Set.of("category", "geoLatitude"), Set.copyOf(EntriesWriteApiFixture.fields(problem)));
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    @Test
    void anUnknownCategoryNamesTheWeblogsCategories() throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"category\":\"Travle\"}"));

        assertEquals("Unknown category 'Travle'; it is one of: Food, Travel.", problem.get("detail").asString());
        assertEquals(List.of("category"), EntriesWriteApiFixture.fields(problem));
    }

    @Test
    void aSingleProblemKeepsTodaysDetailAndNamesItsField() throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"status\":\"NOPE\"}"));

        assertEquals("Unknown status 'NOPE'.", problem.get("detail").asString());
        assertEquals(List.of("status"), EntriesWriteApiFixture.fields(problem));
    }

    @Test
    void aCreateMissingTitleAndTextNamesBoth() throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content("{}"));

        assertEquals(List.of("title", "text"), EntriesWriteApiFixture.fields(problem));
    }

    /**
     * Criterion 3: the valid fields of a refused PATCH are not applied
     * either. Before this change applyWrite set the title, then threw on
     * jsonLdType, leaving the managed entity half-written.
     */
    @Test
    void aPatchRefusedForOneFieldChangesNoFieldAtAll() throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(fx.weblog);
        stored.setTitle("kept");
        stored.setSummary("kept too");
        stored.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(fx.entries.getWeblogEntry("e1")).thenReturn(stored);

        fx.refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"title\":\"new\",\"summary\":\"new\",\"jsonLdType\":\"NOPE\"}"));

        assertEquals("kept", stored.getTitle());
        assertEquals("kept too", stored.getSummary());
        verify(fx.entries, never()).saveWeblogEntry(any(), any());
        verify(fx.entries, never()).saveWeblogEntry(any(), any(), any());
    }

    /** The event-order check still sees a start stored on the entry, through the scratch copy. */
    @Test
    void aPatchEndingBeforeTheStoredStartIsRefused() throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e2");
        stored.setWebsite(fx.weblog);
        stored.setTitle("Festival");
        stored.setEventStart(java.sql.Timestamp.valueOf("2026-08-02 19:30:00"));
        when(fx.entries.getWeblogEntry("e2")).thenReturn(stored);

        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e2").content(
                "{\"eventEnd\":\"2026-08-01T10:00\"}"));

        assertEquals(List.of("eventEnd"), EntriesWriteApiFixture.fields(problem));
        assertEquals(null, stored.getEventEnd());
    }

    /** A pubTime that does not parse is one error, not also "pubTime is required" for SCHEDULED. */
    @Test
    void aMalformedPubTimeWithScheduledIsOneError() throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"status\":\"SCHEDULED\",\"pubTime\":\"soon\"}"));

        assertEquals(List.of("pubTime"), EntriesWriteApiFixture.fields(problem));
    }

    /** A malformed eventStart is one error; the valid eventEnd is not judged against the stored start. */
    @Test
    void aMalformedEventStartDoesNotMakeAValidEventEndWrong() throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e3");
        stored.setWebsite(fx.weblog);
        stored.setTitle("Festival");
        stored.setEventStart(java.sql.Timestamp.valueOf("2026-08-02 19:30:00"));
        when(fx.entries.getWeblogEntry("e3")).thenReturn(stored);

        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e3").content(
                "{\"eventStart\":\"nonsense\",\"eventEnd\":\"2026-08-01T10:00\"}"));

        assertEquals(List.of("eventStart"), EntriesWriteApiFixture.fields(problem));
    }
}
