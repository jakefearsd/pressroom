import { useState, type FormEvent } from 'react';
import { runPool } from '../../api/pool';
import { useMemberActions, useMembers, type MemberPermission, type MemberView } from '../../api/people';
import { useMeta } from '../../api/queries';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { EmptyState } from '../../components/EmptyState';
import { Markup, markupArg } from '../../components/Markup';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { PageTitle } from '../../frame/PageTitle';
import { useBoot } from '../../context';
import { useT } from '../../i18n/messages';
import { RequireLevel, bannerError, failureLine, useHandle } from './shared';

const BULK_CONCURRENCY = 4;

type Choice = MemberPermission | 'REMOVE';

/** The table's house order: Administrator, Author, Limited. */
const LEVELS: readonly { value: MemberPermission; key: string }[] = [
  { value: 'ADMIN', key: 'memberPermissions.administrator' },
  { value: 'POST', key: 'memberPermissions.author' },
  { value: 'EDIT_DRAFT', key: 'memberPermissions.limited' }
];

export function Members() {
  const handle = useHandle();
  return <RequireLevel level="admin" handle={handle}><MembersBody handle={handle} /></RequireLevel>;
}

function MembersBody({ handle }: { handle: string }) {
  const t = useT();
  const { user } = useBoot();
  const meta = useMeta();
  const members = useMembers(handle);
  const actions = useMemberActions(handle);
  const [userName, setUserName] = useState('');
  const [permission, setPermission] = useState<MemberPermission>('POST');
  const [picked, setPicked] = useState<Readonly<Record<string, Choice>>>({});
  const [confirming, setConfirming] = useState(false);
  const [notice, setNotice] = useState<string[]>([]);
  const [problems, setProblems] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const fields = useFieldErrors(actions.grant.error, (field) => (field === 'userName' ? 'grantUserName' : 'grantPermission'));

  const rows = members.data ?? [];
  const choiceOf = (m: MemberView): Choice => picked[m.userName] ?? m.permission;
  const changes = rows.filter((m) => choiceOf(m) !== m.permission);
  const removing = changes.some((m) => picked[m.userName] === 'REMOVE');
  const groupBlogging = meta.data?.features.groupBlogging === true;

  const grant = (event: FormEvent) => {
    event.preventDefault();
    setNotice([]);
    setProblems([]);
    actions.grant.mutate({ userName, permission }, {
      onSuccess: (made) => {
        setUserName('');
        setNotice([t('memberPermissions.memberChanged', made.userName)]);
      }
    });
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (removing) {
      setConfirming(true);
    } else {
      void saveAll();
    }
  };

  const saveAll = async () => {
    setConfirming(false);
    setNotice([]);
    setProblems([]);
    if (changes.length === 0) {
      setNotice([t('memberPermissions.noneChanged')]);
      return;
    }
    const remaining = rows.filter((m) => choiceOf(m) === 'ADMIN');
    if (remaining.length === 0) {
      setProblems([t('memberPermissions.oneAdminRequired')]);
      return;
    }
    actions.grant.reset();
    setSaving(true);
    const failures: string[] = [];
    // Promotions to admin first: demoting the old admin before the new one exists would trip the last-admin rule.
    const phases = [changes.filter((m) => picked[m.userName] === 'ADMIN'), changes.filter((m) => picked[m.userName] !== 'ADMIN')];
    const done = { removed: 0, changed: 0 };
    for (const phase of phases) {
      const failed = await runPool(phase, BULK_CONCURRENCY, async (m) => {
        const to = picked[m.userName];
        if (to === 'REMOVE') {
          await actions.remove(m.userName);
          done.removed++;
        } else if (to !== undefined) {
          await actions.change(m.userName, to);
          done.changed++;
        }
      });
      failures.push(...failed.map((f) => failureLine(f.item.userName, f.error)));
    }
    await actions.refresh();
    setPicked({});
    setSaving(false);
    const said: string[] = [];
    if (done.removed > 0) {
      said.push(t('memberPermissions.membersRemoved', String(done.removed)));
    }
    if (done.changed > 0) {
      said.push(t('memberPermissions.membersChanged', String(done.changed)));
    }
    setNotice(said);
    setProblems(failures);
  };

  return (
    <>
      <PageTitle>{t('memberPermissions.title')}</PageTitle>
      <ProblemBanner messages={notice} errors={problems} error={bannerError(actions.grant.error ?? members.error)} />
      <p className="subtitle"><Markup text={t('memberPermissions.subtitle', markupArg(handle))} /></p>
      <p>{t('memberPermissions.description')}</p>

      {groupBlogging ? (
        <form className="d-flex flex-wrap align-items-end gap-2 mb-4" onSubmit={grant}>
          <div>
            <label htmlFor="grantUserName" className="form-label">{t('memberPermissions.userName')}</label>
            <input type="text" size={30} maxLength={255} value={userName}
                   onChange={(e) => { setUserName(e.target.value); }}
                   {...fields.props('userName', 'form-control')} name="userName" />
          </div>
          <div>
            <label htmlFor="grantPermission" className="form-label">{t('yourWebsites.permission')}</label>
            <select value={permission} onChange={(e) => { setPermission(e.target.value as MemberPermission); }}
                    {...fields.props('permission', 'form-select')} name="permissionString">
              {LEVELS.map((level) => <option key={level.value} value={level.value}>{t(level.key)}</option>)}
            </select>
          </div>
          <button type="submit" className="btn btn-secondary" disabled={actions.grant.isPending}>
            {t('memberPermissions.add')}
          </button>
        </form>
      ) : null}

      <form id="memberPermissionsForm" onSubmit={submit}>
        {members.data !== undefined && rows.length === 0 ? (
          <EmptyState title={t('empty.members.title')} body={t('empty.members.body')} />
        ) : null}
        {rows.length > 0 ? (
          <>
            <div className="table-responsive">
              <table className="pressroomtable table table-striped">
                <thead>
                  <tr>
                    <th scope="col" className="pressroomtable" style={{ width: '20%' }}>{t('memberPermissions.userName')}</th>
                    {LEVELS.map((level) => (
                      <th key={level.value} scope="col" className="pressroomtable" style={{ width: '20%' }}>{t(level.key)}</th>
                    ))}
                    <th scope="col" className="pressroomtable" style={{ width: '20%' }}>{t('memberPermissions.remove')}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((m) => {
                    const self = m.userName === user.userName;
                    const options: { value: Choice; key: string; locked: boolean }[] = [
                      ...LEVELS.map((level) => ({ ...level, locked: self && level.value !== 'ADMIN' })),
                      { value: 'REMOVE', key: 'memberPermissions.remove', locked: self }
                    ];
                    return (
                      <tr key={m.userName}>
                        <td className="pressroomtable">
                          <span className="bi bi-person" aria-hidden="true" /> {m.userName}
                        </td>
                        {options.map((o) => (
                          <td key={o.value} className="pressroomtable">
                            <input type="radio" name={`perm-${m.userName}`} value={o.value} disabled={o.locked}
                                   checked={choiceOf(m) === o.value}
                                   aria-label={`${t(o.key)}: ${m.userName}`}
                                   onChange={() => { setPicked((p) => ({ ...p, [m.userName]: o.value })); }} />
                          </td>
                        ))}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <div className="control">
              <button type="submit" className="btn btn-primary" disabled={saving}>{t('generic.save')}</button>
            </div>
          </>
        ) : null}
      </form>
      {confirming ? (
        <ConfirmDialog title={t('memberPermissions.confirmRemove')} confirmLabel={t('memberPermissions.remove')}
                       onConfirm={() => { void saveAll(); }} onCancel={() => { setConfirming(false); }} />
      ) : null}
    </>
  );
}
