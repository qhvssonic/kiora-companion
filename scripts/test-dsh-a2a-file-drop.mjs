import { strict as assert } from 'node:assert';
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const packageDir = resolve(process.argv[2] ?? '');
const outputRoot = resolve(process.argv[3] ?? packageDir);
const { A2AServer, createDshAgentExecutor } = await import(
  pathToFileURL(join(packageDir, 'lib', 'index.js')).href
);
const cwd = mkdtempSync(join(outputRoot, 'file-drop-smoke-'));
writeFileSync(join(cwd, 'report.md'), 'existing');
const executor = createDshAgentExecutor({
  create() { throw new Error('File receipt must not invoke the DSH model'); },
}, { cwd });
const file = Buffer.from('# 扫描结果\n', 'utf8');
const input = {
  message: {
    messageId: 'smoke-message', role: 'ROLE_USER',
    parts: [{ text: '保存这个 Markdown 文件' }, {
      filename: 'report.md', mediaType: 'text/markdown', raw: file.toString('base64'),
    }],
  },
  taskId: 'smoke-task', contextId: 'smoke-context', signal: new AbortController().signal,
};
const output = await executor(input);
assert.match(output.parts[0].text, /report-1\.md/);
assert.equal(readFileSync(join(cwd, 'report.md'), 'utf8'), 'existing');
assert.deepEqual(readFileSync(join(cwd, 'report-1.md')), file);
assert.equal(existsSync(join(cwd, 'escape.md')), false);
await assert.rejects(() => executor({
  ...input, message: { ...input.message, parts: [{ filename: '../escape.md', raw: file.toString('base64') }] },
}), /Invalid A2A filename/);
await assert.rejects(() => executor({
  ...input, message: { ...input.message, parts: [{ filename: '..\\escape.md', raw: file.toString('base64') }] },
}), /Invalid A2A filename/);
await assert.rejects(() => executor({
  ...input, message: { ...input.message, parts: [{ filename: 'broken.md', raw: '@@@' }] },
}), /Invalid A2A base64 file/);
const card = new A2AServer({
  baseUrl: 'http://127.0.0.1:3000', agentName: 'DSH', agentDescription: 'test',
  agentVersion: '0.1.0', execute: executor,
  authToken: 'test-bearer-token',
  skills: [{ id: 'file_drop', name: 'File drop', description: 'Save files' }],
});
assert.deepEqual(card.card.skills[0].inputModes, ['text/plain', '*/*']);
const rpc = JSON.stringify({ jsonrpc: '2.0', id: 'smoke-rpc', method: 'SendMessage', params: {
  message: { messageId: 'smoke-wire', role: 'ROLE_USER', parts: [{ text: '保存' }, {
    filename: 'wire.md', mediaType: 'text/markdown', raw: file.toString('base64'),
  }] },
} });
const noAuth = await card.handle({ method: 'POST', url: '/a2a', headers: { 'content-type': 'application/json' } }, rpc);
assert.equal(noAuth.status, 401);
const accepted = await card.handle({ method: 'POST', url: '/a2a', headers: {
  'content-type': 'application/json', authorization: 'Bearer test-bearer-token',
} }, rpc);
assert.equal(accepted.status, 200, accepted.body);
const wireResult = JSON.parse(accepted.body).result;
const wireTask = wireResult.task ?? wireResult;
assert.ok(wireTask?.id);
for (let attempt = 0; attempt < 30 && !existsSync(join(cwd, 'wire.md')); attempt++) {
  await new Promise((done) => setTimeout(done, 20));
}
assert.deepEqual(readFileSync(join(cwd, 'wire.md')), file);
process.stdout.write(`DSH file receipt smoke test passed; saved to ${cwd}\n`);
