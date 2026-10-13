package org.apache.roller.weblogger.ui.restapi.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** featuredImageId/ogImageId name this weblog's public images (spec 2026-10-13, section 4; criterion 10). */
class EntriesWriteApiMediaIdTest {

    private EntriesWriteApiFixture fx;
    private final MediaFileManager media = mock(MediaFileManager.class);

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
        when(fx.weblogger.getMediaFileManager()).thenReturn(media);
        Weblog other = new Weblog();
        other.setId("other-id");
        MediaFileDirectory mine = new MediaFileDirectory(fx.weblog, "default", null);
        MediaFileDirectory hidden = new MediaFileDirectory(fx.weblog, "hidden", null);
        hidden.setPrivate(true);
        MediaFileDirectory theirs = new MediaFileDirectory(other, "default", null);
        stub("img", mine, "image/jpeg");
        stub("pdf", mine, "application/pdf");
        stub("private", hidden, "image/jpeg");
        stub("foreign", theirs, "image/jpeg");
    }

    private void stub(String id, MediaFileDirectory directory, String contentType) throws Exception {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setDirectory(directory);
        file.setWeblog(directory.getWeblog());
        file.setContentType(contentType);
        when(media.getMediaFile(id)).thenReturn(file);
    }

    private int create(String field, String id) throws Exception {
        return fx.send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"" + field + "\":\"" + id + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void aPublicImageOfThisWeblogIsAccepted() throws Exception {
        assertEquals(201, create("featuredImageId", "img"));
        assertEquals(201, create("ogImageId", "img"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "pdf", "private", "missing"})
    void anythingElseIsA400OnTheField(String id) throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"featuredImageId\":\"" + id + "\",\"ogImageId\":\"" + id + "\"}"));

        assertEquals(List.of("featuredImageId", "ogImageId"), EntriesWriteApiFixture.fields(problem));
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    /** Review Focus 1: the stored id of a file deleted since is not re-checked. */
    @Test
    void aPatchCarryingTheStoredIdOfADeletedFileSucceeds() throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(fx.weblog);
        stored.setTitle("Harbour");
        stored.setFeaturedImageId("deleted-since");
        when(fx.entries.getWeblogEntry("e1")).thenReturn(stored);

        fx.send(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"featuredImageId\":\"deleted-since\",\"summary\":\"new\"}")).andExpect(status().isOk());

        assertEquals("new", stored.getSummary());
        verify(media, never()).getMediaFile("deleted-since");
    }
}
