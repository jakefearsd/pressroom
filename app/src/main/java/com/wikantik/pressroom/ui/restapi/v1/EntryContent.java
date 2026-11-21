package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.dto.RevisionDtos;

/** An entry's text and revisions, as {@link RevisionedContent}. */
final class EntryContent implements RevisionedContent<WeblogEntry, WeblogEntryRevision> {

    private final Weblogger weblogger;

    EntryContent(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @Override
    public String storedText(WeblogEntry entry) {
        return entry.getText();
    }

    @Override
    public String lockText(WeblogEntry entry) throws WebloggerException {
        return weblogger.getWeblogEntryManager().lockEntryText(entry);
    }

    @Override
    public WeblogEntryRevision revision(WeblogEntry entry, String revisionId) {
        return WeblogOwnership.revision(weblogger, revisionId, entry);
    }

    @Override
    public String revisionId(WeblogEntryRevision revision) {
        return revision.getId();
    }

    @Override
    public String revisionText(WeblogEntryRevision revision) {
        return revision.getText();
    }

    @Override
    public void restore(WeblogEntry entry, WeblogEntryRevision revision, User editor) throws WebloggerException {
        weblogger.getWeblogEntryManager().restoreRevision(entry, revision, editor);
    }

    @Override
    public void setKept(WeblogEntryRevision revision, boolean kept) throws WebloggerException {
        weblogger.getWeblogEntryManager().setRevisionKept(revision, kept);
    }

    @Override
    public ApiException changed(String currentContentHash) {
        return ApiException.entryChanged(currentContentHash);
    }

    @Override
    public ProblemCode invalidHashCode() {
        return ProblemCode.ENTRY_HASH_INVALID;
    }

    @Override
    public ApiException gone() {
        return ApiException.problem(ProblemCode.ENTRY_NOT_FOUND);
    }

    @Override
    public RevisionDtos.RevisionSummary summary(WeblogEntryRevision revision) {
        return RevisionDtos.toSummary(revision);
    }

    @Override
    public RevisionDtos.RevisionView view(WeblogEntryRevision revision, String diff) {
        return RevisionDtos.toView(revision, diff);
    }
}
