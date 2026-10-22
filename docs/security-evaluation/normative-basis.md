# Normative basis and cryptographic boundary

Verified on 2026-10-22 against the final May 2020 **NIST SP 800-57 Part 1, Revision 5**, not a draft or a secondary summary. [NIST publication record](https://csrc.nist.gov/pubs/sp/800/57/pt1/r5/final) and [official full text](https://nvlpubs.nist.gov/nistpubs/specialpublications/nist.sp.800-57pt1r5.pdf). Page numbers below are the document's printed numbers; PDF page numbers count the cover as page 1.

## Verified basis

| Location | Relevant guidance, paraphrased | Use in this experiment |
| --- | --- | --- |
| [Section 5.3, printed pp. 34–36; PDF pp. 47–49](https://nvlpubs.nist.gov/nistpubs/specialpublications/nist.sp.800-57pt1r5.pdf#page=47) | A cryptoperiod bounds authorized key use and can limit exposure. Selection depends on consequences, environment, usage and rekeying costs. Compromise invalidates the previous authorized period. | Compare service at identical cryptoperiods. A still-unexpired disclosed key is not safe merely because its deadline has not passed. Numerical experiment periods remain model parameters. |
| [Section 5.5, especially 5.5.1(1), printed p. 49; PDF p. 62](https://nvlpubs.nist.gov/nistpubs/specialpublications/nist.sp.800-57pt1r5.pdf#page=62) | Compromised keys must stop protecting new information and be revoked. Controlled processing of previously protected information is a different use. Disclosure of an encryption key can expose both earlier and later ciphertext encrypted with it. | Count retained historical ciphertext as well as traffic after disclosure. Evaluate detection and enforcement delays explicitly; rotation does not undo past exposure. |
| [Section 8.3.5, printed pp. 113–115; PDF pp. 126–128](https://nvlpubs.nist.gov/nistpubs/specialpublications/nist.sp.800-57pt1r5.pdf#page=126) | Revocation should occur as soon as feasible. Parties sharing a symmetric key must be notified. Notices identify the key and revocation time, with a reason where appropriate; pairwise revocation includes informing the peer. | Keep local notice, local prohibition, remote notification and restored service as distinct events. Target the disclosed generation, including when a newer generation already exists. |

These passages motivate lifecycle constraints and measurement. They do not specify this simulator's contact windows, cryptoperiod values, incident-detection delays, management costs, five-objective ordering or frame scheduling. This work does not establish NIST compliance.

## What the existing implementation actually establishes

The pre-security-evaluation model implements lifecycle metadata and an execution-cost model:

- `KeyState` contains identifiers, creation/activation/deactivation dates, validity limits, installation flags and nonce progress. Its class comment explicitly calls it traffic-key metadata. It contains no cryptographic key bytes.
- `LifecycleTransitions.prepareKeys` constructs two metadata records. Unique IDs and separate direction fields establish model identity, not random entropy or cryptographic independence.
- `LifecycleTransitions.confirmInstallation` changes the installation flag after checks. `SecurityActionExecutor` publishes that transition at modeled completion; it does not serialize, encrypt, authenticate or decrypt a key-delivery message.
- `SecurityCostProfile.syntheticWitness()` is labeled `SYNTHETIC_ASSUMPTION`; its CPU, radio phases and byte counts are assumed costs, not measurements of a cipher or protocol.
- `SimulationEngine` models complete payload frames and records the actual directional pair in each `PAYLOAD_TRANSFERRED` journal event. Its counters and time intervals can support exposure accounting without implementing payload encryption.
- A source search for `javax.crypto`, `Cipher`, `KeyGenerator`, `KeyAgreement`, `SecureRandom`, `Mac.getInstance` and `Signature.getInstance` found no real cryptographic data path in `src/main` or `src/test` at this review.

Consequently, the existing installation acknowledgment alone does **not** demonstrate that a newly installed key is protected independently of the disclosed traffic key.

## Additional security assumptions to state in the experiment

The minimal disclosure experiment assumes that distinct traffic-key generations are independent secrets, and that disclosing one active pair does not reveal earlier independent generations, later independent generations, the key-generation state or a management root credential. These are assumptions, not consequences of distinct IDs.

Key delivery and incident notification require a separate authenticated management mechanism whose confidentiality/integrity protection does not depend on the disclosed traffic keys. An example implementation choice for a future testbed is a distinct uncompromised management wrapping/authentication credential, with authenticated key IDs, context, generation and acknowledgment. The simulator must charge and schedule its declared exchange, but it does not prove that mechanism secure. If new traffic material were delivered solely under the disclosed traffic key, the proposed recovery assumption would fail and subsequent material could also be exposed.

A one-time disclosure is not persistent endpoint control. The attacker can retain intercepted ciphertext and use the disclosed traffic keys; this block does not model forging, injection, replay, jamming, malicious state changes, root compromise or repeated exfiltration. Omitting those capabilities is a scope restriction, not evidence that disclosure cannot enable them.

Before observable notification, the controller cannot know the hidden incident. Continued use during that interval is measured as exposure; it is not excused as safe by the absence of a notice. Receipt, local prohibition and remote propagation must be represented honestly: a ground-side gate preventing joint service is not proof that an out-of-contact spacecraft has learned the revocation.

## Required checks on a later cryptographic testbed

1. Implement and verify the selected authenticated-encryption and key-establishment/transport protocols, including algorithm parameters, directional separation, identity/context binding and independent key generation or derivation.
2. Demonstrate that knowledge of a traffic key cannot decrypt the recovery exchange or authenticate a replacement/notification. Exercise wrong credentials, altered IDs, replayed notices, replayed acknowledgments and delayed generation-specific messages.
3. Verify nonce uniqueness across reset, crash/recovery, SA reopening, retransmission and counter persistence; check replay rejection and the exact payload-frame format.
4. Measure CPU, elapsed time, radio bytes/occupancy and energy on representative SC/GS hardware, including key confirmation, notification, interruptions and retries. Replace synthetic profiles only with provenance-tagged measurements.
5. Test endpoint-local revocation and in-flight frame behavior under delay, loss, asymmetric knowledge, contact loss and restart; validate that independent new material is not disabled by a late old-key notice.
6. Validate key storage, separation of management and traffic credentials, access controls and erasure. Metadata retirement in this simulator is not physical zeroization.

The resulting simulation evaluates consequences of a **stipulated key disclosure** under explicit assumptions. It measures neither cryptanalytic strength nor the probability of a real compromise.
