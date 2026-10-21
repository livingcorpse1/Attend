#!/usr/bin/env node
/**
 * Parses every client module and reports syntax errors.
 *
 * The browser modules are plain ECMAScript modules with no build step, so a syntax
 * error would only show up at runtime. This script catches it before a deployment.
 *
 * Usage:  node scripts/check-client.mjs
 */

import { execFileSync } from 'node:child_process';
import { mkdtempSync, copyFileSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = fileURLToPath(new URL('.', import.meta.url));
const root = resolve(here, '..');
const clientRoot = join(root, 'src', 'main', 'resources', 'static', 'js');

function collect(directory) {
  const found = [];
  for (const entry of readdirSync(directory)) {
    const full = join(directory, entry);
    if (statSync(full).isDirectory()) {
      found.push(...collect(full));
    } else if (entry.endsWith('.js')) {
      found.push(full);
    }
  }
  return found;
}

const workDirectory = mkdtempSync(join(tmpdir(), 'veriqra-client-'));
let failures = 0;

try {
  for (const file of collect(clientRoot)) {
    // The modules are copied to a .mjs file so Node parses them as modules rather
    // than as CommonJS scripts.
    const target = join(workDirectory, `${relative(clientRoot, file).replace(/[\\/]/g, '_')}.mjs`);
    copyFileSync(file, target);
    try {
      execFileSync(process.execPath, ['--check', target], { stdio: 'pipe' });
    } catch (error) {
      failures += 1;
      const output = error.stderr ? error.stderr.toString() : error.message;
      console.error(`FAIL ${relative(root, file)}`);
      console.error(output.split('\n').slice(0, 8).map((line) => `    ${line}`).join('\n'));
    }
  }
} finally {
  rmSync(workDirectory, { recursive: true, force: true });
}

if (failures > 0) {
  console.error(`\n${failures} client module(s) failed to parse.`);
  process.exit(1);
}
console.log('All client modules parse cleanly.');
