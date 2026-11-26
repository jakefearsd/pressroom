import { useEffect, useId, useState } from 'react';
import { createPortal } from 'react-dom';
import { useBoot } from '../context';
import { useT } from '../i18n/messages';

export interface ChosenMedia {
  name: string;
  url: string;
  isImage: boolean;
  id: string;
}

declare global {
  interface Window {
    /** The callback MediaFileImageChooser.jsp (in the iframe) calls on its parent; set while the chooser is open. */
    onSelectMediaFile?: (name: string, url: string, isImage: string, id: string) => void;
  }
}

export function chooserUrl(contextPath: string, handle: string, imagesOnly: boolean): string {
  const base = `${contextPath}/pressroom-ui/authoring/overlay/mediaFileImageChooser.rol?weblog=${encodeURIComponent(handle)}`;
  return imagesOnly ? `${base}&imagesOnly=true` : base;
}

/**
 * The media chooser modal of EditorSurface.jsp: the media library's chooser overlay (still a
 * server page until the media wave) in an iframe, answering through window.onSelectMediaFile.
 * A featured/social picker takes an image; anything else is refused in place and the chooser
 * stays open for another pick.
 */
export function MediaChooser({ handle, imagesOnly, onChoose, onClose }: {
  handle: string;
  imagesOnly: boolean;
  onChoose: (media: ChosenMedia) => void;
  onClose: () => void;
}) {
  const t = useT();
  const { contextPath } = useBoot();
  const titleId = useId();
  const [refused, setRefused] = useState(false);

  useEffect(() => {
    window.onSelectMediaFile = (name, url, isImage, id) => {
      if (imagesOnly && (isImage !== 'true' || id === '')) {
        setRefused(true);
        return;
      }
      onChoose({ name, url, isImage: isImage === 'true', id });
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      delete window.onSelectMediaFile;
      document.removeEventListener('keydown', onKey);
    };
  }, [imagesOnly, onChoose, onClose]);

  return createPortal(
    <>
      <div id="mediafile_edit_lightbox" className="modal fade show" style={{ display: 'block' }} role="dialog"
           tabIndex={-1} aria-modal="true" aria-labelledby={titleId}>
        <div className="modal-dialog modal-lg">
          <div className="modal-content">
            <div className="modal-header">
              <p id={titleId} className="modal-title">{t('weblogEdit.insertMediaFile')}</p>
              <button type="button" className="btn-close" aria-label={t('generic.close')} onClick={onClose} />
            </div>
            <div className="modal-body">
              <iframe id="mediaFileEditor" title={t('weblogEdit.insertMediaFile')} style={{ visibility: 'inherit' }}
                      height="600" width="100%" src={chooserUrl(contextPath, handle, imagesOnly)} />
            </div>
            <div className="modal-footer">
              {refused ? (
                <p className="editor-chooser-refusal" id="mediaChooserRefusal" role="status">
                  {t('editor.chooser.imagesOnly')}
                </p>
              ) : null}
              <button type="button" className="btn btn-secondary" onClick={onClose}>{t('generic.close')}</button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop fade show" />
    </>,
    document.body
  );
}
