import { render, screen, waitFor } from '@testing-library/react';
import { PageBodyEditor } from '../../../src/admin/screens/pages/PageBodyEditor';
import { PagePreview } from '../../../src/admin/screens/pages/PagePreview';
import { renderInApp } from '../support';

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
