# X01 measured latency progress

The registered experiment and its verification are complete. After discussing the
results, the user explicitly approved the article update on 28 October 2026.

- Instruction read: `instruktsiya_magistrantu_eksperiment.docx`; preserved under
  `target/measured-delay-x01-20261028/inputs/instruction.docx`.
- User-modified article backed up as `inputs/article-before.docx`; not edited.
- Configuration audit complete: archive autonomous start is t=-2; all original
  model parameters, deadlines and horizons retained. Timers30/32/180/210s.
- Measured proof-verification compatibility fix: local commit `b2d04b8`.
- Focused Maven:13tests passed. Original sandbox attempt failed before tests
  because `.m2` was unwritable; its log remains, followed by successful run log.
- New JAR SHA256:
  `8537c0ba9e5cdd7821ad39bb2ab16034cf2fd6d16028c412eaae4417efa967cd`.
- 88attempts registered in immutable `manifest.json`; no scientific retries.
- Four controls complete: CW Next `(2,38,.12,160,0)`; CW Multi
  `(3,37,.83,1240,2)`; CAN Next same as CW Next; CAN Multi controller limit,
  final score unavailable. Event-derived control gate PASS.
- Four pilots FINISHED. CW Next2contacts/U30s, Multi1/U17s; CAN Next2,
  Multi1/U18.5s. Pilot validation and manual log review PASS; pilots excluded
  from the main-series statistics. The first snapshot check flagged only JSON
  order of `authorizedContexts`, a Java Set; only that set order is normalized.
- Main80attempts completed in registered alternating order, one JVM at a time:
  all80 FINISHED. CW20pairs:0wins/0ties/20losses for Multi by S. CAN20pairs:
  0wins/5ties/15losses. Full five-coordinate ordering:40/40 losses for Multi.

Final checks:10100decision records,264contact rows,8984delivered frames; journal
metrics, frame/contact/key deadlines, counters, serial order and420previous files
verified. Three representative pairs were selected by the registered median rule;
the timeline was rendered and visually inspected. The measured result is limited
to X01, the recorded desktop and the stated timing boundary.

The sources and an additive reproduction release were published before article
approval; that release preserves the supplied article byte-for-byte. Following
approval, the working article now includes the design, results, discussion and
conclusion; its abstract was revised last. The original formatting and all 20
references are preserved. All 15 rendered pages were inspected. A separate local
materials archive retains the supplied predecessor, updated article and evidence;
the existing public release is unchanged. See `../article-x01-update/README.md`.
