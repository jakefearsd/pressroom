import { useCallback, useEffect, useRef, useState } from 'react';
import { useBoot } from '../../context';
import { useT } from '../../i18n/messages';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { cropRectInNaturalPixels, loadCropper, seedSelection, type CropRect, type SelectionElement } from './crop';

const HANDLES = ['n-resize', 'e-resize', 's-resize', 'w-resize', 'ne-resize', 'nw-resize', 'se-resize', 'sw-resize'];

/**
 * The crop section of MediaFileEdit: Cropper.js v2 custom elements over the original, and a
 * destructive Apply behind a ConfirmDialog. `onApply` gets the rectangle in natural pixels and
 * resolves when the server answered; `busy` shows the "Cropping..." label meanwhile.
 */
export function CropEditor({ src, width, height, busy, onApply }: {
  src: string; width: number; height: number; busy: boolean; onApply: (rect: CropRect) => void;
}) {
  const t = useT();
  const boot = useBoot();
  const canvas = useRef<HTMLElement | null>(null);
  const image = useRef<HTMLElement | null>(null);
  const selection = useRef<(HTMLElement & SelectionElement) | null>(null);
  const setCanvas = useCallback((el: HTMLElement | null) => { canvas.current = el; }, []);
  const setImage = useCallback((el: HTMLElement | null) => { image.current = el; }, []);
  const setSelection = useCallback((el: HTMLElement | null) => { selection.current = el as (HTMLElement & SelectionElement) | null; }, []);
  const [ready, setReady] = useState(false);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    let live = true;
    void loadCropper(boot.contextPath).then(() => { if (live) { setReady(true); } }, () => undefined);
    return () => { live = false; };
  }, [boot.contextPath]);

  useEffect(() => {
    const box = canvas.current;
    const area = selection.current;
    if (!ready || box === null || area === null) {
      return undefined;
    }
    if (typeof ResizeObserver === 'undefined') {
      seedSelection(box, area);
      return undefined;
    }
    const observer = new ResizeObserver(() => { seedSelection(box, area); });
    observer.observe(box);
    return () => { observer.disconnect(); };
  }, [ready]);

  const rect = (): CropRect | null => {
    const box = canvas.current;
    const picture = image.current;
    const area = selection.current;
    if (box === null || picture === null || area === null) {
      return null;
    }
    const c = box.getBoundingClientRect();
    const i = picture.getBoundingClientRect();
    return cropRectInNaturalPixels(area, c, i, { width, height });
  };

  const apply = () => {
    const found = rect();
    if (found !== null && found.width > 0 && found.height > 0) {
      setConfirming(true);
    }
  };

  const confirm = () => {
    setConfirming(false);
    const found = rect();
    if (found !== null) {
      onApply(found);
    }
  };

  return (
    <>
      <hr />
      <h2 id="cropSectionTitle" className="section-head">{t('mediaFileEdit.crop.title')}</h2>
      <p className="pagetip">{t('mediaFileEdit.crop.tip')}</p>
      {ready ? (
        <cropper-canvas id="cropCanvas" background style={{ width: '100%', height: 360 }} ref={setCanvas}>
          <cropper-image id="cropImage" src={src} alt="" ref={setImage} />
          <cropper-shade hidden />
          <cropper-handle action="select" plain />
          <cropper-selection id="cropSelection" initial-coverage="0.9" movable resizable ref={setSelection}>
            <cropper-grid role="presentation" covered />
            <cropper-crosshair centered />
            <cropper-handle action="move" theme-color="rgba(255, 255, 255, 0.35)" />
            {HANDLES.map((action) => <cropper-handle key={action} action={action} />)}
          </cropper-selection>
        </cropper-canvas>
      ) : null}
      <form id="cropForm" className="mt-2" onSubmit={(event) => { event.preventDefault(); apply(); }}>
        <button type="submit" id="cropButton" className="btn btn-danger" disabled={busy}>
          {busy ? t('mediaFileEdit.crop.applying') : t('mediaFileEdit.crop.apply')}
        </button>
      </form>
      {confirming ? (
        <ConfirmDialog title={t('mediaFileEdit.crop.confirm')} confirmLabel={t('mediaFileEdit.crop.apply')}
                       onConfirm={confirm} onCancel={() => { setConfirming(false); }} />
      ) : null}
    </>
  );
}
