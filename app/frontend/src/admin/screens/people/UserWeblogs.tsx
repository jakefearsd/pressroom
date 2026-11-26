import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { call } from '../../api/client';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { EmptyState } from '../../components/EmptyState';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useApi } from '../../context';
import { useT } from '../../i18n/messages';
import { useMeta } from '../../api/queries';
import { useAllWeblogs, useUserWeblogs } from './queries';
import { useAction } from './useAction';

type Level = 'ADMIN' | 'POST' | 'EDIT_DRAFT';

/** The Members page's levels and labels. */
function LevelOptions() {
  const t = useT();
  return (
    <>
      <option value="ADMIN">{t('memberPermissions.administrator')}</option>
      <option value="POST">{t('memberPermissions.author')}</option>
      <option value="EDIT_DRAFT">{t('memberPermissions.limited')}</option>
    </>
  );
}

/**
 * The Weblogs section of an account's page, person or agent (UserWeblogs.jsp): the
 * memberships with change and remove (remove asks first) and the add form while group
 * blogging is on and there is a weblog left to add. The API field is `weblogHandle` in the
 * path and `handle` in the body, never `weblog` (which names the action weblog).
 */
export function UserWeblogs({ userName }: { userName: string }) {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const memberships = useUserWeblogs(userName);
  const meta = useMeta();
  const everyWeblog = useAllWeblogs();
  const [levels, setLevels] = useState<Record<string, string>>({});
  const [addHandle, setAddHandle] = useState<string | undefined>(undefined);
  const [addLevel, setAddLevel] = useState('POST');
  const [removing, setRemoving] = useState<string | null>(null);
  const [messages, setMessages] = useState<string[]>([]);

  const act = useAction(async (op: () => Promise<string>) => {
    const message = await op();
    await queries.invalidateQueries({ queryKey: ['userWeblogs', userName] });
    return message;
  });
  const run = async (op: () => Promise<string>) => {
    setMessages([]);
    const outcome = await act.run(op);
    if (outcome.ok) {
      setMessages([outcome.value]);
    }
  };

  const rows = memberships.data ?? [];
  const held = new Set(rows.map((m) => m.handle));
  const available = (everyWeblog.data ?? []).filter((w) => w.handle !== undefined && !held.has(w.handle));
  const groupBlogging = meta.data?.features.groupBlogging === true;
  const chosen = addHandle !== undefined && available.some((w) => w.handle === addHandle) ? addHandle : available[0]?.handle;

  const change = (handle: string, level: string) => run(async () => {
    await call(api.PATCH('/v1/admin/users/{userName}/weblogs/{weblogHandle}', {
      params: { path: { userName, weblogHandle: handle } }, body: { permission: level as Level }
    }));
    return t('userWeblogs.changed', handle);
  });
  const remove = (handle: string) => run(async () => {
    await call(api.DELETE('/v1/admin/users/{userName}/weblogs/{weblogHandle}', {
      params: { path: { userName, weblogHandle: handle } }
    }));
    return t('userWeblogs.removed', handle);
  });
  const grant = (handle: string, level: string) => run(async () => {
    await call(api.POST('/v1/admin/users/{userName}/weblogs', {
      params: { path: { userName } }, body: { handle, permission: level as Level }
    }));
    return t('userWeblogs.granted', handle);
  });

  return (
    <section id="userWeblogsSection" className="mt-4">
      <h2 className="section-head">{t('userAdmin.userWeblogs')}</h2>
      <ProblemBanner messages={messages} error={act.error ?? memberships.error} />

      {rows.length > 0 ? (
        <div className="table-responsive">
          <table className="pressroomtable table" id="userWeblogsTable">
            <tbody>
              <tr>
                <th scope="col">{t('generic.name')}</th>
                <th scope="col">{t('userWeblogs.level')}</th>
                <th scope="col"><span className="visually-hidden">{t('userWeblogs.remove')}</span></th>
              </tr>
              {rows.map((m) => (
                <tr key={m.handle}>
                  <td>
                    <Link to={`/weblogs/${encodeURIComponent(m.handle)}`}>{m.name === '' ? m.handle : m.name}</Link>
                    {' '}<span className="data">{m.handle}</span>
                  </td>
                  <td>
                    <form className="d-flex flex-wrap gap-2 align-items-center"
                          onSubmit={(event) => { event.preventDefault(); void change(m.handle, levels[m.handle] ?? m.permission); }}>
                      <label className="visually-hidden" htmlFor={`level-${m.handle}`}>{t('userWeblogs.level')}</label>
                      <select className="form-select form-select-sm" id={`level-${m.handle}`}
                              value={levels[m.handle] ?? m.permission}
                              onChange={(event) => { setLevels({ ...levels, [m.handle]: event.target.value }); }}>
                        <LevelOptions />
                      </select>
                      <button type="submit" className="btn btn-secondary btn-sm">{t('userWeblogs.change')}</button>
                    </form>
                  </td>
                  <td>
                    <button type="button" className="btn btn-secondary btn-sm"
                            onClick={() => { setRemoving(m.handle); }}>{t('userWeblogs.remove')}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : memberships.data === undefined ? null : (
        <EmptyState title={t('empty.userWeblogs.title')}
                    {...(groupBlogging && available.length > 0 ? { body: t('userWeblogs.empty.body') } : {})} />
      )}

      {meta.data === undefined || everyWeblog.data === undefined ? null : !groupBlogging ? (
        <p className="form-text" id="userWeblogsGroupBloggingOff">{t('userWeblogs.groupBloggingOff')}</p>
      ) : available.length === 0 ? (
        <p className="form-text">{t('userWeblogs.noOtherWeblogs')}</p>
      ) : (
        <form className="d-flex flex-wrap align-items-end gap-2 mt-3" id="userWeblogsAddForm"
              onSubmit={(event) => { event.preventDefault(); if (chosen !== undefined) { void grant(chosen, addLevel); } }}>
          <div>
            <label htmlFor="userWeblogsAddHandle" className="form-label">{t('userWeblogs.weblog')}</label>
            <select className="form-select" id="userWeblogsAddHandle" value={chosen}
                    onChange={(event) => { setAddHandle(event.target.value); }}>
              {available.map((w) => <option key={w.handle} value={w.handle}>{w.name} ({w.handle})</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="userWeblogsAddLevel" className="form-label">{t('userWeblogs.level')}</label>
            <select className="form-select" id="userWeblogsAddLevel" value={addLevel}
                    onChange={(event) => { setAddLevel(event.target.value); }}>
              <LevelOptions />
            </select>
          </div>
          <button type="submit" className="btn btn-secondary" id="userWeblogsAddButton">{t('userWeblogs.add')}</button>
        </form>
      )}

      {removing === null ? null : (
        <ConfirmDialog title={t('userWeblogs.remove.confirm', removing)} confirmLabel={t('userWeblogs.remove')}
                       onCancel={() => { setRemoving(null); }}
                       onConfirm={() => { const handle = removing; setRemoving(null); void remove(handle); }} />
      )}
    </section>
  );
}
