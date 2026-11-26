import { useRef, useState, type DragEvent, type ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { runBulk } from '../../api/bulk';
import { useDirectories, useMediaActions, type MediaView } from '../../api/media';
import { ApiError } from '../../api/problem';
import { useUploader } from '../../api/upload';
import { ProblemBanner } from '../../components/ProblemBanner';
import { PageTitle } from '../../frame/PageTitle';
import { useBoot } from '../../context';
import { useT } from '../../i18n/messages';
import { formatSize, isImage, libraryPath, splitTags, thumbnailUrl } from './shared';

interface Row {
  file: File;
  state: 'waiting' | 'uploading' | 'done' | 'failed';
  loaded: number;
}

/** A readonly URL with a Copy button, the MediaFileAddSuccess idiom. */
function LinkField({ id, value }: { id: string; value: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  return (
    <p>
      <input type="text" className="form-control-plaintext d-inline-block w-auto" id={id} size={57} value={value} readOnly
             aria-label={t('mediaFileSuccess.link')} />
      <button className={`btn btn-secondary btn-sm clipbutton${copied ? ' copied' : ''}`} type="button"
              aria-label={t('generic.copyToClipboard')}
              onClick={() => {
                void navigator.clipboard.writeText(value).then(() => {
                  setCopied(true);
                  setTimeout(() => { setCopied(false); }, 1500);
                }, () => undefined);
              }}>
        {t('generic.copy')}
      </button>
    </p>
  );
}

/** The success half: MediaFileAddSuccess.jsp. */
function Uploaded({ handle, dir, created, onMore }: { handle: string; dir: string; created: MediaView[]; onMore: () => void }) {
  const t = useT();
  const boot = useBoot();
  const images = created.filter(isImage);
  const others = created.filter((f) => !isImage(f));
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set());
  const [enclosure, setEnclosure] = useState('');
  const ready = picked.size > 0 || enclosure !== '';
  const card = (children: ReactNode, key?: string) => (
    <div className="card" key={key}><div className="card-body"><div className="row">{children}</div></div></div>
  );
  const facts = (f: MediaView, withPixels: boolean) => (
    <>
      <p><b>{t('mediaFileSuccess.name')}</b> {f.name}</p>
      <p>
        <b>{t('mediaFileSuccess.type')}</b> {f.contentType},{' '}
        <b>{t('mediaFileSuccess.size')}</b> {f.length} {t('mediaFileSuccess.bytes')}
        {withPixels ? <>, {f.width} x {f.height} {t('mediaFileSuccess.pixels')}</> : null}
      </p>
      <p><b>{t('mediaFileSuccess.link')}</b></p>
    </>
  );

  return (
    <>
      <p className="subtitle">{t('mediaFileSuccess.subtitle')}</p>
      <p className="pagetip">{t('mediaFileSuccess.pageTip')}</p>
      <form id="entry" method="post" action={`${boot.contextPath}/pressroom-ui/authoring/entryAddWithMediaFile.rol`}>
        <input type="hidden" name="weblog" value={handle} />
        <input type="hidden" name="bean.enclosureURL" id="enclosureURL" value={enclosure} />
        {[...picked].map((id) => <input key={id} type="hidden" name="selectedImages" value={id} />)}
        <input type="hidden" name="_csrf" value={boot.csrf.token} />

        {images.length > 0 ? (
          <>
            <h2 className="section-head">{t('mediaFileSuccess.selectImagesTitle')}</h2>
            <p>{t('mediaFileSuccess.selectImages')}</p>
            {images.map((f) => card(
              <>
                <div className="col-md-1">
                  <input type="checkbox" value={f.id} aria-label={f.name} checked={picked.has(f.id ?? '')}
                         onChange={() => {
                           const next = new Set(picked);
                           if (!next.delete(f.id ?? '')) {
                             next.add(f.id ?? '');
                           }
                           setPicked(next);
                         }} />
                </div>
                <div className="col-md-2"><img className="mediaFileImage" src={thumbnailUrl(f)} alt="" /></div>
                <div className="col-md-9">{facts(f, true)}<LinkField id={`clip_image_${f.id ?? ''}`} value={f.url ?? ''} /></div>
              </>, f.id))}
          </>
        ) : null}

        {others.length > 0 ? (
          <>
            <h2 className="section-head">{t('mediaFileSuccess.selectEnclosureTitle')}</h2>
            <p>{t('mediaFileSuccess.selectEnclosure')}</p>
            {others.map((f) => card(
              <>
                <div className="col-md-1">
                  <input type="radio" name="enclosure" aria-label={f.name} checked={enclosure === f.url}
                         onChange={() => { setEnclosure(f.url ?? ''); }} />
                </div>
                <div className="col-md-11">{facts(f, false)}<LinkField id={`clip_file_${f.id ?? ''}`} value={f.url ?? ''} /></div>
              </>, f.id))}
            {card(
              <>
                <div className="col-md-1">
                  <input type="radio" name="enclosure" aria-label={t('mediaFileSuccess.noEnclosure')} checked={enclosure === ''}
                         onChange={() => { setEnclosure(''); }} />
                </div>
                <div className="col-md-10">{t('mediaFileSuccess.noEnclosure')}</div>
              </>)}
          </>
        ) : null}

        <div>
          <button type="submit" id="createPostButton" className="btn btn-primary" disabled={!ready}>
            {t('mediaFileSuccess.createPost')}
          </button>
          {' '}
          <button type="button" className="btn btn-secondary" onClick={onMore}>{t('mediaFileSuccess.uploadMore')}</button>
          {' '}
          <Link to={libraryPath(handle, dir)} className="btn btn-secondary">{t('generic.done')}</Link>
        </div>
      </form>
    </>
  );
}

/**
 * MediaFileAdd.jsp and its success page: choose or drop files, optional shared details, upload
 * each file in its own request (so each has its own progress and its own refusal), then offer
 * the entry-from-these step. Description, copyright and tags apply to every file, as before.
 */
export function MediaUpload() {
  const t = useT();
  const handle = useParams()['handle'] ?? '';
  const [params] = useSearchParams();
  const dirs = useDirectories(handle);
  const upload = useUploader();
  const actions = useMediaActions(handle);
  const input = useRef<HTMLInputElement>(null);

  const directories = dirs.data ?? [];
  const [chosenDir, setChosenDir] = useState<string | null>(null);
  const dir = chosenDir ?? params.get('dir') ?? directories.find((d) => d.name === 'default')?.id ?? '';
  const [files, setFiles] = useState<File[]>([]);
  const [rows, setRows] = useState<Row[]>([]);
  const [dragging, setDragging] = useState(false);
  const depth = useRef(0);
  const [description, setDescription] = useState('');
  const [copyright, setCopyright] = useState('');
  const [tags, setTags] = useState('');
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [created, setCreated] = useState<MediaView[] | null>(null);

  const choose = (list: FileList | null) => {
    setFiles(list === null ? [] : [...list]);
    setRows([]);
  };
  const total = files.reduce((sum, f) => sum + f.size, 0);
  const summary = (files.length === 1 ? t('mediaFileAdd.chosenSummary.one') : t('mediaFileAdd.chosenSummary.many'))
    .replace('#COUNT#', String(files.length)).replace('#SIZE#', formatSize(total));

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    depth.current = 0;
    setDragging(false);
    choose(event.dataTransfer.files);
  };

  const send = async () => {
    const state: Row[] = files.map((file) => ({ file, state: 'waiting', loaded: 0 }));
    const update = (index: number, change: Partial<Row>) => {
      state[index] = { ...(state[index] as Row), ...change };
      setRows([...state]);
    };
    setBusy(true);
    setErrors([]);
    setProblem(null);
    setRows(state);
    const landed: MediaView[] = [];
    const failed: string[] = [];
    let refused: unknown = null;
    await runBulk(files.map((file, index) => ({ file, index })), async ({ file, index }) => {
      update(index, { state: 'uploading' });
      try {
        const answer = await upload(handle, dir, file, (loaded) => { update(index, { loaded }); });
        const result = answer.results?.[0];
        if (result?.status === 'created' && result.file !== undefined) {
          landed.push(result.file);
          update(index, { state: 'done' });
        } else {
          failed.push(`${t('mediaFileAdd.errorUploading', file.name)}${result?.detail === undefined ? '' : `: ${result.detail}`}`);
          update(index, { state: 'failed' });
        }
      } catch (error) {
        refused = error instanceof ApiError ? error : null;
        failed.push(t('mediaFileAdd.errorUploading', file.name));
        update(index, { state: 'failed' });
      }
    }, 2);

    const shared = { description: description.trim(), copyrightText: copyright.trim(), tags: splitTags(tags) };
    if (shared.description !== '' || shared.copyrightText !== '' || shared.tags.length > 0) {
      const body = {
        ...(shared.description === '' ? {} : { description: shared.description }),
        ...(shared.copyrightText === '' ? {} : { copyrightText: shared.copyrightText }),
        ...(shared.tags.length === 0 ? {} : { tags: shared.tags })
      };
      const patched = await runBulk(landed, (file) => actions.update(file.id ?? '', body));
      for (const miss of patched.filter((r) => r.error !== undefined)) {
        failed.push(`${miss.item.name ?? ''}${miss.error instanceof ApiError ? `: ${miss.error.detail}` : ''}`);
      }
    }
    await actions.refresh();
    setBusy(false);
    setErrors(failed);
    setProblem(refused);
    if (landed.length > 0) {
      setCreated(landed);
    }
  };

  if (created !== null) {
    return (
      <>
        <PageTitle>{t('mediaFileAdd.title')}</PageTitle>
        <ProblemBanner errors={errors} />
        <Uploaded handle={handle} dir={dir} created={created}
                  onMore={() => { setCreated(null); setFiles([]); setRows([]); setErrors([]); }} />
      </>
    );
  }

  return (
    <>
      <PageTitle>{t('mediaFileAdd.title')}</PageTitle>
      <ProblemBanner errors={errors} error={problem ?? dirs.error} />
      <p className="subtitle">{t('mediaFileAdd.title')}</p>
      <p className="pagetip">{t('mediaFileAdd.pageTip')}</p>

      <form id="entry" className="form-stacked" onSubmit={(e) => { e.preventDefault(); void send(); }}>
        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="mfadd_bean_directoryId">{t('mediaFileAdd.directory')}</label>
          <div className="col-sm-9">
            <select id="mfadd_bean_directoryId" name="bean.directoryId" className="form-select" value={dir}
                    onChange={(e) => { setChosenDir(e.target.value); }}>
              {directories.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </div>
        </div>

        <div className="card">
          <div className="card-header"><h2 className="section-head">{t('mediaFileAdd.fileLocation')}</h2></div>
          <div className="card-body">
            <div id="mediaDropZone" className={`media-dropzone${dragging ? ' media-dropzone-active' : ''}`}
                 onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
                 onDragEnter={() => { depth.current++; setDragging(true); }}
                 onDragLeave={() => { depth.current = Math.max(0, depth.current - 1); if (depth.current === 0) { setDragging(false); } }}
                 onDrop={onDrop}
                 onClick={(e) => { if (e.target !== input.current) { input.current?.click(); } }}>
              <label htmlFor="uploadedFiles" className="visually-hidden">{t('mediaFileAdd.fileLocation')}</label>
              <input type="file" name="uploadedFiles" id="uploadedFiles" multiple ref={input}
                     onChange={(e) => { choose(e.target.files); }} />
              <p className="media-dropzone-hint">{t('mediaFileAdd.dropHint')}</p>
              <ul id="mediaChosenFiles" className="media-chosen">
                {files.map((file, index) => {
                  const row = rows[index];
                  return (
                    <li key={`${file.name}:${String(index)}`}>
                      {file.name} ({formatSize(file.size)})
                      {row === undefined || row.state === 'waiting' ? null : row.state === 'failed' ? null : (
                        <progress value={row.state === 'done' ? file.size : row.loaded} max={file.size}
                                  aria-label={file.name} />
                      )}
                    </li>
                  );
                })}
                {files.length > 0 ? <li className="media-chosen-total">{summary}</li> : null}
              </ul>
            </div>
          </div>
        </div>

        <details className="editor-details" open={open} onToggle={(e) => { setOpen(e.currentTarget.open); }}>
          <summary className="rail-group-label">{t('generic.details')}</summary>
          <div className="row mb-3">
            <label className="col-sm-3 col-form-label" htmlFor="mfadd_bean_description">{t('generic.description')}</label>
            <div className="col-sm-9">
              <textarea id="mfadd_bean_description" rows={3} maxLength={255} className="form-control" value={description}
                        onChange={(e) => { setDescription(e.target.value); }} />
            </div>
          </div>
          <div className="row mb-3">
            <label className="col-sm-3 col-form-label" htmlFor="mfadd_bean_copyrightText">{t('mediaFileAdd.copyright')}</label>
            <div className="col-sm-9">
              <textarea id="mfadd_bean_copyrightText" rows={3} maxLength={1023} className="form-control" value={copyright}
                        onChange={(e) => { setCopyright(e.target.value); }} />
            </div>
          </div>
          <div className="row mb-3">
            <label className="col-sm-3 col-form-label" htmlFor="mfadd_bean_tagsAsString">{t('mediaFileAdd.tags')}</label>
            <div className="col-sm-9">
              <input id="mfadd_bean_tagsAsString" type="text" maxLength={255} className="form-control" value={tags}
                     onChange={(e) => { setTags(e.target.value); }} />
            </div>
          </div>
        </details>

        <button type="submit" id="uploadButton" className="btn btn-primary" disabled={files.length === 0 || busy}>
          {busy ? t('mediaFileAdd.uploading') : t('mediaFileAdd.upload')}
        </button>
        {' '}
        <Link className="btn btn-secondary" to={libraryPath(handle, dir)}>{t('generic.cancel')}</Link>
      </form>
    </>
  );
}
