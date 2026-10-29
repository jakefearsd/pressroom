package com.wikantik.pressroom.ui.restapi.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP tools: one per API resource, each action declared by its route and
 * a description, nothing more -- arguments are derived from the route
 * (ArgumentDeriver). McpCoverageTest holds every /api/v1 route to an action
 * here or an exclusion with its reason.
 */
public final class McpToolCatalog {

    public record ActionDecl(String name, String description, List<String> routes) { }

    public record ToolDecl(String name, String description, boolean readOnly, List<ActionDecl> actions) { }

    public record Exclusion(String route, String reason) { }

    private static final String W = "/v1/weblogs/{handle}";

    public static final List<ToolDecl> TOOLS = List.of(
            tool("whoami", "Who this token belongs to: user name, whether the user is a site admin, "
                    + "the token's role and weblog limit, and the weblogs it can act on.", true,
                    action("", "Describe the caller.", "GET /v1/me")),
            tool("weblogs", "Weblogs: create one (any writing token not limited to a weblog); list them and change "
                    + "their settings (ADMIN tokens).", false,
                    action("list", "List weblogs.", "GET /v1/weblogs"),
                    action("get", "One weblog's settings.", "GET " + W),
                    action("create", "Create a weblog; you become its owner.", "POST /v1/weblogs"),
                    action("update", "Change a weblog's settings; name fields in clear to remove them.", "PATCH " + W)),
            tool("entries", "Blog entries: write, revise, publish, trash and preview, and read or restore "
                    + "their earlier revisions.", false,
                    action("list", "List entries; without status the trash is excluded.", "GET " + W + "/entries"),
                    action("get", "One entry, by id or by anchor (its URL slug).", "GET " + W + "/entries/{id}",
                            "GET " + W + "/entries/by-anchor/{anchor}"),
                    action("create", "Create an entry (a DRAFT unless status says otherwise).",
                            "POST " + W + "/entries"),
                    action("update", "Change the fields given; others are left alone. edits change part of the "
                            + "text; expectedContentHash refuses the update if the entry changed since it was read.",
                            "PATCH " + W + "/entries/{id}"),
                    action("trash", "Move an entry to the trash.", "DELETE " + W + "/entries/{id}"),
                    action("restore", "Bring an entry back from the trash, as a DRAFT.",
                            "POST " + W + "/entries/{id}/restore"),
                    action("delete_forever", "Permanently delete an entry that is already in the trash.",
                            "POST " + W + "/entries/{id}/delete-forever"),
                    action("preview", "Render text exactly as it would publish, without saving, with warnings "
                            + "about what will render badly or hurt search; with id, in that entry's context, and "
                            + "edits in place of text show what an update with them would store.",
                            "POST " + W + "/entries/{id}/preview", "POST " + W + "/entries/preview"),
                    action("revisions", "An entry's revisions, newest first: each is the content one save "
                            + "replaced, with who saved, when, its note and its title; no text.",
                            "GET " + W + "/entries/{id}/revisions"),
                    action("revision", "One revision's title, summary and text, and diff: a unified diff from it "
                            + "to the entry's current text, or to the revision against names.",
                            "GET " + W + "/entries/{id}/revisions/{revisionId}"),
                    action("restore_revision", "Put a revision's title, summary and text back; what it replaces "
                            + "becomes a revision. expectedContentHash refuses it if the entry changed since it was "
                            + "read.", "POST " + W + "/entries/{id}/revisions/{revisionId}/restore")),
            tool("categories", "A weblog's categories and tags.", false,
                    action("list", "List categories.", "GET " + W + "/categories"),
                    action("create", "Create a category.", "POST " + W + "/categories"),
                    action("update", "Rename or describe a category.", "PATCH " + W + "/categories/{id}"),
                    action("delete", "Delete a category; moveTo names where its entries go.",
                            "DELETE " + W + "/categories/{id}"),
                    action("list_tags", "List the weblog's tags.", "GET " + W + "/tags")),
            tool("media", "A weblog's media files and folders. Add an image with import (a public URL); "
                    + "uploading files is REST-only.", false,
                    action("list", "List media files, optionally in one directory.", "GET " + W + "/media"),
                    action("get", "One media file.", "GET " + W + "/media/{id}"),
                    action("import", "Import an image from a public http(s) URL; use its id as an entry's "
                            + "featuredImageId.", "POST " + W + "/media/imports"),
                    action("update", "Change alt text, focal point, name or directory.",
                            "PATCH " + W + "/media/{id}"),
                    action("delete", "Delete a media file.", "DELETE " + W + "/media/{id}"),
                    action("list_directories", "List media directories.", "GET " + W + "/media/directories"),
                    action("create_directory", "Create a media directory.", "POST " + W + "/media/directories")),
            tool("pages", "A weblog's standalone pages (DRAFT or PUBLISHED).", false,
                    action("list", "List pages.", "GET " + W + "/pages"),
                    action("get", "One page.", "GET " + W + "/pages/{id}"),
                    action("create", "Create a page.", "POST " + W + "/pages"),
                    action("update", "Change the fields given.", "PATCH " + W + "/pages/{id}"),
                    action("delete", "Delete a page.", "DELETE " + W + "/pages/{id}")),
            tool("redirects", "301 rules for weblog paths that would otherwise be not found.", false,
                    action("list", "List rules with their hit counts.", "GET " + W + "/redirects"),
                    action("create", "Add a rule from source to target (weblog-relative paths).",
                            "POST " + W + "/redirects"),
                    action("delete", "Delete a rule.", "DELETE " + W + "/redirects/{id}")),
            tool("comments", "Comments by signed-in readers: list held or published ones, approve or hide them, "
                    + "delete them, and reply as the token's user.", false,
                    action("list", "List comments, newest first; filter by status (PENDING, PUBLISHED, HIDDEN) "
                            + "or entry.", "GET " + W + "/comments"),
                    action("get", "One comment.", "GET " + W + "/comments/{id}"),
                    action("update", "Approve (status PUBLISHED) or hide (status HIDDEN) a comment.",
                            "PATCH " + W + "/comments/{id}"),
                    action("delete", "Delete a comment and its reply.", "DELETE " + W + "/comments/{id}"),
                    action("reply", "Reply to a comment as the token's user; the reply is published.",
                            "POST " + W + "/comments/{id}/replies")),
            tool("audits", "Work lists: entries with SEO gaps, every SEO finding for one entry, media missing alt "
                    + "text.", true,
                    action("seo", "Entries missing a search description, meta title or featured image, marked "
                            + "noindex, or with a page title or search description too long, too short or the same "
                            + "as another entry's.", "GET " + W + "/audit/seo"),
                    action("seo_entry", "Every SEO check for one entry, its rendered body included: headings, image "
                            + "alt text, links to missing parts of the entry or to unpublished entries.",
                            "GET " + W + "/audit/seo/entries/{id}"),
                    action("media", "Media files with no alt text.", "GET " + W + "/audit/media")),
            tool("users", "Site user accounts. Needs an ADMIN token. Admin accounts are read-only here: "
                    + "they are managed in the admin UI.", false,
                    action("list", "List users and their roles.", "GET /v1/admin/users"),
                    action("create", "Create an account; it is emailed a link to set its password.",
                            "POST /v1/admin/users"),
                    action("update", "Change a non-admin account's email, screen name or enabled flag.",
                            "PATCH /v1/admin/users/{userName}")),
            tool("site_settings", "Site-wide runtime settings. Needs an ADMIN token.", false,
                    action("get", "Every runtime setting and its value.", "GET /v1/admin/config"),
                    action("update", "Set runtime settings by name (values as strings).",
                            "PATCH /v1/admin/config")),
            tool("maintenance", "Site maintenance on one weblog. Needs an ADMIN token.", false,
                    action("flush_cache", "Drop the weblog's rendered-page cache.",
                            "POST /v1/admin/weblogs/{handle}/actions/flush-cache"),
                    action("rebuild_index", "Rebuild the weblog's search index.",
                            "POST /v1/admin/weblogs/{handle}/actions/rebuild-index"),
                    action("regenerate_renditions", "Regenerate the weblog's image renditions.",
                            "POST /v1/admin/weblogs/{handle}/actions/regenerate-renditions")));

    public static final List<Exclusion> EXCLUDED = List.of(
            new Exclusion("POST /v1/tokens", "Basic-auth only by design: a token holder can never mint a token."),
            new Exclusion("GET /v1/tokens", "Basic-auth only by design: a token holder can never list tokens."),
            new Exclusion("DELETE /v1/tokens/{id}", "Basic-auth only by design: a token holder can never revoke."),
            new Exclusion("GET /v1/ping", "Unauthenticated liveness check; nothing for an agent."),
            new Exclusion("POST " + W + "/media", "Multipart file upload; a tool call carries JSON. "
                    + "Use media import to add an image by URL."));

    private McpToolCatalog() {
    }

    public static List<ResolvedTool> resolve(ApiRouteTable table) {
        List<ResolvedTool> tools = new ArrayList<>();
        for (ToolDecl decl : TOOLS) {
            Map<String, ResolvedAction> actions = new LinkedHashMap<>();
            for (ActionDecl action : decl.actions()) {
                List<ResolvedAction.Variant> variants = new ArrayList<>();
                for (String key : action.routes()) {
                    ApiRoute route = table.find(key).orElseThrow(() -> new IllegalStateException(
                            "MCP " + decl.name() + "." + action.name() + " names no API route: " + key));
                    variants.add(new ResolvedAction.Variant(route, ArgumentDeriver.derive(route),
                            ArgumentDeriver.takesBody(route)));
                }
                actions.put(action.name(), new ResolvedAction(action.name(), action.description(), variants));
            }
            tools.add(new ResolvedTool(decl.name(), decl.description(), decl.readOnly(), actions));
        }
        return tools;
    }

    private static ToolDecl tool(String name, String description, boolean readOnly, ActionDecl... actions) {
        return new ToolDecl(name, description, readOnly, List.of(actions));
    }

    private static ActionDecl action(String name, String description, String... routes) {
        return new ActionDecl(name, description, List.of(routes));
    }
}
