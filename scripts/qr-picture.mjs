/**
 * Renders the classroom code of a running session to a picture, so it can be fed to
 * the camera of a fake device and the scanner can be tested for real.
 *
 * Usage: node --experimental-websocket scripts/qr-picture.mjs <output.png>
 */

import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const baseUrl = process.argv[3] || 'http://localhost:8080';
const output = process.argv[2] || 'target/qr.png';
const debugPort = 9366;

const browserPath = [
  process.env.CHROME_PATH,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
].filter(Boolean).find((candidate) => existsSync(candidate));

const profileDirectory = mkdtempSync(join(tmpdir(), 'veriqra-qr-'));
const browser = spawn(browserPath, [
  '--headless=new',
  '--disable-gpu',
  '--no-sandbox',
  '--disable-extensions',
  '--no-first-run',
  `--user-data-dir=${profileDirectory}`,
  `--remote-debugging-port=${debugPort}`,
  'about:blank'
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
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error(`The browser did not answer ${method}`));
      }
    }, 20000);
  });
}

async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || 'the page threw an error');
  }
  return result.result.value;
}

async function waitFor(predicate, description, timeoutMillis = 15000) {
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

await connect();
await send('Page.enable');
await send('Runtime.enable');

// The teacher signs in and starts a session, so there is a code to draw.
await send('Page.navigate', { url: `${baseUrl}/` });
await waitFor('document.readyState === "complete"', 'the page');
await waitFor('document.querySelector(".auth-card") !== null', 'the sign in screen');

const payload = await evaluate(`(async () => {
  const bootstrap = await (await fetch('/api/auth/session')).json();
  const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };
  await fetch('/api/auth/login', {
    method: 'POST', headers,
    body: JSON.stringify({ email: 'faculty@veriqra.local', password: 'Demo!2026' })
  });
  const slots = await (await fetch('/api/faculty/timetable', { headers })).json();
  const day = new Date().getDay() === 0 ? 7 : new Date().getDay();
  let slot = slots.find((entry) => entry.dayOfWeek === day);
  if (!slot) {
    const subjects = await (await fetch('/api/faculty/subjects', { headers })).json();
    const subject = subjects[0];
    const created = await (await fetch('/api/faculty/classes', {
      method: 'POST', headers,
      body: JSON.stringify({ subjectId: subject.id, section: 'Z', room: 'Test Room' })
    })).json();
    slot = await (await fetch('/api/faculty/timetable', {
      method: 'POST', headers,
      body: JSON.stringify({ classId: created.id, dayOfWeek: day, starts: '15:00', ends: '16:00' })
    })).json();
  }
  const session = await (await fetch('/api/faculty/sessions?slotId=' + slot.id, {
    method: 'POST', headers
  })).json();
  if (!session || !session.id) {
    return { error: 'no session' };
  }
  const code = await (await fetch('/api/faculty/sessions/' + session.id + '/qr', { headers })).json();
  return { token: code.token, sessionId: session.id, slotId: slot.id };
})()`);

if (!payload || payload.error) {
  console.error('The session could not be started:', payload?.error);
  process.exit(1);
}

// The code is drawn with the library of the application itself, on a white sheet.
const dataUrl = await evaluate(`(async () => {
  const { renderQr } = await import('/js/qr.js');
  const host = document.createElement('div');
  host.style.cssText = 'position:fixed;left:0;top:0;z-index:9999;background:#ffffff;padding:20px';
  document.body.appendChild(host);
  host.appendChild(renderQr(${JSON.stringify(payload.token)}, { size: 560 }));
  await new Promise((r) => setTimeout(r, 800));
  const code = host.querySelector('canvas');
  if (!code) {
    return null;
  }
  const sheet = document.createElement('canvas');
  sheet.width = 640;
  sheet.height = 640;
  const context = sheet.getContext('2d');
  context.fillStyle = '#ffffff';
  context.fillRect(0, 0, sheet.width, sheet.height);
  context.drawImage(code, 40, 40, 560, 560);
  return sheet.toDataURL('image/png');
})()`);

if (!dataUrl) {
  console.error('The code could not be drawn');
  process.exit(1);
}

writeFileSync(output, Buffer.from(dataUrl.split(',')[1], 'base64'));
console.log(JSON.stringify({ output, sessionId: payload.sessionId, slotId: payload.slotId }));

try {
  socket?.close();
} catch (error) {
  // Nothing to do.
}
browser.kill();
await new Promise((resolve) => setTimeout(resolve, 300));
rmSync(profileDirectory, { recursive: true, force: true, maxRetries: 5 });
