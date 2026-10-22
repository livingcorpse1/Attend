/**
 * Drives a headless browser through every screen of the application.
 *
 * <p>The client has no build step, so the only honest way to verify it is to run it in a
 * real browser. This script signs in as a teacher and as a student, visits every screen
 * and fails on the first console error or on the first missing element.
 *
 * <p>Nothing about the college is seeded, so the script builds the subject, the class
 * section and the enrolment it needs through the teacher's own API, and removes them
 * again when it is done.
 *
 * Usage:
 *   node --experimental-websocket scripts/smoke-client.mjs [base-url]
 */

import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const baseUrl = process.argv[2] || 'http://localhost:8080';
const debugPort = 9333;

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

const DEMO_PASSWORD = settingFromEnvironmentFile('DEMO_PASSWORD', 'Demo!2026');

const browserPath = [
  process.env.CHROME_PATH,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium'
].filter(Boolean).find((candidate) => existsSync(candidate));

if (!browserPath) {
  console.error('No Chromium based browser was found. Set CHROME_PATH and try again.');
  process.exit(2);
}

const profileDirectory = mkdtempSync(join(tmpdir(), 'veriqra-smoke-'));
const browser = spawn(browserPath, [
  '--headless=new',
  '--disable-gpu',
  '--no-sandbox',
  '--disable-extensions',
  '--no-first-run',
  '--disable-dev-shm-usage',
  `--user-data-dir=${profileDirectory}`,
  `--remote-debugging-port=${debugPort}`,
  'about:blank'
], { stdio: 'ignore' });

let socket = null;
let messageId = 0;
const pending = new Map();
const consoleErrors = [];

/** The structure the run created, so it can be removed again afterwards. */
let structure = { classId: null, subjectId: null };

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

async function waitFor(predicateExpression, description, timeoutMillis = 15000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMillis) {
    const value = await evaluate(predicateExpression);
    if (value) {
      return value;
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Timed out while waiting for ${description}`);
}

async function connect() {
  const started = Date.now();
  let lastError = null;
  while (Date.now() - started < 20000) {
    try {
      const response = await fetch(
        `http://127.0.0.1:${debugPort}/json/new?${encodeURIComponent('about:blank')}`,
        { method: 'PUT' }
      );
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
      lastError = error;
      await new Promise((resolve) => setTimeout(resolve, 300));
    }
  }
  throw new Error('The browser did not expose a debugging endpoint: '
    + (lastError ? lastError.message : 'unknown')
    + '. Run this script with: node --experimental-websocket scripts/smoke-client.mjs');
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

/**
 * Builds the academic structure the screens need.
 */
async function buildAcademicStructure() {
  await signIn('faculty@veriqra.local', DEMO_PASSWORD);

  const ids = await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };

    // Reuse what a previous run created, so the script can be run again.
    const subjects = await (await fetch('/api/faculty/subjects', { headers })).json();
    let subject = subjects.find((entry) => entry.code === 'SMOKE1');
    if (!subject) {
      subject = await (await fetch('/api/faculty/subjects', {
        method: 'POST', headers,
        body: JSON.stringify({ code: 'SMOKE1', name: 'Smoke Test Subject' })
      })).json();
    }

    const classes = await (await fetch('/api/faculty/classes', { headers })).json();
    let classSection = classes.find((entry) => entry.subjectId === subject.id);
    if (!classSection) {
      classSection = await (await fetch('/api/faculty/classes', {
        method: 'POST', headers,
        body: JSON.stringify({ subjectId: subject.id, section: 'A', room: 'Smoke Lab' })
      })).json();
    }

    await fetch('/api/faculty/enrollments?email=student@veriqra.local&classId=' + classSection.id,
      { method: 'POST', headers });

    // A lecture for today, so the attendance screen has something to show. The teacher
    // only has a lecture on Monday, and the screen lists the lectures of the day.
    const today = new Date().getDay() === 0 ? 7 : new Date().getDay();
    const lectures = await (await fetch('/api/faculty/timetable', { headers })).json();
    let lecture = lectures.find((entry) => entry.classId === classSection.id && entry.dayOfWeek === today);
    if (!lecture) {
      lecture = await (await fetch('/api/faculty/timetable', {
        method: 'POST', headers,
        body: JSON.stringify({ classId: classSection.id, dayOfWeek: today, starts: '15:00', ends: '16:00' })
      })).json();
    }

    return { classId: classSection.id, subjectId: subject.id, slotId: lecture.id };
  })()`);

  if (!ids || !ids.classId) {
    throw new Error('The academic structure could not be created');
  }
  return ids;
}

/**
 * Removes the structure the test created, so a run leaves no trace.
 */
async function removeAcademicStructure(ids) {
  if (!ids || !ids.classId) {
    return;
  }
  await signIn('faculty@veriqra.local', DEMO_PASSWORD);
  await evaluate(`(async () => {
    const bootstrap = await (await fetch('/api/auth/session')).json();
    const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };
    if (${ids.slotId || 'null'}) {
      await fetch('/api/faculty/timetable/' + ${ids.slotId}, { method: 'DELETE', headers });
    }
    await fetch('/api/faculty/classes/' + ${ids.classId}, { method: 'DELETE', headers });
    await fetch('/api/faculty/subjects/' + ${ids.subjectId}, { method: 'DELETE', headers });
  })()`);
}

const checks = [];

function check(name, value) {
  checks.push({ name, ok: Boolean(value) });
  console.log(`${value ? 'PASS' : 'FAIL'}  ${name}`);
}

async function run() {
  await connect();
  await send('Page.enable');
  await send('Runtime.enable');

  // The sign in screen. The root of the site is the home page.
  await navigate(`${baseUrl}/#/login`);
  await waitFor('document.querySelector(".auth-card") !== null', 'the sign in screen');
  check('the sign in screen renders', await evaluate('document.querySelector(".auth-card h1").textContent'));
  check('the sign in opens as a dialog over the home page',
    await evaluate('document.querySelector(".modal") !== null '
      + '&& document.querySelector(".home-title") !== null'));

  // The home page.
  await navigate(`${baseUrl}/`);
  await waitFor('document.querySelector(".home-title") !== null', 'the home page');
  check('the home page renders the headline', await evaluate('document.querySelector(".home-title").textContent'));
  check('the home page offers the two actions',
    await evaluate('[...document.querySelectorAll(".home-actions a")].map((a) => a.textContent.trim()).join(",")'));
  check('the home page lists the features',
    await evaluate('document.querySelectorAll(".home-feature").length === 3'));

  // The sign up screen.
  await navigate(`${baseUrl}/#/signup`);
  await waitFor('document.querySelector("#signup-name") !== null', 'the sign up screen');
  check('the sign up screen renders', await evaluate('document.querySelector("#signup-name") !== null'));
  check('the consent notice is shown',
    await evaluate('document.body.innerText.includes("Biometric data notice")'));

  // The teacher workspace.
  structure = await buildAcademicStructure();
  await signIn('faculty@veriqra.local', DEMO_PASSWORD);
  await navigate(`${baseUrl}/`);
  await waitFor('document.querySelector(".shell") !== null', 'the teacher shell');
  check('the teacher shell renders',
    await evaluate('document.querySelector(".sidebar .brand span:last-child") !== null'));
  check('the teacher navigation lists the attendance screen',
    await evaluate('document.querySelector(\'.nav-item[data-path="/attendance"]\') !== null'));

  await evaluate('location.hash = "#/dashboard"');
  await waitFor('document.querySelector(".stat-value") !== null', 'the teacher dashboard');
  check('the teacher dashboard renders statistics',
    await evaluate('document.querySelectorAll(".stat-value").length >= 4'));
  check('the teacher dashboard shows the subjects panel',
    await evaluate('document.body.innerText.includes("Attendance by subject")'));
  check('the teacher dashboard shows the fortnight calendar',
    await evaluate('document.querySelectorAll(".calendar-day").length === 14'));

  // The arrows must replace the fortnight, not pile a new one on top of it.
  await evaluate('document.querySelectorAll(".card .card-header .icon-button")[1].click()');
  await new Promise((resolve) => setTimeout(resolve, 400));
  check('the calendar does not duplicate its rows when the arrows are used',
    await evaluate('document.querySelectorAll(".calendar-day").length === 14'));
  check('the calendar header still shows one week',
    await evaluate('document.querySelectorAll(".calendar .calendar-head").length === 7'));

  await evaluate('location.hash = "#/attendance"');
  await waitFor('document.querySelector(".lecture-row") !== null', 'the lectures of the day');
  check('the live attendance screen renders',
    await evaluate('document.querySelector("#view .card") !== null'));
  check('the attendance screen lists the lectures of the day',
    await evaluate('document.querySelectorAll(".lecture-row").length >= 1'));
  check('the attendance screen offers a button for the lecture',
    await evaluate('document.querySelector(".lecture-row .button") !== null'));

  await evaluate('location.hash = "#/timetable"');
  await waitFor('document.querySelector(".scheduler-cell") !== null', 'the teacher timetable');
  check('the teacher timetable renders the week grid',
    await evaluate('document.querySelectorAll(".scheduler-cell").length === 7'));

  // The profile of the teacher.
  await evaluate('location.hash = "#/profile"');
  await waitFor('document.body.innerText.includes("Your details")', 'the teacher profile');
  check('the teacher profile shows the account details',
    await evaluate('document.body.innerText.includes("Your details")'));
  check('the teacher profile lists the subjects they teach',
    await evaluate('document.body.innerText.includes("Your subjects")'));

  // The student workspace.
  await signIn('student@veriqra.local', DEMO_PASSWORD);
  await navigate(`${baseUrl}/`);
  await waitFor('document.querySelector(".shell") !== null', 'the student shell');
  check('the student shell renders',
    await evaluate('document.querySelector(".sidebar .brand span:last-child") !== null'));

  await evaluate('location.hash = "#/profile"');
  await waitFor('document.querySelector(".verify-steps") !== null', 'the profile of the student');
  check('the student profile holds the identity check',
    await evaluate('document.querySelector(".verify-steps") !== null'));
  check('the student profile explains the liveness challenge',
    await evaluate('document.body.innerText.includes("camera")'));
  check('the student profile shows the biometric data',
    await evaluate('document.body.innerText.includes("Biometric data")'));

  // The student workspace has to be as quick as the teacher one.
  await evaluate('location.hash = "#/dashboard"');
  await waitFor('document.querySelector(".stat-value") !== null', 'the student dashboard');
  check('the student dashboard renders statistics',
    await evaluate('document.querySelectorAll(".stat-value").length >= 4'));

  // The account in the store has to be refreshable. The identity check changes it, and
  // without a refresh the route guard still sees the old state and sends the student
  // back to the check.
  check('the account can be refreshed after the identity check',
    await evaluate(`(async () => {
      const { refreshAccount } = await import('/js/store.js');
      const account = await refreshAccount();
      return Boolean(account) && account.role === 'STUDENT';
    })()`));
}

let failed = false;
try {
  await run();
} catch (error) {
  failed = true;
  console.error(`\nThe smoke test could not finish: ${error.message}`);
}

if (consoleErrors.length > 0) {
  failed = true;
  console.error('\nThe browser reported errors:');
  for (const entry of consoleErrors) {
    console.error(`    ${entry}`);
  }
}

const failedChecks = checks.filter((entry) => !entry.ok);
if (failedChecks.length > 0) {
  failed = true;
  console.error(`\n${failedChecks.length} check(s) failed.`);
}

// Leave the deployment as it was found.
try {
  await removeAcademicStructure(structure);
} catch (error) {
  console.warn(`The test structure could not be removed: ${error.message}`);
}

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
  // The profile folder is in the temporary directory, the operating system will clear it.
}
process.exit(failed ? 1 : 0);
