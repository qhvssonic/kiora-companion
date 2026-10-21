import { strict as assert } from 'node:assert';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const packageDir = resolve(process.argv[2] ?? '');
const { A2AServer } = await import(pathToFileURL(join(packageDir, 'lib', 'index.js')).href);
let release;
const gate = new Promise((done) => { release = done; });
const server = new A2AServer({
  baseUrl: 'http://127.0.0.1:3000', agentName: 'Test', agentDescription: '',
  agentVersion: '0.1.0', execute: async () => {
    await gate;
    return { messageId: 'reply', role: 'ROLE_AGENT', parts: [{ text: 'done' }] };
  },
});
const call = (method, params, id) => server.handle({ method: 'POST', url: '/a2a',
  headers: { 'content-type': 'application/json' } }, JSON.stringify({ jsonrpc: '2.0', id, method, params }));
const response = await Promise.race([
  call('SendMessage', { message: {
    messageId: 'msg-1', role: 'ROLE_USER', parts: [{ text: 'slow task' }],
  }, configuration: { blocking: false } }, 'rpc-1'),
  new Promise((_, reject) => setTimeout(() => reject(new Error('SendMessage blocked on agent execution')), 1000)),
]);
assert.equal(response.status, 200, response.body);
const first = JSON.parse(response.body).result.task;
assert.equal(first.status.state, 'TASK_STATE_WORKING');
release();
let completed;
for (let attempt = 0; attempt < 50; attempt++) {
  const reply = await call('GetTask', { id: first.id }, `poll-${attempt}`);
  completed = JSON.parse(reply.body).result;
  if (completed.status?.state === 'TASK_STATE_COMPLETED') break;
  await new Promise((done) => setTimeout(done, 20));
}
assert.equal(completed.status.state, 'TASK_STATE_COMPLETED');
assert.equal(completed.artifacts[0].parts[0].text, 'done');
process.stdout.write('DSH nonblocking SendMessage returned a Task before execution and GetTask completed it\n');
