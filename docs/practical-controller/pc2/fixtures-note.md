# PC2 pre-change decision fixtures

Extracted on 2026-10-25 from preserved PC1/R1 artifacts using `scripts/pc2_extract_fixtures.py`. Extraction reads JSON and verifies source hashes; it launches no JVM, search, simulation or build. It never replaces different fixture bytes. These are historical witnesses, not PC2 outcomes.

All decision and attempt indexes are zero based. Scores are `(servedContacts, usefulSecureTime seconds, controlCpu seconds, managementBytes, new directional keys)`. Each selected score is incremental from its own saved observation to its own evaluationEnd. A whole-run score or a score from an earlier root is not substituted for this value.

| Fixture | Saved root and permitted horizon | Selected pre-change result | Known alternative at this root |
| --- | --- | --- | --- |
| `post-prepare-known-retained-winner` | R1 `018-demand-none-practical-H2-CANONICAL`, decision 41 / attempt 10; `2026-10-12T00:13:53.296295166Z`; H=2, current + two future contacts; end `2026-10-12T01:49:19.758453369Z` | CONTINUE; INCOMPLETE / NODE_LIMIT; 5000 nodes; `(2,100,1.93,3240,4)` | Saved nominal replay of attempt 9 suffix offset 32: `(2,100,1.22,2160,2)`. Both plans reach the same end. Retained wins on CPU; neither is claimed optimal. |
| `iss46-earliest-skipped-contact` | PC1 offline `02-ISS46-H2-CANONICAL`, decision 1 / attempt 1; `2026-10-10T00:07:54.680328369Z`; H=2, current + two future contacts; end `2026-10-10T01:49:34.350128174Z` | WAIT to `00:10:45.617523193Z`, the first contact's end; INCOMPLETE / NODE_LIMIT; 5000 nodes, 58 terminal plans; `(1,20,1.31,1080,8)` | Unknown. This identifies an actually skipped contact, not an already proved better continuation at that root. |
| `demand-none-disadvantageous-wait` | R1 `020-demand-none-practical-H2-CONTINUE_WAIT`, decision 1 / attempt 1; `2026-10-12T00:08:37.296295166Z`; H=2, current + two future contacts; end `2026-10-12T01:49:19.758453369Z` | WAIT to `00:14:59.990386963Z`, the first contact's end; INCOMPLETE / TIME_LIMIT; 3909 nodes; `(2,40,1.42,2160,4)` | A prior COMPLETE plan exists, but its root and evaluationEnd differ. Its remaining plan is supplied for future replay/extension; its score at this fixture root is explicitly unknown. |

The post-PREPARE root is immediately after **starting** PREPARE, with completion still pending at `00:13:55.296295166Z`. Its allocated key and operation IDs, pending step and reservation are preserved. The retained plan delays g2 activation until the return, while the fresh plan activates it before that return. The same-root saved replay is the concrete cost-dominance witness; it avoids comparing attempt 9's original score with attempt 10's score.

The ISS witness is the earliest contact by start time in the exact 46-contact schedule. Its actual contact metric has zero payload and `served=false`; the saved run eventually finishes with 1/46 contacts and whole-run `(1,20,0.71,1080,2)`. The selected search plan's `(1,20,1.31,1080,8)` is a forecast from this earlier root. These different scores are expected because subsequent replanning changes execution. No same-root improvement is inferred from the completed 40-contact H1 run.

The third fixture is distinct from the post-PREPARE case. At its prior decision 0, the saved COMPLETE plan has `(2,390,1.42,2160,4)` and ends at `2026-10-12T00:30:27.284088135Z`. At decision 1 the horizon additionally includes the return contact. The prior suffix remains evidence of a service-capable planned continuation, but its old score does not certify dominance at the changed horizon. The executed WAIT does skip the entire first contact, confirmed by the actual event and contact metrics.

## Artifact layout and snapshot boundary

The output root is `target/practical-controller-pc2/fixtures/`; `manifest.json` identifies all three fixtures and hashes the extracted files. `source-hashes.json` records the original source files; the manifest separately records the extractor hash. Each fixture contains:

- `observation.json`: the complete saved, permitted PlanningObservation, including H, visible contacts, evaluationEnd, state, ongoing work, IDs, reservations and accrued progress.
- `selected-plan.json`, `saved-search.json`, `fixture.json`: exact selected plan, attempt ledger record, stop reason and all five root-relative metrics.
- `actual-snapshot-reconstruction.json`: the exact saved scenario and actual decision prefix, expected observation, source final snapshot/checkpoint paths and hashes, and a reconstruction recipe.

An intermediate actual ExecutionSnapshot was **not** saved at these roots. The extracted final checkpoint is a source reference, not an initial state that can be rewound. The reconstruction input is explicitly marked `RECONSTRUCTION_INPUT_ONLY_NOT_AN_EXECUTION_SNAPSHOT`. No actual snapshot has been fabricated or claimed reconstructed by this extraction.

To restore an actual root, a package-local probe must start the exact supplied scenario, enter controlled execution, and replay each saved action through `SecurityPolicyController.executeAction` with nominal outcomes. Before each action it must compare the complete current observation to the saved one; after each action it must compare `at` with saved `resumedAt`. Only after equality with `expectedObservation` may it export the actual root snapshot. The parent PC2 validation owns this reconstruction and its execution evidence. For nominal plan evaluation, `BranchEngine.create(observation)` uses only the supplied observation; full scenario and actual future journal must remain unavailable to the planner.

The post-PREPARE fixture additionally includes `alternative-plan.json`, `continuation-input.json`, and `saved-replay-result.json`, copied semantically from the existing PC1 probe. Its protocol input hash and both historical source hashes are checked. The disadvantageous-WAIT fixture includes `prior-root-continuation.json` with an explicit null same-root score. Both WAIT fixtures include `skipped-contact-evidence.json`.

## Reproduce and check

From the repository root, using the available bundled Python runtime:

```powershell
& 'C:\Users\Muhalek\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -X utf8 scripts/pc2_extract_fixtures.py
& 'C:\Users\Muhalek\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -X utf8 scripts/pc2_extract_fixtures.py --verify
```

The extractor checks selected-action equality, terminal plan end, exact saved observation equality for the existing probe, known replay scores, chronological prefix alignment, registered PC1 model/JAR hashes, and factual unserved first-contact metrics. These are JSON/provenance checks, not a new simulation or an independent rerun of the historical replay. A changed source or fixture fails verification instead of silently overwriting the evidence.
