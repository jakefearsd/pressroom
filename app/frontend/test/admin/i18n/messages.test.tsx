import { render, screen } from '@testing-library/react';
import { createTranslate, loadMessages, MessagesProvider, plainText, resetMissingKeyLog, useT } from '../../../src/admin/i18n/messages';

describe('createTranslate', () => {
  beforeEach(() => { resetMissingKeyLog(); });

  it('returns a no-argument value verbatim, doubled apostrophes and all (as the server does)', () => {
    const t = createTranslate({ k: "Don''t {0}" });
    expect(t('k')).toBe("Don''t {0}");
  });

  it('runs a value with arguments through MessageFormat', () => {
    const t = createTranslate({ k: "Don''t {0}" });
    expect(t('k', 'x')).toBe("Don't x");
  });

  it('renders a missing key as the key and logs it once', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const t = createTranslate({});
    expect(t('no.such.key')).toBe('no.such.key');
    expect(t('no.such.key')).toBe('no.such.key');
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledWith('Missing message key: no.such.key');
    warn.mockRestore();
  });
});

describe('loadMessages', () => {
  it('reads the flat bundle', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(new Response('{"a":"b"}')));
    await expect(loadMessages('/m', fetchImpl)).resolves.toEqual({ a: 'b' });
    expect(fetchImpl).toHaveBeenCalledWith('/m', { credentials: 'same-origin' });
  });

  it('fails on a bad status', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(new Response('no', { status: 500 })));
    await expect(loadMessages('/m', fetchImpl)).rejects.toThrow('500');
  });
});

describe('plainText', () => {
  it('decodes the entities JSP-era values carry', () => {
    expect(plainText('Create &amp; edit &lt;b&gt; &quot;x&quot; &#39;y&#39;')).toBe('Create & edit <b> "x" \'y\'');
  });
});

describe('useT', () => {
  it('translates inside a provider and refuses to run outside one', () => {
    function Label() {
      return <p>{useT()('k', 'v')}</p>;
    }
    render(<MessagesProvider messages={{ k: 'got {0}' }} locale="en"><Label /></MessagesProvider>);
    expect(screen.getByText('got v')).toBeInTheDocument();
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => render(<Label />)).toThrow('MessagesProvider');
    error.mockRestore();
  });
});
