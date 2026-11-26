import { useState } from 'react';
import { useParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { call } from '../api/client';
import { useCategories, type CategoryView } from '../api/listQueries';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { EmptyState } from '../components/EmptyState';
import { FormDialog } from '../components/FormDialog';
import { ProblemBanner } from '../components/ProblemBanner';
import { Markup, markupArg } from '../components/Markup';
import { useApi } from '../context';
import { useFieldErrors } from '../forms/useFieldErrors';
import { PageTitle } from '../frame/PageTitle';
import { useT } from '../i18n/messages';
import { failureText } from '../lib/errors';

/** An absolute http(s) URL, the one kind a category image may be (what the dialog's isValidUrl checked). */
function isValidUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

/** The category list (Categories.jsp): add, edit and delete-with-move. */
export function Categories() {
  const t = useT();
  const handle = useParams()['handle'] ?? '';
  const categories = useCategories(handle);
  const queries = useQueryClient();
  const [editing, setEditing] = useState<CategoryView | 'new' | null>(null);
  const [removing, setRemoving] = useState<CategoryView | null>(null);
  const [messages, setMessages] = useState<string[]>([]);
  const list = categories.data ?? [];

  const saved = (message: string) => {
    setEditing(null);
    setMessages([message]);
    void queries.invalidateQueries({ queryKey: ['categories', handle] });
  };
  const removed = (name: string) => {
    setRemoving(null);
    setMessages([t('categoryForm.removed', name)]);
    void queries.invalidateQueries({ queryKey: ['categories', handle] });
    void queries.invalidateQueries({ queryKey: ['entries', handle] });
  };

  return (
    <>
      <PageTitle>{t('categoriesForm.rootTitle')}</PageTitle>
      <p className="subtitle"><Markup text={t('categoriesForm.subtitle', markupArg(handle))} /></p>
      <ProblemBanner messages={messages} error={categories.error} />
      <p className="pagetip">{t('categoriesForm.rootPrompt')}</p>
      {list.length > 0 ? (
        <div className="list-actions">
          <button type="button" id="addCategoryButton" className="btn btn-primary btn-sm" onClick={() => { setMessages([]); setEditing('new'); }}>
            {t('categoriesForm.addCategory')}
          </button>
        </div>
      ) : null}
      <div className="table-responsive">
        <table id="category-table" className="pressroomtable table table-striped" width="100%">
          {list.length === 0 ? null : (
            <>
              <thead>
                <tr className="pressroomtable">
                  <th scope="col" style={{ width: '30%' }}>{t('generic.name')}</th>
                  <th scope="col">{t('generic.description')}</th>
                  <th scope="col" className="row-actions"><span className="visually-hidden">{t('mainPage.actions')}</span></th>
                </tr>
              </thead>
              <tbody>
                {list.map((category) => (
                  <tr key={category.id} data-category-id={category.id} data-category-name={category.name}>
                    <td>{category.name}</td>
                    <td>{category.description}</td>
                    <td className="row-actions">
                      <button type="button" className="row-action category-edit-btn" aria-label={`${t('generic.edit')}: ${category.name ?? ''}`}
                              onClick={() => { setMessages([]); setEditing(category); }}>
                        <span className="bi bi-pencil-square" aria-hidden="true" />
                      </button>
                      {list.length > 1 ? (
                        <button type="button" className="row-action row-action-danger category-delete-btn"
                                aria-label={`${t('categoriesForm.remove')}: ${category.name ?? ''}`}
                                onClick={() => { setMessages([]); setRemoving(category); }}>
                          <span className="bi bi-trash" aria-hidden="true" />
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </>
          )}
        </table>
      </div>
      {categories.data !== undefined && list.length === 0 ? (
        <EmptyState title={t('empty.categories.title')} body={t('empty.categories.body')}>
          <button type="button" id="addCategoryButton" className="btn btn-primary" onClick={() => { setEditing('new'); }}>
            {t('empty.categories.action')}
          </button>
        </EmptyState>
      ) : null}
      {editing === null ? null : (
        <CategoryDialog handle={handle} category={editing === 'new' ? null : editing}
                        onSaved={saved} onCancel={() => { setEditing(null); }} />
      )}
      {removing === null ? null : (
        <RemoveDialog handle={handle} category={removing} others={list.filter((c) => c.id !== removing.id)}
                      onRemoved={removed} onCancel={() => { setRemoving(null); }} />
      )}
    </>
  );
}

const IDS: Record<string, string> = { name: 'category_bean_name', description: 'category_bean_description', image: 'category_bean_image' };

/** The add/edit dialog: name, description and image URL, validated before it is sent. */
function CategoryDialog({ handle, category, onSaved, onCancel }: {
  handle: string; category: CategoryView | null; onSaved: (message: string) => void; onCancel: () => void;
}) {
  const t = useT();
  const api = useApi();
  const [name, setName] = useState(category?.name ?? '');
  const [description, setDescription] = useState(category?.description ?? '');
  const [image, setImage] = useState(category?.image ?? '');
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const fields = useFieldErrors(error, (field) => IDS[field] ?? field);

  const problem = name.trim() === '' ? t('categoryForm.requiredFields')
    : image.trim() !== '' && !isValidUrl(image.trim()) ? t('categoryForm.badURL') : null;

  const save = async () => {
    if (problem !== null || saving) {
      return;
    }
    setSaving(true);
    setError(null);
    const body = { name: name.trim(), description, image: image.trim() };
    try {
      if (category === null) {
        const created = await call(api.POST('/v1/weblogs/{handle}/categories', {
          params: { path: { handle } },
          body: { name: body.name, ...(description === '' ? {} : { description }), ...(body.image === '' ? {} : { image: body.image }) }
        }));
        onSaved(t('categoryForm.created', created.name ?? body.name));
      } else {
        const updated = await call(api.PATCH('/v1/weblogs/{handle}/categories/{id}', {
          params: { path: { handle, id: category.id ?? '' } }, body
        }));
        onSaved(t('categoryForm.changesSaved', updated.name ?? body.name));
      }
    } catch (failure) {
      setError(failure);
    } finally {
      setSaving(false);
    }
  };

  const enter = (event: { key: string; preventDefault: () => void }) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      void save();
    }
  };
  const field = (key: 'name' | 'description' | 'image', label: string, value: string, set: (v: string) => void) => (
    <div className="row mb-3">
      <label className="col-sm-3 col-form-label" htmlFor={IDS[key]}>{label}</label>
      <div className="col-sm-9">
        <input type="text" name={`bean.${key}`} {...fields.props(key, 'form-control')} value={value}
               {...(key === 'name' ? { maxLength: 255 } : {})}
               onChange={(event) => { set(event.target.value); }} onKeyDown={enter} />
        {fields.message(key) === undefined ? null : <div className="invalid-feedback d-block">{fields.message(key)}</div>}
      </div>
    </div>
  );

  return (
    <FormDialog title={category === null ? t('categoryForm.add.title') : t('categoryForm.edit.title')}
                saveLabel={t('generic.save')} saveDisabled={problem !== null || saving} onSave={() => { void save(); }} onCancel={onCancel}
                note={error !== null ? <p id="feedback-area-edit" role="alert" style={{ color: 'var(--bad)' }}>{failureText(error)}</p>
                  : problem !== null ? <p id="feedback-area-edit" style={{ color: 'var(--bad)' }}>{problem}</p> : <p id="feedback-area-edit" />}>
      <form id="categoryEditForm" className="form-stacked" onSubmit={(event) => { event.preventDefault(); }}>
        {field('name', t('generic.name'), name, setName)}
        {field('description', t('generic.description'), description, setDescription)}
        {field('image', t('categoryForm.image'), image, setImage)}
      </form>
    </FormDialog>
  );
}

/** Delete: an unused category goes; a used one needs a category to receive its entries. */
function RemoveDialog({ handle, category, others, onRemoved, onCancel }: {
  handle: string; category: CategoryView; others: CategoryView[]; onRemoved: (name: string) => void; onCancel: () => void;
}) {
  const t = useT();
  const api = useApi();
  const [target, setTarget] = useState(others[0]?.id ?? '');
  const [error, setError] = useState<unknown>(null);
  const inUse = (category.entryCount ?? 0) > 0;

  const remove = async () => {
    setError(null);
    try {
      await call(api.DELETE('/v1/weblogs/{handle}/categories/{id}', {
        params: { path: { handle, id: category.id ?? '' }, query: inUse ? { moveTo: target } : {} }
      }));
      onRemoved(category.name ?? '');
    } catch (failure) {
      setError(failure);
    }
  };

  return (
    <ConfirmDialog title={`${t('categoryDeleteOK.removeCategory')}: ${category.name ?? ''}`} confirmLabel={t('generic.delete')}
                   onConfirm={() => { void remove(); }} onCancel={onCancel}>
      {inUse ? (
        <div id="category-in-use">
          <p>{t('categoryDeleteOK.warningCatInUse')} {t('categoryDeleteOK.youMustMoveEntries')}</p>
          <label htmlFor="targetCategoryId">{t('categoryDeleteOK.moveToWhere')}</label>
          <select id="targetCategoryId" name="targetCategoryId" className="form-select" value={target}
                  onChange={(event) => { setTarget(event.target.value); }}>
            {others.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
      ) : (
        <div id="category-empty"><p>{t('categoryDeleteOK.noEntriesInCat')}</p></div>
      )}
      <p><strong>{t('categoryDeleteOK.areYouSure')}</strong></p>
      {error === null ? null : <p className="text-danger" role="alert">{failureText(error)}</p>}
    </ConfirmDialog>
  );
}
