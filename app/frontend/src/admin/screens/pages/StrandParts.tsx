import { useState } from 'react';
import { StatusPill } from '../../components/StatusPill';
import { useT } from '../../i18n/messages';

export interface PartInfo {
  id: string;
  title: string;
  status: string;
}

interface Props {
  /** The part ids in reading order. */
  parts: readonly string[];
  info: ReadonlyMap<string, PartInfo>;
  /** The posts that could be added (not in this or another strand). */
  candidates: readonly PartInfo[];
  invalid?: boolean;
  onChange: (parts: string[]) => void;
}

/** The strand Parts panel of PageEdit.jsp (`#page_strand_parts`): move, remove, add and drag rows. */
export function StrandParts({ parts, info, candidates, invalid = false, onChange }: Props) {
  const t = useT();
  const [dragging, setDragging] = useState<number | null>(null);
  const move = (from: number, to: number) => {
    if (to < 0 || to >= parts.length || from === to) {
      return;
    }
    const next = [...parts];
    next.splice(to, 0, next.splice(from, 1)[0] ?? '');
    onChange(next);
  };
  const addable = candidates.filter((c) => !parts.includes(c.id));
  return (
    <div id="page_strand_parts" className={`strand-parts${invalid ? ' is-invalid' : ''}`}>
      <ol className="strand-parts-list">
        {parts.map((id, index) => {
          const part = info.get(id) ?? { id, title: id, status: 'DRAFT' };
          return (
            <li key={id} className="strand-part" draggable data-status={part.status.toLowerCase()}
                onDragStart={() => { setDragging(index); }}
                onDragOver={(event) => { event.preventDefault(); }}
                onDrop={(event) => {
                  event.preventDefault();
                  if (dragging !== null) {
                    move(dragging, index);
                  }
                  setDragging(null);
                }}>
              <span className="strand-part-title">{part.title}</span>
              <StatusPill status={part.status} />
              <span className="strand-part-actions">
                <button type="button" className="row-action strand-part-up"
                        aria-label={`${t('pageEdit.parts.moveUp')}: ${part.title}`}
                        onClick={() => { move(index, index - 1); }}>
                  <span className="bi bi-arrow-up" aria-hidden="true" title={t('pageEdit.parts.moveUp')} />
                </button>
                <button type="button" className="row-action strand-part-down"
                        aria-label={`${t('pageEdit.parts.moveDown')}: ${part.title}`}
                        onClick={() => { move(index, index + 1); }}>
                  <span className="bi bi-arrow-down" aria-hidden="true" title={t('pageEdit.parts.moveDown')} />
                </button>
                <button type="button" className="row-action row-action-danger strand-part-remove"
                        aria-label={`${t('pageEdit.parts.remove')}: ${part.title}`}
                        onClick={() => { onChange(parts.filter((p) => p !== id)); }}>
                  <span className="bi bi-x-lg" aria-hidden="true" title={t('pageEdit.parts.remove')} />
                </button>
              </span>
            </li>
          );
        })}
      </ol>
      <label className="editor-field-label" htmlFor="strandAddSelect">{t('pageEdit.parts.add')}</label>
      <select id="strandAddSelect" className="form-select" value=""
              onChange={(event) => {
                if (event.target.value !== '') {
                  onChange([...parts, event.target.value]);
                }
              }}>
        <option value="" />
        {addable.map((c) => (
          <option key={c.id} value={c.id} data-status={c.status.toLowerCase()}>{c.title}</option>
        ))}
      </select>
    </div>
  );
}
