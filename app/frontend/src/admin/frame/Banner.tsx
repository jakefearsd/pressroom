import { useEffect, useRef, useState } from 'react';
import { useBoot } from '../context';
import { useT } from '../i18n/messages';
import { CHROME_LINKS, type LinkTarget } from '../routes/railTable';
import { clearAllDrafts } from '../session/drafts';
import { AppLink } from './AppLink';

export interface SwitcherOption {
  handle: string;
  name: string;
  target: LinkTarget;
}

function WeblogSwitcher({ options, currentHandle, currentName }: {
  options: readonly SwitcherOption[]; currentHandle: string | undefined; currentName: string | undefined;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const outside = (event: MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  return (
    <div className="dropdown weblog-switcher" ref={root}>
      <button className="btn btn-secondary btn-sm dropdown-toggle" type="button" id="weblogSwitcher"
              aria-expanded={open} aria-label={t('switcher.label')}
              onClick={() => { setOpen((o) => !o); }}>
        {currentName ?? t('switcher.label')}
      </button>
      <ul className={`dropdown-menu${open ? ' show' : ''}`} aria-labelledby="weblogSwitcher">
        {options.map((option) => (
          <li key={option.handle}>
            <AppLink target={option.target}
                     className={`dropdown-item${option.handle === currentHandle ? ' active' : ''}`}>
              {option.name} <span className="data">{option.handle}</span>
            </AppLink>
          </li>
        ))}
      </ul>
    </div>
  );
}

function mainMenuTarget(contextPath: string): LinkTarget {
  const { path, legacy } = CHROME_LINKS.mainMenu;
  return path === undefined ? { kind: 'legacy', to: contextPath + legacy } : { kind: 'react', to: path };
}

/** The top bar of bannerStatus.jsp: brand, weblog switcher (two or more weblogs), main menu, sign out. */
export function Banner({ siteName, switcher, currentHandle, currentName }: {
  siteName: string;
  switcher: readonly SwitcherOption[];
  currentHandle: string | undefined;
  currentName: string | undefined;
}) {
  const t = useT();
  const { contextPath } = useBoot();
  const [open, setOpen] = useState(false);
  return (
    <nav className="navbar navbar-expand-md pressroom-topbar">
      <div className="container-fluid">
        <a className="navbar-brand" href={`${contextPath}/`}>{siteName}</a>
        {switcher.length > 0
          ? <WeblogSwitcher options={switcher} currentHandle={currentHandle} currentName={currentName} />
          : null}
        <button type="button" className={`navbar-toggler${open ? '' : ' collapsed'}`} aria-expanded={open}
                aria-controls="navbar" aria-label={t('generic.toggle')}
                onClick={() => { setOpen((o) => !o); }}>
          <span className="visually-hidden">{t('generic.toggle')}</span>
          <span className="navbar-toggler-icon" />
        </button>
        <div id="navbar" className={`navbar-collapse collapse${open ? ' show' : ''}`}>
          <ul className="navbar-nav ms-auto">
            <li className="nav-item">
              <AppLink className="nav-link" target={mainMenuTarget(contextPath)}>
                {t('mainPage.mainMenu')}
              </AppLink>
            </li>
            <li className="nav-item">
              {/* Sign-out also clears this browser's autosaved drafts (pressroom.js did it on the click). */}
              <a className="nav-link" href={contextPath + CHROME_LINKS.logout.legacy} onClick={clearAllDrafts}>
                {t('navigationBar.logout')}
              </a>
            </li>
          </ul>
        </div>
      </div>
    </nav>
  );
}
