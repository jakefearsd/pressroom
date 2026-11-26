package com.wikantik.pressroom.ui.restapi.dto;

import java.util.Date;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Templates API's view of a template: markup only when asked for, and only when there is some. */
class TemplateDtosTest {

    private static WeblogTemplate template(String name) {
        WeblogTemplate t = new WeblogTemplate();
        t.setId("t1");
        t.setName(name);
        t.setAction(ComponentType.CUSTOM);
        return t;
    }

    @Test
    void theListViewNeverCarriesMarkup() throws Exception {
        WeblogTemplate t = template("About");
        CustomTemplateRendition r = new CustomTemplateRendition(t, RenditionType.STANDARD);
        r.setTemplate("<p>hi</p>");
        // the rendition's constructor registers it with the template

        var view = TemplateDtos.toView(t, false);

        assertNull(view.contents());
        assertEquals("About", view.name());
        assertEquals("CUSTOM", view.action());
        assertNull(view.lastModified());
        assertFalse(view.required());
    }

    @Test
    void readingOneCarriesItsStandardMarkup() throws Exception {
        WeblogTemplate t = template("About");
        CustomTemplateRendition r = new CustomTemplateRendition(t, RenditionType.STANDARD);
        r.setTemplate("<p>hi</p>");
        // the rendition's constructor registers it with the template
        Date when = new Date(1_700_000_000_000L);
        t.setLastModified(when);

        var view = TemplateDtos.toView(t, true);

        assertEquals("<p>hi</p>", view.contents());
        assertEquals(when.toInstant(), view.lastModified());
    }

    @Test
    void aTemplateWithoutAStandardRenditionReadsAsEmptyMarkup() throws Exception {
        assertEquals("", TemplateDtos.toView(template("Bare"), true).contents());
    }

    @Test
    void aRequiredTemplateSaysSo() throws Exception {
        assertTrue(TemplateDtos.toView(template("Weblog"), false).required());
    }
}
