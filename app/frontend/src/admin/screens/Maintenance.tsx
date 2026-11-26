import { useState, type ReactNode } from 'react';
import { useMeta } from '../api/queries';
import {
  useAllWeblogs, useAnalyticsStatus, useDiscoveryStatus, useRefreshDiscovery, useRevisionStats, useSiteAction,
  useSweepRevisions, useSyncAnalytics, type SiteAction
} from '../api/siteQueries';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { DateTime } from '../components/DateTime';
import { ProblemBanner } from '../components/ProblemBanner';
import { PageTitle } from '../frame/PageTitle';
import { useT, type Translate } from '../i18n/messages';

interface Outcome {
  messages: string[];
  errors: string[];
  error?: unknown;
}

const NONE: Outcome = { messages: [], errors: [] };

/** A bordered operation row: its heading and body, then the button the JSP put in its own form. */
function Operation({ className, title, children }: { className: string; title?: string; children: ReactNode }) {
  return (
    <div className={`maintenance-op ${className}`.trim()}>
      {title === undefined ? null : <h2 className="section-head">{title}</h2>}
      {children}
    </div>
  );
}

function DiscoverySection({ report }: { report: (outcome: Outcome) => void }) {
  const t = useT();
  const status = useDiscoveryStatus();
  const refresh = useRefreshDiscovery();
  const view = status.data;
  const stateText = (state: string | undefined): string => {
    switch (state) {
      case 'DISABLED': return t('maintenance.discovery.state.DISABLED');
      case 'OK': return t('maintenance.discovery.state.OK');
      case 'FAILED': return t('maintenance.discovery.state.FAILED');
      default: return t('maintenance.discovery.state.NEVER');
    }
  };
  const run = () => {
    report(NONE);
    refresh.mutate(undefined, {
      onSuccess: (after) => {
        if (after.state === 'OK') {
          report({ ...NONE, messages: [t('maintenance.discovery.refreshed')] });
        } else if (after.state === 'FAILED') {
          report({ ...NONE, errors: [t('maintenance.discovery.refreshFailed', String(after.lastError))] });
        } else {
          report({ ...NONE, messages: [stateText(after.state)] });
        }
      },
      onError: (error) => { report({ ...NONE, error }); }
    });
  };
  if (view === undefined) {
    return <ProblemBanner error={status.error} />;
  }
  return (
    <Operation className="maintenance-discovery" title={t('maintenance.discovery.title')}>
      <p>{stateText(view.state)}</p>
      <dl>
        {view.lastAttempt === undefined ? null : (
          <>
            <dt>{t('maintenance.discovery.lastAttempt')}</dt>
            <dd><DateTime value={view.lastAttempt} /></dd>
          </>
        )}
        {view.lastSuccess === undefined ? null : (
          <>
            <dt>{t('maintenance.discovery.lastSuccess')}</dt>
            <dd><DateTime value={view.lastSuccess} /></dd>
          </>
        )}
        {view.state === 'OK' || view.state === 'FAILED' ? (
          <>
            <dt>{t('maintenance.discovery.countsLabel')}</dt>
            <dd>{t('maintenance.discovery.counts', view.scoredEntries, view.candidates)}</dd>
          </>
        ) : null}
        {view.lastError === undefined || view.lastError === '' ? null : (
          <>
            <dt>{t('maintenance.discovery.lastError')}</dt>
            <dd>{view.lastError}</dd>
          </>
        )}
      </dl>
      <button type="button" className="btn btn-secondary" disabled={refresh.isPending} onClick={run}>
        {refresh.isPending ? t('maintenance.discovery.refreshing') : t('maintenance.discovery.refresh')}
      </button>
    </Operation>
  );
}

function reachable(t: Translate, value: boolean | undefined): string {
  if (value === undefined) {
    return t('maintenance.analytics.reachable.unknown');
  }
  return value ? t('maintenance.analytics.reachable.yes') : t('maintenance.analytics.reachable.no');
}

function AnalyticsSection({ report }: { report: (outcome: Outcome) => void }) {
  const t = useT();
  const status = useAnalyticsStatus();
  const sync = useSyncAnalytics();
  const view = status.data;
  if (view === undefined) {
    return <ProblemBanner error={status.error} />;
  }
  const run = () => {
    report(NONE);
    sync.mutate(undefined, {
      onSuccess: () => { report({ ...NONE, messages: [t('maintenance.analytics.syncRequested')] }); },
      onError: (error) => { report({ ...NONE, error }); }
    });
  };
  return (
    <Operation className="maintenance-analytics" title={t('maintenance.analytics.title')}>
      {!view.configured ? <p>{t('maintenance.analytics.off')}</p> : (
        <>
          <p>{t('maintenance.analytics.registered', view.registered, view.total)}</p>
          <dl>
            <dt>{t('maintenance.analytics.reachable')}</dt>
            <dd>{reachable(t, view.reachable)}</dd>
            <dt>{t('maintenance.analytics.lastRead')}</dt>
            <dd>{view.lastRead === undefined ? t('maintenance.analytics.neverRead') : <DateTime value={view.lastRead} />}</dd>
            {view.lastReadError === undefined || view.lastReadError === '' ? null : (
              <>
                <dt>{t('maintenance.analytics.lastReadError')}</dt>
                <dd>{view.lastReadError}</dd>
              </>
            )}
          </dl>
          {view.failures.length === 0 ? null : (
            <>
              <h3>{t('maintenance.analytics.failures')}</h3>
              <ul>
                {view.failures.map((f) => (
                  <li key={f.handle}>{f.handle}: {f.error} (<DateTime value={f.at} />)</li>
                ))}
              </ul>
            </>
          )}
          <button type="button" className="btn btn-secondary" disabled={sync.isPending} onClick={run}>
            {sync.isPending ? t('maintenance.analytics.syncing') : t('maintenance.analytics.sync')}
          </button>
        </>
      )}
    </Operation>
  );
}

function RevisionsSection({ report }: { report: (outcome: Outcome) => void }) {
  const t = useT();
  const stats = useRevisionStats();
  const sweep = useSweepRevisions();
  const [asked, setAsked] = useState(false);
  const view = stats.data;
  if (view === undefined) {
    return stats.isError ? <ProblemBanner errors={[t('maintenance.revisions.unreadable')]} /> : null;
  }
  const next = view.nextSweep;
  const run = () => {
    setAsked(false);
    report(NONE);
    sweep.mutate(undefined, {
      onSuccess: (done) => {
        const total = String(done.total ?? 0);
        report(done.weblogsFailed !== undefined && done.weblogsFailed > 0 || done.ceilingFailed === true
          ? { ...NONE, errors: [t('maintenance.revisions.sweepFailed', total)] }
          : { ...NONE, messages: [t('maintenance.revisions.swept', total)] });
      },
      onError: (error) => { report({ ...NONE, error }); }
    });
  };
  return (
    <Operation className="maintenance-revisions" title={t('maintenance.revisions.title')}>
      <dl>
        <dt>{t('maintenance.revisions.posts')}</dt>
        <dd>{view.entryRevisions}</dd>
        <dt>{t('maintenance.revisions.pages')}</dt>
        <dd>{view.pageRevisions}</dd>
        <dt>{t('maintenance.revisions.kept')}</dt>
        <dd>{view.kept}</dd>
        <dt>{t('maintenance.revisions.size')}</dt>
        <dd>{t('maintenance.revisions.sizeValue', new Intl.NumberFormat('en-US').format(Math.floor((view.approximateBytes ?? 0) / 1024)))}</dd>
        {view.oldest === undefined ? null : (
          <>
            <dt>{t('maintenance.revisions.oldest')}</dt>
            <dd><DateTime value={view.oldest} /></dd>
          </>
        )}
        <dt>{t('maintenance.revisions.lastSweep')}</dt>
        <dd>
          {view.lastSweep?.at === undefined ? t('maintenance.revisions.lastSweepNone') : (
            <>
              <DateTime value={view.lastSweep.at} />, {t('maintenance.revisions.lastSweepValue', view.lastSweep.total ?? 0)}
            </>
          )}
        </dd>
        <dt>{t('maintenance.revisions.nextSweep')}</dt>
        <dd className="maintenance-revisions-next">{next?.total ?? 0}</dd>
      </dl>
      {next?.incomplete === true ? <p className="pagetip">{t('maintenance.revisions.nextSweepIncomplete')}</p> : null}
      {next?.ceilingUnmeetable === true ? <p className="pagetip">{t('maintenance.revisions.unmeetable')}</p> : null}
      {view.largest === undefined || view.largest.length === 0 ? null : (
        <>
          <h3>{t('maintenance.revisions.largest')}</h3>
          <ul>
            {view.largest.map((item) => (
              <li key={`${String(item.kind)}:${String(item.id)}`}>
                {item.revisions}: {item.title} ({item.kind === 'PAGE' ? t('maintenance.revisions.kind.PAGE') : t('maintenance.revisions.kind.ENTRY')})
              </li>
            ))}
          </ul>
        </>
      )}
      <button type="button" className="btn btn-secondary" disabled={sweep.isPending} onClick={() => { setAsked(true); }}>
        {sweep.isPending ? t('maintenance.revisions.sweeping') : t('maintenance.revisions.sweep')}
      </button>
      {asked ? (
        <ConfirmDialog title={`${t('maintenance.revisions.sweepConfirm')}: ${String(next?.total ?? 0)}`}
                       confirmLabel={t('maintenance.revisions.sweep')} onConfirm={run}
                       onCancel={() => { setAsked(false); }} />
      ) : null}
    </Operation>
  );
}

/** Maintenance.jsp: per-weblog operations, then the site-wide discovery, revisions and analytics panels. */
export function Maintenance() {
  const t = useT();
  const weblogs = useAllWeblogs();
  const action = useSiteAction();
  const searchEnabled = useMeta().data?.features.searchEnabled === true;
  const [handle, setHandle] = useState('');
  const [outcome, setOutcome] = useState<Outcome>(NONE);
  const [asked, setAsked] = useState<SiteAction | null>(null);

  const start = (which: SiteAction) => {
    setAsked(null);
    setOutcome(NONE);
    action.mutate({ handle, action: which }, {
      onSuccess: (done) => {
        const key: Record<SiteAction, string> = {
          'flush-cache': t('maintenance.message.flushed'),
          'rebuild-index': t('maintenance.message.indexed'),
          'regenerate-renditions': t('maintenance.message.renditionsRegenerated',
            'count' in done ? (done.count ?? 0) : 0)
        };
        setOutcome({ ...NONE, messages: [key[which]] });
      },
      onError: (error) => { setOutcome({ ...NONE, error }); }
    });
  };
  const pending = action.isPending ? action.variables.action : undefined;
  const confirmText: Partial<Record<SiteAction, string>> = {
    'rebuild-index': t('maintenance.confirm.index', handle),
    'regenerate-renditions': t('maintenance.confirm.regenerateRenditions', handle)
  };
  const labels: Record<SiteAction, string> = {
    'flush-cache': t('maintenance.button.flush'),
    'rebuild-index': t('maintenance.button.index'),
    'regenerate-renditions': t('maintenance.button.regenerateRenditions')
  };
  const busy: Partial<Record<SiteAction, string>> = {
    'rebuild-index': t('maintenance.index.building'),
    'regenerate-renditions': t('maintenance.regenerateRenditions.building')
  };
  const prompts: Record<SiteAction, string> = {
    'flush-cache': t('maintenance.prompt.flush'),
    'rebuild-index': t('maintenance.prompt.index'),
    'regenerate-renditions': t('maintenance.prompt.regenerateRenditions')
  };
  const op = (which: SiteAction) => (
    <div className="maintenance-op">
      <p>{prompts[which]}</p>
      <button type="button" className="btn btn-secondary" disabled={handle === '' || pending !== undefined}
              onClick={() => { if (confirmText[which] === undefined) { start(which); } else { setAsked(which); } }}>
        {pending === which ? (busy[which] ?? labels[which]) : labels[which]}
      </button>
    </div>
  );

  return (
    <>
      <PageTitle>{t('maintenance.title')}</PageTitle>
      <ProblemBanner messages={outcome.messages} errors={outcome.errors} error={outcome.error ?? weblogs.error} />
      <p className="subtitle">{t('maintenance.subtitle')}</p>
      <form className="form-stacked" onSubmit={(event) => { event.preventDefault(); }}>
        <div className="mb-3">
          <label htmlFor="maintenanceWeblog">{t('maintenance.prompt.weblog')}</label>
          <select name="weblogId" id="maintenanceWeblog" className="form-select" value={handle}
                  onChange={(event) => { setHandle(event.target.value); }}>
            <option value="" disabled>{t('maintenance.select.placeholder')}</option>
            {(weblogs.data?.items ?? []).map((w) => (
              <option key={w.handle} value={w.handle}>{w.name} ({w.handle})</option>
            ))}
          </select>
        </div>
        {op('flush-cache')}
        {searchEnabled ? op('rebuild-index') : null}
        {op('regenerate-renditions')}
      </form>
      <DiscoverySection report={setOutcome} />
      <RevisionsSection report={setOutcome} />
      <AnalyticsSection report={setOutcome} />
      {asked === null ? null : (
        <ConfirmDialog title={confirmText[asked] ?? ''} confirmLabel={labels[asked]} tone="primary"
                       onConfirm={() => { start(asked); }} onCancel={() => { setAsked(null); }} />
      )}
    </>
  );
}
