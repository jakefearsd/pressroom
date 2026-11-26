import { useState } from 'react';
import { useParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { call } from '../api/client';
import { useTrash, type EntryView } from '../api/listQueries';
import { BulkProgress } from '../components/BulkProgress';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { DateTime } from '../components/DateTime';
import { EmptyState } from '../components/EmptyState';
import { ProblemBanner } from '../components/ProblemBanner';
import { Subtitle } from '../components/Subtitle';
import { useApi } from '../context';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';
import { useBulk } from '../lib/bulk';
import { failureText } from '../lib/errors';

function titleOf(entry: EntryView): string {
  return entry.title !== undefined && entry.title !== '' ? entry.title : (entry.anchor ?? entry.id ?? '');
}

/**
 * The entry trash (Trash.jsp): restore, delete forever and empty trash. Emptying runs
 * delete-forever per entry (spec A6) and reports the ones it could not remove in place.
 *
 * Not on /api, so not shown: the retention sentence (trash.retention.*, the
 * entry.trash.retention.days runtime property is not on /v1/meta) and the true trashed-at
 * time (EntryView carries no trashedAt, so the column shows the entry's last update).
 */
export function Trash() {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const handle = useParams()['handle'] ?? '';
  const trash = useTrash(handle);
  const [messages, setMessages] = useState<string[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [forever, setForever] = useState<EntryView | null>(null);
  const [confirmEmpty, setConfirmEmpty] = useState(false);
  const bulk = useBulk<EntryView>();
  const entries = trash.data ?? [];

  const path = (entry: EntryView) => ({ params: { path: { handle, id: entry.id ?? '' } } });
  const refresh = () => queries.invalidateQueries({ queryKey: ['entries', handle] });
  const reset = () => { setMessages([]); setErrors([]); setError(null); };

  const restore = async (entry: EntryView) => {
    reset();
    try {
      await call(api.POST('/v1/weblogs/{handle}/entries/{id}/restore', path(entry)));
      setMessages([t('trash.restored', titleOf(entry))]);
      await refresh();
    } catch (failure) {
      setError(failure);
    }
  };

  const deleteForever = async (entry: EntryView) => {
    setForever(null);
    reset();
    try {
      await call(api.POST('/v1/weblogs/{handle}/entries/{id}/delete-forever', path(entry)));
      setMessages([t('trash.deletedForever', titleOf(entry))]);
      await refresh();
    } catch (failure) {
      setError(failure);
    }
  };

  const empty = async () => {
    setConfirmEmpty(false);
    reset();
    const outcome = await bulk.run(entries, (entry) => call(api.POST('/v1/weblogs/{handle}/entries/{id}/delete-forever', path(entry))));
    setMessages(outcome.ok > 0 ? [t('trash.emptied', outcome.ok)] : []);
    setErrors(outcome.failed.map((f) => t('bulk.itemFailed', titleOf(f.item), failureText(f.error))));
    await refresh();
  };

  return (
    <>
      <PageTitle>{t('trash.title')}</PageTitle>
      <Subtitle text={t('trash.subtitle', handle)} />
      <div id="trash-list-marker">
        <ProblemBanner messages={messages} errors={errors} error={error ?? trash.error} />
        <BulkProgress progress={bulk.progress} />
        {trash.data === undefined ? null : entries.length === 0 ? (
          <EmptyState title={t('trash.none.title')} body={t('trash.none.body')} />
        ) : (
          <>
            <div className="table-responsive">
              <table className="pressroomtable table table-striped" width="100%">
                <thead>
                  <tr>
                    <th scope="col" className="pressroomtable">{t('trash.column.title')}</th>
                    <th scope="col" className="pressroomtable" style={{ width: '15%' }}>{t('trash.column.trashed')}</th>
                    <th scope="col" className="pressroomtable" style={{ width: '15%' }}>{t('trash.column.category')}</th>
                    <th scope="col" className="pressroomtable" style={{ width: '10%' }} />
                    <th scope="col" className="pressroomtable" style={{ width: '10%' }} />
                  </tr>
                </thead>
                <tbody>
                  {entries.map((entry) => (
                    <tr key={entry.id}>
                      <td>{titleOf(entry)}</td>
                      <td className="data"><DateTime value={entry.updateTime} /></td>
                      <td>{entry.category}</td>
                      <td>
                        <button type="button" className="btn btn-outline-secondary btn-sm" disabled={bulk.progress !== null}
                                onClick={() => { void restore(entry); }}>
                          {t('trash.restore')}
                        </button>
                      </td>
                      <td>
                        <button type="button" className="btn btn-link p-0 align-baseline border-0 text-danger"
                                disabled={bulk.progress !== null} onClick={() => { setForever(entry); }}>
                          {t('trash.deleteForever')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="d-flex justify-content-end mb-3">
              <button type="button" className="btn btn-danger" disabled={bulk.progress !== null}
                      onClick={() => { setConfirmEmpty(true); }}>
                {t('trash.empty')}
              </button>
            </div>
          </>
        )}
      </div>
      {forever === null ? null : (
        <ConfirmDialog title={t('trash.deleteForeverConfirm')} confirmLabel={t('trash.deleteForever')}
                       onConfirm={() => { void deleteForever(forever); }} onCancel={() => { setForever(null); }} />
      )}
      {confirmEmpty ? (
        <ConfirmDialog title={t('trash.emptyConfirm', entries.length)} confirmLabel={t('trash.empty')}
                       onConfirm={() => { void empty(); }} onCancel={() => { setConfirmEmpty(false); }} />
      ) : null}
    </>
  );
}
