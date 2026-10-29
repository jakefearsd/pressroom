package com.wikantik.pressroom.business;

import java.sql.Timestamp;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.PressroomEvent;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventManagerTest {

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("eventuser");
        weblog = TestUtils.setupWeblog("eventblog", user);
        otherWeblog = TestUtils.setupWeblog("othereventblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(otherWeblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static EventManager manager() {
        return TestUtils.weblogger().getEventManager();
    }

    private PressroomEvent record(Weblog target, PressroomEvent.EventType type, String anchor)
            throws Exception {
        PressroomEvent event = new PressroomEvent();
        event.setWeblog(TestUtils.getManagedWebsite(target));
        event.setEventType(type);
        event.setEntryAnchor(anchor);
        event.setOccurredAt(new Timestamp(System.currentTimeMillis()));
        manager().record(event);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return event;
    }

    @Test
    void aRecordedEventComesBackForItsWeblog() throws Exception {
        record(weblog, PressroomEvent.EventType.FORM_SUBMITTED, null);

        List<PressroomEvent> events = manager().getEvents(TestUtils.getManagedWebsite(weblog), 10);

        assertEquals(1, events.size());
        assertEquals(PressroomEvent.EventType.FORM_SUBMITTED, events.get(0).getEventType());
        assertNotNull(events.get(0).getOccurredAt());
    }

    @Test
    void eventsAreScopedToTheirWeblog() throws Exception {
        record(weblog, PressroomEvent.EventType.ENTRY_PUBLISHED, "some-post");

        assertTrue(manager().getEvents(TestUtils.getManagedWebsite(otherWeblog), 10).isEmpty(),
                "another weblog's events must not answer this weblog's query");
    }

    @Test
    void newestEventsComeFirstAndMaxIsHonoured() throws Exception {
        record(weblog, PressroomEvent.EventType.ENTRY_PUBLISHED, "first");
        Thread.sleep(5);
        record(weblog, PressroomEvent.EventType.ENTRY_PUBLISHED, "second");

        List<PressroomEvent> events = manager().getEvents(TestUtils.getManagedWebsite(weblog), 1);

        assertEquals(1, events.size(), "max must cap the result");
        assertEquals("second", events.get(0).getEntryAnchor(), "newest first");
    }

    @Test
    void removingAWeblogsEventsLeavesAnothersAlone() throws Exception {
        record(weblog, PressroomEvent.EventType.FORM_SUBMITTED, null);
        record(otherWeblog, PressroomEvent.EventType.FORM_SUBMITTED, null);

        manager().removeEvents(TestUtils.getManagedWebsite(weblog));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertTrue(manager().getEvents(TestUtils.getManagedWebsite(weblog), 10).isEmpty());
        assertEquals(1, manager().getEvents(TestUtils.getManagedWebsite(otherWeblog), 10).size());
    }
}
