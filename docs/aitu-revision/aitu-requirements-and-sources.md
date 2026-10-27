# AITU requirements and source verification

Checked on 2026-10-28. This is a requirements/source audit, not certification of a submission. No manuscript, scientific results, experiments, or JVM processes were changed or run.

## Evidence and manuscript boundary

- Official [requirements](https://journal.astanait.edu.kz/index.php/ojs/requirements), [author guidelines](https://journal.astanait.edu.kz/index.php/ojs/authorguidelines), and the actual [DOCX template](https://sj.astanait.edu.kz/wp-content/uploads/2023/11/Scientific-Journal-of-Astana-IT-University-article-template-1.docx) were read. Downloads and extracted evidence are under `target/aitu-revision/reference-materials/`.
- The template downloaded successfully: 47,215 bytes; SHA-256 `d30c35b2d97c04dd69453d517d701cd4d8b78c7963e57001b6f31d3803a7587d`. `aitu-template-paragraphs.txt` uses document paragraph numbers, **not page numbers**. OOXML was inspected; no rendered page-count or layout certification was performed.
- `C:\Users\Muhalek\Downloads\main(3).tex` was absent. A recursive filename search under Downloads also returned no match. Directly available files were `main.tex` and `main(1).tex`; neither was substituted for the requested current manuscript.
- Initially `docs/paper-verified-20261027/main.tex` was used **only for bibliography** (lines 2127–2228). The parent subsequently conveyed the user's instruction to use the supplied files already present. The selected inputs are therefore **`C:\Users\Muhalek\Downloads\main(1).tex`** and **`C:\Users\Muhalek\Downloads\Contact_Aware_Research-4.pdf`**, not the absent `main(3).tex`.
- Selected TEX SHA-256: `bffa93d0315b3c02fc893ce090cc70ab4d394a9a6f437206f8e70e3985746068`. Selected PDF SHA-256: `f12fd809d8963f2d546580ea52d112cb1ba3d69c979d4118d5efdea986bbf630`. The PDF has **16 pages**. Title, author, email and abstract agree by text extraction; complete source/PDF equivalence and rendered layout were not audited.
- Selected-source measurements: displayed title **95 characters**, but contains **LEO**, conflicting with the no-abbreviation rule. Abstract **201 whitespace-delimited words**, **1545 characters including normalized spaces**: below the English word minimum while inside the character interval. The TEX has **12 references** and nine major sections; AITU restructuring remains necessary. Existing IEEE PDF length does not predict final Word page count. Evidence: `selected-input-summary.json`.

## Official website constraints

Source: [requirements, sections 1–13](https://journal.astanait.edu.kz/index.php/ojs/requirements).

| Area | Requirement |
|---|---|
| Language/authors | English; at most 6 authors |
| Text | A4; Times New Roman 12 pt; single spacing; paragraph spacing 0 pt |
| Margins | Top 2 cm; bottom 2.5; left 3; right 1.5 |
| Length | At least 8 pages |
| Title | Content-specific; no abbreviations |
| Abstract | General rule: 250–1900 characters including spaces; English rule: 250–280 words |
| Keywords | At most 10 words; comma-separated; no context-dependent abbreviations |
| Structure | Introduction; literature/problem; aim/objectives; methods; results; discussion; conclusion; acknowledgments; references; author information |
| References | At least 20; APA entries; numbered citations in first-mention order; every entry cited; ranges limited to three sources |
| Age | Fundamental research ≤10 years; applied sources ≤5 years |
| Shares | Scopus/Web of Science ≥30%; foreign ≥60%; self-citations ≤20% |
| Other references | Cyrillic transliteration with English title; foreign equivalents for national/interstate standards |
| Math | MathType; consecutive numbers right-aligned; references `(1)`; punctuation follows sentence |
| Figures/tables | Figures ≥300 dpi, inline, cited beforehand; consistent labels; nonempty table headers |
| Authors | Full name, degree, department, university, email, ORCID |
| Identifiers | UDC optional; DOI assigned by editors |

## Template additions and disagreements

Source: downloaded DOCX and paragraph identifiers in `aitu-template-paragraphs.txt`.

| Evidence | Additional rule or conflict |
|---|---|
| P18 | Title ≤96 characters; no symbols/math in title or abstract |
| P20 | Single-paragraph abstract, ≥250 words; background–methods–results–conclusions logic without headings |
| P21 | Keywords use semicolons, conflicting with website commas |
| P23, P27–31 | Combined introduction/literature and flexible body versus website's separate sections |
| P29–30 | Justified text, 1.25 cm first-line indent; 8–15 pages |
| P34, P42 | Recommended ≥600 dpi; figure labels 8 pt Times New Roman |
| P49–54 | MathType preferred; legacy Word equations allowed; LaTeX conversion must be checked; equation font sizes 12/7/5/18/12 pt |
| P68–69, P87 | Unnumbered-reference sentence conflicts with numbered-list instruction/examples |
| P30, P60–62 | Discourages future-work statements but later invites research gaps/recommendations |
| P3–14 | Adds author position and organization/country |
| P107–108 | DOCX required, including LaTeX conversion; separate high-quality JPG figures |
| OOXML `sectPr` | Actual top/bottom: 1418/1134 twips, approximately 2.50/2.00 cm; opposite to written rule |
| OOXML author runs | Names/affiliations 10 pt; body/title 12 pt; one-column section despite generic two-column wording |

### Working decisions for a revision

These are proposed conservative implementation decisions, not new journal rules:

1. Draft 250–280 English words, and independently measure characters including spaces. Try to satisfy both abstract limits naturally; otherwise record the conflict for editorial clarification. Do not silently assert that one rule supersedes the other.
2. Use the website's explicit seven-section sequence, with acknowledgments, references and author information afterward. Keep the detailed literature comparison visible.
3. Target 8–15 Word pages. The [author guidelines](https://journal.astanait.edu.kz/index.php/ojs/authorguidelines) discuss additional charges above 10 pages; this is a billing threshold, not a demonstrated 10-page hard maximum. Submission checklist accepts Word-compatible formats, while the specific template requests DOCX. Deliver DOCX.
4. Apply the written margin values explicitly; do not blindly inherit the reversed DOCX vertical margins. Preserve author-block formatting deliberately and record its difference from the general font-size statement.
5. Prefer individual bracketed citations and a numbered APA bibliography to avoid the range/numbering contradictions. Use comma-separated keywords under the website rule; record that the template differs.
6. Use 600 dpi raster exports where appropriate and inspect final-size readability. Plain-text equation images or unexamined OMML conversion should not be called MathType compliance. Equations need an editable final format and visual verification.
7. Do not email editors or submit anything as part of this audit. Any unresolved formal interpretation can be listed in a submission note for the author.

## Author information status

After the parent's update, the supplied TEX and PDF confirm the following **manuscript-declared** name and email. This is not external identity verification. The other fields are absent; a GitHub account or email domain does not establish an affiliation or degree.

| Field | Status/action |
|---|---|
| Publication name and author order | Both supplied files list one author: **Mukhammed Zhumali** |
| Academic degree | Missing; do not convert student status into an earned degree |
| Position | Missing current confirmation |
| Department | Missing exact official name |
| University/organization | Missing current affiliation declaration |
| Country | Missing current affiliation declaration |
| Email | Both supplied files give **262937@astanait.edu.kz** |
| ORCID | Missing verified identifier; do not invent or infer one |
| Corresponding author | Not identified in allowed current evidence |
| Additional authors/acknowledgments/funding | Not inferred; obtain actual contribution and funding statements |

The main agent should merge any additional author facts directly supplied by the user in the parent chat, marking their provenance. Do not fill missing fields by guessing from the email domain.

## Bibliography audit of the permitted fallback

Both the fallback and now-authorized `Downloads/main(1).tex` contain **12** `bibitem` entries: `sdls`, `ep`, `skm`, `iota`, `bp`, `bpsec`, `sabr`, `detke`, `rhc`, `celestrak`, `satnogs`, `orekit`. The selected TEX is eight below the minimum. Nothing is inferred about the missing `main(3).tex`.

| Existing item | Verification and action |
|---|---|
| SDLS 355.0-B-2 (2022), Extended Procedures 355.1-B-1 (2020), Symmetric Key Management 354.0-M-1 (2023) | Official CCSDS cover/title metadata verified: [SDLS](https://ccsds.org/Pubs/355x0b2.pdf), [EP](https://ccsds.org/Pubs/355x1b1.pdf), [SKM](https://ccsds.org/Pubs/354x0m1.pdf). This is not a check that each remains the newest edition. EP is older than five years. Do not misdate standards or substitute a different specification merely to improve recency. |
| Bernal et al., IOTA | Publisher confirms 2026, 86(1), 1–39, DOI `10.32604/cmc.2025.069746`. The same [page](https://www.techscience.com/cmc/v86n1/64479/html) gives issue publication 2025-11-10; distinguish bibliographic volume year from online date. |
| Detke et al. | Author page and conference-hosted PDF verified; 2026 WiSEE/STINT. No DOI or indexing status established. Preserve the conference-hosted-paper qualification rather than inventing final proceedings pagination. |
| Mattingley et al. (2010) | Sixteen years old in 2026: outside even the fundamental ten-year rule. If historically necessary, explicitly flag an exception request or find a genuinely suitable recent source; do not relabel it as recent. |
| SABR (2019) | Seven years old: outside a five-year applied cutoff, though within ten years. Classification/standards exception remains unresolved. |
| BPv7 and BPSec (2022) | Official RFC title/author/year metadata verified during the expanded check. Claim-specific normative sections still require precise citation when integrating. Not counted as indexed research articles. |
| CelesTrak, SatNOGS, Orekit webpages | Operational documentation; access date is not publication year. Keep for provenance/software facts, not as substitutes for nearest peer-reviewed research. |

For a 2026-10-28 rolling cutoff, ten/five years means 2016-10-28/2021-10-28; a calendar-year reading differs near the boundaries. The journal does not specify which interpretation or a standards exception. Classify each entry honestly and record exceptions. If there are exactly 20 references, the numeric targets would be at least six database-qualified sources, at least twelve foreign sources, and at most four self-citations.

**Indexing compliance is unverified.** The requirement uses the wording “cited in Scopus and Web of Science,” which is ambiguous between indexing and citations, and between intersection and union. No database record was directly verified. The expanded search found a Scopus accession/link supplied by KNU's institutional record for Kwon et al. (`105020927427`), which is a useful lead but not verification of the required share or Web of Science inclusion. An IEEE/Springer publisher page, DOI, Crossref record, DBLP listing, journal indexing badge, or search snippet does not certify the required share. Unknown is not evidence of non-indexing. Do not report the 30% threshold as passed.

## Nearest primary works and the distinction the paper must make

This is a targeted novelty-risk check, not an exhaustive systematic review or proof of novelty. Claims below are bounded by the stated inspection depth. The candidate distinction is a policy choosing security-context/key lifecycle actions over ordered future spacecraft–ground-station contacts under fixed safety constraints; implementation and experimental support must be checked separately by the main agent.

| Work and primary evidence | Established overlap | Required distinction/action |
|---|---|---|
| **Detke et al. (2026), Security-Aware Route Selection in DTNs: Towards Optimal Key Provisioning.** [Author metadata](https://juanfraire.space/publications/2026-security-aware-route-selection-in-dtns/), [conference PDF](https://stintworkshops.org/papers/stint2026-19-detke.pdf). Downloaded full text; inspected problem/model/results/conclusion. | CGR contact-plan routes induce BPSec key scopes; MILP minimizes activated scopes subject to a target connectivity ratio. The conclusion identifies key scheduling over contact plans as future work. | Strongest provisioning-optimization precedent. Distinguish route/key-scope selection and connectivity from sequential lifecycle timing, cryptoperiod/authorization constraints, and per-contact payload opportunity. Do not claim the first use of contact prediction in security optimization. A paper-specific comparison is required; a citation alone is insufficient. |
| **Bernal, Ledesma, Lamo and Bermejo (2026), IOTA-Based Authentication for IoT Devices in Satellite Networks.** [Publisher full text](https://www.techscience.com/cmc/v86n1/64479/html), §§3.1–3.3, prevalidation subsection, §4. | Ground-side DID creation outside the satellite channel; onboard allowlists refreshed in contact windows; resolution/deactivation/reactivation measurements and orbital revisit comparison. It also discusses local authorization withdrawal and short-lived capability tokens. | Do not reduce it to generic identity management. Contrast DID/credential authorization and ledger latency with the present security-association/key-policy state and multi-contact action objective. Separate measured Shimmer operations, orbital simulation, and proposed local overlays. The paper's real ledger measurements do not validate this simulator's costs. |
| **Burleigh et al. (2018), DTKA.** [IETF draft full text](https://datatracker.ietf.org/doc/html/draft-burleigh-dtnwg-dtka-02), §§1.1, 3.4, 4.4–4.7; [status](https://datatracker.ietf.org/doc/draft-burleigh-dtnwg-dtka/). | Planned contacts motivate proactive authenticated public-key distribution and scheduled effective times, rollover and revocation. | Prior schedule-aware key administration already exists. Compare protocol/distribution architecture against the present policy optimization. Clearly label expired individual Internet-Draft/work in progress; it is not an endorsed IETF standard, and recency is a separate problem. |
| **Fuchs, Walter and Tschorsch (2025), BERMUDA.** [Author-deposited abstract/metadata](https://eprint.iacr.org/2025/806), DOI `10.1007/978-3-031-92886-4_15`; [publisher volume](https://link.springer.com/book/10.1007/978-3-031-92886-4). Abstract and publication metadata checked; full model not audited. | BPSec unicast key management combines hierarchical PKI, ECDH and adapted NOVOMODO revocation; implemented DTN chat example. | Use for the mechanics/security integration boundary; inspect full text before asserting the absence of contact-aware decisions. Do not call the current lifecycle simulator a complete cryptographic key-distribution implementation. |
| **TRAP (2025), Timely Revocable Authentication Protocol for Scheduled Handovers in Space-Sea Integrated Networks.** [IEEE record](https://ieeexplore.ieee.org/document/11148861/), DOI `10.1109/ICCC65529.2025.11148861`. Publisher search-indexed abstract read; direct full record was blocked by a JavaScript challenge. | Predictable LEO paths support advance distribution of revocation/valid-key lists and proofs, with timed revocation release at handovers. | A close scheduled-security precedent. Retrieve authors/full paper before final citation. Distinguish handover authentication/revocation from optimization across intermittent ground-station contacts. No performance/security equivalence claim is justified from the abstract. |
| **Hang Zhao (2020), A Context-Based Secure Handover Mechanism for Space-Ground-Integrated Network.** [Publisher PDF](https://rebicte.org/index.php/rebicte/article/download/94/169/150), §4.2.2. Abstract and preauthentication steps checked through publisher extraction. | Ephemeris-predicted next satellite/time enables advance context and session-key transfer, followed by handover. | Preauthentication/context reuse is established. Distinguish ongoing mobile access handover from disconnected-contact lifecycle planning and the study's ground-station trust policy. Applied-source age exceeds five years. Publisher listings disagree on old/new DOI/article numbering; resolve before importing. |

The CCSDS sources establish mechanisms and lifecycle vocabulary. They are not evidence that the proposed optimization policy, threat model, or safety outcomes have been validated. A literature claim that no prior work uses future contact information for security would conflict with the primary evidence above.

## Reference metadata register for integration

The following **21 distinct relevant sources** include the 12 selected-manuscript entries and nine additional candidates. This is a source pool, not a ready or fully compliant reference list. Include a work only where the manuscript makes a supported substantive claim. Metadata flags below must survive integration; do not conceal incomplete metadata or treat abstracts as full-paper reviews. No row certifies Scopus/Web of Science inclusion.

| ID | Metadata and primary URL | Verified claim/evidence depth and remaining limit |
|---|---|---|
| R01 | Consultative Committee for Space Data Systems. (2022, July). *Space Data Link Security Protocol* (CCSDS 355.0-B-2). [Official PDF](https://ccsds.org/Pubs/355x0b2.pdf). | Title/edition/date and data-link security scope checked; no implementation-conformance claim. |
| R02 | Consultative Committee for Space Data Systems. (2020, February). *Space Data Link Security Protocol—Extended Procedures* (CCSDS 355.1-B-1). [Official PDF](https://ccsds.org/Pubs/355x1b1.pdf). | Edition/date checked; management baseline. Age >5 years; inspect exact procedures for each mapped action. |
| R03 | Consultative Committee for Space Data Systems. (2023, December). *Symmetric Key Management* (CCSDS 354.0-M-1). [Official PDF](https://ccsds.org/Pubs/354x0m1.pdf). | Recommended Practice identity/date checked; lifecycle baseline rather than a new lifecycle invention. |
| R04 | Bernal, D., Ledesma, O., Lamo, P., & Bermejo, J. (2026). IOTA-Based Authentication for IoT Devices in Satellite Networks. *Computers, Materials & Continua, 86*(1), 1–39. DOI `10.32604/cmc.2025.069746`. [Publisher](https://www.techscience.com/cmc/v86n1/64479/html). | Full relevant methods/results inspected; detailed contact/allowlist/token distinction above. Volume year 2026, online date 2025-11-10. |
| R05 | Burleigh, S., Fall, K., & Birrane, E., III. (2022, January). *Bundle Protocol Version 7* (RFC 9171). [RFC Editor](https://www.rfc-editor.org/rfc/rfc9171.html). | Official metadata checked; DTN protocol baseline. Not evidence of this simulator's interoperability. |
| R06 | Birrane, E., III, & McKeever, K. (2022, January). *Bundle Protocol Security (BPSec)* (RFC 9172). [RFC Editor](https://www.rfc-editor.org/rfc/rfc9172.html). | Official metadata checked; bundle integrity/confidentiality baseline. Normative key-management boundary should receive exact section reference. |
| R07 | Consultative Committee for Space Data Systems. (2019, July). *Schedule-Aware Bundle Routing* (CCSDS 734.3-B-1). [Official PDF](https://ccsds.org/Pubs/734x3b1.pdf). | §§1.1, 2.1: scheduled connectivity/contact plans drive routing. Age >5 years; timing-aware routing is established. |
| R08 | Detke, R., Madoery, P. G., Cherini, R., Fraire, J. A., & Finochietto, J. M. (2026). *Security-Aware Route Selection in DTNs: Towards Optimal Key Provisioning*. IEEE WiSEE, STINT workshop. [Conference-hosted full paper](https://stintworkshops.org/papers/stint2026-19-detke.pdf). | Full model inspected; key-scope MILP/contact routes verified. DOI/proceedings pages not established. |
| R09 | Mattingley, J., Wang, Y., & Boyd, S. (2010). Code Generation for Receding Horizon Control. *IEEE International Symposium on Computer-Aided Control System Design*, 985–992. [Author-hosted conference PDF](https://web.stanford.edu/~boyd/papers/pdf/code_gen_rhc_msc.pdf). | Author PDF abstract/introduction confirms moving-horizon repeated optimization; old foundational source, outside ten years. Do not infer that a larger horizon improves results universally. |
| R10 | Kelso, T. S. *A New Way to Obtain GP Data (aka TLEs).* CelesTrak. [Provider documentation](https://celestrak.org/NORAD/documentation/gp-data-formats.php). Accessed 2026-10-28. | Provider page reachable; use for input formats/provenance. Publication/update date must be taken from actual page if used; access date is not publication date. |
| R11 | Libre Space Foundation. *SatNOGS Network API Documentation.* [Project documentation](https://docs.satnogs.org/projects/satnogs-network/en/stable/api.html). Accessed 2026-10-28. | API purpose and public data statement checked. Does not establish ground-station availability or reservation for every geometric contact. |
| R12 | Orekit. *Orekit: An Open-Source Library for Spaceflight Dynamics.* [Project site](https://www.orekit.org/). | Selected-manuscript metadata retained; live homepage blocked by Anubis. Do not claim fresh documentation verification; cite exact used version/documentation in final methods. |
| R13 | Burleigh, S., Horres, D., Viswanathan, K., Benson, M., & Templin, F. (2018, August). *Architecture for Delay-Tolerant Key Administration* (draft-burleigh-dtnwg-dtka-02). [IETF text](https://datatracker.ietf.org/doc/html/draft-burleigh-dtnwg-dtka-02). | Planned proactive public-key distribution verified; expired individual draft, not an IETF standard. Age issue acknowledged. |
| R14 | Fuchs, F., Walter, F., & Tschorsch, F. (2025). BERMUDA: A BPSec-Compatible Key Management Scheme for DTNs. In *ICT Systems Security and Privacy Protection*, Part II, IFIP AICT 746, 219–233. DOI `10.1007/978-3-031-92886-4_15`. [Author ePrint](https://eprint.iacr.org/2025/806); [publisher volume](https://link.springer.com/book/10.1007/978-3-031-92886-4). | Abstract/metadata verified: PKI/ECDH/revocation and implementation. Full paper not audited; ePrint revision date 2026-04-09 differs from publication year. |
| R15 | *TRAP: Timely Revocable Authentication Protocol for Scheduled Handovers in Space-Sea Integrated Networks.* (2025). *IEEE/CIC International Conference on Communications in China*. DOI `10.1109/ICCC65529.2025.11148861`. [IEEE](https://ieeexplore.ieee.org/document/11148861/). | Publisher abstract verifies planned revocation/proofs. **Author list and pages not verified from primary record**; obtain them before final bibliography. Secondary metadata found but intentionally not promoted to confirmed author data. |
| R16 | Zhao, H. (2020). A Context-Based Secure Handover Mechanism for Space-Ground-Integrated Network. *Research Briefs on Information and Communication Technology Evolution, 6*, 52–61. DOI `10.56801/rebicte.v6i.94`. [Current publisher record](https://rebicte.org/index.php/rebicte/article/view/94). | Current publisher metadata and preauthentication/context-transfer steps checked. Prefer current publisher DOI over conflicting legacy listing. Older than applied five-year window. |
| R17 | Birrane, E., III, White, A., & Heiner, S. (2022, January). *Default Security Contexts for Bundle Protocol Security (BPSec)* (RFC 9173). [RFC Editor](https://www.rfc-editor.org/rfc/rfc9173.html). | Title/authors/date verified. Candidate for concrete cryptographic-context assumptions; does not support claims that the prototype implements those contexts. |
| R18 | Madoery, P. G., Cherini, R., Cammarano, A., Grosso, J., & Finochietto, J. M. (2024). Bundle Protocol Security Models and Policies for Safeguarding Space Information Networks. *IEEE Journal of Radio Frequency Identification, 8*, 547–558. DOI `10.1109/JRFID.2024.3406890`. [Publisher DOI](https://doi.org/10.1109/JRFID.2024.3406890). | Metadata cross-checked in Detke's bibliography and an author-deposited guest editorial; direct publisher access failed. Candidate for protection-scope policies, **not full-text claim verified here**. |
| R19 | Fraire, J. A., De Jonckère, O., & Burleigh, S. C. (2021). Routing in the Space Internet: A contact graph routing tutorial. *Journal of Network and Computer Applications, 174*, 102884. DOI `10.1016/j.jnca.2020.102884`. [Publisher](https://www.sciencedirect.com/science/article/pii/S1084804520303489). | Publisher abstract/introduction and section descriptions verify contact-plan/route-search/management/forwarding scope. 2021-01-15 is outside a strict rolling five-year cutoff in October 2026. |
| R20 | Dowling, B., Hale, B., Tian, X., & Wimalasiri, B. (2024 journal issue; 2025 ePrint). *Cryptography is Rocket Science: Analysis of BPSec*. *IACR Communications in Cryptology, 1*(4), article 21. DOI `10.62056/a39qudhdj`. [Authors' ePrint](https://eprint.iacr.org/2025/023); [institutional paper](https://eprints.whiterose.ac.uk/id/eprint/221768/1/1-4-21.pdf). | Primary abstract verifies formal BPSec analysis and missing-component detection issue. Source labels journal publication CIC 2024 while ePrint is 2025; choose publication-version year consistently. Full proof not reviewed. Useful to distinguish protocol proof from simulated lifecycle outcomes. |
| R21 | Kwon, D. K., Son, S., Park, K., Das, A. K., & Park, Y. (2025). An Efficient Handover Authentication Scheme for 6G-Enabled Space-Terrestrial Integrated Networks With Mobile Edge Computing. *IEEE Transactions on Information Forensics and Security, 20*, 12157–12172. DOI `10.1109/TIFS.2025.3628470`. [Author-institution record](https://research.knu.ac.kr/en/publications/an-efficient-handover-authentication-scheme-for-6g-enabled-space-/). | Institutional abstract/metadata verified: ECC initial authentication, lightweight handover phase, protocol analyses and NS-3 study. Full protocol/security proof not audited. Provides a current authentication-mechanism comparator, not evidence for the proposed horizon policy. |

R15 can remain a review lead until the missing metadata is obtained; the other 20 rows provide a minimum-sized working pool, but age, relevance and database-share checks still prevent calling it submission-ready. A recent-paper percentage must not count an accessed old webpage or standard as newly published research.

## Public release verification

The public [research release](https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/research) was accessible. Direct GitHub API metadata and the small checksum asset were downloaded.

- Release ID: `423291574`; published `2026-10-25T09:37:15Z`; `draft=false`; `prerelease=false`.
- Uploaded reference asset: `ContactAwareSS-final-v2-reproduction-public.zip`, 498,239,521 bytes.
- GitHub API digest: `sha256:4f19e2451ecc2e57c901e217dde0e5da57620575e7e01373e85ef4f1ba9b2a45`.
- Downloaded `.sha256` file contains the same digest for that exact ZIP filename.
- Release text explicitly directs readers to the attached ZIP and its manifest/reproduction instructions. Automatically generated source archives correspond to historical tag commit `a3e7a95`, not the final experimental source snapshot.

Evidence: `github-research-release.json` and `public-release.zip.sha256`. The 498 MB ZIP was **not downloaded or recomputed** in this audit, its internal manifest was **not inspected**, and reproduction was **not run**. Therefore this verifies public metadata/checksum availability and agreement, not independent ZIP integrity, contents, or reproducibility. Cite the attached reference artifact explicitly in the data-availability statement.

## Integration checklist

- [x] Live official formatting page and actual DOCX checked; conflicting values recorded.
- [x] Current requested filename searched; absence recorded without silently substituting an older manuscript.
- [x] Allowed fallback bibliography counted and age/metadata risks identified.
- [x] Two closest papers inspected beyond abstracts; preauthentication and scheduled-key precedents identified with inspection limits.
- [x] Public release metadata and checksum agreement checked.
- [x] Parent conveyed author-designated supplied inputs: `main(1).tex` and `Contact_Aware_Research-4.pdf`; identities and limited checks recorded.
- [ ] Confirm author declarations and exact names/affiliations/ORCIDs from the author.
- [ ] Build a ≥20-source bibliography on claim relevance, not padding; link every entry to actual text.
- [ ] Record each source's DOI/URL, exact title/authors/year, source type, inspected sections, claim supported, recency class, and foreign/self-citation classification.
- [ ] Verify database share from item-level records and covered years; save evidence and dates, or keep status explicitly unresolved.
- [ ] Reconcile standards/old foundational sources and ambiguous AITU interpretation honestly.
- [ ] Verify final abstract words/characters, title length, Word page count, all formulas, references and figure placement after conversion/rendering.
- [ ] Verify ZIP payload/hash and frozen source identity separately if claiming independent artifact integrity; do not describe this availability check as a rerun.

## Local evidence inventory

`target/aitu-revision/reference-materials/` contains the original AITU DOCX/HTML, extracted paragraphs and OOXML, publisher IOTA HTML/text, Detke PDF/text, GitHub API response/checksum, selected-input summary, and `verification-manifest.json` with hashes. Source files retained here are read-only evidence, not manuscript edits. No indexing exports were obtained; the selected manuscripts remain unchanged in Downloads.
