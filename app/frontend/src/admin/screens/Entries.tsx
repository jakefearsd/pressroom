import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { call } from '../api/client';
import { useMeta } from '../api/queries';
import { ENTRIES_PAGE, useCategories, useEntryList, type EntryView } from '../api/listQueries';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { BulkProgress } from '../components/BulkProgress';
import { DateTime } from '../components/DateTime';
import { EmptyState } from '../components/EmptyState';
import { Pager } from '../components/Pager';
import { ProblemBanner } from '../components/ProblemBanner';
import { SelectionBar } from '../components/SelectionBar';
import { StatusPill } from '../components/StatusPill';
import { Markup, markupArg } from '../components/Markup';
import { useApi, useBoot } from '../context';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';
import { useBulk } from '../lib/bulk';
import { failureText } from '../lib/errors';
import { useWeblogRights } from '../lib/permission';

/** The status chips, in the JSP's order; the message keys are weblogEntryQuery.label.* (listed for MessageKeyTest). */
const STATUS_CHIPS = [
  { key: 'ALL', label: 'weblogEntryQuery.label.allEntries' },
  { key: 'DRAFT', label: 'weblogEntryQuery.label.draftOnly' },
  { key: 'PUBLISHED', label: 'weblogEntryQuery.label.publishedOnly' },
  { key: 'PENDING', label: 'weblogEntryQuery.label.pendingOnly' },
  { key: 'SCHEDULED', label: 'weblogEntryQuery.label.scheduledOnly' }
] as const;

/** The JSP list's own default order (EntriesBean.sortBy); the API's, when none is sent, is the publication time. */
const DEFAULT_SORT = 'UPDATE_TIME';

/** The fields behind "More filters"; the toggle starts open when any is set (sortBy counts when not the default). */
const MORE_PARAMS = ['category', 'tags', 'startDate', 'endDate'] as const;

const FILTER_PARAMS = ['status', 'category', 'tags', 'text', 'startDate', 'endDate'] as const;

function titleOf(entry: EntryView): string {
  return entry.title !== undefined && entry.title !== '' ? entry.title : (entry.anchor ?? entry.id ?? '');
}

function withParam(params: URLSearchParams, name: string, value: string | null): string {
  const next = new URLSearchParams(params);
  next.delete('offset');
  if (value === null || value === '') {
    next.delete(name);
  } else {
    next.set(name, value);
  }
  const query = next.toString();
  return query === '' ? '' : `?${query}`;
}

/** The entries list (Entries.jsp + EntriesSidebar.jsp): filter, status chips, paging, row and bulk actions. */
export function Entries() {
  const t = useT();
  const handle = useParams()['handle'] ?? '';
  const [params, setParams] = useSearchParams();
  const search = params.toString();
  const filters = {
    status: params.get('status') ?? undefined,
    category: params.get('category') ?? undefined,
    tags: (params.get('tags') ?? '').split(',').map((tag) => tag.trim()).filter((tag) => tag !== ''),
    text: params.get('text') ?? undefined,
    startDate: params.get('startDate') ?? undefined,
    endDate: params.get('endDate') ?? undefined,
    sortBy: params.get('sortBy') ?? DEFAULT_SORT,
    offset: Math.max(0, Number(params.get('offset') ?? 0) || 0)
  };
  const meta = useMeta();
  const viewsColumn = meta.data?.features.analytics === true;
  const list = useEntryList(handle, filters, viewsColumn);
  const categories = useCategories(handle);
  const base = `/weblogs/${encodeURIComponent(handle)}/entries`;
  const filtered = FILTER_PARAMS.some((name) => name === 'status'
    ? !['', 'ALL'].includes(params.get(name) ?? '') : (params.get(name) ?? '') !== '');

  const submitFilter = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    for (const name of FILTER_PARAMS) {
      const value = name === 'status' ? params.get('status') : form.get(name);
      if (typeof value === 'string' && value.trim() !== '') {
        next.set(name, value.trim());
      }
    }
    const sort = form.get('sortBy');
    if (typeof sort === 'string' && sort !== '') {
      next.set('sortBy', sort);
    }
    setParams(next);
  };

  const activeStatus = params.get('status') ?? 'ALL';
  const urlHasMore = MORE_PARAMS.some((name) => (params.get(name) ?? '') !== '')
    || (params.get('sortBy') ?? DEFAULT_SORT) !== DEFAULT_SORT;
  const [moreOpen, setMoreOpen] = useState(urlHasMore);
  // Back/forward to a URL that carries one of those filters must show it; the author's own toggle is left
  // alone otherwise. Adjusted during render (React's pattern for state derived from a changed value).
  const [sawUrlMore, setSawUrlMore] = useState(urlHasMore);
  if (sawUrlMore !== urlHasMore) {
    setSawUrlMore(urlHasMore);
    if (urlHasMore) {
      setMoreOpen(true);
    }
  }
  return (
    <>
      <PageTitle>{t('weblogEntryQuery.title')}</PageTitle>
      <p className="subtitle"><Markup text={t('weblogEntryQuery.subtitle', markupArg(handle))} /></p>
      <p className="pagetip">{t('weblogEntryQuery.tip')}</p>

      <form className="form-stacked entries-filter mb-3" key={search} onSubmit={submitFilter}
            aria-label={t('weblogEntryQuery.sidebarTitle')}>
        <div className="entries-filter-bar">
          <div className="entries-filter-text">
            <label htmlFor="entries_bean_text" className="form-label">{t('weblogEntryQuery.label.text')}</label>
            <input type="text" id="entries_bean_text" name="text" className="form-control" defaultValue={params.get('text') ?? ''} />
          </div>
          <button type="submit" className="btn btn-secondary">{t('weblogEntryQuery.button.query')}</button>
          <button type="button" id="entries-more-filters" className="btn btn-link entries-more-toggle" aria-expanded={moreOpen}
                  aria-controls="entries-more-fields" title={t('weblogEntryQuery.sidebarDescription')}
                  onClick={() => { setMoreOpen((open) => !open); }}>{t('weblogEntryQuery.moreFilters')}</button>
        </div>
        <nav className="entries-status-chips d-flex flex-wrap gap-2 mb-3" aria-label={t('weblogEdit.status')}>
          {STATUS_CHIPS.map((chip) => {
            const active = chip.key === activeStatus || (chip.key === 'ALL' && activeStatus === '');
            return (
              <Link key={chip.key} to={`${base}${withParam(params, 'status', chip.key === 'ALL' ? null : chip.key)}`}
                    className={`btn btn-sm ${active ? 'btn-secondary' : 'btn-outline-secondary'}`}
                    {...(active ? { 'aria-current': 'page' as const } : {})}>
                {t(chip.label)}
              </Link>
            );
          })}
        </nav>
        <div id="entries-more-fields" className="entries-more-fields" hidden={!moreOpen}>
          <div className="mb-3">
            <label htmlFor="entries_bean_categoryName" className="form-label">{t('weblogEntryQuery.label.category')}</label>
            <select id="entries_bean_categoryName" name="category" className="form-select" defaultValue={params.get('category') ?? ''}>
              <option value="">{t('weblogEntryQuery.label.anyCategory')}</option>
              {(categories.data ?? []).map((c) => <option key={c.id} value={c.name}>{c.name}</option>)}
            </select>
          </div>
          <div className="mb-3">
            <label htmlFor="entries_bean_tagsAsString" className="form-label">{t('weblogEntryQuery.label.tags')}</label>
            <input type="text" id="entries_bean_tagsAsString" name="tags" className="form-control" defaultValue={params.get('tags') ?? ''} />
          </div>
          <div className="mb-3">
            <label htmlFor="entries_bean_startDateString" className="form-label">{t('weblogEntryQuery.label.startDate')}</label>
            <input type="date" id="entries_bean_startDateString" name="startDate" className="form-control" defaultValue={params.get('startDate') ?? ''} />
          </div>
          <div className="mb-3">
            <label htmlFor="entries_bean_endDateString" className="form-label">{t('weblogEntryQuery.label.endDate')}</label>
            <input type="date" id="entries_bean_endDateString" name="endDate" className="form-control" defaultValue={params.get('endDate') ?? ''} />
          </div>
          <div className="mb-3">
            <label htmlFor="entries_bean_sortBy" className="form-label">{t('weblogEntryQuery.label.sortBy')}</label>
            <select id="entries_bean_sortBy" name="sortBy" className="form-select" defaultValue={filters.sortBy}
                    onChange={(event) => { setParams(new URLSearchParams(withParam(params, 'sortBy', event.target.value))); }}>
              <option value="PUBLICATION_TIME">{t('weblogEntryQuery.label.pubTime')}</option>
              <option value="UPDATE_TIME">{t('weblogEntryQuery.label.updateTime')}</option>
            </select>
          </div>
        </div>
      </form>

      <div id="entries-list-marker">
        {list.error !== null ? <ProblemBanner error={list.error} /> : null}
        {list.data === undefined ? null : (
          <>
            <Pager offset={filters.offset} limit={ENTRIES_PAGE} hasMore={list.data.hasMore}
                   hrefFor={(offset) => `${base}${withParam(params, 'offset', offset === 0 ? null : String(offset))}`} />
            <EntryRows key={search} handle={handle} entries={list.data.items} filtered={filtered} base={base}
                       viewsColumn={viewsColumn} />
            <Pager offset={filters.offset} limit={ENTRIES_PAGE} hasMore={list.data.hasMore}
                   hrefFor={(offset) => `${base}${withParam(params, 'offset', offset === 0 ? null : String(offset))}`} />
          </>
        )}
      </div>
    </>
  );
}

/** The table, its selection and every action on it; remounted (selection cleared) when the list changes. */
function EntryRows({ handle, entries, filtered, base, viewsColumn }: {
  handle: string; entries: EntryView[]; filtered: boolean; base: string; viewsColumn: boolean;
}) {
  const t = useT();
  const boot = useBoot();
  const api = useApi();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const { isAuthor } = useWeblogRights(handle);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [bulkTag, setBulkTag] = useState('');
  const [messages, setMessages] = useState<string[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [removing, setRemoving] = useState<EntryView | null>(null);
  const [confirmBulk, setConfirmBulk] = useState(false);
  const bulk = useBulk<EntryView>();

  const refresh = () => queries.invalidateQueries({ queryKey: ['entries', handle] });
  const path = (id: string) => ({ params: { path: { handle, id } } });
  const reset = () => { setMessages([]); setErrors([]); setError(null); };

  const finish = async (outcome: { ok: number; failed: { item: EntryView; error: unknown }[] }, successKey: string) => {
    setMessages(outcome.ok > 0 ? [t(successKey, outcome.ok)] : []);
    setErrors(outcome.failed.length === 0 ? [] : [
      t('weblogEntryQuery.bulkFailed', outcome.failed.length),
      ...outcome.failed.map((f) => t('bulk.itemFailed', titleOf(f.item), failureText(f.error)))
    ]);
    setSelected(new Set());
    await refresh();
  };

  const chosen = entries.filter((entry) => entry.id !== undefined && selected.has(entry.id));
  const toggle = (id: string, on: boolean) => {
    setSelected((now) => {
      const next = new Set(now);
      if (on) { next.add(id); } else { next.delete(id); }
      return next;
    });
  };

  const duplicate = async (entry: EntryView) => {
    reset();
    try {
      const copy = await call(api.POST('/v1/weblogs/{handle}/entries/{id}/duplicate', path(entry.id ?? '')));
      void refresh();
      void navigate(`/weblogs/${encodeURIComponent(handle)}/entries/${encodeURIComponent(copy.id ?? '')}`, {
        state: { message: t('weblogEdit.entryDuplicated', copy.title ?? '') }
      });
    } catch (failure) {
      setError(failure);
    }
  };

  const trashOne = async (entry: EntryView) => {
    setRemoving(null);
    reset();
    try {
      await call(api.DELETE('/v1/weblogs/{handle}/entries/{id}', path(entry.id ?? '')));
      setMessages([t('weblogEdit.entryRemoved', titleOf(entry))]);
      await refresh();
    } catch (failure) {
      setError(failure);
    }
  };

  const publish = async () => {
    reset();
    const outcome = await bulk.run(chosen, (entry) =>
      call(api.PATCH('/v1/weblogs/{handle}/entries/{id}', { ...path(entry.id ?? ''), body: { status: 'PUBLISHED' } })));
    await finish(outcome, isAuthor ? 'weblogEntryQuery.bulkSubmitted' : 'weblogEntryQuery.bulkPublished');
  };

  const addTag = async () => {
    reset();
    const tag = bulkTag.trim();
    if (tag === '') {
      setErrors([t('weblogEntryQuery.bulkTagMissing')]);
      return;
    }
    const outcome = await bulk.run(chosen, (entry) => {
      const tags = entry.tags ?? [];
      return call(api.PATCH('/v1/weblogs/{handle}/entries/{id}', {
        ...path(entry.id ?? ''), body: { tags: tags.includes(tag) ? tags : [...tags, tag] }
      }));
    });
    setBulkTag('');
    await finish(outcome, 'weblogEntryQuery.bulkTagged');
  };

  const trashSelected = async () => {
    setConfirmBulk(false);
    reset();
    const outcome = await bulk.run(chosen, (entry) => call(api.DELETE('/v1/weblogs/{handle}/entries/{id}', path(entry.id ?? ''))));
    await finish(outcome, 'weblogEntryQuery.bulkDeleted');
  };

  if (entries.length === 0) {
    return (
      <>
        <ProblemBanner messages={messages} errors={errors} error={error} />
        {filtered ? (
          <EmptyState title={t('empty.entries.filtered.title')} body={t('empty.entries.filtered.body')}>
            <Link to={base} className="btn btn-secondary">{t('empty.entries.filtered.action')}</Link>
          </EmptyState>
        ) : (
          <EmptyState title={t('empty.entries.title')} body={t('empty.entries.body')}>
            <Link to={`/weblogs/${encodeURIComponent(handle)}/entries/new`} className="btn btn-primary">{t('empty.entries.action')}</Link>
          </EmptyState>
        )}
      </>
    );
  }

  const allOn = chosen.length === entries.length;
  return (
    <>
      <ProblemBanner messages={messages} errors={errors} error={error} />
      <SelectionBar count={chosen.length} formId="entriesBulkForm">
        <button type="button" className="btn btn-secondary" disabled={bulk.progress !== null} onClick={() => { void publish(); }}>
          {isAuthor ? t('weblogEntryQuery.bulkSubmit') : t('weblogEntryQuery.bulkPublish')}
        </button>
        <div className="input-group" style={{ width: '22em' }}>
          <input type="text" name="bulkTag" id="bulkTag" className="form-control" value={bulkTag}
                 placeholder={t('weblogEntryQuery.bulkTagPlaceholder')} onChange={(event) => { setBulkTag(event.target.value); }} />
          <button type="button" className="btn btn-outline-secondary" disabled={bulk.progress !== null}
                  onClick={() => { void addTag(); }}>
            {t('weblogEntryQuery.bulkTagAdd')}
          </button>
        </div>
        <button type="button" className="btn btn-danger" id="bulkDeleteButton" disabled={bulk.progress !== null}
                onClick={() => { setConfirmBulk(true); }}>
          {t('weblogEntryQuery.bulkDelete')}
        </button>
      </SelectionBar>
      <BulkProgress progress={bulk.progress} />
      <div className="table-responsive">
        <table className="pressroomtable table table-striped" width="100%">
          <thead>
            <tr>
              <th scope="col" className="pressroomtable" style={{ width: '3%' }}>
                <input type="checkbox" id="selectAllEntries" className="form-check-input" title={t('weblogEntryQuery.selectAll')}
                       checked={allOn} onChange={(event) => {
                         setSelected(event.target.checked ? new Set(entries.flatMap((e) => (e.id === undefined ? [] : [e.id]))) : new Set());
                       }} />
              </th>
              <th scope="col" className="pressroomtable" style={{ width: '7%' }}>{t('weblogEntryQuery.pubTime')}</th>
              <th scope="col" className="pressroomtable" style={{ width: '7%' }}>{t('weblogEntryQuery.updateTime')}</th>
              <th scope="col" className="pressroomtable">{t('weblogEntryQuery.title')}</th>
              <th scope="col" className="pressroomtable" style={{ width: '10%' }}>{t('weblogEdit.status')}</th>
              <th scope="col" className="pressroomtable" style={{ width: '15%' }}>{t('weblogEntryQuery.category')}</th>
              {viewsColumn ? <th scope="col" className="pressroomtable n" style={{ width: '7%' }}>{t('entries.views30')}</th> : null}
              <th scope="col" className="pressroomtable" style={{ width: '3%' }} />
              <th scope="col" className="pressroomtable" style={{ width: '3%' }} />
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => {
              const id = entry.id ?? '';
              const title = titleOf(entry);
              return (
                <tr key={id}>
                  <td>
                    <input type="checkbox" className="form-check-input entry-select" aria-label={title}
                           checked={selected.has(id)} onChange={(event) => { toggle(id, event.target.checked); }} />
                  </td>
                  <td className="data text-nowrap"><DateTime value={entry.pubTime} /></td>
                  <td className="data text-nowrap"><DateTime value={entry.updateTime} /></td>
                  <td className="entry-cell">
                    <Link className="entry-title" to={`/weblogs/${encodeURIComponent(handle)}/entries/${encodeURIComponent(id)}`}>{title}</Link>
                    <div className="entry-meta">
                      <span className="data">{entry.anchor}</span>
                      {entry.status === 'PUBLISHED' && entry.permalink !== undefined ? (
                        <> &#183; <a className="quiet-link" href={entry.permalink} target="_blank" rel="noopener">{t('generic.view')}</a></>
                      ) : null}
                      {entry.pinned === true ? <> &#183; <span className="entry-pinned">{t('weblogEntryQuery.pinned')}</span></> : null}
                    </div>
                  </td>
                  <td><StatusPill status={entry.status ?? 'DRAFT'} when={entry.status === 'SCHEDULED' ? entry.pubTime : null} /></td>
                  <td>{entry.category}</td>
                  {viewsColumn ? (
                    <td className="n">{entry.views30d === undefined ? '—' : new Intl.NumberFormat(boot.uiLocale).format(entry.views30d)}</td>
                  ) : null}
                  <td>
                    {isAuthor ? null : (
                      <button type="button" className="btn btn-link p-0 align-baseline border-0"
                              aria-label={`${t('generic.duplicate')}: ${title}`} onClick={() => { void duplicate(entry); }}>
                        <span className="bi bi-files" aria-hidden="true" title={t('generic.duplicate')} />
                      </button>
                    )}
                  </td>
                  <td>
                    <button type="button" className="btn btn-link p-0 align-baseline border-0 entry-delete-btn"
                            aria-label={`${t('generic.delete')}: ${title}`} onClick={() => { setRemoving(entry); }}>
                      <span className="bi bi-trash" aria-hidden="true" title={t('generic.delete')} />
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {removing === null ? null : (
        <ConfirmDialog title={t('weblogEntryRemove.removeWeblogEntry')} confirmLabel={t('weblogEntryRemove.moveToTrash')}
                       onConfirm={() => { void trashOne(removing); }} onCancel={() => { setRemoving(null); }}>
          <p><strong>{titleOf(removing)}</strong></p>
          <p>{t('weblogEntryRemove.areYouSure')}</p>
        </ConfirmDialog>
      )}
      {confirmBulk ? (
        <ConfirmDialog title={t('weblogEntryQuery.bulkDeleteConfirm')} confirmLabel={t('weblogEntryRemove.moveToTrash')}
                       onConfirm={() => { void trashSelected(); }} onCancel={() => { setConfirmBulk(false); }}>
          <p>{t('weblogEntryQuery.bulkDeleteWarning')}</p>
          <p className="data">{t('selection.count', chosen.length)}</p>
        </ConfirmDialog>
      ) : null}
    </>
  );
}
