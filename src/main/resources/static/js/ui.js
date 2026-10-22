/**
 * Shared interface helpers: formatting, toasts, modals and table rendering.
 */

import { h, replaceChildren } from './dom.js';
import { icon } from './icons.js';

/* -------------------------------------------------------------------------- */
/* Formatting                                                                  */
/* -------------------------------------------------------------------------- */

const DATE_TIME = new Intl.DateTimeFormat(undefined, {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit'
});

const DATE_ONLY = new Intl.DateTimeFormat(undefined, {
  day: '2-digit',
  month: 'short',
  year: 'numeric'
});

const TIME_ONLY = new Intl.DateTimeFormat(undefined, {
  hour: '2-digit',
  minute: '2-digit'
});

/** Formats a timestamp for humans. */
export function formatDateTime(value) {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : DATE_TIME.format(date);
}

/** Formats a date without the time. */
export function formatDate(value) {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : DATE_ONLY.format(date);
}

/** Formats a time of day. */
export function formatTime(value) {
  if (!value) {
    return '—';
  }
  if (value instanceof Date) {
    return TIME_ONLY.format(value);
  }
  const [hours, minutes] = String(value).split(':');
  if (hours === undefined || minutes === undefined) {
    return String(value);
  }
  const date = new Date();
  date.setHours(Number(hours), Number(minutes), 0, 0);
  return TIME_ONLY.format(date);
}

/**
 * Formats a start and an end time as one range.
 *
 * <p>The half day is only written once, so a lecture reads as "9:00 – 10:00 am" instead
 * of repeating it on both ends.
 */
export function formatRange(start, end) {
  if (!start || !end) {
    return '—';
  }
  const startDate = asDate(start);
  const endDate = asDate(end);
  if (!startDate || !endDate) {
    return `${start} – ${end}`;
  }
  const sameHalf = startDate.getHours() < 12 === (endDate.getHours() < 12);
  const format = (date, withHalf) => {
    const hours = date.getHours();
    const minutes = String(date.getMinutes()).padStart(2, '0');
    const twelve = hours % 12 === 0 ? 12 : hours % 12;
    const half = withHalf ? (hours < 12 ? 'am' : 'pm') : '';
    return `${twelve}:${minutes}${half ? ' ' + half : ''}`;
  };
  return sameHalf
    ? `${format(startDate, false)} – ${format(endDate, true)}`
    : `${format(startDate, true)} – ${format(endDate, true)}`;
}

function asDate(value) {
  if (value instanceof Date) {
    return value;
  }
  const [hours, minutes] = String(value).split(':');
  if (hours === undefined || minutes === undefined) {
    return null;
  }
  const date = new Date();
  date.setHours(Number(hours), Number(minutes), 0, 0);
  return date;
}

/** Formats a number with a fixed number of decimals. */
export function formatNumber(value, decimals = 0) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '—';
  }
  return number.toFixed(decimals);
}

/** Formats a percentage. */
export function formatPercentage(value, decimals = 1) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '—';
  }
  return `${number.toFixed(decimals)}%`;
}

/** Turns `ATTENDANCE_RECORDED` into `Attendance recorded`. */
export function humanise(value) {
  if (!value) {
    return '—';
  }
  const text = String(value).replace(/[_-]+/g, ' ').toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** The weekday name for a 1 based weekday number. */
export function weekdayName(dayOfWeek) {
  const names = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  return names[Number(dayOfWeek) - 1] || '—';
}

/** The short weekday name for a 1 based weekday number. */
export function weekdayShort(dayOfWeek) {
  const names = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  return names[Number(dayOfWeek) - 1] || '—';
}

/* -------------------------------------------------------------------------- */
/* Toasts                                                                      */
/* -------------------------------------------------------------------------- */

let toastStack = null;

/** The icon that goes with each kind of message. */
const TOAST_ICONS = {
  info: 'info',
  success: 'check-circle',
  error: 'alert-circle'
};

/**
 * Shows a short lived message.
 *
 * @param {string} message
 * @param {'info'|'success'|'error'} [variant]
 */
export function toast(message, variant = 'info') {
  if (!toastStack) {
    toastStack = h('div.toast-stack', { role: 'status', 'aria-live': 'polite' });
    document.body.append(toastStack);
  }
  const node = h(`div.toast${variant === 'info' ? '' : `.is-${variant}`}`, [
    icon(TOAST_ICONS[variant] || 'info', { size: 16 }),
    h('span.grow', message)
  ]);
  toastStack.append(node);
  window.setTimeout(() => {
    node.classList.add('is-leaving');
    window.setTimeout(() => node.remove(), 160);
  }, variant === 'error' ? 5200 : 3200);
}

/* -------------------------------------------------------------------------- */
/* Modal                                                                       */
/* -------------------------------------------------------------------------- */

/**
 * Opens a modal dialog and resolves with the value passed to `close`.
 *
 * <p>The focus is trapped inside the dialog while it is open, the page behind it
 * cannot scroll, and the focus goes back to whatever opened it when it closes.
 *
 * @param {object} options
 * @param {string} options.title
 * @param {(ctx: {close: (value: any) => void}) => Node} options.body
 * @param {(ctx: {close: (value: any) => void}) => Node[]} [options.actions]
 * @returns {Promise<any>}
 */
export function openModal({ title, body, actions = [] }) {
  return new Promise((resolve) => {
    let settled = false;
    const previousFocus = document.activeElement;
    const backdrop = h('div.modal-backdrop', {
      role: 'dialog',
      'aria-modal': 'true',
      'aria-label': title
    });

    const close = (value) => {
      if (settled) {
        return;
      }
      settled = true;
      document.removeEventListener('keydown', onKeyDown);
      backdrop.remove();
      document.body.style.removeProperty('overflow');
      // The focus goes back where it came from, so the keyboard does not lose its place.
      if (previousFocus && previousFocus.isConnected) {
        previousFocus.focus();
      }
      resolve(value);
    };

    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        close(undefined);
        return;
      }
      if (event.key !== 'Tab') {
        return;
      }
      // The focus cannot leave the dialog while it is open.
      const focusable = focusableWithin(dialog);
      if (focusable.length === 0) {
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    const context = { close };
    const dialog = h('div.modal', [
      h('div.modal-header', [
        h('h3', title),
        h('button.icon-button', { type: 'button', 'aria-label': 'Close', onClick: () => close(undefined) },
          icon('close', { size: 16 }))
      ]),
      h('div.modal-body', body(context)),
      actions.length > 0 ? h('div.modal-footer', actions.map((action) => action(context))) : null
    ]);

    backdrop.append(dialog);
    backdrop.addEventListener('mousedown', (event) => {
      if (event.target === backdrop) {
        close(undefined);
      }
    });
    document.addEventListener('keydown', onKeyDown);
    document.body.append(backdrop);
    document.body.style.overflow = 'hidden';
    // The first field gets the focus, so the form can be filled without reaching for
    // the mouse. The close button is only the fallback.
    const first = dialog.querySelector('input, select, textarea');
    (first || dialog.querySelector('button'))?.focus();
  });
}

/**
 * Every element inside a node that can hold the focus.
 */
function focusableWithin(node) {
  return [...node.querySelectorAll(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), '
    + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )].filter((element) => element.offsetParent !== null);
}

/** Shows a confirmation dialog and resolves to true when the action is confirmed. */
export function confirmDialog({ title, message, confirmLabel = 'Confirm', variant = 'primary' }) {
  return openModal({
    title,
    body: () => h('p.text-sm', message),
    actions: [
      (ctx) => h('button.button.button-ghost', { type: 'button', onClick: () => ctx.close(false) }, 'Cancel'),
      (ctx) => h(`button.button.button-${variant}`, { type: 'button', onClick: () => ctx.close(true) }, confirmLabel)
    ]
  }).then((value) => value === true);
}

/* -------------------------------------------------------------------------- */
/* Content                                                                      */
/* -------------------------------------------------------------------------- */

/**
 * Renders a table from a list of rows.
 *
 * @param {Array<object>} rows
 * @param {Array<{key: string, label?: string, align?: 'left'|'right', render?: Function}>} columns
 * @param {object} [options]
 * @param {string} [options.empty] Message shown when there is no data.
 */
export function table(rows, columns, options = {}) {
  if (!rows || rows.length === 0) {
    return emptyState(options.empty || 'Nothing to show yet.', options.emptyGlyph || 'inbox');
  }
  const head = h('thead', h('tr', columns.map((column) =>
    h(`th${column.align === 'right' ? '.numeric' : ''}`, column.label || humanise(column.key)))));

  const body = h('tbody', rows.map((row) => h('tr', columns.map((column) => {
    const content = column.render ? column.render(row) : row[column.key];
    const cell = h(`td${column.align === 'right' ? '.numeric' : ''}`);
    if (content instanceof Node) {
      cell.append(content);
    } else if (content === null || content === undefined || content === '') {
      cell.textContent = '—';
      cell.style.color = 'var(--text-tertiary)';
    } else {
      cell.textContent = String(content);
    }
    return cell;
  }))));

  return h('div.table-wrap', h('table.data', head, body));
}

/**
 * Renders an empty state block.
 *
 * @param {string} message
 * @param {string|Node} [glyph] The name of an icon, or an element.
 */
export function emptyState(message, glyph = 'inbox') {
  const mark = typeof glyph === 'string' ? icon(glyph, { size: 20 }) : glyph;
  return h('div.empty-state', [h('div.glyph', mark), h('p', message)]);
}

/**
 * Renders a skeleton placeholder while data loads.
 */
export function skeleton(rows = 4) {
  return h('div.stack.stack-3', Array.from({ length: rows }, () =>
    h('div.skeleton', { style: { height: '18px' } })));
}

/**
 * The placeholder for a page that is still loading.
 *
 * <p>It mirrors the shape of a workspace screen so the layout does not jump when the
 * real content arrives.
 */
export function viewSkeleton() {
  return h('div.stack.stack-5', [
    h('div.stack.stack-2', [
      h('div.skeleton', { style: { height: '28px', width: '260px' } }),
      h('div.skeleton', { style: { height: '14px', width: '380px' } })
    ]),
    h('div.stat-grid', Array.from({ length: 4 }, () => h('div.stat', [
      h('div.skeleton', { style: { height: '10px', width: '90px' } }),
      h('div.skeleton', { style: { height: '30px', width: '70px', marginTop: '6px' } })
    ]))),
    h('div.card', h('div.card-body', h('div.stack.stack-3', [
      h('div.skeleton', { style: { height: '14px', width: '45%' } }),
      h('div.skeleton', { style: { height: '14px', width: '80%' } }),
      h('div.skeleton', { style: { height: '14px', width: '60%' } }),
      h('div.skeleton', { style: { height: '14px', width: '70%' } })
    ])))
  ]);
}

/**
 * Renders a stat tile.
 *
 * @param {object} options
 * @param {string} options.label
 * @param {string|number} options.value
 * @param {string} [options.foot]
 * @param {'good'|'warn'|'bad'} [options.tone]
 * @param {number} [options.meter]
 * @param {string} [options.glyph] The name of an icon.
 */
export function statTile({ label, value, foot, tone, meter, glyph }) {
  const toneClass = tone ? ` is-${tone}` : '';
    // Every tile carries the same parts, and the row of the meter always has the same
    // height, so the note of every tile in a row stands on the same line.
    const children = [];
    if (glyph) {
      children.push(h('div.stat-glyph', icon(glyph, { size: 16 })));
    }
    children.push(h('div.stat-label', label));
    children.push(h(`div.stat-value${toneClass}`, value));
    children.push(h('div.stat-meter', meter !== undefined && meter !== null
      ? h(`div.meter${meter < 75 ? '.is-low' : ''}`,
        h('span', { style: { width: `${Math.min(100, Math.max(0, meter))}%` } }))
      : null));
    children.push(h('div.stat-foot', foot || ''));
  return h('div.stat', children);
}

/**
 * Renders a status badge for an attendance outcome.
 */
export function statusBadge(status) {
  const variants = {
    PRESENT: 'badge-success',
    ABSENT: 'badge-danger',
    EXCUSED: 'badge-warning',
    PENDING: 'badge',
    ACTIVE: 'badge-success',
    CLOSED: 'badge',
    EXPIRED: 'badge'
  };
  return h(`span.badge.badge-dot.${variants[status] || 'badge'}`, humanise(status));
}

/**
 * Renders the header of a screen.
 *
 * <p>Every screen starts with the same three things: a title, a line that says what
 * the screen is for, and the actions that belong to it. Building them here keeps them
 * looking the same on every screen.
 *
 * @param {object} options
 * @param {string} options.title
 * @param {string} [options.lead]
 * @param {Node|Node[]} [options.actions]
 * @returns {HTMLElement}
 */
export function pageHead({ title, lead, actions }) {
  return h('div.page-head', [
    h('div', [
      h('h1', title),
      lead ? h('p.lead', lead) : null
    ]),
    actions ? h('div.page-head-actions', actions) : null
  ]);
}

/**
 * Renders an inline error block.
 */
export function errorBlock(message) {
  return h('div.alert.alert-error', message);
}

/**
 * Renders a page level error with a retry button.
 */
export function errorState(message, onRetry) {
  return h('div.card.card-pad.stack.stack-4', [
    h('div.alert.alert-error', message),
    onRetry ? h('button.button.button-secondary', { type: 'button', onClick: onRetry }, 'Try again') : null
  ]);
}

/**
 * Fills a container with a loading placeholder and swaps in the content when ready.
 */
export async function renderInto(container, loader) {
  replaceChildren(container, skeleton());
  try {
    const content = await loader();
    replaceChildren(container, content);
  } catch (error) {
    replaceChildren(container, errorState(error.message || 'Something went wrong.', () => renderInto(container, loader)));
  }
}

/**
 * Groups timetable slots by weekday.
 */
export function groupByDay(slots) {
  const groups = new Map();
  for (const slot of slots || []) {
    const day = Number(slot.dayOfWeek);
    if (!groups.has(day)) {
      groups.set(day, []);
    }
    groups.get(day).push(slot);
  }
  return [...groups.entries()].sort((left, right) => left[0] - right[0]);
}

/**
 * Groups the timetable into a per day layout.
 */
export function timetableView(slots) {
  const groups = groupByDay(slots);
  if (groups.length === 0) {
    return emptyState('No timetable has been published yet.', 'calendar');
  }
  return h('div', groups.map(([day, daySlots]) => h('section.day-group', [
    h('header', [
      h('span', weekdayName(day)),
      h('span', `${daySlots.length} ${daySlots.length === 1 ? 'session' : 'sessions'}`)
    ]),
    daySlots.map((slot) => h('div.slot', [
      h('div.time', `${formatTime(slot.starts)} – ${formatTime(slot.ends)}`),
      h('div', [
        h('div.subject', `${slot.subjectCode} · ${slot.subjectName}`),
        h('div.meta', `${slot.section}${slot.room ? ` · ${slot.room}` : ''}${slot.facultyName ? ` · ${slot.facultyName}` : ''}`)
      ]),
      h('span.badge', slot.facultyName ? 'Teaching' : 'Enrolled')
    ]))
  ])));
}
