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

package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.FormSubmissionManager;
import org.apache.roller.weblogger.pojos.FormSubmission;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FormSubmissionsController}: the per-weblog contact-inquiries
 * inbox.
 *
 * <p>{@link #aForeignSubmissionIdInTheDeleteSelectionIsSkipped()} is the same
 * ownership hazard covered for every other by-id action in this package --
 * {@code deleteIds} is client input and {@code FormSubmissionManager.get} is a
 * global by-id lookup, so without the check an editor on one weblog could
 * delete another weblog's inquiries.
 */
class FormSubmissionsControllerTest extends EditorControllerTestSupport {

    private Weblog weblogA;
    private Weblog weblogB;

    private FormSubmissionsController controller;
    private FormSubmissionManager submissionManager;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = prepare(new FormSubmissionsController());
        model = newModel();
        submissionManager = weblogger.getFormSubmissionManager();

        weblogA = weblog;
        weblogB = new Weblog();
        weblogB.setId("weblog-2");
        weblogB.setHandle("someoneelse");
    }

    // ------------------------------------------------------------ metadata

    @Test
    void declaresItsMenuAndPermissionMetadata() {
        assertEquals("submissions", controller.getActionName());
        assertEquals("editor", controller.getDesiredMenu());
        assertEquals(List.of(WeblogPermission.POST), controller.requiredWeblogPermissionActions());
    }

    // ------------------------------------------------------------- listing

    @Test
    void listingShowsOnlyThisWeblogsSubmissions() throws Exception {
        FormSubmission mine = submissionOn(weblogA);
        when(submissionManager.getSubmissions(weblogA, 0, 30)).thenReturn(List.of(mine));
        when(submissionManager.getCount(weblogA)).thenReturn(1);

        String view = controller.execute(requestFor(weblogA), model, 0);

        assertEquals(".Submissions", view);
        assertEquals(List.of(mine), model.getAttribute("submissions"));
        assertEquals(1, model.getAttribute("submissionCount"));
        // Never asked for weblogB's submissions at all -- the list is scoped
        // by construction, not filtered after the fact.
        verify(submissionManager, never()).getSubmissions(eq(weblogB), anyInt(), anyInt());
    }

    @Test
    void pagingPassesOffsetAndMaxThroughToTheManager() throws Exception {
        when(submissionManager.getSubmissions(weblogA, 60, 30)).thenReturn(List.of());
        when(submissionManager.getCount(weblogA)).thenReturn(0);

        String view = controller.execute(requestFor(weblogA), model, 2);

        assertEquals(".Submissions", view);
        verify(submissionManager).getSubmissions(weblogA, 60, 30);
        assertEquals(2, model.getAttribute("page"));
    }

    /** The pager reads the page size from the model rather than hard-coding 30 in the JSP. */
    @Test
    void thePageSizeIsGivenToThePager() throws Exception {
        when(submissionManager.getSubmissions(weblogA, 0, 30)).thenReturn(List.of());

        controller.execute(requestFor(weblogA), model, 0);

        assertEquals(30, model.getAttribute("pageSize"));
    }

    @Test
    void aFailedListLookupIsReportedGenericallyRatherThanThrowing() throws Exception {
        when(submissionManager.getSubmissions(weblogA, 0, 30))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(requestFor(weblogA), model, 0);

        assertEquals(".Submissions", view);
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected a generic failure, got: " + errors(model));
    }

    // -------------------------------------------------------------- delete

    @Test
    void aForeignSubmissionIdInTheDeleteSelectionIsSkipped() throws Exception {
        FormSubmission mine = submissionOn(weblogA);
        FormSubmission foreign = submissionOn(weblogB);

        controller.delete(new String[] { mine.getId(), foreign.getId() },
                requestFor(weblogA), model);

        verify(submissionManager).remove(mine);
        verify(submissionManager, never()).remove(foreign);
    }

    @Test
    void deleteReportsOnlyTheSubmissionsActuallyRemoved() throws Exception {
        FormSubmission mine = submissionOn(weblogA);
        FormSubmission foreign = submissionOn(weblogB);
        registerMessage("submissions.deleted", "Deleted {0} submission(s).");
        when(submissionManager.getSubmissions(weblogA, 0, 30)).thenReturn(List.of());
        when(submissionManager.getCount(weblogA)).thenReturn(0);

        controller.delete(new String[] { mine.getId(), foreign.getId() }, requestFor(weblogA), model);

        assertTrue(messages(model).contains("Deleted 1 submission(s)."),
                "Expected a message counting only the one owned submission actually removed, got: "
                        + messages(model));
    }

    @Test
    void deleteFlushesExactlyOnceRegardlessOfSelectionSize() throws Exception {
        FormSubmission first = submissionOn(weblogA);
        FormSubmission second = submissionOn(weblogA);
        when(submissionManager.getSubmissions(weblogA, 0, 30)).thenReturn(List.of());
        when(submissionManager.getCount(weblogA)).thenReturn(0);

        controller.delete(new String[] { first.getId(), second.getId() }, requestFor(weblogA), model);

        assertEquals(1, weblogger.flushCount());
    }

    @Test
    void deleteWithNoSelectionRemovesNothing() throws Exception {
        when(submissionManager.getSubmissions(weblogA, 0, 30)).thenReturn(List.of());
        when(submissionManager.getCount(weblogA)).thenReturn(0);

        String view = controller.delete(null, requestFor(weblogA), model);

        assertEquals(".Submissions", view);
        verify(submissionManager, never()).remove(any());
    }

    @Test
    void deleteReloadsTheFirstPageOfTheList() throws Exception {
        FormSubmission mine = submissionOn(weblogA);
        when(submissionManager.getSubmissions(weblogA, 0, 30)).thenReturn(List.of(mine));
        when(submissionManager.getCount(weblogA)).thenReturn(1);

        controller.delete(new String[] { mine.getId() }, requestFor(weblogA), model);

        assertEquals(List.of(mine), model.getAttribute("submissions"));
    }

    // --- fixtures ---

    private HttpServletRequest requestFor(Weblog weblog) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getLocale()).thenReturn(Locale.US);
        when(req.getAttribute("authenticatedUser")).thenReturn(user);
        when(req.getAttribute("actionWeblog")).thenReturn(weblog);
        return req;
    }

    private FormSubmission submissionOn(Weblog weblog) throws WebloggerException {
        FormSubmission submission = new FormSubmission();
        submission.setId("submission-" + weblog.getHandle() + "-" + System.nanoTime());
        submission.setWeblog(weblog);
        submission.setName("Jane Reader");
        submission.setEmail("jane@example.com");
        submission.setMessage("Hello there");
        when(submissionManager.get(submission.getId())).thenReturn(submission);
        return submission;
    }
}
