import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useParams } from 'react-router';
import { call } from '../api/client';
import { useWeblog, useWeblogThemes } from '../api/queries';
import type { components } from '../api/schema';
import { ProblemBanner } from '../components/ProblemBanner';
import { Rich, escapeArg } from '../components/Rich';
import { useApi, useBoot } from '../context';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';
import { useCanAdminister } from '../routes/access';

type WeblogThemes = components['schemas']['WeblogThemes'];
type Type = 'shared' | 'custom';

/** The theme screen: ThemeEdit.jsp's six state blocks, driven by the same "what is proposed" rule. */
export function ThemeEdit() {
  const t = useT();
  const handle = useParams()['handle'];
  const allowed = useCanAdminister(handle);
  const themes = useWeblogThemes(handle);
  return (
    <>
      <PageTitle>{t('themeEditor.title')}</PageTitle>
      {allowed === false ? <ProblemBanner errors={[t('api.problem.auth.forbidden')]} /> : null}
      {allowed === true && themes.error !== null ? <ProblemBanner error={themes.error} /> : null}
      {allowed === true && themes.data !== undefined && handle !== undefined
        ? <ThemeForm key={handle} handle={handle} data={themes.data} /> : null}
    </>
  );
}

function ThemeForm({ handle, data }: { handle: string; data: WeblogThemes }) {
  const t = useT();
  const api = useApi();
  const { contextPath } = useBoot();
  const queries = useQueryClient();
  const weblog = useWeblog(handle);
  const originalType: Type = data.custom ? 'custom' : 'shared';
  const originalThemeId = data.custom ? '' : (data.current ?? '');
  const cards = data.themes;
  const currentCard = cards.find((c) => c.id === data.current);

  const [type, setType] = useState<Type>(originalType);
  const [picked, setPicked] = useState<string | null>(null);
  const [proposedType, setProposedType] = useState<Type | ''>('');
  const [proposedTheme, setProposedTheme] = useState('');
  const [importTheme, setImportTheme] = useState(false);
  const [messages, setMessages] = useState<string[]>([]);

  // The checked card: the weblog's own theme, or the first card when it names none (custom, or a retired id).
  const selected = picked ?? (data.custom || currentCard === undefined ? (cards[0]?.id ?? '') : currentCard.id);
  const changed = (proposedTheme !== '' && proposedTheme !== originalThemeId)
    || (proposedType !== '' && proposedType !== originalType);
  const currentName = currentCard?.name ?? data.current ?? weblog.data?.theme ?? '';
  const nameOf = (id: string) => cards.find((c) => c.id === id)?.name ?? id;

  const reset = () => {
    setProposedTheme(originalThemeId);
    setProposedType(originalType);
    setType(originalType);
    setPicked(originalThemeId === '' ? null : originalThemeId);
    setImportTheme(false);
  };

  const apply = useMutation({
    mutationFn: async (): Promise<string[]> => {
      if (type === 'custom') {
        const importing = data.firstCustomization || importTheme;
        await call(api.POST('/v1/weblogs/{handle}/themes/custom', {
          params: { path: { handle } }, body: importing ? { importTheme: selected } : {}
        }));
        return [
          ...(importing ? [t('themeEditor.setCustomTheme.success', nameOf(selected))] : []),
          t('themeEditor.setCustomTheme.enabled'), t('themeEditor.setCustomTheme.instructions')
        ];
      }
      await call(api.PATCH('/v1/weblogs/{handle}', { params: { path: { handle } }, body: { theme: selected } }));
      return selected === data.current && !data.custom ? [] : [t('themeEditor.setTheme.success', nameOf(selected))];
    },
    onSuccess: async (done) => {
      setMessages(done);
      setProposedTheme('');
      setProposedType('');
      setImportTheme(false);
      await Promise.all([
        queries.invalidateQueries({ queryKey: ['weblogThemes', handle] }),
        queries.invalidateQueries({ queryKey: ['weblog', handle] })
      ]);
    },
    onError: () => { setMessages([]); }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    apply.mutate();
  };

  // The address readers reach the weblog at is the server's to say (custom domain, virtual host, site url).
  const blogUrl = weblog.data?.absoluteUrl;
  const viewBlog = blogUrl === undefined ? null : (
    <p><a href={blogUrl} target="_blank" rel="noopener noreferrer">{t('themeEditor.viewYourBlog')}</a></p>
  );
  const busy = apply.isPending;
  const buttons = (withPreview: boolean) => (
    <>
      {withPreview ? (
        <>
          <p><Rich text={t('themeEditor.previewDescription')} /></p>
          <button type="button" name="themePreview" className="btn btn-secondary"
                  onClick={() => {
                    window.open(`${contextPath}/pressroom-ui/authoring/preview/${encodeURIComponent(handle)}?theme=${encodeURIComponent(selected)}`);
                  }}>{t('themeEditor.preview')}</button>
          {' '}
        </>
      ) : null}
      <button type="submit" className="btn btn-primary" disabled={busy}>
        {busy ? t('themeEditor.saving') : t('themeEditor.save')}
      </button>
      {' '}
      <button type="button" className="btn btn-secondary" onClick={reset}>{t('generic.cancel')}</button>
    </>
  );

  const customRadio = (value: Type) => {
    setType(value);
    if (value === 'shared') {
      setProposedType('shared');
      setProposedTheme(selected);
    } else {
      setProposedTheme(originalThemeId);
      setProposedType('custom');
    }
  };

  return (
    <>
      <p className="subtitle"><Rich text={t('themeEditor.subtitle', escapeArg(handle))} /></p>
      {messages.length > 0 ? <ProblemBanner messages={messages} /> : null}
      <ProblemBanner error={apply.error} />
      <form onSubmit={submit} className="guard-submit">
        {data.customAllowed ? (
          <div className="row row-display-flex g-3">
            <div className="col-12 col-md-6">
              <div className="card">
                <div className={`card-body${type === 'shared' ? ' chooser-selected' : ''}`} id="sharedChooser">
                  <h2 className="section-head">
                    <input id="sharedRadio" type="radio" name="themeType" value="shared" checked={type === 'shared'}
                           onChange={() => { customRadio('shared'); }} />&nbsp;
                    <label htmlFor="sharedRadio">{t('themeEditor.sharedTheme')}</label>
                  </h2>
                  {t('themeEditor.sharedThemeDescription')}
                </div>
              </div>
            </div>
            <div className="col-12 col-md-6">
              <div className="card">
                <div className={`card-body${type === 'custom' ? ' chooser-selected' : ''}`} id="customChooser">
                  <h2 className="section-head">
                    <input id="customRadio" type="radio" name="themeType" value="custom" checked={type === 'custom'}
                           onChange={() => { customRadio('custom'); }} />&nbsp;
                    <label htmlFor="customRadio">{t('themeEditor.customTheme')}</label>
                  </h2>
                  {t('themeEditor.customThemeDescription')}
                </div>
              </div>
            </div>
          </div>
        ) : null}

        {type === 'shared' ? (
          <>
            {!changed ? (
              <div id="sharedNoChange">
                <p className="mt-3">
                  <Rich text={t('themeEditor.currentTheme', escapeArg(currentName))} />
                  {data.sharedStylesheetCustomised ? ` ${t('themeEditor.customStylesheetNote')}` : null}
                </p>
                {viewBlog}
              </div>
            ) : null}
            <div id="themeChooser">
              <p className="section-head" id="themeChooserLabel">{t('themeEditor.selectTheme')}</p>
              <div className="theme-cards" role="radiogroup" aria-labelledby="themeChooserLabel">
                {cards.map((card) => (
                  <label className="theme-card" key={card.id}>
                    {card.previewPath === undefined ? null : (
                      <img className="theme-card-thumb" alt="" src={`${contextPath}${card.previewPath}`} />
                    )}
                    <span className="theme-card-name">
                      <input className="theme-card-radio" type="radio" name="selectedThemeId" value={card.id}
                             checked={selected === card.id}
                             onChange={() => { setPicked(card.id); setProposedTheme(card.id); }} />
                      {card.name}
                    </span>
                    <span className="theme-card-desc">{card.description}</span>
                  </label>
                ))}
              </div>
            </div>
            {changed ? (
              <div id={originalType === 'shared' ? 'sharedChangeToShared' : 'customChangeToShared'}>
                <div className="alert alert-warning my-4">
                  {originalType === 'shared' ? t('themeEditor.proposedSharedThemeChange') : t('themeEditor.proposedChangeToShared')}
                </div>
                {buttons(true)}
              </div>
            ) : null}
          </>
        ) : !changed ? (
          <div id="customNoChange">
            <p className="mt-3">{t('themeEditor.youAreUsingACustomTheme')}</p>
            {viewBlog}
          </div>
        ) : (
          <div id="sharedChangeToCustom">
            <div className="alert alert-warning my-4">{t('themeEditor.proposedSharedChangeToCustom')}</div>
            {data.firstCustomization ? (
              <p>{t('themeEditor.importRequired')}</p>
            ) : (
              <>
                <p><Rich text={t('themeEditor.existingTemplatesWarning')} /></p>
                <label>
                  <input type="checkbox" name="importTheme" checked={importTheme}
                         onChange={(e) => { setImportTheme(e.target.checked); }} /> {t('themeEditor.importAndOverwriteTemplates')}
                </label>
              </>
            )}
            {buttons(false)}
          </div>
        )}
      </form>
    </>
  );
}
