# Time-model sensitivity protocol

Registered before running the timing experiment on 22 October 2026. This is a separate synthetic model variant. The frozen v2 inputs, rules, executable and evidence are not replaced.

## Question and intervention

Does preparation for a station absent from the visible schedule improve service only because its completion creates a decision boundary? The control preserves v2 event boundaries. The experimental `PREPARE_DURATION_WAIT` rule additionally permits a cost-free WAIT until `observation.at + observation.costs.PREPARE.duration`, when that instant is inside the fixed visible horizon. It uses no hidden contact or incident information, consumes no identifier, creates no key and occupies no resource. An earlier physical event or existing wakeup still interrupts the wait. The rule is recomputed at each decision boundary in both forecast branches and actual execution.

The rule is finite because PREPARE has positive integer-nanosecond duration, the horizon is finite, and the existing finite operation/frame/event transitions remain in force. This does not permit arbitrary real-valued starts or establish optimality over continuous time. Search accelerations still bound an unrestricted optimistic physical continuation; added decision times do not invalidate those relaxations. A complete independent small enumeration checks the accelerated result.

No PREPARE candidate is removed from either normal model. A separate tiny ablation removes only preparation for B, to test necessity in the specific counterexample. This is not a general dominance proof and does not authorize pruning such actions in the production generator.

## Registered cases

1. Four complete independent exhaustive searches of the established timer counterexample: v2/new timing crossed with B PREPARE retained/removed. A has an installed PRE_ACTIVE pair, B is authorized but has no visible contact. A windows are [0,9] and [12,14]; one 1-second uplink frame serves each. PREPARE=4 seconds, INSTALL=3, SWITCH=1, OPEN/REUSE=1; all operation time is synthetic ground CPU, management bytes are zero. Cryptoperiod=9 seconds. Historical preparation and installation before t=0 are shared sunk work and excluded from incremental scores.
2. Prepared and autonomous original X/Y, Next H=1 and Multi H=2, crossed with the two timing models: 16 actual controller runs. No attack is introduced here. Record all five final metrics, executed actions, per-decision completion status, explored nodes and search time. Shared warmup is included once. Keep original fixture traffic, cryptoperiod, cost profile and contacts.

The tiny enumeration has no budget and no memoization or optimistic pruning; only equivalent WAIT transitions are coalesced. X/Y use the same accelerated exact planner, 100,000 nodes and 10 seconds per decision. No retry with a changed budget is part of this protocol. Any incomplete decision stops actual execution and is reported separately without final metrics. Search wall time does not advance physical model time.

## Checks and interpretation

- Independently calculate the counterexample's frame count, CPU and created keys and compare all five metrics.
- Compare the complete canonical winning plans with B retained/removed under the new rule, not merely their service count.
- Confirm default and explicit v2 constructors produce identical winner, score and node count.
- Identical Next observations for X/Y produce identical extra wakeups; no hidden C3 enters the rule.
- Replay the new counterexample through the common actual controller.
- Report whether the main X/Y service contrast survives, changes or cannot be certified within budget. A changed canonical plan under an expanded timing space is not a regression in v2.

Run after compiling:

```powershell
& 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe' -Xmx1024m -cp target/contact-aware-contacts.jar org.contactaware.security.simulation.TimeModelSensitivityMain target/security-time-model
```

`TimeModelSensitivityMain` refuses a nonempty output directory and writes its protocol before any enumeration or controller run. Actual journals use the existing four-policy export format and can be checked independently by `scripts/verify_policy_experiments.py`.
