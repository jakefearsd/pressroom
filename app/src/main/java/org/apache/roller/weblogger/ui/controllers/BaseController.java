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

package org.apache.roller.weblogger.ui.controllers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.ui.core.util.menu.Menu;
import org.apache.roller.weblogger.ui.core.util.menu.MenuHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base controller for Spring MVC controllers in Roller.
 * Replaces the Struts 2 UIAction base class, providing common utilities
 * for authentication, authorization, message resolution, and model population.
 */
public abstract class BaseController implements UISecurityEnforced, UIActionPreparable {

    private static final Logger log = LoggerFactory.getLogger(BaseController.class);

    @Autowired
    protected MessageSource messageSource;

    // @Lazy is load-bearing: controllers are constructed at context refresh,
    // before WebloggerStartup.prepare() has run. The lazy proxy defers
    // building the business-tier graph to first use rather than at wiring time.
    @Autowired
    @org.springframework.context.annotation.Lazy
    protected Weblogger weblogger;

    /**
     * Allow form parameters prefixed with "bean." to bind to @ModelAttribute("bean").
     * This preserves the Struts2 convention where form fields were named bean.title, bean.id, etc.
     * Controllers using Spring form:form tags (which don't use the prefix) should override
     * this method with a no-op.
     *
     * <p><strong>Checkbox field markers ("_xxx" hidden inputs) must name the
     * bean's real property path, not the "bean."-prefixed one.</strong>
     * {@code WebDataBinder.doBind} runs {@code checkFieldDefaults} (which
     * strips this "bean." prefix) BEFORE {@code checkFieldMarkers}, and
     * {@code checkFieldDefaults} only rewrites parameters that literally
     * start with "bean." -- a marker named e.g. {@code "_bean.showInNav"}
     * starts with {@code "_"}, so it is left alone, and
     * {@code checkFieldMarkers} then looks for a writable property literally
     * named {@code "bean.showInNav"}, which does not exist, so the marker is
     * silently discarded and an unchecked box never binds to {@code false}.
     * The marker must be named {@code "_showInNav"} (see PageEdit.jsp).
     */
    @InitBinder("bean")
    public void initBeanBinder(WebDataBinder binder) {
        binder.setFieldDefaultPrefix("bean.");
    }

    /**
     * The one place the admin JSPs build weblog-content urls:
     * {@code ${urls.weblogAbsolute(actionWeblog)}}, {@code ${urls.entry(post)}},
     * {@code ${urls.media(f)}} and so on -- the replacement for the url getters
     * the JPA entities used to reach the {@code URLStrategy} through. It is a
     * {@code @ModelAttribute} rather than a line in {@link #populateCommonModel}
     * so that every handler on every subclass gets it, and it resolves the
     * strategy lazily because this runs for the install wizard too, before the
     * business tier behind the {@code @Lazy weblogger} proxy exists.
     */
    @ModelAttribute("urls")
    public AdminUrls urls() {
        return new AdminUrls(weblogger::getUrlStrategy);
    }

    // --- UIActionPreparable default ---

    @Override
    public void myPrepare() {
        // no-op by default; subclasses override as needed
    }

    // --- UISecurityEnforced defaults (safe: require login + weblog admin) ---

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.ADMIN);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.LOGIN);
    }

    // --- Request attribute accessors ---

    /**
     * Get the authenticated user from the request attributes (set by the interceptor).
     */
    protected User getAuthenticatedUser(HttpServletRequest request) {
        return (User) request.getAttribute("authenticatedUser");
    }

    /**
     * Get the action weblog from the request attributes (set by the interceptor).
     */
    protected Weblog getActionWeblog(HttpServletRequest request) {
        return (Weblog) request.getAttribute("actionWeblog");
    }

    // --- Message / i18n helpers ---

    /**
     * Resolve a message from the message source using the request locale.
     */
    protected String getText(String key, HttpServletRequest request) {
        return messageSource.getMessage(key, null, key, request.getLocale());
    }

    /**
     * Resolve a message with arguments from the message source using the request locale.
     */
    protected String getText(String key, Object[] args, HttpServletRequest request) {
        return messageSource.getMessage(key, args, key, request.getLocale());
    }

    // --- Model error / message helpers ---

    /**
     * Add an error message (resolved from the message source) to the model.
     */
    protected void addError(Model model, String key, HttpServletRequest request) {
        addToModel(model, "errors", getText(key, request));
    }

    /**
     * Add an error message (resolved from the message source) with a parameter to the model.
     */
    protected void addError(Model model, String key, String param, HttpServletRequest request) {
        addToModel(model, "errors", getText(key, new Object[]{param}, request));
    }

    /**
     * Add an error message (resolved from the message source) with arbitrary
     * arguments to the model.
     *
     * <p>For a key carrying more than one placeholder -- or whose arguments
     * are already collected as an {@code Object[]}/{@code String[]}, e.g. a
     * {@link org.apache.roller.weblogger.util.RollerMessages.RollerMessage}
     * replayed from a validation collector -- the single-{@code param}
     * overload above cannot carry them, and dropping them silently returns
     * the raw, unresolved {@code {0}}/{@code {1}} pattern text: {@code
     * alwaysUseMessageFormat} is {@code false} (see {@code
     * MessageFormatRegressionTest}), so a no-args lookup never runs the
     * pattern through {@code MessageFormat} at all.
     */
    protected void addError(Model model, String key, Object[] args, HttpServletRequest request) {
        addToModel(model, "errors", getText(key, args, request));
    }

    /**
     * The ordered set of DOM ids naming the form controls a validation
     * refused. Read by tests; the page reads {@link #INVALID_FIELD_IDS}.
     */
    public static final String INVALID_FIELDS = "invalidFields";

    /**
     * The same ids, space-joined, which is the form the three layouts render
     * into {@code <body data-invalid-fields>}. Joined here rather than in the
     * page because JSTL's {@code fn:join} only accepts a {@code String[]}.
     */
    public static final String INVALID_FIELD_IDS = "invalidFieldIds";

    /**
     * An error message, plus the DOM id of the control it is about.
     *
     * <p>Identical to {@link #addError(Model, String, HttpServletRequest)} in
     * every respect the caller's {@code hasErrors} gate can see -- the message
     * lands in the same list and refuses the save the same way. The addition
     * is the id, which travels to the page as {@code
     * <body data-invalid-fields>} and lets {@code roller.js} put
     * {@code .is-invalid} and {@code aria-invalid} on the control and focus
     * the first one. A banner at the top of a long settings form says
     * <em>that</em> something was refused; only the marker says <em>which</em>
     * of thirty fields.
     *
     * <p>The id must be the control's real {@code id} attribute in the JSP
     * this controller renders -- {@code JspConsistencyTest} checks every id
     * named here against that file, so a renamed field fails the build rather
     * than silently marking nothing.
     */
    protected void addFieldError(Model model, String fieldId, String key, HttpServletRequest request) {
        addError(model, key, request);
        markInvalidField(model, fieldId);
    }

    /**
     * The {@link #addError(Model, String, Object[], HttpServletRequest)}
     * counterpart of {@link #addFieldError(Model, String, String,
     * HttpServletRequest)}, for a message whose text names the offending value.
     */
    protected void addFieldError(Model model, String fieldId, String key, Object[] args,
                                 HttpServletRequest request) {
        addError(model, key, args, request);
        markInvalidField(model, fieldId);
    }

    /**
     * A {@link LinkedHashSet}, so two complaints about one control mark it
     * once and the joined string is deterministic (the first id named is the
     * one the page focuses).
     */
    @SuppressWarnings("unchecked")
    private void markInvalidField(Model model, String fieldId) {
        Set<String> fields = (Set<String>) model.getAttribute(INVALID_FIELDS);
        if (fields == null) {
            fields = new LinkedHashSet<>();
            model.addAttribute(INVALID_FIELDS, fields);
        }
        fields.add(fieldId);
        model.addAttribute(INVALID_FIELD_IDS, String.join(" ", fields));
    }

    /**
     * Check whether any error messages have been added to the model.
     */
    @SuppressWarnings("unchecked")
    protected boolean hasErrors(Model model) {
        List<String> errors = (List<String>) model.getAttribute("errors");
        return errors != null && !errors.isEmpty();
    }

    /**
     * Add a status message (resolved from the message source) to the model.
     */
    protected void addMessage(Model model, String key, HttpServletRequest request) {
        addToModel(model, "messages", getText(key, request));
    }

    /**
     * Add a status message (resolved from the message source) with a parameter to the model.
     */
    protected void addMessage(Model model, String key, String param, HttpServletRequest request) {
        addToModel(model, "messages", getText(key, new Object[]{param}, request));
    }

    // --- Flash attribute helpers (for messages that survive redirects) ---

    /**
     * Add a flash message that survives a redirect.
     */
    protected void addFlashMessage(RedirectAttributes redirectAttributes, String key, HttpServletRequest request) {
        addToFlash(redirectAttributes, "messages", getText(key, request));
    }

    /**
     * Add a flash message with a parameter that survives a redirect.
     */
    protected void addFlashMessage(RedirectAttributes redirectAttributes, String key, String param, HttpServletRequest request) {
        addToFlash(redirectAttributes, "messages", getText(key, new Object[]{param}, request));
    }

    /**
     * Add a flash error that survives a redirect.
     */
    protected void addFlashError(RedirectAttributes redirectAttributes, String key, HttpServletRequest request) {
        addToFlash(redirectAttributes, "errors", getText(key, request));
    }

    /**
     * Add a flash error with a parameter that survives a redirect.
     */
    protected void addFlashError(RedirectAttributes redirectAttributes, String key, String param, HttpServletRequest request) {
        addToFlash(redirectAttributes, "errors", getText(key, new Object[]{param}, request));
    }

    @SuppressWarnings("unchecked")
    private void addToModel(Model model, String attrName, String text) {
        List<String> list = (List<String>) model.getAttribute(attrName);
        if (list == null) {
            list = new ArrayList<>();
            model.addAttribute(attrName, list);
        }
        list.add(text);
    }

    @SuppressWarnings("unchecked")
    private void addToFlash(RedirectAttributes redirectAttributes, String attrName, String text) {
        List<String> list = (List<String>) redirectAttributes.getFlashAttributes().get(attrName);
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(text);
        redirectAttributes.addFlashAttribute(attrName, list);
    }

    // --- Entry lookup ---

    /**
     * The entry with this id, but only when it belongs to the action weblog.
     *
     * <p>{@code getWeblogEntry} is a global by-id lookup and the id always
     * arrives as client input, while the permission interceptor only
     * establishes that the caller may edit the <em>action</em> weblog. Every
     * caller that resolves an entry id -- read and write alike -- goes through
     * here: the editor page hangs an entry's share URL (a durable credential
     * for an unpublished draft) off whatever this resolves, and the list
     * actions delete and rewrite whatever it returns.
     *
     * <p>An unknown id and a foreign one are deliberately indistinguishable to
     * the caller, so a probe cannot map one weblog's entry ids from another.
     */
    protected WeblogEntry lookupEntry(String id, HttpServletRequest request) {
        return WeblogOwnership.entry(weblogger, id, getActionWeblog(request));
    }

    /**
     * The entry with this id, but refusing one that has been trashed -- the
     * counterpart to {@code TrashController}'s own {@code trashedEntry} check,
     * which refuses everything that is <em>not</em> trashed. This refuses
     * everything that <em>is</em>.
     *
     * <p>{@link #lookupEntry} carries the ownership check but no status
     * filter, so without this a bookmarked editor URL, a forged
     * {@code duplicateId}, or a trashed id slipped into a bulk-action
     * selection would resolve a trashed entry as if it were live. That
     * matters because every one of those seams can end in a save: the
     * editor's Publish button, the duplicate action's copy-and-open, and
     * bulk actions like {@code bulkPublish} all end up calling
     * {@code saveWeblogEntry}, which would resurrect the entry to
     * {@code DRAFT} (or straight to {@code PUBLISHED}) by a side door that
     * bypasses restore entirely -- and does so while {@code trashedAt} is
     * still populated on the row.
     *
     * <p>An unknown id, a foreign id, and a trashed id are deliberately
     * indistinguishable to the caller, the same way {@link #lookupEntry}
     * already keeps an unknown id and a foreign one indistinguishable.
     */
    protected WeblogEntry lookupNonTrashedEntry(String id, HttpServletRequest request) {
        WeblogEntry entry = lookupEntry(id, request);
        if (entry == null || entry.getStatus() == PubStatus.TRASHED) {
            return null;
        }
        return entry;
    }

    /**
     * Moves an entry to the trash, taking it out of everything that indexes
     * or caches it. This is the authoring UI's single deletion seam -- the
     * entry list's bulk action and both entry-removal endpoints all call
     * this, never {@link WeblogEntryManager#trashWeblogEntry} directly.
     *
     * <p>The order matters and the steps are not optional, and staying
     * TRASHED rather than being removed from the database does not make them
     * unnecessary -- the opposite: the search index holds documents keyed by
     * entry id, so a TRASHED entry left in it is still findable by site
     * search and still links to a page that now 404s, exactly the failure a
     * genuine delete would produce. {@code CacheManager.invalidate} then
     * clears the rendered pages that contained it.
     *
     * <p>Indexing failures are logged and swallowed rather than aborting the
     * trash: an index that has fallen behind is repairable from the admin
     * screen, whereas a half-trashed entry is not.
     *
     * <p>Delegates to {@link EntryDeletion#trashEntryWithIndex} (Task 10) so
     * the automation API's write controller -- which does not extend this
     * class -- can reach the same seam without inheriting the JSP-only state
     * ({@code MessageSource}, {@code Model}, flash-message helpers) this
     * class carries.
     */
    protected void trashEntryWithIndex(WeblogEntry entry) throws WebloggerException {
        EntryDeletion.trashEntryWithIndex(weblogger, entry);
    }

    /**
     * Permanently deletes an entry -- the "delete forever" action on an
     * already-trashed entry -- taking it out of everything that indexes or
     * caches it first.
     *
     * <p>Same index/cache steps as {@link #trashEntryWithIndex} and for the
     * same reason (see its javadoc); the only difference is the final call,
     * which here is the actual, irreversible {@link WeblogEntryManager#removeWeblogEntry}
     * rather than a trash. A trashed entry is normally already out of the
     * index -- it was de-indexed on the way into the trash -- but this
     * re-runs the same dance rather than assuming that, since nothing
     * prevents a caller from reaching this on an entry that never went
     * through the trash path.
     *
     * <p>Delegates to {@link EntryDeletion#deleteEntryForeverWithIndex}, for
     * the same reason {@link #trashEntryWithIndex} does.
     */
    protected void deleteEntryForeverWithIndex(WeblogEntry entry) throws WebloggerException {
        EntryDeletion.deleteEntryForeverWithIndex(weblogger, entry);
    }

    /**
     * The template with this id, but only when it belongs to the action weblog.
     *
     * <p>Exactly the same hazard as {@link #lookupEntry}, and for exactly the
     * same reason: {@code getTemplate} is a global by-id lookup while the
     * permission interceptor only establishes that the caller may edit the
     * <em>action</em> weblog. A template id arrives as client input, so without
     * this an editor on one weblog can read, overwrite and delete another
     * weblog's theme templates -- which on a shared install means writing
     * arbitrary markup into somebody else's public pages.
     *
     * <p>An unknown id and a foreign one are deliberately indistinguishable.
     */
    protected WeblogTemplate lookupTemplate(String id, HttpServletRequest request) {
        return WeblogOwnership.template(weblogger, id, getActionWeblog(request));
    }

    /**
     * The category with this id, but only when it belongs to the action weblog.
     *
     * <p>The third of the same hazard, after {@link #lookupEntry} and
     * {@link #lookupTemplate}: {@code getWeblogCategory} is a global by-id
     * lookup, the permission interceptor only establishes that the caller may
     * edit the <em>action</em> weblog, and the id arrives as client input.
     * Without this, an editor on one weblog could rename another weblog's
     * categories, delete them, or name one as the destination for a move --
     * silently re-filing somebody else's posts.
     *
     * <p>An unknown id and a foreign one are deliberately indistinguishable.
     */
    protected WeblogCategory lookupCategory(String id, HttpServletRequest request) {
        return WeblogOwnership.category(weblogger, id, getActionWeblog(request));
    }

    /**
     * The page named by {@code id}, but only when it belongs to the weblog
     * this action is scoped to. The fourth of the same hazard, after
     * {@link #lookupEntry}, {@link #lookupTemplate} and {@link #lookupCategory}:
     * {@code getPage} is a global by-id lookup, the permission interceptor only
     * vouches for the <em>action</em> weblog, and the id arrives as client
     * input. A blank id is absent, not something to look up.
     *
     * <p>Public rather than {@code protected} like its three siblings, so that
     * {@code PageEditControllerTest} -- in a different package -- can pin the
     * blank-id guard directly, the same way the ownership check itself is
     * pinned through the controller's own {@code edit}/{@code save} methods.
     */
    public WeblogPage lookupPage(String id, HttpServletRequest request) {
        return WeblogOwnership.page(weblogger, id, getActionWeblog(request));
    }

    /**
     * Where the editor's split/preview panes point their iframe: the weblog's
     * own theme with one empty article for the rendered fragment to land in
     * ({@code PreviewServlet}'s {@code ?shell=true} branch).
     *
     * <p>Built from the parts rather than asked of the preview URL strategy,
     * because the strategy answers with the weblog's <em>root</em> and this is
     * that root plus a query parameter -- one string, versus a strategy
     * contract that does not cover it. The context path has to be on the
     * front or an iframe under a servlet prefix loads the site root instead;
     * that is the trap {@code ContactShortcode} already hit (see CLAUDE.md,
     * Audience).
     *
     * <p>Shared by the entry editor and the page editor, which run the same
     * surface: two copies of one string is exactly how the two screens'
     * previews would come to frame different documents.
     */
    protected String previewShellURL(Weblog weblog) {
        return StringUtils.defaultString(WebloggerRuntimeConfig.getRelativeContextURL())
                + "/roller-ui/authoring/preview/" + weblog.getHandle() + "/?shell=true";
    }

    // --- Configuration property helpers ---

    /**
     * Get a configuration property value. Checks static config first,
     * then runtime config. Returns the key itself if not found.
     */
    protected String getProp(String key) {
        String value = WebloggerConfig.getProperty(key);
        if (value == null) {
            value = WebloggerRuntimeConfig.getProperty(key);
        }
        return (value == null) ? key : value;
    }

    /**
     * Get a boolean configuration property value. Checks static config first,
     * then runtime config. Returns false if not found.
     */
    protected boolean getBooleanProp(String key) {
        String value = WebloggerConfig.getProperty(key);
        if (value == null) {
            value = WebloggerRuntimeConfig.getProperty(key);
        }
        return (value != null) && Boolean.parseBoolean(value);
    }

    // --- Permission checks ---

    /**
     * Whether {@code user} holds {@code action} on {@code weblog}. This used to
     * be {@code Weblog.hasUserPermission(User, String)}, an entity method that
     * located the user manager statically; a check that cannot be answered is
     * a denial (logged), exactly as before.
     */
    protected boolean hasWeblogAction(Weblog weblog, User user, String action) {
        try {
            WeblogPermission userPerms =
                    new WeblogPermission(weblog, user, Collections.singletonList(action));
            return weblogger.getUserManager().checkPermission(userPerms, user);
        } catch (WebloggerException ex) {
            // something is going seriously wrong, not much we can do here
            log.error("ERROR checking user permssion", ex);
        }
        return false;
    }

    /**
     * Whether {@code user} holds the global {@code admin} action. This used to
     * be {@code User.hasGlobalPermission("admin")}; a check that cannot be
     * answered is a denial, as before.
     */
    protected boolean isGlobalAdmin(User user) {
        try {
            GlobalPermission perm =
                    new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN));
            return weblogger.getUserManager().checkPermission(perm, user);
        } catch (WebloggerException ex) {
            return false;
        }
    }

    // --- Common model population ---

    /**
     * The rail's own tab actions -- the only {@code actionName} values a
     * weblog-scoped screen can be showing. The top-bar switcher keeps the
     * current screen when the reader picks a different weblog; anything else
     * (an entry editor, a global admin screen reached without a weblog in
     * play, ...) has no equivalent on the target weblog, so it falls back to
     * {@code entries} instead. Kept here, rather than inlined in the JSP, so
     * {@link BaseControllerTest} can pin it against a real
     * {@code populateCommonModel} call.
     */
    protected static final Set<String> SWITCHER_ACTIONS = Set.of(
            "entries", "trash", "submissions", "comments", "categories", "pages",
            "mediaFileView", "themeEdit", "weblogConfig", "members");

    /**
     * Populate common model attributes used across all pages:
     * authenticatedUser, userIsAuthor, actionWeblog, pageTitle, siteURL,
     * absoluteSiteURL, menu.
     */
    protected void populateCommonModel(HttpServletRequest request, Model model) {
        User user = getAuthenticatedUser(request);
        Weblog weblog = getActionWeblog(request);
        String actionName = getActionName();

        model.addAttribute("authenticatedUser", user);
        model.addAttribute("userIsAuthor", isAuthor(user));
        model.addAttribute("actionWeblog", weblog);
        model.addAttribute("pageTitle", getPageTitle());
        model.addAttribute("siteURL", WebloggerRuntimeConfig.getRelativeContextURL());
        model.addAttribute("absoluteSiteURL", WebloggerRuntimeConfig.getAbsoluteContextURL());
        model.addAttribute("actionName", actionName);
        model.addAttribute("desiredMenu", getDesiredMenu());
        model.addAttribute("switcherAction",
                actionName != null && SWITCHER_ACTIONS.contains(actionName) ? actionName : "entries");

        // build menu if applicable
        Menu menu = MenuHelper.getMenu(getDesiredMenu(), actionName, user, weblog,
                weblogger.getUserManager());
        if (menu != null) {
            model.addAttribute("menu", menu);
        }

        addUserWeblogsForSwitcher(user, model);
    }

    /**
     * Whether {@code user} may use the authoring UI at all: holds the global
     * {@code login} action. A reader (SSO sign-in) does not, so the top bar
     * offers them no "Main Menu" link that {@code menu.rol} would refuse.
     * A display path: a check that cannot run answers "not an author".
     */
    private boolean isAuthor(User user) {
        if (user == null) {
            return false;
        }
        try {
            return weblogger.getUserManager().checkPermission(
                    new GlobalPermission(List.of(GlobalPermission.LOGIN)), user);
        } catch (WebloggerException ex) {
            log.warn("Could not check whether {} may author; the top bar offers no Main Menu",
                    user.getUserName(), ex);
            return false;
        }
    }

    /**
     * The top-bar weblog switcher's options: every weblog {@code user} holds a
     * permission on, sorted by handle, added ONLY when there are two or more --
     * with one weblog (or none) there is nothing to switch to, and the JSP
     * renders the switcher only when {@code userWeblogs} is present.
     *
     * <p>This is a display path, not a decision path (see CLAUDE.md): a
     * weblog the user can no longer reach, or a store that cannot answer the
     * permission query at all, simply leaves the switcher off the page. The
     * page underneath -- built from {@code actionWeblog}, resolved
     * separately by the interceptor -- renders exactly as it would have
     * before this method existed.
     */
    private void addUserWeblogsForSwitcher(User user, Model model) {
        if (user == null) {
            return;
        }
        try {
            Set<String> handles = new TreeSet<>();
            for (WeblogPermission permission : weblogger.getUserManager().getWeblogPermissions(user)) {
                if (permission.getObjectId() != null) {
                    handles.add(permission.getObjectId());
                }
            }
            if (handles.size() < 2) {
                return;
            }
            List<Weblog> weblogs = new ArrayList<>();
            for (String handle : handles) {
                Weblog weblog = weblogger.getWeblogManager().getWeblogByHandle(handle, null);
                if (weblog != null) {
                    weblogs.add(weblog);
                }
            }
            if (weblogs.size() > 1) {
                model.addAttribute("userWeblogs", weblogs);
            }
        } catch (WebloggerException ex) {
            log.error("Error loading weblogs for the top-bar switcher", ex);
        }
    }

    // --- Overridable metadata ---

    /**
     * Returns the page title for the current action. Subclasses should override
     * to provide a meaningful title (typically a message key).
     */
    public String getPageTitle() {
        return "";
    }

    /**
     * Returns the name of the menu this action wants to display, or null for no menu.
     */
    public String getDesiredMenu() {
        return null;
    }

    /**
     * Returns the action name used for menu highlighting. Subclasses should override.
     */
    public String getActionName() {
        return null;
    }
}
