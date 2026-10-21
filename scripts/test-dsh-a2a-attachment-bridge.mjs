import { strict as assert } from 'node:assert';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const packageDir = resolve(process.argv[2] ?? '');
const outputRoot = resolve(process.argv[3] ?? packageDir);
const { A2AServer, createDshAgentExecutor } = await import(
  pathToFileURL(join(packageDir, 'lib', 'index.js')).href
);
const cwd = mkdtempSync(join(outputRoot, 'attachment-bridge-smoke-'));
const prompts = [];
let creates = 0;
const agent = {
  session: { deriveMessages: () => [{ role: 'assistant', content: [{ type: 'text', text: 'DSH_HANDLED' }] }] },
  send(message) { prompts.push(message.content[0].text); },
  async whenIdle() {},
  cancel() {},
};
const executor = createDshAgentExecutor({
  async create() { creates++; return { agent, async dispose() {} }; },
}, { cwd });
const bytes = Buffer.from('# 扫描文档\n一二三\n', 'utf8');
const base64 = bytes.toString('base64');
const request = {
  message: { messageId: 'message-1', role: 'ROLE_USER', parts: [
    { text: '请分析附件，给我一个摘要，不要移动文件。' },
    { filename: 'report.md', mediaType: 'text/markdown', raw: base64 },
  ] },
  taskId: 'task-1', contextId: 'context-1', signal: new AbortController().signal,
};
const reply = await executor(request);
assert.equal(reply.parts[0].text, 'DSH_HANDLED');
assert.equal(creates, 1, 'raw attachments must go through the DSH agent');
assert.match(prompts[0], /请分析附件/);
assert.ok(!prompts[0].includes(base64), 'Base64 must not enter the model prompt');
const manifest = JSON.parse(prompts[0].slice(prompts[0].lastIndexOf('\n[') + 1));
assert.equal(manifest.length, 1);
assert.equal(manifest[0].originalName, 'report.md');
assert.equal(manifest[0].mediaType, 'text/markdown');
assert.equal(manifest[0].sizeBytes, bytes.length);
assert.equal(manifest[0].sha256, createHash('sha256').update(bytes).digest('hex'));
assert.ok(manifest[0].path.startsWith(join(cwd, 'a2a-attachments')));
assert.deepEqual(readFileSync(manifest[0].path), bytes);

await executor({ ...request, message: { ...request.message, parts: [{ text: '普通文字任务' }] } });
assert.equal(prompts[1], '普通文字任务');
await executor({ ...request, message: { ...request.message, parts: [{ text: '保存两个附件' },
  { filename: 'same.md', raw: base64 }, { filename: 'same.md', raw: base64 }] } });
const duplicates = JSON.parse(prompts[2].slice(prompts[2].lastIndexOf('\n[') + 1));
assert.notEqual(duplicates[0].path, duplicates[1].path);
assert.ok(existsSync(duplicates[0].path) && existsSync(duplicates[1].path));
await assert.rejects(() => executor({ ...request, message: { ...request.message,
  parts: [{ text: 'bad' }, { filename: '../escape.md', raw: base64 }] } }), /Invalid A2A filename/);
await assert.rejects(() => executor({ ...request, message: { ...request.message,
  parts: [{ text: 'bad' }, { filename: 'broken.md', raw: '@@@' }] } }), /Invalid A2A base64 attachment/);
assert.equal(prompts.length, 3, 'invalid attachments must never reach DSH agent');

const server = new A2AServer({
  baseUrl: 'http://127.0.0.1:3000', agentName: 'DSH Agent', agentDescription: 'General tasks',
  agentVersion: '0.2.0', execute: executor, authToken: 'test-bearer-token',
  skills: [{ id: 'delegate_task', name: '通用任务', description: 'Handle user tasks',
    inputModes: ['text/plain', '*/*'] }],
});
assert.deepEqual(server.card.skills[0].inputModes, ['text/plain', '*/*']);
const rpc = JSON.stringify({ jsonrpc: '2.0', id: 'rpc-1', method: 'SendMessage', params: { message: {
  messageId: 'message-2', role: 'ROLE_USER', parts: [
    { text: '请处理文件' }, { filename: 'wire.md', mediaType: 'text/markdown', raw: base64 },
  ],
} } });
const noAuth = await server.handle({ method: 'POST', url: '/a2a',
  headers: { 'content-type': 'application/json' } }, rpc);
assert.equal(noAuth.status, 401);
const accepted = await server.handle({ method: 'POST', url: '/a2a',
  headers: { 'content-type': 'application/json', authorization: 'Bearer test-bearer-token' } }, rpc);
assert.equal(accepted.status, 200, accepted.body);
const task = JSON.parse(accepted.body).result;
assert.ok((task.task ?? task).id);
for (let attempt = 0; attempt < 30 && prompts.length < 4; attempt++) {
  await new Promise((done) => setTimeout(done, 20));
}
assert.equal(prompts.length, 4);
assert.match(prompts[3], /wire.md/);
assert.ok(!prompts[3].includes(base64));
await executor.disposeAll();
process.stdout.write('DSH generic A2A attachment bridge passed: staged file, model prompt, validation, auth\n');
