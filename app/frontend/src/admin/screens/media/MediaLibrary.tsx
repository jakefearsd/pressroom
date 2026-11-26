import { useState, type FormEvent, type KeyboardEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useDirectories, useMediaActions, useMediaList, type DirectoryView, type MediaView } from '../../api/media';
import { ApiError } from '../../api/problem';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { EmptyState } from '../../components/EmptyState';
import { ProblemBanner } from '../../components/ProblemBanner';
import { Markup, markupArg } from '../../components/Markup';
import { runBulk } from '../../lib/bulk';
import { SelectionBar } from '../../components/SelectionBar';
import { PageTitle } from '../../frame/PageTitle';
import { useBoot } from '../../context';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { useT } from '../../i18n/messages';
import { useWeblogRights } from '../../lib/permission';
import { isImage, splitTags, thumbnailUrl } from './shared';

const SORTS = ['name', 'date_uploaded', 'type'] as const;
const UNIT_BYTES: Record<string, number> = { bytes: 1, kb: 1024, mb: 1024 * 1024 };

interface Criteria {
  q: string;
  type: string;
  size: string;
  unit: string;
  tags: string;
}

const readCriteria = (params: URLSearchParams): Criteria => ({
  q: params.get('q') ?? '', type: params.get('type') ?? '', size: params.get('size') ?? '',
  unit: params.get('unit') ?? 'kb', tags: params.get('tags') ?? ''
});

const sizeOf = (c: Criteria): number => Math.max(0, Number(c.size) || 0);
const isSearch = (c: Criteria): boolean => c.q.trim() !== '' || c.type !== '' || sizeOf(c) > 0 || c.tags.trim() !== '';

/** The search criteria the API cannot filter (size, tags) applied to what it returned. */
export function narrow(files: readonly MediaView[], c: Criteria): MediaView[] {
  const min = sizeOf(c) * (UNIT_BYTES[c.unit] ?? 1);
  const wanted = splitTags(c.tags.toLowerCase());
  return files.filter((f) => (min === 0 || (f.length ?? 0) > min)
    && wanted.every((tag) => (f.tags ?? []).some((have) => have.toLowerCase() === tag)));
}

function Tile({ file, selectable, selected, onToggle, onOpen }: {
  file: MediaView; selectable: boolean; selected: boolean; onToggle: () => void; onOpen: () => void;
}) {
  const t = useT();
  const [lit, setLit] = useState(false);
  const name = file.name ?? '';
  const open = (event: KeyboardEvent) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onOpen();
    }
  };
  return (
    <li className={`align-images${lit ? ' highlight' : ''}`}
        onMouseEnter={() => { setLit(true); }} onMouseLeave={() => { setLit(false); }}>
      <div className="mediaObject" data-media-file-id={file.id} data-media-file-name={name} aria-label={name}
           {...(selectable ? { role: 'button', tabIndex: 0, onClick: onOpen, onKeyDown: open,
             onFocus: () => { setLit(true); }, onBlur: () => { setLit(false); } } : {})}>
        {isImage(file)
          ? <img src={thumbnailUrl(file)} title={name} alt={name} style={{ maxWidth: 120, maxHeight: 120 }} />
          : <span className="bi bi-file-earmark fs-1" aria-hidden="true" />}
      </div>
      <div className="mediaObjectInfo">
        {selectable ? (
          <input type="checkbox" className="form-check-input" name="selectedMediaFiles" value={file.id}
                 aria-label={name} checked={selected} onChange={onToggle} />
        ) : null}
        {' '}{name}
        {selectable && isImage(file) && (file.altText ?? '').trim() === '' ? (
          <button type="button" className="media-alt-missing" data-alt-fix data-media-file-id={file.id}
                  title={t('mediaFileView.altMissing.tip')}
                  onClick={(event) => { event.stopPropagation(); onOpen(); }}>
            {t('mediaFileView.altMissing')}
          </button>
        ) : null}
      </div>
    </li>
  );
}

/**
 * MediaFileView.jsp and its sidebar: browse a folder (or search the weblog), sort, select and
 * move/delete/start-an-entry, create and delete folders, set a folder private. The folder, sort
 * and search ride in the URL (`dir`, `sort`, `q`, `type`, `size`, `unit`, `tags`) so a reload or
 * a link lands on the same view. A contributor sees the files, none of the controls.
 */
export function MediaLibrary() {
  const t = useT();
  const boot = useBoot();
  const handle = useParams()['handle'] ?? '';
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const canManage = useWeblogRights(handle).mayPublish;
  const dirs = useDirectories(handle);
  const actions = useMediaActions(handle);

  const criteria = readCriteria(params);
  const searching = isSearch(criteria);
  const directories = dirs.data ?? [];
  const dirId = params.get('dir') ?? directories.find((d) => d.name === 'default')?.id ?? directories[0]?.id;
  const current: DirectoryView | undefined = directories.find((d) => d.id === dirId);
  const sort = SORTS.find((s) => s === params.get('sort')) ?? 'name';
  const list = useMediaList(
    handle,
    searching ? { q: criteria.q.trim(), type: criteria.type, sort } : { directoryId: dirId, sort },
    dirs.isSuccess && (searching || dirId !== undefined)
  );
  const files = searching ? narrow(list.data?.items ?? [], criteria) : (list.data?.items ?? []);

  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [moveTo, setMoveTo] = useState('');
  const [confirm, setConfirm] = useState<'delete' | 'folder' | null>(null);
  const [messages, setMessages] = useState<string[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [folderName, setFolderName] = useState('');
  const [folderError, setFolderError] = useState<unknown>(null);
  const fieldErrors = useFieldErrors(folderError, () => 'newDirectoryName');
  const [draft, setDraft] = useState<Criteria | null>(null);
  const form = draft ?? criteria;

  const report = (messageList: string[], errorList: string[], error: unknown = null) => {
    setMessages(messageList);
    setErrors(errorList);
    setProblem(error);
  };
  const go = (next: Record<string, string | undefined>) => {
    const merged = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value === undefined || value === '') {
        merged.delete(key);
      } else {
        merged.set(key, value);
      }
    }
    setSelected(new Set());
    setParams(merged);
  };
  const openFile = (file: MediaView) => { void navigate(`/weblogs/${encodeURIComponent(handle)}/media/${encodeURIComponent(file.id ?? '')}`); };
  const toggle = (id: string) => {
    const next = new Set(selected);
    if (!next.delete(id)) {
      next.add(id);
    }
    setSelected(next);
  };

  const names = new Map(files.map((f) => [f.id ?? '', f.name ?? '']));
  const failures = (results: readonly { item: string; error?: unknown }[]) => results.map((r) => {
    const why = r.error instanceof ApiError ? r.error.detail : '';
    return `${names.get(r.item) ?? r.item}${why === '' ? '' : `: ${why}`}`;
  });

  const runOnSelected = async (work: (id: string) => Promise<unknown>, ok: string, bad: string) => {
    const ids = [...selected];
    setProgress({ done: 0, total: ids.length });
    const results = await runBulk(ids, work, (done) => { setProgress({ done, total: ids.length }); });
    setProgress(null);
    const failed = failures(results);
    await actions.refresh();
    setSelected(new Set(results.map((r) => r.item)));
    report(failed.length < ids.length ? [ok] : [], failed.length === 0 ? [] : [bad, ...failed]);
  };

  const deleteSelected = async () => {
    setConfirm(null);
    await runOnSelected(actions.remove, t('mediaFile.delete.success'), t('mediaFile.delete.error'));
  };
  const moveSelected = async () => {
    await runOnSelected((id) => actions.update(id, { directoryId: moveTo || (dirId ?? '') }),
      t('mediaFile.move.success'), t('mediaFile.move.errors'));
  };

  const deleteFolder = async () => {
    setConfirm(null);
    if (current === undefined) {
      return;
    }
    // The server refuses a non-empty folder (directory.notEmpty): the confirmed delete empties it first.
    const ids = files.map((f) => f.id ?? '');
    const results = await runBulk(ids, actions.remove);
    const failed = failures(results);
    if (failed.length > 0) {
      await actions.refresh();
      report([], [t('mediaFile.deleteFolder.error'), ...failed]);
      return;
    }
    try {
      await actions.deleteDirectory(current.id ?? '');
      await actions.refresh();
      setParams(new URLSearchParams());
      report([t('mediaFile.deleteFolder.success')], []);
    } catch (error) {
      await actions.refresh();
      report([], [t('mediaFile.deleteFolder.error')], error);
    }
  };

  const setPrivate = async (value: boolean) => {
    try {
      await actions.setPrivate(current?.id ?? '', value);
      await actions.refresh();
      report([t('mediaFile.privacy.updated')], []);
    } catch (error) {
      report([], [t('mediaFile.privacy.error')], error);
    }
  };

  const createFolder = async (event: FormEvent) => {
    event.preventDefault();
    setFolderError(null);
    const name = folderName.trim();
    try {
      const created = await actions.createDirectory(name);
      await actions.refresh();
      setFolderName('');
      go({ dir: created.id, sort: undefined, q: undefined, type: undefined, size: undefined, tags: undefined });
      report([t('mediaFile.directoryCreate.success', name)], []);
    } catch (error) {
      setFolderError(error);
      const exists = error instanceof ApiError && error.code === 'directory.nameInUse';
      report([], exists ? [t('mediaFile.directoryCreate.error.exists', name)] : [], exists ? null : error);
    }
  };

  const search = (event: FormEvent) => {
    event.preventDefault();
    setDraft(null);
    go({ q: form.q.trim(), type: form.type, size: sizeOf(form) > 0 ? String(sizeOf(form)) : '', unit: form.unit, tags: form.tags.trim() });
  };

  const typeLabel = (type: string) => {
    switch (type) {
      case 'image': return t('mediaFileView.image');
      case 'video': return t('mediaFileView.video');
      case 'audio': return t('mediaFileView.audio');
      default: return t('mediaFileView.others');
    }
  };
  const unitLabel = (unit: string) => {
    switch (unit) {
      case 'mb': return t('mediaFileView.mb');
      case 'bytes': return t('mediaFileView.bytes');
      default: return t('mediaFileView.kb');
    }
  };

  const folderOptions = directories.map((d) => <option key={d.id} value={d.id}>{d.name}</option>);
  const addUrl = `/weblogs/${encodeURIComponent(handle)}/media/upload${dirId === undefined ? '' : `?dir=${encodeURIComponent(dirId)}`}`;
  const showForm = !searching || files.length > 0;
  const loading = list.isPending && dirs.isSuccess && (searching || dirId !== undefined);

  return (
    <>
      <PageTitle>{t('mediaFileView.title')}</PageTitle>
      <ProblemBanner messages={messages} errors={errors} error={problem ?? dirs.error ?? list.error} />

      <div className="media-tools">
        {canManage ? (
          <div className="sidebar-group">
            <p className="sidebar-label">{t('mediaFileSidebar.actions')}</p>
            <Link to={addUrl}><span className="bi bi-image" aria-hidden="true" /> {t('mediaFileSidebar.add')}</Link>
          </div>
        ) : null}

        {canManage && !searching ? (
          <div className="sidebar-group">
            <p className="sidebar-label"><span className="bi bi-folder2-open" aria-hidden="true" /> {t('mediaFileView.addDirectory')}</p>
            <form id="createDirectoryForm" onSubmit={(e) => { void createFolder(e); }}>
              <label htmlFor="newDirectoryName">{t('mediaFileView.directoryName')}</label>
              <div className="input-group">
                <input type="text" name="newDirectoryName" maxLength={255} {...fieldErrors.props('name', 'form-control')}
                       value={folderName} onChange={(e) => { setFolderName(e.target.value); }} />
                <button type="submit" id="newDirectoryButton" className="btn btn-secondary"
                        disabled={folderName.trim() === ''}>{t('mediaFileView.create')}</button>
              </div>
            </form>
          </div>
        ) : null}

        <div className="sidebar-group">
          <p className="sidebar-label">{t('mediaFileView.search')}</p>
          <form id="mediaFileSearchForm" className="form-stacked" onSubmit={search}>
            <label htmlFor="beanName">{t('generic.name')}</label>
            <input type="text" id="beanName" maxLength={255} className="form-control" value={form.q}
                   onChange={(e) => { setDraft({ ...form, q: e.target.value }); }} />
            <label htmlFor="beanType">{t('mediaFileView.type')}</label>
            <select id="beanType" className="form-select" value={form.type}
                    onChange={(e) => { setDraft({ ...form, type: e.target.value }); }}>
              <option value="">{t('mediaFileView.any')}</option>
              <option value="image">{t('mediaFileView.image')}</option>
              <option value="video">{t('mediaFileView.video')}</option>
              <option value="audio">{t('mediaFileView.audio')}</option>
              <option value="other">{t('mediaFileView.others')}</option>
            </select>
            <label id="beanSizeLabel" htmlFor="beanSize">{t('mediaFileView.largerThan')}</label>
            <div className="input-group">
              <input type="number" min={0} id="beanSize" className="form-control" value={form.size}
                     onChange={(e) => { setDraft({ ...form, size: e.target.value }); }} />
              <select aria-labelledby="beanSizeLabel" className="form-select" value={form.unit}
                      onChange={(e) => { setDraft({ ...form, unit: e.target.value }); }}>
                <option value="bytes">{t('mediaFileView.bytes')}</option>
                <option value="kb">{t('mediaFileView.kb')}</option>
                <option value="mb">{t('mediaFileView.mb')}</option>
              </select>
            </div>
            <label htmlFor="beanTags">{t('mediaFileView.tags')}</label>
            <input type="text" id="beanTags" maxLength={50} className="form-control" value={form.tags}
                   onChange={(e) => { setDraft({ ...form, tags: e.target.value }); }} />
            <button type="submit" id="searchButton" className="btn btn-primary" disabled={!isSearch(form)}>
              {t('mediaFileView.search')}
            </button>
            {searching ? (
              <button type="button" id="resetButton" className="btn btn-secondary"
                      onClick={() => { setDraft(null); go({ q: undefined, type: undefined, size: undefined, unit: undefined, tags: undefined }); }}>
                {t('mediaFileView.reset')}
              </button>
            ) : null}
          </form>
        </div>
      </div>

      {searching ? (
        <>
          <p className="subtitle">{t('mediaFileView.searchTitle')}</p>
          <p className="pagetip">
            {files.length > 0 ? <Markup text={t('mediaFileView.matchingResults', String(files.length))} /> : null}
            {' '}{t('mediaFileView.searchInfo')}
          </p>
          <ul>
            {criteria.q.trim() === '' ? null : <li><Markup text={t('mediaFileView.filesNamed', markupArg(criteria.q.trim()))} /></li>}
            {sizeOf(criteria) > 0 ? <li><Markup text={t('mediaFileView.filesOfSize', t('mediaFileView.gt'), String(sizeOf(criteria)), unitLabel(criteria.unit))} /></li> : null}
            {criteria.type === '' ? null : <li><Markup text={t('mediaFileView.filesOfType', markupArg(typeLabel(criteria.type)))} /></li>}
            {criteria.tags.trim() === '' ? null : <li><Markup text={t('mediaFileView.filesTagged', markupArg(criteria.tags.trim()))} /></li>}
          </ul>
        </>
      ) : current?.name === 'default' ? (
        <>
          <p className="subtitle"><Markup text={t('mediaFileView.subtitle', markupArg(handle))} /></p>
          <p className="pagetip">{t('mediaFileView.rootPageTip')}</p>
        </>
      ) : (
        <>
          <p className="subtitle">{t('mediaFileView.folderName')}: {current?.name ?? ''}</p>
          <p className="pagetip">{t('mediaFileView.dirPageTip')}</p>
        </>
      )}

      {showForm && !loading ? (
        <form id="mediaFileViewForm" name="mediaFileViewForm" onSubmit={(e) => { e.preventDefault(); }}>
          <div className="media-view-controls d-flex flex-wrap gap-3 mb-3">
            {directories.length > 0 ? (
              <div>
                <label htmlFor="viewDirectoryMenu" className="form-label">{t('mediaFileView.viewFolder')}</label>
                <select name="viewDirectoryId" id="viewDirectoryMenu" className="form-select" value={dirId ?? ''}
                        onChange={(e) => { go({ dir: e.target.value, q: undefined, type: undefined, size: undefined, tags: undefined }); }}>
                  {folderOptions}
                </select>
              </div>
            ) : null}
            {!searching && files.length > 0 ? (
              <div>
                <label htmlFor="sortByMenu" className="form-label">{t('mediaFileView.sortBy')}</label>
                <select name="sortBy" id="sortByMenu" className="form-select" value={sort}
                        onChange={(e) => { go({ sort: e.target.value }); }}>
                  <option value="name">{t('generic.name')}</option>
                  <option value="date_uploaded">{t('mediaFileView.date')}</option>
                  <option value="type">{t('mediaFileView.type')}</option>
                </select>
              </div>
            ) : null}
          </div>

          {!searching && files.length === 0 ? (
            <EmptyState title={t('empty.media.title')} body={t('empty.media.body')}>
              {canManage ? <Link to={addUrl} className="btn btn-primary">{t('empty.media.action')}</Link> : null}
            </EmptyState>
          ) : (
            <>
              <div id="imageGrid" className="card">
                <div className="card-body">
                  <ul>
                    {files.map((file) => (
                      <Tile key={file.id} file={file} selectable={canManage} selected={selected.has(file.id ?? '')}
                            onToggle={() => { toggle(file.id ?? ''); }} onOpen={() => { openFile(file); }} />
                    ))}
                  </ul>
                </div>
              </div>
              <div style={{ clear: 'left' }} />
              {canManage ? (
                <SelectionBar count={selected.size} formId="mediaFileViewForm">
                  {progress === null ? null : (
                    <progress value={progress.done} max={progress.total} aria-valuetext={`${String(progress.done)}/${String(progress.total)}`} />
                  )}
                  <button id="toggleButton" type="button" className="btn btn-secondary"
                          onClick={() => { setSelected(selected.size === files.length ? new Set() : new Set(files.map((f) => f.id ?? ''))); }}>
                    {t('generic.toggle')}
                  </button>
                  <button id="moveButton" type="button" className="btn btn-secondary"
                          disabled={selected.size === 0 || progress !== null} onClick={() => { void moveSelected(); }}>
                    {t('mediaFileView.moveSelected')}
                  </button>
                  <label htmlFor="moveTargetMenu" className="visually-hidden">{t('mediaFileView.moveSelected')}</label>
                  <select name="selectedDirectory" id="moveTargetMenu" className="form-select d-inline-block"
                          disabled={selected.size === 0} value={moveTo || (dirId ?? '')}
                          onChange={(e) => { setMoveTo(e.target.value); }}>
                    {folderOptions}
                  </select>
                  <button id="newEntryButton" type="submit" form="createPostForm" className="btn btn-secondary"
                          disabled={selected.size === 0}>
                    {t('mediaFileView.newEntryWithSelected')}
                  </button>
                  <button id="deleteButton" type="button" className="btn btn-danger"
                          disabled={selected.size === 0 || progress !== null} onClick={() => { setConfirm('delete'); }}>
                    {t('mediaFileView.deleteSelected')}
                  </button>
                </SelectionBar>
              ) : null}
            </>
          )}

          {canManage && current !== undefined && current.name !== 'default' && !searching ? (
            <div className="d-flex flex-wrap gap-2 align-items-center mb-3">
              <button id="deleteFolderButton" type="button" className="btn btn-secondary"
                      onClick={() => { setConfirm('folder'); }}>{t('mediaFileView.deleteFolder')}</button>
            </div>
          ) : null}
        </form>
      ) : null}

      {searching && !loading && files.length === 0 ? (
        <EmptyState title={t('empty.mediaSearch.title')} body={t('mediaFileView.noResults')}>
          <button type="button" className="btn btn-primary"
                  onClick={() => { setDraft(null); go({ q: undefined, type: undefined, size: undefined, unit: undefined, tags: undefined }); }}>
            {t('empty.mediaSearch.action')}
          </button>
        </EmptyState>
      ) : null}

      <form id="createPostForm" method="post" action={`${boot.contextPath}/pressroom-ui/authoring/entryAddWithMediaFile.rol`}>
        <input type="hidden" name="weblog" value={handle} />
        {[...selected].map((id) => <input key={id} type="hidden" name="selectedImages" value={id} />)}
        <input type="hidden" name="_csrf" value={boot.csrf.token} />
      </form>

      {canManage && current !== undefined && !searching ? (
        <div id="directoryPrivacyCard" className="card mt-3">
          <div className="card-header">{t('mediaFile.privacy.title')}</div>
          <div className="card-body">
            <div className="form-check">
              <input type="checkbox" className="form-check-input" id="directoryPrivateToggle"
                     checked={current.isPrivate ?? false} onChange={(e) => { void setPrivate(e.target.checked); }} />
              <label className="form-check-label" htmlFor="directoryPrivateToggle">{t('mediaFile.directoryPrivate')}</label>
            </div>
          </div>
        </div>
      ) : null}

      {confirm === 'delete' ? (
        <ConfirmDialog title={t('mediaFile.delete.confirm')} confirmLabel={t('mediaFileView.deleteSelected')}
                       onConfirm={() => { void deleteSelected(); }} onCancel={() => { setConfirm(null); }} />
      ) : null}
      {confirm === 'folder' ? (
        <ConfirmDialog title={t('mediaFile.deleteFolder.confirm')} confirmLabel={t('mediaFileView.deleteFolder')}
                       onConfirm={() => { void deleteFolder(); }} onCancel={() => { setConfirm(null); }} />
      ) : null}
    </>
  );
}

