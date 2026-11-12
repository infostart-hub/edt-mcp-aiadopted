const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
function invoke(hook, input) {
  return spawnSync(process.execPath, [path.join(root, hook)], {
    input: JSON.stringify(input), encoding: 'utf8', cwd: root
  });
}

test('Codex routes sensitive Java edits to real upstream skill files', () => {
  const result = invoke('.cursor/hooks/edt-skill-router.cjs', {
    tool_input: { file_path: 'C:\\fork\\tools\\impl\\ModifyMetadataTool.java' }
  });
  assert.equal(result.status, 0, result.stderr);
  const output = JSON.parse(result.stdout);
  assert.deepEqual(Object.keys(output), ['additional_context']);
  assert.match(output.additional_context, /edt-mcp-bilingual\/SKILL\.md/);
  assert.match(output.additional_context, /edt-mcp-tool-conventions\/SKILL\.md/);
  for (const file of output.additional_context.match(/\.claude\/skills\/[a-z0-9-]+\/SKILL\.md/g)) {
    assert.equal(fs.existsSync(path.join(root, file)), true, file);
  }
});

test('irrelevant edits remain advisory and produce no context or permission', () => {
  const result = invoke('.cursor/hooks/edt-skill-router.cjs', {
    tool_input: { file_path: 'README.md' }
  });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), {});
});

test('the existing Claude contract remains available', () => {
  const result = invoke('.claude/hooks/edt-skill-router.js', {
    tool_input: { file_path: '/fork/tests/e2e/tools/test_dcs.py' }
  });
  assert.equal(result.status, 0, result.stderr);
  const output = JSON.parse(result.stdout).hookSpecificOutput;
  assert.equal(output.hookEventName, 'PostToolUse');
  assert.match(output.additionalContext, /edt-mcp-e2e-testing/);
});

test('malformed input is an observable hook failure', () => {
  const result = spawnSync(process.execPath, [path.join(root, '.cursor/hooks/edt-skill-router.cjs')], {
    input: 'invalid-json', encoding: 'utf8', cwd: root
  });
  assert.notEqual(result.status, 0);
  assert.equal(result.stdout, '');
});

test('Codex config declares the native event and executable entry point', () => {
  const config = JSON.parse(fs.readFileSync(path.join(root, '.cursor/hooks.json'), 'utf8'));
  assert.equal(config.version, 1);
  assert.equal(config.hooks.postToolUse[0].command, 'node .cursor/hooks/edt-skill-router.cjs');
});


test('registered command dispatches matching edits through the native adapter', () => {
  const config = JSON.parse(fs.readFileSync(path.join(root, '.cursor/hooks.json'), 'utf8'));
  const [hook] = config.hooks.postToolUse;
  const matcher = new RegExp(hook.matcher);
  assert.equal(matcher.test('Read'), false);
  for (const tool of ['Write', 'Edit']) {
    assert.equal(matcher.test(tool), true);
    const result = spawnSync(hook.command, {
      shell: true, cwd: root, encoding: 'utf8', timeout: hook.timeout * 1000,
      input: JSON.stringify({
        tool_name: tool,
        tool_input: { file_path: 'C:\\project with spaces\\tests\\e2e\\tools\\test_dcs.py' }
      })
    });
    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stderr);
    const output = JSON.parse(result.stdout);
    assert.deepEqual(Object.keys(output), ['additional_context']);
    assert.match(output.additional_context, /edt-mcp-e2e-testing\/SKILL\.md/);
    const references = output.additional_context.match(/\.claude\/skills\/[a-z0-9-]+\/SKILL\.md/g);
    assert.ok(references.length > 0);
    for (const file of references) assert.equal(fs.existsSync(path.join(root, file)), true, file);
  }
});

test('Codex preserves the upstream routing matrix and resolves every skill link', () => {
  const cases = [
    ['metadata', '/fork/tools/metadata/Resolver.java', ['edt-mcp-bilingual']],
    ['metadata class', '/fork/ModifyMetadata.java', ['edt-mcp-bilingual']],
    ['synonym class', '/fork/ResolveSynonym.java', ['edt-mcp-bilingual']],
    ['subsystem class', '/fork/ResolveSubsystem.java', ['edt-mcp-bilingual']],
    ['translation class', '/fork/TranslationService.java', ['edt-mcp-bilingual']],
    ['BSL source', '/fork/ReadModuleSourceTool.java', ['edt-mcp-bilingual']],
    ['tags', '/fork/tags/TagService.java', ['edt-mcp-architecture']],
    ['groups', '/fork/groups/GroupService.java', ['edt-mcp-architecture']],
    ['tool registration', '/fork/BuiltInToolRegistrar.java', ['edt-mcp-new-tool']],
    ['bundle imports', '/fork/META-INF/MANIFEST.MF', ['edt-mcp-new-tool']],
    ['YAXUnit', '/fork/RunYaxunitTool.java', ['edt-mcp-yaxunit']],
    ['shell build', '/fork/source/compile.sh', ['edt-mcp-build-test']],
    ['Maven build', '/fork/mcp/pom.xml', ['edt-mcp-build-test']],
    ['exclusive e2e policy', 'C:\\fork\\tests\\e2e\\tools\\TestModule.py', ['edt-mcp-e2e-testing']]
  ];
  for (const [name, file, expected] of cases) {
    const result = invoke('.cursor/hooks/edt-skill-router.cjs', {
      tool_input: { file_path: file }
    });
    assert.ifError(result.error);
    assert.equal(result.status, 0, `${name}: ${result.stderr}`);
    const output = JSON.parse(result.stdout);
    assert.deepEqual(Object.keys(output), ['additional_context'], name);
    const references = [...output.additional_context.matchAll(/\.claude\/skills\/(edt-mcp-[a-z0-9-]+)\/SKILL\.md/g)];
    assert.deepEqual(references.map(match => match[1]), expected, name);
    for (const [file] of references) assert.ok(fs.existsSync(path.join(root, file)), file);
  }
});

test('all supported path fields reach the same upstream policy', () => {
  for (const field of ['file_path', 'path', 'notebook_path']) {
    const result = invoke('.cursor/hooks/edt-skill-router.cjs', {
      tool_input: { [field]: '/fork/tools/impl/ModifyMetadataTool.java' }
    });
    assert.equal(result.status, 0, result.stderr);
    const context = JSON.parse(result.stdout).additional_context;
    assert.match(context, /edt-mcp-tool-conventions\/SKILL\.md/);
    assert.match(context, /edt-mcp-tool-descriptions\/SKILL\.md/);
    assert.match(context, /edt-mcp-bilingual\/SKILL\.md/);
  }
});

test('an event with no edited path does not manufacture routing context', () => {
  for (const input of [{}, { tool_input: {} }, { tool_input: { file_path: '/fork/' } }]) {
    const result = invoke('.cursor/hooks/edt-skill-router.cjs', input);
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), {});
  }
});

test('the native adapter preserves a failed upstream process and its diagnostic', () => {
  // Valid JSON reaches the upstream router; it cannot represent a hook event.
  const upstream = invoke('.claude/hooks/edt-skill-router.js', null);
  const adapter = invoke('.cursor/hooks/edt-skill-router.cjs', null);
  assert.ifError(upstream.error);
  assert.ifError(adapter.error);
  assert.notEqual(upstream.status, 0);
  assert.equal(adapter.status, upstream.status);
  assert.equal(adapter.stdout, '');
  assert.match(upstream.stderr, /TypeError/);
  assert.equal(adapter.stderr, upstream.stderr);
});

test('the upstream advisory contract ignores malformed or empty input', () => {
  for (const input of ['invalid-json', '']) {
    const result = spawnSync(process.execPath, [path.join(root, '.claude/hooks/edt-skill-router.js')], {
      input, encoding: 'utf8', cwd: root
    });
    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
  }
});
