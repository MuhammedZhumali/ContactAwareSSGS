# Directional-key lower bound for the base core

Checked 2026-10-25. Scope: the registered base core, CP=5400 s, initially empty key map, positive service minima in both directions, and all consecutive same-context contact gaps strictly greater than CP. This note is the only change. No JVM, experiment or active checkpoint was used.

**Conclusion: the model supports `N_new >= 2 * N_served` for a complete legal execution of these base inputs.** More precise wording than “a fresh pair during each contact” is: **each served contact requires at least two distinct directional keys that cannot contribute to another contact; all such keys must have been generated during the execution.** Material may have been prepared or installed earlier while still unactivated. The proof does not require both delivered directions to use one original PREPARE pair throughout a contact.

## Verified input premises

The existing input-only descriptor (`docs/final-research/workload-descriptors.md:13-33`) states empty initial key maps and CP5400 for all nine base mission/load models. It counts strict same-context recurrence gaps: ISS 29, NOAA 22, HST 35, with no gap at or below CP. Context identity is `(satelliteId, groundStationId)`.

This check independently loaded one registered model per mission, verified its SHA-256 against `protocol.json`, checked empty initial keys, CP5400 and minima 1000 B in **each** direction for every contact, and recomputed all same-context gaps using integer nanoseconds:

| Model | Contacts | Recurrence gaps | Minimum gap s |
| --- | ---: | ---: | ---: |
| `target/final-research/models/ISS46-low.json` | 46 | 29 | 5453.85772705 |
| `target/final-research/models/NOAA38-low.json` | 38 | 22 | 5429.92767334 |
| `target/final-research/models/HST48-low.json` | 48 | 35 | 5528.383483887 |

These agree with the existing descriptor. Empty-key/CP fields are at ISS lines 639/642, NOAA 603/606 and HST 495/498. The other load levels are covered by the existing nine-model descriptor, not a new independent read here.

## Proof from the actual lifecycle and accounting rules

1. **Serving a contact requires actual directional delivery.** `MetricsCollector.java:29-46` computes served status from delivered UL/DL bytes against both minima. Both minima are positive in these inputs. The engine admits a complete frame only within the contact and both bound-key deadlines (`SimulationEngine.java:664-690`), rechecks protection on completion and only then credits payload (`:701-719`). Select one key that delivered an uplink frame and one that delivered a downlink frame for each served contact.

2. **The selected keys cannot cross contexts or directions.** `SecurityConstraintChecker.java:66-73,129-137` requires the exact context, direction, ACTIVE status, installation and unexpired interval. `SecurityState.java:34-43` checks SA/key scope, requires an active bound key and forbids one key bound to multiple SAs. `KeyPairIds.java:4-8` requires different directional IDs. `LifecycleTransitions.java:46-56` creates immutable context/direction-specific key records and rejects reused IDs, including destroyed IDs. Thus contacts at different stations cannot share a selected key, and uplink/downlink cannot use the same key identity.

3. **Nor can the same directional key serve a later contact in the same context.** Let `e` be the earlier contact end, `s` the later start, and `a` the key's activation time. Actual use implies `a <= e`. `KeyState.hardDeadline:49-54` gives `d <= a + 5400`. The consecutive same-context gap premise implies `s > e + 5400` also for any nonconsecutive later occurrence. Therefore `d <= a + 5400 <= e + 5400 < s`. Protection after `s` is rejected by `SecurityConstraintChecker.java:135-137`.

4. **Reopening cannot restart the clock.** Activation requires PRE_ACTIVE (`SecurityConstraintChecker.java:37-42,129-132`); an ACTIVE/deactivated key cannot be activated again. `KeyState.java:62-67` freezes the activation-limited deadline in the key record. Binding/opening operates on SAs/context without changing key activation or deadline (`LifecycleTransitions.java:69-81`; `SecurityAssociation.java:26-36`). OPEN/REUSE only starts the context, while SWITCH requires admissible PRE_ACTIVE material (`SecurityActionExecutor.java:63-79`). Creating a new SA, unbinding/rebinding or reopening a session therefore cannot extend the same key's lifetime.

5. **These distinct keys are included in `N_new`.** The initial key maps are empty. Successful PREPARE creates the two records (`SecurityActionExecutor.java:58`; `LifecycleTransitions.java:46-56`). After this state transition, the engine logs two created keys (`SimulationEngine.java:449-458`), which `MetricsCollector.java:18-21` sums. No selected key can exist without such creation in this execution. The two selected keys for each served contact are disjoint from every other contact's selection, proving at least `2*N_served` generated directional keys.

Staged PRE_ACTIVE material is fully compatible with the proof: it was already generated and counted, can activate later, and cannot have carried earlier payload while PRE_ACTIVE. A successful but abandoned PREPARE adds unused counted keys. A failed PREPARE that creates no key supplies no usable material; it cannot reduce the required count. Mixing material from different generated pairs, or creating additional pairs during a contact, cannot reduce the disjoint-directional-key count.

All nine inspected source files (KeyState, SecurityConstraintChecker, LifecycleTransitions, SecurityState, KeyPairIds, SecurityAssociation, SecurityActionExecutor, SimulationEngine and MetricsCollector) match their frozen `final-v2-sources.json` hashes.

## Optional observation: direct exports, audit still pending

`target/final-research/completed-core-exports.json` explicitly labels its scope “Completed core direct exports only; independent whole-matrix event audit pending. No incomplete prefix ranked.” In that saved snapshot, all 27 PerContact/Seed H1/Seed H2 base-core rows are FINISHED and satisfy the equality below at all three loads:

| Mission | Policies and loads | Exported served contacts | Exported new directional keys |
| --- | --- | ---: | ---: |
| ISS46 | PerContact, Seed H1, Seed H2; low/medium/high | 46 | 92 |
| NOAA38 | PerContact, Seed H1, Seed H2; low/medium/high | 38 | 76 |
| HST48 | PerContact, Seed H1, Seed H2; low/medium/high | 48 | 96 |

Snapshot SHA-256: `f4d5b069af7f56b2477d62ecc4e04ddf42d056c19d8afe1ebafe602b96134c49`. This is a direct-export equality check, **not independent journal validation** of those results. After the final audit confirms them, it is sound to say these cases attain the directional-key-count lower bound at their served count. The export check did not rank any incomplete run.

## Limits for manuscript use

Do not extend this deduction to CP10800 sensitivity, security/disclosure cases, nonempty initial states, or a different geometry. The proof is about generated key count only. It establishes no CPU or management-byte lower bound and no useful-time or whole-policy optimum. Earlier preparation/installation can still alter useful transmission, resource scheduling or completion. Matching the fifth component's lower bound does not settle the preceding lexicographic objectives or show that extra horizon is useless.

Suggested bounded claim: “For the registered base core, empty initial key stores and same-context gaps exceeding the 5400-s cryptoperiod imply at least two newly generated directional keys per served contact. Staging unactivated material does not evade this count. The lower bound concerns key generation only; it does not establish useful-time or management-cost optimality.”

Owner/status: final_output_coverage — DONE; parent may integrate the theoretical bound now and the export-equality observation only with its stated audit boundary.
