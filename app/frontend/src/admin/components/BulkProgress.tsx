import { useT } from '../i18n/messages';

/** "Working: 3 of 12 done" while a bulk action runs (spec A6), beside the SelectionBar. */
export function BulkProgress({ progress }: { progress: { done: number; total: number } | null }) {
  const t = useT();
  if (progress === null) {
    return null;
  }
  return (
    <p className="bulk-progress form-text" role="status" aria-live="polite">
      {t('bulk.progress', progress.done, progress.total)}
    </p>
  );
}
