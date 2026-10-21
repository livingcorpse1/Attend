/**
 * The application entry point.
 *
 * It boots the session, decides whether the visitor sees the sign in screens or the
 * workspace, renders the shell and wires the routes.
 */

import { h, replaceChildren } from './dom.js';
import { api, prefetch } from './api.js';
import { applyTheme, currentAccount, institution, refreshAccount, setState, toggleTheme } from './store.js';
import {
  isCurrent,
  navigate,
  route,
  setContainerProvider,
  setGuard,
  setNotFound,
  startRouter
} from './router.js';
import { toast, viewSkeleton } from './ui.js';
import { renderLogin, renderSignup } from './views/auth.js';
import { renderVerification } from './views/verify.js';
import {
  renderBiometricSettings,
  renderStudentAttendance,
  renderStudentDashboard,
  renderStudentTimetable,
  renderVerifyPresence
} from './views/student.js';
import {
  renderFacultyAttendance,
  renderFacultyDashboard,
  renderFacultyReports,
  renderFacultyTimetable
} from './views/faculty.js';
import { renderMyClasses } from './views/classes.js';

const root = document.getElementById('app');

/** The navigation entries per role. */
const NAVIGATION = {
  STUDENT: [
    { path: '/dashboard', label: 'Dashboard', glyph: '⌂' },
    { path: '/verify-presence', label: 'Verify presence', glyph: '◎' },
    { path: '/attendance', label: 'My attendance', glyph: '◷' },
    { path: '/timetable', label: 'Timetable', glyph: '▤' },
    { path: '/biometric', label: 'Biometric data', glyph: '☺' }
  ],
  FACULTY: [
    { path: '/dashboard', label: 'Dashboard', glyph: '⌂' },
    { path: '/attendance', label: 'Take attendance', glyph: '◎' },
    { path: '/classes', label: 'My classes', glyph: '▤' },
    { path: '/timetable', label: 'Timetable', glyph: '▥' },
    { path: '/reports', label: 'Reports', glyph: '▥' }
  ]
};

/** The page title shown in the top bar. */
const PAGE_TITLES = {
  '/dashboard': 'Dashboard',
  '/attendance': 'Attendance',
  '/timetable': 'Timetable',
  '/verify-presence': 'Verify presence',
  '/biometric': 'Biometric data',
  '/reports': 'Reports',
  '/users': 'Accounts',
  '/classes': 'Subjects',
  '/settings': 'Settings',
  '/audit': 'Audit trail',
  '/verify-identity': 'Identity check'
};

/** The API call each screen needs, so it can be fetched before the screen is opened. */
const PREFETCH_BY_ROLE = {
  FACULTY: {
    '/dashboard': '/faculty/dashboard',
    '/attendance': '/faculty/classes',
    '/classes': '/faculty/structure',
    '/timetable': '/faculty/timetable',
    '/reports': '/faculty/reports/subjects'
  },
  STUDENT: {
    '/dashboard': '/student/dashboard',
    '/attendance': '/student/attendance',
    '/timetable': '/student/timetable',
    '/verify-presence': '/student/sessions/active',
    '/biometric': '/student/biometric/status'
  }
};

boot();

async function boot() {
  applyTheme();
  registerRoutes();

  try {
    const bootstrap = await api.get('/auth/session');
    setState({
      account: bootstrap.account,
      institution: bootstrap.institution || institution(),
      consentVersion: bootstrap.consentVersion || '',
      demoMode: Boolean(bootstrap.demoMode),
      demoAccounts: bootstrap.demoAccounts || []
    });
  } catch (failure) {
    setState({ account: null });
  }

  startRouter();
  registerServiceWorker();
}

/**
 * Refreshes the account and then goes to the dashboard.
 */
async function refreshAccountThenGoHome() {
  await refreshAccount();
  navigate('/dashboard');
}

/**
 * Gives the navigation to the router.
 */
function registerRoutes() {
  route('/login', () => renderAuthView('login'));
  route('/signup', () => renderAuthView('signup'));

  route('/dashboard', async ({ container, route }) => {
    const account = currentAccount();
    if (account.role === 'STUDENT') {
      await renderStudentDashboard(container, route);
    } else {
      await renderFacultyDashboard(container, route);
    }
  });

  route('/attendance', async ({ container, route }) => {
    const account = currentAccount();
    if (account.role === 'STUDENT') {
      await renderStudentAttendance(container, route);
    } else {
      await renderFacultyAttendance(container, route);
    }
  });

  route('/timetable', async ({ container, route }) => {
    const account = currentAccount();
    if (account.role === 'STUDENT') {
      await renderStudentTimetable(container, route);
    } else {
      await renderFacultyTimetable(container, route);
    }
  });

  route('/verify-presence', async ({ container, route }) => {
    await renderVerifyPresence(container, route);
  });

  route('/biometric', async ({ container, route }) => {
    await renderBiometricSettings(container, route);
  });

  route('/verify-identity', async ({ container, route }) => {
    await renderVerification(container, route, { onVerified: refreshAccountThenGoHome });
  });

  route('/reports', async ({ container, route }) => {
    await renderFacultyReports(container, route);
  });

  route('/classes', async ({ container, route }) => {
    await renderMyClasses(container, route);
  });

  setContainerProvider(({ path }) => {
    // The sign in screens are rendered on their own, they do not use the shell.
    if (path === '/login' || path === '/signup') {
      return null;
    }
    return ensureShell();
  });

  setNotFound(() => {
    const account = currentAccount();
    navigate(account ? '/dashboard' : '/login', { replace: true });
  });

  setGuard(async ({ path }) => {
    const account = currentAccount();
    const isPublic = path === '/login' || path === '/signup';

    if (!account) {
      if (isPublic) {
        return true;
      }
      navigate('/login', { replace: true });
      return false;
    }
    if (isPublic) {
      navigate('/dashboard', { replace: true });
      return false;
    }
    if (account.role === 'STUDENT' && account.faceState !== 'VERIFIED'
      && path !== '/verify-identity' && path !== '/biometric') {
      navigate('/verify-identity', { replace: true });
      return false;
    }
    return true;
  });
}

/**
 * Renders the sign in or the sign up screen.
 */
async function renderAuthView(mode) {
  replaceChildren(root, h('div', h('div.empty-state', 'Loading…')));
  const afterAuth = async () => {
    const bootstrap = await api.get('/auth/session');
    setState({ account: bootstrap.account, institution: bootstrap.institution || institution() });
    const account = currentAccount();
    navigate(account.role === 'STUDENT' && account.faceState !== 'VERIFIED' ? '/verify-identity' : '/dashboard');
  };
  if (mode === 'signup') {
    await renderSignup(root, afterAuth);
  } else {
    await renderLogin(root, afterAuth);
  }
}

/**
 * Makes sure the workspace shell exists and returns the element the pages render into.
 */
function ensureShell() {
  const existing = document.getElementById('view');
  if (existing) {
    highlightNavigation();
    return existing;
  }
  const account = currentAccount();
  if (!account) {
    return null;
  }
  const items = NAVIGATION[account.role] || [];

  const navItems = items.map((item) => h('a.nav-item', {
    href: `#${item.path}`,
    dataset: { path: item.path }
  }, [h('span.nav-icon', item.glyph), h('span', item.label)]));

  // Fetching the data of a screen the moment the pointer rests on it removes the wait
  // when the screen is actually opened.
  const prefetchMap = PREFETCH_BY_ROLE[account.role] || {};
  for (const link of navItems) {
    const path = link.dataset.path;
    const target = prefetchMap[path];
    if (target) {
      link.addEventListener('pointerenter', () => prefetch(target), { once: true });
      link.addEventListener('focus', () => prefetch(target), { once: true });
    }
  }

  // The live session list is needed as soon as the attendance screen opens, so it is
  // warmed together with the class list.
  if (prefetchMap['/attendance']) {
    prefetch('/faculty/classes');
    prefetch('/faculty/sessions', { params: { status: 'ACTIVE' } });
  }

  const content = h('section.content', { id: 'view' }, viewSkeleton());

  const shell = h('div.shell', [
    h('aside.sidebar', [
      h('div.brand', [h('span.brand-mark', 'V'), h('span', 'VeriQra')]),
      h('div.nav-group', [
        h('div.nav-label', 'Workspace'),
        h('nav.stack.stack-1', navItems)
      ]),
      h('div.sidebar-foot', [
        h('div.name', account.fullName),
        h('div.role', account.role.toLowerCase()),
        h('button.nav-item', { type: 'button', id: 'logout' },
          [h('span.nav-icon', '↪'), h('span', 'Sign out')])
      ])
    ]),
    h('div.main', [
      h('header.topbar', [
        h('h2', { id: 'page-title' }, PAGE_TITLES[currentPathSafe()] || 'VeriQra'),
        h('div.topbar-actions', [
          institution().name ? h('span.badge', institution().name) : null,
          h('button.icon-button', { type: 'button', id: 'theme-toggle', 'aria-label': 'Switch the colour theme' }, '◐')
        ])
      ]),
      content
    ])
  ]);

  replaceChildren(root, shell);
  shell.querySelector('#theme-toggle').onclick = () => toggleTheme();
  shell.querySelector('#logout').onclick = async () => {
    await api.post('/auth/logout');
    setState({ account: null });
    replaceChildren(root);
    navigate('/login');
  };

  highlightNavigation();
  return content;
}

/**
 * Marks the active navigation entry and updates the page title.
 */
function highlightNavigation() {
  const path = currentPathSafe();
  for (const link of document.querySelectorAll('.nav-item[data-path]')) {
    link.classList.toggle('is-active', link.dataset.path === path);
  }
  const title = document.getElementById('page-title');
  if (title) {
    title.textContent = PAGE_TITLES[path] || 'VeriQra';
  }
}

function currentPathSafe() {
  const hash = window.location.hash.replace(/^#/, '') || '/';
  return hash.length > 1 ? hash.replace(/\/+$/, '') : '/';
}

/**

function statTile(label, value, foot) {
  return h('div.stat', [
    h('div.stat-label', label),
    h('div.stat-value', value),
    h('div.stat-foot', foot)
  ]);
}

function tableOfClasses(classList) {
  if (!classList || classList.length === 0) {
    return h('div.empty-state', 'No classes have been created yet.');
  }
  return h('div.table-wrap', h('table.data', [
    h('thead', h('tr', [
      h('th', 'Code'), h('th', 'Subject'), h('th', 'Section'), h('th', 'Room'), h('th', 'Students')
    ])),
    h('tbody', classList.map((row) => h('tr', [
      h('td', row.subjectCode),
      h('td', row.subjectName),
      h('td', row.section),
      h('td', row.room),
      h('td.numeric', String(row.enrolled))
    ])))
  ]));
}

/**
 * Registers the service worker that makes the application installable.
 */
function registerServiceWorker() {
  if ('serviceWorker' in navigator && window.location.protocol !== 'file:') {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('/sw.js').catch(() => {
        // A missing service worker only costs offline support.
      });
    });
  }
}

window.addEventListener('offline', () => toast('You are offline. Cached screens keep working.', 'error'));
window.addEventListener('online', () => toast('Back online.', 'success'));
