/**
 * The API client.
 *
 * Every call goes through `request()` so the CSRF token, the credentials and the error
 * shape are handled in exactly one place.
 */

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

/** An error returned by the server. */
export class ApiError extends Error {
  constructor(code, message, status) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
  }

  /** True when the session is no longer valid. */
  get isUnauthenticated() {
    return this.status === 401;
  }

  /** True when the student still has to complete the identity check. */
  get isFaceVerificationRequired() {
    return this.code === 'FACE_VERIFICATION_REQUIRED';
  }
}

/**
 * Reads a cookie value.
 */
export function readCookie(name) {
  const prefix = `${name}=`;
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim();
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.slice(prefix.length));
    }
  }
  return null;
}

/**
 * Performs a request against the application API.
 *
 * @param {string} path Path below `/api`.
 * @param {object} [options]
 * @param {string} [options.method] HTTP method.
 * @param {object} [options.body] JSON payload.
 * @param {FormData} [options.form] Multipart payload.
 * @param {AbortSignal} [options.signal] Abort signal.
 * @returns {Promise<any>} The parsed response.
 */
export async function request(path, options = {}) {
  const { method = 'GET', body, form, signal, headers = {}, params } = options;

  const requestHeaders = { Accept: 'application/json', ...headers };
  if (body !== undefined) {
    requestHeaders['Content-Type'] = 'application/json';
  }
  if (method !== 'GET' && method !== 'HEAD') {
    const token = readCookie(CSRF_COOKIE);
    if (token) {
      requestHeaders[CSRF_HEADER] = token;
    }
  }

  const search = params ? query(params) : '';

  let response;
  try {
    response = await fetch(`/api${path}${search}`, {
      method,
      headers: requestHeaders,
      credentials: 'same-origin',
      signal,
      body: form !== undefined ? form : body !== undefined ? JSON.stringify(body) : undefined
    });
  } catch (error) {
    if (error.name === 'AbortError') {
      throw error;
    }
    throw new ApiError('NETWORK_ERROR', 'The server could not be reached. Check your connection.', 0);
  }

  if (response.status === 204) {
    return null;
  }

  const contentType = response.headers.get('content-type') || '';
  const payload = contentType.includes('application/json')
    ? await response.json().catch(() => null)
    : await response.text();

  if (!response.ok) {
    const code = payload && payload.code ? payload.code : `HTTP_${response.status}`;
    const message = payload && payload.message
      ? payload.message
      : `The request failed with status ${response.status}.`;
    throw new ApiError(code, message, response.status);
  }

  return payload;
}

/** Convenience wrappers. */
export const api = {
  get: (path, options) => request(path, { ...options, method: 'GET' }),
  post: (path, body, options) => request(path, { ...options, method: 'POST', body }),
  patch: (path, body, options) => request(path, { ...options, method: 'PATCH', body }),
  put: (path, body, options) => request(path, { ...options, method: 'PUT', body }),
  delete: (path, options) => request(path, { ...options, method: 'DELETE' }),
  upload: (path, form, options) => request(path, { ...options, method: 'POST', form })
};

/**
 * Builds a query string from a plain object, skipping empty values.
 */
export function query(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

/* -------------------------------------------------------------------------- */
/* Cache                                                                       */
/* -------------------------------------------------------------------------- */

/**
 * A small read cache for the JSON API.
 *
 * <p>Switching between the workspace screens used to wait for a round trip every time.
 * With the cache a screen that was visited before renders straight away from memory,
 * and the fresh answer replaces it in the background.
 */

const cache = new Map();

/** How long a cached answer is considered current, in milliseconds. */
const FRESH_FOR = 20_000;

/**
 * Reads a resource, using the cache when there is one.
 *
 * @param {string} path Path below `/api`.
 * @param {object} [options]
 * @param {number} [options.ttl] How long the answer stays current.
 * @param {object} [options.params] Query parameters.
 * @param {Function} [options.onRefresh] Called with the fresh answer when a stale
 *        value was returned and the background refresh finishes.
 * @returns {Promise<any>}
 */
export function cachedGet(path, options = {}) {
  const { ttl = FRESH_FOR, params, onRefresh } = options;
  const key = cacheKey(path, params);
  const entry = cache.get(key);
  const now = Date.now();

  if (entry) {
    if (entry.value !== undefined && now - entry.storedAt < ttl) {
      return Promise.resolve(entry.value);
    }
    if (entry.value !== undefined) {
      // Stale but usable. Show it now, refresh in the background.
      refresh(key, path, params).then((value) => {
        if (onRefresh) {
          onRefresh(value);
        }
      }).catch(() => {});
      return Promise.resolve(entry.value);
    }
    // A request is already on its way.
    return entry.pending || refresh(key, path, params);
  }

  return refresh(key, path, params);
}

/**
 * Warms the cache for a path without waiting for the answer.
 */
export function prefetch(path, options = {}) {
  const key = cacheKey(path, options.params);
  const entry = cache.get(key);
  if (entry && (entry.pending || (Date.now() - entry.storedAt < FRESH_FOR))) {
    return;
  }
  refresh(key, path, options.params).catch(() => {});
}

/**
 * Reads a resource again and again, so a screen with live numbers keeps itself
 * up to date and nobody has to press a refresh button.
 *
 * <p>The answer is written to the cache as well, so the next screen that needs the
 * same numbers has them at hand.
 *
 * @param {string} path Path below `/api`.
 * @param {object} [options]
 * @param {object} [options.params] Query parameters.
 * @param {Function} [options.onRefresh] Called with every fresh answer.
 * @param {number} [options.intervalMillis] How often the resource is read.
 * @returns {Function} A function that stops the polling.
 */
export function poll(path, options = {}) {
  const { params, onRefresh, intervalMillis = 30000 } = options;
  const key = cacheKey(path, params);
  const timer = window.setInterval(() => {
    refresh(key, path, params)
      .then((value) => {
        if (onRefresh) {
          onRefresh(value);
        }
      })
      .catch(() => {
        // A poll that fails is not worth a message: the screen keeps what it has.
      });
  }, intervalMillis);
  return () => window.clearInterval(timer);
}

/**
 * Drops cached answers, either everything or the ones below a path.
 */
export function invalidate(prefix) {
  if (!prefix) {
    cache.clear();
    return;
  }
  for (const key of [...cache.keys()]) {
    if (key.startsWith(prefix)) {
      cache.delete(key);
    }
  }
}

function cacheKey(path, params) {
  return params ? `${path}?${query(params)}` : path;
}

function refresh(key, path, params) {
  const existing = cache.get(key);
  if (existing && existing.pending) {
    return existing.pending;
  }
  const pending = request(path, { method: 'GET', params })
    .then((value) => {
      cache.set(key, { value, storedAt: Date.now(), pending: null });
      return value;
    })
    .catch((error) => {
      const previous = cache.get(key);
      cache.set(key, {
        value: previous ? previous.value : undefined,
        storedAt: previous ? previous.storedAt : 0,
        pending: null
      });
      throw error;
    });
  cache.set(key, {
    value: existing ? existing.value : undefined,
    storedAt: existing ? existing.storedAt : 0,
    pending
  });
  return pending;
}
