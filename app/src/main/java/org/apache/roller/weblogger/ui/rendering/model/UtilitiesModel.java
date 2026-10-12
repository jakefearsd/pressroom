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

package org.apache.roller.weblogger.ui.rendering.model;

import java.text.SimpleDateFormat;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.BookingLink;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.jsonld.BusinessJsonLd;
import org.apache.roller.weblogger.business.jsonld.EntryJsonLd;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.wrapper.WeblogWrapper;
import org.apache.roller.weblogger.ui.rendering.util.WeblogRequest;
import org.apache.roller.util.DateUtil;
import org.apache.roller.util.RegexUtil;
import org.apache.roller.weblogger.business.shortcodes.GalleryMarkup;
import org.apache.roller.weblogger.pojos.Business;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.wrapper.UserWrapper;
import org.apache.roller.weblogger.ui.rendering.util.ParsedRequest;
import org.apache.roller.weblogger.util.URLUtilities;
import org.apache.roller.weblogger.util.Utilities;
import java.util.List;
import org.apache.roller.weblogger.pojos.User;

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
     * exactly what the share pages do.
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
     * Format date in ISO-8601 format.
     */
    public String formatIso8601Day(Date d) {
        return DateUtil.formatIso8601Day(d);
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
        return StringUtils.equals(str1, str2);
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
     * element.
     */
    public String escapeJson(String str) {
        return StringEscapeUtils.escapeJson(str);
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
        return StringUtils.replace(src, target, rWith);
    }
    
    public String replace(String src, String target, String rWith, int maxCount) {
        return StringUtils.replace(src, target, rWith, maxCount);
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
