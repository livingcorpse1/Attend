/**
 * The icon set.
 *
 * <p>Every icon is drawn on the same 24 unit grid with the same stroke, so they sit
 * next to each other without one of them shouting. They are inline SVG rather than an
 * icon font or an image: they inherit the colour of the text they sit in, they never
 * cause a flash while they load, and they are crisp on every screen.
 *
 * <p>The geometry follows Lucide, which is the drawing language Vercel uses.
 */

import { svg } from './dom.js';

/**
 * The drawings, keyed by name.
 *
 * <p>An entry is a list of elements. A string is shorthand for a path, the rest are
 * described explicitly.
 */
const ICONS = {
  dashboard: [
    { t: 'rect', x: 3, y: 3, w: 7, h: 9, rx: 1 },
    { t: 'rect', x: 14, y: 3, w: 7, h: 5, rx: 1 },
    { t: 'rect', x: 14, y: 12, w: 7, h: 9, rx: 1 },
    { t: 'rect', x: 3, y: 16, w: 7, h: 5, rx: 1 }
  ],
  qr: [
    { t: 'rect', x: 3, y: 3, w: 7, h: 7, rx: 1 },
    { t: 'rect', x: 14, y: 3, w: 7, h: 7, rx: 1 },
    { t: 'rect', x: 3, y: 14, w: 7, h: 7, rx: 1 },
    'M14 14h3v3h-3z',
    'M20 14v3',
    'M14 20h3'
  ],
  'clipboard-check': [
    { t: 'rect', x: 8, y: 2, w: 8, h: 4, rx: 1 },
    'M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2',
    'm9 14 2 2 4-4'
  ],
  calendar: [
    { t: 'rect', x: 3, y: 4, w: 18, h: 17, rx: 2 },
    'M16 2v4',
    'M8 2v4',
    'M3 10h18'
  ],
  'calendar-days': [
    { t: 'rect', x: 3, y: 4, w: 18, h: 17, rx: 2 },
    'M16 2v4',
    'M8 2v4',
    'M3 10h18',
    'M8 14h.01',
    'M12 14h.01',
    'M16 14h.01',
    'M8 18h.01',
    'M12 18h.01'
  ],
  'calendar-plus': [
    { t: 'rect', x: 3, y: 4, w: 18, h: 17, rx: 2 },
    'M16 2v4',
    'M8 2v4',
    'M3 10h18',
    'M12 14v4',
    'M10 16h4'
  ],
  'scan-face': [
    'M3 7V5a2 2 0 0 1 2-2h2',
    'M17 3h2a2 2 0 0 1 2 2v2',
    'M21 17v2a2 2 0 0 1-2 2h-2',
    'M7 21H5a2 2 0 0 1-2-2v-2',
    'M9 10v1.5',
    'M15 10v1.5',
    'M9.5 15.5a4 4 0 0 0 5 0'
  ],
  users: [
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2',
    { t: 'circle', cx: 9, cy: 7, r: 4 },
    'M22 21v-2a4 4 0 0 0-3-3.87',
    'M16 3.13a4 4 0 0 1 0 7.75'
  ],
  user: [
    'M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2',
    { t: 'circle', cx: 12, cy: 7, r: 4 }
  ],
  'user-plus': [
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2',
    { t: 'circle', cx: 9, cy: 7, r: 4 },
    'M19 8v6',
    'M22 11h-6'
  ],
  'user-check': [
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2',
    { t: 'circle', cx: 9, cy: 7, r: 4 },
    'm17 11 2 2 4-4'
  ],
  chart: [
    'M3 3v18h18',
    'M18 17V9',
    'M13 17V5',
    'M8 17v-3'
  ],
  'trending-up': ['m22 7-8.5 8.5-5-5L2 17', 'M16 7h6v6'],
  book: [
    'M4 19.5A2.5 2.5 0 0 1 6.5 17H20',
    'M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z'
  ],
  layers: [
    'm12.83 2.18 8 4a1 1 0 0 1 0 1.79l-8 4a1.77 1.77 0 0 1-1.66 0l-8-4a1 1 0 0 1 0-1.79l8-4a1.77 1.77 0 0 1 1.66 0',
    'm22 12.5-9.17 4.59a1 1 0 0 1-1.66 0L2 12.5',
    'm22 17.5-9.17 4.59a1 1 0 0 1-1.66 0L2 17.5'
  ],
  list: [
    'M8 6h13',
    'M8 12h13',
    'M8 18h13',
    'M3 6h.01',
    'M3 12h.01',
    'M3 18h.01'
  ],
  history: [
    'M3 12a9 9 0 1 0 3-6.7L3 8',
    'M3 3v5h5',
    'M12 7v5l4 2'
  ],
  file: [
    'M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z',
    'M14 2v4a2 2 0 0 0 2 2h4',
    'M10 9H8',
    'M16 13H8',
    'M16 17H8'
  ],
  sliders: [
    'M4 21v-7',
    'M4 10V3',
    'M12 21v-9',
    'M12 8V3',
    'M20 21v-5',
    'M20 12V3',
    'M1 14h6',
    'M9 8h6',
    'M17 16h6'
  ],
  building: [
    'M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z',
    'M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2',
    'M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2',
    'M10 6h4',
    'M10 10h4',
    'M10 14h4',
    'M10 18h4'
  ],
  shield: [
    'M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z',
    'm9 12 2 2 4-4'
  ],
  radio: [
    { t: 'circle', cx: 12, cy: 12, r: 2 },
    'M16.24 7.76a6 6 0 0 1 0 8.49',
    'M7.76 16.25a6 6 0 0 1 0-8.49',
    'M19.07 4.93a10 10 0 0 1 0 14.14',
    'M4.93 19.07a10 10 0 0 1 0-14.14'
  ],
  percent: [
    'M19 5 5 19',
    { t: 'circle', cx: 6.5, cy: 6.5, r: 2.5 },
    { t: 'circle', cx: 17.5, cy: 17.5, r: 2.5 }
  ],
  'log-out': [
    'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4',
    'm16 17 5-5-5-5',
    'M21 12H9'
  ],
  sun: [
    { t: 'circle', cx: 12, cy: 12, r: 4 },
    'M12 2v2',
    'M12 20v2',
    'm4.93 4.93 1.41 1.41',
    'm17.66 17.66 1.41 1.41',
    'M2 12h2',
    'M20 12h2',
    'm6.34 17.66-1.41 1.41',
    'm19.07 4.93-1.41 1.41'
  ],
  moon: ['M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z'],
  plus: ['M12 5v14', 'M5 12h14'],
  minus: ['M5 12h14'],
  close: ['M18 6 6 18', 'm6 6 12 12'],
  check: ['M20 6 9 17l-5-5'],
  'check-circle': [
    { t: 'circle', cx: 12, cy: 12, r: 10 },
    'm9 12 2 2 4-4'
  ],
  'chevron-right': ['m9 18 6-6-6-6'],
  'chevron-left': ['m15 18-6-6 6-6'],
  'chevron-down': ['m6 9 6 6 6-6'],
  'chevron-up': ['m18 15-6-6-6 6'],
  'arrow-right': ['M5 12h14', 'm12 5 7 7-7 7'],
  'arrow-left': ['M19 12H5', 'm12 19-7-7 7-7'],
  clock: [
    { t: 'circle', cx: 12, cy: 12, r: 10 },
    'M12 6v6l4 2'
  ],
  alert: [
    'm21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z',
    'M12 9v4',
    'M12 17h.01'
  ],
  'alert-circle': [
    { t: 'circle', cx: 12, cy: 12, r: 10 },
    'M12 8v4',
    'M12 16h.01'
  ],
  info: [
    { t: 'circle', cx: 12, cy: 12, r: 10 },
    'M12 16v-4',
    'M12 8h.01'
  ],
  trash: [
    'M3 6h18',
    'M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6',
    'M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2',
    'M10 11v6',
    'M14 11v6'
  ],
  download: [
    'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4',
    'm7 10 5 5 5-5',
    'M12 15V3'
  ],
  refresh: [
    'M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8',
    'M21 3v5h-5',
    'M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16',
    'M8 16H3v5'
  ],
  camera: [
    'M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3z',
    { t: 'circle', cx: 12, cy: 13, r: 3 }
  ],
  search: [
    { t: 'circle', cx: 11, cy: 11, r: 8 },
    'm21 21-4.3-4.3'
  ],
  filter: ['M3 4h18l-7 8v6l-4 2v-8Z'],
  play: ['M6 3.5 20 12 6 20.5z'],
  eye: [
    'M2.06 12.35a1 1 0 0 1 0-.7 10.75 10.75 0 0 1 19.88 0 1 1 0 0 1 0 .7 10.75 10.75 0 0 1-19.88 0',
    { t: 'circle', cx: 12, cy: 12, r: 3 }
  ],
  'eye-off': [
    'm2 2 20 20',
    'M6.71 6.71A10.94 10.94 0 0 0 2.06 12.65a1 1 0 0 0 0 .7 10.75 10.75 0 0 0 19.88 0 1 1 0 0 0 0-.7 10.75 10.75 0 0 0-5.06-5.06',
    'M10.73 5.08A10.43 10.43 0 0 1 12 5c4 0 7.6 3.05 9.94 7.65a1 1 0 0 1 0 .7 10.75 10.75 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24',
    'M14.12 14.12a3 3 0 1 1-4.24-4.24'
  ],
  mail: [
    { t: 'rect', x: 2, y: 4, w: 20, h: 16, rx: 2 },
    'm22 7-10 6L2 7'
  ],
  lock: [
    { t: 'rect', x: 3, y: 11, w: 18, h: 11, rx: 2 },
    'M7 11V7a5 5 0 0 1 10 0v4'
  ],
  smartphone: [
    { t: 'rect', x: 5, y: 2, w: 14, h: 20, rx: 2 },
    'M12 18h.01'
  ],
  grid: [
    { t: 'rect', x: 3, y: 3, w: 7, h: 7, rx: 1 },
    { t: 'rect', x: 14, y: 3, w: 7, h: 7, rx: 1 },
    { t: 'rect', x: 14, y: 14, w: 7, h: 7, rx: 1 },
    { t: 'rect', x: 3, y: 14, w: 7, h: 7, rx: 1 }
  ],
  'external-link': [
    'M15 3h6v6',
    'M10 14 21 3',
    'M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6'
  ],
  copy: [
    { t: 'rect', x: 9, y: 9, w: 13, h: 13, rx: 2 },
    'M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1'
  ],
  more: [
    { t: 'circle', cx: 12, cy: 12, r: 1 },
    { t: 'circle', cx: 19, cy: 12, r: 1 },
    { t: 'circle', cx: 5, cy: 12, r: 1 }
  ],
  sparkles: [
    'm12 3-1.9 5.8a2 2 0 0 1-1.3 1.3L3 12l5.8 1.9a2 2 0 0 1 1.3 1.3L12 21l1.9-5.8a2 2 0 0 1 1.3-1.3L21 12l-5.8-1.9a2 2 0 0 1-1.3-1.3z'
  ],
  menu: ['M4 6h16', 'M4 12h16', 'M4 18h16']
};

/** The icons that are filled rather than drawn, because the shape reads better solid. */
const FILLED = new Set(['play']);

/**
 * Builds an icon element.
 *
 * @param {string} name The name of the icon.
 * @param {object} [options]
 * @param {number} [options.size] The width and the height in pixels.
 * @param {string} [options.class] Extra class names.
 * @param {string} [options.label] An accessible name. Without it the icon is hidden
 *   from the screen reader, which is right when there is a label next to it.
 * @param {number} [options.stroke] The stroke width.
 * @returns {SVGElement}
 */
export function icon(name, options = {}) {
  const drawing = ICONS[name];
  if (!drawing) {
    throw new Error(`Unknown icon: ${name}`);
  }
  const size = options.size || 16;
  const filled = FILLED.has(name);

  const element = svg('svg', {
    xmlns: 'http://www.w3.org/2000/svg',
    viewBox: '0 0 24 24',
    width: size,
    height: size,
    fill: filled ? 'currentColor' : 'none',
    stroke: filled ? 'none' : 'currentColor',
    'stroke-width': filled ? '0' : (options.stroke || 1.6),
    'stroke-linecap': 'round',
    'stroke-linejoin': 'round',
    'aria-hidden': options.label ? null : 'true',
    role: options.label ? 'img' : null,
    focusable: 'false'
  });

  if (options.label) {
    element.append(svg('title', {}, options.label));
  }

  for (const part of drawing) {
    if (typeof part === 'string') {
      element.append(svg('path', { d: part }));
      continue;
    }
    if (part.t === 'circle') {
      element.append(svg('circle', { cx: part.cx, cy: part.cy, r: part.r }));
      continue;
    }
    if (part.t === 'rect') {
      element.append(svg('rect', {
        x: part.x,
        y: part.y,
        width: part.w,
        height: part.h,
        rx: part.rx === undefined ? 2 : part.rx
      }));
    }
  }

  if (options.class) {
    element.setAttribute('class', options.class);
  }
  return element;
}

/**
 * Builds an icon wrapped in a span, for the places where the icon has to be aligned
 * with text by its box rather than by its baseline.
 *
 * @param {string} name
 * @param {object} [options]
 * @returns {HTMLElement}
 */
export function iconSpan(name, options = {}) {
  const wrapper = document.createElement('span');
  wrapper.className = options.class ? `icon ${options.class}` : 'icon';
  wrapper.setAttribute('aria-hidden', 'true');
  wrapper.append(icon(name, options));
  return wrapper;
}

/**
 * The names of every icon in the set, for the tests.
 */
export function iconNames() {
  return Object.keys(ICONS);
}
