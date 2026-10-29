package com.wikantik.pressroom.ui.rendering.velocity;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.servlet.ServletContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How the Velocity resource loader finds, caches and re-checks templates.
 *
 * <p>The loader keeps a {@code templatePaths} map remembering which configured
 * root a given template was found under, so that a later staleness check can go
 * straight back to the same file. Everything interesting here is about that map
 * being consulted with the same key it was filled with -- see
 * {@link #aResourceThatWasNeverLoadedBuildsAPathContainingTheWordNull()}.
 *
 * <p>The loader's collaborators are protected fields rather than constructor
 * arguments, so these tests set them directly instead of calling {@code init()},
 * which would reach for a real {@code PressroomContext}.
 */
class WebappResourceLoaderBehaviourTest {

    private WebappResourceLoader loader;
    private ServletContext context;

    @BeforeEach
    void createLoader() {
        context = mock(ServletContext.class);
        loader = new WebappResourceLoader();
        loader.servletContext = context;
        loader.paths = new String[]{"/WEB-INF/velocity/"};
        loader.templatePaths = new HashMap<>();
    }

    private static Resource resourceNamed(String name) {
        Resource resource = mock(Resource.class);
        when(resource.getName()).thenReturn(name);
        return resource;
    }

    private static ByteArrayInputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    // --- finding a template ------------------------------------------------

    @Test
    void aTemplateWithNoNameIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> loader.getResourceReader(null, "UTF-8"));
        assertThrows(ResourceNotFoundException.class, () -> loader.getResourceReader("", "UTF-8"));
    }

    @Test
    void aTemplateIsFoundUnderAConfiguredRoot() throws IOException {
        when(context.getResourceAsStream("/WEB-INF/velocity/weblog.vm"))
                .thenReturn(stream("hello"));

        try (Reader reader = loader.getResourceReader("weblog.vm", "UTF-8")) {
            assertNotNull(reader);
            assertEquals('h', reader.read(), "the reader must be positioned at the template");
        }
    }

    @Test
    void findingATemplateRemembersWhichRootItCameFrom() {
        when(context.getResourceAsStream("/WEB-INF/velocity/weblog.vm"))
                .thenReturn(stream("hello"));

        loader.getResourceReader("weblog.vm", "UTF-8");

        assertEquals("/WEB-INF/velocity/", loader.templatePaths.get("weblog.vm"),
                "The root is remembered so the next lookup can skip the search");
    }

    @Test
    void aRememberedRootIsUsedBeforeSearching() {
        loader.templatePaths.put("weblog.vm", "/remembered/");
        when(context.getResourceAsStream("/remembered/weblog.vm")).thenReturn(stream("hi"));

        assertNotNull(loader.getResourceReader("weblog.vm", "UTF-8"),
                "A remembered root must be tried first, without walking the path list");
    }

    @Test
    void aRenditionSuffixIsNotPartOfTheFileName() {
        // names arrive as <template>|<renditionType>; only the part before the
        // bar names a file
        when(context.getResourceAsStream("/WEB-INF/velocity/weblog.vm"))
                .thenReturn(stream("hello"));

        assertNotNull(loader.getResourceReader("weblog.vm|standard", "UTF-8"));
    }

    @Test
    void aTemplateThatIsNowhereIsReportedNotFound() {
        when(context.getResourceAsStream(anyString())).thenReturn(null);

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("missing.vm", "UTF-8"));
        assertTrue(thrown.getMessage().contains("not found"), thrown.getMessage());
    }

    @Test
    void theFirstFailureIsCarriedIntoTheNotFoundReport() {
        when(context.getResourceAsStream(anyString()))
                .thenThrow(new IllegalStateException("context is closed"));

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("missing.vm", "UTF-8"));

        assertTrue(thrown.getMessage().contains("Due to:"),
                "A failure while searching must be reported, not swallowed into a bare "
                        + "'not found': " + thrown.getMessage());
    }

    // --- staleness checks --------------------------------------------------

    @Test
    void aContainerThatCannotMapARealPathReportsNothingStale(@TempDir Path unused) {
        when(context.getRealPath("/")).thenReturn(null);

        assertFalse(loader.isSourceModified(resourceNamed("weblog.vm")),
                "Served from a .war there is no real path, so staleness is unknowable and "
                        + "must not be reported as modified");
        assertEquals(0L, loader.getLastModified(resourceNamed("weblog.vm")),
                "and there is no timestamp to give either");
    }

    /**
     * Note the trailing slash on the mocked real path. {@code getCachedFile}
     * concatenates {@code rootPath + savedPath} with no separator between them,
     * so it assumes the container hands back a path that already ends in one.
     * Tomcat does; the servlet spec does not promise it. Mocking it without the
     * slash silently glues the two together and every lookup below misses --
     * which is exactly what happened while writing these tests.
     */
    @Test
    void aTemplateWhoseFileIsGoneCountsAsModified(@TempDir Path root) throws IOException {
        when(context.getRealPath("/")).thenReturn(root + "/");
        loader.templatePaths.put("weblog.vm", "sub/");
        Files.createDirectories(root.resolve("sub"));

        assertTrue(loader.isSourceModified(resourceNamed("weblog.vm")),
                "A template whose file has been deleted or moved must be reloaded");
    }

    @Test
    void aTemplateThatHasNotChangedIsNotModified(@TempDir Path root) throws IOException {
        when(context.getRealPath("/")).thenReturn(root + "/");
        loader.paths = new String[]{"sub/"};
        loader.templatePaths.put("weblog.vm", "sub/");

        Path file = root.resolve("sub/weblog.vm");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello");

        Resource resource = resourceNamed("weblog.vm");
        when(resource.getLastModified()).thenReturn(file.toFile().lastModified());

        assertFalse(loader.isSourceModified(resource),
                "Same file, same timestamp: nothing to reload");
    }

    @Test
    void aTemplateWithANewerTimestampIsModified(@TempDir Path root) throws IOException {
        when(context.getRealPath("/")).thenReturn(root + "/");
        loader.paths = new String[]{"sub/"};
        loader.templatePaths.put("weblog.vm", "sub/");

        Path file = root.resolve("sub/weblog.vm");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello");

        Resource resource = resourceNamed("weblog.vm");
        when(resource.getLastModified()).thenReturn(file.toFile().lastModified() - 10_000L);

        assertTrue(loader.isSourceModified(resource),
                "The file on disk is newer than what Velocity holds, so it must be reloaded");
    }

    @Test
    void theLastModifiedOfAReadableTemplateIsTheFilesOwn(@TempDir Path root) throws IOException {
        when(context.getRealPath("/")).thenReturn(root + "/");
        loader.templatePaths.put("weblog.vm", "sub/");

        Path file = root.resolve("sub/weblog.vm");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello");

        assertEquals(file.toFile().lastModified(),
                loader.getLastModified(resourceNamed("weblog.vm")));
    }

    @Test
    void anUnreadableTemplateHasNoLastModified(@TempDir Path root) {
        when(context.getRealPath("/")).thenReturn(root + "/");
        loader.templatePaths.put("weblog.vm", "sub/");

        assertEquals(0L, loader.getLastModified(resourceNamed("weblog.vm")),
                "No file, no timestamp -- 0 rather than an exception");
    }

    /**
     * Characterises a defect rather than endorsing it.
     *
     * <p>{@code getCachedFile} strips leading slashes from the name before
     * looking it up, with a comment claiming "we do this when we cache a
     * resource, so do it again to ensure a match" -- but {@code
     * getResourceReader} stores the name unstripped and never strips anything.
     * A name that was never stored, for that reason or any other, yields a null
     * {@code savedPath}, which string-concatenates into the path as the literal
     * text "null".
     *
     * <p>The consequence is quiet rather than fatal: the bogus path does not
     * exist, so {@code isSourceModified} answers "yes, modified" every single
     * time and Velocity reloads the template on every check, defeating
     * {@code resource.loader.cache=true} for that resource. No error is logged
     * and nothing fails.
     */
    @Test
    void aResourceThatWasNeverLoadedBuildsAPathContainingTheWordNull(@TempDir Path root) {
        when(context.getRealPath("/")).thenReturn(root + "/");
        // deliberately NOT registered in templatePaths
        assertTrue(new File(root.toString() + "null").getPath().contains("null"),
                "sanity: this is what the concatenation produces");

        assertTrue(loader.isSourceModified(resourceNamed("never-loaded.vm")),
                "An unregistered resource is reported modified on every check, forever, "
                        + "because its cached path is nonsense rather than missing");
        assertEquals(0L, loader.getLastModified(resourceNamed("never-loaded.vm")),
                "and reports no timestamp");
    }
}
