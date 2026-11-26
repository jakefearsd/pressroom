import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FormDialog } from '../../../src/admin/components/FormDialog';
import { renderInApp } from '../support';

const dialog = (onSave = vi.fn(), onCancel = vi.fn(), extra: { note?: string; saveDisabled?: boolean } = {}) => (
  <FormDialog title="Add category" saveLabel="Save it" onSave={onSave} onCancel={onCancel} {...extra}>
    <input aria-label="Name" />
  </FormDialog>
);

describe('FormDialog', () => {
  it('puts focus on the first field and hands it back to the opener when it closes', () => {
    const opener = document.createElement('button');
    document.body.append(opener);
    opener.focus();
    const { unmount } = renderInApp(dialog());
    expect(screen.getByLabelText('Name')).toHaveFocus();
    unmount();
    expect(opener).toHaveFocus();
    opener.remove();
  });

  it('cancels on Escape and ignores every other key', () => {
    const onCancel = vi.fn();
    renderInApp(dialog(vi.fn(), onCancel));
    fireEvent.keyDown(document, { key: 'Enter' });
    fireEvent.keyDown(document, { key: 'a' });
    expect(onCancel).not.toHaveBeenCalled();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('saves and cancels from its buttons, shows its note, and can disable the save', async () => {
    const onSave = vi.fn();
    const onCancel = vi.fn();
    const user = userEvent.setup();
    const { rerender } = renderInApp(dialog(onSave, onCancel, { note: 'Name is required' }));
    expect(screen.getByRole('dialog', { name: 'Add category' })).toHaveTextContent('Name is required');
    await user.click(screen.getByRole('button', { name: 'Save it' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledTimes(1);
    rerender(dialog(onSave, onCancel, { saveDisabled: true }));
    expect(screen.getByRole('button', { name: 'Save it' })).toBeDisabled();
  });

  it('closes cleanly when nothing focusable was focused before it opened', () => {
    const active = vi.spyOn(document, 'activeElement', 'get').mockReturnValue(null);
    const { unmount } = renderInApp(dialog());
    expect(() => { unmount(); }).not.toThrow();
    active.mockRestore();
  });
});
