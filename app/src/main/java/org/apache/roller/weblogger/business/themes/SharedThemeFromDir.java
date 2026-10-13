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

package org.apache.roller.weblogger.business.themes;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.apache.roller.weblogger.pojos.ThemeResource;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;

/**
 * The Theme object encapsulates all elements of a single weblog theme. It is
 * used mostly to contain all the templates for a theme, but does contain other
 * theme related attributes such as name, last modified date, etc.
 */
public class SharedThemeFromDir extends SharedTheme {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(SharedThemeFromDir.class);

    // the filesystem directory where we should read this theme from
    private String themeDir = null;

    // the theme preview image
    private transient ThemeResource previewImage = null;

    // the theme stylesheet
    private transient ThemeTemplate stylesheet = null;

    // we keep templates in a Map for faster lookups by name
    private transient Map<String, ThemeTemplate> templatesByName = new HashMap<>();

    // we keep templates in a Map for faster lookups by link
    private transient Map<String, ThemeTemplate> templatesByLink = new HashMap<>();

    // we keep templates in a Map for faster lookups by action
    private transient Map<ComponentType, ThemeTemplate> templatesByAction = new EnumMap<>(ComponentType.class);

    // we keep resources in a Map for faster lookups by path
    // the Map contains ... (resource path, ThemeResource)
    private transient Map<String, ThemeResource> resources = new HashMap<>();

    public SharedThemeFromDir(String themeDirPath)
            throws ThemeInitializationException {

        this.themeDir = themeDirPath;

        // load the theme elements and cache 'em
        loadThemeFromDisk();
    }

    /**
     * Get a resource representing the preview image for this theme.
     */
    @Override
    public ThemeResource getPreviewImage() {
        return this.previewImage;
    }

    /**
     * Get the collection of all templates associated with this Theme.
     */
    @Override
    public List<ThemeTemplate> getTemplates() {
        return new ArrayList<>(this.templatesByName.values());
    }

    /**
     * Lookup the stylesheet. Returns null if no stylesheet defined.
     */
    @Override
    public ThemeTemplate getStylesheet() {
        return this.stylesheet;
    }

    /**
     * Looup the default template, action = weblog. Returns null if the template
     * cannot be found.
     */
    @Override
    public ThemeTemplate getDefaultTemplate() {
        return this.templatesByAction.get(ComponentType.WEBLOG);
    }

    /**
     * Lookup the specified template by name. Returns null if the template
     * cannot be found.
     */
    @Override
    public ThemeTemplate getTemplateByName(String name) {
        return this.templatesByName.get(name);
    }

    /**
     * Lookup the specified template by link. Returns null if the template
     * cannot be found.
     */
    @Override
    public ThemeTemplate getTemplateByLink(String link) {
        return this.templatesByLink.get(link);
    }

    /**
     * Lookup the specified template by action. Returns null if the template
     * cannot be found.
     */
    @Override
    public ThemeTemplate getTemplateByAction(ComponentType action) {
        return this.templatesByAction.get(action);
    }

    /**
     * Get the collection of all resources associated with this Theme.
     * 
     * It is assured that the resources are returned sorted by pathname.
     */
    @Override
    public List<ThemeResource> getResources() {

        List<ThemeResource> myResources = new ArrayList<>(this.resources.values());
        // make sure resources are sorted.
        Collections.sort(myResources);

        return myResources;
    }

    /**
     * Lookup the specified resource by path. Returns null if the resource
     * cannot be found.
     */
    @Override
    public ThemeResource getResource(String path) {
        return this.resources.get(path);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        sb.append("\n");

        for (ThemeTemplate template : templatesByName.values()) {
            sb.append(template);
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Loads every element of this theme from disk and caches them.
     *
     * <p>The five phases below fail differently on purpose, and that is the
     * thing to know before editing any of them: a missing descriptor or a
     * missing TEMPLATE file is fatal, because neither leaves anything that can
     * render; a missing preview image, stylesheet or static resource is logged
     * and skipped, because a theme that renders unstyled or unillustrated is
     * still a theme. This used to be one method at cyclomatic complexity 26,
     * with those five outcomes interleaved.
     */
    private void loadThemeFromDisk() throws ThemeInitializationException {

        ThemeMetadata themeMetadata = parseDescriptor();

        log.debug("Loading Theme {}", themeMetadata.getName());

        applyIdentity(themeMetadata);
        loadPreviewImage(themeMetadata);

        // available types with Roller
        List<RenditionType> availableTypesList = new ArrayList<>();
        availableTypesList.add(RenditionType.STANDARD);

        loadStylesheet(themeMetadata, availableTypesList);
        loadResources(themeMetadata);
        loadTemplates(themeMetadata, availableTypesList);
    }

    /** The theme.xml itself. Fatal if it is missing or will not parse. */
    private ThemeMetadata parseDescriptor() throws ThemeInitializationException {


    log.debug("Parsing theme descriptor for {}", this.themeDir);

    ThemeMetadata themeMetadata;
    try (InputStream is = new FileInputStream(this.themeDir + File.separator
            + "theme.xml")) {
        // lookup theme descriptor and parse it
        ThemeMetadataParser parser = new ThemeMetadataParser();
        themeMetadata = parser.unmarshall(is);
    } catch (Exception ex) {
        throw new ThemeInitializationException(
                "Unable to parse theme.xml for theme " + this.themeDir, ex);
    }

    log.debug("Loading Theme {}", themeMetadata.getName());
        return themeMetadata;
    }

    /** Name, description, author -- what the theme picker shows. */
    private void applyIdentity(ThemeMetadata themeMetadata) {
    // use parsed theme descriptor to load Theme data
    setId(themeMetadata.getId());
    setName(themeMetadata.getName());
    if (StringUtils.isNotEmpty(themeMetadata.getDescription())) {
        setDescription(themeMetadata.getDescription());
    } else {
        setDescription(" ");
    }
    setAuthor(themeMetadata.getAuthor());
    setLastModified(null);
    setEnabled(true);
    }

    /** Optional: a theme with no preview picture is still usable. */
    private void loadPreviewImage(ThemeMetadata themeMetadata) {
    // load resource representing preview image
    File previewFile = new File(this.themeDir + File.separator + themeMetadata.getPreviewImage());
    if (!previewFile.exists() || !previewFile.canRead()) {
        log.warn("Couldn't read theme [{}] preview image file [{}]",
                this.getName(), themeMetadata.getPreviewImage());
    } else {
        this.previewImage = new SharedThemeResourceFromDir(
                themeMetadata.getPreviewImage(), previewFile);
    }
    }

    /**
     * Optional: an unreadable stylesheet is logged and dropped, leaving a theme
     * that renders unstyled rather than one that does not render.
     */
    private void loadStylesheet(ThemeMetadata themeMetadata,
                                List<RenditionType> availableTypesList)
            throws ThemeInitializationException {
    // load stylesheet if possible
    if (themeMetadata.getStylesheet() != null) {

        ThemeMetadataTemplate stylesheetTmpl = themeMetadata
                .getStylesheet();
        // getting the template codes for available types
        ThemeMetadataTemplateRendition standardTemplateCode = stylesheetTmpl
                .getTemplateRenditionTable().get(RenditionType.STANDARD);

        // standardTemplateCode required
        if (standardTemplateCode == null) {
            throw new ThemeInitializationException(
                    "Error in getting template codes for template");
        }

        // construct File object from path
        // we are getting the file path from standard as the default and
        // load it to initially.
        File templateFile = new File(this.themeDir + File.separator
                + standardTemplateCode.getContentsFile());

        // read stylesheet contents
        String contents = loadTemplateFile(templateFile);
        if (contents == null) {
            // if we don't have any contents then skip this one
            log.error("Couldn't load stylesheet theme [{}] template file [{}]",
                    this.getName(), templateFile);
        } else {

            // construct ThemeTemplate representing this file
            // here we set content and template language from standard
            // template code assuming it is the default
            SharedThemeTemplate themeTemplate = new SharedThemeTemplate(
                    themeMetadata.getId() + ":"
                            + stylesheetTmpl.getName(),
                    stylesheetTmpl.getAction(), stylesheetTmpl.getName(),
                    stylesheetTmpl.getDescription(), contents,
                    stylesheetTmpl.getLink(), new Date(
                            templateFile.lastModified()), false, false);

            for (RenditionType type : availableTypesList) {
                SharedThemeTemplateRendition rendition = createRendition(
                        stylesheetTmpl.getTemplateRendition(type));

                themeTemplate.addTemplateRendition(rendition);

                // Set Last Modified
                Date lstModified = rendition.getLastModified();
                if (getLastModified() == null
                        || lstModified.after(getLastModified())) {
                    setLastModified(lstModified);
                }
            }
            // store it
            this.stylesheet = themeTemplate;

            // Update last modified
            themeTemplate.setLastModified(getLastModified());

            addTemplate(themeTemplate);
        }

    }
    }

    /**
     * Optional, per file: one declared resource that is not on disk is skipped
     * without costing the theme its other resources.
     */
    private void loadResources(ThemeMetadata themeMetadata) {
    // go through static resources and add them to the theme
    for (String resourcePath : themeMetadata.getResources()) {
        // construct ThemeResource object from resource
        File resourceFile = new File(this.themeDir + File.separator
                + resourcePath);

        // Continue reading theme even if problem encountered with one file
        if (!resourceFile.exists() || !resourceFile.canRead()) {
            log.warn("Couldn't read  theme [{}] resource file [{}]",
                    this.getName(), resourcePath);
            continue;
        }

        // add it to the theme
        setResource(resourcePath, new SharedThemeResourceFromDir(
                resourcePath, resourceFile));

        // Set Last Modified
        Date lstModified = new Date(resourceFile.lastModified());
        if (getLastModified() == null
                || lstModified.after(getLastModified())) {
            setLastModified(lstModified);
        }

    }
    }

    /** Required: a template that will not load fails the whole theme. */
    private void loadTemplates(ThemeMetadata themeMetadata,
                               List<RenditionType> availableTypesList)
            throws ThemeInitializationException {
    // go through templates and read in contents to a ThemeTemplate
    SharedThemeTemplate themeTemplate;
    for (ThemeMetadataTemplate templateMetadata : themeMetadata.getTemplates()) {

        // getting the template codes for available types
        ThemeMetadataTemplateRendition standardTemplateCode = templateMetadata
                .getTemplateRenditionTable().get(RenditionType.STANDARD);

        // If no template code present for standard type
        if (standardTemplateCode == null) {
            throw new ThemeInitializationException(
                    "Error in getting template codes for template");
        }

        // construct File object from path
        File templateFile = new File(this.themeDir + File.separator
                + standardTemplateCode.getContentsFile());

        String contents = loadTemplateFile(templateFile);
        if (contents == null) {
            // if we don't have any contents then skip this one
            throw new ThemeInitializationException("Couldn't load theme ["
                    + this.getName() + "] template file [" + templateFile
                    + "]");
        }

        // construct ThemeTemplate representing this file
        themeTemplate = new SharedThemeTemplate(
                themeMetadata.getId() + ":" + templateMetadata.getName(),
                templateMetadata.getAction(), templateMetadata.getName(),
                templateMetadata.getDescription(), contents,
                templateMetadata.getLink(), new Date(
                        templateFile.lastModified()),
                templateMetadata.isHidden(), templateMetadata.isNavbar());

        for (RenditionType type : availableTypesList) {
            SharedThemeTemplateRendition templateCode = createRendition(
                    templateMetadata.getTemplateRendition(type));

            themeTemplate.addTemplateRendition(templateCode);

            // Set Last Modified
            Date lstModified = templateCode.getLastModified();
            if (getLastModified() == null
                    || lstModified.after(getLastModified())) {
                setLastModified(lstModified);
            }
        }

        themeTemplate.setLastModified(getLastModified());

        // add it to the theme
        addTemplate(themeTemplate);

    }
    }

    /**
     * Load a single template file as a string, returns null if can't read file.
     */
    private String loadTemplateFile(File templateFile) {
        // Continue reading theme even if problem encountered with one file
        if (!templateFile.exists() && !templateFile.canRead()) {
            return null;
        }

        char[] chars;
        int length;
        try (FileInputStream stream = new FileInputStream(templateFile);
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            chars = new char[(int) templateFile.length()];
            length = reader.read(chars);
        } catch (Exception noprob) {
            log.error("Exception reading theme [{}] template file [{}]",
                    this.getName(), templateFile);
            log.debug("Exception reading theme [{}] template file [{}]",
                    this.getName(), templateFile, noprob);
            return null;
        }

        return new String(chars, 0, length);
    }

    /**
     * Set the value for a given template name.
     */
    private void addTemplate(ThemeTemplate template) {
        this.templatesByName.put(template.getName(), template);
        this.templatesByLink.put(template.getLink(), template);
        if (!ComponentType.CUSTOM.equals(template.getAction())) {
            this.templatesByAction.put(template.getAction(), template);
        }
    }

    /**
     * Set the value for a given resource path.
     */
    private void setResource(String path, SharedThemeResourceFromDir resource) {
        // normalize to use web-style separators
        String normalizedPath = Strings.CS.replace(path, "\\", "/");
        this.resources.put(normalizedPath, resource);
    }

    private SharedThemeTemplateRendition createRendition(
            ThemeMetadataTemplateRendition templateCodeMetadata) {
        SharedThemeTemplateRendition templateRendition = new SharedThemeTemplateRendition();

        // construct File object from path
        File templateFile = new File(this.themeDir + File.separator
                + templateCodeMetadata.getContentsFile());

        // read stylesheet contents
        String contents = loadTemplateFile(templateFile);
        if (contents == null) {
            // if we don't have any contents then load no string
            contents = "";
            log.error("Couldn't load stylesheet theme [{}] template file [{}]",
                    this.getName(), templateFile);
        }
        templateRendition.setTemplate(contents);
        templateRendition.setTemplateLanguage(templateCodeMetadata.getTemplateLang());
        templateRendition.setType(templateCodeMetadata.getType());
        templateRendition.setLastModified(new Date(templateFile.lastModified()));

        return templateRendition;
    }

}
