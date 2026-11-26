import { useState } from 'react';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { DateTime } from '../components/DateTime';
import { useT } from '../i18n/messages';

/**
 * "Send as newsletter": a manual, synchronous, cannot-double-send action for a published entry.
 * Already sent shows when; no list configured links to the weblog's settings; otherwise a button
 * opens the confirmation (a mailed typo is permanent).
 */
export function NewsletterCard({ sentAt, listConfigured, settingsHref, sending, onSend }: {
  sentAt: string | undefined;
  listConfigured: boolean;
  settingsHref: string;
  sending: boolean;
  /** Resolves once the call has answered, success or refusal (the confirmation closes then). */
  onSend: () => Promise<void>;
}) {
  const t = useT();
  const [confirming, setConfirming] = useState(false);
  let body;
  if (sentAt !== undefined) {
    // The date is an element, not text: the message's one argument is a placeholder swapped for <time>.
    const [before = '', after = ''] = t('newsletter.sentAt', '\u0000').split('\u0000');
    body = <p className="pagetip" id="newsletterSentAt">{before}<DateTime value={sentAt} />{after}</p>;
  } else if (!listConfigured) {
    body = (
      <p className="pagetip" id="newsletterNoList">
        {t('newsletter.noList')}{' '}<a href={settingsHref}>{t('newsletter.noList.link')}</a>
      </p>
    );
  } else {
    body = (
      <button type="button" className="btn btn-primary" id="sendNewsletterButton" onClick={() => { setConfirming(true); }}>
        {t('newsletter.send')}
      </button>
    );
  }
  return (
    <div id="newsletterCard" className="editor-box editor-rail-extra">
      <p className="rail-group-label">{t('newsletter.cardTitle')}</p>
      {body}
      {confirming ? (
        <ConfirmDialog title={t('newsletter.confirmTitle')} tone="primary"
                       confirmLabel={sending ? t('newsletter.sending') : t('newsletter.send')}
                       onConfirm={() => { if (!sending) { void onSend().then(() => { setConfirming(false); }); } }}
                       onCancel={() => { setConfirming(false); }}>
          <p>{t('newsletter.confirmBody')}</p>
        </ConfirmDialog>
      ) : null}
    </div>
  );
}
