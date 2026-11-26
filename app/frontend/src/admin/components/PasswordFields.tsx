import { useEffect, useRef, useState } from 'react';
import { useT } from '../i18n/messages';

export type MatchState = 'empty' | 'match' | 'mismatch';

/** What the match line under a confirm field says: nothing until it has content, then exact comparison. */
export function passwordMatchState(password: string, confirm: string): MatchState {
  if (confirm === '') {
    return 'empty';
  }
  return password === confirm ? 'match' : 'mismatch';
}

interface FieldProps {
  id: string;
  name?: string;
  value: string;
  onChange: (value: string) => void;
  autoComplete?: string;
  /** On a confirm field: the new-password value to compare with; shows the live match line. */
  matchWith?: string;
}

/**
 * One password field with the show/hide button and, on a confirm field, the live match
 * line: the markup pressroom-password.js built. Every field starts hidden and goes back to
 * type=password when its form submits or the page is left, so password managers still see
 * a password field.
 */
export function PasswordField({ id, name, value, onChange, autoComplete, matchWith }: FieldProps) {
  const t = useT();
  const [shown, setShown] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const hide = () => { setShown(false); };
    const form = input.current?.form;
    form?.addEventListener('submit', hide, true);
    window.addEventListener('pagehide', hide);
    return () => {
      form?.removeEventListener('submit', hide, true);
      window.removeEventListener('pagehide', hide);
    };
  }, []);

  const state = matchWith === undefined ? 'empty' : passwordMatchState(matchWith, value);
  return (
    <>
      <div className="input-group">
        <input ref={input} id={id} name={name ?? id} type={shown ? 'text' : 'password'}
               className="form-control" value={value} autoComplete={autoComplete}
               onChange={(event) => { onChange(event.target.value); }} />
        <button type="button" className="btn btn-outline-secondary password-reveal"
                aria-controls={id} aria-pressed={shown}
                onClick={() => { setShown((on) => !on); }}>
          {shown ? t('passwordField.hide') : t('passwordField.show')}
        </button>
      </div>
      {matchWith === undefined ? null : (
        <div id={`${id}-match`}
             className={`form-text password-match${state === 'match' ? ' is-match' : ''}${state === 'mismatch' ? ' is-mismatch' : ''}`}
             aria-live="polite">
          {state === 'match' ? t('passwordField.match') : state === 'mismatch' ? t('passwordField.mismatch') : ''}
        </div>
      )}
    </>
  );
}

interface PairProps {
  idPrefix: string;
  newLabel: string;
  confirmLabel: string;
  newValue: string;
  confirmValue: string;
  onNewChange: (value: string) => void;
  onConfirmChange: (value: string) => void;
}

/** A new-password field and its confirm field with the match line. */
export function PasswordFields(p: PairProps) {
  return (
    <>
      <div className="mb-3">
        <label htmlFor={`${p.idPrefix}New`} className="form-label">{p.newLabel}</label>
        <PasswordField id={`${p.idPrefix}New`} value={p.newValue} onChange={p.onNewChange} autoComplete="new-password" />
      </div>
      <div className="mb-3">
        <label htmlFor={`${p.idPrefix}Confirm`} className="form-label">{p.confirmLabel}</label>
        <PasswordField id={`${p.idPrefix}Confirm`} value={p.confirmValue} onChange={p.onConfirmChange}
                       autoComplete="new-password" matchWith={p.newValue} />
      </div>
    </>
  );
}
