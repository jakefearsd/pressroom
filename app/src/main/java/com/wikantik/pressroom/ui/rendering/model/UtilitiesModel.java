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

package com.wikantik.pressroom.ui.rendering.model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BookingLink;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.jsonld.BreadcrumbJsonLd;
import com.wikantik.pressroom.business.jsonld.BusinessJsonLd;
import com.wikantik.pressroom.business.jsonld.EntryJsonLd;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.JsonLdType;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.wrapper.MediaFileWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;
import com.wikantik.pressroom.business.seo.SeoLimits;
import com.wikantik.pressroom.util.DateUtil;
import com.wikantik.pressroom.util.Excerpts;
import com.wikantik.pressroom.util.RegexUtil;
import com.wikantik.pressroom.business.shortcodes.GalleryMarkup;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.pojos.wrapper.UserWrapper;
import com.wikantik.pressroom.ui.rendering.util.ParsedRequest;
import com.wikantik.pressroom.util.URLUtilities;
import com.wikantik.pressroom.util.Utilities;
import java.util.List;
import com.wikantik.pressroom.pojos.User;

/**
 * Model which provides access to a set of general utilities.
 */
public class UtilitiesModel implements Model {
    
    private static final Logger log = LoggerFactory.getLogger(UtilitiesModel.class);

    /** A trailing "ext", "ext.", "extension" or "x" and the digits after it. */
    private static final Pattern TEL_EXTENSION =
            Pattern.compile(" *(?:extension|ext\\.?|x) *(\\d*) *$", Pattern.CASE_INSENSITIVE);
    
    private ParsedRequest parsedRequest = null;
    private Weblog weblog = null;
    private Weblogger weblogger = null;
    
    
    /**
     * The justified-grid stylesheet for galleries, so the theme macro emits
     * exactly what the {@code [gallery]} shortcode emits.
     *
     * <p>Generated in {@link GalleryMarkup#gridStyles()} from the same
     * constants that produce the {@code ar-NNN} classes, which is what stops
     * the rules and the markup drifting -- they used to be maintained by hand
     * in two places and did drift.
     */
    public String getGalleryGridStyles() {
        return GalleryMarkup.gridStyles();
    }

    /** Template context name to be used for model */
    @Override
    public String getModelName() {
        return "utils";
    }
    
    
    /** Init page model based on request */
    @Override
    public void init(Map<String, Object> initData) throws WebloggerException {      
        
        // we expect the init data to contain a parsedRequest object
        parsedRequest = (ParsedRequest) initData.get("parsedRequest");
        if(parsedRequest == null) {
            throw new WebloggerException("expected parsedRequest from init data");
        }
        
        // extract weblog object if possible
        if(parsedRequest instanceof WeblogRequest) {
            WeblogRequest weblogRequest = (WeblogRequest) parsedRequest;
            weblog = weblogRequest.getWeblog();
        }

        // The only thing this model needs the tier for is resolving a
        // [map auto=..] directory into the travel JSON-LD itinerary, so the
        // facade is read when present rather than required -- ModelLoader
        // already refuses to load any model without it in production.
        weblogger = (Weblogger) initData.get(ModelLoader.WEBLOGGER);
    }
     
    
    //---------------------------------------------------- Authentication utils 
    
    public boolean isUserAuthorizedToAuthor(WeblogWrapper weblog) {
        try {
            if (parsedRequest.getAuthenticUser() != null) {
                return hasWeblogAction(weblog, WeblogPermission.POST);
            }
        } catch (Exception e) {
            log.warn("ERROR: checking user authorization", e);
        }
        return false;
    }
    
    /**
     * Whether the request's user may use the authoring UI at all: holds the
     * global {@code login} action. A reader (SSO sign-in) does not. This is
     * site-level; {@link #isUserAuthorizedToAuthor} is the per-weblog check.
     * A display path: a check that cannot run answers "not an author".
     */
    public boolean isUserAuthor() {
        try {
            if (parsedRequest.getAuthenticUser() != null) {
                return weblogger.getUserManager().checkPermission(
                        new GlobalPermission(List.of(GlobalPermission.LOGIN)), parsedRequest.getUser());
            }
        } catch (Exception e) {
            log.warn("ERROR: checking user authorization", e);
        }
        return false;
    }

    public boolean isUserAuthorizedToAdmin(WeblogWrapper weblog) {
        try {
            if (parsedRequest.getAuthenticUser() != null) {
                return hasWeblogAction(weblog, WeblogPermission.ADMIN);
            }
        } catch (Exception e) {
            log.warn("ERROR: checking user authorization", e);
        }
        return false;
    }
        
    /**
     * Whether the request's user holds {@code action} on the weblog, asked of
     * the user manager of the facade this model was given (was
     * {@code Weblog.hasUserPermission}, an entity method that located it).
     */
    private boolean hasWeblogAction(WeblogWrapper weblog, String action) throws WebloggerException {
        User user = parsedRequest.getUser();
        return weblogger.getUserManager().checkPermission(
                new WeblogPermission(weblog.getPojo(), user, List.of(action)), user);
    }

    public boolean isUserAuthenticated() {
        return parsedRequest.getAuthenticUser() != null;
    }
       
    public UserWrapper getAuthenticatedUser() {
        return parsedRequest.getAuthenticUser() != null 
                ? UserWrapper.wrap(parsedRequest.getUser()) : null;
    }

    //---------------------------------------------------------------- Comments

    /**
     * Where the comments page script talks to the server, built here so the
     * script never guesses the context path: {@code session} (GET),
     * {@code post} (POST) and {@code delete} (the prefix of
     * {@code DELETE <delete><id>.rol}). The same for every visitor, so they
     * may sit in a cached page.
     */
    public Map<String, String> getCommentEndpoints() {
        String context = WebloggerRuntimeConfig.getRelativeContextURL();
        return Map.of(
                "session", context + "/pressroom-ui/rendering/comments/session.rol",
                "post", context + "/pressroom-ui/rendering/comments.rol",
                "delete", context + "/pressroom-ui/rendering/comments/");
    }
             
    //-------------------------------------------------------------- Date utils
    /**
     * Return date for current time.
     */
    public static Date getNow() {
        return new Date();
    }
    
    /**
     * Format date using SimpleDateFormat format string.
     */
    public String formatDate(Date d, String fmt) {
        return formatDate(d, fmt, weblog.getTimeZoneInstance());
    }
    
    /**
     * Format date using SimpleDateFormat format string.
     */
    public String formatDate(Date d, String fmt, TimeZone tzOverride) {
        
        if (d == null || fmt == null) {
            return fmt;
        }
        
        SimpleDateFormat format = new SimpleDateFormat(fmt, weblog.getLocaleInstance());
        if(tzOverride != null) {
            format.setTimeZone(tzOverride);
        }
        
        return format.format(d);
    }
    
    /**
     * Format date in ISO-8601 format.
     */
    public String formatIso8601Date(Date d) {
        return DateUtil.formatIso8601(d);
    }
    
    /**
     * The calendar day ({@code yyyy-MM-dd}) in the weblog's time zone: the
     * machine-readable twin of the visible date {@link #formatDate} prints
     * beside it in a {@code <time datetime>}. It used the JVM's zone (UTC in
     * the container), so an evening post in a western weblog carried the
     * next day.
     */
    public String formatIso8601Day(Date d) {
        if (d == null) {
            return "";
        }
        SimpleDateFormat format = DateUtil.getIso8601DayDateFormat();
        format.setTimeZone(weblog.getTimeZoneInstance());
        return format.format(d);
    }
    
    /**
     * Whether a post published at {@code published} and last saved at
     * {@code updated} was changed on a later calendar day, as the weblog's
     * time zone counts days: the test for the post page's "Updated" line
     * (the showEntryUpdated macro in weblog.vm).
     */
    public boolean updatedOnLaterDay(Date published, Date updated) {
        return weblog != null
                && DateUtil.isLaterCalendarDay(published, updated, weblog.getTimeZoneInstance());
    }

    /**
     * Return a date in RFC-822 format.
     */
    public String formatRfc822Date(Date date) {
        return DateUtil.formatRfc822(date);
    }
    
    /**
     * Return a date in 8 character format YYYYMMDD.
     */
    public String format8charsDate(Date date) {
        return DateUtil.format8chars(date);
    }

    
    //------------------------------------------------------------ String utils
    
    // isEmpty = empty (size = 0) or null
    public boolean isEmpty(String str) {
        return StringUtils.isEmpty(str);
    }
    
    public boolean isNotEmpty(String str) {
        return StringUtils.isNotEmpty(str);
    }
    
    public String[] split(String str1, String str2) {
        return StringUtils.split(str1, str2);
    }
    
    public boolean equals(String str1, String str2) {
        return Strings.CS.equals(str1, str2);
    }
    
    public boolean isAlphanumeric(String str) {
        return StringUtils.isAlphanumeric(str);
    }
    
    public String[] stripAll(String[] strs) {
        return StringUtils.stripAll(strs);
    }
    
    public String left(String str, int length) {
        return StringUtils.left(str, length);
    }
    
    public String escapeHTML(String str) {
        return StringEscapeUtils.escapeHtml4(str);
    }
    
    public String unescapeHTML(String str) {
        return StringEscapeUtils.unescapeHtml4(str);
    }

    /**
     * A {@code <title>}: "title — name" when that fits in what a search
     * result shows ({@link SeoLimits#TITLE_MAX}, counted in the characters a
     * reader sees), else the title alone, since the name would only be cut
     * off. Both come in HTML-escaped and go out as they came.
     */
    public String titleWithName(String title, String name) {
        if (StringUtils.isEmpty(title)) {
            return name == null ? "" : name;
        }
        if (StringUtils.isEmpty(name)) {
            return title;
        }
        String joined = title + " — " + name;
        return StringEscapeUtils.unescapeHtml4(joined).length() <= SeoLimits.TITLE_MAX ? joined : title;
    }

    /**
     * The alt text for a post's picture in a list: the image's own alt text
     * when it has one, else the post's title. Never empty when there is a
     * title -- an empty alt is what search engines report as "image missing
     * alt text", and the lists hide these links from assistive technology
     * anyway. The title comes in HTML-escaped (entry titles are stored that
     * way); the result is plain text, escaped once on the way out.
     */
    public String pictureAlt(MediaFileWrapper image, String title) {
        String alt = image == null ? null : image.getAltText();
        if (StringUtils.isNotBlank(alt)) {
            return alt;
        }
        return title == null ? "" : StringEscapeUtils.unescapeHtml4(title);
    }

    /**
     * Fallback text (an excerpt, a blog's about text) fitted to a search
     * description: whole sentences within {@link SeoLimits#DESCRIPTION_MAX}.
     * Plain text in and out; escape it on the way out.
     */
    public String metaDescription(String text) {
        return Excerpts.fit(text, SeoLimits.DESCRIPTION_MAX);
    }
    
    public String escapeXML(String str) {
        return StringEscapeUtils.escapeXml11(str);
    }
    
    public String unescapeXML(String str) {
        return StringEscapeUtils.unescapeXml(str);
    }
    
    public String escapeJavaScript(String str) {
        return StringEscapeUtils.escapeEcmaScript(str);
    }
    
    public String unescapeJavaScript(String str) {
        return StringEscapeUtils.unescapeEcmaScript(str);
    }

    /**
     * Escape a value for embedding inside a JSON string literal, e.g. the
     * JSON-LD block emitted by the {@code #showSeoHead} macro. Unlike
     * {@link #escapeJavaScript} this never produces {@code \'} (invalid
     * JSON), and it escapes the forward slash so a value containing
     * {@code </script>} cannot terminate the surrounding inline script
     * element. {@code <}, {@code >} and {@code &} become {@code \\u} escapes,
     * so no markup at all reaches the script block (a post's opening is a
     * description too): every JSON parser reads them back unchanged.
     */
    public String escapeJson(String str) {
        String escaped = StringEscapeUtils.escapeJson(str);
        if (escaped == null) {
            return null;
        }
        return escaped.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }

    /**
     * The schema.org type of the permalink's article block: {@code TechArticle}
     * for an entry typed TECH_ARTICLE, {@code BlogPosting} for everything else
     * (including null). The block's properties are the same either way.
     */
    public String articleSchemaType(WeblogEntry entry) {
        return entry != null && entry.getJsonLdType() == JsonLdType.TECH_ARTICLE
                ? JsonLdType.TECH_ARTICLE.getSchemaType() : JsonLdType.BLOG_POSTING.getSchemaType();
    }

    /**
     * The typed travel JSON-LD object for a permalink whose author picked a
     * schema.org type in the editor's SEO card, ready to drop straight into a
     * SECOND {@code <script type="application/ld+json">} element after the
     * BlogPosting one every permalink keeps -- or null when the entry has no
     * type override (or lacks the data its type structurally requires), which
     * is {@code #showSeoHead}'s signal to emit no second block at all.
     *
     * <p>The four values come from the macro's existing plumbing (the same
     * ones behind the Open Graph tags) and must be passed RAW:
     * {@link EntryJsonLd} is the single place they are escaped, so handing it
     * {@code $seoTitleJson} instead of the plain title would double-escape.
     * Blank means "absent".
     */
    public String travelJsonLd(WeblogEntry entry, String name, String description,
            String imageUrl, String url) {
        return EntryJsonLd.build(entry, name, description, imageUrl, url,
                weblogger == null ? null : weblogger.getMediaFileManager());
    }

    /**
     * The finished JSON for the weblog's business or place block on its
     * non-entry pages, or null when it declares neither. The place's url is
     * the blog's own public URL (so a custom domain is honoured); the
     * business {@code @id} hangs off the site URL.
     */
    public String businessJsonLd(Weblog w) {
        if (w == null || weblogger == null) {
            return null;
        }
        return BusinessJsonLd.forWeblog(w,
                weblogger.getUrlStrategy().getWeblogURL(w, null, true),
                WebloggerRuntimeConfig.getAbsoluteContextURL());
    }

    /**
     * The finished {@code BreadcrumbList} JSON for a post page, or null when
     * there is no trail to state: the site's front page, the post's blog
     * (left out for a post of the site space, whose front page that is), its
     * category, the post. Addresses are absolute and come from the url
     * strategy, so they hold at any context path and on a custom domain.
     *
     * <p>Everything is handed to {@link BreadcrumbJsonLd} RAW and escaped
     * there once -- {@code entryName} is the macro's plain resolved title,
     * never the stored (HTML-escaped) one.
     *
     * @param entryName  the post's plain-text name
     * @param entryUrl   the post's canonical address
     * @param homeLabel  what to call the front page when the site has no name
     */
    public String breadcrumbJsonLd(WeblogEntry entry, String entryName, String entryUrl, String homeLabel) {
        Weblog blog = entry == null ? null : entry.getWebsite();
        if (blog == null || weblogger == null) {
            return null;
        }
        URLStrategy urls = weblogger.getUrlStrategy();
        List<BreadcrumbJsonLd.Crumb> trail = new ArrayList<>();
        trail.add(new BreadcrumbJsonLd.Crumb(
                StringUtils.defaultIfBlank(WebloggerRuntimeConfig.getProperty("site.name"), homeLabel),
                DiscoveryModel.siteHomeUrl(weblogger, urls)));
        if (!blog.isSiteSpace()) {
            trail.add(new BreadcrumbJsonLd.Crumb(blog.getName(), urls.getWeblogURL(blog, null, true)));
        }
        WeblogCategory category = entry.getCategory();
        if (category != null && StringUtils.isNotBlank(category.getName())) {
            trail.add(new BreadcrumbJsonLd.Crumb(category.getName(),
                    urls.getWeblogCollectionURL(blog, null, category.getName(), null, null, -1, true)));
        }
        trail.add(new BreadcrumbJsonLd.Crumb(entryName, entryUrl));
        return BreadcrumbJsonLd.build(trail);
    }

    /** The business as a JSON object for BlogPosting.publisher, or null. */
    public String businessPublisherJson(Weblog w) {
        if (w == null) {
            return null;
        }
        return BusinessJsonLd.publisherFor(w, WebloggerRuntimeConfig.getAbsoluteContextURL());
    }

    /**
     * The facts the footer business card shows, or an empty map when the blog has
     * neither a business nor a place (a place type). Keys, each present only
     * when it has a value: {@code name} (for a place, the place name -- the
     * blog name; otherwise the business name), {@code locality} (for a place,
     * "Locality, Region"; otherwise the business's area served),
     * {@code telephone} (as typed), {@code telHref}
     * ("tel:" + digits with any leading +), {@code bookingHref} (UTM-tagged,
     * campaign = {@code slug}) and {@code dest} (its host). Values are raw;
     * the template escapes them.
     */
    public Map<String, String> businessCard(Weblog w, String slug) {
        if (w == null) {
            return Map.of();
        }
        Business business = w.getBusiness();
        // A place is a place type, the same test BusinessJsonLd.placeNode
        // uses, so the card never shows a place the JSON-LD does not describe.
        boolean hasPlace = StringUtils.isNotBlank(w.getPlaceType());
        if (business == null && !hasPlace) {
            return Map.of();
        }
        Map<String, String> card = new HashMap<>();
        String businessName = business == null ? null : StringUtils.trimToNull(business.getName());
        String placeName = StringUtils.trimToNull(w.getName());
        String name = hasPlace && placeName != null ? placeName
                : businessName != null ? businessName : placeName;
        if (name != null) {
            card.put("name", name);
        }
        String place = hasPlace
                ? Stream.of(w.getPlaceLocality(), w.getPlaceRegion()).map(StringUtils::trimToNull)
                        .filter(Objects::nonNull).collect(Collectors.joining(", "))
                : StringUtils.trimToEmpty(business.getAreaServed());
        if (!place.isEmpty()) {
            card.put("locality", place);
        }
        String phone = business == null ? null : StringUtils.trimToNull(business.getTelephone());
        if (phone != null) {
            Matcher ext = TEL_EXTENSION.matcher(phone);
            boolean hasExt = ext.find();
            String number = hasExt ? phone.substring(0, ext.start()) : phone;
            String digits = number.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                card.put("telephone", phone);
                String href = "tel:" + (number.trim().startsWith("+") ? "+" : "") + digits;
                // RFC 3966: an extension is a parameter, never more digits of the number.
                String extDigits = hasExt ? ext.group(1) : "";
                card.put("telHref", extDigits.isEmpty() ? href : href + ";ext=" + extDigits);
            }
        }
        String booking = BookingLink.resolve(w);
        if (booking != null) {
            card.put("bookingHref", BookingLink.withUtmParams(booking, w, slug));
            String dest = BookingLink.destHost(booking);
            if (dest != null) {
                card.put("dest", dest);
            }
        }
        return card;
    }

    public String replace(String src, String target, String rWith) {
        return Strings.CS.replace(src, target, rWith);
    }
    
    public String replace(String src, String target, String rWith, int maxCount) {
        return Strings.CS.replace(src, target, rWith, maxCount);
    }
    
    /**
     * Remove occurences of html, defined as any text
     * between the characters "&lt;" and "&gt;".  Replace
     * any HTML tags with a space.
     */
    public String removeHTML(String str) {
        return removeHTML(str, true);
    }
    
    /**
     * Remove occurences of html, defined as any text
     * between the characters "&lt;" and "&gt;".
     * Optionally replace HTML tags with a space.
     */
    public String removeHTML(String str, boolean addSpace) {
        return Utilities.removeHTML(str, addSpace);
    }
        
    /**
     * Autoformat.
     */
    public String autoformat(String s) {
        return Utilities.autoformat(s);
    }
    
    /**
     * Strips HTML and truncates.
     */
    public String truncate(String str, int lower, int upper, String appendToEnd) {
        // this method is a dupe of truncateText() method
        return truncateText(str, lower, upper, appendToEnd);
    }
    
    public String truncateNicely(String str, int lower, int upper, String appendToEnd) {
        return Utilities.truncateNicely(str, lower, upper, appendToEnd);
    }
    
    public String truncateText(String str, int lower, int upper, String appendToEnd) {
        return Utilities.truncateText(str, lower, upper, appendToEnd);
    }    
    
    public String hexEncode(String str) {
        if (StringUtils.isEmpty(str)) {
            return str;
        }
        
        return RegexUtil.encode(str);
    }
    
    public String encodeEmail(String str) {
        return str!=null ? RegexUtil.encodeEmail(str) : null;
    }
    
    /**
     * URL encoding.
     * @param s a string to be URL-encoded
     * @return URL encoding of s using character encoding UTF-8; null if s is null.
     */
    public final String encode(String s) {
        if(s != null) {
            return URLUtilities.encode(s);
        } else {
            return s;
        }
    }
    
    /**
     * URL encoding for one component of a link a reader's own program opens
     * (a {@code mailto:} subject, a share link's parameter): as
     * {@link #encode(String)}, with a space as {@code %20}. A {@code +} is a
     * space only in form encoding; a mail program shows it as a plus sign.
     */
    public final String encodeComponent(String s) {
        return s == null ? null : URLUtilities.encode(s).replace("+", "%20");
    }

    /**
     * URL decoding.
     * @param s a URL-encoded string to be URL-decoded
     * @return URL decoded value of s using character encoding UTF-8; null if s is null.
     */
    public final String decode(String s) {
        if(s != null) {
            return URLUtilities.decode(s);
        } else {
            return s;
        }
    }
        
    /**
     * Code (stolen from Pebble) to add rel="nofollow" string to all links in HTML.
     */
    public String addNofollow(String html) {
        return Utilities.addNofollow(html);
    }
    
    /**
     * Transforms the given String into a subset of HTML displayable on a web
     * page. The subset includes &lt;b&gt;, &lt;i&gt;, &lt;p&gt;, &lt;br&gt;,
     * &lt;pre&gt; and &lt;a href&gt; (and their corresponding end tags).
     *
     * @param s   the String to transform
     * @return    the transformed String
     */
    public String transformToHTMLSubset(String s) {
        return Utilities.transformToHTMLSubset(s);
    }
    
    /**
     * Convert a byte array into a Base64 string (as used in mime formats)
     */
    public String toBase64(byte[] aValue) {
        return Utilities.toBase64(aValue);
    }
       
}
