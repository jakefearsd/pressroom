import type { ReactNode } from 'react';
import { useT } from '../i18n/messages';

/**
 * The bulk-action bar (pressroom.js's selection bar): hidden until something is checked,
 * then "{0} selected" and the actions given as children.
 */
export function SelectionBar({ count, formId, children }: { count: number; formId?: string; children?: ReactNode }) {
  const t = useT();
  return (
    <div className="selection-bar" hidden={count === 0} {...(formId === undefined ? {} : { 'data-selection-bar': formId })}>
      <span className="selection-count">{t('selection.count', count)}</span>
      {children}
    </div>
  );
}
