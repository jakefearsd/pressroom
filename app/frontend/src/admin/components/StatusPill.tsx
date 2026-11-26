import { useT } from '../i18n/messages';
import { DateTime } from './DateTime';

/**
 * The status pill of editor/StatusPill.jsp. The label key is composed from the status,
 * so the keys this can use are listed here for MessageKeyTest (dynamic keys are not
 * seen): weblogEdit.draft weblogEdit.pending weblogEdit.published weblogEdit.scheduled
 * weblogEdit.trashed weblogEdit.unsaved
 */
export function StatusPill({ status, when }: { status: string; when?: Date | string | number | null }) {
  const t = useT();
  const name = status.toLowerCase();
  return (
    <span className={`status-pill status-${name}`}>
      {t(`weblogEdit.${name}`)}
      {when === undefined || when === null ? null : (
        <>
          {' '}
          <span className="status-when"><DateTime value={when} /></span>
        </>
      )}
    </span>
  );
}
