package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;

/**
 * Builds an {@link EntryDtos.EntryWrite} by name. The record has twenty-odd
 * positional components, and a test that passes nulls by position breaks,
 * or silently shifts, whenever one is added.
 */
final class EntryWrites {

    private String title;
    private String summary;
    private String text;
    private String status;
    private String category;
    private String pubTime;
    private List<String> tags;
    private String metaTitle;
    private String searchDescription;
    private String canonicalUrl;
    private Boolean noindex;
    private String featuredImageId;
    private String ogImageId;
    private String jsonLdType;
    private Double geoLatitude;
    private Double geoLongitude;
    private String eventStart;
    private String eventEnd;
    private String eventLocation;
    private Boolean commentsClosed;
    private String anchor;
    private List<String> clear;

    private EntryWrites() {
    }

    static EntryWrites write() {
        return new EntryWrites();
    }

    EntryWrites title(String value) { title = value; return this; }
    EntryWrites summary(String value) { summary = value; return this; }
    EntryWrites text(String value) { text = value; return this; }
    EntryWrites status(String value) { status = value; return this; }
    EntryWrites category(String value) { category = value; return this; }
    EntryWrites pubTime(String value) { pubTime = value; return this; }
    EntryWrites tags(List<String> value) { tags = value; return this; }
    EntryWrites metaTitle(String value) { metaTitle = value; return this; }
    EntryWrites searchDescription(String value) { searchDescription = value; return this; }
    EntryWrites canonicalUrl(String value) { canonicalUrl = value; return this; }
    EntryWrites noindex(Boolean value) { noindex = value; return this; }
    EntryWrites featuredImageId(String value) { featuredImageId = value; return this; }
    EntryWrites ogImageId(String value) { ogImageId = value; return this; }
    EntryWrites jsonLdType(String value) { jsonLdType = value; return this; }
    EntryWrites geoLatitude(Double value) { geoLatitude = value; return this; }
    EntryWrites geoLongitude(Double value) { geoLongitude = value; return this; }
    EntryWrites eventStart(String value) { eventStart = value; return this; }
    EntryWrites eventEnd(String value) { eventEnd = value; return this; }
    EntryWrites eventLocation(String value) { eventLocation = value; return this; }
    EntryWrites commentsClosed(Boolean value) { commentsClosed = value; return this; }
    EntryWrites anchor(String value) { anchor = value; return this; }
    EntryWrites clear(List<String> value) { clear = value; return this; }

    EntryDtos.EntryWrite build() {
        return new EntryDtos.EntryWrite(title, summary, text, status, category, pubTime, tags,
                metaTitle, searchDescription, canonicalUrl, noindex, featuredImageId, ogImageId,
                jsonLdType, geoLatitude, geoLongitude, eventStart, eventEnd, eventLocation,
                commentsClosed, anchor, clear);
    }
}
