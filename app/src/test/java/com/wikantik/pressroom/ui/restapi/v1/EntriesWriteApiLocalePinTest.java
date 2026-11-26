package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.fields;
import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code locale} and {@code pinnedToMain} on an entry write and view (spec 2026-11-25 React admin migration, A7).
 * The editor posts the entry's locale as a hidden field it round-trips, and shows the pin-to-main checkbox to a
 * site administrator only, keeping the stored value for anyone else; the API mirrors that, refusing (naming the
 * field) a write that would change the pin, and passing one that sends the stored value back.
 */
class EntriesWriteApiLocalePinTest {

    private static final String ENTRIES = "/v1/weblogs/myblog/entries";

    private EntriesWriteApiFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
    }

    /** A publisher on the weblog who is not a site administrator. */
    private void notSiteAdmin() throws Exception {
        when(fx.users.checkPermission(any(GlobalPermission.class), any())).thenReturn(false);
    }

    private WeblogEntry stored() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e1");
        entry.setWebsite(fx.weblog);
        entry.setCreatorUserName("maiia");
        entry.setTitle("Stored");
        entry.setText("Stored text.");
        entry.setStatus(PubStatus.DRAFT);
        entry.setLocale("en_US");
        when(fx.entries.getWeblogEntry("e1")).thenReturn(entry);
        return entry;
    }

    @Test
    void aCreateCarriesTheLocaleAndASiteAdminsPinToTheMainPage() throws Exception {
        JsonNode created = json(fx.send(post(ENTRIES).content(
                "{\"title\":\"T\",\"text\":\"x\",\"locale\":\"it_IT\",\"pinnedToMain\":true}"))
                .andExpect(status().isCreated()));

        ArgumentCaptor<WeblogEntry> saved = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(fx.entries).saveWeblogEntry(saved.capture());
        assertEquals("it_IT", saved.getValue().getLocale());
        assertEquals(Boolean.TRUE, saved.getValue().getPinnedToMain());
        assertEquals("it_IT", created.get("locale").asString());
        assertTrue(created.get("pinnedToMain").asBoolean());
    }

    @Test
    void anUpdateChangesThemAndLeavesThemAloneWhenAbsent() throws Exception {
        WeblogEntry entry = stored();

        fx.send(patch(ENTRIES + "/e1").content("{\"title\":\"New\"}")).andExpect(status().isOk());
        assertEquals("en_US", entry.getLocale());
        assertEquals(Boolean.FALSE, entry.getPinnedToMain());

        JsonNode updated = json(fx.send(patch(ENTRIES + "/e1")
                .content("{\"locale\":\"fr_FR\",\"pinnedToMain\":true}")).andExpect(status().isOk()));
        assertEquals("fr_FR", entry.getLocale());
        assertEquals(Boolean.TRUE, entry.getPinnedToMain());
        assertEquals("fr_FR", updated.get("locale").asString());
        assertTrue(updated.get("pinnedToMain").asBoolean());
    }

    @Test
    void aBlankLocaleIsNoLocaleSoTheSaveFillsInTheWeblogs() throws Exception {
        WeblogEntry entry = stored();

        fx.send(patch(ENTRIES + "/e1").content("{\"locale\":\"  \"}")).andExpect(status().isOk());

        assertNull(entry.getLocale());
    }

    @Test
    void aLocaleOverTheColumnIsAFieldError() throws Exception {
        stored();

        JsonNode problem = fx.refused(patch(ENTRIES + "/e1").content("{\"locale\":\"" + "x".repeat(21) + "\"}"));

        assertEquals(java.util.List.of("locale"), fields(problem));
        assertEquals("field.tooLong", problem.get("errors").get(0).get("code").asString());
        verify(fx.entries, never()).saveWeblogEntry(any(), any(), any());
    }

    @Test
    void aWriterWhoIsNotASiteAdminCannotChangeThePinToTheMainPage() throws Exception {
        notSiteAdmin();
        WeblogEntry entry = stored();

        JsonNode problem = json(fx.send(patch(ENTRIES + "/e1").content("{\"pinnedToMain\":true}"))
                .andExpect(status().isForbidden()));

        assertEquals("entry.needsSiteAdmin", problem.get("code").asString());
        assertEquals("pinnedToMain", problem.get("errors").get(0).get("field").asString());
        assertEquals(Boolean.FALSE, entry.getPinnedToMain());
        verify(fx.entries, never()).saveWeblogEntry(any(), any(), any());

        json(fx.send(post(ENTRIES).content("{\"title\":\"T\",\"text\":\"x\",\"pinnedToMain\":true}"))
                .andExpect(status().isForbidden()));
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    @Test
    void theStoredPinSentBackIsNoRefusalForAWriterWhoIsNotASiteAdmin() throws Exception {
        notSiteAdmin();
        WeblogEntry entry = stored();
        entry.setPinnedToMain(true);

        fx.send(patch(ENTRIES + "/e1").content("{\"pinnedToMain\":true,\"locale\":\"de_DE\"}"))
                .andExpect(status().isOk());

        assertEquals(Boolean.TRUE, entry.getPinnedToMain());
        assertEquals("de_DE", entry.getLocale(), "the locale is anyone's");
    }
}
