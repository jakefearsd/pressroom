import { useState, type FormEvent } from 'react';
import { useStylesheet, useStylesheetActions, type StylesheetView } from '../../api/people';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Markup } from '../../components/Markup';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { DraftBar } from '../../session/DraftBar';
import { useDraftKeeper } from '../../session/useDraftKeeper';
import { RequireLevel, bannerError, useHandle } from './shared';

export function Stylesheet() {
  const handle = useHandle();
  return <RequireLevel level="admin" handle={handle}><StylesheetBody handle={handle} /></RequireLevel>;
}

type Pending = 'revert' | 'delete' | null;

function StylesheetBody({ handle }: { handle: string }) {
  const t = useT();
  const query = useStylesheet(handle);
  const actions = useStylesheetActions(handle);
  const [notice, setNotice] = useState<string[]>([]);
  const [pending, setPending] = useState<Pending>(null);
  const view = query.data;

  const failure = actions.save.error ?? actions.copy.error ?? actions.revert.error ?? actions.remove.error ?? query.error;
  const say = (key: string) => { setNotice([t(key)]); };
  /** A new action starts clean: an old refusal must not hide the new one. */
  const clear = () => {
    setNotice([]);
    actions.save.reset();
    actions.copy.reset();
    actions.revert.reset();
    actions.remove.reset();
  };

  const run = (which: Exclude<Pending, null>) => {
    setPending(null);
    clear();
    if (which === 'revert') {
      actions.revert.mutate(undefined, { onSuccess: () => { say('stylesheetEdit.revert.success'); } });
    } else {
      actions.remove.mutate(undefined, { onSuccess: () => { say('stylesheetEdit.default.success'); } });
    }
  };

  return (
    <>
      <PageTitle>{t('stylesheetEdit.title')}</PageTitle>
      <ProblemBanner messages={notice} error={bannerError(failure)} />
      <p className="subtitle">{t('stylesheetEdit.subtitle')}</p>
      {view === undefined ? null : view.exists ? (
        <>
          <Markup text={t('stylesheetEdit.youCanCustomize')} />
          <StylesheetEditor key={view.lastModified ?? 'new'} handle={handle} view={view} save={actions.save}
                            onAsk={setPending} onSaved={() => { say('stylesheetEdit.save.success'); }}
                            onSaving={clear} />
        </>
      ) : !view.customTheme ? (
        view.sharedThemeStylesheet ? (
          <>
            <Markup text={t('stylesheetEdit.sharedThemeWithStylesheet')} />
            <button type="button" className="btn btn-primary" disabled={actions.copy.isPending}
                    onClick={() => {
                      clear();
                      actions.copy.mutate(undefined, { onSuccess: () => { say('stylesheetEdit.create.success'); } });
                    }}>
              {t('stylesheetEdit.copyStylesheet')}
            </button>
          </>
        ) : <Markup text={t('stylesheetEdit.sharedThemeNoStylesheetSupport')} />
      ) : <p><Markup text={t('stylesheetEdit.customThemeNoStylesheet')} /></p>}
      {pending === null ? null : (
        <ConfirmDialog title={t(pending === 'revert' ? 'stylesheetEdit.confirmRevert' : 'stylesheetEdit.confirmDelete')}
                       confirmLabel={t(pending === 'revert' ? 'stylesheetEdit.revert' : 'stylesheetEdit.delete')}
                       onConfirm={() => { run(pending); }} onCancel={() => { setPending(null); }} />
      )}
    </>
  );
}

function StylesheetEditor({ handle, view, save, onAsk, onSaved, onSaving }: {
  handle: string; view: StylesheetView; save: ReturnType<typeof useStylesheetActions>['save']; onAsk: (what: 'revert' | 'delete') => void; onSaved: () => void; onSaving: () => void;
}) {
  const t = useT();
  const [contents, setContents] = useState(view.contents ?? '');
  const keeper = useDraftKeeper(`stylesheet:${handle}`, contents);
  const fields = useFieldErrors(save.error, () => 'stylesheet_contentsStandard');

  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSaving();
    save.mutate(contents, { onSuccess: () => { keeper.clear(); onSaved(); } });
  };

  return (
    <form id="stylesheetEditForm" onSubmit={submit}>
      <DraftBar keeper={keeper} onRestore={setContents} />
      <div className="mb-3">
        <label htmlFor="stylesheet_contentsStandard" className="form-label">{t('stylesheetEdit.source')}</label>
        <textarea rows={30} cols={80} spellCheck={false} value={contents}
                  onChange={(e) => { setContents(e.target.value); }}
                  {...fields.props('contents', 'form-control')} id="stylesheet_contentsStandard" name="contentsStandard" />
      </div>
      <button type="submit" className="btn btn-primary" disabled={save.isPending}>{t('generic.save')}</button>
      {view.customTheme ? null : (
        <>
          {' '}
          <button type="button" className="btn btn-secondary" onClick={() => { onAsk('revert'); }}>
            {t('stylesheetEdit.revert')}
          </button>
        </>
      )}
      {view.sharedThemeStylesheet ? (
        <>
          {' '}
          <button type="button" className="btn btn-danger" onClick={() => { onAsk('delete'); }}>
            {t('stylesheetEdit.delete')}
          </button>
        </>
      ) : null}
    </form>
  );
}
