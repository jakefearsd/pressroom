import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrandParts } from '../../../src/admin/screens/pages/StrandParts';
import { renderInApp } from '../support';

const info = new Map([['a', { id: 'a', title: 'Alpha post', status: 'PUBLISHED' }], ['b', { id: 'b', title: 'Beta post', status: 'DRAFT' }]]);
const show = (parts: string[], onChange = vi.fn()) => {
  renderInApp(<StrandParts parts={parts} info={info} candidates={[]} search="" onSearch={vi.fn()} onChange={onChange} />);
  return onChange;
};
const rows = () => [...document.querySelectorAll('.strand-part')];

describe('StrandParts', () => {
  it('names a part the host knows nothing about by its id, as a draft', () => {
    show(['a', 'ghost']);
    expect(screen.getByText('ghost')).toHaveClass('strand-part-title');
    expect(rows()[1]).toHaveAttribute('data-status', 'draft');
  });

  it('moves a dragged row to where it is dropped, once', () => {
    const onChange = show(['a', 'b']);
    fireEvent.dragStart(rows()[0] as Element);
    fireEvent.drop(rows()[1] as Element);
    expect(onChange).toHaveBeenCalledExactlyOnceWith(['b', 'a']);
  });

  it('ignores a drop that began outside the list, or onto the row it came from', () => {
    const onChange = show(['a', 'b']);
    fireEvent.drop(rows()[1] as Element);
    fireEvent.dragStart(rows()[0] as Element);
    fireEvent.drop(rows()[0] as Element);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('does not move the first row up or the last row down, and removes a row on request', async () => {
    const onChange = show(['a', 'b']);
    await userEvent.click(screen.getByRole('button', { name: 'Move up: Alpha post' }));
    await userEvent.click(screen.getByRole('button', { name: 'Move down: Beta post' }));
    expect(onChange).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Remove from strand: Alpha post' }));
    expect(onChange).toHaveBeenCalledExactlyOnceWith(['b']);
  });
});
