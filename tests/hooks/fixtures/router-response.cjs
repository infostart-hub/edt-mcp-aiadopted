// Fault publication at the child-process boundary; never replace spawnSync results.
const fs = require('node:fs');
const path = require('node:path');
const router = path.resolve(__dirname, '../../..', '.claude/hooks/edt-skill-router.js');

if (process.argv[1] === router) {
  const input = JSON.parse(fs.readFileSync(0, 'utf8'));
  if (input.tool_input.file_path !== '/fork/tools/impl/ModifyMetadataTool.java') {
    throw new Error('Unexpected isolated hook event');
  }
  switch (process.env.EDT_HOOK_TEST_RESPONSE) {
    case 'exit':
      fs.writeSync(2, 'router refused: \u041e\u0442\u043a\u0430\u0437 / denied\n');
      process.exit(23);
      break;
    case 'signal':
      fs.writeSync(2, 'router interrupted before publishing context\n');
      process.kill(process.pid, 'SIGTERM');
      break;
    case 'overflow':
      fs.writeSync(1, Buffer.alloc(2 * 1024 * 1024, 'x'));
      process.exit(0);
      break;
    case 'invalid-json':
      fs.writeSync(1, '{ incomplete');
      process.exit(0);
      break;
    case 'missing-context':
      fs.writeSync(1, JSON.stringify({ hookSpecificOutput: {} }));
      process.exit(0);
      break;
    case 'invalid-context':
      fs.writeSync(1, JSON.stringify({ hookSpecificOutput: { additionalContext: 42 } }));
      process.exit(0);
      break;
    default:
      throw new Error('Unknown isolated hook response');
  }
}
