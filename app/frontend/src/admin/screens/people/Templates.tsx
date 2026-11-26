import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useDeleteTemplate, useCreateTemplate, useTemplates, type TemplateView } from '../../api/people';
import { useWeblog } from '../../api/queries';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { EmptyState } from '../../components/EmptyState';
import { Markup, markupArg } from '../../components/Markup';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { bannerError, useHandle } from './shared';
import { RequireLevel } from '../../lib/permission';

/** ThemeTemplate.ComponentType's readable names, as the Action column printed them. */
export const ACTION_NAMES: Readonly<Record<string, string>> = {
  WEBLOG: 'Weblog', PERMALINK: 'Permalink', SEARCH: 'Search', TAGSINDEX: 'Tag Index', STYLESHEET: 'Stylesheet', CUSTOM: 'Custom'
};
export const actionName = (action: string): string => ACTION_NAMES[action] ?? action;

export function Templates() {
  const handle = useHandle();
  return <RequireLevel level="admin" handle={handle}><TemplatesBody handle={handle} /></RequireLevel>;
}

function TemplatesBody({ handle }: { handle: string }) {
  const t = useT();
  const list = useTemplates(handle);
  const weblog = useWeblog(handle);
  const create = useCreateTemplate(handle);
  const remove = useDeleteTemplate(handle);
  const [name, setName] = useState('');
  const [action, setAction] = useState('');
  const [doomed, setDoomed] = useState<TemplateView | null>(null);
  const [notice, setNotice] = useState<string[]>([]);
  const fields = useFieldErrors(create.error, (field) => (field === 'name' ? 'newTmplName' : 'newTmplAction'));

  const actions = list.data?.availableActions ?? [];
  const chosen = action !== '' && actions.includes(action) ? action : (actions[0] ?? '');
  const customTheme = weblog.data?.theme === 'custom';
  const theme = weblog.data?.theme;

  const add = (event: FormEvent) => {
    event.preventDefault();
    setNotice([]);
    remove.reset();
    create.mutate({ name, action: chosen }, {
      onSuccess: (made) => {
        setName('');
        setNotice([t('pagesForm.added', made.name)]);
      }
    });
  };
  const confirmDelete = () => {
    const target = doomed;
    setDoomed(null);
    if (target === null) {
      return;
    }
    setNotice([]);
    create.reset();
    remove.mutate(target.id, { onSuccess: () => { setNotice([t('pagesForm.removed', target.name)]); } });
  };

  return (
    <>
      <PageTitle>{t('pagesForm.title')}</PageTitle>
      <ProblemBanner messages={notice} error={bannerError(create.error ?? remove.error ?? list.error)} />
      <p className="subtitle"><Markup text={t('pagesForm.subtitle', markupArg(handle))} /></p>
      <p className="pagetip">{t('pagesForm.tip')}</p>
      {weblog.data !== undefined && !customTheme && theme !== undefined
        ? <p><Markup text={t('pagesForm.themesReminder', markupArg(theme))} /></p> : null}

      <form id="templateAdd" className="form-stacked mb-4" onSubmit={add}>
        <p className="sidebar-label">{t('pagesForm.addNewPage')}</p>
        <label htmlFor="newTmplName">{t('generic.name')}</label>
        <input type="text" maxLength={255} value={name} onChange={(e) => { setName(e.target.value); }}
               {...fields.props('name', 'form-control')} name="newTmplName" />
        {actions.length > 0 ? (
          <>
            <label htmlFor="newTmplAction">{t('pagesForm.action')}</label>
            <select value={chosen} onChange={(e) => { setAction(e.target.value); }}
                    {...fields.props('action', 'form-select')} name="newTmplAction">
              {actions.map((a) => <option key={a} value={a}>{actionName(a)}</option>)}
            </select>
          </>
        ) : null}
        <button type="submit" className="btn btn-secondary mt-2" disabled={create.isPending}>{t('pagesForm.add')}</button>
      </form>

      {list.data !== undefined && list.data.items.length === 0 ? (
        <EmptyState title={t('empty.templates.title')} body={t('empty.templates.body')} />
      ) : null}
      {list.data !== undefined && list.data.items.length > 0 ? (
        <div className="table-responsive">
          <table className="pressroomtable table table-striped align-middle">
            <thead>
              <tr>
                <th scope="col" style={{ width: '30%' }}>{t('generic.name')}</th>
                <th scope="col" style={{ width: '10%' }}>{t('pagesForm.action')}</th>
                <th scope="col" style={{ width: '50%' }}>{t('generic.description')}</th>
                <th scope="col" style={{ width: '10%' }} className="text-center">{t('pagesForm.remove')}</th>
              </tr>
            </thead>
            <tbody>
              {list.data.items.map((tpl) => (
                <tr key={tpl.id}>
                  <td>
                    {tpl.hidden
                      ? <span className="bi bi-eye-slash" role="img" aria-label={t('pageForm.hidden')} />
                      : <span className="bi bi-file-earmark-text" aria-hidden="true" />}
                    {' '}
                    <Link to={`/weblogs/${encodeURIComponent(handle)}/templates/${encodeURIComponent(tpl.id)}`}>{tpl.name}</Link>
                  </td>
                  <td>{actionName(tpl.action)}</td>
                  <td>{tpl.description}</td>
                  <td className="text-center">
                    {!tpl.required || !customTheme ? (
                      <button type="button" className="btn btn-link p-0 align-baseline border-0 template-delete-btn"
                              data-template-name={tpl.name}
                              aria-label={`${t('generic.delete')}: ${tpl.name}`}
                              onClick={() => { setDoomed(tpl); }}>
                        <span className="bi bi-trash" aria-hidden="true" />
                      </button>
                    ) : (
                      <span className="bi bi-lock" role="img" aria-label={t('pagesForm.cannotDelete')} />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {doomed === null ? null : (
        <ConfirmDialog title={`${t('pageRemove.confirm')}: '${doomed.name}'?`} confirmLabel={t('generic.delete')}
                       onConfirm={confirmDelete} onCancel={() => { setDoomed(null); }} />
      )}
    </>
  );
}

