import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useDirectories, useMediaList, type MediaView } from '../../api/media';
import { EmptyState } from '../../components/EmptyState';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useT } from '../../i18n/messages';
import { isImage, thumbnailUrl } from './shared';

/** What the chooser hands back: the JSP overlay's four arguments, plus the whole MediaView. */
export interface MediaChoice {
  id: string;
  name: string;
  /** The file's public URL (what an entry links or embeds). */
  url: string;
  isImage: boolean;
  file: MediaView;
}

/**
 * Props contract of the media chooser, for the entry and page editors (featured and share
 * images, inserting an image or a link).
 */
export interface MediaChooserProps {
  /** The weblog whose library is browsed. */
  handle: string;
  /**
   * Offer images only, outside private folders: what a featured or share image may be (the
   * server refuses any other). Default false: every file, images as thumbnails and the rest as
   * links, as the editor's insert dialog lists them.
   */
  imagesOnly?: boolean;
  /** Called with the clicked (or Enter/Space-activated) file. The chooser does not close itself. */
  onSelect: (choice: MediaChoice) => void;
  /** Called by Cancel, Escape and a click on the backdrop. */
  onCancel: () => void;
}

/**
 * MediaFileImageChooser.jsp as a modal: a folder menu ("All folders" first), the thumbnails,
 * the same empty state. Read-only, so any member who can open the editor can use it.
 */
export function MediaChooser({ handle, imagesOnly = false, onSelect, onCancel }: MediaChooserProps) {
  const t = useT();
  const titleId = useId();
  const cancel = useRef<HTMLButtonElement>(null);
  const [dir, setDir] = useState('');
  const dirs = useDirectories(handle);
  const list = useMediaList(handle, { directoryId: dir, type: imagesOnly ? 'image' : undefined });

  useEffect(() => {
    const before = document.activeElement;
    cancel.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onCancel();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      if (before instanceof HTMLElement) {
        before.focus();
      }
    };
  }, [onCancel]);

  const privateIds = new Set((dirs.data ?? []).filter((d) => d.isPrivate === true).map((d) => d.id));
  const files = (list.data?.items ?? []).filter((f) => !imagesOnly || !privateIds.has(f.directory));
  const choose = (file: MediaView) => {
    onSelect({ id: file.id ?? '', name: file.name ?? '', url: file.url ?? '', isImage: isImage(file), file });
  };

  return createPortal(
    <>
      <div className="modal fade show" style={{ display: 'block' }} tabIndex={-1} role="dialog" aria-modal="true"
           aria-labelledby={titleId} onClick={(e) => { if (e.target === e.currentTarget) { onCancel(); } }}>
        <div className="modal-dialog modal-lg">
          <div className="modal-content">
            <div className="modal-header"><p id={titleId} className="modal-title">{t('mediaFileImageChooser.title')}</p></div>
            <div className="modal-body">
              <ProblemBanner error={dirs.error ?? list.error} />
              <form id="mediaFileChooserForm" className="form-vertical" onSubmit={(e) => { e.preventDefault(); }}>
                <p className="pagetip">{t('mediaFileImageChooser.pageTip')}</p>
                {(dirs.data ?? []).length > 0 ? (
                  <>
                    <label htmlFor="chooserDirectoryId">{t('mediaFileAdd.directory')}</label>
                    <select id="chooserDirectoryId" name="directoryId" className="form-select" value={dir}
                            onChange={(e) => { setDir(e.target.value); }}>
                      <option value="">{t('mediaFileImageChooser.allDirectories')}</option>
                      {(dirs.data ?? []).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                    </select>
                  </>
                ) : null}
                <div id="imageGrid" className="card">
                  <div className="card-body">
                    {list.isSuccess && files.length === 0 ? (
                      <EmptyState title={t('mediaFileView.noFiles')} body={t('empty.mediaChooser.body')} />
                    ) : null}
                    <ul>
                      {files.map((file) => (
                        <li key={file.id} className="align-images">
                          <div className="mediaObject" role="button" tabIndex={0} aria-label={file.name}
                               data-media-file-id={file.id}
                               onClick={() => { choose(file); }}
                               onKeyDown={(e) => {
                                 if (e.key === 'Enter' || e.key === ' ') {
                                   e.preventDefault();
                                   choose(file);
                                 }
                               }}>
                            {isImage(file)
                              ? <img src={thumbnailUrl(file)} alt={file.name} style={{ maxWidth: 120, maxHeight: 120 }} />
                              : <span className="bi bi-file-earmark fs-1" aria-hidden="true" />}
                          </div>
                          <div className="mediaObjectInfo">{file.name}</div>
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
              </form>
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" ref={cancel} onClick={onCancel}>{t('generic.cancel')}</button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop fade show" />
    </>,
    document.body
  );
}
