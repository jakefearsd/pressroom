package org.apache.roller.weblogger.build;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Access to the repository's tracked files for the rebrand safety-net tests.
 *
 * <p>Tests run with {@code user.dir} at {@code app/}, so the repository root
 * is its parent. Tracked files come from {@code git ls-files -z}, so
 * untracked build output and editor droppings are never scanned.
 */
public final class RepoFiles {

    private RepoFiles() {
    }

    /** The repository root (parent of the module directory the tests run in). */
    public static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).getParent();
    }

    /** Repo-relative paths of every tracked file; fails the test if git fails. */
    public static List<Path> trackedFiles() {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "-z")
                    .directory(repoRoot().toFile())
                    .redirectErrorStream(false)
                    .start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                in.transferTo(out);
            }
            int code = p.waitFor();
            if (code != 0) {
                fail("git ls-files exited " + code);
            }
            List<Path> files = new ArrayList<>();
            for (String name : out.toString(StandardCharsets.UTF_8).split("\0")) {
                if (!name.isEmpty()) {
                    files.add(Path.of(name));
                }
            }
            return files;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run git ls-files", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running git ls-files", e);
        }
    }

    /** The file's text, UTF-8. */
    public static String read(Path repoRelative) {
        try {
            return Files.readString(repoRoot().resolve(repoRelative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + repoRelative, e);
        }
    }

    /** The file parsed namespace-aware, with DTDs and external entities disabled. */
    static Document xml(Path repoRelative) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            byte[] bytes = Files.readAllBytes(repoRoot().resolve(repoRelative));
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("cannot parse " + repoRelative, e);
        }
    }
}
