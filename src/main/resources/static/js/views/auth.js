/**
 * Sign in and sign up.
 */

import { h, formValues } from '../dom.js';
import { api } from '../api.js';
import { toast } from '../ui.js';
import { consentVersion, demoAccounts, demoMode } from '../store.js';

const SELLING_POINTS = [
  { glyph: '⟳', title: 'Rotating codes', text: 'Every classroom code is signed and expires within seconds, so a screenshot cannot be reused.' },
  { glyph: '☺', title: 'Verified presence', text: 'The face check runs on the server. Nobody can mark attendance for somebody else.' },
  { glyph: '⧉', title: 'Works on any phone', text: 'Install it like an app. It keeps working on a weak connection and comes back online on its own.' }
];

/**
 * Renders the marketing panel of the authentication screens.
 */
export function authAside() {
  return h('aside.auth-aside', [
    h('div.brand', [h('span.brand-mark', 'V'), h('span', 'VeriQra', h('small', 'Presence, verified'))]),
    h('div.stack.stack-4', [
      h('h2', 'Attendance you can actually trust.'),
      h('p', 'VeriQra pairs a rotating classroom code with a face check that runs on the server, so every attendance record is backed by evidence.')
    ]),
    h('div.auth-points', SELLING_POINTS.map((point) => h('div.auth-point', [
      h('span.glyph', point.glyph),
      h('div', [h('div', { style: { fontWeight: '600' } }, point.title), h('p.text-sm', { style: { color: '#a9b7d6' } }, point.text)])
    ])))
  ]);
}

/**
 * Renders the sign in screen.
 *
 * @param {HTMLElement} container
 * @param {(account: object) => void} onSignedIn
 */
export async function renderLogin(container, onSignedIn) {
  const error = h('div');
  const submit = h('button.button.button-primary.button-block.button-lg', { type: 'submit' }, 'Sign in');

  const form = h('form.auth-form', {
    onSubmit: async (event) => {
      event.preventDefault();
      error.replaceChildren();
      submit.disabled = true;
      submit.textContent = 'Signing in…';
      try {
        const values = formValues(form);
        const account = await api.post('/auth/login', {
          email: values.email,
          password: values.password
        });
        toast(`Welcome back, ${account.fullName.split(' ')[0]}.`, 'success');
        await onSignedIn(account);
      } catch (failure) {
        error.replaceChildren(h('div.alert.alert-error', failure.message));
        submit.disabled = false;
        submit.textContent = 'Sign in';
      }
    }
  }, [
    h('div.field', [
      h('label', { for: 'login-email' }, 'College email address'),
      h('input.input', {
        id: 'login-email',
        name: 'email',
        type: 'email',
        required: true,
        autocomplete: 'username',
        placeholder: 'you@college.edu',
        inputMode: 'email'
      })
    ]),
    h('div.field', [
      h('label', { for: 'login-password' }, 'Password'),
      h('input.input', {
        id: 'login-password',
        name: 'password',
        type: 'password',
        required: true,
        autocomplete: 'current-password',
        placeholder: 'Your password'
      })
    ]),
    error,
    submit
  ]);

  const card = h('div.auth-card', [
    h('div.brand', [h('span.brand-mark', 'V'), h('span', 'VeriQra')]),
    h('p.eyebrow', 'Welcome back'),
    h('h1', 'Sign in to continue'),
    h('p.lead', 'Use the account issued by your college.'),
    form,
    h('p.auth-switch', [
      'New student? ',
      h('a', { href: '#/signup', onClick: () => {} }, 'Create an account')
    ]),
    demoHint()
  ]);

  container.replaceChildren(h('div.auth', [authAside(), h('main.auth-main', card)]));
  container.querySelector('#login-email')?.focus();
}

/**
 * Shows the demonstration accounts, but only when they exist.
 */
function demoHint() {
  if (!demoMode()) {
    return null;
  }
  const accounts = demoAccounts();
  if (accounts.length === 0) {
    return null;
  }
  return h('div.demo-note', [
    h('strong', 'Demonstration accounts'),
    h('div.demo-accounts', accounts.map((entry) => h('span.demo-account', entry)))
  ]);
}

/**
 * Renders the sign up screen.
 *
 * @param {HTMLElement} container
 * @param {(account: object) => void} onRegistered
 */
export async function renderSignup(container, onRegistered) {
  const error = h('div');
  const submit = h('button.button.button-primary.button-block.button-lg', { type: 'submit' }, 'Create account');

  const form = h('form.auth-form', {
    onSubmit: async (event) => {
      event.preventDefault();
      error.replaceChildren();
      const values = formValues(form);
      if (values.consentAccepted !== true) {
        error.replaceChildren(h('div.alert.alert-error', 'Please read and accept the biometric data notice.'));
        return;
      }
      submit.disabled = true;
      submit.textContent = 'Creating your account…';
      try {
        const account = await api.post('/auth/register', {
          fullName: values.fullName,
          email: values.email,
          password: values.password,
          studentNumber: values.studentNumber,
          consent: consentVersion(),
          consentAccepted: true
        });
        toast('Account created. Let us verify your face.', 'success');
        await onRegistered(account);
      } catch (failure) {
        error.replaceChildren(h('div.alert.alert-error', failure.message));
        submit.disabled = false;
        submit.textContent = 'Create account';
      }
    }
  }, [
    h('div.field', [
      h('label', { for: 'signup-name' }, 'Full name'),
      h('input.input', {
        id: 'signup-name',
        name: 'fullName',
        type: 'text',
        required: true,
        minLength: 3,
        maxLength: 140,
        autocomplete: 'name',
        placeholder: 'As it appears on your college record'
      })
    ]),
    h('div.field', [
      h('label', { for: 'signup-email' }, 'College email address'),
      h('input.input', {
        id: 'signup-email',
        name: 'email',
        type: 'email',
        required: true,
        autocomplete: 'username',
        placeholder: 'you@college.edu',
        inputMode: 'email'
      })
    ]),
    h('div.field', [
      h('label', { for: 'signup-roll' }, 'Roll number'),
      h('input.input', {
        id: 'signup-roll',
        name: 'studentNumber',
        type: 'text',
        required: true,
        minLength: 4,
        maxLength: 40,
        pattern: '[A-Za-z0-9-]{4,40}',
        placeholder: 'e.g. VQ24001',
        autocomplete: 'off'
      }),
      h('span.hint', 'Letters, digits and dashes only.')
    ]),
    h('div.field', [
      h('label', { for: 'signup-password' }, 'Password'),
      h('input.input', {
        id: 'signup-password',
        name: 'password',
        type: 'password',
        required: true,
        minLength: 10,
        autocomplete: 'new-password',
        placeholder: 'At least 10 characters'
      }),
      h('span.hint', 'Mix letters and digits. Do not reuse a password from another site.')
    ]),
    h('div.alert.alert-info', [
      h('div.stack.stack-2', [
        h('strong', 'Biometric data notice'),
        h('span', 'Your college stores a mathematical template of your face so that attendance can be verified. '
          + 'The template is created from photographs taken on your own device, it never contains an image of your face, '
          + 'and only the verification result is compared. You can withdraw it at any time from the Verify page.')
      ])
    ]),
    h('label.checkbox', [
      h('input', { type: 'checkbox', name: 'consentAccepted', required: true }),
      h('span', 'I have read the notice above and I agree to my face template being created and stored for attendance verification.')
    ]),
    error,
    submit
  ]);

  const card = h('div.auth-card', [
    h('div.brand', [h('span.brand-mark', 'V'), h('span', 'VeriQra')]),
    h('p.eyebrow', 'Student sign up'),
    h('h1', 'Create your account'),
    h('p.lead', 'One step, then a quick face check and you are ready.'),
    form,
    h('p.auth-switch', [
      'Already registered? ',
      h('a', { href: '#/login', onClick: () => {} }, 'Sign in instead')
    ])
  ]);

  container.replaceChildren(h('div.auth', [authAside(), h('main.auth-main', card)]));
  container.querySelector('#signup-name')?.focus();
}
