import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useBusiness, useSaveBusiness, type BusinessView, type BusinessWrite } from '../api/siteQueries';
import { ProblemBanner } from '../components/ProblemBanner';
import { useFieldErrors } from '../forms/useFieldErrors';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';

/** BusinessRules.MAX_SAME_AS, as the JSP showed it in the help line. */
const MAX_SAME_AS = 10;

const OPTIONAL = ['websiteUrl', 'bookingUrl', 'telephone', 'email', 'logoUrl', 'areaServed', 'description'] as const;

interface Form {
  name: string;
  businessType: string;
  websiteUrl: string;
  bookingUrl: string;
  telephone: string;
  email: string;
  logoUrl: string;
  sameAs: string;
  areaServed: string;
  description: string;
}

const EMPTY: Form = { name: '', businessType: 'Organization', websiteUrl: '', bookingUrl: '', telephone: '', email: '',
  logoUrl: '', sameAs: '', areaServed: '', description: '' };

function fromView(b: BusinessView): Form {
  return { name: b.name, businessType: b.businessType, websiteUrl: b.websiteUrl ?? '', bookingUrl: b.bookingUrl ?? '',
    telephone: b.telephone ?? '', email: b.email ?? '', logoUrl: b.logoUrl ?? '', sameAs: b.sameAs.join('\n'),
    areaServed: b.areaServed ?? '', description: b.description ?? '' };
}

/** The request: filled fields as typed; on an edit, a field emptied is named in `clear` (null never removes). */
export function toWrite(form: Form, editing: boolean): BusinessWrite {
  const body: Record<string, unknown> = { name: form.name.trim(), businessType: form.businessType };
  const clear: string[] = [];
  for (const field of OPTIONAL) {
    const value = form[field].trim();
    if (value !== '') {
      body[field] = value;
    } else if (editing) {
      clear.push(field);
    }
  }
  const links = form.sameAs.split('\n').map((line) => line.trim()).filter((line) => line !== '');
  if (links.length > 0) {
    body['sameAs'] = links;
  } else if (editing) {
    clear.push('sameAs');
  }
  if (clear.length > 0) {
    body['clear'] = clear;
  }
  return body as BusinessWrite;
}

/** BusinessEdit.jsp: one business profile, new (`/admin/businesses/new`) or existing. */
export function BusinessEdit() {
  const t = useT();
  const navigate = useNavigate();
  const id = useParams()['id'];
  const editingId = id === 'new' ? undefined : id;
  const existing = useBusiness(editingId);
  const save = useSaveBusiness(editingId);
  const fields = useFieldErrors(save.error, (field) => `bean_${field}`);
  const [edited, setEdited] = useState<Form | null>(null);
  // What the person has typed, else what is stored (or nothing, for a new business).
  const form = edited ?? (existing.data === undefined ? EMPTY : fromView(existing.data));

  const set = (field: keyof Form) => (event: { target: { value: string } }) => {
    setEdited({ ...form, [field]: event.target.value });
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    save.mutate(toWrite(form, editingId !== undefined), {
      onSuccess: () => { void navigate('/admin/businesses', { state: { flash: 'businesses.saved' } }); }
    });
  };
  const text = (field: keyof Form, label: string, type: string, maxLength: number) => (
    <div className="row mb-3">
      <label className="col-sm-3 col-form-label" htmlFor={`bean_${field}`}>{label}</label>
      <div className="col-sm-9">
        <input type={type} {...fields.props(field, 'form-control')} name={`bean.${field}`} value={form[field]}
               maxLength={maxLength} onChange={set(field)} required={field === 'name'} />
      </div>
    </div>
  );

  return (
    <>
      <PageTitle>{t('businesses.title')}</PageTitle>
      <ProblemBanner error={existing.error ?? save.error} />
      <p className="subtitle">{editingId === undefined ? t('businesses.edit.titleNew') : t('businesses.edit.titleEdit')}</p>
      <form onSubmit={submit} className="form-stacked">
        {text('name', t('businesses.name'), 'text', 255)}
        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="bean_businessType">{t('businesses.type')}</label>
          <div className="col-sm-9">
            <select {...fields.props('businessType', 'form-select')} name="bean.businessType" value={form.businessType}
                    onChange={set('businessType')}>
              <option value="Organization">{t('businesses.type.Organization')}</option>
              <option value="LocalBusiness">{t('businesses.type.LocalBusiness')}</option>
              <option value="ProfessionalService">{t('businesses.type.ProfessionalService')}</option>
            </select>
          </div>
        </div>
        {text('websiteUrl', t('businesses.websiteUrl'), 'url', 255)}
        {text('bookingUrl', t('businesses.bookingUrl'), 'url', 255)}
        {text('telephone', t('businesses.telephone'), 'tel', 32)}
        {text('email', t('businesses.email'), 'email', 255)}
        {text('logoUrl', t('businesses.logoUrl'), 'url', 255)}
        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="bean_sameAs">{t('businesses.sameAs')}</label>
          <div className="col-sm-9">
            <textarea {...fields.props('sameAs', 'form-control')} name="bean.sameAs" rows={5} value={form.sameAs}
                      onChange={set('sameAs')} />
            <div className="form-text">{t('businesses.sameAs.help', MAX_SAME_AS)}</div>
          </div>
        </div>
        {text('areaServed', t('businesses.areaServed'), 'text', 255)}
        <div className="row mb-3">
          <label className="col-sm-3 col-form-label" htmlFor="bean_description">{t('businesses.description')}</label>
          <div className="col-sm-9">
            <textarea {...fields.props('description', 'form-control')} name="bean.description" rows={4}
                      value={form.description} onChange={set('description')} />
          </div>
        </div>
        <div className="mb-3">
          <button type="submit" className="btn btn-primary" id="business-save" disabled={save.isPending}>{t('generic.save')}</button>
          {' '}
          <Link to="/admin/businesses" className="btn btn-secondary">{t('generic.cancel')}</Link>
        </div>
      </form>
    </>
  );
}
