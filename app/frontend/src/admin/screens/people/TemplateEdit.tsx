import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { useTemplate, useUpdateTemplate, type TemplateView, type TemplateWrite } from '../../api/people';
import { useWeblog } from '../../api/queries';
import { Markup, markupArg } from '../../components/Markup';
import { ProblemBanner } from '../../components/ProblemBanner';
import { useFieldErrors } from '../../forms/useFieldErrors';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { DraftBar } from '../../session/DraftBar';
import { useDraftKeeper } from '../../session/useDraftKeeper';
import { bannerError, useHandle, weblogAddress } from './shared';
import { RequireLevel } from '../../lib/permission';

const FIELD_IDS: Record<string, string> = {
  name: 'template_bean_name', link: 'template_bean_link', description: 'template_bean_description',
  contents: 'template_bean_contentsStandard', contentType: 'template_bean_manualContentType'
};

export function TemplateEdit() {
  const handle = useHandle();
  const id = useParams()['id'] ?? '';
  return <RequireLevel level="admin" handle={handle}><TemplateLoader handle={handle} id={id} /></RequireLevel>;
}

function TemplateLoader({ handle, id }: { handle: string; id: string }) {
  const t = useT();
  const query = useTemplate(handle, id);
  if (query.data === undefined) {
    return (
      <>
        <PageTitle>{t('pagesForm.title')}</PageTitle>
        <ProblemBanner error={query.error} />
      </>
    );
  }
  return <TemplateForm handle={handle} template={query.data} />;
}

function TemplateForm({ handle, template }: { handle: string; template: TemplateView }) {
  const t = useT();
  const update = useUpdateTemplate(handle, template.id);
  const weblog = useWeblog(handle);
  const custom = template.action === 'CUSTOM';
  const required = template.required;

  const [name, setName] = useState(template.name);
  const [description, setDescription] = useState(template.description ?? '');
  const [link, setLink] = useState(template.link ?? '');
  const [contents, setContents] = useState(template.contents ?? '');
  const [hidden, setHidden] = useState(template.hidden);
  const [navbar, setNavbar] = useState(template.navbar);
  const [autoType, setAutoType] = useState((template.contentType ?? '') === '');
  const [manualType, setManualType] = useState(template.contentType ?? '');
  const [advanced, setAdvanced] = useState(false);
  const [saved, setSaved] = useState<string[]>([]);
  const keeper = useDraftKeeper(`template:${handle}:${template.id}`, contents);
  const fields = useFieldErrors(update.error, (field) => FIELD_IDS[field] ?? field);

  const advancedOpen = advanced || fields.invalid('contentType');
  const address = weblogAddress(weblog.data?.absoluteUrl);
  const savedLink = template.link ?? '';

  const save = (event: FormEvent) => {
    event.preventDefault();
    setSaved([]);
    const body: TemplateWrite = { contents };
    if (!required) {
      body.name = name;
      body.description = description;
    }
    if (custom && !required) {
      body.link = link;
    }
    if (custom) {
      body.hidden = hidden;
      body.navbar = navbar;
      body.contentType = autoType ? '' : manualType;
    }
    update.mutate(body, {
      onSuccess: (view) => {
        keeper.clear();
        setSaved([t('pageForm.save.success', view.name)]);
      }
    });
  };

  const launch = () => {
    if (address !== undefined) {
      window.open(`${address}page/${encodeURI(savedLink)}?type=${encodeURIComponent(template.action)}`, '_blank', 'noopener');
    }
  };

  return (
    <>
      <PageTitle>{t('pagesForm.title')}</PageTitle>
      <ProblemBanner messages={saved} error={bannerError(update.error)} />
      <p className="subtitle"><Markup text={t('pageForm.subtitle', markupArg(template.name), markupArg(handle))} /></p>
      <p className="pagetip">{required ? t('pageForm.tip.required') : t('pageForm.tip')}</p>
      <form id="template" className="form-stacked" onSubmit={save}>
        <DraftBar keeper={keeper} onRestore={setContents} />

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="template_bean_name">{t('generic.name')}</label>
          <div className="col-sm-9">
            <input type="text" size={50} readOnly={required} value={name} onChange={(e) => { setName(e.target.value); }}
                   {...fields.props('name', 'form-control')} name="bean.name" />
          </div>
        </div>

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="template_bean_action">{t('pageForm.action')}</label>
          <div className="col-sm-9">
            <input id="template_bean_action" type="text" size={50} readOnly className="form-control" value={template.action} />
          </div>
        </div>

        {custom && !required ? (
          <>
            <div className="row mb-3">
              <label className="col-sm-3 col-form-label" htmlFor="template_bean_link">{t('pageForm.link')}</label>
              <div className="col-sm-9">
                <input type="text" size={50} value={link} onChange={(e) => { setLink(e.target.value); }}
                       {...fields.props('link', 'form-control')} name="bean.link" />
              </div>
            </div>
            <div id="no_link" className="alert alert-danger" hidden={link !== ''}>{t('pageForm.noUrl')}</div>
            <div id="good_link" className="alert alert-success" hidden={link === ''}>
              {t('pageForm.resultingUrlWillBe')}
              <span className="data">{address ?? ''}page/<span id="linkPreview">{link}</span></span>
              {template.link !== undefined ? (
                <>
                  {' '}
                  <button type="button" className="btn btn-link p-0 align-baseline" id="launchLink"
                          hidden={link !== savedLink} onClick={launch}>{t('pageForm.launch')}</button>
                  <span id="linkChanged" className="form-text" hidden={link === savedLink}>{t('pageForm.launch.linkChanged')}</span>
                </>
              ) : null}
            </div>
          </>
        ) : null}

        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="template_bean_description">{t('generic.description')}</label>
          <div className="col-sm-9">
            <textarea rows={2} cols={50} readOnly={required} value={description}
                      onChange={(e) => { setDescription(e.target.value); }}
                      {...fields.props('description', 'form-control')} name="bean.description" />
          </div>
        </div>

        <div className="mb-3">
          <label htmlFor="template_bean_contentsStandard" className="form-label">{t('pageForm.source')}</label>
          <textarea rows={30} cols={80} spellCheck={false} value={contents}
                    onChange={(e) => { setContents(e.target.value); }}
                    {...fields.props('contents', 'form-control')} name="bean.contentsStandard" />
        </div>

        <button type="submit" className="btn btn-primary" disabled={update.isPending}>{t('generic.save')}</button>
        {' '}
        <Link to={`/weblogs/${encodeURIComponent(handle)}/templates`} className="btn btn-secondary">{t('generic.done')}</Link>

        {custom ? (
          <div id="accordion" className="mt-4">
            <div className="card" id="panel-advanced">
              <div className="card-header">
                <h2 className="section-head">
                  <button type="button" className={`btn btn-link p-0${advancedOpen ? '' : ' collapsed'}`}
                          aria-expanded={advancedOpen} aria-controls="collapseAdvanced"
                          onClick={() => { setAdvanced((on) => !on); }}>
                    {t('pageForm.advancedSettings')}
                  </button>
                </h2>
              </div>
              <div id="collapseAdvanced" className={`collapse${advancedOpen ? ' show' : ''}`}>
                <div className="card-body">
                  <div className="row mb-3"><div className="offset-sm-3 col-sm-9"><div className="form-check">
                    <label className="form-check-label">
                      <input type="checkbox" className="form-check-input" name="bean.hidden" checked={hidden}
                             onChange={(e) => { setHidden(e.target.checked); }} /> {t('pageForm.hidden')}
                    </label>
                  </div></div></div>
                  <div className="row mb-3"><div className="offset-sm-3 col-sm-9"><div className="form-check">
                    <label className="form-check-label">
                      <input type="checkbox" className="form-check-input" name="bean.navbar" checked={navbar}
                             onChange={(e) => { setNavbar(e.target.checked); }} /> {t('pageForm.navbar')}
                    </label>
                  </div></div></div>
                  <div className="row mb-3"><div className="offset-sm-3 col-sm-9"><div className="form-check">
                    <label className="form-check-label">
                      <input type="checkbox" className="form-check-input" id="template_bean_autoContentType"
                             name="bean.autoContentType" checked={autoType}
                             onChange={(e) => { setAutoType(e.target.checked); }} /> {t('pageForm.useAutoContentType')}
                    </label>
                  </div></div></div>
                  {autoType ? null : (
                    <div className="row mb-3" id="manual-content-type-control-group">
                      <label className="col-sm-3 col-form-label" htmlFor="template_bean_manualContentType">
                        {t('pageForm.useManualContentType')}
                      </label>
                      <div className="col-sm-9">
                        <input type="text" value={manualType} onChange={(e) => { setManualType(e.target.value); }}
                               {...fields.props('contentType', 'form-control')} name="bean.manualContentType" />
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        ) : null}
      </form>
    </>
  );
}
