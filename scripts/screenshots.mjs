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
  while (Date.now() - started < 12000) {
    if (await evaluate(waitFor)) { return; }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
}

/**
 * Builds the academic structure the screens need.
 *
 * Nothing about the college is seeded, so the script creates a subject, a class section
 * and an enrolment through the administration API.
 */
async function buildAcademicStructure() {
  await signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
  const ids = await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };
    const users = await (await fetch('/api/admin/users', { headers })).json();
    const teacher = users.find((user) => user.email === 'faculty@veriqra.local');
    const student = users.find((user) => user.email === 'student@veriqra.local');

    const subjects = await (await fetch('/api/admin/subjects', { headers })).json();
    let subject = subjects.find((entry) => entry.code === 'SMOKE1');
    if (!subject) {
      subject = await (await fetch('/api/admin/subjects', {
        method: 'POST', headers,
        body: JSON.stringify({ code: 'SMOKE1', name: 'Smoke Test Subject', facultyId: teacher.id })
      })).json();
    }
    const classes = await (await fetch('/api/admin/classes', { headers })).json();
    let classSection = classes.find((entry) => entry.subjectId === subject.id);
    if (!classSection) {
      classSection = await (await fetch('/api/admin/classes', {
        method: 'POST', headers,
        body: JSON.stringify({ subjectId: subject.id, section: 'A', room: 'Smoke Lab' })
      })).json();
    }
    await fetch('/api/admin/enrollments?studentId=' + student.id + '&classId=' + classSection.id,
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
  // The script starts a live session, so it is removed first. A session that holds
  // attendance records is never removed.
  await signIn('faculty@veriqra.local', DEMO_PASSWORD);
  const sessions = await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'X-XSRF-TOKEN': bootstrap.csrfToken };
    const list = await (await fetch('/api/faculty/sessions?status=ALL&limit=100', { headers })).json();
    return list.map((session) => session.id);
  })()`);

  await signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
  await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };
    for (const id of ${JSON.stringify(sessions)}) {
      await fetch('/api/faculty/sessions/' + id, { method: 'DELETE', headers });
    }
    await fetch('/api/admin/classes/' + ${ids.classId}, { method: 'DELETE', headers });
    await fetch('/api/admin/subjects/' + ${ids.subjectId}, { method: 'DELETE', headers });
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
await new Promise((resolve) => setTimeout(resolve, 2000));
await goto('#/dashboard', 'document.querySelector(".stat-grid") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('03-teacher-dashboard');

await goto('#/attendance', 'document.querySelector("#class-choice") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('04-teacher-attendance');

// Start a real session so the rotating code and the live roster are captured.
await evaluate('document.querySelector("#start").click()');
await new Promise((resolve) => setTimeout(resolve, 3000));
await shot('04b-live-session');
await evaluate('document.querySelector("#end")?.click()');
await new Promise((resolve) => setTimeout(resolve, 500));
await evaluate('document.querySelector(".modal-footer .button-danger")?.click()');
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
await goto('#/verify-identity', 'document.querySelector(".verify-steps") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('06-identity-check');

await goto('#/verify-presence', 'document.querySelector("#capture-panel") !== null || document.querySelector(".split-wide") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('07-verify-presence');

// The dark theme.
await evaluate('document.documentElement.setAttribute("data-theme", "dark")');
await goto('#/biometric', 'document.querySelector("#view .card") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('08-dark-theme');

// The administration screens.
await signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
await send('Page.navigate', { url: `${baseUrl}/` });
await new Promise((resolve) => setTimeout(resolve, 2000));
await goto('#/classes', 'document.querySelector("#view .card") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('09-admin-structure');

await goto('#/settings', 'document.querySelector("#view .card") !== null');
await new Promise((resolve) => setTimeout(resolve, 800));
await shot('10-admin-settings');

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
