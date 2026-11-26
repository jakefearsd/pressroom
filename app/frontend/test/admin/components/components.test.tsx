import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../src/admin/api/problem';
import { ConfirmDialog } from '../../../src/admin/components/ConfirmDialog';
import { DateTime, formatDateTime, TimeZoneContext } from '../../../src/admin/components/DateTime';
import { EmptyState } from '../../../src/admin/components/EmptyState';
import { PasswordField, PasswordFields, passwordMatchState } from '../../../src/admin/components/PasswordFields';
import { ProblemBanner, SUCCESS_DISMISS_MS } from '../../../src/admin/components/ProblemBanner';
import { SelectionBar } from '../../../src/admin/components/SelectionBar';
import { StatusPill } from '../../../src/admin/components/StatusPill';
import { renderInApp } from '../support';
import { useState } from 'react';

describe('StatusPill', () => {
  it('shows the translated status as the JSP pill markup', () => {
    renderInApp(<StatusPill status="PUBLISHED" />);
    const pill = screen.getByText('Published');
    expect(pill).toHaveClass('status-pill', 'status-published');
  });

  it('adds the time in the weblog zone when given one', () => {
    renderInApp(<StatusPill status="scheduled" when="2026-11-25T23:30:00Z" />);
    expect(screen.getByText('2026-11-25 23:30 UTC').closest('.status-when')).not.toBeNull();
  });
});

describe('EmptyState', () => {
  it('renders title, optional body and the call to action', () => {
    const { container, rerender } = render(<EmptyState title="Nothing yet" body="Write one."><a href="/x">Go</a></EmptyState>);
    expect(container.querySelector('.empty-state-title')).toHaveTextContent('Nothing yet');
    expect(container.querySelector('.empty-state-body')).toHaveTextContent('Write one.');
    expect(screen.getByRole('link', { name: 'Go' })).toBeInTheDocument();
    rerender(<EmptyState title="Just a title" />);
    expect(container.querySelector('.empty-state-body')).toBeNull();
  });
});

describe('SelectionBar', () => {
  it('is hidden at zero and counts with the bundle string otherwise', () => {
    const { container, rerender } = renderInApp(<SelectionBar count={0} formId="f"><button>Approve</button></SelectionBar>);
    const bar = container.querySelector('.selection-bar');
    expect(bar).toHaveAttribute('hidden');
    expect(bar).toHaveAttribute('data-selection-bar', 'f');
    rerender(<SelectionBar count={3}><button>Approve</button></SelectionBar>);
    expect(container.querySelector('.selection-bar')).not.toHaveAttribute('hidden');
  });

  it('shows the count and the actions', () => {
    const { container } = renderInApp(<SelectionBar count={3}><button>Approve</button></SelectionBar>);
    expect(container.querySelector('.selection-count')).toHaveTextContent('3 selected');
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(container.querySelector('.selection-bar')).not.toHaveAttribute('data-selection-bar');
  });
});

describe('DateTime', () => {
  it('prints yyyy-MM-dd HH:mm in the weblog zone with a UTC datetime attribute', () => {
    const { container } = render(<DateTime value="2026-07-01T23:30:00Z" timeZone="Europe/Berlin" />);
    const time = container.querySelector('time');
    expect(time).toHaveAttribute('datetime', '2026-07-01T23:30:00.000Z');
    expect(time).toHaveClass('data');
    expect(time).toHaveTextContent('2026-07-02 01:30');
  });

  it('takes the zone from context, and names UTC when there is none', () => {
    const { container, rerender } = render(
      <TimeZoneContext.Provider value="America/Los_Angeles"><DateTime value={0} /></TimeZoneContext.Provider>);
    expect(container.querySelector('time')).toHaveTextContent('1969-12-31 16:00');
    rerender(<DateTime value={new Date(Date.UTC(2026, 0, 2, 3, 4))} />);
    expect(container.querySelector('time')).toHaveTextContent('2026-01-02 03:04 UTC');
  });

  it('renders nothing for an absent or unparseable value', () => {
    const { container, rerender } = render(<DateTime value={null} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<DateTime value={undefined} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<DateTime value="not a date" />);
    expect(container).toBeEmptyDOMElement();
    expect(formatDateTime('2026-01-02T03:04:00Z', 'UTC')).toBe('2026-01-02 03:04');
  });
});

describe('ConfirmDialog', () => {
  it('is the modal shape, focuses Cancel, and confirms or cancels', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    renderInApp(<ConfirmDialog title="Delete all?" confirmLabel="Delete" onConfirm={onConfirm} onCancel={onCancel}>It's permanent.</ConfirmDialog>);

    const dialog = screen.getByRole('dialog', { name: 'Delete all?' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveClass('modal');
    expect(dialog.querySelector('.modal-title')).toHaveTextContent('Delete all?');
    expect(screen.getByText("It's permanent.")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Delete' })).toHaveClass('btn-danger');
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.keyDown(document, { key: 'Escape' });
    fireEvent.keyDown(document, { key: 'a' });
    expect(onCancel).toHaveBeenCalledTimes(2);
  });

  it('can be primary and returns focus to what had it', () => {
    function Host() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button onClick={() => { setOpen(true); }}>open</button>
          {open ? <ConfirmDialog title="Sure?" confirmLabel="Yes" tone="primary" onConfirm={() => { setOpen(false); }} onCancel={() => { setOpen(false); }} /> : null}
        </>
      );
    }
    renderInApp(<Host />);
    const opener = screen.getByText('open');
    opener.focus();
    fireEvent.click(opener);
    expect(screen.getByRole('button', { name: 'Yes' })).toHaveClass('btn-primary');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });
});

describe('passwordMatchState', () => {
  it('is empty until the confirm field has content, then exact', () => {
    expect(passwordMatchState('a', '')).toBe('empty');
    expect(passwordMatchState('a', 'a')).toBe('match');
    expect(passwordMatchState('a', 'A')).toBe('mismatch');
  });
});

describe('PasswordFields', () => {
  function Pair() {
    const [a, setA] = useState('');
    const [b, setB] = useState('');
    return (
      <form data-testid="form">
        <PasswordFields idPrefix="pw" newLabel="New password" confirmLabel="Confirm" newValue={a} confirmValue={b}
                        onNewChange={setA} onConfirmChange={setB} />
      </form>
    );
  }

  it('starts hidden, reveals and hides, and re-hides on submit', async () => {
    renderInApp(<Pair />);
    const field = screen.getByLabelText('New password');
    expect(field).toHaveAttribute('type', 'password');
    const [reveal] = screen.getAllByRole('button', { name: 'Show password' });
    await userEvent.click(reveal as HTMLElement);
    expect(field).toHaveAttribute('type', 'text');
    expect(screen.getAllByRole('button', { name: 'Hide password' })[0]).toHaveAttribute('aria-pressed', 'true');
    expect(reveal).toHaveAttribute('aria-controls', 'pwNew');

    fireEvent.submit(screen.getByTestId('form'));
    expect(field).toHaveAttribute('type', 'password');

    await userEvent.click(reveal as HTMLElement);
    act(() => { window.dispatchEvent(new Event('pagehide')); });
    expect(field).toHaveAttribute('type', 'password');
  });

  it('shows the live match line only under the confirm field', async () => {
    const { container } = renderInApp(<Pair />);
    expect(container.querySelector('#pwNew-match')).toBeNull();
    const line = container.querySelector('#pwConfirm-match');
    expect(line).toHaveTextContent('');
    expect(line).toHaveAttribute('aria-live', 'polite');

    await userEvent.type(screen.getByLabelText('New password'), 'secret');
    await userEvent.type(screen.getByLabelText('Confirm'), 'secre');
    expect(line).toHaveTextContent("Passwords don't match");
    expect(line).toHaveClass('is-mismatch');
    await userEvent.type(screen.getByLabelText('Confirm'), 't');
    expect(line).toHaveTextContent('Passwords match');
    expect(line).toHaveClass('is-match');
  });

  it('a single field takes a name and autocomplete', () => {
    renderInApp(<PasswordField id="p" name="current" value="" onChange={() => undefined} autoComplete="current-password" />);
    expect(document.getElementById('p')).toHaveAttribute('name', 'current');
    expect(document.getElementById('p')).toHaveAttribute('autocomplete', 'current-password');
  });
});

describe('ProblemBanner', () => {
  afterEach(() => { vi.useRealTimers(); });

  it('shows errors as tiles/messages.jsp does, dismissible, never auto-dismissed', async () => {
    vi.useFakeTimers();
    const { container } = renderInApp(<ProblemBanner errors={['Bad <b>x</b>']} />);
    const alert = container.querySelector('#errors');
    expect(alert).toHaveClass('alert', 'alert-danger', 'alert-dismissible');
    expect(alert).toHaveAttribute('role', 'alert');
    expect(alert?.querySelector('li')?.textContent).toBe('Bad <b>x</b>');
    act(() => { vi.advanceTimersByTime(SUCCESS_DISMISS_MS * 2); });
    expect(container.querySelector('#errors')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(container.querySelector('#errors')).toBeNull();
  });

  it('shows success messages and dismisses them after ten seconds', () => {
    vi.useFakeTimers();
    const { container } = renderInApp(<ProblemBanner messages={['Saved.']} />);
    expect(container.querySelector('#messages')).toHaveClass('alert-success');
    expect(container.querySelector('#messages')).toHaveAttribute('aria-live', 'polite');
    act(() => { vi.advanceTimersByTime(SUCCESS_DISMISS_MS); });
    expect(container.querySelector('#messages')).toBeNull();
  });

  it('turns an ApiError into its detail and field messages, and any Error into its message', () => {
    const problem = new ApiError(422, 'request.invalidFields', 'Fix these.', [{ field: 'title', message: 'Too long' }]);
    const { container, rerender } = renderInApp(<ProblemBanner error={problem} />);
    expect([...container.querySelectorAll('li')].map((li) => li.textContent)).toEqual(['Fix these.', 'Too long']);
    rerender(<ProblemBanner error={new Error('boom')} errors={['first']} />);
    expect([...container.querySelectorAll('li')].map((li) => li.textContent)).toEqual(['first', 'boom']);
  });

  it('renders nothing for no lines or a non-error', () => {
    const { container } = renderInApp(<ProblemBanner error="not an error" />);
    expect(container.querySelector('.alert')).toBeNull();
  });
});
