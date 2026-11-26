import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';
import { call } from '../../api/client';
import { useMeta, useWeblog } from '../../api/queries';
import { usePage, useStrandCandidates, type PageView, type PageWrite } from '../../api/pages';
import { ApiError } from '../../api/problem';
import { useBoot } from '../../context';
import type { SaveState } from '../../editor/EditorSurface';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useApi } from '../../context';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { DraftBar } from '../../session/DraftBar';
import { useDraftKeeper } from '../../session/useDraftKeeper';
import { MediaChooser } from '../media/MediaChooser';
import { EditorSurface } from '../../editor/EditorSurface';
import type { EditorHandle } from '../../editor/CodeMirrorField';
import { RevisionsPanel } from './RevisionsPanel';
import { StrandParts, type PartInfo } from './StrandParts';
import { Markup, markupArg } from '../../components/Markup';
import { isSiteSpace } from './urls';

/** Everything the form edits; its JSON is the unsaved-text snapshot and the dirty check. */
export interface FormState {
  title: string;
  slug: string;
  status: 'DRAFT' | 'PUBLISHED';
  text: string;
  showInNav: boolean;
  navOrder: string;
  strand: boolean;
  parts: string[];
  metaTitle: string;
  searchDescription: string;
  canonicalUrl: string;
  noindex: boolean;
  ogImageId: string;
}

const EMPTY: FormState = {
  title: '', slug: '', status: 'DRAFT', text: '', showInNav: false, navOrder: '0', strand: false, parts: [],
  metaTitle: '', searchDescription: '', canonicalUrl: '', noindex: false, ogImageId: ''
};

const dirty0 = (snapshot: string, page: PageView | undefined): boolean => snapshot !== JSON.stringify(formOf(page));

function formOf(page: PageView | undefined): FormState {
  return page === undefined ? EMPTY : {
    title: page.title ?? '', slug: page.slug ?? '', status: page.status === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT',
    text: page.text ?? '', showInNav: page.showInNav === true, navOrder: String(page.navOrder ?? 0),
    strand: page.strand === true, parts: (page.parts ?? []).map((p) => p.id ?? ''),
    metaTitle: page.metaTitle ?? '', searchDescription: page.searchDescription ?? '',
    canonicalUrl: page.canonicalUrl ?? '', noindex: page.noindex === true, ogImageId: page.ogImageId ?? ''
  };
}

function parseSnapshot(text: string): FormState | null {
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === 'object' && value !== null ? { ...EMPTY, ...(value as Partial<FormState>) } : null;
  } catch {
    return null;
  }
}

/** The request field names the server reports, mapped to the control that carries each. */
const CONTROL_IDS: Record<string, string> = {
  title: 'page_bean_title', slug: 'page_bean_slug', status: 'page_bean_status', navOrder: 'page_bean_navOrder',
  strand: 'page_bean_strand', entries: 'page_strand_parts', metaTitle: 'seo_metaTitle',
  searchDescription: 'seo_metaDescription', canonicalUrl: 'seo_canonicalUrl', noindex: 'seo_noindex',
  ogImageId: 'seo_ogImage_clear', text: 'edit_content'
};

const OPTIONAL = ['metaTitle', 'searchDescription', 'canonicalUrl', 'ogImageId'] as const;

/** What the page editor did, handed to the surrounding screen: the saved page and what to tell the author. */
interface Saved {
  page: PageView;
  messages: string[];
  errors: string[];
}

/** `value`, but only once it has stopped changing for `ms`. */
function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => { setSettled(value); }, ms);
    return () => { clearTimeout(timer); };
  }, [value, ms]);
  return settled;
}

/** The page editor (PageEdit.jsp) for `pages/new` and `pages/:id`. */
export function PageEditor() {
  const t = useT();
  const handle = useParams()['handle'] ?? '';
  const id = useParams()['id'];
  const pageQuery = usePage(handle, id);
  const [messages, setMessages] = useState<string[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const queries = useQueryClient();
  const missing = pageQuery.error instanceof ApiError && pageQuery.error.status === 404;
  const page = pageQuery.data;
  const subtitle = isSiteSpace(handle) ? t('weblogPagesForm.subtitle.site') : t('weblogPagesForm.subtitle', markupArg(handle));

  const onSaved = ({ page: saved, messages: said, errors: failed }: Saved) => {
    queries.setQueryData(['page', handle, saved.id], saved);
    void queries.invalidateQueries({ queryKey: ['pages', handle] });
    setMessages(said);
    setErrors(failed);
  };

  return (
    <>
      <PageTitle>{id === undefined ? t('pageEdit.title.new') : t('pageEdit.title')}</PageTitle>
      <p className="subtitle"><Markup text={subtitle} /></p>
      <ProblemBanner messages={messages} errors={missing ? [t('pageEdit.notFound'), ...errors] : errors}
                     {...(missing ? {} : { error: pageQuery.error })} />
      {id === undefined || page !== undefined ? (
        <PageForm key={`${id ?? 'new'}:${page?.contentHash ?? ''}:${page?.updateTime ?? ''}`}
                  handle={handle} page={page} onSaved={onSaved} />
      ) : null}
    </>
  );
}

function PageForm({ handle, page, onSaved }: {
  handle: string; page: PageView | undefined; onSaved: (saved: Saved) => void;
}) {
  const t = useT();
  const api = useApi();
  const navigate = useNavigate();
  const siteSpace = isSiteSpace(handle);
  // The API never serves the site space as a weblog; its pages live at the site root.
  const weblog = useWeblog(siteSpace ? undefined : handle).data;
  const siteUrl = useMeta().data?.siteUrl;
  // Where readers reach the blog (its own domain when it has one): the slug's prefix.
  const base = siteSpace ? (siteUrl === undefined ? undefined : `${siteUrl}/`) : weblog?.absoluteUrl;

  const [form, setForm] = useState<FormState>(() => formOf(page));
  const [seoOpen, setSeoOpen] = useState(false);
  const [chooser, setChooser] = useState<null | 'insert' | 'ogImage'>(null);
  const editor = useRef<EditorHandle>(null);
  const { contextPath } = useBoot();
  const [confirmingTrash, setConfirmingTrash] = useState(false);
  const [copied, setCopied] = useState(false);
  const snapshot = JSON.stringify(form);
  const saveState: SaveState = !dirty0(snapshot, page) ? (page === undefined ? 'notSaved' : 'saved') : 'unsaved';
  const keeper = useDraftKeeper(`${handle}:pageEdit:${page?.id ?? 'new'}`, snapshot);
  const dirty = snapshot !== JSON.stringify(formOf(page));
  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => { setForm((f) => ({ ...f, [key]: value })); };

  useEffect(() => {
    if (!dirty) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => { window.removeEventListener('beforeunload', warn); };
  }, [dirty]);

  const [strandSearch, setStrandSearch] = useState('');
  const searched = useDebounced(strandSearch, 250);
  const candidatesQuery = useStrandCandidates(handle, form.strand && !siteSpace, searched);
  // The posts chosen so far, kept by title: a later search replaces the choices on offer.
  const [chosen, setChosen] = useState<ReadonlyMap<string, PartInfo>>(new Map());
  const changeParts = (parts: string[]) => {
    const offered = new Map((candidatesQuery.data?.items ?? []).map((e): [string, PartInfo] =>
      [e.id ?? '', { id: e.id ?? '', title: e.title ?? '', status: e.status ?? 'DRAFT' }]));
    setChosen((previous) => new Map([...previous, ...parts.flatMap((id): [string, PartInfo][] => {
      const found = offered.get(id);
      return found === undefined ? [] : [[id, found]];
    })]));
    set('parts', parts);
  };
  const info = useMemo(() => {
    const map = new Map<string, PartInfo>(chosen);
    for (const p of page?.parts ?? []) {
      map.set(p.id ?? '', { id: p.id ?? '', title: p.title ?? '', status: p.status ?? 'DRAFT' });
    }
    for (const e of candidatesQuery.data?.items ?? []) {
      if (!map.has(e.id ?? '')) {
        map.set(e.id ?? '', { id: e.id ?? '', title: e.title ?? '', status: e.status ?? 'DRAFT' });
      }
    }
    return map;
  }, [page, chosen, candidatesQuery.data]);
  const candidates = (candidatesQuery.data?.items ?? [])
    .filter((e) => e.strand === undefined || e.strand === '')
    .map((e) => info.get(e.id ?? ''))
    .filter((c): c is PartInfo => c !== undefined && c.status !== 'TRASHED');

  const save = useMutation({
    mutationFn: async (): Promise<Saved> => {
      const storedStrand = page?.strand === true;
      const storedParts = (page?.parts ?? []).map((p) => p.id ?? '');
      const partsChanged = storedParts.join(',') !== form.parts.join(',');
      const wantsStrand = form.strand && !siteSpace;
      // A strand that is going away (or being reordered) is emptied/replaced while it still is one.
      if (page?.id !== undefined && storedStrand && partsChanged && (wantsStrand || form.parts.length === 0)) {
        await call(api.PUT('/v1/weblogs/{handle}/pages/{id}/parts', {
          params: { path: { handle, id: page.id } }, body: { entries: wantsStrand ? form.parts : [] }
        }));
      }
      const body = writeOf(form, page, siteSpace);
      const saved = page?.id === undefined
        ? await call(api.POST('/v1/weblogs/{handle}/pages', { params: { path: { handle } }, body }))
        : await call(api.PATCH('/v1/weblogs/{handle}/pages/{id}', { params: { path: { handle, id: page.id } }, body }));
      if (wantsStrand && !storedStrand && form.parts.length > 0) {
        try {
          return { page: await call(api.PUT('/v1/weblogs/{handle}/pages/{id}/parts', {
            params: { path: { handle, id: saved.id ?? '' } }, body: { entries: form.parts }
          })), messages: [t('pageEdit.saved')], errors: [] };
        } catch (error) {
          return { page: saved, messages: [], errors: error instanceof ApiError ? error.messages : [String(error)] };
        }
      }
      return { page: saved, messages: [t('pageEdit.saved')], errors: [] };
    },
    onSuccess: (result) => {
      keeper.clear();
      if (page?.id === undefined) {
        void navigate(`../${result.page.id ?? ''}`, { replace: true, relative: 'path' });
      }
      onSaved(result);
    }
  });
  const fields = useFieldErrors(save.error, (field) => CONTROL_IDS[field] ?? field);
  const field = (name: string, baseClass = '') => fields.props(name, baseClass);

  const trash = useMutation({
    mutationFn: () => call(api.DELETE('/v1/weblogs/{handle}/pages/{id}', { params: { path: { handle, id: page?.id ?? '' } } })),
    onSuccess: () => {
      keeper.clear();
      void navigate('..', { relative: 'path', state: { message: t('pages.trash.moved', page?.slug ?? '') } });
    },
    onSettled: () => { setConfirmingTrash(false); }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    save.mutate();
  };
  const published = page?.id !== undefined && page.status === 'PUBLISHED';
  // The server says where the page is served: a blog's front page is the blog root, the front door the site root.
  const publicUrl = published ? page.absoluteUrl : undefined;
  const copy = () => {
    if (publicUrl !== undefined) {
      void navigator.clipboard.writeText(publicUrl).then(() => {
        setCopied(true);
        setTimeout(() => { setCopied(false); }, 1500);
      });
    }
  };

  return (
    <>
      <ProblemBanner error={save.error ?? trash.error} />
      <div className="editor-grid">
        <form id="pageEditForm" className="form-stacked editor-form" onSubmit={submit} noValidate>
          <div className="editor-main">
            <DraftBar keeper={keeper} onRestore={(text) => {
              const restored = parseSnapshot(text);
              if (restored !== null) {
                setForm(restored);
              }
            }} />
            <textarea {...field('title', 'editor-title')} name="bean.title" rows={1} maxLength={255} autoFocus
                      placeholder={t('weblogEdit.title')} aria-label={t('weblogEdit.title')} value={form.title}
                      onChange={(e) => { set('title', e.target.value); }} />
            <p className="editor-permalink" role="status" aria-live="polite">
              <span className="editor-slug-prefix">{base ?? ''}</span>
              <input type="text" {...field('slug', 'editor-slug')} name="bean.slug" value={form.slug} maxLength={255}
                     aria-label={t('weblogPagesForm.slug')} onChange={(e) => { set('slug', e.target.value); }} />
              {published && publicUrl !== undefined ? (
                <>
                  {' · '}
                  <button className={`clipbutton editor-permalink-copy${copied ? ' copied' : ''}`} type="button"
                          aria-label={t('generic.copyToClipboard')} onClick={copy}>
                    {t('weblogEdit.copyPermalink')}
                  </button>
                </>
              ) : null}
            </p>
            {page?.frontDoor === true && page.absoluteUrl !== undefined ? (
              <p className="form-text editor-permalink-note">{t('pageEdit.frontDoorNote', page.absoluteUrl)}</p>
            ) : null}
            <EditorSurface editorRef={editor} handle={handle} entryId={page?.id} kind="page" contextPath={contextPath}
                           text={form.text} onTextChange={(text) => { set('text', text); }} saveState={saveState}
                           primaryActionKey="generic.save" saveActionKey="generic.save"
                           onSave={() => { save.mutate(); }} onPublish={() => { save.mutate(); }}
                           onChooseMedia={() => { setChooser('insert'); }} />
          </div>

          <div className="editor-rail">
            <div className="editor-box">
              <p className="rail-group-label">{t('weblogEdit.publishGroup')}</p>
              <label className="editor-field-label" htmlFor="page_bean_status">{t('weblogEdit.status')}</label>
              <select {...field('status', 'form-select')} name="bean.status" value={form.status}
                      onChange={(e) => { set('status', e.target.value === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT'); }}>
                <option value="DRAFT">{t('weblogEdit.draft')}</option>
                <option value="PUBLISHED">{t('weblogEdit.published')}</option>
              </select>
              <div className="editor-btnrow">
                <button type="submit" id="pageSaveButton" className="btn btn-primary" disabled={save.isPending}>
                  {t('generic.save')}
                </button>
              </div>
              {publicUrl === undefined ? null : (
                <a className="editor-preview-link" target="_blank" rel="noopener" href={publicUrl}>
                  {t('weblogEdit.fullPreviewMode')}
                </a>
              )}
            </div>

            <div className="editor-box">
              <p className="rail-group-label">{t('pageEdit.navigationGroup')}</p>
              <div className="form-check editor-quiet-check">
                <label className="form-check-label">
                  <input type="checkbox" className="form-check-input" name="bean.showInNav" checked={form.showInNav}
                         onChange={(e) => { set('showInNav', e.target.checked); }} />
                  {' '}{t('weblogPagesForm.showInNav')}
                </label>
              </div>
              <label className="editor-field-label" htmlFor="page_bean_navOrder">{t('weblogPagesForm.navOrder')}</label>
              <input type="number" {...field('navOrder', 'form-control')} name="bean.navOrder" min={0} value={form.navOrder}
                     onChange={(e) => { set('navOrder', e.target.value); }} />
              <div className="form-text">{t('weblogPagesForm.navOrder.tip')}</div>
            </div>

            {siteSpace ? null : (
              <div className="editor-box">
                <p className="rail-group-label">{t('pageEdit.parts')}</p>
                <div className="form-check editor-quiet-check">
                  <label className="form-check-label">
                    <input type="checkbox" {...field('strand', 'form-check-input')} name="bean.strand" checked={form.strand}
                           onChange={(e) => { set('strand', e.target.checked); }} />
                    {' '}{t('pageEdit.strand')}
                  </label>
                </div>
                <div className="form-text">{t('pageEdit.strand.help')}</div>
                {form.strand ? (
                  <StrandParts parts={form.parts} info={info} candidates={candidates}
                               search={strandSearch} onSearch={setStrandSearch}
                               invalid={fields.invalid('entries')} onChange={changeParts} />
                ) : null}
              </div>
            )}

            <div className="editor-box">
              <button type="button" className={`editor-drawer${seoOpen ? '' : ' collapsed'}`} aria-expanded={seoOpen}
                      aria-controls="collapseSeo" onClick={() => { setSeoOpen((o) => !o); }}>
                {t('weblogEdit.seoSettings')}
              </button>
              <div id="collapseSeo" className={`collapse${seoOpen ? ' show' : ''}`}>
                <div className="editor-drawer-body">
                  <div className="row mb-3">
                    <label className="col-sm-3 col-form-label" htmlFor="seo_metaTitle">{t('weblogEdit.metaTitle')}</label>
                    <div className="col-sm-9">
                      <input type="text" {...field('metaTitle', 'form-control')} name="bean.metaTitle" maxLength={255}
                             value={form.metaTitle} onChange={(e) => { set('metaTitle', e.target.value); }} />
                    </div>
                  </div>
                  <div className="row mb-3">
                    <label className="col-sm-3 col-form-label" htmlFor="seo_metaDescription">{t('weblogEdit.metaDescription')}</label>
                    <div className="col-sm-9">
                      <input type="text" {...field('searchDescription', 'form-control')} name="bean.searchDescription"
                             maxLength={255} value={form.searchDescription}
                             onChange={(e) => { set('searchDescription', e.target.value); }} />
                    </div>
                  </div>
                  <div className="row mb-3" role="group" aria-labelledby="seo_ogImage_label">
                    <span className="col-sm-3 col-form-label" id="seo_ogImage_label">{t('weblogEdit.ogImage')}</span>
                    <div className="col-sm-9">
                      {form.ogImageId === '' ? null : (
                        <div className="mb-2">
                          <OgImagePreview handle={handle} imageId={form.ogImageId} />
                        </div>
                      )}
                      <button type="button" className="btn btn-secondary btn-sm" id="seo_ogImage_choose"
                              onClick={() => { setChooser('ogImage'); }}>
                        {t('weblogEdit.chooseImage')}
                      </button>
                      {form.ogImageId === '' ? null : (
                        <button type="button" {...field('ogImageId', 'btn btn-outline-danger btn-sm')}
                                onClick={() => { set('ogImageId', ''); }}>
                          {t('weblogEdit.clearImage')}
                        </button>
                      )}
                    </div>
                  </div>
                  <div className="row mb-3">
                    <label className="col-sm-3 col-form-label" htmlFor="seo_canonicalUrl">{t('weblogEdit.canonicalUrl')}</label>
                    <div className="col-sm-9">
                      <input type="text" {...field('canonicalUrl', 'form-control')} name="bean.canonicalUrl" maxLength={255}
                             value={form.canonicalUrl} onChange={(e) => { set('canonicalUrl', e.target.value); }} />
                    </div>
                  </div>
                  <div className="row mb-3">
                    <div className="offset-sm-3 col-sm-9">
                      <div className="form-check">
                        <label className="form-check-label">
                          <input type="checkbox" {...field('noindex', 'form-check-input')} name="bean.noindex"
                                 checked={form.noindex} onChange={(e) => { set('noindex', e.target.checked); }} />
                          {' '}{t('weblogEdit.noindex')}
                        </label>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {page?.id === undefined ? null : (
              <button type="button" id="deletePageButton" className="delete-link"
                      aria-label={`${t('pages.trash.move')}: ${page.title ?? ''}`}
                      onClick={() => { setConfirmingTrash(true); }}>
                {t('pages.trash.move')}
              </button>
            )}
          </div>
        </form>

        {page?.id === undefined ? null : (
          <RevisionsPanel handle={handle} page={page}
                          onRestored={(restored) => { onSaved({ page: restored, messages: [t('pageEdit.revisionRestored')], errors: [] }); }} />
        )}
      </div>

      {confirmingTrash && page !== undefined ? (
        <ConfirmDialog title={`${t('pages.trash.move')}: ${page.title ?? ''}`} confirmLabel={t('pages.trash.move')}
                       onConfirm={() => { trash.mutate(); }} onCancel={() => { setConfirmingTrash(false); }}>
          {(page.parts?.length ?? 0) > 0 ? <p>{t('pages.deleteStrandConfirm', page.parts?.length ?? 0)}</p> : null}
        </ConfirmDialog>
      ) : null}
      {chooser === null ? null : (
        <MediaChooser handle={handle} imagesOnly={chooser === 'ogImage'}
                      onSelect={(media) => {
                        const target = chooser;
                        setChooser(null);
                        if (target === 'ogImage') {
                          set('ogImageId', media.id);
                        } else if (media.isImage) {
                          editor.current?.insert(`[image id="${media.id}"]`);
                        } else {
                          editor.current?.insert(`[${media.name.replace(/[[\]]/g, '')}](${media.url})`);
                        }
                      }}
                      onCancel={() => { setChooser(null); }} />
      )}
    </>
  );
}

/** The share image, from its media view's own address. */
function OgImagePreview({ handle, imageId }: { handle: string; imageId: string }) {
  const api = useApi();
  const media = useQuery({
    queryKey: ['media', handle, imageId],
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/media/{id}', { params: { path: { handle, id: imageId } } }))
  });
  return media.data?.url === undefined ? null
    : <img id="seo_ogImage_preview" src={media.data.url} alt="" style={{ maxHeight: '120px' }} />;
}

/** The PageWrite for a save: emptied optional fields are cleared on an update, left out on a create. */
export function writeOf(form: FormState, page: PageView | undefined, siteSpace: boolean): PageWrite {
  const navOrder = Number.parseInt(form.navOrder, 10);
  const body: PageWrite = {
    title: form.title,
    text: form.text,
    status: form.status,
    showInNav: form.showInNav,
    noindex: form.noindex,
    ...(form.slug.trim() === '' ? {} : { slug: form.slug.trim() }),
    ...(Number.isNaN(navOrder) ? {} : { navOrder }),
    ...(siteSpace ? {} : { strand: form.strand }),
    ...(page?.contentHash === undefined ? {} : { expectedContentHash: page.contentHash })
  };
  const clear: string[] = [];
  for (const name of OPTIONAL) {
    if (form[name].trim() !== '') {
      body[name] = form[name].trim();
    } else if (page?.id !== undefined && (page[name] ?? '') !== '') {
      clear.push(name);
    }
  }
  return clear.length === 0 ? body : { ...body, clear };
}
