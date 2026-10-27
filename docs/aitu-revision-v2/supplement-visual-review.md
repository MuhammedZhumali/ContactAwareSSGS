# Supplement visual review

**Current result: final3 PASS, 14 pages.** Historical findings and snapshot hashes follow; the final3 assessment is at the end.

2026-10-28 — **FIXES REQUIRED** for the reviewed v1 render.

Inspected all fifteen original PNGs in [document-qa/v1](C:/Users/Muhalek/ContactAwareSS/target/aitu-revision-v2/document-qa/v1). Word 16 rendering was supplied by the parent; no rerender, JVM, experiment or document edit occurred.

- **PASS, pages 1–11 and 15:** readable text, complete visible numbers, no clipping/overlap. Continued tables repeat their headers.
- **PASS, pages 8–10:** S5b.1/2 and S6b.1/2 have readable wide panels; headers and numeric columns remain intact across page breaks.
- **Page 12, S9:** “Certificate” wraps as “Certificat/e”; “FEASIBLE_TERMINAL” splits internally. Widen/rebalance columns or use explained short labels, retaining 12 pt. S10’s “CONTROLLER_TIME_LIMIT” also breaks internally.
- **Page 13:** justified manual line breaks create excessive spacing throughout the pseudocode. Use left-aligned separate numbered paragraphs.
- **Page 14:** hash first halves are displaced far right after “SHA256:”, while second halves start left. Set paths and 32+32 hash lines in separate left-aligned paragraphs. No visible character clipping was found.

The reviewed [article-content.json](C:/Users/Muhalek/ContactAwareSS/docs/aitu-revision-v2/article-content.json:163) matches the code audit for comparator, traversal, seed priority, candidate comparison and latency sequence. Two precision fixes remain: line 179 should say preparation **at or after** its boundary; line 253 should assign the 10,000-transition cap to seed rollout/retained extension, not the entire retained replay loop.

This verifies rendering and the named method blocks, not independent numerical reconstruction or journal compliance. Reinspect affected pages after fixes.

## Snapshot SHA-256

PNG names below are relative to the reviewed v1 directory; DOCX/JSON are in docs/aitu-revision-v2.

```text
supplement.docx 08aaed0eeebcedb3086b08d414167661bb00febcbfc5a4cb8d2fc3eef3107082
article-content.json ef4ddb5ec202fe8cfe7e669caf45325c905314b47b173d2a5322a79d0edb5c81
supplement-01.png 4bec58fe7435e06f965303a2e90da69af83832d23f89fbfc1b5c0f200d7b6596
supplement-02.png 366f35c160211c99f7d507a24038700867a1a1cbc09833432593fd6acd97dc2f
supplement-03.png 0af18c3add142d281d1947b5a4d713b71989de95a6567d2ed2062d3f0ae7c868
supplement-04.png c871f4a5795634e75f6ba73d04d5a31be165e214db41876abf8b9e436e70ec74
supplement-05.png c5ad497596d221f11670ecefe9e948ba2ea2fb597e456640e35a5037e52a31d3
supplement-06.png 9304c25ceac8453cb8f06b2f27afad02907872d98edb4ce955b95ff2addb02cf
supplement-07.png 0f949c97a5db2bcb3142d3525673fc835ee4278b03f5b8ec345a4f9e82fe020c
supplement-08.png c7eac6d50432eb31508c673ed117d8d9ca930f9fec490445c6935245dbfa3b6c
supplement-09.png ef81e2e44542b19acc3f1383425395ddc20f469515558a49647eb783b500881c
supplement-10.png 925e2cf22383a5982e7dfd8653e4c2025f24849be0b0cc85dddddbc61d43d8ec
supplement-11.png 63938661559a331b50a7fc9a2ac5cd52b7bc5acb87e6cbabfdff0c010dfce0e9
supplement-12.png af11ed8d83b701b5c76b73991eaf0316d7e314cc81ad5a9258105742718f6288
supplement-13.png 3c66e933e9dff5fb3e84f3c59dceaf158116b55b51ebf7b6ab8d33540171d3e0
supplement-14.png d647888245820e3f8e050166491bcab2354519f1b0e598c8a624c64f45955451
supplement-15.png d14beba2055cc85284ac7fa935b71e41317c8a44fe27a5cdaf4f164f573966a9
```

## Final2 review — PASS with pagination note

2026-10-28. This final2 assessment supersedes the v1 layout findings above.

Viewed every changed page again: **3, 4, 5, 6, 7, 12, 13, 14, 15**. Pages **1, 2, 8, 9, 10, 11** are SHA-256 identical to the previously inspected v1 images. All fifteen final2 pages are therefore covered.

The S9/S10 labels on page 12, pseudocode on page 13, and left-aligned path/hash lines on page 14 are corrected. Headers repeat correctly; numeric columns, wide S5b/S6b panels and table continuations remain readable without clipping or overlap.

**Nonblocking pagination note:** page 15 contains only the final two lines of the paragraph started on page 14. Keeping that closing section together or reclaiming two lines above would improve presentation; no text is lost.

Method precision fixes are confirmed in article-content.json lines 179 and 253: preparation is “at or after” the boundary; the 10,000-transition count limits seed rollout and retained extension, not the replay loop. The supplement states these accurately on pages 3 and 13.

No rerender, JVM, experiment or DOCX edit was performed. PASS concerns readability and the specified method corrections, not numerical reconstruction, public deposition or journal compliance.

### Final2 SHA-256

DOCX/JSON paths remain docs/aitu-revision-v2; PNGs are in [document-qa/final2](C:/Users/Muhalek/ContactAwareSS/target/aitu-revision-v2/document-qa/final2).

```text
supplement.docx fe267dbe7fa7d9ca801130f8e2ee0a79d59414daf242b00e5ff602d0cfa6fd9e
article-content.json 71c7db62a7039d8e6b9be12b3f4e103aac1df77c810b704e79a4e637e432c23b
supplement-01.png 4bec58fe7435e06f965303a2e90da69af83832d23f89fbfc1b5c0f200d7b6596
supplement-02.png 366f35c160211c99f7d507a24038700867a1a1cbc09833432593fd6acd97dc2f
supplement-03.png a53e7c250181dc844c3414ae6de48f5863ac391147a8b1a2986868d2bda2edf4
supplement-04.png ce233658dec65aa553e61d5c9dd18d813fbf62abaa85ba457eae808a8ed82433
supplement-05.png 99874b99266adab8ea6cb83cfb9f2cfeb150fffaf75d59376cee0bcf5b0ed19d
supplement-06.png ab480884cc960fa09203ca8313e764de952e0339ee93e1578b53022b1cd5275c
supplement-07.png 4d9f5adbd55c4ab7b2f71a0dc2ff467c9c17df2e56186fab4de7d9789ed66c61
supplement-08.png c7eac6d50432eb31508c673ed117d8d9ca930f9fec490445c6935245dbfa3b6c
supplement-09.png ef81e2e44542b19acc3f1383425395ddc20f469515558a49647eb783b500881c
supplement-10.png 925e2cf22383a5982e7dfd8653e4c2025f24849be0b0cc85dddddbc61d43d8ec
supplement-11.png 63938661559a331b50a7fc9a2ac5cd52b7bc5acb87e6cbabfdff0c010dfce0e9
supplement-12.png be8bdd680d9b8498a4afa5ccf65885c169645a788488b198b831368f171d7d82
supplement-13.png 4b64d099df0965575599c7e19d827716527d8d2e13559a85795f996989f9d2e5
supplement-14.png 4b4c56ea343a953459ed34e5452b3678244cdfd0c44a0b948c408b6cd6c7fba5
supplement-15.png 3ce59fab1ca221d990721c9b30d2d32b378b3fc697d75b10fe0be507d622ec54
```

## Final3 review — PASS

2026-10-28. **Final supplement: 14 pages.** This assessment supersedes the final2 pagination note.

The final3 render inventory and Word 16 render metadata both report fourteen supplement pages. SHA-256 comparison confirms that pages **1–13 are identical to the reviewed final2 pages**. Only page **14** changed; it was opened and visually inspected at original resolution. The closing section now fits completely on page 14. Paths, split hashes, body text and footer remain readable without clipping or overlap; the nearly empty fifteenth page is eliminated.

Coverage is complete: thirteen previously inspected, hash-identical pages plus one newly inspected page. The exact final DOCX hash matches the identity supplied by the parent. No new render, experiment, JVM execution or document edit occurred.

Final DOCX: [supplement.docx](C:/Users/Muhalek/ContactAwareSS/docs/aitu-revision-v2/supplement.docx)

```text
supplement.docx 969ef31117be827086256f6a0b334129d342b70f47554e449d3352c5f777e0b0
supplement-14.png c977e336c02206646e7dbd5842789270d2099305d7986d81f2cc377ee5556311
```

Pages 1–13 retain the individual hashes listed in the final2 block above. Final images: [document-qa/final3](C:/Users/Muhalek/ContactAwareSS/target/aitu-revision-v2/document-qa/final3). PASS concerns the completed visual/method review, not journal certification.




## Wording correction 2026-10-28 — PASS

Current supplement remains14 pages, DOCX SHA256 789c7a4f48f55339efce57b5dd5f536d6023e1a4ac9f38d6fdd32908feffbc1d. Pages1–10 are byte-identical to final3; changed pages11–14 were viewed by the parent. SHA256 hard wraps were removed at12pt with all11 values preserved. The display instruction now applies to either line wrapping. Three requested scientific wording changes were independently checked against raw controller results; no new experiment occurred. Current hashes and complete coverage are in visual-review.json.
