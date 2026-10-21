/**
 * The academic structure a teacher owns.
 *
 * <p>There is no administration. A teacher creates their own subjects, the class
 * sections that run them and the students who attend, and nobody can touch anybody
 * else's.
 */

import { h, replaceChildren } from '../dom.js';
import { api, cachedGet, invalidate } from '../api.js';
import { isCurrent } from '../router.js';
import { emptyState, openModal, table, toast } from '../ui.js';

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
          h('button.button.button-secondary', {
            type: 'button',
            onClick: () => enrolStudent(container, route, data)
          }, 'Enrol a student'),
          h('button.button.button-secondary', {
            type: 'button',
            onClick: () => addColleague(container, route)
          }, 'Add a colleague'),
          h('button.button.button-secondary', {
            type: 'button',
            onClick: () => createClass(container, route, data)
          }, 'Add a class section'),
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

async function addColleague(container, route) {
  const name = h('input.input', { type: 'text', required: true, placeholder: 'Full name' });
  const email = h('input.input', { type: 'email', required: true, placeholder: 'colleague@college.edu' });

  const outcome = await openModal({
    title: 'Add a colleague',
    body: () => h('div.stack.stack-4', [
      h('div.field', [h('label', 'Full name'), name]),
      h('div.field', [h('label', 'Email'), email]),
      h('p.text-xs.text-subtle', 'A password is generated for them. It is shown once, so write it down.')
    ]),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(undefined) }, 'Cancel'),
      (ctx) => h('button.button.button-primary', {
        type: 'button',
        onClick: () => ctx.close({ fullName: name.value.trim(), email: email.value.trim() })
      }, 'Add the colleague')
    ]
  });

  if (!outcome) {
    return;
  }
  try {
    const created = await api.post('/faculty/teachers', outcome);
    await openModal({
      title: 'The account is ready',
      body: () => h('div.stack.stack-3', [
        h('p.text-sm', `Give ${created.email} the password below. It is not shown again.`),
        h('code.code-chip', created.password)
      ]),
      actions: [(ctx) => h('button.button.button-primary', { type: 'button', onClick: () => ctx.close(undefined) }, 'Noted')]
    });
    toast('The colleague was added.', 'success');
  } catch (failure) {
    toast(failure.message, 'error');
  }
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

async function createClass(container, route, data) {
  if (data.subjects.length === 0) {
    toast('Create a subject first.', 'error');
    return;
  }
  const subject = h('select.select', data.subjects.map((item) => h('option', { value: String(item.id) },
    `${item.code} · ${item.name}`)));
  const section = h('input.input', { type: 'text', required: true, placeholder: 'A' });
  const room = h('input.input', { type: 'text', required: true, placeholder: 'Innovation Lab 204' });

  const outcome = await openModal({
    title: 'Add a class section',
    body: () => h('div.stack.stack-4', [
      h('div.field', [h('label', 'Subject'), subject]),
      h('div.field', [h('label', 'Section'), section]),
      h('div.field', [h('label', 'Room'), room])
    ]),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(undefined) }, 'Cancel'),
      (ctx) => h('button.button.button-primary', {
        type: 'button',
        onClick: () => ctx.close({
          subjectId: Number(subject.value),
          section: section.value.trim(),
          room: room.value.trim()
        })
      }, 'Create the class section')
    ]
  });

  if (!outcome) {
    return;
  }
  try {
    await api.post('/faculty/classes', outcome);
    toast('The class section was created.', 'success');
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

async function enrolStudent(container, route, data) {
  if (data.classes.length === 0) {
    toast('Create a class section first.', 'error');
    return;
  }
  const email = h('input.input', { type: 'email', required: true, placeholder: 'student@college.edu' });
  const classSection = h('select.select', data.classes.map((item) => h('option', { value: String(item.id) },
    `${item.subjectCode} · ${item.section}`)));

  const outcome = await openModal({
    title: 'Enrol a student',
    body: () => h('div.stack.stack-4', [
      h('div.field', [
        h('label', 'Email of the student'),
        email,
        h('span.hint', 'The student has to register first.')
      ]),
      h('div.field', [h('label', 'Class section'), classSection])
    ]),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(undefined) }, 'Cancel'),
      (ctx) => h('button.button.button-primary', {
        type: 'button',
        onClick: () => ctx.close({
          email: email.value.trim(),
          classId: Number(classSection.value)
        })
      }, 'Enrol the student')
    ]
  });

  if (!outcome) {
    return;
  }
  try {
    await api.post('/faculty/enrollments', undefined, { params: outcome });
    toast('The student was enrolled.', 'success');
    invalidate('/faculty/structure');
    invalidate('/faculty/classes');
    await renderMyClasses(container, route);
  } catch (failure) {
    toast(failure.message, 'error');
  }
}
