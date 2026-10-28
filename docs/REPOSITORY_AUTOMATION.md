# Repository automation

## Android TV verification

`.github/workflows/android.yml` builds the APK and runs domain, protocol, notification and lint checks for Android/workflow changes, or on manual dispatch. Its token has read-only repository access; reports are retained for seven days.

The original setup action failed before Gradle because the v3 default requested the retired SDK package `tools`. The workflow now explicitly installs only `platform-tools`, `platforms;android-34` and `build-tools;34.0.0`, and suppresses verbose license text. The 25-minute job budget bounds a stalled setup/build.

The corrected workflow completed successfully on GitHub in [run 40709353583](https://github.com/farhaniqbalta/TVCompanion/actions/runs/40709353583).

## Automatic PR descriptions — no additional API key

`.github/workflows/pr-description.yml` appends or refreshes a marked section of an open pull request's description on open, new commits, reopen, and ready-for-review events. It uses the automatic repository `GITHUB_TOKEN`; no new secret, personal token, AI provider key or subscription is needed for this metadata-based workflow.

The generated section lists changed areas, file/line counts, commit subjects, up to thirty file entries and a link to checks. It is explicitly labeled as metadata-generated, not an AI summary. It never claims build/test success. Large PRs are bounded and incomplete metadata is identified.

Handwritten text outside these markers is preserved:

```html
<!-- tvcompanion:auto-description:start -->
...managed summary...
<!-- tvcompanion:auto-description:end -->
```

To opt a PR out, add this anywhere in its description:

```html
<!-- tvcompanion:auto-description:off -->
```

Ambiguous/malformed managed markers and descriptions exceeding the size budget are left unchanged. The helper re-reads the latest body before writing and skips a closed PR or superseded head. There remains a small API read/update race with simultaneous human edits; keep manual content outside the managed section and review updates normally.

The workflow also supports manual dispatch with `pull_request_number`, from the default branch only. **Automatic description events begin after this workflow/helper are merged into the default branch.** A new `pull_request_target` workflow in a PR cannot install itself into the base branch. For an already-open PR after merge, push another commit, reopen it, or run the workflow manually.

### Trust boundary

The write-capable job checks out only the immutable `github.workflow_sha` for its reviewed workflow source, with persisted credentials disabled and a sparse checkout of helper code. It never fetches or executes a PR head, build script, patch or dependency. PR metadata is obtained through structured GitHub API calls and escaped for display. It is not inserted into shell commands or JavaScript source. Only `contents: read` and `pull-requests: write` are granted to this job. Actions used by the new automation are pinned to immutable commits.

The helper and tests are in `.github/scripts/`. The separate read-only `.github/workflows/automation-checks.yml` runs the tests on relevant pushes/PRs. It may test untrusted proposed helper code, but has no write permission or custom secrets.

Run locally with Node 24:

```sh
node --test .github/scripts/pr-description.test.cjs
```

Tests cover manual-text preservation, idempotence, opt-out, broken markers, untrusted metadata rendering, size limits, stale/closed PRs and API update orchestration. No npm packages are required.

## Optional GitHub-native AI wording

GitHub Copilot's [PR Summary command](https://docs.github.com/en/copilot/how-tos/copilot-on-github/copilot-for-github-tasks/create-a-pr-summary) can generate an AI-written description in the GitHub editor without a separate provider API key. It requires an eligible Copilot plan and a human review/save step; it is not the automation above. Do not assume a Copilot subscription from the existence of this repository.

If using Copilot, place the reviewed prose outside the automation markers. The no-key workflow will preserve that text and maintain only its metadata section.
