'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { generate, mergeBody, run, code, START, END, OFF } = require('./pr-description.cjs');
const sample = () => ({
  owner: 'example', repo: 'TVCompanion',
  pr: { number: 2, state: 'open', body: 'Handwritten purpose and test notes.', head: { sha: 'a'.repeat(40) }, changed_files: 2, additions: 10, deletions: 3, commits: 1 },
  files: [{ filename: 'android/core/domain/Light.kt', status: 'modified', additions: 8, deletions: 2 }, { filename: 'docs/NOTES.md', status: 'added', additions: 2, deletions: 1 }],
  commits: [{ sha: 'b'.repeat(40), commit: { message: 'Separate application state\n\nLonger body' } }]
});
test('adds a bounded factual section without inventing test results', () => {
  const summary = generate(sample());
  assert.ok(summary.includes('Android domain and application logic: 1 file'));
  assert.ok(summary.includes('Documentation: 1 file'));
  assert.ok(summary.includes('Separate application state'));
  assert.ok(!summary.includes('Longer body'));
  assert.ok(summary.includes('does not claim that tests passed'));
  assert.ok(summary.startsWith(START)); assert.ok(summary.endsWith(END));
});
test('preserves manual text byte for byte and is idempotent', () => {
  const manual = 'Purpose\n\n- [x] Tested manually\n  ';
  const generated = generate(sample());
  const first = mergeBody(manual, generated);
  assert.ok(first.body.startsWith(manual + '\n\n'));
  assert.equal(mergeBody(first.body, generated).reason, 'already current');
  const existing = `Before\n${START}\nOld generated text\n${END}\nAfter\n`;
  assert.equal(mergeBody(existing, generated).body, `Before\n${generated}\nAfter\n`);
});
test('opt-out and malformed/duplicate markers never erase author content', () => {
  for (const body of [OFF, START, END, `${END} notes ${START}`, `${START}${START}${END}`]) {
    assert.equal(mergeBody(body, generate(sample())).body, body);
    assert.ok(mergeBody(body, generate(sample())).reason);
  }
});
test('escapes untrusted filenames and commit text instead of rendering markup', () => {
  const input = sample();
  input.files[0].filename = 'file</code><script>alert(1)</script>.kt';
  input.commits[0].commit.message = '</code><img src=x onerror=alert(1)> @someone';
  const summary = generate(input);
  assert.ok(!summary.includes('<script>')); assert.ok(!summary.includes('<img'));
  assert.ok(summary.includes('&lt;script&gt;'));
  assert.ok(code('`$(echo bad)`${{ secrets.TOKEN }}').includes('$(echo bad)'));
});
test('caps large output and explicitly reports partial metadata', () => {
  const input = sample(); input.pr.changed_files = 4000; input.pr.commits = 90;
  input.files = Array.from({ length: 100 }, (_, i) => ({ filename: 'docs/' + i + 'x'.repeat(2000), status: 'added' }));
  input.commits = Array.from({ length: 12 }, () => ({ sha: 'b'.repeat(40), commit: { message: 'x'.repeat(10000) } }));
  const summary = generate(input);
  assert.ok(summary.includes('Only 100 of 4000'));
  assert.ok(summary.includes('first 12 of 90'));
  assert.ok(summary.length < 20_000);
  const manual = 'a'.repeat(59_999);
  assert.equal(mergeBody(manual, summary).body, manual);
});
function fixture({ stale = false, closed = false, optedOut = false, latestManual = 'Latest manual edit' } = {}) {
  const input = sample(); const updates = []; let gets = 0;
  if (closed) input.pr.state = 'closed';
  if (optedOut) input.pr.body = OFF;
  return { updates, context: { repo: { owner: input.owner, repo: input.repo }, payload: { pull_request: { number: 2 } } }, core: { info() {} },
    github: { paginate: async () => input.files, rest: { pulls: {
      get: async () => ({ data: ++gets === 1 ? input.pr : { ...input.pr, body: latestManual, head: { sha: stale ? 'c'.repeat(40) : input.pr.head.sha } } }),
      listFiles() {}, listCommits: async () => ({ data: input.commits }), update: async (data) => updates.push(data)
    } } }
  };
}
test('API orchestration updates only the latest body and the requested PR', async () => {
  const f = fixture(); await run(f);
  assert.equal(f.updates.length, 1);
  assert.equal(f.updates[0].pull_number, 2);
  assert.ok(f.updates[0].body.startsWith('Latest manual edit\n\n'));
});
test('closed, superseded, or opted-out PRs are not changed', async () => {
  for (const options of [{ closed: true }, { stale: true }, { optedOut: true }, { latestManual: OFF }]) {
    const f = fixture(options); await run(f); assert.equal(f.updates.length, 0);
  }
});
test('rejects invalid dispatch input before touching the API', async () => {
  const f = fixture(); f.context.payload = { inputs: { pull_request_number: '2; echo token' } };
  await assert.rejects(run(f), /valid pull request number/);
  assert.equal(f.updates.length, 0);
});
