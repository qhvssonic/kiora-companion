import { strict as assert } from 'node:assert';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';

const cardUrl = process.argv[2];
const configPath = process.argv[3];
if (!cardUrl || !configPath) throw new Error('Usage: node smoke-dsh-a2a-delegate-http.mjs <card-url> <a2a.json>');
const config = JSON.parse(readFileSync(configPath, 'utf8'));
const token = config.server?.authToken;
assert.equal(typeof token, 'string');
assert.ok(token.length >= 32);
const cardResponse = await fetch(cardUrl);
assert.equal(cardResponse.status, 200);
const card = await cardResponse.json();
const skill = card.skills.find((item) => item.id === 'delegate_task');
assert.ok(skill, 'delegate_task skill missing');
assert.ok(skill.inputModes?.includes('*/*'), 'delegate_task does not advertise file input');
assert.ok(card.securitySchemes, 'Bearer authentication is not advertised');
const endpoint = card.supportedInterfaces.find((item) => item.protocolBinding === 'JSONRPC')?.url;
assert.ok(endpoint, 'JSONRPC endpoint missing');
const marker = `KIORA_A2A_BRIDGE_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
const filename = `kiora-bridge-smoke-${Date.now()}.txt`;
const content = Buffer.from(`${marker}\n`, 'utf8');
const rpc = async (method, params) => {
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
    body: JSON.stringify({ jsonrpc: '2.0', id: randomUUID(), method, params }),
  });
  assert.equal(response.status, 200, `HTTP ${response.status} for ${method}`);
  const body = await response.json();
  if (body.error) throw new Error(`${method}: ${body.error.message}`);
  return body.result;
};
const submittedAt = Date.now();
let task = await rpc('SendMessage', { message: {
  messageId: randomUUID(), role: 'ROLE_USER', parts: [
    { text: '请实际读取这个附件，并仅回复文件中的验证码。无法读取时请明确说明。' },
    { raw: content.toString('base64'), filename, mediaType: 'text/plain' },
  ],
}, configuration: { blocking: false } });
task = task.task ?? task;
assert.ok(task.id, 'Task ID missing');
assert.ok(Date.now() - submittedAt < 20_000, 'DSH did not return the Task promptly');
process.stdout.write(`DSH returned ${task.status?.state} Task in ${Date.now() - submittedAt} ms\n`);
for (let attempt = 0; attempt < 300 && !['TASK_STATE_COMPLETED', 'TASK_STATE_FAILED'].includes(task.status?.state); attempt++) {
  await new Promise((done) => setTimeout(done, 500));
  task = await rpc('GetTask', { id: task.id });
  task = task.task ?? task;
}
const output = task.artifacts?.flatMap((artifact) => artifact.parts ?? [])
  .map((part) => part.text ?? '').join('\n') ?? '';
assert.equal(task.status?.state, 'TASK_STATE_COMPLETED', `DSH task state: ${task.status?.state}; ${output.slice(0, 500)}`);
assert.ok(output.includes(marker), `DSH did not read attachment: ${output.slice(0, 500)}`);
assert.ok(!output.includes(content.toString('base64')), 'DSH output unexpectedly contains Base64');
process.stdout.write(`DSH agent read staged A2A attachment: ${filename}\n`);
