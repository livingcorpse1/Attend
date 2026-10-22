/**
 * Takes a picture of the home page, so the design can be looked at.
 */

import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const baseUrl = process.argv[2] || 'http://localhost:8080';
const output = process.argv[3] || 'target/screenshots/home.png';
const debugPort = 9377;

const browserPath = [
  process.env.CHROME_PATH,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
].filter(Boolean).find((candidate) => existsSync(candidate));

const profileDirectory = mkdtempSync(join(tmpdir(), 'veriqra-home-'));
mkdirSync(join(output, '..'), { recursive: true });

const browser = spawn(browserPath, [
  '--headless=new', '--disable-gpu', '--no-sandbox', '--disable-extensions', '--no-first-run',
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
      if (pending.has(id)) { pending.delete(id); reject(new Error('timeout')); }
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

async function waitFor(predicate, description, timeoutMillis = 20000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMillis) {
    if (await evaluate(predicate)) { return; }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Timed out while waiting for ${description}`);
}

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
        if (message.error) { handler.reject(new Error(message.error.message)); }
        else { handler.resolve(message.result); }
      }
    });
    break;
  } catch (error) {
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
}

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', {
  width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false
});
await send('Page.navigate', { url: `${baseUrl}/` });
await waitFor('document.readyState === "complete"', 'the page');
await waitFor('document.querySelector(".home-title") !== null', 'the home page');
await new Promise((resolve) => setTimeout(resolve, 800));

const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
const { writeFileSync } = await import('node:fs');
writeFileSync(output, Buffer.from(shot.data, 'base64'));
console.log('wrote ' + output);

try { socket?.close(); } catch (error) { /* nothing */ }
browser.kill();
await new Promise((resolve) => setTimeout(resolve, 300));
rmSync(profileDirectory, { recursive: true, force: true, maxRetries: 5 });
