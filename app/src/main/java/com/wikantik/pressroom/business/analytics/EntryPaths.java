/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.business.analytics;

import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.Weblog;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Which recorded paths are which of one blog's entries.
 *
 * <p>The shapes are not written down here: they are what the blog's
 * {@link URLStrategy} builds for an entry, so a custom domain (the blog's root
 * is the host's root, no handle segment) and the context path come out the
 * way every page links to an entry. A blog with a custom domain also maps the
 * site-host form, {@code /<handle>/entry/<anchor>}, which it may have been
 * read at before the domain was set. Locale-prefixed entry URLs are not
 * mapped (no theme links to them); such a path counts toward the totals as a
 * plain path.
 *
 * <p>A recorded path loses its query string and fragment, then the context
 * path, then any trailing slashes, and is matched against the shapes; what
 * stands where the anchor goes is percent-decoded. Anchors become entry IDs
 * in one lookup for all of a blog's paths ({@link #entryIds}), never one per
 * path. A path that matches no shape, or names an anchor the blog does not
 * have, maps to nothing and still counts toward the blog's totals.
 */
public final class EntryPaths {

    /** Stands in for an anchor while asking the strategy for a shape; letters only, so encoding leaves it alone. */
    private static final String PROBE = "PRESSROOMANCHORPROBE";

    /** What comes before and after the anchor, context path removed. */
    private record Shape(String prefix, String suffix) {
    }

    private final List<Shape> shapes;
    private final String contextPath;

    private EntryPaths(List<Shape> shapes, String contextPath) {
        this.shapes = List.copyOf(shapes);
        this.contextPath = contextPath;
    }

    /**
     * The entry shapes of one blog.
     *
     * @param weblog      the blog
     * @param urls        the URL strategy pages link with
     * @param contextPath the deployment's context path that recorded paths start with ({@code ""} or
     *                    null at the root, {@code "/pressroom"} under a prefix)
     * @return the blog's shapes
     */
    public static EntryPaths forWeblog(Weblog weblog, URLStrategy urls, String contextPath) {
        // What the strategy itself puts before every root-relative URL, asked
        // of it rather than assumed: the site space's root is that prefix
        // plus "/". Before the first request latches a relative context the
        // strategy's prefix is not the deployment's, so the two are kept apart.
        Weblog siteRoot = new Weblog();
        siteRoot.setSiteSpace(true);
        String prefix = StringUtils.defaultString(Strings.CS.removeEnd(urls.getWeblogURL(siteRoot, null, false), "/"));
        List<Shape> shapes = new ArrayList<>();
        addShape(shapes, urls.getWeblogEntryURL(weblog, null, PROBE, false), prefix);
        if (StringUtils.isNotBlank(weblog.getCustomDomain())) {
            Weblog siteHost = new Weblog();
            siteHost.setHandle(weblog.getHandle());
            addShape(shapes, urls.getWeblogEntryURL(siteHost, null, PROBE, false), prefix);
        }
        return new EntryPaths(shapes, StringUtils.defaultString(contextPath));
    }

    private static void addShape(List<Shape> shapes, String url, String strategyPrefix) {
        if (url == null) {
            return;
        }
        String relative = Strings.CS.removeStart(url, strategyPrefix);
        int at = relative.indexOf(PROBE);
        if (at >= 0) {
            shapes.add(new Shape(relative.substring(0, at), relative.substring(at + PROBE.length())));
        }
    }

    /**
     * The anchor a recorded path names, or null when it is no entry's path.
     *
     * @param path a path as the tracker recorded it
     * @return the decoded anchor, or null
     */
    public String anchorOf(String path) {
        String p = normalise(path);
        if (p == null) {
            return null;
        }
        for (Shape shape : shapes) {
            if (p.length() > shape.prefix().length() + shape.suffix().length()
                    && p.startsWith(shape.prefix()) && p.endsWith(shape.suffix())) {
                String raw = p.substring(shape.prefix().length(), p.length() - shape.suffix().length());
                String anchor = decode(raw);
                if (anchor != null && !anchor.isEmpty() && raw.indexOf('/') < 0) {
                    return anchor;
                }
            }
        }
        return null;
    }

    /**
     * Maps each path that names an entry to that entry's ID.
     *
     * @param paths  recorded paths
     * @param lookup anchors to entry IDs, for this blog; called once, with every anchor, and not at all
     *               when no path names one
     * @return path to entry ID, for the paths that map
     */
    public Map<String, String> entryIds(Collection<String> paths, Function<Set<String>, Map<String, String>> lookup) {
        Map<String, String> anchors = new HashMap<>();
        for (String path : paths) {
            String anchor = anchorOf(path);
            if (anchor != null) {
                anchors.put(path, anchor);
            }
        }
        if (anchors.isEmpty()) {
            return Map.of();
        }
        Map<String, String> ids = lookup.apply(new LinkedHashSet<>(anchors.values()));
        Map<String, String> mapped = new HashMap<>();
        anchors.forEach((path, anchor) -> {
            String id = ids.get(anchor);
            if (id != null) {
                mapped.put(path, id);
            }
        });
        return mapped;
    }

    private String normalise(String path) {
        if (path == null) {
            return null;
        }
        String p = StringUtils.substringBefore(StringUtils.substringBefore(path, "?"), "#");
        if (!contextPath.isEmpty() && (p.equals(contextPath) || p.startsWith(contextPath + "/"))) {
            p = p.substring(contextPath.length());
        }
        return StringUtils.stripEnd(p, "/");
    }

    /** Percent-decoding of a path segment: a {@code +} in a path is a plus, not a space. */
    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
