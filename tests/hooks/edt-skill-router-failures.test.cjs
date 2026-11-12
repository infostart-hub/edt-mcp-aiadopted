const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const adapter = path.join(root, '.cursor/hooks/edt-skill-router.cjs');
const upstream = path.join(root, '.claude/hooks/edt-skill-router.js');
const publication = path.join(__dirname, 'fixtures/router-response.cjs');
const input = JSON.stringify({
  tool_input: { file_path: '/fork/tools/impl/ModifyMetadataTool.java' }
});

function publish(hook, response) {
  const result = spawnSync(process.execPath, ['--require', publication, hook], {
    input, encoding: 'utf8', cwd: root, timeout: 5000,
    env: {
      ...process.env,
      NODE_OPTIONS: [process.env.NODE_OPTIONS, `--require=${JSON.stringify(publication)}`]
        .filter(Boolean).join(' '),
      EDT_HOOK_TEST_RESPONSE: response
    }
  });
  assert.ifError(result.error);
  return result;
}

test('a real failed child preserves its exit code and exact Unicode diagnostic', () => {
  const child = publish(upstream, 'exit');
  const result = publish(adapter, 'exit');
  assert.equal(child.status, 23);
  assert.equal(child.stderr, 'router refused: \u041e\u0442\u043a\u0430\u0437 / denied\n');
  assert.equal(result.status, child.status);
  assert.equal(result.signal, null);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, child.stderr);
});

test('a child killed by an OS signal remains a failure and preserves stderr', () => {
  const child = publish(upstream, 'signal');
  const result = publish(adapter, 'signal');
  // libuv's Windows self-kill uses TerminateProcess(..., 1). Only a kill
  // through the parent's process handle records an exit_signal there.
  if (process.platform === 'win32') {
    assert.equal(child.status, 1);
    assert.equal(child.signal, null);
  } else {
    assert.equal(child.status, null);
    assert.equal(child.signal, 'SIGTERM');
  }
  assert.equal(child.stderr, 'router interrupted before publishing context\n');
  assert.equal(result.status, 1);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, child.stderr);
});

test('the OS output-buffer refusal is observable without publishing partial context', () => {
  const result = publish(adapter, 'overflow');
  assert.equal(result.status, 1);
  assert.equal(result.stdout, '');
  assert.match(result.stderr, /ENOBUFS/);
});

test('an incomplete child publication cannot become a successful context', () => {
  const result = publish(adapter, 'invalid-json');
  assert.equal(result.status, 1);
  assert.equal(result.stdout, '');
  assert.match(result.stderr, /SyntaxError/);
});

for (const response of ['missing-context', 'invalid-context']) {
  test(`the ${response} child publication reports the owning contract error`, () => {
    const result = publish(adapter, response);
    assert.equal(result.status, 1);
    assert.equal(result.stdout, '');
    assert.match(result.stderr, /TypeError: Missing upstream hook context/);
  });
}

for (const [name, hook] of [['Claude', upstream], ['Codex', adapter]]) {
  test(`${name} handles the native stdin-close result according to its host contract`, () => {
    const result = spawnSync(process.execPath, [
      '-e', `
        const fs = require('node:fs');
        const assert = require('node:assert/strict');
        fs.closeSync(0);
        if (process.platform === 'win32') {
          assert.doesNotThrow(() => fs.fstatSync(0));
        } else {
          assert.throws(() => fs.fstatSync(0), { code: 'EBADF' });
        }
        require(process.argv[1]);
      `, hook
    ], { encoding: 'utf8', cwd: root, timeout: 5000 });
    assert.ifError(result.error);
    assert.equal(result.stdout, '');
    if (name === 'Claude') {
      assert.equal(result.status, 0);
      assert.equal(result.stderr, '');
    } else {
      assert.equal(result.status, 1);
      if (process.platform === 'win32') {
        // libuv leaves standard descriptors open; the inherited pipe is at EOF.
        assert.match(result.stderr, /SyntaxError: Unexpected end of JSON input/);
      } else {
        assert.match(result.stderr, /EBADF/);
      }
    }
  });
}
