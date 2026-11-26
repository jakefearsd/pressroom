import { fireEvent, screen } from '@testing-library/react';
import { useState } from 'react';
import { TimeZoneContext } from '../../../src/admin/components/DateTime';
import { APP_DRAFT_PREFIX } from '../../../src/admin/session/drafts';
import { DraftBar } from '../../../src/admin/session/DraftBar';
import { useDraftKeeper } from '../../../src/admin/session/useDraftKeeper';
import { renderInApp } from '../support';

const KEY = `${APP_DRAFT_PREFIX}jake:entry-9`;

function Editor() {
  const [text, setText] = useState('server text');
  const keeper = useDraftKeeper('entry-9', text);
  return (
    <>
      <DraftBar keeper={keeper} onRestore={setText} />
      <p data-testid="text">{text}</p>
    </>
  );
}

beforeEach(() => { window.localStorage.clear(); });

describe('DraftBar', () => {
  it('offers nothing when there is no draft', () => {
    renderInApp(<Editor />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('offers the unsaved text with its time, and restore puts it back', () => {
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'lost work', at: Date.parse('2026-11-25T10:15:00Z') }));
    renderInApp(<TimeZoneContext.Provider value="UTC"><Editor /></TimeZoneContext.Provider>);
    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes from 2026-11-25 10:15.');
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }));
    expect(screen.getByTestId('text')).toHaveTextContent('lost work');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('discard throws it away', () => {
    window.localStorage.setItem(KEY, JSON.stringify({ text: 'lost work', at: Date.now() }));
    renderInApp(<Editor />);
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(screen.getByTestId('text')).toHaveTextContent('server text');
    expect(window.localStorage.getItem(KEY)).toBeNull();
  });
});
