package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPageRevision;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.dto.RevisionDtos;

/** A page's text and revisions, as {@link RevisionedContent}. */
final class PageContent implements RevisionedContent<WeblogPage, WeblogPageRevision> {

    private final Weblogger weblogger;

    PageContent(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    public String storedText(WeblogPage page) {
        return page.getContent();
    }

    @Override
    public String lockText(WeblogPage page) throws WebloggerException {
        return weblogger.getWeblogPageManager().lockPageContent(page);
    }

    @Override
    public WeblogPageRevision revision(WeblogPage page, String revisionId) {
        return WeblogOwnership.pageRevision(weblogger, revisionId, page);
    }

    @Override
    public String revisionId(WeblogPageRevision revision) {
        return revision.getId();
    }

    @Override
    public String revisionText(WeblogPageRevision revision) {
        return revision.getContent();
    }

    @Override
    public void restore(WeblogPage page, WeblogPageRevision revision, User editor) throws WebloggerException {
        weblogger.getWeblogPageManager().restoreRevision(page, revision, editor);
    }

    @Override
    public void setKept(WeblogPageRevision revision, boolean kept) throws WebloggerException {
        weblogger.getWeblogPageManager().setRevisionKept(revision, kept);
    }

    @Override
    public ApiException changed(String currentContentHash) {
        return ApiException.pageChanged(currentContentHash);
    }

    @Override
    public ProblemCode invalidHashCode() {
        return ProblemCode.PAGE_HASH_INVALID;
    }

    @Override
    public ApiException gone() {
        return ApiException.problem(ProblemCode.PAGE_NOT_FOUND);
    }

    @Override
    public RevisionDtos.RevisionSummary summary(WeblogPageRevision revision) {
        return RevisionDtos.toSummary(revision);
    }

    @Override
    public RevisionDtos.RevisionView view(WeblogPageRevision revision, String diff) {
        return RevisionDtos.toView(revision, diff);
    }
}
