package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.HashSet;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterisation test (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 3): written against the code before the category and media-folder
 * rules moved into {@code CategoryRules} and {@code MediaRules}, and passing
 * on it. It pins what the API answers today for every case the move touches,
 * whole problem bodies included. Cases the task changes on purpose are named
 * "today" and are rewritten, new expectation first, in the commit that
 * changes them.
 */
class CategoryAndFolderApiCharacterisationTest {

    private static final String CATEGORIES = "/v1/weblogs/myblog/categories";
    private static final String MEDIA = "/v1/weblogs/myblog/media";
    private static final String TOO_LONG_NAME = "n".repeat(256);

    private WeblogEntryManager entries;
    private MediaFileManager files;
    private Weblog weblog;
    private WeblogCategory travel;
    private MediaFileDirectory images;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        entries = mock(WeblogEntryManager.class);
        files = mock(MediaFileManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(weblogger.getMediaFileManager()).thenReturn(files);
        URLStrategy urls = mock(URLStrategy.class);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(urls.getMediaFileURL(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn("https://example.invalid/media/x");
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setWeblogCategories(new ArrayList<>());
        weblog.setMediaFileDirectories(new ArrayList<>());
        travel = category("c1", "Travel");
        images = new MediaFileDirectory(weblog, "images", null);
        images.setId("d1");
        images.setMediaFiles(new HashSet<>());
        when(files.getMediaFileDirectory("d1")).thenReturn(images);
        when(files.createMediaFileDirectory(any(), any())).thenAnswer(call -> {
            String raw = call.getArgument(1);
            return new MediaFileDirectory(weblog, raw.startsWith("/") ? raw.substring(1) : raw, null);
        });
        CategoriesApi categories = new CategoriesApi();
        categories.weblogger = weblogger;
        MediaApi media = new MediaApi();
        media.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(categories, media).setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private WeblogCategory category(String id, String name) throws Exception {
        WeblogCategory made = new WeblogCategory(weblog, name, null, null);
        made.setId(id);
        when(entries.getWeblogCategory(id)).thenReturn(made);
        when(entries.getWeblogCategoryByName(weblog, name)).thenReturn(made);
        return made;
    }

    private JsonNode answer(ResultMatcher status, MockHttpServletRequestBuilder request, String body)
            throws Exception {
        String text = mvc.perform(request.requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status).andReturn().getResponse().getContentAsString();
        return text.isEmpty() ? null : new ObjectMapper().readTree(text);
    }

    private static String name(String value) {
        return "{\"name\":\"" + value + "\"}";
    }

    // --- category names ---

    @Test
    void aBlankCategoryNameIsRequiredOnCreateAndBlankOnRename() throws Exception {
        oneField(answer(status().isBadRequest(), post(CATEGORIES), "{}"),
                "name", "field.required", "name is required.");
        oneField(answer(status().isBadRequest(), post(CATEGORIES), name("   ")),
                "name", "field.required", "name is required.");
        oneField(answer(status().isBadRequest(), patch(CATEGORIES + "/c1"), name("  ")),
                "name", "field.blank", "name cannot be blank.");
    }

    @Test
    void aCategoryNameOver255IsTooLongOnCreateAndRename() throws Exception {
        String detail = "'name' must be 255 characters or fewer.";
        oneField(answer(status().isBadRequest(), post(CATEGORIES), name(TOO_LONG_NAME)),
                "name", "field.tooLong", detail);
        oneField(answer(status().isBadRequest(), patch(CATEGORIES + "/c1"), name(TOO_LONG_NAME)),
                "name", "field.tooLong", detail);
        verify(entries, never()).saveWeblogCategory(any());
    }

    @Test
    void aCategoryDescriptionOver255IsTooLong() throws Exception {
        String body = "{\"name\":\"Food\",\"description\":\"" + "d".repeat(256) + "\"}";
        oneField(answer(status().isBadRequest(), post(CATEGORIES), body),
                "description", "field.tooLong", "'description' must be 255 characters or fewer.");
        oneField(answer(status().isBadRequest(), patch(CATEGORIES + "/c1"),
                        "{\"description\":\"" + "d".repeat(256) + "\"}"),
                "description", "field.tooLong", "'description' must be 255 characters or fewer.");
    }

    /**
     * Changed on purpose (spec Task 3, AC3a; owner decision 2026-11-21): the API refuses a category name
     * HTML escaping would change, as the screen always has. It took them before.
     */
    @Test
    void aCategoryNameHtmlEscapingWouldChangeIsRefusedOnCreateAndRename() throws Exception {
        String detail = "A category name cannot contain an ampersand, an angle bracket or a double quote.";
        for (String bad : new String[] {"R&D", "a<b", "a>b", "a\\\"b"}) {
            oneField(answer(status().isBadRequest(), post(CATEGORIES), name(bad)),
                    "name", "category.nameInvalid", detail);
            oneField(answer(status().isBadRequest(), patch(CATEGORIES + "/c1"), name(bad)),
                    "name", "category.nameInvalid", detail);
        }
        verify(entries, never()).saveWeblogCategory(any());
    }

    /** HTML 4 escaping leaves an apostrophe alone, so it is no HTML character to the rule either. */
    @Test
    void anApostropheInACategoryNameIsAccepted() throws Exception {
        assertEquals("Can't", answer(status().isCreated(), post(CATEGORIES), name("Can't")).get("name").asString());
    }

    @Test
    void aTakenCategoryNameIsAConflictOnCreateAndRename() throws Exception {
        category("c2", "Food");
        oneField(answer(status().isConflict(), post(CATEGORIES), name("Travel")),
                "name", "category.nameInUse", "A category named 'Travel' already exists.");
        oneField(answer(status().isConflict(), patch(CATEGORIES + "/c1"), name("Food")),
                "name", "category.nameInUse", "A category named 'Food' already exists.");
        verify(entries, never()).saveWeblogCategory(any());
    }

    @Test
    void renamingACategoryToItsOwnNameIsAccepted() throws Exception {
        assertEquals("Travel", answer(status().isOk(), patch(CATEGORIES + "/c1"), name("Travel")).get("name")
                .asString());
        // padded: trimmed to the name it already has
        assertEquals("Travel", answer(status().isOk(), patch(CATEGORIES + "/c1"), name(" Travel ")).get("name")
                .asString());
    }

    @Test
    void aCategoryNameResentUnchangedIsNeverJudgedAgain() throws Exception {
        WeblogCategory odd = category("c3", "R&D");
        assertEquals("R&D", answer(status().isOk(), patch(CATEGORIES + "/c3"), name("R&D")).get("name").asString());
        assertEquals("R&D", odd.getName());
    }

    // --- category removal ---

    @Test
    void theLastCategoryCannotBeRemoved() throws Exception {
        problem(answer(status().isConflict(), delete(CATEGORIES + "/c1"), ""),
                "category.lastOne", "A weblog must keep at least one category.");
    }

    @Test
    void aCategoryInUseCannotBeRemovedWithoutAMoveTo() throws Exception {
        category("c2", "Food");
        when(entries.isWeblogCategoryInUse(travel)).thenReturn(true);
        problem(answer(status().isConflict(), delete(CATEGORIES + "/c1"), ""),
                "category.hasEntries", "Category has entries; pass moveTo to move them to another category first.");
        verify(entries, never()).removeWeblogCategory(any());
    }

    @Test
    void moveToNamingTheCategoryItselfIsRefusedOnMoveTo() throws Exception {
        category("c2", "Food");
        oneField(answer(status().isBadRequest(), delete(CATEGORIES + "/c1").param("moveTo", "c1"), ""),
                "moveTo", "category.moveToSelf", "moveTo cannot name the category being deleted.");
    }

    @Test
    void aBlankMoveToIsNoMoveToAndAnEmptyCategoryIsRemoved() throws Exception {
        category("c2", "Food");
        answer(status().isNoContent(), delete(CATEGORIES + "/c1").param("moveTo", " "), "");
        verify(entries).removeWeblogCategory(travel);
    }

    // --- folder names ---

    private JsonNode folder(ResultMatcher status, String body) throws Exception {
        return answer(status, post(MEDIA + "/directories"), body);
    }

    @Test
    void anEmptyFolderNameIsRequired() throws Exception {
        oneField(folder(status().isBadRequest(), "{}"), "name", "field.required", "name is required.");
        oneField(folder(status().isBadRequest(), name("  ")), "name", "field.required", "name is required.");
        oneField(folder(status().isBadRequest(), name("/")), "name", "field.required", "name is required.");
    }

    @Test
    void defaultIsAReservedFolderName() throws Exception {
        String detail = "'default' is a reserved directory name.";
        oneField(folder(status().isBadRequest(), name("default")), "name", "directory.nameReserved", detail);
        oneField(folder(status().isBadRequest(), name("/default")), "name", "directory.nameReserved", detail);
    }

    @Test
    void aFolderNameOver255IsTooLong() throws Exception {
        oneField(folder(status().isBadRequest(), name(TOO_LONG_NAME)),
                "name", "field.tooLong", "'name' must be 255 characters or fewer.");
    }

    @Test
    void aTakenFolderNameIsAConflictCountingTheLeadingSlashAsNothing() throws Exception {
        String detail = "A directory named 'images' already exists.";
        oneField(folder(status().isConflict(), name("images")), "name", "directory.nameInUse", detail);
        oneField(folder(status().isConflict(), name("/images")), "name", "directory.nameInUse", detail);
    }

    @Test
    void aFolderDescriptionOver255IsTooLong() throws Exception {
        oneField(folder(status().isBadRequest(), "{\"name\":\"x\",\"description\":\"" + "d".repeat(256) + "\"}"),
                "description", "field.tooLong", "'description' must be 255 characters or fewer.");
    }

    @Test
    void aFolderNameIsTrimmedAndCreated() throws Exception {
        JsonNode created = folder(status().isCreated(), name("  trips  "));
        assertEquals("trips", created.get("name").asString());
    }

    @Test
    void aLeadingSlashIsStrippedOnce() throws Exception {
        assertEquals("x", folder(status().isCreated(), name("/x")).get("name").asString());
    }

    /** Changed on purpose (spec Task 3, AC3c): an inner slash was passed through to the manager and created. */
    @Test
    void aSlashInsideAFolderNameIsRefused() throws Exception {
        String detail = "A directory name cannot contain a slash.";
        for (String bad : new String[] {"a/b", "x/", "//x"}) {
            oneField(folder(status().isBadRequest(), name(bad)), "name", "directory.nameInvalid", detail);
        }
        verify(files, never()).createMediaFileDirectory(any(), any());
    }

    // --- file names ---

    private MediaFile file(String id, String fileName) throws Exception {
        MediaFile made = new MediaFile();
        made.setId(id);
        made.setName(fileName);
        made.setDirectory(images);
        images.getMediaFiles().add(made);
        when(files.getMediaFile(id)).thenReturn(made);
        return made;
    }

    @Test
    void aBlankFileNameIsRefused() throws Exception {
        file("f1", "dog.jpg");
        oneField(answer(status().isBadRequest(), patch(MEDIA + "/f1"), name(" ")),
                "name", "field.blank", "name cannot be blank.");
    }

    @Test
    void aFileNameOver255IsTooLong() throws Exception {
        file("f1", "dog.jpg");
        oneField(answer(status().isBadRequest(), patch(MEDIA + "/f1"), name(TOO_LONG_NAME)),
                "name", "field.tooLong", "'name' must be 255 characters or fewer.");
    }

    @Test
    void aFileNameIsTrimmedAndKeptWhenItIsItsOwn() throws Exception {
        file("f1", "dog.jpg");
        assertEquals("dog.jpg", answer(status().isOk(), patch(MEDIA + "/f1"), name(" dog.jpg ")).get("name")
                .asString());
    }

    /** Changed on purpose (spec Task 3, AC3d): the API had no duplicate-name check on a rename. */
    @Test
    void renamingAFileOntoAnotherFilesNameInItsDirectoryIsAConflictAndNothingChanges() throws Exception {
        MediaFile dog = file("f1", "dog.jpg");
        file("f2", "cat.jpg");
        oneField(answer(status().isConflict(), patch(MEDIA + "/f1"), name("cat.jpg")),
                "name", "media.nameInUse", "A file named 'cat.jpg' already exists in that directory.");
        assertEquals("dog.jpg", dog.getName());
        verify(files, never()).updateMediaFile(any(), any());
    }

    @Test
    void aTakenNameIsJudgedInTheDirectoryTheFileIsMovingTo() throws Exception {
        MediaFile dog = file("f1", "dog.jpg");
        MediaFileDirectory other = new MediaFileDirectory(weblog, "other", null);
        other.setId("d2");
        other.setMediaFiles(new HashSet<>());
        when(files.getMediaFileDirectory("d2")).thenReturn(other);
        MediaFile cat = new MediaFile();
        cat.setId("f3");
        cat.setName("cat.jpg");
        cat.setDirectory(other);
        other.getMediaFiles().add(cat);
        oneField(answer(status().isConflict(), patch(MEDIA + "/f1"), "{\"name\":\"cat.jpg\",\"directoryId\":\"d2\"}"),
                "name", "media.nameInUse", "A file named 'cat.jpg' already exists in that directory.");
        assertEquals("dog.jpg", dog.getName());
    }
}
