/**
 * The home page of VeriQra.
 *
 * <p>It is the first thing a person sees, so it carries the promise of the product in
 * one line and lets the rest of the page be read in a few seconds. There is no picture
 * on it: the type, the space and the grey do the work.
 */

import { h } from '../dom.js';
import { icon } from '../icons.js';

const FEATURES = [
  {
    glyph: 'qr',
    title: 'QR verification',
    text: 'The teacher projects a code that changes every second and is signed by the server. '
      + 'A screenshot is worthless after a few seconds, so a code cannot be passed on.'
  },
  {
    glyph: 'scan-face',
    title: 'Face and video verification',
    text: 'The camera looks at the student and the server decides. A movement is asked for, '
      + 'the frames are compared, and the identity is checked against the enrolment.'
  },
  {
    glyph: 'clipboard-check',
    title: 'Attendance tracking',
    text: 'Every session is a lecture of the teaching diary. The roster fills in as the students '
      + 'arrive, and a correction is written to the audit trail with its reason.'
  }
];

const SAFEGUARDS = [
  'The face check runs on the server, never in the browser.',
  'The templates are stored as a descriptor, not as a photograph.',
  'Every change of a record is written to an audit trail with its reason.',
  'The attendance follows the teaching diary, so a record cannot be invented.'
];

/**
 * Renders the home page.
 *
 * @param {HTMLElement} container
 * @param {object} [options]
 * @param {() => void} [options.onSignIn] Called when the reader asks to sign in.
 * @param {() => void} [options.onSignUp] Called when the reader asks to make an account.
 */
export function renderHome(container, { onSignIn, onSignUp } = {}) {
  // The links to the sections scroll to them. The address of the page stays on the home
  // page, so a reload brings the reader back to the top and not to a half read page.
  const toSection = (id) => (event) => {
    event.preventDefault();
    const section = document.getElementById(id);
    if (section) {
      section.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  };

  // The sign in opens over the page, so the reader does not lose the place they are in.
  const opensDialog = (event, action) => {
    if (!action) {
      return;
    }
    event.preventDefault();
    action();
  };

  const header = h('header.home-nav', [
    h('a.home-brand', { href: '#/' }, [
      h('img', { src: '/icons/logo.png', alt: '', width: '28', height: '28' }),
      h('span', 'VeriQra')
    ]),
    h('nav.home-nav-links', [
      h('a', { href: '#features', onClick: toSection('features') }, 'Features'),
      h('a', { href: '#security', onClick: toSection('security') }, 'Security'),
      h('a.button.button-primary.button-sm', {
        href: '#/login',
        onClick: (event) => opensDialog(event, onSignIn)
      }, 'Sign In')
    ])
  ]);

  const hero = h('section.home-hero', [
    h('p.home-eyebrow', 'Attendance, verified'),
    h('h1.home-title', 'Identity. Verified.'),
    h('p.home-lead', 'Attendance that cannot be faked, built on a classroom code that changes '
      + 'every second and a face check that runs on the server.'),
    h('div.home-actions', [
      h('a.button.button-primary.button-lg', {
        href: '#/signup',
        onClick: (event) => opensDialog(event, onSignUp)
      }, 'Get Started'),
      h('a.button.button-secondary.button-lg', { href: '#features', onClick: toSection('features') },
        'Explore Platform')
    ])
  ]);

  const features = h('section.home-section', { id: 'features' }, [
    h('p.home-eyebrow', 'Features'),
    h('h2.home-heading', 'Three checks, one record'),
    h('div.home-features', FEATURES.map((feature) => h('article.home-feature', [
      h('span.home-feature-glyph', icon(feature.glyph, { size: 20 })),
      h('h3', feature.title),
      h('p', feature.text)
    ])))
  ]);

  const security = h('section.home-section', { id: 'security' }, [
    h('p.home-eyebrow', 'Security'),
    h('h2.home-heading', 'Built so that a record means something'),
    h('ul.home-safeguards', SAFEGUARDS.map((item) => h('li', [
      h('span.home-tick', icon('check', { size: 14 })),
      h('span', item)
    ])))
  ]);

  const footer = h('footer.home-foot', [
    h('span', 'VeriQra'),
    h('span.home-foot-muted', 'Presence, verified.')
  ]);

  container.replaceChildren(h('div.home', [header, hero, features, security, footer]));
}
