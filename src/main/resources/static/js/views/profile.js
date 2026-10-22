/**
 * The profile of the signed in person.
 *
 * <p>Both roles have one. The student finds the account details, the identity check and
 * the biometric data here. The teacher finds the account details and what they teach.
 */

import { h } from '../dom.js';
import { api, cachedGet, invalidate } from '../api.js';
import { isCurrent, navigate, onDispose } from '../router.js';
import { currentAccount, refreshAccount } from '../store.js';
import { table, toast, pageHead } from '../ui.js';
import { identityCheckSection, biometricPanel } from './verify.js';

/**
 * Renders the profile.
 *
 * @param {HTMLElement} container
 * @param {string} route
 */
export async function renderProfile(container, route) {
  const account = currentAccount();
  if (!account) {
    return;
  }

  if (account.role === 'STUDENT') {
    await renderStudentProfile(container, route, account);
  } else {
    await renderFacultyProfile(container, route, account);
  }
}

/**
 * The profile of a student: the account details, the identity check and the biometric
 * data, all in one place.
 */
async function renderStudentProfile(container, route, account) {
  const status = await cachedGet('/student/biometric/status');
  if (!isCurrent(container, route)) {
    return;
  }

  const details = detailCard('Your details', [
    ['Full name', account.fullName],
    ['Email', account.email],
    ['Student number', account.studentNumber || '—'],
    ['Role', 'Student'],
    ['Account', account.active ? h('span.badge.badge-success', 'Active') : h('span.badge.badge-warning', 'Inactive')],
    ['Member since', account.createdAt ? new Date(account.createdAt).toLocaleDateString() : '—']
  ]);

  const check = h('div.card', [
    h('div.card-header', [
      h('h3', 'Identity check'),
      h('span.badge', status.state === 'VERIFIED' ? 'Verified' : 'Not verified')
    ]),
    h('div.card-body', identityCheckSection(status, {
      container,
      onVerified: async () => {
        // The account changed, so it is read again and the profile is redrawn.
        await refreshAccount();
        invalidate('/student/biometric/status');
        await renderStudentProfile(container, route, currentAccount());
      }
    }))
  ]);

  const biometric = biometricPanel(status, async () => {
    // The account changed, so it is read again before the next screen is opened.
    await refreshAccount();
    invalidate('/student/biometric/status');
    navigate('/profile', { replace: true });
  });

  container.replaceChildren(h('div.stack.stack-6', [
    pageHead({
      title: 'Your profile',
      lead: 'Your account, your identity check and the biometric data that is stored about you.'
    }),
    h('div.split-wide', [
      h('div.stack.stack-6', [details, check]),
      h('div.stack.stack-6', [biometric, sessionCard()])
    ])
  ]));
}

/**
 * The profile of a teacher: the account details and what they teach.
 */
async function renderFacultyProfile(container, route, account) {
  const structure = await cachedGet('/faculty/structure');
  if (!isCurrent(container, route)) {
    return;
  }

  const details = detailCard('Your details', [
    ['Full name', account.fullName],
    ['Email', account.email],
    ['Role', 'Teacher'],
    ['Account', account.active ? h('span.badge.badge-success', 'Active') : h('span.badge.badge-warning', 'Inactive')],
    ['Member since', account.createdAt ? new Date(account.createdAt).toLocaleDateString() : '—']
  ]);

  const subjects = table(structure.subjects || [], [
    { key: 'code', label: 'Code' },
    { key: 'name', label: 'Subject' }
  ], { empty: 'You do not own any subject yet.', emptyGlyph: 'book' });

  const classes = table(structure.classes || [], [
    { key: 'subjectCode', label: 'Code' },
    { key: 'subjectName', label: 'Subject' },
    { key: 'section', label: 'Section' },
    { key: 'room', label: 'Room' },
    { key: 'enrolled', label: 'Students', align: 'right', render: (row) => String(row.enrolled) }
  ], { empty: 'You do not teach any class yet.', emptyGlyph: 'book' });

  const teaching = h('div.stack.stack-6', [
    h('div.card', [
      h('div.card-header', [h('h3', 'Your subjects')]),
      h('div.card-body.card-body-flush', subjects)
    ]),
    h('div.card', [
      h('div.card-header', [h('h3', 'Your class sections')]),
      h('div.card-body.card-body-flush', classes)
    ])
  ]);

  container.replaceChildren(h('div.stack.stack-6', [
    pageHead({
      title: 'Your profile',
      lead: 'Your account and the subjects you teach.'
    }),
    h('div.split-wide', [
      h('div.stack.stack-6', [details, sessionCard()]),
      teaching
    ])
  ]));
}

/**
 * A card that lists the details of the account.
 */
function detailCard(title, rows) {
  return h('div.card', [
    h('div.card-header', [h('h3', title)]),
    h('div.card-body.stack.stack-3', rows.map(([label, value]) => h('div.row.between', [
      h('span.text-sm.text-muted', label),
      value instanceof Node ? value : h('span.text-sm', value)
    ])))
  ]);
}

/**
 * The card that ends the session.
 *
 * <p>The sign out lives here, so the sidebar holds nothing but the way into the
 * profile.
 */
function sessionCard() {
  const button = h('button.button.button-secondary', { type: 'button' }, 'Sign out');
  button.onclick = async () => {
    button.disabled = true;
    try {
      await api.post('/auth/logout');
      // The account is gone, so the shell is torn down and the sign in screen comes back.
      const { setState } = await import('../store.js');
      setState({ account: null });
      const root = document.getElementById('app');
      if (root) {
        root.replaceChildren();
      }
      navigate('/login', { replace: true });
    } catch (failure) {
      button.disabled = false;
      toast(failure.message, 'error');
    }
  };
  return h('div.card', [
    h('div.card-header', [h('h3', 'Session')]),
    h('div.card-body.stack.stack-3', [
      h('p.text-sm.text-muted', 'Signing out ends the session on this device. Your attendance and your enrolment stay where they are.'),
      h('div', button)
    ])
  ]);
}
