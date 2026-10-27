# Publishing the current ContactAwareSS revision

Repository: https://github.com/MuhammedZhumali/ContactAwareSSGS

This page describes the AITU revision 2 wording correction dated 28 October 2026.
The repository URL is already public. The updated sources become public only after
commit and push. The full AITU reproduction asset is a separate publication step.

## Source commit

The cleanup preserves source code, tests, Maven wrapper, experiment scripts,
current documents, required figures and compact research evidence. It excludes
local datasets, build output, raw runs, duplicate exports and release ZIP/RAR files.
Historical specifications and source documents remain because code and reports
refer to them; old denominators must not be presented as new attempts.

The working branch was `main`, 15 local commits ahead of the locally recorded
`origin/main` when cleanup began. No commit, push, reset or history rewrite was
performed. Review the existing changes before publishing:

```powershell
git status --short
git diff --stat
git diff --check
git add .
git diff --cached --stat
git commit -m "Prepare AITU revision 2 sources and publication materials"
git push origin main
git status --short
```

The final command should have no output once all intended changes are committed.
These commands publish the existing local commits too. If a separate publication
branch is desired, create it first with `git switch -c codex/aitu-v2-publication`
and push that branch with `git push -u origin codex/aitu-v2-publication`.

## Full reproduction release

Upload this prepared archive and its matching `.sha256` file as assets of a **new**
GitHub release after the source commit:

```text
target/aitu-revision-v2-wording-delivery/ContactAwareSS-AITU-v2-wording-reproduction-20261028.zip
target/aitu-revision-v2-wording-delivery/ContactAwareSS-AITU-v2-wording-reproduction-20261028.sha256
```

Archive size: 48,939,233 bytes.
SHA256: `7d96531f6fae98bbd40898ce40d158f386990087209f3e33d49b310fd3a4e721`.
This asset retains the registered 6 root searches and 4 controller attempts; the
wording correction did not add experiments. Its frozen-code identity is in its
manifest; a later repository-documentation commit does not change that executable.

Suggested release tag: `aitu-v2-20261028`. This is a suggested future tag, not an
existing release URL. Copy the actual published release URL after uploading, then
replace the new-package placeholder in the article's data-availability statement.
Do not invent a DOI and do not use the old release as the URL for the new evidence.

The existing release
https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/research
contains the historical final-v2 artifact. It remains unchanged.

## Local evidence and cleanup boundary

The two temporary AITU verification copies were redundant with archived evidence;
only verified copies and Python bytecode caches are candidates for removal.
Original raw runs, frozen JARs, registered inputs, release archives and local
datasets remain outside Git. The cleanup inventory and preservation checks are
saved locally under `target/github-cleanup-20261028/`.

A fresh Git clone does not include those ignored inputs. Use the matching release
asset in a separate directory for reproduction, and restore archived contact data
before running data-dependent tests. The updated source tree and the frozen
experimental version are intentionally distinguished by their manifests.
