import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useDirectories, useMediaActions, useMediaFile, type MediaPatch, type MediaView } from '../../api/media';
import { useReplacer } from '../../api/upload';
import { ProblemBanner } from '../../components/ProblemBanner';
import { Markup, markupArg } from '../../components/Markup';
import { PageTitle } from '../../frame/PageTitle';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { useT } from '../../i18n/messages';
import { CropEditor } from './CropEditor';
import type { CropRect } from './crop';
import { FocalPicker, type Focal } from './FocalPicker';
import { isCroppable, isImage, libraryPath, splitTags, thumbnailUrl } from './shared';

const CONTROL_IDS: Record<string, string> = {
  name: 'mfedit_bean_name', description: 'mfedit_bean_description', altText: 'mfedit_bean_altText',
  tags: 'mfedit_bean_tagsAsString', copyrightText: 'mfedit_bean_copyrightText', directoryId: 'mfedit_bean_directoryId',
  focalX: 'focalPicker', focalY: 'focalPicker', file: 'mfedit_uploadedFile'
};

interface Draft {
  name: string;
  description: string;
  altText: string;
  tags: string;
  copyrightText: string;
  directoryId: string;
  focal: Focal | null;
}

const draftOf = (file: MediaView): Draft => ({
  name: file.name ?? '', description: file.description ?? '', altText: file.altText ?? '',
  tags: (file.tags ?? []).join(' '), copyrightText: file.copyrightText ?? '', directoryId: file.directory ?? '',
  focal: file.focalX !== undefined && file.focalY !== undefined ? { x: file.focalX, y: file.focalY } : null
});

/**
 * The PATCH for what changed. A text field the author blanked goes in `clear`, not as "": the
 * edit form never meant a blank alt text as "decorative" (docs/dev/media.md, Alt text).
 */
export function patchFor(file: MediaView, draft: Draft): MediaPatch {
  const before = draftOf(file);
  const patch: MediaPatch = {};
  const clear: string[] = [];
  if (draft.name !== before.name) {
    patch.name = draft.name;
  }
  for (const field of ['description', 'altText', 'copyrightText'] as const) {
    if (draft[field] !== before[field]) {
      if (draft[field] === '') {
        clear.push(field);
      } else {
        patch[field] = draft[field];
      }
    }
  }
  if (splitTags(draft.tags).join(' ') !== splitTags(before.tags).join(' ')) {
    patch.tags = splitTags(draft.tags);
  }
  if (draft.directoryId !== before.directoryId) {
    patch.directoryId = draft.directoryId;
  }
  if (draft.focal === null) {
    if (before.focal !== null) {
      clear.push('focalX');
    }
  } else if (draft.focal.x !== before.focal?.x || draft.focal.y !== before.focal.y) {
    patch.focalX = draft.focal.x;
    patch.focalY = draft.focal.y;
  }
  if (clear.length > 0) {
    patch.clear = clear;
  }
  return patch;
}

function CopyField({ id, value, label }: { id: string; value: string; label: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) {
      return undefined;
    }
    const timer = setTimeout(() => { setCopied(false); }, 1500);
    return () => { clearTimeout(timer); };
  }, [copied]);
  return (
    <>
      <input type="text" id={id} size={57} value={value} readOnly />
      <button className={`btn btn-secondary btn-sm clipbutton${copied ? ' copied' : ''}`} type="button"
              aria-label={t('generic.copyToClipboard')}
              onClick={() => { void navigator.clipboard.writeText(value).then(() => { setCopied(true); }, () => undefined); }}>
        {label}
      </button>
    </>
  );
}

/** MediaFileEdit.jsp: the file's fields, focal point and crop, as a screen of its own. */
export function MediaEdit() {
  const t = useT();
  const { handle = '', id = '' } = useParams();
  const file = useMediaFile(handle, id);
  if (file.error !== null) {
    return (
      <>
        <PageTitle>{t('mediaFile.edit.title')}</PageTitle>
        <ProblemBanner error={file.error} />
      </>
    );
  }
  if (file.data === undefined) {
    return <PageTitle>{t('mediaFile.edit.title')}</PageTitle>;
  }
  return <EditForm handle={handle} id={id} loaded={file.data} />;
}

function EditForm({ handle, id, loaded }: { handle: string; id: string; loaded: MediaView }) {
  const t = useT();
  const navigate = useNavigate();
  const dirs = useDirectories(handle);
  const actions = useMediaActions(handle);
  const replace = useReplacer();
  const [replacement, setReplacement] = useState<File | null>(null);
  const [draft, setDraft] = useState<Draft>(() => draftOf(loaded));
  const [error, setError] = useState<unknown>(null);
  const [cropError, setCropError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const [cropping, setCropping] = useState(false);
  const [cropped, setCropped] = useState<string[]>([]);
  const [version, setVersion] = useState(0);
  const fields = useFieldErrors(error, (field) => CONTROL_IDS[field] ?? field);

  const image = isImage(loaded);
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => { setDraft({ ...draft, [key]: value }); };
  const permalink = loaded.url ?? '';
  const back = libraryPath(handle, draft.directoryId === '' ? loaded.directory : draft.directoryId);

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      // The new bytes first: a refused replacement (size, quota, type) stops before anything else changes.
      if (replacement !== null) {
        await replace(handle, id, replacement);
      }
      const patch = patchFor(loaded, draft);
      if (Object.keys(patch).length > 0) {
        await actions.update(id, patch);
      }
      await actions.refresh();
      void navigate(libraryPath(handle, draft.directoryId), { state: { message: t('mediaFile.update.success') } });
    } catch (failure) {
      setError(failure);
      setSaving(false);
    }
  };

  const crop = async (rect: CropRect) => {
    setCropping(true);
    setCropError(null);
    setCropped([]);
    try {
      const updated = await actions.crop(id, { ...rect, expectedWidth: loaded.width ?? 0, expectedHeight: loaded.height ?? 0 });
      await actions.refresh();
      setDraft({ ...draft, focal: updated.focalX !== undefined && updated.focalY !== undefined
        ? { x: updated.focalX, y: updated.focalY } : null });
      setVersion((v) => v + 1);
      setCropped([t('mediaFileEdit.crop.success')]);
    } catch (failure) {
      setCropError(failure);
    }
    setCropping(false);
  };

  return (
    <>
      <PageTitle>{t('mediaFile.edit.title')}</PageTitle>
      <ProblemBanner error={error ?? cropError} messages={cropped}
                     errors={cropError === null ? [] : [t('mediaFileEdit.crop.error')]} />
      <p className="pagetip">{t('mediaFileEdit.pagetip')}</p>

      <form id="entry" className="form-stacked" onSubmit={(event) => { void save(event); }}>
        {image ? (
          <>
            <div className="row mb-3">
              <span className="col-form-label col-sm-3">{t('mediaFileEdit.thumbnail')}</span>
              <div className="col-sm-9">
                <a href={permalink} target="_blank" rel="noreferrer">
                  <img alt={t('mediaFileEdit.clickToView')} src={`${thumbnailUrl(loaded)}&v=${String(version)}`}
                       title={t('mediaFileEdit.clickToView')} />
                </a>
              </div>
            </div>
            <div className="row mb-3">
              <span className="col-form-label col-sm-3">{t('mediaFileEdit.focalPoint')}</span>
              <div className="col-sm-9">
                <FocalPicker src={permalink} value={draft.focal} onChange={(focal) => { set('focal', focal); }}
                             props={fields.props('focalX')} />
              </div>
            </div>
          </>
        ) : null}

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="mfedit_bean_name">{t('generic.name')}</label>
          <div className="col-sm-9">
            <input type="text" size={35} maxLength={255} {...fields.props('name', 'form-control')}
                   value={draft.name} onChange={(e) => { set('name', e.target.value); }} />
          </div>
        </div>

        <div className="row mb-3">
          <span className="col-form-label col-sm-3">{t('mediaFileEdit.fileInfo')}</span>
          <div className="col-sm-9">
            <Markup text={t('mediaFileEdit.fileTypeSize', markupArg(loaded.contentType ?? ''), String(loaded.length ?? 0))} />
            {image ? <> <Markup text={t('mediaFileEdit.fileDimensions', String(loaded.width ?? 0), String(loaded.height ?? 0))} /></> : null}
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="mfedit_uploadedFile">{t('mediaFileEdit.replaceFile')}</label>
          <div className="col-sm-9">
            <input type="file" {...fields.props('file', 'form-control')} aria-describedby="mfedit_uploadedFile_tip"
                   onChange={(e) => { setReplacement(e.target.files?.[0] ?? null); }} />
            <div id="mfedit_uploadedFile_tip" className="form-text">{t('mediaFileEdit.replaceFile.tip')}</div>
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-form-label col-sm-3" htmlFor="clip_text">{t('mediaFileEdit.url')}</label>
          <div className="col-sm-9"><CopyField id="clip_text" value={permalink} label={t('generic.copy')} /></div>
        </div>

        <div className="row mb-3">
          <label className="col-form-label col-sm-3" htmlFor="clip_shortcode">{t('mediaFileEdit.shortcode')}</label>
          <div className="col-sm-9">
            <CopyField id="clip_shortcode" value={`[image id="${id}"]`} label={t('generic.copy')} />
            <div className="form-text">{t('mediaFileEdit.shortcode.tip')}</div>
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="mfedit_bean_description">{t('generic.description')}</label>
          <div className="col-sm-9">
            <textarea rows={2} cols={50} {...fields.props('description', 'form-control')}
                      value={draft.description} onChange={(e) => { set('description', e.target.value); }} />
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="mfedit_bean_altText">{t('mediaFileEdit.altText')}</label>
          <div className="col-sm-9">
            <input type="text" maxLength={255} {...fields.props('altText', 'form-control')}
                   value={draft.altText} onChange={(e) => { set('altText', e.target.value); }} />
            <div className="form-text">{t('mediaFileEdit.altText.tip')}</div>
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="mfedit_bean_tagsAsString">{t('mediaFileEdit.tags')}</label>
          <div className="col-sm-9">
            <input type="text" size={30} maxLength={255} {...fields.props('tags', 'form-control')}
                   value={draft.tags} onChange={(e) => { set('tags', e.target.value); }} />
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="mfedit_bean_copyrightText">{t('mediaFileEdit.copyright')}</label>
          <div className="col-sm-9">
            <input type="text" size={30} maxLength={1023} {...fields.props('copyrightText', 'form-control')}
                   value={draft.copyrightText} onChange={(e) => { set('copyrightText', e.target.value); }} />
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="mfedit_bean_directoryId">{t('mediaFileEdit.directory')}</label>
          <div className="col-sm-9">
            <select {...fields.props('directoryId', 'form-select')} value={draft.directoryId}
                    onChange={(e) => { set('directoryId', e.target.value); }}>
              {(dirs.data ?? []).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </div>
        </div>

        <button type="submit" className="btn btn-primary" name="saveButton" disabled={saving}>{t('generic.save')}</button>
        {' '}
        <Link className="btn btn-secondary" to={back}>{t('generic.cancel')}</Link>
      </form>

      {isCroppable(loaded) ? (
        <CropEditor key={version} src={`${permalink}${version === 0 ? '' : `?v=${String(version)}`}`}
                    width={loaded.width ?? 0} height={loaded.height ?? 0} busy={cropping}
                    onApply={(rect) => { void crop(rect); }} />
      ) : null}
    </>
  );
}

