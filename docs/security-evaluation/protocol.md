# Security evaluation protocol — se1

Registered before executing the security experiment grid. The v2 search-scaling
inputs, journals, executable and reports remain historical artifacts. This is a
new model experiment, not a replacement of v2 or a cryptographic implementation.

## Questions and controls

* RQ1: under the same tightened cryptoperiod, which policies retain service?
* RQ2: how much intercepted payload becomes readable after one traffic-key-pair disclosure?
* RQ3: after detection, how long until use of that material stops and a complete
  protected frame is delivered using independent valid material?

The planner still optimizes served contacts, useful secure time, control CPU,
management bytes and created directional keys, in that order. Security outcomes
are evaluation metrics, never forecast objectives or hidden attack information.
Search time is measured separately and does not advance the physical clock.

## Threat model and incident semantics

One incident discloses the active bound uplink/downlink traffic-key pair of a
specified SC–GS context at a policy-independent physical timestamp. An active
KEYED pair can be disclosed even when its session is closed. There is no target
if no active, unexpired bound pair exists at that instant; this is reported,
not changed to another key or attack time. Actual key identifiers are recorded.
No persistent device access, root credential theft, packet injection, forged
notifications, or ongoing observation of future key generation is modeled.

The passive attacker records all payload transmission in the experiment from
its start, in both directions, and retains the ciphertext. A disclosed traffic
key makes all intercepted bytes encrypted with that key readable, before as well
as after disclosure. We conservatively include emitted fragments of interrupted
frames. This is an explicit abstract leakage model: possessing a key is not
assumed to require an intact authentication tag to read an already emitted
fragment. Historical traffic before the experiment is outside measurement even
if initial metadata records prior use. Rotation cannot erase captured plaintext
or ciphertext. New key generations are assumed independent.

The first authenticated detection notification reaches the affected GS over an
independent trusted terrestrial path at the configured detection timestamp.
The GS immediately gates joint traffic admission and locally retires the
specifically identified material and its bound SAs. An affected uplink frame
stops immediately. An already committed downlink frame continues emitting until
its natural end: GS knowledge cannot stop the spacecraft transmitter instantly.
The GS drops that frame instead of accepting useful data. Its full emitted
ciphertext, including the tail after ground notice, remains exposed. The occupied
frame prevents a concurrent notification exchange. This central admission state
does **not** mean the spacecraft has received the notice. All dropped/partial
frames deliver no useful payload; emitted bytes are separately journaled.
New joint frame admission requires GS participation; autonomous unsolicited
spacecraft transmissions are outside this model. Other independent generations
are unaffected by a late notice.

A separate NOTIFY_REVOCATION exchange carries the identifiers to the spacecraft
in a matching contact. Its synthetic phases cost 0.4 s elapsed, 0.02 s GS CPU,
0.02 s SC CPU, 0.24 s radio occupancy, 64 B UL and 32 B DL. It uses the common
resource and completion/ACK machinery. No contact means no remote delivery.
Only receipt of this notice is a claim of spacecraft knowledge; secure deletion
of remote storage is not modeled. All policies use the same emergency response:
notification and admissible independent-key setup are prioritized after known
revocation. FixedPeriod does not wait for T, and a successful recovery SWITCH
anchors its next ordinary period normally.

Independent authentication and confidentiality for management/key delivery,
including notification, are **additional assumptions**. Current INSTALL changes
metadata after a modeled ACK and does not implement a wrapping/root protocol.
Compromise of a traffic key therefore does not reveal replacement material in
this model. See normative-basis.md for NIST scope and subsequent bench tests.

At a shared timestamp: previously completed payload is processed first, then
disclosure captures the still-valid bound material, existing expiry gates act,
then ground notice acts, followed by the existing contact/operation ordering.
No next frame starts between events at that timestamp. A frame completing
exactly at receipt is already transmitted over [start,receipt); a frame extending
beyond receipt is interrupted on uplink or dropped while its committed downlink
emission finishes. Hidden disclosure cannot wake the controller;
only externally observable notification can create a new decision opportunity.
Prediction branches contain no future incidents or detections.

The nonce counter in this model commits only complete delivered frames. A
discarded/partial frame's key is irrevocably retired, so that nonce can never be
reused with the same key; this is not a general retransmission or crash-recovery
nonce allocator. The later cryptographic testbed must consume nonces on actual
encryption, including unsuccessful delivery.

## Frozen main matrix

Four policies: FixedPeriod, PerContact with paid just-in-time preparation,
Next H=1, Multi H=2. Shared exact solver and v2 decision-time model for Next/Multi.
No approximation is used. Budget: 100,000 nodes and 5 seconds **per decision**;
no automatic budget escalation. A bounded unfinished search stops that run and
is excluded from final paired outcome statistics.

| Fixture | Cryptoperiod (s) | FixedPeriod T (s) | Disclosure | Detection modes |
|---|---|---|---|---|
| Hand-checkable A→A, [0,20], [30,45] | 30, 60 | 20 | A at t=10 | none, undetected, delay 2 s |
| X, original A→B→A | 120, 150 | 60 | A at t=10 | none, undetected, delay 2 s |
| Y, original A→B→B | 120, 150 | 60 | A at t=10 | none, undetected, delay 2 s |
| Original ISS first three contacts, LIGHT demand | 5400, 7200 | 1800 | first station at first AOS+15 s | none, undetected, delay 2 s |

All X/Y cases start autonomously at t=-2 with the same historical key metadata.
Only the cryptoperiod constraint is tightened; frozen material deadlines cannot
be extended. The manual case starts with a pair activated at t=-5, prepared at
t=-10; 1 s/100 B frames, 100 B/s, offered 2000 B/direction/contact, minimum
100 B/direction/contact. Synthetic witness security costs apply.

For each physical fixture/cryptoperiod, run all four no-attack controls first,
then their undetected/detected partners (96 registered runs). Two predeclared
manual CP=60 sensitivities add eight runs: detection delay 15 s (notification in
the gap), and disclosure at t=18 with delay 2 s (different actual generation or
no target is legitimate). Their no-attack controls are reused. Total: 104 runs.
No T>CP cases in this block; those belong to a separate strict-periodic sensitivity.

Persist protocol SHA-256, executable SHA-256, fixture inputs, JVM environment,
budgets, factual events, compact decisions and final/partial status. Never
interpret missing, interrupted or censored outcomes as zero.

## Metrics and independent audit

Compute from emitted/complete frame facts and captured key IDs, not useful time
or forecast scores: decryptable bytes before/after disclosure; delivered bytes
remaining confidential under these assumptions; actual transmission time with
disclosed material after disclosure; bytes/time after known ground notice;
notice propagation; detection-to-first-complete-frame on independent valid
material. An already admitted independent frame finishing strictly after notice
also qualifies; completion exactly at notice precedes receipt in the event order.
Recovery absent by experiment end is right-censored. Undetected and
no-target cases have distinct non-numeric recovery statuses.

Pair with the same policy's no-attack run: lost/gained served contacts, delivered
bytes and useful time, and net extra CPU, management bytes and created keys.
Net deltas can be negative when an incident suppresses normal work. Also show
explicit notification costs. Show exposure alongside delivered and confidential
service, never ranking silence as security superiority.

An independent Python audit reconstructs the five execution metrics, all frame
leakage and recovery metrics, validates key/deadline links, checks frame/cost
uniqueness and pre-notification decision invariance, then generates tables and
publication PDF/SVG/PNG figures. COMPLETE describes a finite horizon search,
not global closed-loop policy optimality. Win/tie/loss comparisons include only
matched finished runs and distinguish service from exposure and recovery.

## Separate decision-time sensitivity

Keep v2 default rules unchanged. Reproduce the existing A-only visible schedule
where PREPARE for unseen B creates a decision time. In a separately labeled
variant add WAIT to now+PREPARE-duration, computed solely from the observation.
Apply the same rule to all branches and actual Next/Multi execution. On the tiny
example compare retaining/removing B PREPARE as a diagnostic control; this is
not permission to remove it in v2. Compare X/Y five metrics under the variant.
Optimality remains restricted to each declared finite decision-time model.
