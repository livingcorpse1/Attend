#!/usr/bin/env node
/**
 * Takes screenshots of the application screens for a visual check.
 *
 * Usage:  node --experimental-websocket scripts/screenshots.mjs [base-url] [output-dir]
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const baseUrl = process.argv[2] || 'http://localhost:8080';
const debugPort = 9334;

/**
 * Reads a setting from the .env file of the project, so the script uses the same
 * credentials as the application instead of carrying its own copy.
 */
function settingFromEnvironmentFile(name, fallback) {
  const path = join(dirname(fileURLToPath(import.meta.url)), '..', '.env');
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

const ADMIN_EMAIL = settingFromEnvironmentFile('BOOTSTRAP_ADMIN_EMAIL', 'admin@veriqra.local');
const ADMIN_PASSWORD = settingFromEnvironmentFile('BOOTSTRAP_ADMIN_PASSWORD', 'AdminDemo!26');
const DEMO_PASSWORD = settingFromEnvironmentFile('DEMO_PASSWORD', 'Demo!2026');

const outputDirectory = process.argv[3] || join(process.cwd(), 'target', 'screenshots');

const browserPath = [
  process.env.CHROME_PATH,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
].filter(Boolean).find((candidate) => existsSync(candidate));

if (!browserPath) {
  console.error('No Chromium based browser was found.');
  process.exit(2);
}

mkdirSync(outputDirectory, { recursive: true });
const profileDirectory = mkdtempSync(join(tmpdir(), 'veriqra-shots-'));
const browser = spawn(browserPath, [
  '--headless=new', '--disable-gpu', '--no-sandbox', '--disable-extensions', '--no-first-run',
  '--hide-scrollbars', '--window-size=1440,960',
  `--user-data-dir=${profileDirectory}`, `--remote-debugging-port=${debugPort}`, 'about:blank'
], { stdio: 'ignore' });

let socket = null;
let messageId = 0;
const pending = new Map();

function send(method, params = {}) {
  const id = ++messageId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
    setTimeout(() => {
      if (pending.has(id)) { pending.delete(id); reject(new Error(`${method} timed out`)); }
    }, 30000);
  });
}

async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || 'the page threw an error');
  }
  return result.result.value;
}

async function connect() {
  const started = Date.now();
  while (Date.now() - started < 20000) {
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
          console.error(`  exception: ${details.exception?.description || details.text}`);
        }
        if (message.method === 'Runtime.consoleAPICalled') {
          console.log(`  console.${message.params.type}: ${message.params.args.map((a) => a.value ?? a.description).join(' ')}`);
        }
        if (message.id && pending.has(message.id)) {
          const handler = pending.get(message.id);
          pending.delete(message.id);
          message.error ? handler.reject(new Error(message.error.message)) : handler.resolve(message.result);
        }
      });
      return;
    } catch (error) {
      await new Promise((resolve) => setTimeout(resolve, 300));
    }
  }
  throw new Error('The browser did not expose a debugging endpoint');
}

async function shot(name) {
  const result = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  const file = join(outputDirectory, `${name}.png`);
  writeFileSync(file, Buffer.from(result.data, 'base64'));
  console.log(`wrote ${file}`);
}

async function signIn(email, password) {
  await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken },
      body: JSON.stringify({ email: ${JSON.stringify(email)}, password: ${JSON.stringify(password)} })
    });
    return response.status;
  })()`);
}

async function goto(hash, waitFor) {
  await evaluate(`location.hash = ${JSON.stringify(hash)}`);
  const started = Date.now();
  while (Date.now() - started < 25000) {
    if (await evaluate(waitFor)) { return; }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  console.warn(`  the screen ${hash} did not settle in time`);
  const state = await evaluate(`(() => {
    const view = document.getElementById('view');
    return {
      hash: location.hash,
      route: view ? view.dataset.route : null,
      cards: document.querySelectorAll('#view .card').length,
      values: document.querySelectorAll('#view .stat-value').length,
      skeletons: document.querySelectorAll('#view .skeleton').length,
      head: view ? view.innerText.slice(0, 120) : ''
    };
  })()`);
  console.warn(`  the page says: ${JSON.stringify(state)}`);
}

/**
 * Waits for something on the page.
 */
async function waitFor(predicate, description, timeoutMillis = 25000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMillis) {
    if (await evaluate(predicate)) { return; }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  console.warn(`  ${description} did not appear in time`);
}

/**
 * Builds the academic structure the screens need.
 *
 * <p>There is no administration any more, so the script builds what it needs through the
 * teacher's own API and removes it again when it is done.
 */
async function buildAcademicStructure() {
  await signIn('faculty@veriqra.local', DEMO_PASSWORD);
  const ids = await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };

    const subjects = await (await fetch('/api/faculty/subjects', { headers })).json();
    let subject = subjects.find((entry) => entry.code === 'SHOTS');
    if (!subject) {
      subject = await (await fetch('/api/faculty/subjects', {
        method: 'POST', headers,
        body: JSON.stringify({ code: 'SHOTS', name: 'Screenshot Subject' })
      })).json();
    }

    const classes = await (await fetch('/api/faculty/classes', { headers })).json();
    let classSection = classes.find((entry) => entry.subjectId === subject.id);
    if (!classSection) {
      classSection = await (await fetch('/api/faculty/classes', {
        method: 'POST', headers,
        body: JSON.stringify({ subjectId: subject.id, section: 'A', room: 'Screenshot Lab' })
      })).json();
    }

    await fetch('/api/faculty/enrollments?email=student@veriqra.local&classId=' + classSection.id,
      { method: 'POST', headers });
    return { classId: classSection.id, subjectId: subject.id };
  })()`);
  return ids;
}

/**
 * Removes the structure the script created, so a run leaves no trace.
 */
async function removeAcademicStructure(ids) {
  if (!ids || !ids.classId) {
    return;
  }
  await signIn('faculty@veriqra.local', DEMO_PASSWORD);
  await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };
    const list = await (await fetch('/api/faculty/sessions?status=ALL&limit=100', { headers })).json();
    for (const session of list) {
      await fetch('/api/faculty/sessions/' + session.id, { method: 'DELETE', headers });
    }
    await fetch('/api/faculty/classes/' + ${ids.classId}, { method: 'DELETE', headers });
    await fetch('/api/faculty/subjects/' + ${ids.subjectId}, { method: 'DELETE', headers });
  })()`);
}
await connect();
await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', {
  width: 1440, height: 960, deviceScaleFactor: 1, mobile: false
});

await send('Page.navigate', { url: `${baseUrl}/` });
await new Promise((resolve) => setTimeout(resolve, 2500));
await shot('01-sign-in');

await send('Page.navigate', { url: `${baseUrl}/#/signup` });
await new Promise((resolve) => setTimeout(resolve, 1500));
await shot('02-sign-up');

const structure = await buildAcademicStructure();
await signIn('faculty@veriqra.local', 'Demo!2026');
await send('Page.navigate', { url: `${baseUrl}/` });
await waitFor('document.readyState === "complete"', 'the page');
// The application reads the session before it can open a screen, so the wait is for the
// shell and not for a fixed time.
await waitFor('document.querySelector(".shell") !== null || document.querySelector(".auth-card") !== null',
  'the application');
await goto('#/dashboard', 'document.querySelector(".stat-value") !== null');
await new Promise((resolve) => setTimeout(resolve, 1200));
await shot('03-teacher-dashboard');

await goto('#/attendance', 'document.querySelector(".lecture-row") !== null || document.querySelector("#view .card") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('04-teacher-attendance');

// Start a real session so the rotating code and the live roster are captured.
await evaluate('document.querySelector(".lecture-row button")?.click()');
await new Promise((resolve) => setTimeout(resolve, 3000));
await shot('04b-live-session');

// The dashboard comes after the other screens, because the first request of a fresh
// browser is the slow one and the screen would be caught with its placeholder.
await goto('#/dashboard', 'document.querySelector(".stat-value") !== null');
await new Promise((resolve) => setTimeout(resolve, 1500));
await shot('03-teacher-dashboard');
await evaluate('document.querySelector("#end")?.click()');
await new Promise((resolve) => setTimeout(resolve, 700));
await evaluate('[...document.querySelectorAll(".modal-footer button")].find((b) => /end session/i.test(b.textContent))?.click()');
await new Promise((resolve) => setTimeout(resolve, 1500));

await goto('#/timetable', 'document.querySelector(".scheduler") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('05-teacher-timetable');

// Sign out properly. A GET on the logout route would be answered with a method
// not allowed, which is correct but not what this script wants.
await evaluate(`(async () => {
  const bootstrap = await (await fetch('/api/auth/session')).json();
  await fetch('/api/auth/logout', {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': bootstrap.csrfToken }
  });
})()`);
await signIn('student@veriqra.local', 'Demo!2026');
await send('Page.navigate', { url: `${baseUrl}/` });
await new Promise((resolve) => setTimeout(resolve, 2000));
await goto('#/profile', 'document.querySelector(".verify-steps") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('06-profile');

await goto('#/verify-presence', 'document.querySelector(".split-wide") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('07-verify-presence');

// The dark theme.
await evaluate('document.documentElement.setAttribute("data-theme", "dark")');
await goto('#/timetable', 'document.querySelector(".scheduler") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('08-dark-theme');

// The classes of the teacher.
await evaluate('document.documentElement.setAttribute("data-theme", "light")');
await signIn('faculty@veriqra.local', 'Demo!2026');
await send('Page.navigate', { url: `${baseUrl}/` });
await new Promise((resolve) => setTimeout(resolve, 2000));
await goto('#/classes', 'document.querySelector("#view .card") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('09-teacher-classes');

// Leave the deployment as it was found.
try {
  await removeAcademicStructure(structure);
} catch (error) {
  console.warn(`The test structure could not be removed: ${error.message}`);
}

try { socket?.close(); } catch (error) { /* nothing to do */ }
browser.kill();
await new Promise((resolve) => setTimeout(resolve, 400));
try { rmSync(profileDirectory, { recursive: true, force: true, maxRetries: 5 }); } catch (error) { /* ignore */ }
console.log('done');
process.exit(0);
