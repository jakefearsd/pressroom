package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import com.wikantik.pressroom.ui.restapi.dto.RevisionDtos;
import com.wikantik.pressroom.util.TextDiff;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The part of an entry or a page that the API revises: stored text, its
 * revisions, and the optimistic-concurrency guard around them. Entries and
 * pages answer the same revision routes the same way and differ only in which
 * manager, which pojo and which {@code *-changed} problem type they use, so
 * those differences are the abstract methods and the shared behaviour (hash
 * check, locked re-check, revision diff, restore, keep) is written once here.
 * Every revision lookup still goes through {@code WeblogOwnership}, in the
 * implementation's {@link #revision}.
 *
 * @param <D> the owning document (entry or page)
 * @param <R> its revision type
 */
interface RevisionedContent<D, R> {

    /** A contentHash: SHA-256 as hex digits, either case accepted. */
    Pattern CONTENT_HASH = Pattern.compile("[0-9a-fA-F]{64}");

    /** The stored text now, unlocked. */
    String storedText(D doc);

    /** The stored text as committed, read under the row lock; null when the row is gone. */
    String lockText(D doc) throws WebloggerException;

    /** The revision with this id of {@code doc}, or null (unknown id or another document's). */
    R revision(D doc, String revisionId);

    /** A revision's id. */
    String revisionId(R revision);

    /** A revision's text. */
    String revisionText(R revision);

    /** The manager's restore. */
    void restore(D doc, R revision, User editor) throws WebloggerException;

    /** The manager's keep or release. */
    void setKept(R revision, boolean kept) throws WebloggerException;

    /** The 409 problem for text that is not the one the caller read. */
    ApiException changed(String currentContentHash);

    /** "an entry" or "a page", for the 400 on a malformed hash. */
    String article();

    /** The 404 for a document whose row vanished under the lock. */
    ApiException gone();

    RevisionDtos.RevisionSummary summary(R revision);

    RevisionDtos.RevisionView view(R revision, String diff);

    /** The revision, or the 404 an unknown id and another document's revision share. */
    default R requireRevision(D doc, String revisionId) {
        R revision = revision(doc, revisionId);
        if (revision == null) {
            throw ApiException.notFound("No such revision.");
        }
        return revision;
    }

    /**
     * A write's {@code expectedContentHash}, when sent, must be the hash of
     * the text now stored; otherwise someone changed the document after the
     * caller read it. Either case of hex matches. A value that is not a hash
     * at all is the caller's mistake, so a 400, not the 409.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Compares case-insensitively only after CONTENT_HASH has confined the value to "
                    + "ASCII hex digits, which no case mapping can turn into anything else.")
    default void requireUnchanged(D doc, String expected) {
        if (expected == null) {
            return;
        }
        if (!CONTENT_HASH.matcher(expected).matches()) {
            throw ApiException.invalidField("expectedContentHash",
                    "expectedContentHash must be the contentHash " + article() + " is read with: 64 hex digits.");
        }
        String current = EntryDtos.contentHash(storedText(doc));
        if (!expected.equalsIgnoreCase(current)) {
            throw changed(current);
        }
    }

    /**
     * {@link #requireUnchanged} again, under the row lock, immediately before
     * the write: the text committed now must be the text this request read,
     * or another request saved in between.
     *
     * @param read the stored text the write was checked or computed against
     */
    default void requireUnchangedUnderLock(D doc, String read) throws WebloggerException {
        String committed = lockText(doc);
        if (committed == null) {
            throw gone();
        }
        if (!Objects.equals(committed, read)) {
            throw changed(EntryDtos.contentHash(committed));
        }
    }

    /**
     * A revision with its text and a unified diff from it to the current
     * text, or to another revision of the same document named by
     * {@code against}.
     */
    default RevisionDtos.RevisionView diff(D doc, String revisionId, String against) {
        R revision = requireRevision(doc, revisionId);
        String from = "revision " + revisionId(revision);
        String diff;
        if (against == null || against.isBlank()) {
            diff = TextDiff.unified(revisionText(revision), storedText(doc), from, "current");
        } else {
            R other = requireRevision(doc, against);
            diff = TextDiff.unified(revisionText(revision), revisionText(other), from,
                    "revision " + revisionId(other));
        }
        return view(revision, diff);
    }

    /**
     * Restores a revision: the hash is checked unlocked, then again under the
     * lock when one was sent, then the manager's restore (never coalesced, so
     * what it replaces always becomes a revision).
     */
    default void restoreChecked(D doc, R revision, String expected, User editor) throws WebloggerException {
        requireUnchanged(doc, expected);
        if (expected != null) {
            requireUnchangedUnderLock(doc, storedText(doc));
        }
        restore(doc, revision, editor);
    }

    /** What a create refuses: both act on stored content, which a create has none of. */
    static void refuseRevisionNote(FieldErrors errors, String revisionNote) {
        if (revisionNote != null) {
            errors.add("revisionNote", "revisionNote applies only to updates; a create leaves no revision.");
        }
    }

    /** See {@link #refuseRevisionNote}. */
    static void refuseExpectedHash(FieldErrors errors, String expectedHash) {
        if (expectedHash != null) {
            errors.add("expectedContentHash", "expectedContentHash applies only to updates.");
        }
    }
}
