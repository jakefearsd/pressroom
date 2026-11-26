import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { ApiError } from '../api/problem';
import { call } from '../api/client';
import { useMe, useWeblog } from '../api/queries';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { formatDateTime } from '../components/DateTime';
import { ProblemBanner, SUCCESS_DISMISS_MS } from '../components/ProblemBanner';
import { useApi, useBoot } from '../context';
import { CONTROL_IDS, OrganizeBox, PublishBox, SEO_FIELDS, SeoDrawer, type Setter } from '../editor/EntryRail';
import { buildWrite, emptyForm, formFromEntry, type EntryForm, type EntryView } from '../editor/entryForm';
import { EditorSurface, type SaveState } from '../editor/EditorSurface';
import type { EditorHandle } from '../editor/CodeMirrorField';
import { MediaChooser, type MediaChoice } from './media/MediaChooser';
import { NewsletterCard } from '../editor/NewsletterCard';
import { RevisionsCard, type RevisionDiff } from '../editor/RevisionsCard';
import { Markup, markupArg } from '../components/Markup';
import { useLatest } from '../editor/useLatest';
import { entryKey, useCategories, useCommentCounts, useEntry, useRevisions, revisionsKey, useStrands } from '../editor/queries';
import { useFieldErrors } from '../forms/useFieldErrors';
import { PageTitle } from '../frame/PageTitle';
import { useT, type Translate } from '../i18n/messages';
import { weblogLevel } from '../routes/visibility';
import { RAIL_ENTRIES, entryTarget } from '../routes/railTable';
import { DraftBar } from '../session/DraftBar';
import { flashState } from '../session/flash';
import { SessionExpiryBar } from '../session/SessionExpiryBar';
import { useDraftKeeper } from '../session/useDraftKeeper';


type Notice =
  | { kind: 'saved'; status: string }
  | { kind: 'restored' | 'kept' | 'unkept' | 'newsletter' };

/** What the route state may carry in: a just-created entry's save notice (the list's Duplicate sends `{message}`, shown by the frame). */
interface RouteState {
  notice?: Notice['kind'];
  status?: string;
}

const lastCategoryKey = (contextPath: string, handle: string) => `pressroom.appLastCategory.v1:${contextPath}:${handle}`;

function readStored(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

/** A non-image file goes in as a Markdown link; brackets and spaces in either part are neutralised. */
export function markdownLink(name: string, url: string): string {
  const text = name.replace(/[[\]]/g, '');
  const href = url.replace(/[ ()]/g, (c) => ({ ' ': '%20', '(': '%28', ')': '%29' })[c] ?? c);
  return `[${text}](${href})`;
}

/**
 * New-from-media (EntryAddWithMediaFileController): the entry's text is seeded with an
 * [image] shortcode for each chosen image and a link for any other file; ids that are not
 * this weblog's (or no longer exist) are skipped, as the controller skipped them.
 */
function useMediaPrefill(handle: string, ids: readonly string[]) {
  const api = useApi();
  return useQuery({
    queryKey: ['entry-media-prefill', handle, ids],
    enabled: ids.length > 0,
    staleTime: Infinity,
    gcTime: 0,
    queryFn: async () => {
      const links: string[] = [];
      for (const id of ids) {
        try {
          const file = await call(api.GET('/v1/weblogs/{handle}/media/{id}', { params: { path: { handle, id } } }));
          links.push(file.contentType?.startsWith('image/') === true
            ? `[image id="${file.id ?? id}"]`
            : `<a href='${file.url ?? ''}'>${file.name ?? ''}</a> (${String(file.length ?? 0)} bytes, ${file.contentType ?? ''})`);
        } catch (error) {
          if (!(error instanceof ApiError)) {
            throw error;
          }
        }
      }
      return links.join('\n\n');
    }
  });
}

/** The route component for both `/weblogs/:handle/entries/new` and `/weblogs/:handle/entries/:id`. */
export function EntryEditor() {
  const { handle = '', id } = useParams();
  const [search] = useSearchParams();
  const mediaIds = useMemo(() => search.getAll('mediaId'), [search]);
  const me = useMe();
  const weblog = useWeblog(handle);
  const categories = useCategories(handle);
  const entry = useEntry(handle, id);
  const prefill = useMediaPrefill(handle, id === undefined ? mediaIds : []);
  const boot = useBoot();

  if (entry.error instanceof ApiError && entry.error.status === 404) {
    return <Navigate to="/" replace />;
  }
  if (entry.data?.status === 'TRASHED') {
    // The JSP sent a trashed entry to the Trash with a flash error; the editor cannot save it.
    return <TrashedRedirect handle={handle} />;
  }
  const failure = [me.error, weblog.error, categories.error, entry.error, prefill.error].find((e) => e !== null);
  if (failure !== undefined) {
    return <ProblemBanner error={failure} />;
  }
  if (me.data === undefined || weblog.data === undefined || categories.data === undefined
    || (id !== undefined && entry.data === undefined) || (id === undefined && mediaIds.length > 0 && prefill.data === undefined)) {
    return null;
  }
  return (
    <EntryEditorForm
      key={id ?? 'new'}
      handle={handle}
      entry={entry.data}
      weblog={weblog.data}
      categories={categories.data.map((c) => c.name ?? '')}
      level={weblogLevel({ globalAdmin: me.data.globalAdmin, membership: me.data.weblogs.find((w) => w.handle === handle)?.permission })}
      siteAdmin={me.data.globalAdmin}
      initialText={prefill.data ?? ''}
      contextPath={boot.contextPath}
    />
  );
}

function TrashedRedirect({ handle }: { handle: string }) {
  return <Navigate to={`/weblogs/${encodeURIComponent(handle)}/trash`} replace />;
}

function noticeText(notice: Notice, t: Translate, server: EntryView | undefined, tz: string | undefined): string {
  switch (notice.kind) {
    case 'saved':
      switch (notice.status) {
        case 'PUBLISHED': return t('weblogEdit.publishedEntry', server?.permalink ?? '');
        case 'SCHEDULED': return t('weblogEdit.scheduledEntry', server?.pubTime === undefined ? '' : formatDateTime(server.pubTime, tz));
        case 'PENDING': return t('weblogEdit.submittedForReview');
        default: return t('weblogEdit.draftSaved');
      }
    case 'restored': return t('weblogEdit.revisionRestored');
    case 'kept': return t('weblogEdit.revisionKept');
    case 'unkept': return t('weblogEdit.revisionUnkept');
    case 'newsletter': return t('newsletter.sent');
  }
}

/** The success banner (tiles/messages.jsp's #messages) for text that carries a link; it dismisses itself after 10 seconds. */
function SuccessNotice({ text }: { text: string }) {
  const t = useT();
  const [closed, setClosed] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => { setClosed(true); }, SUCCESS_DISMISS_MS);
    return () => { clearTimeout(timer); };
  }, []);
  if (closed) {
    return null;
  }
  return (
    <div id="messages" className="alert alert-success alert-dismissible fade show" role="status" aria-live="polite">
      <button type="button" className="btn-close" aria-label={t('generic.close')} onClick={() => { setClosed(true); }} />
      <ul><li><Markup text={text} /></li></ul>
    </div>
  );
}

const SEO_SET: ReadonlySet<string> = new Set(SEO_FIELDS);
const idFor = (field: string) => CONTROL_IDS[field] ?? field;

interface FormProps {
  handle: string;
  entry: EntryView | undefined;
  weblog: NonNullable<ReturnType<typeof useWeblog>['data']>;
  categories: readonly string[];
  level: number;
  siteAdmin: boolean;
  initialText: string;
  contextPath: string;
}

function EntryEditorForm({ handle, entry, weblog, categories, level, siteAdmin, initialText, contextPath }: FormProps) {
  const t = useT();
  const api = useApi();
  const navigate = useNavigate();
  const location = useLocation();
  const queries = useQueryClient();
  const tz = weblog.timeZone;
  const canPublish = level >= 2;

  const [server, setServer] = useState<EntryView | undefined>(entry);
  const [form, setForm] = useState<EntryForm>(() => {
    if (entry !== undefined) {
      return formFromEntry(entry, tz);
    }
    const remembered = readStored(lastCategoryKey(contextPath, handle));
    return emptyForm(weblog.locale ?? '', remembered !== null && categories.includes(remembered) ? remembered : categories[0] ?? '', initialText);
  });
  const [baseline, setBaseline] = useState(() => JSON.stringify(form));
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState<Notice | null>(() => {
    const state = location.state as RouteState | null;
    if (state?.notice === 'saved') {
      return { kind: 'saved', status: state.status ?? 'DRAFT' };
    }
    return null;
  });
  const [busy, setBusy] = useState(false);
  const [saveTick, setSaveTick] = useState(0);
  const [deleting, setDeleting] = useState(false);
  const [chooser, setChooser] = useState<null | 'insert' | 'featuredImage' | 'ogImage'>(null);
  const [pickedUrls, setPickedUrls] = useState<Partial<Record<'featuredImage' | 'ogImage', string>>>({});
  const [copied, setCopied] = useState(false);
  const editor = useRef<EditorHandle>(null);
  const titleBox = useRef<HTMLTextAreaElement>(null);

  const entryId = server?.id;
  const json = JSON.stringify(form);
  const dirty = json !== baseline;
  const keeper = useDraftKeeper(`${handle}:${entryId ?? 'new'}`, json);
  const keeperRef = useLatest(keeper);
  useEffect(() => {
    // After a save the text on screen is what the server has: nothing is left to recover.
    if (saveTick > 0) {
      keeperRef.current.clear();
    }
  }, [saveTick, keeperRef]);

  const errors = useFieldErrors(error, idFor);
  const failedFields = error instanceof ApiError ? error.fieldErrors.flatMap((e) => (e.field === undefined ? [] : [e.field])) : [];
  const seoForced = failedFields.some((f) => SEO_SET.has(f));

  const set: Setter = (key, value) => { setForm((f) => ({ ...f, [key]: value })); };

  const strands = useStrands(handle, canPublish, server?.strand);
  const revisions = useRevisions(handle, entryId);
  const counts = useCommentCounts(handle, entryId, weblog.commentsEnabled === true && canPublish);

  const status = server?.status ?? 'UNSAVED';
  const live = canPublish && status === 'PUBLISHED';

  // Warn before leaving with unsaved edits; a local draft is a recovery aid, not a reason to stop telling.
  useEffect(() => {
    if (!dirty) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = t('weblogEdit.leaveWarning');
    };
    window.addEventListener('beforeunload', warn);
    return () => { window.removeEventListener('beforeunload', warn); };
  }, [dirty, t]);

  /** Runs one server action: clears the last problem, shows a new one in place, and keeps unsaved text safe on a 401. */
  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
    } catch (failure) {
      setError(failure);
      if (failure instanceof ApiError && failure.status === 401) {
        keeper.flush();
      }
    } finally {
      setBusy(false);
    }
  };

  const adopt = (view: EntryView, next: Notice) => {
    const fresh = formFromEntry(view, tz);
    setServer(view);
    setForm(fresh);
    setBaseline(JSON.stringify(fresh));
    setSaveTick((n) => n + 1);
    setNotice(next);
    queries.setQueryData(entryKey(handle, view.id ?? ''), view);
    void queries.invalidateQueries({ queryKey: revisionsKey(handle, view.id ?? '') });
  };

  const save = (intent: 'draft' | 'publish') => run(async () => {
    const body = buildWrite(form, { isNew: server === undefined, intent, canPublish, siteAdmin, tz, now: Date.now(),
      ...(server === undefined ? {} : { original: server }) });
    try {
      window.localStorage.setItem(lastCategoryKey(contextPath, handle), form.category);
    } catch {
      /* storage unavailable: a missing convenience */
    }
    if (server === undefined) {
      const created = await call(api.POST('/v1/weblogs/{handle}/entries', { params: { path: { handle } }, body }));
      keeper.clear();
      void navigate(`/weblogs/${encodeURIComponent(handle)}/entries/${encodeURIComponent(created.id ?? '')}`,
        { replace: true, state: { notice: 'saved', status: created.status ?? 'DRAFT' } satisfies RouteState });
      return;
    }
    const updated = await call(api.PATCH('/v1/weblogs/{handle}/entries/{id}', {
      params: { path: { handle, id: server.id ?? '' } }, body
    }));
    adopt(updated, { kind: 'saved', status: updated.status ?? 'DRAFT' });
  });

  const sendNewsletter = () => run(async () => {
    const sent = await call(api.POST('/v1/weblogs/{handle}/entries/{id}/send-newsletter', {
      params: { path: { handle, id: entryId ?? '' } }
    }));
    setServer(sent);
    setNotice({ kind: 'newsletter' });
  });

  const trash = () => run(async () => {
    await call(api.DELETE('/v1/weblogs/{handle}/entries/{id}', { params: { path: { handle, id: entryId ?? '' } } }));
    keeper.clear();
    setDeleting(false);
    // The list shows the removed message above itself: the one flash channel, router state {message}.
    void navigate(`/weblogs/${encodeURIComponent(handle)}/entries`,
      { state: flashState(t('weblogEdit.entryRemoved', server?.title ?? form.title)) });
  });

  const compare = async (revisionId: string): Promise<RevisionDiff> => {
    const view = await call(api.GET('/v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}', {
      params: { path: { handle, id: entryId ?? '', revisionId } }
    }));
    return {
      text: view.diff ?? '',
      title: { before: view.title ?? '', after: server?.title ?? '' },
      summary: { before: view.summary ?? '', after: server?.summary ?? '' }
    };
  };
  const restore = (revisionId: string) => run(async () => {
    const restored = await call(api.POST('/v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/restore', {
      params: { path: { handle, id: entryId ?? '', revisionId } },
      body: server?.contentHash === undefined ? {} : { expectedContentHash: server.contentHash }
    }));
    adopt(restored, { kind: 'restored' });
  });
  const keep = (revisionId: string, kept: boolean) => run(async () => {
    const path = { handle, id: entryId ?? '', revisionId };
    await call(kept
      ? api.POST('/v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/keep', { params: { path } })
      : api.POST('/v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/unkeep', { params: { path } }));
    setNotice({ kind: kept ? 'kept' : 'unkept' });
    await queries.invalidateQueries({ queryKey: revisionsKey(handle, entryId ?? '') });
  });

  const saveRef = useLatest(save);
  const onShortcutSave = useCallback(() => { void saveRef.current(live ? 'publish' : 'draft'); }, [live, saveRef]);
  const onShortcutPublish = useCallback(() => { void saveRef.current('publish'); }, [saveRef]);

  const choose = (media: MediaChoice) => {
    const target = chooser;
    setChooser(null);
    if (target === 'featuredImage' || target === 'ogImage') {
      set(`${target}Id`, media.id);
      setPickedUrls((urls) => ({ ...urls, [target]: `${media.url}?t=true` }));
    } else if (media.isImage) {
      editor.current?.insert(`[image id="${media.id}"]`);
    } else {
      editor.current?.insert(markdownLink(media.name, media.url));
    }
  };

  const saveState: SaveState = !dirty ? (server === undefined ? 'notSaved' : 'saved')
    : keeper.savedAt !== null ? 'savedLocally' : 'unsaved';

  const settings = RAIL_ENTRIES.find((e) => e.id === 'weblogConfig');
  const settingsHref = settings === undefined ? '#' : entryTarget(settings, contextPath, handle).to;
  const commentsHref = `/weblogs/${encodeURIComponent(handle)}/comments?entry=${encodeURIComponent(entryId ?? '')}`;

  const pageTitle = t(server === undefined ? 'weblogEdit.title.newEntry' : 'weblogEdit.title.editEntry');
  const title = server?.title ?? '';
  const fitTitle = (box: HTMLTextAreaElement) => {
    box.style.height = 'auto';
    box.style.height = `${String(box.scrollHeight)}px`;
  };
  useEffect(() => {
    if (titleBox.current !== null) {
      fitTitle(titleBox.current);
    }
  }, [form.title]);

  const copyPermalink = () => {
    const permalink = server?.permalink ?? '';
    const done = () => { setCopied(true); setTimeout(() => { setCopied(false); }, 1500); };
    if (typeof navigator.clipboard === 'undefined') {
      selectPermalink();
    } else {
      navigator.clipboard.writeText(permalink).then(done, selectPermalink);
    }
  };

  const noticeBanner = notice === null ? null : <SuccessNotice key={JSON.stringify(notice)} text={noticeText(notice, t, server, tz)} />;
  const snippetUrl = server?.permalink ?? `${weblogAbsolute(weblog.handle ?? handle, contextPath)}${t('weblogEdit.snippetPreview.placeholderSlug')}`;

  return (
    <>
      <PageTitle {...(title === '' ? {} : { tabTitle: `${title} — ${pageTitle}` })}>{pageTitle}</PageTitle>
      <p className="subtitle"><Markup text={t(server === undefined ? 'weblogEdit.subtitle.newEntry' : 'weblogEdit.subtitle.editEntry', markupArg(handle))} /></p>
      {noticeBanner}
      <SessionExpiryBar />
      <ProblemBanner error={error} />
      <div className="editor-grid">
        <form id="entry" method="post" className="form-stacked editor-form" onSubmit={(e) => { e.preventDefault(); }}>
          <div className="editor-main">
            <DraftBar keeper={keeper} onRestore={(text) => { setForm(parseDraft(text, form)); }} />
            <textarea ref={titleBox} {...errors.props('title', 'editor-title')} name="bean.title" rows={1} maxLength={255}
                      autoFocus placeholder={t('weblogEdit.title')} aria-label={t('weblogEdit.title')}
                      value={form.title}
                      onChange={(e) => { set('title', e.target.value.replace(/\s*[\r\n]+\s*/g, ' ')); }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && !e.nativeEvent.isComposing && !e.ctrlKey && !e.metaKey) {
                          e.preventDefault();
                          editor.current?.focus();
                        }
                      }} />
            {server === undefined ? null : (
              <p className="editor-permalink" title={t('weblogEdit.permaLink')} role="status" aria-live="polite">
                {status === 'PUBLISHED' ? (
                  <a id="entry_bean_permalink" href={server.permalink}><span id="entry_bean_permalink_text">{server.permalink}</span></a>
                ) : <span id="entry_bean_permalink_text">{server.permalink}</span>}
                {status === 'PUBLISHED' ? (
                  <>
                    {' '}&#183;{' '}
                    <button type="button" className={`editor-permalink-copy${copied ? ' copied' : ''}`} onClick={copyPermalink}>
                      {t(copied ? 'weblogEdit.copiedPermalink' : 'weblogEdit.copyPermalink')}
                    </button>
                  </>
                ) : null}
              </p>
            )}
            <EditorSurface editorRef={editor} handle={handle} entryId={entryId} contextPath={contextPath} text={form.text}
                           onTextChange={(text) => { set('text', text); }} saveState={saveState}
                           primaryActionKey="weblogEdit.post" saveActionKey={live ? 'weblogEdit.update' : 'weblogEdit.save'}
                           onSave={onShortcutSave} onPublish={onShortcutPublish}
                           onChooseMedia={() => { setChooser('insert'); }} />
            <SummaryDrawer form={form} set={set} />
          </div>
          <div className="editor-rail">
            <PublishBox form={form} set={set} errors={errors} status={status} updateTime={server?.updateTime}
                        canPublish={canPublish} siteAdmin={siteAdmin} strands={strands.data ?? []} saving={busy} tz={tz}
                        entryId={entryId}
                        onSave={(intent) => { void save(intent); }}
                        onFullPreview={() => { window.open(`${contextPath}/pressroom-ui/authoring/preview/${encodeURIComponent(handle)}/?previewEntry=${encodeURIComponent(server?.anchor ?? '')}`, 'pressroom-preview'); }} />
            <OrganizeBox form={form} set={set} errors={errors} categories={categories} />
            <SeoDrawer form={form} set={set} errors={errors} handle={handle} weblogName={weblog.name ?? ''}
                       snippetUrl={snippetUrl} entry={server} commentsEnabled={weblog.commentsEnabled === true}
                       canModerate={canPublish} commentCounts={counts.data} commentsHref={commentsHref}
                       forcedOpen={seoForced} pickedUrls={pickedUrls}
                       onPick={setChooser}
                       onClearPick={(target) => {
                         set(`${target}Id`, '');
                         setPickedUrls((urls) => ({ ...urls, [target]: undefined }));
                       }} />
          </div>
        </form>
        {server !== undefined && status === 'PUBLISHED' ? (
          <NewsletterCard sentAt={server.newsletterSentAt} listConfigured={(weblog.newsletterListUuid ?? '') !== ''}
                          settingsHref={settingsHref} sending={busy} onSend={sendNewsletter} />
        ) : null}
        {server === undefined ? null : (
          <RevisionsCard revisions={revisions.data ?? []} busy={busy} onDiff={compare} onRestore={(r) => { void restore(r); }}
                         onKeep={(r, kept) => { void keep(r, kept); }} />
        )}
        {server === undefined ? null : (
          <button type="button" id="deleteEntryButton" className="delete-link editor-rail-extra"
                  aria-label={`${t('weblogEdit.deleteEntry')}: ${form.title}`}
                  onClick={() => { setDeleting(true); }}>{t('weblogEdit.deleteEntry')}</button>
        )}
      </div>
      {deleting ? (
        <ConfirmDialog title={t('weblogEntryRemove.removeWeblogEntry')} confirmLabel={t('weblogEntryRemove.moveToTrash')}
                       onConfirm={() => { void trash(); }} onCancel={() => { setDeleting(false); }}>
          <p><strong id="postTitleLabel">{form.title}</strong></p>
          <p className="mb-0">{t('weblogEntryRemove.areYouSure')}</p>
        </ConfirmDialog>
      ) : null}
      {chooser === null ? null : (
        <MediaChooser handle={handle} imagesOnly={chooser !== 'insert'} onSelect={choose} onCancel={() => { setChooser(null); }} />
      )}
    </>
  );
}

function selectPermalink() {
  const link = document.getElementById('entry_bean_permalink_text');
  const selection = window.getSelection();
  if (link === null || selection === null) {
    return;
  }
  const range = document.createRange();
  range.selectNodeContents(link);
  selection.removeAllRanges();
  selection.addRange(range);
}

/** The site root of a weblog as the snippet preview shows a not-yet-saved entry's address. */
function weblogAbsolute(handle: string, contextPath: string): string {
  return `${contextPath}/${encodeURIComponent(handle)}/`;
}

/** A stored draft is the whole form as JSON; a field a newer build added keeps the form's value. */
function parseDraft(text: string, current: EntryForm): EntryForm {
  try {
    const stored: unknown = JSON.parse(text);
    return typeof stored === 'object' && stored !== null ? { ...current, ...(stored as Partial<EntryForm>) } : current;
  } catch {
    return current;
  }
}

/** The summary, in a quiet drawer under the editor; open from the start when one has been written. */
function SummaryDrawer({ form, set }: { form: EntryForm; set: Setter }) {
  const t = useT();
  const [opened, setOpened] = useState(form.summary !== '');
  return (
    <>
      <button type="button" className={`editor-drawer${opened ? '' : ' collapsed'}`} aria-expanded={opened}
              aria-controls="collapseSummaryEditor" onClick={() => { setOpened((o) => !o); }}>
        {t('weblogEdit.summary')}
      </button>
      <div id="collapseSummaryEditor" className={`collapse${opened ? ' show' : ''}`}>
        <div className="editor-drawer-body">
          <textarea name="bean.summary" id="edit_summary" rows={3} className="col-sm-12" aria-describedby="edit_summary_tip"
                    aria-label={t('weblogEdit.summary')} value={form.summary}
                    onChange={(e) => { set('summary', e.target.value); }} />
          <div id="edit_summary_tip" className="form-text">{t('weblogEdit.summary.tip')}</div>
        </div>
      </div>
    </>
  );
}
