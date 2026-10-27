# ContactAwareSS

Java 21 research simulator for joint cryptographic-key and Security Association
lifecycle planning across predicted spacecraft–ground-station contacts.

Repository: https://github.com/MuhammedZhumali/ContactAwareSSGS

The research question is when a longer visible contact horizon changes feasible
service, and when a bounded controller can find and execute that improvement.
Next and Multi share actions, constraints, costs and tie-breaking; their visible
horizons differ. FixedPeriod and PerContact use the same executor.

## Current article and evidence

The current manuscript is **AITU revision 2, wording corrected on 28 October 2026**.

- [Article DOCX](docs/aitu-revision-v2/article.docx) and [LaTeX](docs/aitu-revision-v2/article-tex/main.tex)
- [Supplement DOCX](docs/aitu-revision-v2/supplement.docx) and [LaTeX](docs/aitu-revision-v2/article-tex/supplement.tex)
- [Results and limitations](docs/aitu-revision-v2/report.md)
- [Reproduction instructions](docs/aitu-revision-v2/REPRODUCE.md)
- [Protocol](docs/aitu-revision-v2/protocol.md), [tables](docs/aitu-revision-v2/tables/) and [submission checklist](docs/aitu-revision-v2/submission-checklist.md)

Six additional practical root searches produced two COMPLETE and four INCOMPLETE
results. Four conditional full controller attempts produced three FINISHED results
and one CONTROLLER_TIME_LIMIT. These are separate from the earlier campaigns.

At 150,000 nodes and a 30-second search limit, the completed offline CONTINUE_WAIT
pair serves three contacts with Multi versus two with Next. Useful time is 37 versus
38 seconds; Multi spends more operation resources. Observed controller elapsed time
is 32.426 versus 2.537 seconds and is not charged to contact time. Whether this new
benefit survives charging computation time remains untested. CANONICAL Multi's
full-run comparison remains unresolved. General Multi superiority, onboard
feasibility and reduced compromise probability are not established.

## Build and tests

Install JDK21. The included Maven wrapper selects Maven; no global Maven installation
is required. From PowerShell in the repository root:

```powershell
.\mvnw.cmd verify
java -jar target/contact-aware-contacts.jar --help
```

Some contact-data tests require archived inputs under
`data/sources/earth-20261011`, `data/sources/celestrak-iss-20261011` and
`data/runs/iss-20261010`. These local datasets are intentionally excluded from Git.
Restore the matching data before running the complete suite; a source clone alone
is not the full experimental artifact.

In the research workspace, `target/` also contains frozen JARs, saved observations,
raw journals and the retained document template. Do not run `mvn clean` or delete
that whole directory before archiving/restoring those materials. Use a separate
checkout for a genuinely clean build. Test totals in historical reports describe
their recorded versions, not a fresh test run during repository cleanup.

## Reproduce the article

The source repository contains code, tests, current documents, compact results and
historical specifications. Large raw evidence and frozen executables belong in a
separate release asset. Extract the matching reproduction ZIP into its own directory
and follow its `docs/aitu-revision-v2/REPRODUCE.md`; it includes the necessary
`target/` inputs for table reconstruction and adapter rebuilds.

The existing [research release](https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/research)
contains the historical final-v2 package. It does **not** contain the latest AITU
experiments. The current AITU reproduction ZIP is prepared locally but has not yet
been publicly deposited. Its public URL/DOI remains unset.

[Publication steps and exact archive identity](docs/github-publication.md) distinguish
committing sources from uploading the full reproduction artifact.

## Project layout

| Path | Purpose |
|---|---|
| `src/main/java/` | Contact-data preparation, lifecycle model, executor, simulator and planners |
| `src/test/java/` | Unit and integration checks |
| `scripts/` | Experiment adapters, journal analysis, plotting, document generation and packaging |
| `docs/aitu-revision-v2/` | Current article, supplement, protocol and compact evidence |
| `docs/aitu-revision/` | Prior AITU inputs and evidence; also required by the current generators |
| Other `docs/` folders | Historical specifications, audits and results, kept with their original scope |
| `data/`, `target/`, `.tools/` | Local data, raw runs, build products and tools; excluded from Git |

Core contracts: [security model](docs/security-model-stage-1.md),
[cost model](docs/formal-cost-model-and-horizon/main.tex),
[planner foundations](docs/planner-foundations.md), and
[baseline policies](docs/baseline-policies.md).

Historical checkpoints: [final registered campaign](docs/final-research/report.md),
[PC2](docs/practical-controller/pc2/README.md),
[R1](RESEARCH_VALIDATION_RESULTS.md), and
[earlier security synthesis](docs/research-synthesis/report.md).

Historical evidence is retained rather than pooled with the current denominators.
Operation costs are synthetic model parameters. Native Word equations are OMML,
not MathType; outstanding author and journal requirements remain explicit in the
submission checklist.
