package com.wikantik.pressroom.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.activation.FileTypeMap;
import jakarta.activation.MimetypesFileTypeMap;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * General purpose utilities, not for use in templates.
 */
public final class Utilities {

    private Utilities() {
    }

    /** The <code>Log</code> instance for this class. */
    private static final Logger log = LoggerFactory.getLogger(Utilities.class);

    public static final String TAG_SPLIT_CHARS = " ,\n\r\f\t";

    private static final Pattern mLinkPattern = Pattern.compile("<a href=.*?>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_B_TAG_PATTERN = Pattern.compile(
            "&lt;b&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_B_TAG_PATTERN = Pattern.compile(
            "&lt;/b&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_I_TAG_PATTERN = Pattern.compile(
            "&lt;i&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_I_TAG_PATTERN = Pattern.compile(
            "&lt;/i&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_BLOCKQUOTE_TAG_PATTERN = Pattern
            .compile("&lt;blockquote&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_BLOCKQUOTE_TAG_PATTERN = Pattern
            .compile("&lt;/blockquote&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern BR_TAG_PATTERN = Pattern.compile(
            "&lt;br */*&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_P_TAG_PATTERN = Pattern.compile(
            "&lt;p&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_P_TAG_PATTERN = Pattern.compile(
            "&lt;/p&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_PRE_TAG_PATTERN = Pattern.compile(
            "&lt;pre&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_PRE_TAG_PATTERN = Pattern.compile(
            "&lt;/pre&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_UL_TAG_PATTERN = Pattern.compile(
            "&lt;ul&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_UL_TAG_PATTERN = Pattern.compile(
            "&lt;/ul&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_OL_TAG_PATTERN = Pattern.compile(
            "&lt;ol&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_OL_TAG_PATTERN = Pattern.compile(
            "&lt;/ol&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_LI_TAG_PATTERN = Pattern.compile(
            "&lt;li&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_LI_TAG_PATTERN = Pattern.compile(
            "&lt;/li&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_A_TAG_PATTERN = Pattern.compile(
            "&lt;/a&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_A_TAG_PATTERN = Pattern.compile(
            "&lt;a href=.*?&gt;", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTE_PATTERN = Pattern.compile("&quot;",
            Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------------------


    public static String unescapeHTML(String str) {
        return StringEscapeUtils.unescapeHtml4(str);
    }

    // ------------------------------------------------------------------------
    /**
     * Remove occurrences of html, defined as any text between the characters
     * "&lt;" and "&gt;". Replace any HTML tags with a space.
     */
    public static String removeHTML(String str) {
        return removeHTML(str, true);
    }

    /**
     * Remove occurrences of html, defined as any text between the characters
     * "&lt;" and "&gt;". Optionally replace HTML tags with a space.
     * 
     * @param str
     * @param addSpace
     * @return
     */
    public static String removeHTML(String str, boolean addSpace) {
        if (str == null) {
            return "";
        }
        int beginTag = str.indexOf('<');
        if (beginTag == -1) {
            return str;
        }
        StringBuilder ret = new StringBuilder(str.length());
        int start = 0;
        int endTag = 0;

        while (beginTag >= start) {
            endTag = str.indexOf('>', beginTag);
            if (endTag == -1) {
                // A '<' never closed is not a tag: keep the rest as written,
                // with no space standing in for a tag that was not removed.
                // Adding one made a second pass add another (Weblog.setTagline
                // cleans every value it loads, so a tagline grew per save).
                ret.append(str, start, str.length());
                break;
            }
            if (beginTag > 0) {
                ret.append(str, start, beginTag);

                // replace each tag with a space (looks better)
                if (addSpace) {
                    ret.append(' ');
                }
            }
            start = endTag + 1;
            beginTag = str.indexOf('<', start);
        }
        // append everything after the last endTag
        if (endTag > -1 && endTag + 1 < str.length()) {
            ret.append(str.substring(endTag + 1));
        }
        return ret.toString().trim();
    }

    // ------------------------------------------------------------------------
    /**
     * Autoformat.
     */
    public static String autoformat(String s) {
        return Strings.CS.replace(s, "\n", "<br />");
    }

    /**
     * Code (stolen from Pebble) to add rel="nofollow" string to all links in
     * HTML.
     */
    public static String addNofollow(String html) {
        if (html == null || html.length() == 0) {
            return html;
        }
        Matcher m = mLinkPattern.matcher(html);
        StringBuilder buf = new StringBuilder(64);
        int copied = 0;
        while (m.find()) {
            String link = m.group();
            buf.append(html, copied, m.start());
            if (link.contains("rel=\"nofollow\"")) {
                // already tagged, leave it alone rather than emitting a second
                // rel attribute (invalid HTML; browsers honour only the first)
                buf.append(link);
            } else {
                buf.append(link, 0, link.length() - 1)
                        .append(" rel=\"nofollow\">");
            }
            copied = m.end();
        }
        buf.append(html, copied, html.length());
        return buf.toString();
    }

    // ------------------------------------------------------------------------
    /**
     * Replaces occurences of non-alphanumeric characters with an underscore.
     */
    public static String replaceNonAlphanumeric(String str) {
        return replaceNonAlphanumeric(str, '_');
    }

    // ------------------------------------------------------------------------
    /**
     * Replaces occurrences of non-alphanumeric characters with a supplied char.
     */
    public static String replaceNonAlphanumeric(String str, char subst) {
        StringBuilder ret = new StringBuilder(str.length());
        char[] testChars = str.toCharArray();
        for (char testChar : testChars) {
            if (Character.isLetterOrDigit(testChar)) {
                ret.append(testChar);
            } else {
                ret.append(subst);
            }
        }
        return ret.toString();
    }

    // ------------------------------------------------------------------------


    // ------------------------------------------------------------------------
    /** Convert string array to string with delimeters. */
    public static String stringArrayToString(String[] stringArray, String delim) {
        StringBuilder bldr = new StringBuilder();
        for (String s : stringArray) {
            if (bldr.length() > 0) {
                bldr.append(delim).append(s);
            } else {
                bldr.append(s);
            }
        }
        return bldr.toString();
    }

    // ------------------------------------------------------------------------
    /** Convert string array to string with delimeters. */
    public static String stringListToString(List<String> stringList,
            String delim) {
        StringBuilder bldr = new StringBuilder();
        for (String s : stringList) {
            if (bldr.length() > 0) {
                bldr.append(delim).append(s);
            } else {
                bldr.append(s);
            }
        }
        return bldr.toString();
    }

    // --------------------------------------------------------------------------
    /** Convert string with delimeters to string array. */
    public static String[] stringToStringArray(String instr, String delim) {
        return StringUtils.split(instr, delim);
    }

    // --------------------------------------------------------------------------
    /** Convert string with delimiters to string list.
     */
    public static List<String> stringToStringList(String instr, String delim) {
        List<String> stringList = new ArrayList<>();
        String[] str = StringUtils.split(instr, delim);
        stringList.addAll(Arrays.asList(str));
        return stringList;
    }

    // --------------------------------------------------------------------------


    // -------------------------------------------------------------------


    // ------------------------------------------------------------------------
    // `in`/`out` are closed inside copyInputToOutput(InputStream, OutputStream,
    // long) below, on every path -- the success path and both IOException
    // branches all close both streams before returning or rethrowing. PMD's
    // CloseResource can't see a close happening inside a different method.
    @SuppressWarnings("PMD.CloseResource")




    // ------------------------------------------------------------------------


    // ------------------------------------------------------------------------




    /**
     * This method based on code from the String taglib at Apache Jakarta:
     * http:/
     * /cvs.apache.org/viewcvs/jakarta-taglibs/string/src/org/apache/taglibs
     * /string/util/StringW.java?rev=1.16&content-type=text/vnd.viewcvs-markup
     * Copyright (c) 1999 The Apache Software Foundation. Author:
     * timster@mac.com
     * 
     * @param str
     * @param lower
     * @param upper
     * @param appendToEnd
     * @return
     */
    public static String truncateNicely(String str, int lower, int upper,
            String appendToEnd) {
        // strip markup from the string
        String str2 = removeHTML(str, false);
        boolean diff = str2.length() < str.length();

        // quickly adjust the upper if it is set lower than 'lower'
        int limit = Math.max(upper, lower);

        // now determine if the string fits within the limit limit
        // if it does, go straight to return, do not pass 'go' and collect $200
        if (str2.length() > limit) {
            // the magic location int
            int loc;

            // first we determine where the next space appears after lower
            loc = str2.lastIndexOf(' ', limit);

            // now we'll see if the location is greater than the lower limit
            if (loc >= lower) {
                // yes it was, so we'll cut it off here
                str2 = str2.substring(0, loc);
            } else {
                // no it wasnt, so we'll cut it off at the limit limit
                str2 = str2.substring(0, limit);
                loc = limit;
            }

            // HTML was removed from original str
            if (diff) {

                // location of last space in truncated string
                loc = str2.lastIndexOf(' ', loc);

                // get last "word" in truncated string (add 1 to loc to
                // eliminate space
                String str3 = str2.substring(loc + 1);

                // find this fragment in original str, from 'loc' position
                loc = str.indexOf(str3, loc) + str3.length();

                // get truncated string from original str, given new 'loc'
                str2 = str.substring(0, loc);

                // get all the HTML from original str after loc
                str3 = extractHTML(str.substring(loc));

                // append the appendToEnd String and
                // add extracted HTML back onto truncated string
                return str2 + appendToEnd + str3;
            }
            // the string was truncated, so we append the appendToEnd String
            return str2 + appendToEnd;
        }

        return str;
    }

    public static String truncateText(String str, int lower, int upper,
            String appendToEnd) {
        // strip markup from the string
        String str2 = removeHTML(str, false);

        // quickly adjust the upper if it is set lower than 'lower'
        int limit = Math.max(upper, lower);

        // now determine if the string fits within the limit limit
        // if it does, go straight to return, do not pass 'go' and collect $200
        if (str2.length() > limit) {
            // the magic location int
            int loc;

            // first we determine where the next space appears after lower
            loc = str2.lastIndexOf(' ', limit);

            // now we'll see if the location is greater than the lower limit
            if (loc >= lower) {
                // yes it was, so we'll cut it off here
                str2 = str2.substring(0, loc);
            } else {
                // no it wasnt, so we'll cut it off at the limit limit
                str2 = str2.substring(0, limit);
            }
            // the string was truncated, so we append the appendToEnd String
            return str2 + appendToEnd;
        }
        return str;
    }

    /**
     * Extract (keep) JUST the HTML from the String.
     * 
     * @param str
     * @return
     */
    public static String extractHTML(String str) {
        if (str == null) {
            return "";
        }
        int beginTag = str.indexOf('<');
        if (beginTag == -1) {
            return str;
        }
        StringBuilder ret = new StringBuilder(str.length());
        int start = 0;
        int endTag;

        while (beginTag >= start) {
            endTag = str.indexOf('>', beginTag);

            // if endTag found, keep tag
            if (endTag > -1) {
                ret.append(str.substring(beginTag, endTag + 1));

                // move start forward and find another tag
                start = endTag + 1;
                beginTag = str.indexOf('<', start);
            }
            // if no endTag found, break
            else {
                break;
            }
        }
        return ret.toString();
    }







    /**
     * Convert a byte array into a Base64 string (as used in mime formats)
     */
    public static String toBase64(byte[] aValue) {

        final String strBase64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

        int byte1;
        int byte2;
        int byte3;
        int iByteLen = aValue.length;
        StringBuilder tt = new StringBuilder();

        for (int i = 0; i < iByteLen; i += 3) {
            boolean bByte2 = (i + 1) < iByteLen;
            boolean bByte3 = (i + 2) < iByteLen;
            byte1 = aValue[i] & 0xFF;
            byte2 = bByte2 ? (aValue[i + 1] & 0xFF) : 0;
            byte3 = bByte3 ? (aValue[i + 2] & 0xFF) : 0;

            tt.append(strBase64Chars.charAt(byte1 / 4))
                    .append(strBase64Chars
                    .charAt((byte2 / 16) + ((byte1 & 0x3) * 16)))
                    .append(bByte2 ? strBase64Chars.charAt((byte3 / 64)
                    + ((byte2 & 0xF) * 4)) : '=')
                    .append(bByte3 ? strBase64Chars.charAt(byte3 & 0x3F) : '=');
        }

        return tt.toString();
    }

    /**
     * @param tag
     * @return
     */
    public static String stripInvalidTagCharacters(String tag) {
        Objects.requireNonNull(tag);

        StringBuilder sb = new StringBuilder();
        char[] charArray = tag.toCharArray();
        for (char c : charArray) {
            // fast-path exclusions quotes and commas are obvious
            // 34 = double-quote, 44 = comma
            switch (c) {
            case 34:
            case 44:
                continue;
            default:
                // every other character falls through to the filter below
                break;
            }

            if ((33 <= c && c <= 126) || Character.isUnicodeIdentifierPart(c)
                    || Character.isUnicodeIdentifierStart(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String normalizeTag(String tag, Locale locale) {
        String stripped = stripInvalidTagCharacters(tag);
        return locale == null ? stripped.toLowerCase(Locale.ROOT) : stripped.toLowerCase(locale);
    }

    /**
     * @param tags String holding space separated list of tags
     * @return List of strings, one string for each tag
     */
    public static List<String> splitStringAsTags(String tags) {
        String[] tagsarr = StringUtils.split(tags, TAG_SPLIT_CHARS);
        if (tagsarr == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(tagsarr);
    }

    /**
     * Transforms the given String into a subset of HTML displayable on a web
     * page. The subset includes &lt;b&gt;, &lt;i&gt;, &lt;p&gt;, &lt;br&gt;,
     * &lt;pre&gt; and &lt;a href&gt; (and their corresponding end tags).
     * 
     * @param text
     *            the String to transform
     * @return the transformed String
     */
    @SuppressFBWarnings(
            value = "MODIFICATION_AFTER_VALIDATION",
            justification = "Nothing here validates: each Matcher is a find-and-replace turning one "
                    + "escaped tag back into that tag. The method is template API only ($utils) with no "
                    + "caller in Pressroom's own templates, and templates are written by weblog admins, who "
                    + "can already emit any markup.")
    public static String transformToHTMLSubset(String text) {

        if (text == null) {
            return null;
        }

        String s = replace(text, OPENING_B_TAG_PATTERN, "<b>");
        s = replace(s, CLOSING_B_TAG_PATTERN, "</b>");
        s = replace(s, OPENING_I_TAG_PATTERN, "<i>");
        s = replace(s, CLOSING_I_TAG_PATTERN, "</i>");
        s = replace(s, OPENING_BLOCKQUOTE_TAG_PATTERN, "<blockquote>");
        s = replace(s, CLOSING_BLOCKQUOTE_TAG_PATTERN, "</blockquote>");
        s = replace(s, BR_TAG_PATTERN, "<br />");
        s = replace(s, OPENING_P_TAG_PATTERN, "<p>");
        s = replace(s, CLOSING_P_TAG_PATTERN, "</p>");
        s = replace(s, OPENING_PRE_TAG_PATTERN, "<pre>");
        s = replace(s, CLOSING_PRE_TAG_PATTERN, "</pre>");
        s = replace(s, OPENING_UL_TAG_PATTERN, "<ul>");
        s = replace(s, CLOSING_UL_TAG_PATTERN, "</ul>");
        s = replace(s, OPENING_OL_TAG_PATTERN, "<ol>");
        s = replace(s, CLOSING_OL_TAG_PATTERN, "</ol>");
        s = replace(s, OPENING_LI_TAG_PATTERN, "<li>");
        s = replace(s, CLOSING_LI_TAG_PATTERN, "</li>");
        s = replace(s, QUOTE_PATTERN, "\"");

        // HTTP links
        s = replace(s, CLOSING_A_TAG_PATTERN, "</a>");
        Matcher m = OPENING_A_TAG_PATTERN.matcher(s);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            String link = s.substring(start, end);
            link = "<" + link.substring(4, link.length() - 4) + ">";
            s = s.substring(0, start) + link + s.substring(end, s.length());
            m = OPENING_A_TAG_PATTERN.matcher(s);
        }

        // escaped angle brackets
        s = s.replace("&amp;lt;", "&lt;");
        s = s.replace("&amp;gt;", "&gt;");
        s = s.replace("&amp;#", "&#");

        return s;
    }

    private static String replace(String string, Pattern pattern,
            String replacement) {
        Matcher m = pattern.matcher(string);
        return m.replaceAll(replacement);
    }

    public static String getContentTypeFromFileName(String fileName) {

        FileTypeMap map = FileTypeMap.getDefaultFileTypeMap();

        // TODO: figure out why PNG is missing from Java MIME types
        if (map instanceof MimetypesFileTypeMap mftm) {
            registerPngMimeType(mftm);
        }

        return map.getContentType(fileName);
    }

    /**
     * Extracted from {@link #getContentTypeFromFileName} so a test can drive
     * the failure path directly with a throwing {@code MimetypesFileTypeMap}
     * subclass -- {@code addMimeTypes} has no declared or documented failure
     * mode for the literal, well-formed string this is called with, so the
     * real JDK implementation cannot be made to throw from a black-box test.
     */
    static void registerPngMimeType(MimetypesFileTypeMap map) {
        try {
            map.addMimeTypes("image/png png PNG");
        } catch (Exception ex) {
            // This guards against a future JDK/vendor MimetypesFileTypeMap
            // that behaves differently. If it ever does throw, PNG simply
            // falls back to whatever the platform default answers, which is
            // exactly the "missing from Java MIME types" case above -- worth
            // a trace if someone is chasing that down.
            log.debug("Could not register PNG mime type", ex);
        }
    }



    /**
     * Checks for name and domain.
     * 
     * @param aEmailAddress
     *            the a email address
     * @return true, if successful
     */
    private static boolean hasNameAndDomain(String aEmailAddress) {
        String[] tokens = aEmailAddress.split("@");
        return tokens.length == 2 && StringUtils.isNotEmpty(tokens[0])
                && StringUtils.isNotEmpty(tokens[1]);
    }

}
