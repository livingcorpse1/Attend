/**
 * The application entry point.
 *
 * It boots the session, decides whether the visitor sees the sign in screens or the
 * workspace, renders the shell and wires the routes.
 */

import { h, replaceChildren } from './dom.js';
import { api, prefetch } from './api.js';
import { applyTheme, currentAccount, institution, refreshAccount, setState, theme, toggleTheme } from './store.js';
import {
  isCurrent,
  navigate,
  route,
  setAfterEach,
  setContainerProvider,
  setGuard,
  setNotFound,
  startRouter
} from './router.js';
import { toast, viewSkeleton } from './ui.js';
import { icon } from './icons.js';
import { renderLogin, renderSignup } from './views/auth.js';
import {
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
import { renderProfile } from './views/profile.js';

const root = document.getElementById('app');

/** The navigation entries per role. */
const NAVIGATION = {
  STUDENT: [
    { path: '/dashboard', label: 'Dashboard', glyph: 'dashboard' },
    { path: '/verify-presence', label: 'Verify presence', glyph: 'qr' },
    { path: '/attendance', label: 'My attendance', glyph: 'clipboard-check' },
    { path: '/timetable', label: 'Timetable', glyph: 'calendar' }
  ],
  FACULTY: [
    { path: '/dashboard', label: 'Dashboard', glyph: 'dashboard' },
    { path: '/attendance', label: 'Take attendance', glyph: 'radio' },
    { path: '/classes', label: 'My classes', glyph: 'book' },
    { path: '/timetable', label: 'Timetable', glyph: 'calendar' },
    { path: '/reports', label: 'Reports', glyph: 'chart' }
  ]
};

/** The page title shown in the top bar. */
const PAGE_TITLES = {
  '/dashboard': 'Dashboard',
  '/attendance': 'Attendance',
  '/timetable': 'Timetable',
  '/verify-presence': 'Verify presence',
  '/profile': 'Profile',
  '/reports': 'Reports',
  '/classes': 'Subjects',
  '/settings': 'Settings',
  '/audit': 'Audit trail',
  '/verify-identity': 'Profile'
};

/** The API call each screen needs, so it can be fetched before the screen is opened. */
const PREFETCH_BY_ROLE = {
  FACULTY: {
    '/dashboard': '/faculty/dashboard',
    '/attendance': '/faculty/classes',
    '/classes': '/faculty/structure',
    '/timetable': '/faculty/timetable',
    '/reports': '/faculty/reports/subjects',
    '/profile': '/faculty/structure'
  },
  STUDENT: {
    '/dashboard': '/student/dashboard',
    '/attendance': '/student/attendance',
    '/timetable': '/student/timetable',
    '/verify-presence': '/student/sessions/active',
    '/profile': '/student/biometric/status'
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

  route('/classes', async ({ container, route }) => {
    await renderMyClasses(container, route);
  });

  route('/reports', async ({ container, route }) => {
    await renderFacultyReports(container, route);
  });

  route('/profile', async ({ container, route }) => {
    await renderProfile(container, route);
  });

  // The identity check and the biometric data live in the profile now.
  route('/verify-identity', () => {
    navigate('/profile', { replace: true });
  });

  route('/biometric', () => {
    navigate('/profile', { replace: true });
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
    if (account.role === 'STUDENT' && account.faceState !== 'VERIFIED' && path === '/verify-presence') {
      // The attendance cannot be marked before the check, so the student is sent to the
      // profile where the check lives. Every other screen stays open.
      navigate('/profile', { replace: true });
      return false;
    }
    return true;
  });

  // A screen that has just been opened is announced, and the focus moves to it, so
  // that a keyboard user does not have to hunt for the new content.
  setAfterEach(({ path, container }) => {
    if (!container) {
      return;
    }
    announce(PAGE_TITLES[path] || 'VeriQra');
    enterView(container);
  });
}

/**
 * Renders the sign in or the sign up screen.
 */
async function renderAuthView(mode) {
  replaceChildren(root, h('div.empty-state', [h('div.glyph', h('div.spinner')), h('p', 'Loading…')]));
  const afterAuth = async () => {
    const bootstrap = await api.get('/auth/session');
    setState({ account: bootstrap.account, institution: bootstrap.institution || institution() });
    const account = currentAccount();
    navigate(account.role === 'STUDENT' && account.faceState !== 'VERIFIED' ? '/profile' : '/dashboard');
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
    dataset: { path: item.path, label: item.label }
  }, [
    h('span.nav-icon', icon(item.glyph, { size: 16 })),
    h('span.nav-item-label', item.label)
  ]));

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

  const content = h('section.content', { id: 'view', tabindex: '-1' }, viewSkeleton());

  const shell = h('div.shell', [
    h('aside.sidebar', [
      h('a.brand', { href: '#/dashboard' }, [
        h('img.brand-mark', { src: '/icons/logo.png', alt: '', width: '22', height: '22' }),
        h('span.brand-text', [
          h('span', 'VeriQra'),
          institution().name ? h('small', institution().name) : null
        ])
      ]),
      h('div.nav-group', [
        h('div.nav-label', 'Workspace'),
        h('nav.stack.stack-1', navItems)
      ]),
      h('div.sidebar-foot', [
        h('button.sidebar-user', {
          type: 'button',
          id: 'user-menu',
          'aria-haspopup': 'menu',
          'aria-expanded': 'false'
        }, [
          h('span.avatar', initials(account.fullName)),
          h('span.sidebar-user-text.grow', [
            h('span.name', account.fullName),
            h('span.role', account.role.toLowerCase())
          ]),
          h('span.nav-icon', icon('more', { size: 16 }))
        ])
      ])
    ]),
    h('div.main', [
      h('header.topbar', [
        h('h2', { id: 'page-title' }, PAGE_TITLES[currentPathSafe()] || 'VeriQra'),
        h('div.topbar-actions', [
          institution().name ? h('span.badge', institution().name) : null,
          account.role === 'STUDENT'
            ? h('a.button.button-primary.button-sm', { href: '#/verify-presence', id: 'scan-shortcut' },
              [icon('qr', { size: 14 }), h('span', 'Scan the code')])
            : null,
          h('button.icon-button', {
            type: 'button',
            id: 'theme-toggle',
            'aria-label': 'Switch the colour theme'
          }, icon(theme() === 'dark' ? 'sun' : 'moon', { size: 16 }))
        ])
      ]),
      content
    ])
  ]);

  replaceChildren(root, shell);

  shell.querySelector('#theme-toggle').onclick = () => {
    toggleTheme();
    const button = shell.querySelector('#theme-toggle');
    replaceChildren(button, icon(theme() === 'dark' ? 'sun' : 'moon', { size: 16 }));
  };

  shell.querySelector('#user-menu').onclick = (event) => {
    event.stopPropagation();
    openUserMenu(event.currentTarget);
  };

  highlightNavigation();
  return content;
}

/**
 * Opens the account menu under the user button in the rail.
 */
function openUserMenu(anchor) {
  closeMenus();
  const account = currentAccount();

  const menu = h('div.menu', { role: 'menu' }, [
    h('div.menu-item', { style: { cursor: 'default' } }, [
      h('span', [
        h('div', { style: { fontWeight: '500' } }, account.fullName),
        h('div.text-xs.text-subtle', account.email || account.role.toLowerCase())
      ])
    ]),
    h('div.menu-separator'),
    h('button.menu-item', {
      type: 'button',
      role: 'menuitem',
      onClick: () => {
        closeMenus();
        navigate('/profile');
      }
    }, [icon('user', { size: 16 }), h('span', 'Your profile')]),
    h('button.menu-item.is-danger', {
      type: 'button',
      role: 'menuitem',
      onClick: () => {
        closeMenus();
        signOut();
      }
    }, [icon('log-out', { size: 16 }), h('span', 'Sign out')])
  ]);

  document.body.append(menu);
  positionMenu(menu, anchor, 'top');

  anchor.setAttribute('aria-expanded', 'true');
  menu.dataset.anchor = anchor.id;
  menu.id = 'user-menu-panel';

  setTimeout(() => {
    document.addEventListener('click', closeMenus, { once: true });
    document.addEventListener('keydown', onMenuKeyDown);
  }, 0);
}

/**
 * Places a menu next to its anchor, inside the window.
 */
function positionMenu(menu, anchor, placement) {
  const bounds = anchor.getBoundingClientRect();
  menu.style.position = 'fixed';
  menu.style.zIndex = '100';
  menu.style.visibility = 'hidden';
  menu.style.left = '0px';
  menu.style.top = '0px';

  const size = menu.getBoundingClientRect();
  const left = Math.min(
    Math.max(8, bounds.left),
    window.innerWidth - size.width - 8
  );

  let top = bounds.top - size.height - 8;
  if (placement !== 'top' || top < 8) {
    top = bounds.bottom + 8;
  }
  if (top + size.height > window.innerHeight - 8) {
    top = Math.max(8, window.innerHeight - size.height - 8);
  }

  menu.style.left = `${left}px`;
  menu.style.top = `${top}px`;
  menu.style.visibility = 'visible';
}

/**
 * Closes every open menu.
 */
function closeMenus() {
  for (const menu of document.querySelectorAll('.menu[id]')) {
    menu.remove();
  }
  for (const button of document.querySelectorAll('[aria-expanded="true"]')) {
    button.setAttribute('aria-expanded', 'false');
  }
  document.removeEventListener('keydown', onMenuKeyDown);
}

function onMenuKeyDown(event) {
  if (event.key === 'Escape') {
    closeMenus();
  }
}

/**
 * Signs the account out and returns to the sign in screen.
 */
async function signOut() {
  await api.post('/auth/logout');
  setState({ account: null });
  replaceChildren(root);
  navigate('/login');
}

/**
 * Marks the active navigation entry and updates the page title.
 */
function highlightNavigation() {
  const path = currentPathSafe();
  for (const link of document.querySelectorAll('.nav-item[data-path]')) {
    const active = link.dataset.path === path;
    link.classList.toggle('is-active', active);
    if (active) {
      link.setAttribute('aria-current', 'page');
    } else {
      link.removeAttribute('aria-current');
    }
  }
  const title = document.getElementById('page-title');
  if (title) {
    title.textContent = PAGE_TITLES[path] || 'VeriQra';
  }
}

/**
 * The initials of a name, for the avatar in the rail.
 */
function initials(fullName) {
  const parts = String(fullName || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return '?';
  }
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * Announces a screen to the screen reader.
 */
function announce(message) {
  const region = document.getElementById('live-region');
  if (region) {
    region.textContent = message;
  }
}

/**
 * Moves the focus to the screen that was just opened.
 */
function enterView(container) {
  if (container && typeof container.focus === 'function') {
    container.focus({ preventScroll: true });
  }
}

function currentPathSafe() {
  const hash = window.location.hash.replace(/^#/, '') || '/';
  return hash.length > 1 ? hash.replace(/\/+$/, '') : '/';
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
