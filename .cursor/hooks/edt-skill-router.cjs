#!/usr/bin/env node
'use strict';

// Native Codex adapter; the upstream Claude router owns the routing rules.
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const input = fs.readFileSync(0, 'utf8');
JSON.parse(input);
const router = path.resolve(__dirname, '../../.claude/hooks/edt-skill-router.js');
const result = spawnSync(process.execPath, [router], { input, encoding: 'utf8' });
if (result.error) throw result.error;
if (result.status !== 0) {
  process.stderr.write(result.stderr);
  process.exit(result.status === null ? 1 : result.status);
}
if (result.stdout.trim() === '') {
  process.stdout.write('{}');
} else {
  const output = JSON.parse(result.stdout);
  const context = output.hookSpecificOutput.additionalContext;
  if (typeof context !== 'string') throw new TypeError('Missing upstream hook context');
  process.stdout.write(JSON.stringify({
    additional_context: context.replace(/\/(edt-mcp-[a-z0-9-]+)/g,
      (_, skill) => `.claude/skills/${skill}/SKILL.md`)
  }));
}
