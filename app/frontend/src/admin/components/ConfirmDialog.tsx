import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useT } from '../i18n/messages';

export interface ConfirmDialogProps {
  /** The question, in the modal header (like comments.bulkDeleteConfirm). */
  title: string;
  /** Warning text or detail under it. */
  children?: ReactNode;
  /** The destructive button's label (comments.delete). */
  confirmLabel: string;
  /** btn-danger for a destructive action (the default), btn-primary otherwise. */
  tone?: 'danger' | 'primary';
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * The one confirmation modal, replacing every data-confirm: the shape of Comments.jsp's
 * comments-delete-modal. The text is React text, so an apostrophe cannot fail it open;
 * focus starts on Cancel, Escape cancels, and focus returns to what had it.
 */
export function ConfirmDialog({ title, children, confirmLabel, tone = 'danger', onConfirm, onCancel }: ConfirmDialogProps) {
  const t = useT();
  const titleId = useId();
  const cancel = useRef<HTMLButtonElement>(null);

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

  return createPortal(
    <>
      <div className="modal fade show" style={{ display: 'block' }} tabIndex={-1} role="dialog"
           aria-modal="true" aria-labelledby={titleId}>
        <div className="modal-dialog">
          <div className="modal-content">
            <div className="modal-header">
              <p id={titleId} className="modal-title">{title}</p>
            </div>
            <div className="modal-body">{children}</div>
            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" ref={cancel} onClick={onCancel}>
                {t('generic.cancel')}
              </button>
              <button type="button" className={`btn btn-${tone}`} onClick={onConfirm}>
                {confirmLabel}
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop fade show" />
    </>,
    document.body
  );
}
