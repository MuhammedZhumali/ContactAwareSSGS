# CP10800 completed-pair export check

Checked 2026-10-25. This is a bounded aggregation of completed cases 027, 094 and 095 from `result.json` and `actual.events.json`, plus comparison of the two frozen input models and registration hashes. It is **direct journal/export analysis pending the final independent whole-matrix audit**. It does not check every admission, nonce, forecast selection or checkpoint invariant. No JVM, experiment, budget/source/model change or active checkpoint read occurred; case 096 was not accessed. Only this note was written.

## Completed five-score comparison

All three inspected results have status FINISHED. The five components below are final exported scores, with operation CPU = spacecraft + ground CPU and management bytes = uplink + downlink operation bytes.

| Case | Configuration | Served | Useful s | Operation CPU s | Management B | New directional keys | Controller host s |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 027 | Base CP5400, Next H1 | 46 | 2740 | 32.66 | 49680 | 92 | 527.7763711 |
| 094 | CP10800, Next H1 | 46 | 2740 | 22.91 | 34680 | 62 | 266.5114422 |
| 095 | CP10800, Multi H2 | 46 | 2740 | 22.91 | 34680 | 62 | 462.1005846 |

The entire `simulationMetrics` objects of 094 and 095 compare equal, including per-contact payload and operation counts; all three report no operation failures and 274 complete delivered payload frames. Thus the completed CP10800 horizon pair ties on all five objectives. Both CP10800 rows differ from the completed base **Next** row by `(0,0,-9.75 s,-15000 B,-30 keys)`. No comparison with unfinished base Multi is made.

## Actual material reuse accounts for the modeled savings

STEP_COMPLETED counts from each journal match its exported completed-operation counts:

| Operation | Base Next 027 | CP10800 Next 094 | CP10800 Multi 095 |
| --- | ---: | ---: | ---: |
| PREPARE | 46 | 31 | 31 |
| INSTALL | 46 | 31 | 31 |
| SWITCH | 46 | 31 | 31 |
| OPEN | 46 | 46 | 46 |
| STOP | 46 | 46 | 46 |
| EXPIRE_CONTEXT | 42 | 26 | 26 |

Grouping actual PAYLOAD_TRANSFERRED events by their directional key-ID pairs gives 46 distinct pairs, each used in one contact, for base Next. Each CP10800 run has 31 distinct payload pairs: 16 used in one contact and 15 used in two contacts. Each contact uses one pair in these journals. For example, the same pair serves `orekit-25544-SATNOGS-3634-1` and `orekit-25544-SATNOGS-3634-2` under CP10800. This establishes observed material reuse; it is implemented through OPEN on retained material here, not a journal action named REUSE.

Aggregated journal usage agrees with the exported CPU, management bytes and generated-key totals. PREPARE/INSTALL/SWITCH each occur 15 fewer times, while OPEN remains 46. The cost deltas are exactly:

- PREPARE CPU: 9.20 to 6.20 s, saving 3.00 s; no management bytes.
- INSTALL CPU: 13.80 to 9.30 s, saving 4.50 s; bytes: 36800 to 24800, saving 12000 B.
- SWITCH CPU: 6.90 to 4.65 s, saving 2.25 s; bytes: 9200 to 6200, saving 3000 B.
- OPEN is unchanged at 2.76 s and 3680 B; STOP/EXPIRE_CONTEXT have zero recorded CPU/bytes in these runs.

The 15 avoided successful PREPAREs also explain 30 fewer directional keys. This is an accounting explanation of these observed executions, not a proof that their resource totals are minimal.

## First Next/Multi execution difference

The two CP10800 journals share an identical prefix through array index/sequence 1029. At sequence 1030 both start PREPARE for the same SATNOGS-5024 generation-1 pair, but at different times:

| Event | Next 094 | Multi 095 |
| --- | --- | --- |
| First differing ACTION_STARTED/PREPARE, sequence 1030 | `2026-10-10T21:27:15.528717041Z` | `2026-10-10T22:02:46.818206787Z` |
| PREPARE completes | `21:27:17.528717041Z`, sequence 1032 | `22:02:48.818206787Z`, sequence 1033 |
| INSTALL starts for `orekit-25544-SATNOGS-5024-2`, sequence 1035 | `22:02:48.818206787Z` | Same |
| INSTALL / SWITCH / OPEN complete | `22:02:52.818206787Z` / `22:02:53.818206787Z` / `22:02:54.818206787Z` | Same |

Next prepares earlier; both have completed preparation by the same INSTALL start. This observed timing difference produces no difference in the final metrics. These two permitted source files do not establish why the planner selected that timing; no search/portfolio causal explanation is inferred.

## Timing and frozen-input boundary

Both CP10800 results are practical CANONICAL offline executions (`physicalTimeAdvancedBySearch=false`) with zero recorded decision deadline misses. Their result configurations differ only in horizon 1/2 and output path. `measurement.elapsedNanos` supplies the controller wall seconds above; it includes controller checkpoint callbacks but excludes the final export interval, per `timing-definitions.md`. It is not modeled operation CPU, process wall time or search-only time. These are individual host observations, not independent statistical timing samples.

The result records report `actualSearchAttempts=551/636` for 094/095. Their compact `computation.searches=239/99` is a different, narrower count and must not replace actual attempts. This check did not read the search ledger, so it reports neither an all-invocation search-only time nor incomplete-search counts from that ledger. Aggregate harness CPU/GC values are not used for attribution or utilization ratios.

A recursive comparison of the complete frozen model documents found exactly one differing leaf: `/scenario/constraints/cryptoperiod`, 5400 to 10800. Both files match their registered SHA-256 values. The schedules, traffic, initial state and operation cost profile therefore remain unchanged in this comparison.

## Exact source identities

All run paths below are relative to `target/final-research/runs/`; the named files were read only after their `result.json` reported FINISHED.

| Case directory | File | SHA-256 |
| --- | --- | --- |
| `027-core-ISS46-medium-offline-next-H1-canonical` | `result.json` | `aee3263bd99065600d2df5300d4f501138142fb59691c4c25265134bcc25d972` |
| Same | `actual.events.json` | `21e0634cf1803b882f9c3f7abee0fc4fa947e86a0f3e0e6ed5706283777d5c3f` |
| `094-sensitivity-ISS46-medium-cp10800-offline-next-H1-canonical` | `result.json` | `21e1393f93e80f7aee6f2b4ee09d8c7e42da33f4660a33912da04909b2d2e12a` |
| Same | `actual.events.json` | `5c9e688cccf4f01b489ddc7faafff8493044868b4aa01433c5e107aa123574c8` |
| `095-sensitivity-ISS46-medium-cp10800-offline-multi-H2-canonical` | `result.json` | `abba54bd5b571d81485ae0e9770125e7286cea89000e5be99435b737470db13e` |
| Same | `actual.events.json` | `53b78789fcd0fbe21acac5393f0fe7efd819292b7e0129195853f9c1ae748687` |
| `target/final-research/models/ISS46-medium.json` (workspace-relative) | Model | `528bc658aabf66e6d507d693cfa29ebe24e700cb6071b32948ca741412b39002` |
| `target/final-research/models/ISS46-medium-cp10800.json` (workspace-relative) | Model | `4bb0295408b109723a85ed5ff2ed210d214f4eb9f4bf235771d12bfe645fd318` |

The result supports a narrow interpretation: increasing CP in this registered medium ISS setting permits observed reuse and reduces lifecycle work for both completed horizons, with an equal final five-score outcome. It does not establish general safety, optimality, universal horizon equality or a favorable comparison with any unfinished run. Final independent event/identity audit remains the parent task's responsibility.

Owner/status: final_output_coverage — DONE; parent integrates only with the stated direct-export/journal boundary until the final audit confirms it.
