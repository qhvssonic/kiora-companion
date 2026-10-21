import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const cardUrl = process.argv[2];
const configPath = process.argv[3];
if (!cardUrl || !configPath) throw new Error('Usage: node smoke-dsh-a2a-http.mjs <card-url> <a2a.json>');
const config = JSON.parse(readFileSync(configPath, 'utf8'));
const token = config.server?.authToken;
assert.equal(typeof token, 'string');
assert.ok(token.length >= 32);
const cardResponse = await fetch(cardUrl);
assert.equal(cardResponse.status, 200);
const card = await cardResponse.json();
const skill = card.skills.find((item) => item.id === 'file_drop');
assert.ok(skill, 'file_drop skill missing');
assert.ok(skill.inputModes.includes('*/*'), 'file_drop does not advertise file input');
const endpoint = card.supportedInterfaces.find((item) => item.protocolBinding === 'JSONRPC')?.url;
assert.ok(endpoint, 'JSONRPC endpoint missing');
const content = Buffer.from(`# Kiora A2A 文件接收测试\n${randomUUID()}\n`, 'utf8');
const filename = `kiora-a2a-smoke-${Date.now()}.md`;
const rpc = async (method, params) => {
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ jsonrpc: '2.0', id: randomUUID(), method, params }),
  });
  assert.equal(response.status, 200, `HTTP ${response.status} for ${method}`);
  const body = await response.json();
  if (body.error) throw new Error(`${method}: ${body.error.message}`);
  return body.result;
};
let task = await rpc('SendMessage', { message: {
  messageId: randomUUID(), role: 'ROLE_USER',
  parts: [{ text: '请直接保存附件' }, {
    raw: content.toString('base64'), filename, mediaType: 'text/markdown',
  }],
} });
task = task.task ?? task;
assert.ok(task.id, 'Task ID missing');
for (let attempt = 0; attempt < 30 && !['TASK_STATE_COMPLETED', 'TASK_STATE_FAILED'].includes(task.status?.state); attempt++) {
  await new Promise((done) => setTimeout(done, 200));
  task = await rpc('GetTask', { id: task.id });
  task = task.task ?? task;
}
assert.equal(task.status?.state, 'TASK_STATE_COMPLETED', JSON.stringify(task.status));
const output = task.artifacts?.flatMap((artifact) => artifact.parts ?? [])
  .map((part) => part.text ?? '').join('\n') ?? '';
assert.match(output, /Saved 1 file/);
assert.ok(output.includes(filename));
process.stdout.write(`A2A HTTP file transfer passed: ${filename}\n`);
