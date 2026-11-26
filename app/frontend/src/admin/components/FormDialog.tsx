import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useT } from '../i18n/messages';

/**
 * The modal around a small form (the add/edit-category dialog): a ConfirmDialog with a body
 * that is a form, a footer note for the form's own feedback, and a confirm that can be
 * disabled. Focus starts on the first field, Escape cancels, and focus returns afterwards.
 */
export function FormDialog({ title, children, note, saveLabel, saveDisabled = false, onSave, onCancel }: {
  title: string;
  children: ReactNode;
  /** The feedback line in the footer (validation text, a refusal). */
  note?: ReactNode;
  saveLabel: string;
  saveDisabled?: boolean;
  onSave: () => void;
  onCancel: () => void;
}) {
  const t = useT();
  const titleId = useId();
  const body = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const before = document.activeElement;
    body.current?.querySelector<HTMLElement>('input, select, textarea')?.focus();
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

  return createPortal(
    <>
      <div className="modal fade show" style={{ display: 'block' }} tabIndex={-1} role="dialog"
           aria-modal="true" aria-labelledby={titleId}>
        <div className="modal-dialog modal-lg">
          <div className="modal-content">
            <div className="modal-header">
              <p id={titleId} className="modal-title">{title}</p>
            </div>
            <div className="modal-body" ref={body}>{children}</div>
            <div className="modal-footer">
              {note}
              <button type="button" className="btn btn-secondary" onClick={onCancel}>{t('generic.cancel')}</button>
              <button type="button" className="btn btn-primary" disabled={saveDisabled} onClick={onSave}>{saveLabel}</button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop fade show" />
    </>,
    document.body
  );
}
