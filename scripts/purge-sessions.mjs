#!/usr/bin/env node
/**
 * Reports and, with --purge, removes the attendance sessions of a deployment.
 *
 * The sessions are the record of what happened during a lecture, so they are never
 * removed with the class they belong to. This script is the deliberate way to clear
 * them, for example when a test run left sessions behind.
 *
 * Usage:
 *   node scripts/purge-sessions.mjs              reports
 *   node scripts/purge-sessions.mjs --purge      removes closed sessions without records
 */
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const purge = process.argv.includes('--purge');
const url = process.argv.find((argument) => argument.startsWith('--url='))?.slice(6)
  || 'http://localhost:8080';

function setting(name, fallback) {
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

const email = setting('BOOTSTRAP_ADMIN_EMAIL', 'admin@veriqra.local');
const password = setting('BOOTSTRAP_ADMIN_PASSWORD', 'AdminDemo!26');

const bootstrap = await (await fetch(`${url}/api/auth/session`)).json();
const headers = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': bootstrap.csrfToken };

const login = await fetch(`${url}/api/auth/login`, {
  method: 'POST',
  headers,
  body: JSON.stringify({ email, password })
});
if (login.status !== 200) {
  console.error(`The sign in failed with status ${login.status}`);
  process.exit(1);
}

const sessions = await (await fetch(`${url}/api/faculty/sessions?status=ALL&limit=100`, { headers })).json();
console.log(`${sessions.length} session(s) found`);

for (const session of sessions) {
  console.log(`  #${session.id} ${session.subjectCode} ${session.section} ${session.status}`
    + ` present=${session.presentCount}/${session.enrolledCount}`);
}

if (!purge) {
  console.log('\nRun again with --purge to remove the closed sessions that hold no records.');
  process.exit(0);
}

let removed = 0;
for (const session of sessions) {
  const response = await fetch(`${url}/api/faculty/sessions/${session.id}`, {
    method: 'DELETE',
    headers
  });
  if (response.ok) {
    removed += 1;
    console.log(`  removed session #${session.id}`);
  } else {
    console.log(`  kept session #${session.id} (${response.status})`);
  }
}
console.log(`\n${removed} session(s) removed.`);

