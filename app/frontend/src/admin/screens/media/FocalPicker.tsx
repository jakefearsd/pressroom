import { useId, type KeyboardEvent, type MouseEvent } from 'react';
import { useT } from '../../i18n/messages';

export interface Focal {
  x: number;
  y: number;
}

const STEPS: Record<string, [number, number]> = {
  ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1]
};
const clamp = (n: number) => Math.min(1, Math.max(0, n));

/**
 * The focal-point control of MediaFileEdit: click the 480px rendition to mark the subject,
 * arrow keys nudge by 5% (1% with Shift) from the centre, Clear removes it. The value is a
 * fraction of the width and height, three decimals as the JSP posted it.
 */
export function FocalPicker({ src, value, onChange, props }: {
  src: string;
  value: Focal | null;
  onChange: (value: Focal | null) => void;
  /** useFieldErrors props for the picker's id/aria-invalid. */
  props?: { id: string; className: string; 'aria-invalid'?: true };
}) {
  const t = useT();
  const tip = useId();
  const keys = useId();
  const round = (n: number) => Number(clamp(n).toFixed(3));

  const onKey = (event: KeyboardEvent) => {
    const step = STEPS[event.key];
    if (step === undefined) {
      return;
    }
    event.preventDefault();
    const size = event.shiftKey ? 0.01 : 0.05;
    onChange({ x: round((value?.x ?? 0.5) + step[0] * size), y: round((value?.y ?? 0.5) + step[1] * size) });
  };

  const onClick = (event: MouseEvent<HTMLDivElement>) => {
    const image = event.currentTarget.querySelector('img');
    const rect = image?.getBoundingClientRect();
    if (rect === undefined || !(rect.width > 0) || !(rect.height > 0)) {
      return;
    }
    onChange({ x: round((event.clientX - rect.left) / rect.width), y: round((event.clientY - rect.top) / rect.height) });
  };

  return (
    <>
      <div id={props?.id ?? 'focalPicker'} tabIndex={0} role="group" aria-describedby={`${tip} ${keys}`}
           aria-label={t('mediaFileEdit.focalPoint')} {...(props?.['aria-invalid'] ? { 'aria-invalid': true } : {})}
           onKeyDown={onKey} onClick={onClick}
           style={{ position: 'relative', display: 'inline-block', cursor: 'crosshair', lineHeight: 0 }}>
        <img id="focalImage" src={`${src}?w=480`} alt="" style={{ maxWidth: 240, maxHeight: 240, display: 'block' }} />
        <span id="focalMarker" data-testid="focal-marker" hidden={value === null}
              style={{
                position: 'absolute', width: 14, height: 14, margin: '-7px 0 0 -7px', borderRadius: '50%',
                border: '2px solid var(--surface)', boxShadow: '0 0 3px var(--ink)', pointerEvents: 'none',
                background: 'color-mix(in srgb, var(--bad) 85%, transparent)',
                left: `${String((value?.x ?? 0) * 100)}%`, top: `${String((value?.y ?? 0) * 100)}%`
              }} />
      </div>
      <div id={tip} className="form-text">{t('mediaFileEdit.focalPoint.tip')}</div>
      <div id={keys} className="form-text">{t('mediaFileEdit.focalPoint.keys')}</div>
      <button type="button" id="clearFocalButton" className="btn btn-sm btn-outline-secondary mt-1"
              onClick={() => { onChange(null); }}>
        {t('mediaFileEdit.focalPoint.clear')}
      </button>
    </>
  );
}
