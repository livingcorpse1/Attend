/**
 * Administration screens: accounts, subjects, classes, enrolments and the audit trail.
 *
 * <p>Nothing about the college is seeded, so these screens are how the academic
 * structure is built: a subject belongs to a teacher, a class section belongs to a
 * subject, and a student is enrolled in a class section.
 */

import { h } from '../dom.js';
import { api } from '../api.js';
import { formatDateTime, humanise, openModal, pageHead, table, toast } from '../ui.js';
import { icon } from '../icons.js';

/**
 * Renders the list of accounts.
 */
export async function renderAdminUsers(container, route) {
  const users = await api.get('/admin/users');
  if (!container.isConnected) {
    return;
  }

  const usersTable = table(users, [
    { key: 'fullName', label: 'Name' },
    { key: 'email', label: 'Email' },
    { key: 'role', label: 'Role', render: (row) => h('span.badge', humanise(row.role)) },
    { key: 'studentNumber', label: 'Roll number' },
    {
      key: 'faceState',
      label: 'Identity check',
      render: (row) => (row.role === 'STUDENT'
        ? h(`span.badge.${row.faceState === 'VERIFIED' ? 'badge-success' : 'badge-warning'}`,
          humanise(row.faceState))
        : '—')
    },
    {
      key: 'active',
      label: 'Status',
      render: (row) => h(`span.badge.${row.active ? 'badge-success' : 'badge-danger'}`,
        row.active ? 'Active' : 'Disabled')
    },
    {
      key: 'actions',
      label: '',
      render: (row) => h('button.button.button-secondary.button-sm', {
        type: 'button',
        onClick: () => toggleActive(row)
      }, row.active ? 'Disable' : 'Enable')
    }
  ], { empty: 'No accounts exist yet.' });

  container.replaceChildren(h('div.stack.stack-5', [
    pageHead({
      title: 'Accounts',
      lead: 'Students register themselves. Staff accounts are created here.',
      actions: h('button.button.button-primary', {
        type: 'button',
        onClick: () => createUser(container)
      }, [icon('user-plus', { size: 16 }), h('span', 'Add a staff account')])
    }),
    h('div.card', [h('div.card-body card-body-flush', usersTable)])
  ]));
}

async function toggleActive(user) {
  try {
    await api.put(`/admin/users/${user.id}/active`, undefined, { params: { active: !user.active } });
    toast('The account status was updated.', 'success');
  } catch (failure) {
    toast(failure.message, 'error');
  }
}

async function createUser(container) {
  const name = h('input.input', { type: 'text', required: true, placeholder: 'Full name' });
  const email = h('input.input', { type: 'email', required: true, placeholder: 'name@college.edu' });
  const password = h('input.input', { type: 'password', required: true, minLength: 10, placeholder: 'At least 10 characters' });
  const role = h('select.select', [
    h('option', { value: 'FACULTY' }, 'Faculty'),
    h('option', { value: 'ADMIN' }, 'Administrator')
  ]);

  const outcome = await openModal({
    title: 'Add a staff account',
    body: () => h('div.stack.stack-4', [
      h('div.field', [h('label', 'Full name'), name]),
      h('div.field', [h('label', 'Email'), email]),
      h('div.field', [h('label', 'Temporary password'), password]),
      h('div.field', [h('label', 'Role'), role])
    ]),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(undefined) }, 'Cancel'),
      (ctx) => h('button.button.button-primary', {
        type: 'button',
        onClick: () => ctx.close({
          fullName: name.value.trim(),
          email: email.value.trim(),
          password: password.value,
          role: role.value
        })
      }, 'Create the account')
    ]
  });

  if (!outcome) {
    return;
  }
  try {
    await api.post('/admin/users', outcome);
    toast('The account was created.', 'success');
    await renderAdminUsers(container, route);
  } catch (failure) {
    toast(failure.message, 'error');
  }
}

/**
 * Renders the academic structure: subjects, class sections and enrolments.
 */
export async function renderAdminClasses(container, route) {
  const [classes, subjects, users] = await Promise.all([
    api.get('/admin/classes'),
    api.get('/admin/subjects'),
    api.get('/admin/users')
  ]);
  if (!container.isConnected) {
    return;
  }
  const teachers = users.filter((user) => user.role === 'FACULTY' || user.role === 'ADMIN');
  const students = users.filter((user) => user.role === 'STUDENT');

  const classesTable = table(classes, [
    { key: 'subjectCode', label: 'Code' },
    { key: 'subjectName', label: 'Subject' },
    { key: 'section', label: 'Section' },
    { key: 'room', label: 'Room' },
    { key: 'facultyName', label: 'Teacher' },
    { key: 'enrolled', label: 'Students', align: 'right' }
  ], { empty: 'No class sections have been created yet.' });

  const subjectsTable = table(subjects, [
    { key: 'code', label: 'Code' },
    { key: 'name', label: 'Subject' },
    { key: 'facultyName', label: 'Teacher' }
  ], { empty: 'No subjects have been created yet.' });

  container.replaceChildren(h('div.stack.stack-5', [
    pageHead({
      title: 'Academic structure',
      lead: 'A subject belongs to a teacher, a class section belongs to a subject, a student is enrolled in a class section.',
      actions: [
        h('button.button.button-secondary', {
          type: 'button',
          onClick: () => createSubject(container, route, teachers)
        }, [icon('plus', { size: 16 }), h('span', 'Add a subject')]),
        h('button.button.button-secondary', {
          type: 'button',
          onClick: () => createClass(container, route, subjects)
        }, [icon('plus', { size: 16 }), h('span', 'Add a class section')])
      ]
    }),
    h('div.card', [
      h('div.card-header', [h('h3', 'Subjects')]),
      h('div.card-body card-body-flush', subjectsTable)
    ]),
    h('div.card', [
      h('div.card-header', [
        h('h3', 'Class sections'),
        h('button.button.button-secondary.button-sm', {
          type: 'button',
          onClick: () => enrolStudent(container, route, students, classes)
        }, 'Enrol a student')
      ]),
      h('div.card-body card-body-flush', classesTable)
    ])
  ]));
}

async function createSubject(container, route, teachers) {
  const code = h('input.input', { type: 'text', required: true, placeholder: 'For example CS301' });
  const name = h('input.input', { type: 'text', required: true, placeholder: 'Distributed Systems' });
  const teacher = h('select.select', teachers.map((user) => h('option', { value: String(user.id) },
    `${user.fullName} (${humanise(user.role)})`)));

  const outcome = await openModal({
    title: 'Add a subject',
    body: () => h('div.stack.stack-4', [
      h('div.field', [h('label', 'Code'), code]),
      h('div.field', [h('label', 'Name'), name]),
      h('div.field', [h('label', 'Teacher'), teacher])
    ]),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(undefined) }, 'Cancel'),
      (ctx) => h('button.button.button-primary', {
        type: 'button',
        onClick: () => ctx.close({
          code: code.value.trim(),
          name: name.value.trim(),
          facultyId: Number(teacher.value)
        })
      }, 'Create the subject')
    ]
  });

  if (!outcome) {
    return;
  }
  try {
    await api.post('/admin/subjects', outcome);
    toast('The subject was created.', 'success');
    await renderAdminClasses(container, route);
  } catch (failure) {
    toast(failure.message, 'error');
  }
}

async function createClass(container, route, subjects) {
  if (subjects.length === 0) {
    toast('Create a subject first.', 'error');
    return;
  }
  const subject = h('select.select', subjects.map((item) => h('option', { value: String(item.id) },
    `${item.code} · ${item.name}`)));
  const section = h('input.input', { type: 'text', required: true, placeholder: 'Section A' });
  const room = h('input.input', { type: 'text', required: true, placeholder: 'Room or hall' });

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
    await api.post('/admin/classes', outcome);
    toast('The class section was created.', 'success');
    await renderAdminClasses(container, route);
  } catch (failure) {
    toast(failure.message, 'error');
  }
}

async function enrolStudent(container, route, students, classes) {
  if (students.length === 0 || classes.length === 0) {
    toast('There are no students or no class sections yet.', 'error');
    return;
  }
  const student = h('select.select', students.map((user) => h('option', { value: String(user.id) },
    `${user.fullName}${user.studentNumber ? ` (${user.studentNumber})` : ''}`)));
  const classSection = h('select.select', classes.map((item) => h('option', { value: String(item.id) },
    `${item.subjectCode} · ${item.section}`)));

  const outcome = await openModal({
    title: 'Enrol a student',
    body: () => h('div.stack.stack-4', [
      h('div.field', [h('label', 'Student'), student]),
      h('div.field', [h('label', 'Class section'), classSection])
    ]),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(undefined) }, 'Cancel'),
      (ctx) => h('button.button.button-primary', {
        type: 'button',
        onClick: () => ctx.close({
          studentId: Number(student.value),
          classId: Number(classSection.value)
        })
      }, 'Enrol the student')
    ]
  });

  if (!outcome) {
    return;
  }
  try {
    await api.post('/admin/enrollments', undefined, { params: outcome });
    toast('The student was enrolled.', 'success');
    await renderAdminClasses(container, route);
  } catch (failure) {
    toast(failure.message, 'error');
  }
}

/**
 * Renders the institution settings.
 *
 * <p>These are the values that describe the college. They are stored in the database so
 * the same build can be pointed at any college, and so they can be corrected without a
 * deployment.
 */
export async function renderAdminSettings(container, route) {
  const settings = await api.get('/admin/settings');
  if (!container.isConnected) {
    return;
  }

  const fields = [
    { key: 'institution_name', label: 'Name of the institution', placeholder: 'The name of the college' },
    { key: 'institution_location', label: 'Location', placeholder: 'City, district' },
    { key: 'low_attendance_threshold', label: 'Low attendance threshold', placeholder: '75' },
    { key: 'biometric_consent_version', label: 'Version of the biometric notice', placeholder: '2026-01' }
  ];

  const inputs = new Map();
  const form = h('form.stack.stack-4', {
    onSubmit: async (event) => {
      event.preventDefault();
      try {
        for (const [key, input] of inputs) {
          await api.put('/admin/settings', undefined, { params: { key, value: input.value.trim() } });
        }
        toast('The settings were saved.', 'success');
        await renderAdminSettings(container, route);
      } catch (failure) {
        toast(failure.message, 'error');
      }
    }
  }, fields.map((field) => {
    const input = h('input.input', {
      type: 'text',
      value: settings[field.key] || '',
      placeholder: field.placeholder,
      required: field.key === 'institution_name'
    });
    inputs.set(field.key, input);
    return h('div.field', [h('label', field.label), input]);
  }));

  container.replaceChildren(h('div.stack.stack-5', [
    pageHead({
      title: 'Institution settings',
      lead: 'The values that describe the college. They are stored in the database.'
    }),
    h('div.card', [
      h('div.card-header', [h('h3', 'Settings')]),
      h('div.card-body', [
        form,
        h('div.row', { style: { marginTop: '1rem' } },
          h('button.button.button-primary', { type: 'submit' }, 'Save the settings'))
      ])
    ])
  ]));
}

/**
 * Renders the audit trail.
 */
export async function renderAdminAudit(container, route) {
  const entries = await api.get('/admin/audit-logs');
  if (!container.isConnected) {
    return;
  }

  const auditTable = table(entries, [
    { key: 'createdAt', label: 'When', render: (row) => formatDateTime(row.createdAt) },
    { key: 'actorName', label: 'Actor' },
    { key: 'action', label: 'Action', render: (row) => h('span.badge', humanise(row.action)) },
    { key: 'target', label: 'Target' },
    { key: 'reasonCode', label: 'Reason' },
    { key: 'explanation', label: 'Explanation' },
    { key: 'oldValue', label: 'From' },
    { key: 'newValue', label: 'To' }
  ], { empty: 'Nothing has been recorded yet.' });

  container.replaceChildren(h('div.stack.stack-5', [
    pageHead({
      title: 'Audit trail',
      lead: 'Every change that affects attendance is written here and cannot be edited.'
    }),
    h('div.card', [h('div.card-body card-body-flush', auditTable)])
  ]));
}
