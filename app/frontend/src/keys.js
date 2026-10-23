/* The keys the host page owns, bound INSIDE the editor above defaultKeymap
   (editor.js wraps this list in Prec.highest).

   Save / publish / guide: defaultKeymap already claims two of the three --
   Mod-Enter is insertBlankLine and Mod-/ is toggleComment. Leaving them to the
   host page's document-level keydown handler does not work: a default binding
   that runs calls preventDefault WITHOUT stopPropagation, so the event still
   reaches document, where that handler deliberately bails on an
   already-handled event. (It has to: the bail is what stops one Ctrl+S being
   served twice.)

   Bold / italic / link: the toolbar and the writing guide advertise them, and
   unbound they were worse than dead -- Mod-i is defaultKeymap's
   selectParentSyntax, and Ctrl+B / Ctrl+K fell through to the browser
   (bookmarks sidebar, search bar). The host passes its toolbar commands, so
   the key and the button insert exactly the same Markdown.

   "Mod" is Ctrl, or Cmd on macOS.

   Returning true is the other half of the contract: CodeMirror preventDefaults
   the key, the document handler stands down, and the shortcut fires exactly
   once. A key the host supplied no callback for returns false instead of
   swallowing it, so the editor's own default still applies.

   Kept free of CodeMirror imports so node --test can exercise it directly. */
export function hostBindings(options) {
  const bind = (handler) => () => {
    if (!handler) return false;
    handler();
    return true;
  };
  return [
    { key: 'Mod-s', run: bind(options.onSave) },
    { key: 'Mod-Enter', run: bind(options.onPublish) },
    { key: 'Mod-/', run: bind(options.onHelp) },
    { key: 'Mod-b', run: bind(options.onBold) },
    { key: 'Mod-i', run: bind(options.onItalic) },
    { key: 'Mod-k', run: bind(options.onLink) }
  ];
}
