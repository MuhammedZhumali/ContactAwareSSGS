# X01 measured latency progress

The registered experiment and its verification are complete. Article editing
remains gated on user approval of the results.

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

Results and proposed wording are ready for review. Publication of the sources and
an additive reproduction release was subsequently authorized. The author's current
article is preserved byte-for-byte; integrating these results into its text remains
pending approval. The release distinguishes this supplied article from the older
published manuscript and the new experimental addendum.
