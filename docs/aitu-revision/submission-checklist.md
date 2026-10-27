# AITU submission checklist

Status: working checklist for the current revision; **not a certification of journal compliance or permission to submit**. Requirements were checked on 2026-10-28 against the [official page](https://journal.astanait.edu.kz/index.php/ojs/requirements), [author guidelines](https://journal.astanait.edu.kz/index.php/ojs/authorguidelines), and [actual DOCX template](https://sj.astanait.edu.kz/wp-content/uploads/2023/11/Scientific-Journal-of-Astana-IT-University-article-template-1.docx). Detailed evidence is in `aitu-requirements-and-sources.md`; the updated reference records are in `literature-draft.json`.

The selected supplied inputs are `C:\Users\Muhalek\Downloads\main(1).tex` and `C:\Users\Muhalek\Downloads\Contact_Aware_Research-4.pdf`. The absent `main(3).tex` is not the revision source. This checklist does not treat the supplied PDF as the final AITU layout. The subsequent text/source review inspected `article-content.json` (SHA-256 `315f7d1bb40b8a4289ec96a45a818176bfdfa547acbe47a15ba1894c15261d1d`). It confirmed title **78 characters**, abstract **263 words / 1676 characters including spaces**, **20 references** appearing first in exact order **1–20**, and five keyword phrases containing eight words. Counts concern that JSON snapshot, not a rendered Word artifact; repeat affected checks after revisions.

## Decisions already made

- [x] The parent conveyed the explicit margin override: **top 2 cm, bottom 2.5 cm, left 3 cm, right 1.5 cm**. Apply these values directly, even though the template's stored top/bottom margins are reversed. Do not ask the author to decide the same issue again.
- [x] The parent selected **Times New Roman 12 pt, single spacing** for body and author text, and figure labels at least 8 pt at their final displayed size. This deliberately differs from the template's 10 pt author runs; do not silently inherit them.
- [x] The parent reports that all **five template pages** were rendered through Microsoft Word COM and visually inspected. The bundled renderer failed because `soffice` was unavailable; no installed LibreOffice was used. This verifies inspection of the template, not the finished article.
- [x] Planned title: **When Future Contacts Improve Satellite Key and Security Association Management**. It has **78 characters including spaces**, contains no acronym, and is below the template's 96-character cap. Confirm that the final generated title is identical; evaluate its conditional wording against the final results.
- [x] Planned equation format is **editable native Word OMML**. Its relationship to the MathType requirement is unresolved below.

## Author information to complete

The supplied TEX and PDF both declare **Mukhammed Zhumali** and **262937@astanait.edu.kz**. These are verified manuscript declarations, not external verification of legal identity, employment, degree, or ownership of the email account.

| Item | Confirmed status | Remaining action |
|---|---|---|
| Publication name | Mukhammed Zhumali | Author checks exact preferred spelling in the final file and submission account. |
| Email | 262937@astanait.edu.kz | Author confirms this is the submission/correspondence address. |
| Author count/order | One author in both supplied files | Author confirms final authorship and any corresponding-author designation. |
| Academic degree | Not supplied | Obtain the factual degree/status wording; do not invent a degree. |
| Position | Not supplied | Obtain exact position or student-status wording appropriate to the journal. |
| Department | Not supplied | Obtain official English department name. |
| University/organization | Not supplied | Obtain declared affiliation; the email domain alone is insufficient. |
| Country | Not supplied | Obtain affiliation country. |
| ORCID | Not supplied | Obtain and verify the author's actual ORCID; do not manufacture an identifier. |
| Acknowledgments/funding | Not supplied | Author supplies truthful declarations, including an absence statement if appropriate. |

Leave missing information visibly unresolved in a review draft. Do not present placeholder author details as a submission-ready author block. UDC is optional; leave the article DOI for the editors rather than generating one.

## Formal points requiring a final check or editorial interpretation

| Item | Requirement or disagreement | Action before submission |
|---|---|---|
| Abstract | Website simultaneously gives 250–1900 characters including spaces and 250–280 English words; template requires a single paragraph of at least 250 words. | The reviewed JSON satisfies both numeric limits: 263 whitespace-delimited words and 1676 characters. Preserve one paragraph and recheck the actual final artifact after edits. The website conflict need not block a text satisfying both. |
| Length | Website minimum is 8 pages; template specifies 8–15. Author guidelines use 10 pages as a fee threshold. | Count rendered Word pages after final content/author details. Target 8–15. Do not treat 10 pages as a demonstrated hard maximum. Confirm applicable fee separately if submitting above 10 pages. |
| Structure | Website separates introduction, literature/problem, aim/objectives, methods, results, discussion and conclusion; template permits a combined introduction/literature section. | Use the explicit website sequence adopted for the revision. Ensure aim/objectives are visible and results are separate from their interpretation. |
| Keywords | Website requests comma separation and at most ten words; template examples use semicolons and up to ten keywords. | Use commas and a compact list. Count words as well as keyword phrases; flag any reliance on a phrase-based interpretation. |
| Reference style | APA bibliographic entries are combined with numeric first-mention citations; one template sentence says references are unnumbered while examples are numbered. | Use numbered APA entries and first-mention citation order. Individual bracketed citations avoid ambiguity about ranges. Every bibliography entry must occur in text. |
| Figures | Website minimum 300 dpi; template recommends 600 dpi and 8 pt Times New Roman labels. | Inspect all figures at their actual Word size, with readable labels, consistent units, prior in-text references and captions below. Prepare the requested high-quality JPG sources without changing scientific content. |
| Equations | Website requires MathType; template recommends MathType/legacy equation objects and warns about modern native Word equations. | Follow the explicit format decision below. Visual correctness and editability alone are not evidence of MathType compliance. |
| Submission/review files | Template requests DOCX. Guidelines describe blind peer review while also requiring author information. | Produce DOCX, then check the OJS submission instructions for a separate anonymized review file/author file. Do not remove authors from every copy or assume the author-bearing draft is the review upload. |

No editor has resolved these conflicts in this task, and no correspondence has been sent. The checklist identifies decisions to settle before submission; it does not request a fresh approval for already authorized draft preparation.

## Equations and the MathType gap

- [ ] Verify that every intended display equation is a genuine editable OMML equation, not a screenshot or flattened image; confirm subscripts, superscripts, operators, grouping, signs, numbering and surrounding punctuation after Word rendering.
- [ ] Record the final format honestly as **native editable Word OMML**, not MathType or legacy Equation Editor.
- [ ] Before claiming journal-format compliance, either convert/verify the equations with the required supported MathType workflow, or obtain explicit editorial acceptance of native OMML for this submission.
- [ ] Keep the authored source and the editable DOCX when performing any conversion. Do not assume that a visually correct PDF demonstrates equation-object compatibility.

The parent selected native OMML for the draft. This is an implemented authoring choice with a remaining submission-format question, not a reason to stop producing the reviewable draft.

## Twenty-source pool and unresolved bibliographic checks

`literature-draft.json` supplies **20 distinct records** and excludes TRAP. The reviewed `article-content.json` integrates all twenty in its body, with first appearances numbered 1–20 and no unresolved citation-key placeholders. This passes the source-text count/order check; verify that the final DOCX retains the same citations and bibliography after authoring. Do not retain uncited items merely to reach the numeric minimum.

| Sources | Age/date issue on 2026-10-28 | Required treatment |
|---|---|---|
| Mattingley et al., 2010 | Older than even ten years | Retain only with a justified foundational exception or find a suitable recent replacement that supports the actual claim. Never change its year. |
| Extended Procedures, 2020; SABR, 2019 | Older than five years | Preserve exact standard editions. The journal does not explicitly grant a standards exemption; document the interpretation/exception. |
| DTKA, 2018 | Older than five years; expired individual Internet-Draft | Label status and age explicitly. It is an important precedent, not an endorsed IETF standard. |
| Zhao, 2020 | Older than five years | Preserve as a relevant earlier preauthentication precedent; resolve age acceptability. |
| Fraire et al., 2021-01-15 | Outside a strict rolling five-year cutoff; calendar-year treatment differs | Do not silently choose the more favorable interpretation. |
| CelesTrak | Original page 2020-05-27, explicit update 2026-06-23 | Cite updated documentation accurately; do not count it as a new research paper. |
| SatNOGS and Orekit | Undated evolving documentation | Retrieval dates are not publication dates. Give the actual archived inputs/software version in methods. |

Additional metadata/claim checks:

- [x] Dowling et al. is **2025** according to the publisher's history and recommended citation, superseding the older 2024 issue/preprint note in the preliminary source register. Its ambiguous article number is omitted in the updated record.
- [x] Madoery's authors, title, year and pages were verified against IEEE-deposited DOI metadata. Detailed protection-scheme claims still require full-paper inspection; metadata verification is not a full-text review.
- [x] BERMUDA authors, editors, pages and publication date were verified through Springer. Its abstract supports the restrained mechanism summary; no complete protocol-proof review is claimed.
- [x] Detke is identified as the inspected conference-hosted paper. No unverified proceedings DOI or page range was added.
- [ ] Preserve the substantive distinction: IOTA already includes contact-dependent allowlists, revocation considerations and short-lived tokens; Detke already couples time-feasible routes to key-scope optimization. The narrower proposed distinction concerns joint lifecycle timing and a matched horizon comparison.

**Database share is not certified.** The journal's phrase about sources being cited in Scopus and Web of Science is ambiguous. No direct item-level database verification was completed. The KNU record supplies a Scopus accession for Kwon et al., but that lead neither proves the required share nor establishes Web of Science inclusion. With exactly twenty references, a 30% criterion would require at least six qualifying entries under the editor's interpretation. Obtain/save the actual records and coverage evidence, or explicitly leave the criterion unresolved.

Also check the journal's foreign-source minimum and self-citation maximum against the **final** author list and bibliography. Do not infer compliance solely from English-language titles, publisher names, DOI existence, or the present single-author header.

## Public baseline versus the new revision

The [public research release](https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/research) is a verified public **baseline artifact**. Its attached reference package is `ContactAwareSS-final-v2-reproduction-public.zip`; the release warns that automatically generated source archives instead correspond to historical tag commit `a3e7a95`.

The public release API reports ZIP SHA-256 `4f19e2451ecc2e57c901e217dde0e5da57620575e7e01373e85ef4f1ba9b2a45`, matching its downloaded checksum file. This audit checked public metadata/checksum agreement; it did not download the 498 MB ZIP, inspect its internal manifest, or rerun it.

- [ ] State separately which reported evidence belongs to that public baseline and which belongs to the **new, unpublished revision**.
- [ ] Do not imply that the existing release already contains the new manuscript, new code state, new result tables, or newly completed checks unless the corresponding payload is actually verified.
- [ ] Record identifiers for the new revision: manuscript version/date, exact source state, configuration and result-manifest paths/hashes, and commands needed to reproduce its added evidence. Identify unpublished/uncommitted material honestly where applicable.
- [ ] Prepare the new revision's reproducibility materials as a separate, reviewable package. Before submission, either publish the complete version through an authorized release action or describe its actual availability accurately. Do not silently replace or relabel the old frozen archive.
- [ ] Ensure the final data-availability paragraph links the specific attached reference artifact and states any additional revision-material location. A release-page URL alone does not establish that all current results are public.

## Final author and artifact gate

- [ ] Author reviews the complete final manuscript, author information, missing-data markers and declarations.
- [x] Source JSON text check: title 78 characters; abstract 263 words / 1676 characters; keywords five phrases / eight words; all twenty references cited in first-appearance order. This check does not certify reference age/indexing or final Word rendering.
- [ ] All twenty final references are relevant, cited and ordered consistently; DOI/year/status caveats are resolved or disclosed, and database-share claims are evidence-backed.
- [ ] Title, abstract words/characters, keyword count, required sections and final Word page count are checked from the actual deliverable.
- [ ] Every article page is rendered and inspected after the last substantive/layout edit. Template-page review is not a substitute for this step.
- [ ] Final DOCX retains editable prose, tables and equations; all figure/table/equation cross-references resolve correctly.
- [ ] Scientific statements distinguish exact finite-model results, prepared initial states, practical bounded execution, incomplete searches and computation cost. No general Multi advantage or cryptographic guarantee is inferred from isolated examples.
- [ ] Author/editor questions above are resolved before representing the package as submission-ready. Draft creation and technical validation do not themselves constitute submission.

This checklist task ran no experiments, tests or JVM processes and changed no manuscript or research artifact.

## Parent integration and final artifact verification — 2026-10-28

The final artifact checks now pass: `validation.json` and `visual-review.json` bind the actual DOCX hashes, A4/margins, 12-point Times New Roman, title/abstract/reference counts, editable tables and three native OMML equations. All 11 article pages and nine supplement pages were visually inspected after their last edits. Both LaTeX documents compile successfully. The reviewed source distinguishes IOTA/contact-dependent authorization and Detke/key-scope work from the present lifecycle/horizon comparison. Scientific claims retain incomplete outcomes and conditional boundaries.

The historical public release and this unpublished revision are explicitly separated in both documents and in `REPRODUCE.md`. A separate package manifest identifies the new source adapters, frozen executable, inputs and outputs. This closes the artifact-format and provenance gates above; it does not close author/editor questions, reference-age or indexing verification, MathType acceptance, or public deposition of the new package. The earlier checklist entries describe the independent review at that point; this paragraph records the later integration evidence.
