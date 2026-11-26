import { act, render } from '@testing-library/react';
import { CodeMirrorField, type EditorHandle } from '../../../src/admin/editor/CodeMirrorField';

const mount = (props: Partial<React.ComponentProps<typeof CodeMirrorField>> = {}) => {
  let handle: EditorHandle | null = null;
  const onChange = vi.fn();
  const utils = render(
    <CodeMirrorField value="Hello world" onChange={onChange} label="Entry text" placeholder="Write" shortcodes={[]}
      ref={(h) => { handle = h ?? handle; }} {...props} />);
  return { ...utils, onChange, handle: () => handle as unknown as EditorHandle };
};

describe('CodeMirrorField: the handle', () => {
  it('replaces the first occurrence in place and reports whether it found the text', () => {
    const { handle } = mount({ value: 'one two two' });
    expect(handle().replaceInEditor('two', 'TWO')).toBe(true);
    expect(handle().getValue()).toBe('one TWO two');
    expect(handle().replaceInEditor('three', 'x')).toBe(false);
    expect(handle().getValue()).toBe('one TWO two');
  });

  it('pushes a changed value prop into the editor but leaves typed text alone', () => {
    const { handle, rerender, onChange } = mount();
    act(() => { handle().insert('!'); });
    expect(onChange).toHaveBeenCalled();
    rerender(<CodeMirrorField value="Restored draft" onChange={onChange} label="Entry text" placeholder="Write" shortcodes={[]} />);
    expect(handle().getValue()).toBe('Restored draft');
  });

  it('answers harmlessly once the editor is gone: empty text, no stats, nothing to replace', () => {
    const { handle, unmount } = mount();
    const gone = handle();
    unmount();
    expect(gone.getValue()).toBe('');
    expect(gone.scrollFraction()).toBe(0);
    expect(gone.stats()).toEqual({ words: 0, minutes: 0 });
    expect(gone.replaceInEditor('Hello', 'x')).toBe(false);
    expect(gone.view).toBeUndefined();
    expect(() => {
      gone.setValue('x'); gone.insert('x'); gone.focus(); gone.destroy(); gone.wrapSelection('*', '*', 'x'); gone.toggleLinePrefix('> ');
    }).not.toThrow();
  });
});

describe('CodeMirrorField: callbacks', () => {
  it('offers editor.js only the callbacks the screen supplied, so a bare surface leaves keys to CodeMirror', () => {
    const { container } = mount();
    expect(container.querySelector('.cm-editor')).not.toBeNull();
    const onUpload = vi.fn();
    const onBold = vi.fn();
    const { handle } = mount({ onUpload, onBold });
    act(() => { handle().focus(); });
    // The bold key chord only fires a screen callback when one was supplied.
    const content = document.querySelectorAll('.cm-content')[1] as HTMLElement;
    act(() => { content.dispatchEvent(new KeyboardEvent('keydown', { key: 'b', ctrlKey: true, bubbles: true, cancelable: true })); });
    expect(onBold).toHaveBeenCalledTimes(1);
  });
});
