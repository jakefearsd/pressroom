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

package com.wikantik.pressroom.ui.controllers.editor;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.validator.routines.UrlValidator;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.SiteSlugClashException;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Add or edit a single static page.
 *
 * <p>One route handles both: {@code id} absent (or blank) means "new page",
 * present means "edit the page it names" -- modelled on
 * {@code TemplateEditController}, which does the same for templates, rather
 * than the entry editor's separate add/edit action pair.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class PageEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(PageEditController.class);

    /** The site's pages are written here, on the site space as on a blog. */
    @Override
    public boolean allowsSiteSpace() {
        return true;
    }

    /**
     * Same allowlist as {@code CtaShortcode}'s href check and
     * {@code EntryEditController}'s: the SEO card's canonical-URL override is
     * emitted straight into {@code <link rel="canonical">}, {@code og:url}
     * and JSON-LD {@code mainEntityOfPage}, so it must be an absolute http(s)
     * URL or blank -- never a {@code javascript:}/{@code data:}/{@code file:}
     * value.
     */
    private static final UrlValidator CANONICAL_URL_VALIDATOR =
            new UrlValidator(new String[] {"http", "https"});

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "pageEdit";
    }

    @Override
    public String getPageTitle() {
        return "pageEdit.title";
    }

    @GetMapping("/pageEdit.rol")
    public String edit(@RequestParam(name = "id", required = false) String id,
                       HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "pageEdit");
        // getPageTitle() (via populateCommonModel above) always answers
        // "pageEdit.title" ("Edit page") -- this one route handles both add
        // and edit (id absent vs present), unlike the entry editor's separate
        // entryAdd.rol/entryEdit.rol pair, so only the request has enough
        // information to pick the right title. Same override-after-populate
        // shape as EntryEditController's actionName.
        if (StringUtils.isBlank(id)) {
            model.addAttribute("pageTitle", "pageEdit.title.new");
        }

        PageBean bean = new PageBean();
        if (!StringUtils.isBlank(id)) {
            // Ownership-checked: id is client input and getPage is a global
            // by-id lookup, so without this an editor on one weblog could
            // open (and, on save, overwrite) another weblog's page.
            WeblogPage page = lookupPage(id, request);
            if (page == null) {
                addError(model, "pageEdit.notFound", request);
                return DENIED_VIEW;
            }
            bean.copyFrom(page);
            model.addAttribute("page", page);
        }

        addPageEditModelAttributes(request, model, bean);
        model.addAttribute("bean", bean);
        return ".PageEdit";
    }

    @PostMapping("/pageEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") PageBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "pageEdit");

        boolean isNew = StringUtils.isBlank(bean.getId());
        WeblogPage page;
        if (isNew) {
            page = new WeblogPage();
            page.setWeblog(getActionWeblog(request));
        } else {
            // Same ownership check as the GET side: bean.id is client input,
            // and a global by-id lookup here would let a posted form
            // overwrite another weblog's page.
            page = lookupPage(bean.getId(), request);
            if (page == null) {
                addError(model, "pageEdit.notFound", request);
                return DENIED_VIEW;
            }
        }

        // Same allowlist as EntryEditController#doSave / CtaShortcode: the SEO
        // card's canonical-URL override is emitted straight into the rendered
        // head, so it must be checked before anything is persisted -- a field
        // error the author can fix, not a 500 and not a silently-stored
        // javascript:/data:/file: value.
        if (StringUtils.isNotBlank(bean.getCanonicalUrl())
                && !CANONICAL_URL_VALIDATOR.isValid(bean.getCanonicalUrl())) {
            addFieldError(model, "seo_canonicalUrl", "entryEdit.canonicalUrlInvalid", request);
            if (!isNew) {
                model.addAttribute("page", page);
            }
        } else {
            // copyTo is inside the try, not before it, the same shape as
            // EntryEditController#doSave -- it is defensive against a garbage
            // status (see PageBean#parseStatus) but is still ordinary application
            // code reachable from a crafted POST, and nothing here may 500.
            try {
                bean.copyTo(page);
                weblogger.getWeblogPageManager().savePage(page);
                weblogger.flush();
                bean.copyFrom(page);
                model.addAttribute("page", page);
                addMessage(model, "pageEdit.saved", request);
            } catch (SiteSlugClashException ex) {
                // The name is another author's blog handle, and
                // <spring:message arguments> does not escape.
                addFieldError(model, "page_bean_slug", ex.getMessageKey(),
                        new Object[] {StringEscapeUtils.escapeHtml4(ex.getArgument())}, request);
                if (!isNew) {
                    model.addAttribute("page", page);
                }
            } catch (WebloggerException ex) {
                // A reserved or malformed slug is the one expected failure mode
                // here (see WeblogPageManager#savePage), and it must surface as a
                // field error the author can fix, not a 500.
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                if (message.contains("reserved")) {
                    addFieldError(model, "page_bean_slug", "pageEdit.error.slugReserved", request);
                } else if (message.contains("slug")) {
                    addFieldError(model, "page_bean_slug", "pageEdit.error.slugInvalid", request);
                } else {
                    log.error("Error saving page {}", bean.getId(), ex);
                    addError(model, "generic.error.check.logs", request);
                }
                if (!isNew) {
                    model.addAttribute("page", page);
                }
            } catch (Exception ex) {
                // Catch-all, matching EntryEditController#doSave's shape: nothing
                // in this method may turn a bad POST into a 500.
                log.error("Error saving page {}", bean.getId(), ex);
                addError(model, "generic.error.check.logs", request);
                if (!isNew) {
                    model.addAttribute("page", page);
                }
            }
        }

        // See the matching comment in edit(): getPageTitle() (via
        // populateCommonModel above) always answers "pageEdit.title" ("Edit
        // page"), and this route re-renders the same view whether the save
        // succeeded or failed validation. Decided here, at the end, off the
        // bean's FINAL id rather than the isNew flag captured on entry: a
        // successful create leaves this POST on the same URL but bean.copyFrom
        // (above) has since populated bean.id from the newly-persisted page,
        // so the title should flip to "Edit page" along with it -- a failed
        // create leaves bean.id blank and the title stays "New page".
        if (StringUtils.isBlank(bean.getId())) {
            model.addAttribute("pageTitle", "pageEdit.title.new");
        }

        addPageEditModelAttributes(request, model, bean);
        model.addAttribute("bean", bean);
        return ".PageEdit";
    }

    /**
     * Renders unsaved editor text exactly as the published page will, and
     * returns the HTML fragment the editor shows in its preview pane.
     *
     * <p>The twin of {@code EntryEditController#entryEditPreview}, and for the
     * same reason: only the server can expand {@code [gallery]}, {@code [map]}
     * or {@code [cta]}, so a browser-side Markdown library would disagree with
     * the published page precisely where an author needs to trust the preview.
     *
     * <p>It renders through {@code EntryRenderer.pageContent}, the page's own
     * production path, on a scratch {@link WeblogPage} rather than on the
     * looked-up row. The scratch page carries the weblog and -- when an id was
     * supplied -- that page's slug, which is all a {@code ShortcodeContext}
     * exposes; building one instead of mutating the managed entity keeps a
     * preview from ever dirtying the persistence context.
     *
     * <p>An id is client input, so it is ownership-checked the same way
     * {@code edit} and {@code save} check theirs. Without that, previewing
     * would be a way to ask what another weblog's shortcodes resolve to --
     * its media directories, its geo, its slug.
     */
    @PostMapping("/pageEdit!preview.rol")
    @ResponseBody
    public ResponseEntity<String> pageEditPreview(HttpServletRequest request,
                                                  @RequestParam(name = "id", required = false) String id,
                                                  @RequestParam(name = "text", required = false) String text) {
        Weblog weblog = getActionWeblog(request);
        if (weblog == null) {
            return ResponseEntity.notFound().build();
        }

        WeblogPage scratch = new WeblogPage();
        scratch.setWeblog(weblog);
        if (!StringUtils.isBlank(id)) {
            WeblogPage page = lookupPage(id, request);
            if (page == null) {
                return ResponseEntity.notFound().build();
            }
            scratch.setSlug(page.getSlug());
        }
        scratch.setContent(text == null ? "" : text);

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(weblogger.getEntryRenderer().pageContent(scratch));
    }

    private void addPageEditModelAttributes(HttpServletRequest request, Model model, PageBean bean) {
        // The editor's insert menu, generated from the shortcode registry
        // itself so it can never advertise a shortcode that does not render,
        // or omit one that does. Pages go through the same shortcode-expanding
        // render pipeline as entries (WeblogPage implements ShortcodeContext).
        model.addAttribute("shortcodeCards", weblogger.getEntryRenderer().shortcodeCards());

        // The front-door page is served at the site root, not at its slug; the
        // editor says so rather than leaving the slug line to suggest otherwise.
        model.addAttribute("frontDoorPage", getActionWeblog(request).isSiteSpace()
                && WebloggerRuntimeConfig.isFrontDoorPage(bean.getSlug()));

        // The document the editor's split/preview panes frame -- the same
        // shell the entry editor uses, through the same BaseController helper,
        // so the two screens cannot come to preview against different
        // documents.
        model.addAttribute("previewShellURL", previewShellURL(getActionWeblog(request)));

        // Thumbnail preview for the SEO panel's social-image picker. Read off
        // the bean rather than the page so a save that failed validation
        // still shows the image the author picked in the form.
        if (!StringUtils.isEmpty(bean.getOgImageId())) {
            try {
                MediaFile mediaFile = weblogger.getMediaFileManager().getMediaFile(bean.getOgImageId());
                // A dangling id (image deleted after being picked) or one
                // belonging to another weblog -- getMediaFile is a global
                // by-id lookup, and this id comes off the submitted form --
                // simply renders no preview rather than a broken page.
                if (mediaFile != null && getActionWeblog(request).equals(mediaFile.getWeblog())) {
                    model.addAttribute("ogImageThumbnailUrl", weblogger.getUrlStrategy()
                            .getMediaFileThumbnailURL(mediaFile.getWeblog(), mediaFile.getId(), true));
                }
            } catch (WebloggerException ex) {
                log.error("Error looking up media file - {}", bean.getOgImageId(), ex);
            }
        }
    }

    /**
     * Where a denied lookup goes: back to the list, same as
     * {@code TemplateEditController} falling back to {@code .Templates}. An
     * unknown id and a foreign one are deliberately indistinguishable to the
     * caller.
     */
    private static final String DENIED_VIEW = ".Pages";

    @ModelAttribute("bean")
    public PageBean getBean() {
        return new PageBean();
    }
}
