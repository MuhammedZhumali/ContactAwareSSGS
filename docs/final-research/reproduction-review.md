# Static reproduction review

Review date: 2026-10-25. Scope: `package_final_research.py`, `rebuild_final_source.py`, `replay_final_case.py`, `docs/final-research/reproduction.md`, their direct local imports, the frozen manifest and the Java tests' external fixture entry points. No experiment, JVM, rebuild, archive creation, active campaign result or checkpoint was read or executed. This is a static reproduction review, not an unpack-and-run validation.

## Finding summary

The current packaging rules include the required source and fixture paths for an isolated Windows build/test and replay. The repair that moves rebuilding into a new directory and PC2 fixtures outside that directory's `target/` addresses the concrete `clean` deletion problem. No omitted required fixture was found in this bounded review. The two portability issues below should be fixed before describing the helpers as generally runnable on another Windows installation; the third point narrows the integrity claim.

### P2: document Python 3.11 or newer

`docs/final-research/reproduction.md:34` says Python 3. Both helpers import `run_final_research`, which imports `run_research_validation.sha`; `scripts/run_research_validation.py:12` calls `hashlib.file_digest`. That API requires Python 3.11+. On Python 3.10 or earlier the first source/model/JAR hash check fails before build or replay.

Minimal fix: state **Python 3.11+** for all reproduction commands. No historical script or executable change is needed. The package's other Python dependencies for these two helpers are standard-library modules.

### P2: quote the temporary path within Surefire's argLine

`scripts/rebuild_final_source.py:28` constructs `-DargLine=-Xmx1024m -Djava.io.tmpdir=` followed by the unquoted temporary directory. Passing that entire Maven option as one `subprocess` argument protects the outer invocation, but Surefire subsequently parses the value into the forked JVM's arguments. A restored package/output path containing a space therefore splits the `java.io.tmpdir` value and can make the test JVM fail to start.

Minimal fix: quote the property value inside `argLine`, for example `-DargLine=-Xmx1024m -Djava.io.tmpdir="<absolute temp path>"`. Preserve the separate list argument containing `-Dpc2.fixtures=...`. This does not change the registered experiment or the packaged final-v2 JAR. The present original workspace path has no spaces, so this is a reproduction portability failure rather than evidence of a failed frozen build.

### P3: complete the promised fixture hash verification

`scripts/rebuild_final_source.py:15-21` verifies each source file and all 40 baseline fixture files. Line 23 copies the PC2 fixture directory without checking its own `manifest.json` `files` hashes. The helper also loads the source-manifest file without first comparing its bytes to the top-level `sourcesSha256`. Thus the document's statement that it copies hash-verified frozen source/required fixtures is broader than the helper's checks.

Current inputs are intact: this review independently verified all 181 source entries, all 40 baseline fixture entries and all 22 PC2 file-hash entries, with zero mismatches. This point does not block the current byte set from rebuilding. Minimal fix: verify the source-manifest digest against `manifest.sourcesSha256`, then verify the PC2 manifest's 22 relative entries before copying. A restored-archive integrity pass against `target/final-research/reproduction-files.json` can additionally cover the baseline and PC2 manifest files themselves. Do not invoke the old global history verifier as a substitute: its preserved-history ledger intentionally references versions that the compact final archive need not contain.

## Required inputs checked

| Input category | Current check and package/rebuild route |
| --- | --- |
| Frozen production/tests/build descriptors | All 181 paths in `target/final-research/final-v2-sources.json` exist and match. Package lines 13-14 include them; rebuild lines 15-17 restore them. Includes `.mvn/wrapper/maven-wrapper.properties`, `pom.xml`, `mvnw.cmd`; Java/test source comprises the remaining entries. |
| Baseline data and formal witness files | All 40 paths in `target/research-validation/baseline-restored-build/fixture-manifest.json` exist and match. Package lines 27-29 include both files and the manifest itself; rebuild lines 18-21 restore them. None is inside the new build's `target/`. |
| PC2 regression fixtures | Package lines 30-31 include all 23 files recursively: the manifest plus its 22 hashed entries. Rebuild copies them to `<new build>/pc2-fixtures` and passes `-Dpc2.fixtures=...`. |
| Final case inputs | `target/final-research/protocol.json`, its 22 model files, `manifest.json`, source identity and final-v2 JAR are inside the final output tree selected by package lines 20-26. Replay verifies selected model/JAR hashes and uses a new output directory. |
| Local Python imports | Package line 16 includes the helper closure: `rebuild_final_source`, `replay_final_case`, `run_final_research`, `run_research_validation`, `run_security_evaluation_se21`. No import-time historical-file read is required by that closure. |

The union of statically required helper/build/replay paths checked here contains 350 existing files. This is not the expected final archive file count; it deliberately excludes campaign output enumeration and unrelated documentation. PC2 `source-hashes.json` refers to historical files for provenance, but `PracticalPlanPortfolioTest` reads the supplied `observation.json`, `alternative-plan.json` and `selected-plan.json` from the declared fixture directory; it does not require rebuilding the old source experiment.

The identified Java fixture readers are covered: `OrekitContactGeneratorTest` reads the saved Earth files and ISS GP; `ScheduleCliTest` reads the saved ISS dataset; `HorizonWitnessIntegrationTest` reads formal-cost-model verification; schedule-based policy tests use `PolicyExperimentFixtures.DEFAULT_SCHEDULE`, whose relative dataset source resolves within the copied ISS tree. `PracticalPlanPortfolioTest` uses the PC2 fixture property. Tests that write `target/r1-small-quality.json`, `target/pc2-oracle-*.json` or temporary controlled-witness output write only inside the new isolated build.

## Evidence preservation and launch behavior

Both helpers resolve `--out` below the restored project root and create it with `exist_ok=False`. They therefore refuse an existing output directory. Rebuild invokes Maven with `cwd=<new build>`; its `clean` applies only to that new build's `target`, not the restored archive's `target/final-research`. The restored baseline data/docs, PC2 fixtures and helper registration/log are outside that build target. Replay does not invoke Maven or delete files; it writes only its new replay destination and kills/reaps only its owned Java child on the registered timeout.

The latest inspected replay helper honors `JAVA_HOME` at line 22, so another Windows host need not edit the frozen historical launcher. Both helpers remain Windows launchers (`mvnw.cmd`, `java.exe`), consistent with the PowerShell examples; no Linux/macOS portability was assessed. Java 21, a usable Python 3.11+ runtime, Maven/dependency network access or a populated cache, and loopback permission for the HTTP tests are external prerequisites. The package excludes local Maven caches and source ZIPs, but includes the source files and shaded final JAR; these exclusions do not omit a build/test/replay fixture.

## Reviewed file identities and validation limit

| Reviewed file | SHA-256 at review |
| --- | --- |
| `scripts/package_final_research.py` | `e4dfa1ae992e241ff388d030eee23d6e2b99143e488218808662f9f9d51a9cdf` |
| `scripts/rebuild_final_source.py` | `f3de7dca2f0f9447a689cc6691483016a66e32b49be2ffef86005c5d82461f13` |
| `scripts/replay_final_case.py` | `1dfe1898ae881f3bbbb3ecbbb4e6ffa1a48cd602e6e7bd19bbe3ee0160a2cb5d` |
| `docs/final-research/reproduction.md` | `f6818aa92bf9c56c4ffbe4ef64972eaf5c6006d3835e03fa4e57d68324c8b2a7` |

All three helper scripts passed Python AST parsing. Source/base/PC2 hashes and required-file presence were checked without importing the launchers. No assertion of a successful fresh restored-package build or replay is made; that requires a later isolated execution after the campaign, if authorized. Any subsequent fixes supersede these reviewed script hashes and should be recorded as a follow-up check rather than silently reinterpreting this snapshot.

## Static follow-up: all three findings addressed

On 2026-10-25 the parent applied the three minimal corrections. This follow-up inspected only those edits and confirmed:

- **Python prerequisite resolved:** `reproduction.md:34-35` now explicitly requires Windows, Java 21 and Python 3.11 or newer.
- **Surefire path quoting resolved:** `rebuild_final_source.py:33` now embeds `-Djava.io.tmpdir="<absolute temp path>"` inside `argLine`, preserving spaces when Surefire parses the JVM arguments.
- **Fixture identity checks resolved:** lines 15-17 verify the source-manifest SHA-256 before reading its entries; lines 25-28 verify each PC2 manifest entry before copying the fixture tree. An independent read-only check confirmed the current source-manifest digest and all 22 PC2 file hashes match.

The updated rebuild helper passed Python AST parsing. All three findings above are **closed by static inspection** for these file identities:

| Updated file | SHA-256 |
| --- | --- |
| `scripts/rebuild_final_source.py` | `292fe85e9217c4305524400f916f908df9054ec44305435ba8e081a44fd8c37d` |
| `docs/final-research/reproduction.md` | `6db7bebca1332cd6c1328e53840263f0ac1f0e98f4128e16db69c86353cf4d33` |

No JVM, build, replay, experiment or active-checkpoint read was performed. The remaining boundary is unchanged: this confirms the fixes and static input coverage, not a fresh restored-package execution.
