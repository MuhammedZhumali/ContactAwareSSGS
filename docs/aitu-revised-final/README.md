# AITU article with the current measured-delay evidence

The author confirmed `docs/aitu-revision-v2/article.docx` as the source when the
named `article(2).docx` was unavailable. That file and the original supplement are
unchanged; working backups are under `target/aitu-current-editorial/inputs/`.

Deliverables:

- `article_aitu_revised_final.docx` — 14 pages in the verified Word rendering.
- `supplement_aitu_revised_final.docx` — 16 pages, with historical evidence clearly
  separated from the current campaign.
- `figures/fig3_measured_execution.png` — 600 dpi, and its editable-text SVG peer.
- `../../scripts/plot_aitu_current_timelines.py` — plotting code reading the frozen
  journals and tables without executing the controller.

The main sequence is now offline feasibility, discovery, current measured-delay
execution, and practical limitations. The earlier one-second measured results are
historical material in the supplement and original archives. Supplement Fig. S1
contains only the original offline budget data; it does not mix in the current
150,000-node/30-second observations. Figure numbering is conceptual mechanism (1),
horizon conditions (2), actual delayed executions (3), disclosure consequences (4).

Fig. 3 uses completed main pairs grouped by tie rule and service outcome. Within
each group, selection minimizes distance to median Delta U, then pair ID. This
gives CW-01, CAN-13 and CAN-01. It shows charged intervals from -2 s, actual OPEN,
automatic STOP and expiry, and complete delivered frames in distinct lanes. No
predicted PREPARE/INSTALL is presented as execution. Frame size is 100 bytes;
400 bytes per direction is the service threshold. Very short decision intervals
may be unresolved at print resolution; their widths are never inflated.

The separate 36-run disclosure series is explicitly labeled **offline** in the
abstract, methods, results and Fig. 4 caption. Its published Stage 4 protocol uses
5,000 nodes, one second per search and two seconds per action selection;
computation is not charged to contact windows. These are not the budgets or
timing mode of the measured-delay campaign. X01 offers `Long.MAX_VALUE`
(9,223,372,036,854,775,807 bytes) per direction in each of its three contacts;
the 400-byte directional service threshold is a separate quantity.

The current campaign remains 88 attempts: four controls, four pilots, 80 main.
All main runs finish. CW has 0/0/20 Multi wins/ties/losses by S; CAN has 0/5/15.
All 40 lexicographic comparisons favor Next, while operation CPU (0.12 s),
management bytes (160) and new keys (0) tie. Conclusions are limited to X01, the
recorded controller/platform, budgets and measurement boundary. No alternative
solver experiment or attribution exclusively to DFS is implied.

The measured interval starts before observation construction and ends after
continuation-audit construction. The pseudocode covers proof checks, ordinary
selection, advancement during delay, fresh admission and loss of an old certificate
after action shifting. Strict INSTALL completion requires PREPARE start <14 s for
the six-second PREPARE/INSTALL chain before C1 closes at 20 s. This is a necessary
bound, not a guarantee after later decision delays.

Funding and competing-interest declarations were restored exactly as confirmed by
the author. Human/animal-study exclusion remains. The release link is an active,
compact hyperlink. The abstract was updated last (274 words, 1,765 characters).
First-person wording has been removed from the main article.
A4 geometry, original journal fields, Times New Roman 12, the three editable
equations and all 20 references remain. Short Table 2 is entirely on page 4.

## Reconstructing the figures and editorial changes

From the complete reproduction workspace, with Python, matplotlib, numpy, Pillow
and lxml installed:

```powershell
python -X utf8 scripts/plot_aitu_current_timelines.py
python -X utf8 scripts/revise_aitu_current_article.py
```

The editorial script uses the retained confirmed source under
`target/aitu-current-editorial/inputs/`; it writes separate final documents and
does not overwrite that source. `figures/provenance.json` lists plot inputs and
hashes. Internal Word/PDF/PNG renders are QA artifacts, not final deliverables.
`validation.json` records the final checks. No Java execution, Maven test series,
new experiment or modification of the published archives was performed.
The preceding editorial documents and validation record are retained under
`target/aitu-mode-demand-clarification/before/`. The clarification changes only
text in the two DOCX packages; all tables, figures, equations and other package
parts remain identical to the preceding editorial version.

## Remaining formal questions

- No installed, verified MathType conversion path was found in the available
  program locations/registration. Equations remain native editable OMML, not
  images or objects falsely labelled as MathType. Confirm acceptance or perform
  a genuine conversion in an appropriately equipped environment.
- Mattingley et al. (2010) is retained for its relevant primary treatment of
  receding-horizon control. No verified modern replacement was established in
  this editing task; an age exception needs editorial agreement.
- The earlier AITU audit did not certify the required Scopus/Web of Science
  share or the journal's source-category interpretation. This editorial task does
  not close that separate bibliographic verification requirement.

The journal DOI field is explicitly reserved for publication; no DOI is invented
for the article or reproduction package. No complete journal-conformity or
submission claim is made.
