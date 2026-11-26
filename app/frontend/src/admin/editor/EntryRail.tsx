import { useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { call } from '../api/client';
import type { FieldErrors } from '../forms/useFieldErrors';
import { DateTime } from '../components/DateTime';
import { StatusPill } from '../components/StatusPill';
import { useApi } from '../context';
import { useT } from '../i18n/messages';
import type { EntryForm, EntryView } from './entryForm';

/** SeoLimits.TITLE_MAX / DESCRIPTION_MAX / DESCRIPTION_MIN: not on any API route yet, so mirrored here (a test pins the Java). */
export const SEO_TITLE_MAX = 60;
export const SEO_DESCRIPTION_MAX = 160;
export const SEO_DESCRIPTION_MIN = 50;

/** The request fields (and the controls they name) that live inside the SEO drawer: an error on one opens it. */
export const SEO_FIELDS = ['metaTitle', 'searchDescription', 'canonicalUrl', 'noindex', 'commentsClosed', 'jsonLdType',
  'geoLatitude', 'geoLongitude', 'eventStart', 'eventEnd', 'eventLocation', 'featuredImageId', 'ogImageId'] as const;

/** Request field -> control id, the ids the browser tests know (do not rename). */
export const CONTROL_IDS: Readonly<Record<string, string>> = {
  title: 'entry_bean_title', pubTime: 'entry_bean_pubTimeLocal', pinned: 'entry_bean_pinned',
  strand: 'entry_bean_strandPageId', category: 'entry_bean_categoryId', tags: 'entry_bean_tagsAsString',
  metaTitle: 'seo_metaTitle', searchDescription: 'seo_metaDescription', canonicalUrl: 'seo_canonicalUrl',
  noindex: 'seo_noindex', commentsClosed: 'comments_closed', jsonLdType: 'seo_jsonldType',
  geoLatitude: 'seo_geoLatitude', geoLongitude: 'seo_geoLongitude', eventStart: 'seo_eventStart',
  eventEnd: 'seo_eventEnd', eventLocation: 'seo_eventLocation', featuredImageId: 'seo_featuredImageId',
  ogImageId: 'seo_ogImageId', text: 'edit_content', pinnedToMain: 'entry_bean_pinnedToMain'
};

export type Setter = <K extends keyof EntryForm>(key: K, value: EntryForm[K]) => void;

const chars = (s: string) => Array.from(s).length;

interface PublishProps {
  form: EntryForm;
  set: Setter;
  errors: FieldErrors;
  /** PUBLISHED, DRAFT, PENDING, SCHEDULED; UNSAVED for an entry that has never been saved. */
  status: string;
  updateTime: string | undefined;
  canPublish: boolean;
  siteAdmin: boolean;
  strands: ReadonlyArray<{ id: string; title: string; trashed: boolean }>;
  saving: boolean;
  tz: string | undefined;
  /** The saved entry's id: only then is there something to preview in full. */
  entryId: string | undefined;
  onSave: (intent: 'draft' | 'publish') => void;
  onFullPreview: () => void;
}

/** Publish rail box: status pill, publish time, the submit buttons, pin, strand and pinned-to-main. */
export function PublishBox(p: PublishProps) {
  const t = useT();
  const live = p.canPublish && p.status === 'PUBLISHED';
  const publishKey = live ? 'weblogEdit.update' : p.canPublish ? 'weblogEdit.post' : 'weblogEdit.submitForReview';
  return (
    <div className="editor-box">
      <p className="rail-group-label">{t('weblogEdit.publishGroup')}</p>
      <div className="editor-statusrow">
        <StatusPill status={p.status} />
        {p.updateTime === undefined ? null : (
          <span className="editor-when" title={t('weblogEdit.updateTime')}><DateTime value={p.updateTime} /></span>
        )}
      </div>
      <label className="editor-field-label" htmlFor="entry_bean_pubTimeLocal">{t('weblogEdit.pubTime')}</label>
      <input type="datetime-local" {...p.errors.props('pubTime', 'editor-dt')} name="bean.pubTimeLocal"
             value={p.form.pubTime} onChange={(e) => { p.set('pubTime', e.target.value); }} />
      <div className="editor-tz">{p.tz}</div>
      <div className="editor-btnrow">
        <button type="button" className="btn btn-primary" disabled={p.saving} {...(live ? { 'data-save-shortcut': true } : {})}
                onClick={() => { p.onSave('publish'); }}>{t(publishKey)}</button>
        <button type="button" className="btn btn-secondary" disabled={p.saving} {...(live ? {} : { 'data-save-shortcut': true })}
                onClick={() => { p.onSave('draft'); }}>{t(live ? 'weblogEdit.unpublish' : 'weblogEdit.save')}</button>
      </div>
      {p.entryId === undefined ? null : (
        <button type="button" name="fullPreview" className="editor-preview-link" onClick={p.onFullPreview}>
          {t('weblogEdit.fullPreviewMode')}
        </button>
      )}
      {p.canPublish ? (
        <>
          <div className="form-check editor-quiet-check">
            <label className="form-check-label">
              <input type="checkbox" {...p.errors.props('pinned', 'form-check-input')}
                     name="bean.pinned" checked={p.form.pinned}
                     onChange={(e) => { p.set('pinned', e.target.checked); }} /> {t('weblogEdit.pinned')}
            </label>
          </div>
          <label className="editor-field-label" htmlFor="entry_bean_strandPageId">{t('weblogEdit.strand')}</label>
          <select {...p.errors.props('strand', 'form-select')} name="bean.strandPageId" value={p.form.strand}
                  onChange={(e) => { p.set('strand', e.target.value); }}>
            <option value="">{t('weblogEdit.strand.none')}</option>
            {p.strands.map((s) => (
              <option key={s.id} value={s.id}>{s.title}{s.trashed ? ` ${t('strand.inTrash')}` : ''}</option>
            ))}
          </select>
        </>
      ) : null}
      {p.siteAdmin ? (
        <div className="form-check editor-quiet-check">
          <label className="form-check-label">
            <input type="checkbox" {...p.errors.props('pinnedToMain', 'form-check-input')}
                   name="bean.pinnedToMain" checked={p.form.pinnedToMain}
                   onChange={(e) => { p.set('pinnedToMain', e.target.checked); }} /> {t('weblogEdit.pinnedToMain')}
          </label>
        </div>
      ) : null}
    </div>
  );
}

/** Organize box: category and tags (the locale rides along untouched in the form, as the JSP's hidden input did). */
export function OrganizeBox({ form, set, errors, categories }: {
  form: EntryForm; set: Setter; errors: FieldErrors; categories: readonly string[];
}) {
  const t = useT();
  return (
    <div className="editor-box">
      <p className="rail-group-label">{t('weblogEdit.organizeGroup')}</p>
      <label className="editor-field-label" htmlFor="entry_bean_categoryId">{t('weblogEdit.category')}</label>
      <select {...errors.props('category', 'form-select')} name="bean.categoryId" value={form.category}
              onChange={(e) => { set('category', e.target.value); }}>
        {categories.map((name) => <option key={name} value={name}>{name}</option>)}
      </select>
      <label className="editor-field-label" htmlFor="entry_bean_tagsAsString">{t('weblogEdit.tags')}</label>
      <input type="text" {...errors.props('tags', 'form-control')} name="bean.tagsAsString" maxLength={255}
             value={form.tags} onChange={(e) => { set('tags', e.target.value); }} />
    </div>
  );
}

function Field({ id, label, children }: { id?: string; label: string; children: ReactNode }) {
  return (
    <>
      <label className="editor-field-label" {...(id === undefined ? {} : { htmlFor: id })}>{label}</label>
      {children}
    </>
  );
}

/** An image picker row of the SEO drawer: thumbnail, Choose image, Remove image. */
function ImagePicker({ target, label, id, handle, pickedUrl, errors, onChoose, onClear }: {
  target: 'featuredImage' | 'ogImage'; label: string; id: string; handle: string; pickedUrl: string | undefined;
  errors: FieldErrors; onChoose: () => void; onClear: () => void;
}) {
  const t = useT();
  const api = useApi();
  const media = useQuery({
    queryKey: ['media', handle, id],
    enabled: id !== '' && pickedUrl === undefined,
    queryFn: () => call(api.GET('/v1/weblogs/{handle}/media/{id}', { params: { path: { handle, id } } }))
  });
  const src = id === '' ? undefined : pickedUrl ?? (media.data?.url === undefined ? undefined : `${media.data.url}?t=true`);
  const field = `${target}Id` as 'featuredImageId' | 'ogImageId';
  return (
    <div className="editor-seo-group" role="group" aria-labelledby={`seo_${target}_label`}>
      <span className="editor-field-label" id={`seo_${target}_label`}>{label}</span>
      <input type="hidden" {...errors.props(field)} name={`bean.${field}`} value={id} readOnly />
      <div className="mb-2">
        <img id={`seo_${target}_preview`} src={src} alt="" style={{ maxHeight: '120px', ...(src === undefined ? { display: 'none' } : {}) }} />
      </div>
      <button type="button" className="btn btn-secondary btn-sm" onClick={onChoose}>{t('weblogEdit.chooseImage')}</button>
      {id === '' ? null : (
        <button type="button" className="btn btn-outline-danger btn-sm" id={`seo_${target}_clear`} onClick={onClear}>
          {t('weblogEdit.clearImage')}
        </button>
      )}
    </div>
  );
}

interface SeoProps {
  form: EntryForm;
  set: Setter;
  errors: FieldErrors;
  handle: string;
  weblogName: string;
  /** The entry's permalink, or what it will be (absolute weblog URL + a placeholder slug). */
  snippetUrl: string;
  entry: EntryView | undefined;
  commentsEnabled: boolean;
  canModerate: boolean;
  commentCounts: { published: number; pending: number } | undefined;
  commentsHref: string;
  forcedOpen: boolean;
  pickedUrls: Readonly<Partial<Record<'featuredImage' | 'ogImage', string>>>;
  onPick: (target: 'featuredImage' | 'ogImage') => void;
  onClearPick: (target: 'featuredImage' | 'ogImage') => void;
}

/** SEO and sharing: the whole card behind one quiet drawer. Field ids are a browser-test contract. */
export function SeoDrawer(p: SeoProps) {
  const t = useT();
  const [opened, setOpened] = useState(false);
  const open = opened || p.forcedOpen;
  const { form } = p;
  const metaTitle = form.metaTitle.trim();
  const entryTitle = form.title.trim();
  // Measured the way themes print it: the meta title when set, else the entry title, a dash and the weblog name.
  const titleLen = metaTitle !== '' ? chars(metaTitle) : entryTitle !== '' ? chars(`${entryTitle} — ${p.weblogName}`) : 0;
  const descLen = chars(form.searchDescription.trim());
  return (
    <div className="editor-box">
      <button type="button" className={`editor-drawer${open ? '' : ' collapsed'}`} aria-expanded={open}
              aria-controls="collapseSeo" onClick={() => { setOpened((o) => !o); }}>
        {t('weblogEdit.seoSettings')}
      </button>
      <div id="collapseSeo" className={`collapse${open ? ' show' : ''}`}>
        <div className="editor-drawer-body">
          <Field id="seo_metaTitle" label={t('weblogEdit.metaTitle')}>
            <input type="text" {...p.errors.props('metaTitle', 'form-control')} name="bean.metaTitle" maxLength={255}
                   aria-describedby="seo_metaTitle_count" value={form.metaTitle}
                   onChange={(e) => { p.set('metaTitle', e.target.value); }} />
          </Field>
          <div id="seo_metaTitle_count" className="form-text seo-count" aria-live="polite">
            <span className="seo-count-n">{titleLen}</span> / {SEO_TITLE_MAX} {t('weblogEdit.seoCount.title')}
            {titleLen > SEO_TITLE_MAX ? <> <span className="seo-count-over text-warning">{t('weblogEdit.seoCount.over')}</span></> : null}
          </div>
          <Field id="seo_metaDescription" label={t('weblogEdit.metaDescription')}>
            <input type="text" {...p.errors.props('searchDescription', 'form-control')} id="seo_metaDescription"
                   name="bean.searchDescription" maxLength={255} aria-describedby="seo_metaDescription_count"
                   value={form.searchDescription} onChange={(e) => { p.set('searchDescription', e.target.value); }} />
          </Field>
          <div id="seo_metaDescription_count" className="form-text seo-count" aria-live="polite">
            <span className="seo-count-n">{descLen}</span> / {SEO_DESCRIPTION_MAX} {t('weblogEdit.seoCount.description')}
            {descLen > SEO_DESCRIPTION_MAX ? <> <span className="seo-count-over text-warning">{t('weblogEdit.seoCount.over')}</span></> : null}
            {descLen !== 0 && descLen < SEO_DESCRIPTION_MIN ? <> <span className="seo-count-short text-warning">{t('weblogEdit.seoCount.short')}</span></> : null}
          </div>
          <div className="editor-seo-group">
            <span className="editor-field-label">{t('weblogEdit.snippetPreview')}</span>
            <div id="seo_snippet_preview" className="border rounded p-2 bg-body">
              <div id="seo_snippet_title" className="seo-snippet-title">{metaTitle !== '' ? metaTitle : entryTitle}</div>
              <div id="seo_snippet_url" className="seo-snippet-url">{p.snippetUrl}</div>
              <div id="seo_snippet_description" className="seo-snippet-description">{form.searchDescription}</div>
            </div>
          </div>
          <ImagePicker target="featuredImage" label={t('weblogEdit.featuredImage')} id={form.featuredImageId}
                       handle={p.handle} pickedUrl={p.pickedUrls.featuredImage} errors={p.errors}
                       onChoose={() => { p.onPick('featuredImage'); }} onClear={() => { p.onClearPick('featuredImage'); }} />
          <ImagePicker target="ogImage" label={t('weblogEdit.ogImage')} id={form.ogImageId}
                       handle={p.handle} pickedUrl={p.pickedUrls.ogImage} errors={p.errors}
                       onChoose={() => { p.onPick('ogImage'); }} onClear={() => { p.onClearPick('ogImage'); }} />
          <Field id="seo_canonicalUrl" label={t('weblogEdit.canonicalUrl')}>
            <input type="text" {...p.errors.props('canonicalUrl', 'form-control')} name="bean.canonicalUrl" maxLength={255}
                   value={form.canonicalUrl} onChange={(e) => { p.set('canonicalUrl', e.target.value); }} />
          </Field>
          <div className="editor-seo-check">
            <div className="form-check">
              <label className="form-check-label">
                <input type="checkbox" {...p.errors.props('noindex', 'form-check-input')} name="bean.noindex"
                       checked={form.noindex} onChange={(e) => { p.set('noindex', e.target.checked); }} /> {t('weblogEdit.noindex')}
              </label>
            </div>
          </div>
          <div className="editor-seo-check">
            <div className="form-check">
              <label className="form-check-label">
                <input type="checkbox" {...p.errors.props('commentsClosed', 'form-check-input')} name="bean.commentsClosed"
                       checked={form.commentsClosed} onChange={(e) => { p.set('commentsClosed', e.target.checked); }} /> {t('weblogEdit.commentsClosed')}
              </label>
            </div>
            {p.commentsEnabled && p.canModerate && p.entry?.id !== undefined && p.commentCounts !== undefined ? (
              <a className="small" id="entryCommentCounts" href={p.commentsHref}>
                {t('weblogEdit.commentCounts', p.commentCounts.published, p.commentCounts.pending)}
              </a>
            ) : null}
          </div>
          <Field id="seo_jsonldType" label={t('weblogEdit.jsonLdType')}>
            <select {...p.errors.props('jsonLdType', 'form-select')} name="bean.jsonLdType" value={form.jsonLdType}
                    onChange={(e) => { p.set('jsonLdType', e.target.value); }}>
              <option value="BLOG_POSTING">{t('weblogEdit.jsonLdType.blogPosting')}</option>
              <option value="TOURIST_ATTRACTION">{t('weblogEdit.jsonLdType.touristAttraction')}</option>
              <option value="TOURIST_TRIP">{t('weblogEdit.jsonLdType.touristTrip')}</option>
              <option value="EVENT">{t('weblogEdit.jsonLdType.event')}</option>
              <option value="FAQ_PAGE">{t('weblogEdit.jsonLdType.faqPage')}</option>
              <option value="TECH_ARTICLE">{t('weblogEdit.jsonLdType.techArticle')}</option>
            </select>
          </Field>
          <div className="form-text">{t('weblogEdit.jsonLdType.tip')}</div>
          <div id="seo_row_geo">
            <Field id="seo_geoLatitude" label={t('weblogEdit.geoLatitude')}>
              <input type="number" step="any" min={-90} max={90} {...p.errors.props('geoLatitude', 'form-control')}
                     name="bean.geoLatitude" value={form.geoLatitude}
                     onChange={(e) => { p.set('geoLatitude', e.target.value); }} />
            </Field>
            <Field id="seo_geoLongitude" label={t('weblogEdit.geoLongitude')}>
              <input type="number" step="any" min={-180} max={180} {...p.errors.props('geoLongitude', 'form-control')}
                     name="bean.geoLongitude" value={form.geoLongitude}
                     onChange={(e) => { p.set('geoLongitude', e.target.value); }} />
            </Field>
            <div className="form-text">{t('weblogEdit.geoCoordinates.tip')}</div>
          </div>
          {/* The event rows stay in the DOM when hidden, so switching type never drops what the author typed. */}
          <div id="seo_row_event" style={form.jsonLdType === 'EVENT' ? {} : { display: 'none' }}>
            <Field id="seo_eventStart" label={t('weblogEdit.eventStart')}>
              <input type="datetime-local" {...p.errors.props('eventStart', 'form-control')} name="bean.eventStartLocal"
                     value={form.eventStart} onChange={(e) => { p.set('eventStart', e.target.value); }} />
            </Field>
            <div className="form-text">{t('weblogEdit.eventStart.tip')}</div>
            <Field id="seo_eventEnd" label={t('weblogEdit.eventEnd')}>
              <input type="datetime-local" {...p.errors.props('eventEnd', 'form-control')} name="bean.eventEndLocal"
                     value={form.eventEnd} onChange={(e) => { p.set('eventEnd', e.target.value); }} />
            </Field>
            <Field id="seo_eventLocation" label={t('weblogEdit.eventLocation')}>
              <input type="text" {...p.errors.props('eventLocation', 'form-control')} name="bean.eventLocation" maxLength={255}
                     value={form.eventLocation} onChange={(e) => { p.set('eventLocation', e.target.value); }} />
            </Field>
          </div>
        </div>
      </div>
    </div>
  );
}
