/**
 * Drives the application in a real browser and proves that the features work.
 *
 * <p>The camera is a fake device that plays a file, so the identity check and the
 * code scanner are tested with real pictures and not with a stub. The file is built by
 * {@code scripts/qr-picture.mjs} and {@code scripts/FakeCamera.java}.
 *
 * <p>Every check names what it proves. A failure is a failure of the application, not
 * of the test.
 *
 * Usage:
 *   node --experimental-websocket scripts/verify-features.mjs [base-url]
 */

import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const baseUrl = process.argv[2] || 'http://localhost:8080';
const debugPort = 9377;
const root = join(dirname(fileURLToPath(import.meta.url)), '..');

function settingFromEnvironmentFile(name, fallback) {
  const path = join(root, '.env');
  if (!existsSync(path)) {
    return fallback;
  }
  for (const line of readFileSync(path, 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    const separator = trimmed.indexOf('=');
    if (separator < 1) {
      continue;
    }
    if (trimmed.slice(0, separator).trim() === name) {
      return trimmed.slice(separator + 1).trim().replace(/^["']|["']$/g, '');
    }
  }
  return fallback;
}

const DEMO_PASSWORD = settingFromEnvironmentFile('DEMO_PASSWORD', 'Demo!2026');
const cameraFile = join(root, 'target', 'camera.y4m');

const browserPath = [
  process.env.CHROME_PATH,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
].filter(Boolean).find((candidate) => existsSync(candidate));

if (!browserPath) {
  console.error('No Chromium based browser was found.');
  process.exit(2);
}

const profileDirectory = mkdtempSync(join(tmpdir(), 'veriqra-verify-'));
const browser = spawn(browserPath, [
  '--headless=new',
  '--disable-gpu',
  '--no-sandbox',
  '--disable-extensions',
  '--no-first-run',
  '--disable-dev-shm-usage',
  '--use-fake-ui-for-media-stream',
  '--use-fake-device-for-media-stream',
  ...(existsSync(cameraFile) ? [`--use-file-for-fake-video-capture=${cameraFile}`] : []),
  `--user-data-dir=${profileDirectory}`,
  `--remote-debugging-port=${debugPort}`,
  'about:blank'
], { stdio: 'ignore' });

let socket = null;
let messageId = 0;
const pending = new Map();
const consoleErrors = [];

function send(method, params = {}) {
  const id = ++messageId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error(`The browser did not answer ${method} in time`));
      }
    }, 30000);
  });
}

async function evaluate(expression) {
  const result = await send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true
  });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || 'the page threw an error');
  }
  return result.result.value;
}

async function waitFor(predicate, description, timeoutMillis = 20000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMillis) {
    const value = await evaluate(predicate);
    if (value) {
      return value;
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Timed out while waiting for ${description}`);
}

async function connect() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${debugPort}/json/new?about:blank`, { method: 'PUT' });
      const target = await response.json();
      socket = new WebSocket(target.webSocketDebuggerUrl);
      await new Promise((resolve, reject) => {
        socket.addEventListener('open', resolve, { once: true });
        socket.addEventListener('error', reject, { once: true });
      });
      socket.addEventListener('message', (event) => {
        const message = JSON.parse(event.data);
        if (message.method === 'Runtime.exceptionThrown') {
          const details = message.params.exceptionDetails;
          consoleErrors.push(`exception: ${details.exception?.description || details.text}`);
        }
        if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
          consoleErrors.push(`console.error: ${message.params.args.map((a) => a.value ?? a.description).join(' ')}`);
        }
        if (message.id && pending.has(message.id)) {
          const handler = pending.get(message.id);
          pending.delete(message.id);
          if (message.error) {
            handler.reject(new Error(message.error.message));
          } else {
            handler.resolve(message.result);
          }
        }
      });
      return;
    } catch (error) {
      await new Promise((resolve) => setTimeout(resolve, 300));
    }
  }
  throw new Error('The browser did not expose a debugging endpoint');
}

async function navigate(url) {
  await send('Page.navigate', { url });
  await waitFor('document.readyState === "complete"', `the page ${url}`);
}

async function signIn(email, password) {
  const status = await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken },
      body: JSON.stringify({ email: ${JSON.stringify(email)}, password: ${JSON.stringify(password)} })
    });
    return response.status;
  })()`);
  if (status !== 200) {
    throw new Error(`The sign in for ${email} failed with status ${status}`);
  }
}

const checks = [];
function check(name, value, detail = '') {
  checks.push({ name, ok: Boolean(value) });
  console.log(`${value ? 'PASS' : 'FAIL'}  ${name}${detail ? ' · ' + detail : ''}`);
}

async function run() {
  await connect();
  await send('Page.enable');
  await send('Runtime.enable');

  /* ------------------------------------------------------- the sign in and the shell */

  await navigate(`${baseUrl}/#/login`);
  await waitFor('document.querySelector(".auth-card") !== null', 'the sign in screen');
  check('the sign in screen renders', true);

  // The home page of the site.
  await navigate(`${baseUrl}/`);
  await waitFor('document.querySelector(".home-title") !== null', 'the home page');
  const home = await evaluate(`(() => ({
    title: document.querySelector('.home-title').textContent.trim(),
    actions: [...document.querySelectorAll('.home-actions a')].map((a) => a.textContent.trim()),
    features: [...document.querySelectorAll('.home-feature h3')].map((h) => h.textContent.trim()),
    links: [...document.querySelectorAll('.home-nav-links a')].map((a) => a.textContent.trim())
  }))()`);
  check('the home page says who the product is for', home.title === 'Identity. Verified.', home.title);
  check('the home page offers the two actions',
    home.actions.join(',') === 'Get Started,Explore Platform', home.actions.join(', '));
  check('the home page explains the three features',
    home.features.length === 3, home.features.join(', '));
  check('the home page has the navigation',
    home.links.join(',') === 'Features,Security,Sign In', home.links.join(', '));

  // The sign in opens over the page, so the reader does not lose the home page.
  const dialog = await evaluate(`(async () => {
    document.querySelector('.home-nav-links a.button').click();
    await new Promise((r) => setTimeout(r, 700));
    return {
      hash: location.hash,
      modal: document.querySelector('.modal') !== null,
      card: document.querySelector('.modal .auth-card') !== null,
      title: (document.querySelector('.modal .auth-card h1') || {}).textContent,
      behind: document.querySelector('.home-title') !== null
    };
  })()`);
  check('the sign in opens as a dialog over the home page',
    dialog.modal && dialog.card && dialog.behind && (dialog.hash === '' || dialog.hash === '#/'),
    JSON.stringify(dialog));

  // The dialog carries both forms.
  const forms = await evaluate(`(() => {
    const link = document.querySelector('.modal .auth-switch a');
    link.click();
    return true;
  })()`);
  await waitFor('document.querySelector(".modal .auth-card h1") && '
    + 'document.querySelector(".modal .auth-card h1").textContent === "Create your account"',
  'the sign up dialog');
  const signUp = await evaluate(`(() => ({
    title: document.querySelector('.modal .auth-card h1').textContent,
    fields: [...document.querySelectorAll('.modal .auth-card input')].map((i) => i.name),
    switch: document.querySelector('.modal .auth-switch a').textContent
  }))()`);
  check('the dialog carries the sign up as well',
    signUp.title === 'Create your account' && signUp.fields.includes('studentNumber')
    && signUp.switch === 'Sign in instead', JSON.stringify(signUp));

  // The dialog can be closed without signing in.
  await evaluate('document.querySelector(".modal .icon-button").click()');
  await new Promise((resolve) => setTimeout(resolve, 400));
  check('the dialog closes without signing in',
    await evaluate('document.querySelector(".modal") === null'));

  // The form of the dialog itself has to sign in.
  const throughDialog = await evaluate(`(async () => {
    document.querySelector('.home-nav-links a.button').click();
    await new Promise((r) => setTimeout(r, 600));
    const form = document.querySelector('.modal form');
    form.querySelector('input[name="email"]').value = 'student@veriqra.local';
    form.querySelector('input[name="password"]').value = ${JSON.stringify(DEMO_PASSWORD)};
    form.requestSubmit();
    const started = Date.now();
    while (Date.now() - started < 15000 && !document.querySelector('.shell')) {
      await new Promise((r) => setTimeout(r, 250));
    }
    return {
      hash: location.hash,
      shell: document.querySelector('.shell') !== null,
      modal: document.querySelector('.modal') !== null
    };
  })()`);
  check('the sign in from the dialog opens the workspace',
    throughDialog.shell && !throughDialog.modal, JSON.stringify(throughDialog));

  await signIn('student@veriqra.local', DEMO_PASSWORD);
  await navigate(`${baseUrl}/`);
  await waitFor('document.querySelector(".shell") !== null', 'the student shell');
  check('the sign in puts the student in the workspace', true);

  // The sign out brings the person back to the home page, not to a sign in screen.
  await evaluate('location.hash = "#/profile"');
  await waitFor('location.hash === "#/profile" && document.querySelector("#view .card") !== null',
    'the profile of the student');
  await waitFor('[...document.querySelectorAll("#view h3")].some((h) => h.textContent.trim() === "Session")',
    'the session card of the profile');
  const signedOut = await evaluate(`(async () => {
    const button = [...document.querySelectorAll('#view button')].find((b) => b.textContent.trim() === 'Sign out');
    button.click();
    const started = Date.now();
    while (Date.now() - started < 10000 && !document.querySelector('.home-title')) {
      await new Promise((r) => setTimeout(r, 250));
    }
    return {
      hash: location.hash,
      home: document.querySelector('.home-title') !== null,
      shell: document.querySelector('.shell') !== null,
      dialog: document.querySelector('.modal') !== null
    };
  })()`);
  check('the sign out brings the person back to the home page',
    signedOut.home && !signedOut.shell && !signedOut.dialog
    && (signedOut.hash === '' || signedOut.hash === '#/'), JSON.stringify(signedOut));

  // The session is signed in again for the rest of the checks.
  await signIn('student@veriqra.local', DEMO_PASSWORD);
  await navigate(`${baseUrl}/`);
  await waitFor('document.querySelector(".shell") !== null', 'the student shell');

  // The session has to survive a reload.
  await send('Page.reload');
  await waitFor('document.readyState === "complete"', 'the reload');
  await waitFor('document.querySelector(".shell") !== null', 'the shell after the reload');
  check('the session survives a reload of the page', true);

  /* ------------------------------------------------------------- the menus and the shell */

  const menu = await evaluate(`(async () => {
    const links = [...document.querySelectorAll('.nav-item[data-path]')].map((a) => a.dataset.path);
    const results = [];
    for (const path of links) {
      document.querySelector('.nav-item[data-path="' + path + '"]').click();
      await new Promise((r) => setTimeout(r, 900));
      results.push({ path, hash: location.hash, title: (document.querySelector('#page-title') || {}).textContent });
    }
    return results;
  })()`);

  // Every screen of the student is opened and has to render.
  const studentScreens = [
    { path: '/dashboard', marker: '.stat-value' },
    { path: '/attendance', marker: '#view .card' },
    { path: '/timetable', marker: '#view .card' },
    { path: '/profile', marker: '#view .card' }
  ];
  for (const screen of studentScreens) {
    await evaluate(`location.hash = "#${screen.path}"`);
    const rendered = await waitFor(
      `document.querySelector("${screen.marker}") !== null && location.hash === "#${screen.path}"`,
      `the screen ${screen.path}`,
      12000
    ).then(() => true).catch(() => false);
    check(`the screen ${screen.path} of the student renders`, rendered);
  }
  for (const entry of menu) {
    if (entry.path === '/verify-presence') {
      // The screen that marks the attendance is closed until the identity check is
      // done, and the student is sent to the profile where the check lives.
      const verified = await evaluate(`(async () => {
        const { currentAccount } = await import('/js/store.js');
        return Boolean(currentAccount()?.faceState === 'VERIFIED');
      })()`);
      check('the screen that marks the attendance is closed until the check is done',
        verified ? entry.hash.includes(entry.path) : entry.hash.includes('/profile'),
        `the address is ${entry.hash}`);
      continue;
    }
    check(`the menu entry ${entry.path} opens its screen`, entry.hash.includes(entry.path),
      `the address is ${entry.hash}`);
  }

  const account = await evaluate(`(async () => {
    const button = document.querySelector('#user-menu');
    if (!button) {
      return { exists: false };
    }
    const name = button.querySelector('.name');
    button.click();
    await new Promise((r) => setTimeout(r, 400));
    const menu = document.querySelector('.menu');
    const entries = menu ? [...menu.querySelectorAll('.menu-item')].map((item) => item.textContent.trim()) : [];
    return { exists: true, name: name ? name.textContent.trim() : '', entries };
  })()`);
  check('the name of the account is at the bottom of the menu',
    account.exists && account.name.length > 0, account.name);
  check('the menu of the account opens the profile',
    (account.entries || []).some((entry) => /profile/i.test(entry)),
    (account.entries || []).join(', '));

  const scannerShortcut = await evaluate(`Boolean(document.querySelector('#scan-shortcut'))`);
  check('the student has a button to scan the code right away', scannerShortcut);

  /* ------------------------------------------------------------------- the identity check */

  await evaluate('location.hash = "#/profile"');
  await waitFor('document.querySelector(".verify-steps") !== null', 'the profile');
  check('the profile holds the identity check', true);

  const check1 = await evaluate(`(async () => {
    const card = [...document.querySelectorAll('.card')].find((node) => {
      const heading = node.querySelector('.card-header h3');
      return heading && /identity check/i.test(heading.textContent);
    });
    const start = card ? [...card.querySelectorAll('button')]
      .find((b) => /start|run/i.test(b.textContent)) : null;
    if (!start) {
      return { error: 'the identity check has no start button' };
    }
    start.click();
    await new Promise((r) => setTimeout(r, 3500));
    const video = document.querySelector('.camera-frame video');
    return {
      width: video ? video.videoWidth : 0,
      mirrored: video ? getComputedStyle(video).transform : ''
    };
  })()`);
  check('the camera of the identity check shows a picture', check1.width > 0,
    check1.error || `the picture is ${check1.width} pixels wide`);
  check('the preview of the camera is mirrored like a mirror',
    check1.mirrored !== 'none' && check1.mirrored !== '', check1.mirrored);

  const check2 = await evaluate(`(async () => {
    const capture = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('Start ·'));
    if (!capture) {
      return { error: 'the capture button is missing' };
    }
    capture.click();
    await new Promise((r) => setTimeout(r, 16000));
    return {
      frames: document.querySelectorAll('.capture-strip img').length,
      text: document.body.innerText
    };
  })()`);
  check('the identity check captures the frames', check2.frames === 5, `${check2.frames} frames`);
  const saidDark = /too dark/i.test(check2.text || '');
  check('the server does not call a lit portrait too dark', !saidDark,
    saidDark ? 'the server said the scene was too dark' : 'the picture was accepted');
  const buttonStuck = /Capturing…/.test(check2.text || '');
  check('the capture button does not stay on capturing', !buttonStuck);
  const liveness = (check2.text || '').match(/(head (?:movement|turn)[^.]*\.|turn your head[^.]*\.|live person[^.]*\.)/i);
  console.log(`      the identity check said: ${liveness ? liveness[0] : 'nothing about the movement'}`);
  const alerts = await evaluate(`[...document.querySelectorAll('.alert')].map((n) => n.textContent.trim())`);
  console.log('      the alerts on the profile: ' + JSON.stringify(alerts));

  /* -------------------------------------------------------------------- the code scanner */

  // The scanner is put on its own, because the screen that marks the attendance is
  // closed for a student whose identity check is still open. What is tested here is
  // that the camera reads the code of the classroom.
  const scan = await evaluate(`(async () => {
    const { scannerPanel } = await import('/js/qr.js');
    const scanner = scannerPanel();
    document.body.appendChild(scanner.panel);
    let captured = null;
    window.addEventListener('veriqra:qr', (event) => { captured = event.detail; }, { once: true });
    await scanner.start();
    await new Promise((r) => setTimeout(r, 9000));
    scanner.stop();
    return { captured, status: scanner.status.textContent };
  })()`);
  check('the scanner reads the code of the classroom', Boolean(scan.captured),
    scan.captured ? 'the code was found' : `the scanner said: ${scan.status}`);

  /* ------------------------------------------------------------------- the teacher screens */

  await signIn('faculty@veriqra.local', DEMO_PASSWORD);
  await navigate(`${baseUrl}/`);
  await waitFor('document.querySelector(".shell") !== null', 'the teacher shell');

  // Every screen of the teacher is opened and has to render.
  const teacherScreens = [
    { path: '/dashboard', marker: '.stat-value' },
    { path: '/attendance', marker: '#view .card' },
    { path: '/classes', marker: '#view .card' },
    { path: '/timetable', marker: '.scheduler-cell' },
    { path: '/reports', marker: '#view .card' },
    { path: '/profile', marker: '#view .card' }
  ];
  for (const screen of teacherScreens) {
    await evaluate(`location.hash = "#${screen.path}"`);
    const rendered = await waitFor(
      `document.querySelector("${screen.marker}") !== null && location.hash === "#${screen.path}"`,
      `the screen ${screen.path}`,
      12000
    ).then(() => true).catch(() => false);
    check(`the screen ${screen.path} of the teacher renders`, rendered);
  }

  await evaluate('location.hash = "#/timetable"');
  await waitFor('document.querySelector(".scheduler-cell") !== null', 'the timetable');
  const geometry = await evaluate(`(() => {
    const hours = [...document.querySelectorAll('.scheduler-hour')].map((node) => ({
      label: node.textContent.trim(), top: Math.round(node.getBoundingClientRect().top)
    }));
    const slots = [...document.querySelectorAll('.scheduler-slot')].map((node) => ({
      text: node.textContent.trim(), top: Math.round(node.getBoundingClientRect().top)
    }));
    return { hours, slots };
  })()`);
  const slotAligned = geometry.slots.every((slot) =>
    geometry.hours.some((hour) => Math.abs(hour.top - slot.top) < 3
      // A lecture that starts half past the hour sits half a row lower.
      || Math.abs(hour.top + 29 - slot.top) < 3));
  check('every lecture in the week grid sits on the line of its hour', slotAligned,
    JSON.stringify(geometry.slots.map((s) => s.top)));

  // The notes of the tiles of the dashboard have to stand on the same line, even when
  // one of the tiles carries a meter. Only the tiles that stand in the same row count.
  await evaluate('location.hash = "#/dashboard"');
  await waitFor('document.querySelector(".stat-value") !== null', 'the dashboard');
  const rows = await evaluate(`(() => {
    const tiles = [...document.querySelectorAll('.stat')].map((node) => {
      const box = node.getBoundingClientRect();
      const foot = node.querySelector('.stat-foot');
      const meter = node.querySelector('.meter');
      return {
        top: Math.round(box.top),
        height: Math.round(box.height),
        foot: foot ? Math.round(foot.getBoundingClientRect().top) : null,
        meter: meter ? Math.round(meter.getBoundingClientRect().top) : null,
        margin: foot ? getComputedStyle(foot).marginTop : null,
        display: getComputedStyle(node).display,
        rows: getComputedStyle(node).gridTemplateRows,
        align: getComputedStyle(node).alignContent,
        children: node.children.length
      };
    });
    const groups = new Map();
    for (const tile of tiles) {
      if (!groups.has(tile.top)) { groups.set(tile.top, []); }
      groups.get(tile.top).push(tile);
    }
    return [...groups.values()];
  })()`);
  const rowsAligned = rows.every((tilesInRow) => new Set(tilesInRow.map((t) => t.foot)).size === 1);
  check('the notes of the tiles in a row stand on the same line', rowsAligned,
    JSON.stringify(rows));

  await evaluate('location.hash = "#/timetable"');
  await waitFor('document.querySelector(".scheduler-cell") !== null', 'the timetable');

  // A lecture that is added has to show up without a reload.
  const added = await evaluate(`(async () => {
    const before = document.querySelectorAll('.scheduler-slot').length;
    const button = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('Add a lecture'));
    button.click();
    await new Promise((r) => setTimeout(r, 900));
    const dialog = document.querySelector('.modal');
    if (!dialog) {
      return { error: 'the editor did not open' };
    }
    const selects = [...dialog.querySelectorAll('select')];
    const times = [...dialog.querySelectorAll('input[type=time]')];
    const save = [...dialog.querySelectorAll('button')].find((b) => /add the lecture/i.test(b.textContent));
    if (!save || selects.length < 2 || times.length < 2) {
      return { error: 'the editor is missing its fields' };
    }
    // A time that nothing else uses, so the server does not refuse a double booking.
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };
    const lectures = await (await fetch('/api/faculty/timetable', { headers })).json();
    const taken = new Set(lectures.map((entry) => entry.dayOfWeek + '@' + entry.starts));
    let day = '2';
    let start = '14:00';
    for (let hour = 14; hour < 20 && taken.has(day + '@' + String(hour).padStart(2, '0') + ':00'); hour += 1) {
      start = String(hour).padStart(2, '0') + ':00';
    }
    selects[1].value = day;
    times[0].value = start;
    const hour = Number(start.slice(0, 2));
    times[1].value = String(Math.min(22, hour + 1)).padStart(2, '0') + ':00';
    save.click();
    await new Promise((r) => setTimeout(r, 3000));
    const session2 = await (await fetch('/api/auth/session')).json();
    const headers2 = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': session2.csrfToken };
    const lectures2 = await (await fetch('/api/faculty/timetable', { headers: headers2 })).json();
    return {
      before,
      after: document.querySelectorAll('.scheduler-slot').length,
      onTheServer: lectures2.length,
      start,
      day,
      toasts: [...document.querySelectorAll('.toast')].map((node) => node.textContent.trim()),
      options: selects[0].options.length
    };
  })()`);
  check('a lecture that is added shows up at once', added.after === added.before + 1,
    added.error || `${added.before} before, ${added.after} after, ${added.onTheServer} on the server`
      + (added.toasts ? ` · ${added.toasts.join(' | ')}` : '')
      + (added.options ? ` · ${added.options} classes` : ''));

  // The lecture the check added is removed again, so a run leaves no trace.
  await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };
    const lectures = await (await fetch('/api/faculty/timetable', { headers })).json();
    const lecture = lectures.find((entry) => entry.dayOfWeek === ${added.day || 2} && entry.starts === ${JSON.stringify(added.start || '14:00')});
    if (lecture) {
      await fetch('/api/faculty/timetable/' + lecture.id, { method: 'DELETE', headers });
    }
    return true;
  })()`);

  // The classes screen holds only the subjects.
  await evaluate('location.hash = "#/classes"');
  await waitFor('document.querySelector("#view .card") !== null', 'the classes');
  const classes = await evaluate(`document.body.innerText`);
  check('the classes screen does not offer the removed actions',
    !classes.includes('Enrol a student') && !classes.includes('Add a colleague')
    && !classes.includes('Add a class section'));

  /* -------------------------------------------------------------- the calendar of a fortnight */

  await evaluate('location.hash = "#/dashboard"');
  await waitFor('document.querySelector(".calendar-day") !== null', 'the calendar');
  const calendar = await evaluate(`(async () => {
    const read = () => {
      const chips = [...document.querySelectorAll('.calendar-chip')].map((chip) => {
        const cell = chip.closest('.calendar-day');
        return {
          date: cell ? cell.querySelector('.calendar-date').textContent.trim() : null,
          text: chip.textContent.trim()
        };
      });
      const card = document.querySelector('.calendar').closest('.card');
      const heading = card ? card.querySelector('.card-header h3') : null;
      return { chips, label: heading ? heading.textContent.trim() : '' };
    };
    const before = read();
    const arrow = [...document.querySelectorAll('.card-header .icon-button')].pop();
    arrow.click();
    await new Promise((r) => setTimeout(r, 400));
    arrow.click();
    await new Promise((r) => setTimeout(r, 400));
    const after = read();
    return { before, after };
  })()`);
  check('the lectures of the calendar follow the dates',
    calendar.before.label !== calendar.after.label
      && calendar.before.chips.length > 0
      && calendar.after.chips.length === 0,
    `the range went from "${calendar.before.label}" to "${calendar.after.label}" and the lectures from ${JSON.stringify(calendar.before.chips.map((c) => c.date))} to ${JSON.stringify(calendar.after.chips.map((c) => c.date))}`);

  /* ------------------------------------------------------------------- the live session */
  await evaluate('location.hash = "#/attendance"');
  await waitFor('document.querySelector("#view .card") !== null', 'the attendance screen');
  const session = await evaluate(`(async () => {
    const buttons = [...document.querySelectorAll('.lecture-row button')];
    const button = buttons.find((b) => !b.disabled);
    if (!button) {
      return { skipped: 'no lecture of the day is running' };
    }
    const label = button.textContent.trim();
    button.click();
    await new Promise((r) => setTimeout(r, 4000));
    return {
      label,
      buttons: buttons.length,
      started: document.querySelector('.qr-stage canvas') !== null,
      stage: document.querySelector('.qr-stage') !== null,
      text: document.body.innerText.slice(0, 200)
    };
  })()`);
  if (session.skipped) {
    console.log(`      the live session was not measured: ${session.skipped}`);
  } else {
    check('a session of a running lecture projects the rotating code', session.started);

    // The live channel: the server pushes the change, so the roster is up to date at once.
    const live = await evaluate(`(async () => {
      const { state } = await import('/js/live.js');
      await new Promise((r) => setTimeout(r, 1500));
      return state();
    })()`);
    check('the live channel is open', live.open, JSON.stringify(live));

    // Ending the session has to close it on the server and clear the screen.
    const ended = await evaluate(`(async () => {
      const button = document.querySelector('#end');
      if (!button) {
        return { error: 'there is no end button' };
      }
      button.click();
      await new Promise((r) => setTimeout(r, 700));
      const dialog = document.querySelector('.modal');
      if (!dialog) {
        return { error: 'the question was not asked' };
      }
      const confirm = [...dialog.querySelectorAll('button')]
        .find((b) => /end session/i.test(b.textContent));
      if (!confirm) {
        return { error: 'the dialog has no confirm button' };
      }
      confirm.click();
      await new Promise((r) => setTimeout(r, 3000));
      const bootstrap = await (await fetch('/api/auth/session')).json();
      const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };
      const open = await (await fetch('/api/faculty/sessions?status=ACTIVE', { headers })).json();
      return {
        openSessions: open.length,
        qrOnScreen: document.querySelector('.qr-stage') !== null
      };
    })()`);
    check('ending a session closes it and clears the screen',
      ended.error === undefined && ended.openSessions === 0 && !ended.qrOnScreen,
      ended.error || `the server still has ${ended.openSessions} open session(s), the code is on the screen: ${ended.qrOnScreen}`);
  }

  /* ------------------------------------------------------------- the stale session */

  // The token of the browser is made stale, the way it happens when the server was
  // restarted while the page was open. The request has to recover by itself.
  const stale = await evaluate(`(async () => {
    document.cookie = 'XSRF-TOKEN=00000000-0000-0000-0000-000000000000; path=/';
    const { request } = await import('/js/api.js');
    try {
      await request('/faculty/timetable', { method: 'POST', body: { classId: 0, dayOfWeek: 1, starts: '07:00', ends: '08:00' } });
      return { status: 200 };
    } catch (failure) {
      return { status: failure.status, code: failure.code, message: failure.message };
    }
  })()`);
  check('a request with a stale token is tried again',
    stale.code && stale.code !== 'HTTP_403', JSON.stringify(stale));

  const recovery = await evaluate(`(async () => {
    const { request } = await import('/js/api.js');
    try {
      await request('/faculty/timetable', { method: 'POST', body: { classId: 0, dayOfWeek: 1, starts: '07:00', ends: '08:00' } });
      return { recovered: false };
    } catch (failure) {
      return { recovered: failure.code === 'SESSION_ENDED' || failure.status === 403, code: failure.code };
    }
  })()`);
  check('a request that the framework refuses is reported clearly',
    recovery.recovered === true, JSON.stringify(recovery));

  /* ---------------------------------------------------------------------- the institution */

  const institution = await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    return bootstrap.institution;
  })()`);
  check('the institution carries the name that was asked for',
    institution.name.toLowerCase() === 'apsit college', institution.name);

  /* ------------------------------------------------------------------- the sign out */

  await evaluate('location.hash = "#/profile"');
  await waitFor('document.querySelector("#view .card") !== null', 'the profile');
  const signOut = await evaluate(`(() => {
    const buttons = [...document.querySelectorAll('#view button')].map((b) => b.textContent.trim());
    return buttons;
  })()`);
  check('the sign out is in the profile', signOut.some((label) => /sign out/i.test(label)),
    signOut.join(', '));

  /* --------------------------------------------------------------------- the live refresh */

  await evaluate('location.hash = "#/dashboard"');
  await waitFor('document.querySelector(".stat-value") !== null', 'the dashboard');
  const before = await evaluate(`(() => {
    const tiles = [...document.querySelectorAll('.stat')];
    const tile = tiles.find((t) => t.textContent.includes('Sessions held'));
    return tile ? tile.querySelector('.stat-value').textContent.trim() : null;
  })()`);
  const started = await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };
    const lectures = await (await fetch('/api/faculty/schedule', { headers })).json();
    if (!lectures.length) {
      return { skipped: 'there is no lecture of the day' };
    }
    const response = await fetch('/api/faculty/sessions?slotId=' + lectures[0].id, { method: 'POST', headers });
    if (!response.ok) {
      return { skipped: 'the lecture is not running' };
    }
    return { started: true };
  })()`);
  if (started.skipped) {
    console.log(`      the live refresh was not measured: ${started.skipped}`);
  } else {
    let after = before;
    const startedAt = Date.now();
    while (Date.now() - startedAt < 45000) {
      after = await evaluate(`(() => {
        const tiles = [...document.querySelectorAll('.stat')];
        const tile = tiles.find((t) => t.textContent.includes('Sessions held'));
        return tile ? tile.querySelector('.stat-value').textContent.trim() : null;
      })()`);
      if (after !== before) {
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    check('the dashboard keeps itself up to date', after !== before,
      `the sessions held went from ${before} to ${after}`);
  }

  /* ------------------------------------------------------------------------- the branding */

  const branding = await evaluate(`(async () => {
    const logo = document.querySelector('.brand-mark');
    const icon = document.querySelector('link[rel=icon]');
    const response = await fetch('/icons/logo.png');
    const favicon = await fetch(icon.href);
    return {
      logo: logo ? logo.getAttribute('src') : null,
      logoLoaded: logo ? logo.naturalWidth : 0,
      icon: icon ? icon.getAttribute('href') : null,
      logoStatus: response.status,
      faviconStatus: favicon.status
    };
  })()`);
  check('the mark of the application is the picture of the brand',
    branding.logo === '/icons/logo.png' && branding.logoLoaded > 0, branding.logo);
  check('the icon of the browser is served', branding.faviconStatus === 200, branding.icon);
}

let failed = false;
try {
  await run();
} catch (error) {
  failed = true;
  console.error(`\nThe check could not finish: ${error.message}`);
}

if (consoleErrors.length > 0) {
  failed = true;
  console.error('\nThe browser reported errors:');
  for (const entry of consoleErrors) {
    console.error(`    ${entry}`);
  }
}

const failedChecks = checks.filter((entry) => !entry.ok);
console.log(`\n${checks.length - failedChecks.length}/${checks.length} checks passed.`);

try {
  socket?.close();
} catch (error) {
  // Nothing to do.
}
browser.kill();
await new Promise((resolve) => setTimeout(resolve, 400));
try {
  rmSync(profileDirectory, { recursive: true, force: true, maxRetries: 5 });
} catch (error) {
  // The operating system will clear the temporary folder.
}
process.exit(failed || failedChecks.length > 0 ? 1 : 0);
