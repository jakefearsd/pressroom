import { useState } from 'react';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DateTime } from '../../components/DateTime';
import { EmptyState } from '../../components/EmptyState';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useBoot } from '../../context';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { useT, type Translate } from '../../i18n/messages';
import type { components } from '../../api/schema';
import type { TokenView } from './queries';
import { useAction } from './useAction';

export type IssueRequest = components['schemas']['IssueRequest'];
export type IssuedToken = components['schemas']['IssuedToken'];
export interface Choice { handle: string; name: string }

/** The role's label; anything that is not READ or ADMIN reads as Write, as the JSP's choose did. */
export function roleLabel(role: string | undefined, t: Translate): string {
  return role === 'READ' ? t('apiTokens.role.read') : role === 'ADMIN' ? t('apiTokens.role.admin') : t('apiTokens.role.write');
}

/** Request field -> control id (the JSP's: the weblog field is "pin", never "weblog"). */
const CONTROL: Record<string, string> = { label: 'tokenLabel', role: 'tokenRole', weblog: 'tokenPin', expiresAt: 'tokenExpiry' };
const controlId = (field: string) => CONTROL[field] ?? field;

const DAY_MS = 86_400_000;

/** The JSP's expiry choice as the API's instant: 30, 90 or 365 days from now, or none. */
export function expiresAtFor(choice: string): string | undefined {
  const days = Number(choice);
  return choice === 'never' || !Number.isFinite(days) ? undefined : new Date(Date.now() + days * DAY_MS).toISOString();
}

/** An account's live tokens (TokenList.jsp). Revoke asks first. */
export function TokenList({ tokens, onRevoke }: { tokens: readonly TokenView[]; onRevoke: (id: string) => void }) {
  const t = useT();
  const [revoking, setRevoking] = useState<string | null>(null);
  if (tokens.length === 0) {
    return <EmptyState id="tokenListEmpty" title={t('apiTokens.none.title')} body={t('apiTokens.none.body')} />;
  }
  return (
    <>
      <div className="table-responsive">
        <table className="pressroomtable table" id="tokenListTable">
          <tbody>
            <tr>
              <th scope="col">{t('apiTokens.label')}</th>
              <th scope="col">{t('apiTokens.role')}</th>
              <th scope="col">{t('apiTokens.weblog')}</th>
              <th scope="col">{t('apiTokens.created')}</th>
              <th scope="col">{t('apiTokens.lastUsed')}</th>
              <th scope="col">{t('apiTokens.expiry')}</th>
              <th scope="col"><span className="visually-hidden">{t('apiTokens.revoke')}</span></th>
            </tr>
            {tokens.map((token) => (
              <tr key={token.id}>
                <td>{token.label}</td>
                <td>{roleLabel(token.scopeRole, t)}</td>
                <td>
                  {token.scopeWeblog === undefined || token.scopeWeblog === ''
                    ? t('apiTokens.weblog.all') : <span className="data">{token.scopeWeblog}</span>}
                </td>
                <td className="data"><DateTime value={token.created} /></td>
                <td className="data"><DateTime value={token.lastUsedAt} /></td>
                <td className="data">{token.expiresAt === undefined ? t('apiTokens.expiry.never') : <DateTime value={token.expiresAt} />}</td>
                <td>
                  <button type="button" className="btn btn-secondary btn-sm"
                          onClick={() => { setRevoking(token.id ?? ''); }}>{t('apiTokens.revoke')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {revoking === null ? null : (
        <ConfirmDialog title={t('apiTokens.revoke.confirm')} confirmLabel={t('apiTokens.revoke')}
                       onCancel={() => { setRevoking(null); }}
                       onConfirm={() => { const id = revoking; setRevoking(null); onRevoke(id); }} />
      )}
    </>
  );
}

/** The token form (TokenForm.jsp), shared by the agent page and Profile -> API tokens. */
export function TokenForm({ choices, mint, onIssued }: {
  choices: readonly Choice[];
  mint: (body: IssueRequest) => Promise<IssuedToken>;
  onIssued: (issued: IssuedToken, label: string) => void;
}) {
  const t = useT();
  const [label, setLabel] = useState('');
  const [role, setRole] = useState('POST');
  const [pin, setPin] = useState('');
  const [expiry, setExpiry] = useState('90');
  const action = useAction(mint);
  const fields = useFieldErrors(action.error, controlId);

  const submit = async () => {
    const expiresAt = expiresAtFor(expiry);
    const outcome = await action.run({
      label, role,
      ...(pin === '' ? {} : { weblog: pin }),
      ...(expiresAt === undefined ? {} : { expiresAt })
    });
    if (outcome.ok) {
      onIssued(outcome.value, label);
    }
  };
  return (
    <>
      <ProblemBanner error={action.error} />
      <form className="form-stacked" id="tokenForm" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <div className="mb-3">
          <label htmlFor="tokenLabel" className="form-label">{t('apiTokens.label')}</label>
          <input type="text" {...fields.props('label', 'form-control')} name="label" maxLength={255} value={label}
                 onChange={(event) => { setLabel(event.target.value); }} />
        </div>
        <div className="mb-3">
          <label htmlFor="tokenRole" className="form-label">{t('apiTokens.role')}</label>
          <select {...fields.props('role', 'form-select')} name="role" value={role}
                  onChange={(event) => { setRole(event.target.value); }}>
            <option value="READ">{t('apiTokens.role.read')}</option>
            <option value="POST">{t('apiTokens.role.write')}</option>
            <option value="ADMIN">{t('apiTokens.role.admin')}</option>
          </select>
        </div>
        <div className="mb-3">
          <label htmlFor="tokenPin" className="form-label">{t('apiTokens.weblog')}</label>
          <select {...fields.props('weblog', 'form-select')} name="pin" value={pin}
                  onChange={(event) => { setPin(event.target.value); }}>
            <option value="">{t('apiTokens.weblog.all')}</option>
            {choices.map((c) => <option key={c.handle} value={c.handle}>{c.name} ({c.handle})</option>)}
          </select>
        </div>
        <div className="mb-3">
          <label htmlFor="tokenExpiry" className="form-label">{t('apiTokens.expiry')}</label>
          <select {...fields.props('expiresAt', 'form-select')} name="expiry" value={expiry}
                  onChange={(event) => { setExpiry(event.target.value); }}>
            <option value="30">{t('apiTokens.expiry.30')}</option>
            <option value="90">{t('apiTokens.expiry.90')}</option>
            <option value="365">{t('apiTokens.expiry.365')}</option>
            <option value="never">{t('apiTokens.expiry.never')}</option>
          </select>
        </div>
        <button type="submit" className="btn btn-secondary" id="tokenCreateButton" disabled={action.pending}>
          {t('apiTokens.create')}
        </button>
      </form>
    </>
  );
}

/**
 * The one-time reveal (TokenReveal.jsp). The secret is a prop of this component and lives
 * nowhere else: not in storage, not in a query or mutation cache; leaving the component
 * (Done, or navigating away) forgets it, and a visit with nothing to show says "already shown".
 */
export function TokenReveal({ secret, label, onDone }: { secret: string; label: string; onDone: () => void }) {
  const t = useT();
  const boot = useBoot();
  const [copied, setCopied] = useState<string | null>(null);
  const mcpUrl = new URL(`${boot.contextPath}/api/mcp`, window.location.href).href;
  const snippet = `claude mcp add --transport http pressroom ${mcpUrl} --header "Authorization: Bearer ${secret}"`;
  const copy = (which: string, text: string) => {
    void navigator.clipboard.writeText(text).then(() => {
      setCopied(which);
      setTimeout(() => { setCopied(null); }, 1500);
    });
  };
  return (
    <div id="tokenRevealPage">
      <p className="subtitle">{t('apiTokens.reveal.once')}</p>
      <p>{t('apiTokens.label')}: <strong>{label}</strong></p>
      <div className="mb-3">
        <label htmlFor="revealedToken" className="form-label">{t('apiTokens.reveal.token')}</label>
        <div className="input-group">
          <input type="text" className="form-control data" id="revealedToken" readOnly autoComplete="off" value={secret} />
          <button type="button" className={`btn btn-secondary clipbutton${copied === 'token' ? ' copied' : ''}`}
                  onClick={() => { copy('token', secret); }}>{t('apiTokens.reveal.copy')}</button>
        </div>
      </div>
      <div className="mb-3">
        <label htmlFor="mcpSnippet" className="form-label">{t('apiTokens.reveal.mcp')}</label>
        <textarea className="form-control data" id="mcpSnippet" rows={3} readOnly value={snippet} />
        <button type="button" className={`btn btn-secondary clipbutton mt-2${copied === 'mcp' ? ' copied' : ''}`}
                onClick={() => { copy('mcp', snippet); }}>{t('apiTokens.reveal.copy')}</button>
      </div>
      <p className="mt-3">
        <button type="button" className="btn btn-primary" id="tokenRevealDone" onClick={onDone}>{t('apiTokens.reveal.done')}</button>
      </p>
    </div>
  );
}

/**
 * An account's token section: the live list, the new-token form and, right after a mint,
 * the one-time reveal in their place. Used by the agent page and Profile -> API tokens.
 */
export function TokenSection({ listHeading, tokens, loadError, choices, mint, revoke, onChanged }: {
  listHeading: string;
  tokens: readonly TokenView[] | undefined;
  loadError?: unknown;
  choices: readonly Choice[];
  mint: (body: IssueRequest) => Promise<IssuedToken>;
  revoke: (id: string) => Promise<void>;
  onChanged: () => Promise<void>;
}) {
  const t = useT();
  const [revealed, setRevealed] = useState<{ secret: string; label: string } | null>(null);
  const [messages, setMessages] = useState<string[]>([]);
  const revoking = useAction(async (id: string) => { await revoke(id); await onChanged(); });

  if (revealed !== null) {
    return (
      <TokenReveal secret={revealed.secret} label={revealed.label}
                   onDone={() => { setRevealed(null); void onChanged(); }} />
    );
  }
  return (
    <>
      <h2 className="section-head">{listHeading}</h2>
      <ProblemBanner messages={messages} error={revoking.error ?? loadError} />
      {tokens === undefined ? null : (
        <TokenList tokens={tokens} onRevoke={(id) => {
          setMessages([]);
          void revoking.run(id).then((outcome) => { if (outcome.ok) { setMessages([t('apiTokens.revoked')]); } });
        }} />
      )}
      <h2 className="section-head">{t('apiTokens.new')}</h2>
      <TokenForm choices={choices} mint={mint} onIssued={(issued, label) => {
        setRevealed({ secret: issued.token ?? '', label });
      }} />
    </>
  );
}
