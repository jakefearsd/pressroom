import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { flashState } from '../../session/flash';
import { call } from '../../api/client';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useApi } from '../../context';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { useAction } from './useAction';

/** Request field -> control id (the JSP's bean_* ids). */
export const AGENT_CONTROL: Record<string, string> = {
  userName: 'bean_userName', name: 'bean_name', emailAddress: 'bean_emailAddress', notes: 'bean_notes', siteAdmin: 'bean_siteAdmin'
};
export const agentControl = (field: string) => AGENT_CONTROL[field] ?? field;

/** New agent (AgentCreate.jsp): an account for software, no password, optional email. */
export function AgentCreate() {
  const t = useT();
  const api = useApi();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const [form, setForm] = useState({ userName: '', name: '', emailAddress: '', notes: '', siteAdmin: false });
  const create = useAction(async () => {
    await call(api.POST('/v1/admin/agents', {
      body: {
        userName: form.userName, name: form.name, siteAdmin: form.siteAdmin,
        ...(form.emailAddress === '' ? {} : { emailAddress: form.emailAddress }),
        ...(form.notes === '' ? {} : { notes: form.notes })
      }
    }));
    await queries.invalidateQueries({ queryKey: ['users'] });
  });
  const fields = useFieldErrors(create.error, agentControl);

  return (
    <>
      <PageTitle>{t('agent.create.title')}</PageTitle>
      <ProblemBanner error={create.error} />
      <p className="subtitle">{t('agent.create.subtitle')}</p>
      <form className="form-stacked" id="agentCreateForm"
            onSubmit={(event) => {
              event.preventDefault();
              void create.run().then((outcome) => {
                if (outcome.ok) {
                  void navigate(`/admin/agents/${encodeURIComponent(form.userName)}`,
                    { state: flashState(t('agent.created', form.userName)) });
                }
              });
            }}>
        <div className="mb-3">
          <label htmlFor="bean_userName" className="form-label">{t('agent.userName')}</label>
          <input type="text" {...fields.props('userName', 'form-control')} name="bean.userName" maxLength={255} autoFocus
                 value={form.userName} onChange={(event) => { setForm({ ...form, userName: event.target.value }); }} />
          <div className="form-text">{t('agent.userName.tip')}</div>
        </div>
        <div className="mb-3">
          <label htmlFor="bean_name" className="form-label">{t('agent.name')}</label>
          <input type="text" {...fields.props('name', 'form-control')} name="bean.name" maxLength={255}
                 value={form.name} onChange={(event) => { setForm({ ...form, name: event.target.value }); }} />
        </div>
        <div className="mb-3">
          <label htmlFor="bean_emailAddress" className="form-label">{t('agent.email')}</label>
          <input type="text" {...fields.props('emailAddress', 'form-control')} name="bean.emailAddress" maxLength={255}
                 value={form.emailAddress} onChange={(event) => { setForm({ ...form, emailAddress: event.target.value }); }} />
          <div className="form-text">{t('agent.email.tip')}</div>
        </div>
        <div className="mb-3">
          <label htmlFor="bean_notes" className="form-label">{t('agent.notes')}</label>
          <textarea {...fields.props('notes', 'form-control')} name="bean.notes" rows={3} maxLength={1000}
                    value={form.notes} onChange={(event) => { setForm({ ...form, notes: event.target.value }); }} />
          <div className="form-text">{t('agent.notes.tip')}</div>
        </div>
        <div className="mb-3 form-check">
          <input type="checkbox" {...fields.props('siteAdmin', 'form-check-input')} name="bean.siteAdmin" checked={form.siteAdmin}
                 onChange={(event) => { setForm({ ...form, siteAdmin: event.target.checked }); }} />
          <label htmlFor="bean_siteAdmin" className="form-check-label">{t('agent.siteAdmin')}</label>
          <div className="form-text">{t('agent.siteAdmin.tip')}</div>
        </div>
        <button type="submit" className="btn btn-primary" id="agentCreateButton" disabled={create.pending}>{t('agent.create.button')}</button>
        {' '}<Link to="/admin/users" className="btn btn-secondary">{t('generic.cancel')}</Link>
      </form>
    </>
  );
}
