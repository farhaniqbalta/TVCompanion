'use strict';

const START = '<!-- tvcompanion:auto-description:start -->';
const END = '<!-- tvcompanion:auto-description:end -->';
const OFF = '<!-- tvcompanion:auto-description:off -->';
const MAX_BODY = 60_000;

// All PR metadata is untrusted data. Render it as escaped code, never executable text.
function code(value, limit = 240) {
  const text = String(value ?? '').replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, ' ')
    .replace(/\s+/g, ' ').trim();
  const clipped = text.length > limit ? `${text.slice(0, limit - 1)}…` : text;
  return `<code>${clipped.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')}</code>`;
}
function count(value, fallback = 0) {
  return Number.isSafeInteger(value) && value >= 0 ? value : fallback;
}
function area(path) {
  if (path.startsWith('.github/')) return 'GitHub automation';
  if (path.startsWith('android/core/domain/')) return 'Android domain and application logic';
  if (path.startsWith('android/core/designsystem/')) return 'Android TV design system';
  if (path.startsWith('android/features/notifications/')) return 'Notifications';
  if (path.startsWith('android/features/smart-home/')) return 'Smart-home presentation';
  if (path.startsWith('android/integrations/')) return 'Device integrations';
  if (path.startsWith('android/')) return 'Android application and build';
  if (path.startsWith('ios/')) return 'Apple workspace';
  if (path.startsWith('contracts/')) return 'Integration contracts';
  if (path.startsWith('brand/')) return 'Brand assets';
  if (path.startsWith('docs/') || /\.md$/i.test(path)) return 'Documentation';
  return 'Repository tooling and configuration';
}
function generate({ pr, files, commits, owner, repo }) {
  const groups = new Map();
  for (const file of files) {
    const label = area(file.filename);
    groups.set(label, (groups.get(label) ?? 0) + 1);
  }
  const totalFiles = count(pr.changed_files, files.length);
  const totalCommits = count(pr.commits, commits.length);
  const url = `https://github.com/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/pull/${pr.number}/checks`;
  const lines = [START, '## Automated change summary', '',
    '_Generated from GitHub file and commit metadata; not an AI-written summary._', '',
    `**Scope:** ${totalFiles} changed files, +${count(pr.additions)} / −${count(pr.deletions)} lines; ${totalCommits} commits.`,
    `**Head:** ${code(pr.head.sha.slice(0, 7))}`, '', '### Areas changed', ''];
  if (!groups.size) lines.push('- No changed-file metadata is available yet.');
  for (const [label, size] of [...groups].sort(([a], [b]) => a.localeCompare(b))) {
    lines.push(`- ${label}: ${size} ${size === 1 ? 'file' : 'files'}`);
  }
  if (totalFiles > files.length) lines.push('', `_Only ${files.length} of ${totalFiles} files were returned by GitHub; area counts are partial._`);
  lines.push('', '### Commit summary', '');
  if (!commits.length) lines.push('- No commit metadata is available yet.');
  for (const commit of commits.slice(0, 12)) {
    lines.push(`- ${code(commit.sha.slice(0, 7))} ${code(commit.commit.message.split(/\r?\n/)[0])}`);
  }
  if (totalCommits > 12) lines.push(`- Showing the first 12 of ${totalCommits} commits.`);
  lines.push('', '<details>', `<summary>Changed files (showing up to 30 of ${totalFiles})</summary>`, '');
  for (const file of files.slice(0, 30)) {
    lines.push(`- ${code(file.filename, 360)} — ${code(file.status, 40)} (+${count(file.additions)} / −${count(file.deletions)})`);
  }
  lines.push('', '</details>', '', `**Validation:** Review the [workflow checks](${url}). This summary does not claim that tests passed.`, END);
  return lines.join('\n');
}
function mergeBody(body, generated) {
  body = body ?? '';
  if (body.includes(OFF)) return { body, reason: 'opted out' };
  const starts = body.split(START).length - 1;
  const ends = body.split(END).length - 1;
  let next;
  if (!starts && !ends) next = `${body}${body ? '\n\n' : ''}${generated}`;
  else if (starts === 1 && ends === 1 && body.indexOf(START) < body.indexOf(END)) {
    next = body.slice(0, body.indexOf(START)) + generated + body.slice(body.indexOf(END) + END.length);
  } else return { body, reason: 'ambiguous or incomplete managed markers' };
  if (next.length > MAX_BODY) return { body, reason: 'description would exceed the size budget' };
  return { body: next, reason: next === body ? 'already current' : null };
}
async function run({ github, context, core }) {
  const raw = context.payload.pull_request?.number ?? context.payload.inputs?.pull_request_number;
  if (!/^[1-9]\d*$/.test(String(raw)) || !Number.isSafeInteger(Number(raw))) throw new Error('A valid pull request number is required');
  const args = { ...context.repo, pull_number: Number(raw) };
  const { data: pr } = await github.rest.pulls.get(args);
  if (pr.state !== 'open') { core.info('Skipping a closed pull request.'); return; }
  if ((pr.body ?? '').includes(OFF)) { core.info('PR opted out of generated descriptions.'); return; }
  const files = await github.paginate(github.rest.pulls.listFiles, { ...args, per_page: 100 });
  const { data: commits } = await github.rest.pulls.listCommits({ ...args, per_page: 12, page: 1 });
  const generated = generate({ pr, files, commits, ...context.repo });
  // Preserve edits made while metadata was loading; do not publish for a superseded head.
  const { data: latest } = await github.rest.pulls.get(args);
  if (latest.state !== 'open' || latest.head.sha !== pr.head.sha) { core.info('PR closed or head changed; skipping stale summary.'); return; }
  const result = mergeBody(latest.body, generated);
  if (result.reason) { core.info(`Leaving description unchanged: ${result.reason}.`); return; }
  await github.rest.pulls.update({ ...args, body: result.body });
  core.info(`Updated the managed description section on PR #${args.pull_number}.`);
}
module.exports = { run, generate, mergeBody, code, START, END, OFF };
