/**
 * Hash based routing.
 *
 * The hash keeps the client a pure static bundle: any route can be opened directly and
 * the service worker can serve the same shell for all of them.
 */

/** The registered routes. */
const routes = new Map();

let notFoundHandler = null;
let beforeEach = null;
let containerProvider = null;
let afterEach = null;

/**
 * Registers a route.
 *
 * @param {string} path The path, for example `/attendance`.
 * @param {(context: {params: object, container: HTMLElement}) => Promise<Node>|Node} handler
 */
export function route(path, handler) {
  routes.set(normalise(path), handler);
}

/** Sets the handler used when no route matches. */
export function setNotFound(handler) {
  notFoundHandler = handler;
}

/** Sets a guard that runs before every navigation. */
export function setGuard(guard) {
  beforeEach = guard;
}

/**
 * Sets the function that provides the element a route renders into.
 *
 * @param {(context: {path: string}) => HTMLElement} provider
 */
export function setContainerProvider(provider) {
  containerProvider = provider;
}

/**
 * Sets the function that runs once a route has finished rendering.
 *
 * @param {(context: {path: string, container: HTMLElement}) => void} handler
 */
export function setAfterEach(handler) {
  afterEach = handler;
}

/** The current route path without the leading hash. */
export function currentPath() {
  const hash = window.location.hash.replace(/^#/, '');
  return normalise(hash || '/');
}

/** Navigates to a path. */
export function navigate(path, { replace = false } = {}) {
  const target = `#${normalise(path)}`;
  if (replace) {
    window.history.replaceState(null, '', target);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  } else if (window.location.hash !== target) {
    window.location.hash = target;
  } else {
    resolve();
  }
}

/** Starts listening for navigation events. */
export function startRouter() {
  window.addEventListener('hashchange', () => resolve());
  resolve();
}

/**
 * Runs the handler for the current route.
 */
export async function resolve() {
  const path = currentPath();
  const [base, ...segments] = path.split('/').filter(Boolean);
  const params = {};

  if (base && segments.length > 0 && /^\d+$/.test(segments[0])) {
    params.id = segments[0];
  }

  if (beforeEach) {
    const outcome = await beforeEach({ path, base, params });
    if (outcome === false) {
      return;
    }
    if (typeof outcome === 'string') {
      navigate(outcome, { replace: true });
      return;
    }
  }

  const handler = routes.get(path) || (base ? routes.get(`/${base}`) : null);
  if (!handler) {
    if (notFoundHandler) {
      await notFoundHandler({ path });
    }
    return;
  }
  const container = containerProvider ? containerProvider({ path }) : null;
  if (container) {
    // Whatever the previous screen left running is stopped before the new one starts.
    // A live attendance screen polls the server every second, and that has to stop
    // when the teacher moves to another page.
    if (typeof container.dispose === 'function') {
      container.dispose();
      container.dispose = null;
    }
    // The route is stamped on the container so a view that finishes late can tell that
    // the user has already moved on, and leave the newer screen alone.
    container.dataset.route = path;
  }
  await handler({ path, base, params, container, route: path });
  if (afterEach) {
    afterEach({ path, base, params, container, route: path });
  }
}

/**
 * True when the screen that is on the page is still the one the caller belongs to.
 *
 * @param {HTMLElement} container The view container.
 * @param {string} route The route the caller was rendering.
 */
export function isCurrent(container, route) {
  return Boolean(container) && container.dataset.route === route;
}

/**
 * Registers what a screen has to clean up when it is left.
 *
 * <p>A screen can hold more than one thing that runs: a poll of the server, a live
 * session, a camera. Every one of them registers here, and the router calls them all
 * when the teacher moves on. Without this the earlier ones would keep running, and the
 * server would answer more and more slowly.
 *
 * @param {HTMLElement} container The view container.
 * @param {Function} cleanup What has to be stopped.
 */
export function onDispose(container, cleanup) {
  if (!container) {
    return;
  }
  const previous = container.dispose;
  container.dispose = () => {
    if (typeof previous === 'function') {
      previous();
    }
    if (typeof cleanup === 'function') {
      cleanup();
    }
  };
}

function normalise(path) {
  if (!path || path === '/') {
    return '/';
  }
  const trimmed = String(path).replace(/^#/, '').replace(/\/+$/, '');
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`;
}
