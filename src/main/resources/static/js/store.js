/**
 * Application state.
 *
 * A tiny observable store. Components subscribe to the slices they care about instead
 * of polling the DOM.
 */

const state = {
  account: null,
  institution: { name: '', location: '' },
  consentVersion: '',
  demoMode: false,
  demoAccounts: [],
  theme: localStorage.getItem('veriqra.theme') || 'light',
  online: navigator.onLine
};

const listeners = new Set();

/** The current state. */
export function getState() {
  return { ...state };
}

/** The signed in account, or null. */
export function currentAccount() {
  return state.account;
}

/** The institution details shown in the shell. */
export function institution() {
  return state.institution;
}

/** The version of the biometric notice the student has to accept. */
export function consentVersion() {
  return state.consentVersion;
}

/** True when the demonstration accounts exist. */
export function demoMode() {
  return state.demoMode;
}

/** The demonstration accounts, for the sign in screen. */
export function demoAccounts() {
  return state.demoAccounts;
}

/** The active colour theme. */
export function theme() {
  return state.theme;
}

/**
 * Merges a patch into the state and notifies the subscribers.
 */
export function setState(patch) {
  Object.assign(state, patch);
  for (const listener of listeners) {
    listener(state);
  }
}

/**
 * Registers a listener. It returns a function that removes the listener again.
 */
export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Applies the theme to the document.
 */
export function applyTheme() {
  document.documentElement.setAttribute('data-theme', state.theme);
  document.querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', state.theme === 'dark' ? '#0a1020' : '#101a2e');
}

/**
 * Switches between the light and the dark theme.
 */
export function toggleTheme() {
  const next = state.theme === 'dark' ? 'light' : 'dark';
  localStorage.setItem('veriqra.theme', next);
  setState({ theme: next });
  applyTheme();
}
