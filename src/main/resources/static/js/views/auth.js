/**
 * Sign in and sign up.
 */

import { h, formValues } from '../dom.js';
import { api } from '../api.js';
import { openModal, toast } from '../ui.js';
import { icon } from '../icons.js';
import { consentVersion, demoAccounts, demoMode } from '../store.js';

/**
 * The card of the sign in form.
 *
 * @param {object} options
 * @param {(account: object) => void} options.onSignedIn Called after a successful sign in.
 * @param {() => void} options.onSwitch Called when the person wants the other form.
 * @returns {HTMLElement}
 */
function loginCard({ onSignedIn, onSwitch }) {
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
      passwordField('login-password', 'password', 'current-password', 'Your password')
    ]),
    error,
    submit
  ]);

  return h('div.auth-card', [
    h('div.brand', [h('img.brand-mark', { src: '/icons/logo.png', alt: '', width: '30', height: '30' }), h('span', 'VeriQra')]),
    h('p.eyebrow', 'Welcome back'),
    h('h1', 'Sign in to continue'),
    h('p.lead', 'Use the account issued by your college.'),
    form,
    insecureNote(),
    h('p.auth-switch', [
      'New student? ',
      h('a', {
        href: '#/signup',
        onClick: (event) => {
          event.preventDefault();
          onSwitch();
        }
      }, 'Create an account')
    ]),
    demoHint()
  ]);
}

/**
 * Opens the sign in or the sign up as a dialog over the page.
 *
 * <p>The dialog holds nothing but the form, so it stays small and the page behind it
 * stays visible. The two forms swap places inside it, and closing it leaves the page
 * exactly as it was.
 *
 * @param {'login'|'signup'} mode
 * @returns {Promise<object|undefined>} The account of the person who signed in, or nothing
 *   when the dialog was closed.
 */
export async function openAuthDialog(mode = 'login') {
  let outcome;
  do {
    outcome = await openModal({
      title: mode === 'signup' ? 'Create your account' : 'Sign in',
      body: (ctx) => h('div.auth-in-dialog', mode === 'signup'
        ? signupCard({
          onRegistered: (account) => ctx.close(account),
          onSwitch: () => ctx.close('login')
        })
        : loginCard({
          onSignedIn: (account) => ctx.close(account),
          onSwitch: () => ctx.close('signup')
        }))
    });
    if (outcome === 'signup') {
      mode = 'signup';
    } else if (outcome === 'login') {
      mode = 'login';
    }
  } while (outcome === 'signup' || outcome === 'login');
  return outcome;
}

/**
 * The note that appears when the browser does not trust the address of the site.
 *
 * <p>Such a page is not a secure origin, so the browser hands no camera to it and the
 * face check and the code scanner cannot work.
 */
function insecureNote() {
  if (window.isSecureContext !== false) {
    return null;
  }
  return h('div.alert.alert-warning', [
    h('span', 'This address is not trusted by the browser, so the camera is not handed over to it. '
      + 'Install the certificate of the college (veriqra-certificate.cer) on this device and open '
      + 'the site again.')
  ]);
}

/**
 * A password field with a button that shows what is typed.
 *
 * @param {string} id
 * @param {string} name
 * @param {string} autocomplete
 * @param {string} placeholder
 * @returns {HTMLElement}
 */
function passwordField(id, name, autocomplete, placeholder) {
  const input = h('input.input', {
    id,
    name,
    type: 'password',
    required: true,
    autocomplete,
    placeholder
  });

  const toggle = h('button.input-action', {
    type: 'button',
    'aria-label': 'Show the password',
    onClick: () => {
      const hidden = input.type === 'password';
      input.type = hidden ? 'text' : 'password';
      toggle.setAttribute('aria-label', hidden ? 'Hide the password' : 'Show the password');
      replaceIcon(toggle, hidden ? 'eye-off' : 'eye');
    }
  }, icon('eye', { size: 16 }));

  return h('div.input-group', [input, toggle]);
}

/**
 * Swaps the drawing inside a button.
 */
function replaceIcon(node, name) {
  const drawing = node.querySelector('svg');
  if (drawing) {
    drawing.replaceWith(icon(name, { size: 16 }));
  }
}

/**
 * Shows the demonstration accounts, but only when they exist.
 *
 * <p>Only a few are named, so the dialog keeps its height, and the rest are counted.
 */
function demoHint(limit = 4) {
  if (!demoMode()) {
    return null;
  }
  const all = demoAccounts();
  if (all.length === 0) {
    return null;
  }
  const accounts = all.slice(0, limit);
  const rest = all.length - accounts.length;
  return h('div.demo-note', [
    h('strong', 'Demonstration accounts'),
    h('div.demo-accounts', [
      ...accounts.map((entry) => h('span.demo-account', entry)),
      rest > 0 ? h('span.demo-account', `+${rest} more`) : null
    ])
  ]);
}

/**
 * The card of the sign up form.
 *
 * @param {object} options
 * @param {(account: object) => void} options.onRegistered Called after the account is made.
 * @param {() => void} options.onSwitch Called when the person wants the other form.
 * @returns {HTMLElement}
 */
function signupCard({ onRegistered, onSwitch }) {
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
      passwordField('signup-password', 'password', 'new-password', 'At least 10 characters'),
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

  return h('div.auth-card', [
    h('div.brand', [h('img.brand-mark', { src: '/icons/logo.png', alt: '', width: '30', height: '30' }), h('span', 'VeriQra')]),
    h('p.eyebrow', 'Student sign up'),
    h('h1', 'Create your account'),
    h('p.lead', 'One step, then a quick face check and you are ready.'),
    form,
    insecureNote(),
    h('p.auth-switch', [
      'Already registered? ',
      h('a', {
        href: '#/login',
        onClick: (event) => {
          event.preventDefault();
          onSwitch();
        }
      }, 'Sign in instead')
    ])
  ]);
}
