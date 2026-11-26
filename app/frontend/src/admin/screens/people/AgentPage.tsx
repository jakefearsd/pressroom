import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router';
import { call } from '../../api/client';
import { ApiError } from '../../api/problem';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useApi } from '../../context';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { agentControl } from './AgentCreate';
import { useAgent, useAllWeblogs, useUserWeblogs, type AgentView } from './queries';
import { TokenSection } from './tokens';
import { useAction } from './useAction';
import { useFlash } from './UserList';
import { UserWeblogs } from './UserWeblogs';

/** An agent's page (Agent.jsp): details, status, weblogs, tokens. */
export function AgentPage() {
  const t = useT();
  const userName = useParams()['userName'] ?? '';
  const agent = useAgent(userName);
  if (agent.data === undefined) {
    return (
      <>
        <PageTitle>{t('agent.title')}</PageTitle>
        <ProblemBanner {...(agent.error instanceof ApiError && agent.error.status === 404
          ? { errors: [t('userAdmin.error.userNotFound')] } : { error: agent.error })} />
      </>
    );
  }
  return <AgentDetails key={userName} agent={agent.data} />;
}

function AgentDetails({ agent }: { agent: AgentView }) {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const flash = useFlash();
  const userName = agent.userName;
  const [form, setForm] = useState({ name: agent.name, notes: agent.notes ?? '', siteAdmin: agent.siteAdmin });
  const [messages, setMessages] = useState<string[]>(flash);
  const [confirming, setConfirming] = useState<'enable' | 'disable' | null>(null);
  const memberships = useUserWeblogs(userName);
  const everyWeblog = useAllWeblogs(agent.siteAdmin);
  // A site-admin agent may pin any weblog, any other one it belongs to (TokenPins.choices).
  const choices = agent.siteAdmin
    ? (everyWeblog.data ?? []).map((w) => ({ handle: w.handle ?? '', name: w.name ?? '' }))
    : (memberships.data ?? []).map((m) => ({ handle: m.handle, name: m.name }));

  const refresh = () => queries.invalidateQueries({ queryKey: ['agent', userName] });
  const save = useAction(async () => {
    await call(api.PATCH('/v1/admin/agents/{userName}', { params: { path: { userName } }, body: form }));
    await refresh();
  });
  const setEnabled = useAction(async (enabled: boolean) => {
    await call(api.PATCH('/v1/admin/agents/{userName}', { params: { path: { userName } }, body: { enabled } }));
    await refresh();
  });
  const fields = useFieldErrors(save.error, agentControl);

  const confirmed = (enable: boolean) => {
    setConfirming(null);
    save.reset();
    setMessages([]);
    void setEnabled.run(enable).then((outcome) => {
      if (outcome.ok) {
        setMessages([t(enable ? 'agent.enabled' : 'agent.disabled')]);
      }
    });
  };

  return (
    <div id="agentPage">
      <PageTitle>{t('agent.title')}</PageTitle>
      <ProblemBanner messages={messages} error={save.error ?? setEnabled.error} />
      <p className="subtitle">{t('agent.subtitle', userName)}</p>

      <h2 className="section-head">{t('agent.details')}</h2>
      <form className="form-stacked" id="agentDetailsForm"
            onSubmit={(event) => {
              event.preventDefault();
              setMessages([]);
              setEnabled.reset();
              void save.run().then((outcome) => { if (outcome.ok) { setMessages([t('agent.saved')]); } });
            }}>
        <div className="mb-3">
          <label htmlFor="agentUserName" className="form-label">{t('agent.userName')}</label>
          <input type="text" className="form-control data" id="agentUserName" readOnly value={userName} />
        </div>
        <div className="mb-3">
          <label htmlFor="bean_name" className="form-label">{t('agent.name')}</label>
          <input type="text" {...fields.props('name', 'form-control')} name="bean.name" maxLength={255} value={form.name}
                 onChange={(event) => { setForm({ ...form, name: event.target.value }); }} />
        </div>
        <div className="mb-3">
          <label htmlFor="bean_notes" className="form-label">{t('agent.notes')}</label>
          <textarea {...fields.props('notes', 'form-control')} name="bean.notes" rows={3} maxLength={1000} value={form.notes}
                    onChange={(event) => { setForm({ ...form, notes: event.target.value }); }} />
          <div className="form-text">{t('agent.notes.tip')}</div>
        </div>
        <div className="mb-3 form-check">
          <input type="checkbox" {...fields.props('siteAdmin', 'form-check-input')} name="bean.siteAdmin" checked={form.siteAdmin}
                 onChange={(event) => { setForm({ ...form, siteAdmin: event.target.checked }); }} />
          <label htmlFor="bean_siteAdmin" className="form-check-label">{t('agent.siteAdmin')}</label>
          <div className="form-text">{t('agent.siteAdmin.tip')}</div>
        </div>
        <button type="submit" className="btn btn-primary" id="agentSaveButton" disabled={save.pending}>{t('generic.save')}</button>
      </form>

      <h2 className="section-head">{t('agent.status')}</h2>
      <div className="d-flex flex-wrap align-items-center gap-3" id="agentEnabledForm">
        {agent.enabled ? (
          <>
            <span className="status-pill status-enabled">{t('userList.status.enabled')}</span>
            <button type="button" className="btn btn-danger" id="agentDisableButton"
                    onClick={() => { setConfirming('disable'); }}>{t('agent.disable')}</button>
            <span className="form-text">{t('agent.enabledNote')}</span>
          </>
        ) : (
          <>
            <span className="status-pill status-disabled">{t('userList.status.disabled')}</span>
            <button type="button" className="btn btn-secondary" id="agentEnableButton"
                    onClick={() => { setConfirming('enable'); }}>{t('agent.enable')}</button>
            <span className="form-text">{t('agent.disabledNote')}</span>
          </>
        )}
      </div>
      {confirming === null ? null : confirming === 'disable' ? (
        <ConfirmDialog title={t('agent.disable.confirm', userName)} confirmLabel={t('agent.disable')}
                       onCancel={() => { setConfirming(null); }} onConfirm={() => { confirmed(false); }} />
      ) : (
        <ConfirmDialog title={t('agent.enable.confirm', userName)} confirmLabel={t('agent.enable')} tone="primary"
                       onCancel={() => { setConfirming(null); }} onConfirm={() => { confirmed(true); }} />
      )}

      <UserWeblogs userName={userName} />

      <TokenSection listHeading={t('agent.tokens')} tokens={agent.tokens ?? []} choices={choices}
                    mint={(body) => call(api.POST('/v1/admin/agents/{userName}/tokens', { params: { path: { userName } }, body }))}
                    revoke={(id) => call(api.DELETE('/v1/admin/agents/{userName}/tokens/{id}', { params: { path: { userName, id } } }))}
                    onChanged={refresh} />
    </div>
  );
}
