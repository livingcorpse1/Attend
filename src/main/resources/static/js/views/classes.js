/**
 * The academic structure a teacher owns.
 *
 * <p>There is no administration. A teacher creates their own subjects, the class
 * sections that run them and the students who attend, and nobody can touch anybody
 * else's.
 *
 * <p>The screen shows the structure. The subject is the one thing a teacher adds here;
 * the class sections and the students of a subject are managed where the subject is
 * taught.
 */

import { h, replaceChildren } from '../dom.js';
import { api, cachedGet, invalidate } from '../api.js';
import { isCurrent } from '../router.js';
import { openModal, table, toast } from '../ui.js';

/**
 * Renders the subjects, the class sections and the students of the teacher.
 */
export async function renderMyClasses(container, route) {
  const paint = (data) => {
    if (!isCurrent(container, route)) {
      return;
    }

    const subjectTable = table(data.subjects, [
      { key: 'code', label: 'Code' },
      { key: 'name', label: 'Subject' }
    ], { empty: 'You have not created a subject yet.', emptyGlyph: '▤' });

    const classTable = table(data.classes, [
      { key: 'subjectCode', label: 'Code' },
      { key: 'subjectName', label: 'Subject' },
      { key: 'section', label: 'Section' },
      { key: 'room', label: 'Room' },
      { key: 'enrolled', label: 'Students', align: 'right' },
      {
        key: 'actions',
        label: '',
        render: (row) => h('button.button.button-secondary.button-sm', {
          type: 'button',
          onClick: () => removeClass(container, route, data, row)
        }, 'Remove')
      }
    ], { empty: 'You have not created a class section yet.', emptyGlyph: '▤' });

    container.replaceChildren(h('div.stack.stack-5', [
      h('div.page-head', [
        h('div', {
          h1: 'My classes',
          lead: 'The subjects you teach, the sections that run them and the students who attend.'
        }),
        h('div.row.gap-2', [
          h('button.button.button-primary', {
            type: 'button',
            onClick: () => createSubject(container, route, data)
          }, 'Add a subject')
        ])
      ]),
      h('div.card', [
        h('div.card-header', [h('h3', 'My subjects')]),
        h('div.card-body card-body-flush', subjectTable)
      ]),
      h('div.card', [
        h('div.card-header', [h('h3', 'My class sections')]),
        h('div.card-body card-body-flush', classTable)
      ])
    ]));
  };

  const data = await cachedGet('/faculty/structure', { onRefresh: paint });
  paint(data);
}

async function createSubject(container, route, data) {
  const code = h('input.input', { type: 'text', required: true, placeholder: 'For example ML' });
  const name = h('input.input', { type: 'text', required: true, placeholder: 'Machine Learning' });

  const outcome = await openModal({
    title: 'Add a subject',
    body: () => h('div.stack.stack-4', [
      h('div.field', [h('label', 'Code'), code]),
      h('div.field', [h('label', 'Name'), name]),
      h('p.text-xs.text-subtle', 'The subject is assigned to you. Only you can schedule lectures for it.')
    ]),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(undefined) }, 'Cancel'),
      (ctx) => h('button.button.button-primary', {
        type: 'button',
        onClick: () => ctx.close({ code: code.value.trim(), name: name.value.trim() })
      }, 'Create the subject')
    ]
  });

  if (!outcome) {
    return;
  }
  try {
    await api.post('/faculty/subjects', outcome);
    toast('The subject was created.', 'success');
    invalidate('/faculty/structure');
    invalidate('/faculty/classes');
    await renderMyClasses(container, route);
  } catch (failure) {
    toast(failure.message, 'error');
  }
}

async function removeClass(container, route, data, classView) {
  try {
    await api.delete(`/faculty/classes/${classView.id}`);
    toast('The class section was removed.', 'success');
    invalidate('/faculty/structure');
    invalidate('/faculty/classes');
    await renderMyClasses(container, route);
  } catch (failure) {
    toast(failure.message, 'error');
  }
}
