package com.wikantik.pressroom.ui.restapi.dto;

import java.sql.Timestamp;
import com.wikantik.pressroom.pojos.FormSubmission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** One contact-form message as the inbox shows it. */
class SubmissionDtosTest {

    @Test
    void aMessageCarriesItsFieldsAndAnIsoInstant() {
        FormSubmission s = new FormSubmission();
        s.setId("s1");
        s.setName("Ann");
        s.setEmail("ann@example.com");
        s.setSubject("Hi");
        s.setMessage("Hello");
        s.setPageSlug("contact");
        s.setEntryAnchor("post-1");
        Timestamp when = new Timestamp(1_700_000_000_000L);
        s.setCreated(when);

        var view = SubmissionDtos.toView(s);

        assertEquals("s1", view.id());
        assertEquals("Ann", view.name());
        assertEquals("ann@example.com", view.email());
        assertEquals("Hi", view.subject());
        assertEquals("Hello", view.message());
        assertEquals("contact", view.pageSlug());
        assertEquals("post-1", view.entryAnchor());
        assertEquals(when.toInstant(), view.created());
    }

    @Test
    void aMessageWithoutATimestampHasNoCreatedInstant() {
        FormSubmission s = new FormSubmission();
        s.setId("s2");
        s.setCreated(null);

        assertNull(SubmissionDtos.toView(s).created());
    }
}
