import { useT } from '../i18n/messages';
import { TimeZoneContext, formatDateTime } from '../components/DateTime';
import { useContext } from 'react';
import type { DraftKeeper } from './useDraftKeeper';

/** The offer to restore unsaved text (markup of EntryEdit.jsp's #draftRecoveryBar); nothing when there is none. */
export function DraftBar({ keeper, onRestore }: { keeper: DraftKeeper; onRestore: (text: string) => void }) {
  const t = useT();
  const zone = useContext(TimeZoneContext);
  if (keeper.offer === null) {
    return null;
  }
  const restore = () => {
    const text = keeper.restore();
    if (text !== null) {
      onRestore(text);
    }
  };
  return (
    <div id="draftRecoveryBar" className="draft-bar" role="status" aria-live="polite">
      <span className="draft-bar-text">
        {t('weblogEdit.draftRecovery.message', formatDateTime(keeper.offer.at, zone))}
      </span>
      <button type="button" className="draft-bar-restore" onClick={restore}>
        {t('weblogEdit.draftRecovery.restore')}
      </button>
      <span className="draft-bar-sep">&#183;</span>
      <button type="button" className="draft-bar-discard" onClick={keeper.discard}>
        {t('weblogEdit.draftRecovery.discard')}
      </button>
    </div>
  );
}
