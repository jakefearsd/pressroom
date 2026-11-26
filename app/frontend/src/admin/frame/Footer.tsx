import { useT } from '../i18n/messages';

/** The Colophon facts the footer states; every one optional (a clause with no fact is left out). */
export interface Colophon {
  copyrightText?: string;
  copyrightYear?: string;
  copyrightHolder?: string;
  privacyUrl?: string;
  version?: string;
  shortRevision?: string;
  releaseDate?: string;
}

/** footer.jsp's colophon: copyright, then privacy link, version and release date. */
export function Footer({ colophon }: { colophon: Colophon }) {
  const t = useT();
  const copyright = colophon.copyrightText
    ?? (colophon.copyrightHolder === undefined ? undefined
      : t('footer.copyright.default', colophon.copyrightYear ?? '', colophon.copyrightHolder));
  const { version, shortRevision, releaseDate, privacyUrl } = colophon;
  return (
    <footer className="footer">
      <div className="container-fluid">
        <div className="colophon">
          {copyright === undefined || copyright === '' ? null : <p className="colophon-copyright">{copyright}</p>}
          <p className="colophon-meta">
            {privacyUrl === undefined ? null : (
              <span className="colophon-privacy"><a href={privacyUrl}>{t('footer.privacy')}</a></span>
            )}
            {version === undefined ? null : (
              <span className="colophon-version">
                {shortRevision === undefined ? t('footer.version', version) : t('footer.version.revision', version, shortRevision)}
              </span>
            )}
            {releaseDate === undefined ? null : (
              <span className="colophon-released">{t('footer.released', releaseDate)}</span>
            )}
          </p>
        </div>
      </div>
    </footer>
  );
}
