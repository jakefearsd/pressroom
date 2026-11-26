import { render, screen, waitFor } from '@testing-library/react';
import { PageBodyEditor } from '../../../src/admin/screens/pages/PageBodyEditor';
import { PagePreview } from '../../../src/admin/screens/pages/PagePreview';
import { pageUrl, weblogBase } from '../../../src/admin/screens/pages/urls';
import { renderInApp } from '../support';

describe('page addresses', () => {
  it('uses the custom domain, else the site (root for the site space, handle for a blog)', () => {
    expect(weblogBase('a', { customDomain: 'a.example.com' }, 'https://s.test')).toBe('https://a.example.com/');
    expect(weblogBase('a', {}, 'https://s.test')).toBe('https://s.test/a/');
    expect(weblogBase('site-space', undefined, 'https://s.test')).toBe('https://s.test/');
    expect(weblogBase('a', {}, undefined)).toBeUndefined();
  });
  it('links the home page at the base and any other page at its slug', () => {
    expect(pageUrl('https://s.test/a/', 'x y', false)).toBe('https://s.test/a/x%20y');
    expect(pageUrl('https://s.test/a/', 'home', true)).toBe('https://s.test/a/');
    expect(pageUrl(undefined, 'x', false)).toBeUndefined();
  });
});

describe('PagePreview', () => {
  it('shows the given (server-sanitised) HTML', () => {
    render(<PagePreview html="<h2>Rendered</h2>" />);
    expect(screen.getByRole('heading', { name: 'Rendered' })).toBeInTheDocument();
  });
});

describe('PageBodyEditor', () => {
  it('follows a value that changes from outside, reports typing, and saves on the save key', async () => {
    const changes: string[] = [];
    const saves: string[] = [];
    const render2 = (value: string) => (
      <PageBodyEditor value={value} onChange={(t) => { changes.push(t); }} onSave={() => { saves.push('s'); }}
                      render={() => Promise.resolve({ html: '', warnings: [] })} />
    );
    const view = renderInApp(render2('first'));
    await waitFor(() => { expect(document.querySelector('.cm-content')).toHaveTextContent('first'); });
    view.rerender(render2('second'));
    await waitFor(() => { expect(document.querySelector('.cm-content')).toHaveTextContent('second'); });
    const content = document.querySelector('.cm-content') as HTMLElement;
    content.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true, bubbles: true, cancelable: true }));
    expect(saves).toEqual(['s']);
    view.unmount();
    expect(document.querySelector('.cm-content')).toBeNull();
  });
});
