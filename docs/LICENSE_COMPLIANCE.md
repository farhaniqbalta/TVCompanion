# License compliance and future release flexibility

> Integration update: the user approved and accepted the [single-APK video preview](phase-2/SINGLE_APK_INTEGRATION.md). The assessment below describes its review-time baseline and proposed strategy; native implementation is now confined to the selected engine module. No license was adopted and distribution clearance remains open.

> Subsequent development update (2026-10-29): the user approved an opt-in [direct-TV video experiment](phase-2/TV_RECEIVER_EXPERIMENT.md). Statements below about absent implementation describe the reviewed baseline. The harness now selects libplist 2.7.0, OpenSSL 3.5.4 and Android MediaCodec; GStreamer remains unselected for production. This does not adopt licenses or clear distribution.

Assessment: 2026-10-28, branch `plan/phase-2-airplay`, baseline `b876c0d` plus architecture documentation. This is a technical compliance assessment and proposed strategy, not a legal determination of derivative-work scope. No license grant, relicensing, LICENSE file, native integration or release is made here. See [source provenance](PROVENANCE.md) and [receiver boundary review](phase-2/RECEIVER_BOUNDARY_REVIEW.md).

## Evidence and limits

The repository has no selected root release license. Public GitHub visibility is not a general reuse license. Actual Gradle/source inspection shows no UxPlay, GStreamer, JNI, AIDL or native media implementation. Current core imports are Kotlin/JDK only. This assessment is not a complete file-by-file ownership audit, resolved dependency SBOM, APK binary scan or opinion that a future combined release is compliant.

Reviewed UxPlay revision: **`534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc`**, upstream commit timestamp 2026-10-28T06:30:49Z, master when inspected. This is a reproducible assessment reference, not selection of a stable release for shipping. No upstream source was copied into this repository. [Pinned source tree](https://github.com/FDH2/UxPlay/tree/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc).

Reviewed exact LICENSE, main source header, renderer header, root/lib/renderer CMake, bundled license files and representative inherited-source headers. Main/renderer headers grant GPL version 3 **or later**; do not infer that every bundled file has identical terms. Preserve per-file grants and notices. Main source credits Florian Draschbacher (2019) and modifications by F. Duncanh (2021–23); the inspected video renderer extends the latter dates through 2024. [Main header](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/uxplay.cpp), [renderer](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/renderers/video_renderer.c).

## Verified GPL terms and unresolved application

GPLv3 sections 4–5 require retained notices/license, modification notices/dates, and GPL licensing of a covered combined work as a whole. Interactive legal-notice requirements have qualifications. Section 1 includes required interface definitions, dependent source and build/install scripts in Corresponding Source, with stated system-library/tool exclusions. Section 6 governs binary distribution: for downloadable APKs, equivalent source access with clear directions beside the binary is a practical route. Section 10 prohibits additional restrictions on recipients' GPL rights. Private non-conveyed experiments do not trigger binary-distribution source delivery. [Exact upstream GPLv3 text](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/LICENSE).

Section 6 installation information applies to qualifying User Product conveyances involving transfer of possession/use. It can include methods or authorization keys needed to install/run modified builds, subject to exceptions. A standalone APK download and a TV/device transfer are distinct scenarios; assess the actual arrangement. Do not assume every APK requires publishing production signing keys, or that Android signing removes this obligation. Document user-signing/install paths and review restrictions before distribution. [GPLv3 section 6](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/LICENSE).

**Conservative release position:** plan to distribute the integrated UxPlay APK on GPLv3-compatible combined-work terms, including corresponding application source, until a qualified review supports a narrower scope. Whether particular IPC-separated components are independent aggregation is fact-sensitive; one APK, independent modules, JNI or a process boundary alone settles neither direction. Do not rely on `:airplay` as an exemption. This position concerns the distribution, not automatic reassignment of copyright or replacement of every original source file's license.

## Native dependency findings

Versions below are not Android selections unless explicitly pinned. Build files establish dependencies; they do not establish the license of every binary a future SDK might bundle.

| Component / evidence | Finding and release action |
| --- | --- |
| UxPlay main/renderers | GPL-3.0-or-later in inspected headers; preserve inherited authorship, original license text and dated patches. Overall README calls the project GPLv3. |
| `lib/playfair` | Bundled GPLv3 license text. README credits EstebanKubata. Inspect individual file grants before assigning an exact SPDX suffix; upstream also flags legal uncertainty surrounding this component. No permission or process layout resolves that concern by itself. [Bundled license](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/lib/playfair/LICENSE.md). |
| ShairPlay-derived code / `lib/raop.c` | Inspected header grants LGPL-2.1-or-later and credits Juho Vähä-Herttua with later modifications. Inheritance includes other authors; preserve individual headers, not just a UxPlay credit. [raop.c](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/lib/raop.c). |
| Internal mDNS `lib/mdnsd/mdnsd.c` | Representative header is LGPL-2.1-or-later, copyright kgbook 2026. Default build uses internal discovery unless external DNS-SD options apply. Audit all selected discovery files; this is not a blanket directory clearance. [Source](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/lib/mdnsd/mdnsd.c). |
| `lib/llhttp` | Bundled MIT license, copyright Fedor Indutny 2018; retain copyright/permission text with distributed copies. [Exact license](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/lib/llhttp/LICENSE-MIT). |
| GStreamer + GLib | Renderer requires GLib and GStreamer core/sdp/video/app. GStreamer uses LGPL, but plugins and their dependencies require separate assessment. GLib's current upstream COPYING points to LGPL-2.1-or-later; select exact versions and inspect file exceptions. [Build](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/renderers/CMakeLists.txt), [GStreamer advisory](https://gstreamer.freedesktop.org/documentation/application-development/appendix/licensing.html), [GLib](https://github.com/GNOME/glib/blob/main/COPYING). |
| Codec/sink/parser plugins | No Android plugin list selected. Record exact plugin names, source revisions, linked dependencies and static/shared linkage; do not infer license from “good/bad/ugly” grouping. Audit FFmpeg/libav or proprietary plugins if selected; do not bundle an entire SDK by default. Patents and codec rights are distinct from copyright licenses. |
| libplist | Required by upstream lib CMake; current upstream provides LGPL-2.1 text. Pin a version and inspect source grants/exceptions, not only the COPYING file. [Library license](https://github.com/libimobiledevice/libplist/blob/master/COPYING.LESSER). |
| OpenSSL/libcrypto | Upstream accepts 1.1.1+, but 3.0+ uses Apache-2.0, while older releases have OpenSSL/SSLeay terms. Prefer an audited compatible 3.x build; upstream warns of pre-3 GPL compatibility issues and no added exception. Do not assume a bundled Android library is a GPL System Library. [Build](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/lib/CMakeLists.txt), [OpenSSL licenses](https://openssl-library.org/source/license/index.html). |
| Optional platform branches | X11, D-Bus and external Bonjour/Avahi discovery appear in desktop build paths. Determine what Android actually includes; no blanket approval of these alternatives or their licenses. [Root build](https://github.com/FDH2/UxPlay/blob/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc/CMakeLists.txt). |

LGPL obligations remain relevant if UxPlay is later removed but LGPL media libraries remain. Selected linkage and license version determine source, modification, notice and replacement/relinking requirements. For static linkage, keep relinkable materials or an adequate full-source rebuild route; for shared linkage verify actual user replacement is feasible, rather than assuming a `.so` suffix is enough. Review applicable reverse-engineering permissions and installation restrictions. [LGPL 2.1 text from libplist](https://github.com/libimobiledevice/libplist/blob/master/COPYING.LESSER).

## Existing TVCompanion provenance and dependencies

| Area | Observed status / unresolved ownership work |
| --- | --- |
| Original application/domain/UI/branding | PROVENANCE describes these as project-authored. That assertion is not a signed ownership record. Confirm authors, employment/assignment constraints and outside contributions before choosing a license. Git author metadata alone is insufficient. |
| Tuya adapter / notification feature | Adapted from sibling Poncol Light and TVNotify. No standalone LICENSE file was found in those local project trees. Trace exact original files/revisions and authors; absence of a license is not permission to relicense. Check copied/adapted snippets and inherited notices, not just the repository owner. |
| TinyTuya 1.20.0 | Existing docs say only synthetic test vectors were generated with it; Python source/runtime is not bundled. Its exact upstream license is MIT. Verify the Java adapter/test fixtures contain no unrecorded copied expression; if they do, retain the required notices. [Versioned license](https://github.com/jasonacox/tinytuya/blob/v1.20.0/LICENSE). |
| Kotlin 1.9.24 | Declared build version; upstream Apache-2.0. Inventory actual packaged Kotlin runtime artifacts/transitives and preserve applicable notices. [Versioned license](https://github.com/JetBrains/kotlin/blob/v1.9.24/license/LICENSE.txt). |
| Android framework / org.json | Runtime code imports platform APIs; do not confuse Android's implementation with the test-only Maven JSON artifact. OS system-library exclusions require actual analysis if relevant. |
| JUnit 4.13.2 | `testImplementation`, EPL-1.0. Keep it out of runtime; its terms differ from GPL-compatible runtime assumptions. Inventory transitive test dependencies separately. [Versioned license](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt). |
| JSON-java 20240303 | `testImplementation`; exact tag LICENSE says Public Domain. Do not substitute historical JSON-java licensing assumptions. Verify artifact metadata/content if distributing the jar. [Versioned license](https://github.com/stleary/JSON-java/blob/20240303/LICENSE). |
| Gradle wrapper, AGP, SDK/NDK and other tooling | Build tools are not automatically APK runtime dependencies. Inspect exact distributed wrapper/tool artifacts and their notices; SDK redistribution terms are separate. Build instructions should identify tool versions/downloads. No resolved transitive inventory has been completed here. |
| CleanYourTV / other POC utilities | External references, not assumed bundled code. POC credential-acquisition scripts and their dependencies are not Android runtime dependencies merely because the workspace contains them. Trace any actual copied code separately. |

No identified existing runtime dependency proves an unavoidable GPLv3 incompatibility, but missing ownership evidence and an unselected native/plugin graph prevent release clearance. Do not interpret this table as a full third-party audit.

## Recommended strategy — proposal, not adopted licenses

For an open-source portfolio project, prefer **Apache-2.0 for verified independently authored code**, with an explicit root scope statement excluding vendored/derived components, and retained upstream licenses for those components. Apache-2.0 supplies a patent grant and permits GPLv3 combination. [Apache Software Foundation compatibility guidance](https://www.apache.org/licenses/GPL-compatibility.html). MIT is a simpler alternative, but offers no comparable express patent grant; neither lets us relicense others' work. No root license is installed by this assessment.

Proposed layout after ownership review and approval:

- Root licensing statement: default license for original material, explicit exceptions/path mapping, and explanation that UxPlay-enabled combined releases comply with GPLv3.
- Original core, IPC, UI and reusable Android runtime: independently authored, permissively licensed where rights are verified. Do not copy upstream code to obtain this separation.
- UxPlay-derived engine/JNI/native files and patches: preserve applicable GPL/LGPL/MIT file terms and author notices; mark modifications. A newly authored adapter's origin and licensing must be recorded without suggesting the integrated binary escapes copyleft.
- A maintained third-party notice inventory and verbatim license texts, with source URLs, hashes/revisions, local paths, modifications, linkage and inclusion per release. Add SPDX/file notices only after verifying rights and exact grants.
- A release compliance document/source bundle generated from the actual chosen build, not a blanket “all dependencies permissive” badge.

Separate original source licensing from combined binary conditions. A permissive original module can retain its original grant while participating in a GPL-covered distribution. Dual licensing is unnecessary for the initial portfolio approach; offering an alternative license for all contributions requires sufficient grants from every relevant rights holder. A DCO records contribution provenance but is not a copyright assignment or blanket future relicensing permission. A CLA with explicit rights may help if future ownership control is essential, but adds administration and cannot grant rights to upstream UxPlay.

## Future replacement and licensing flexibility

Build-time composition should permit a receiver-free or replacement-receiver build. Remove GPL-derived source/headers, copied helper code, generated bindings based on them, JNI/native binaries and exclusive transitive dependencies; inspect the APK and dependency graph to verify removal. Keep original APIs semantic and implementation-independent. No UxPlay implementation expression should migrate into core or the shared runtime. [Receiver proposal](phase-2/RECEIVER_BOUNDARY_REVIEW.md).

Future independently authored code may be offered under different terms where ownership and remaining dependency licenses permit. Existing recipients retain earlier granted rights; a future license choice does not revoke past GPL/permissive grants or retroactively relicense third-party releases. If third-party contributions lack an adequate alternative grant, obtain permission, retain their terms, or replace their expression. Removing a dependency does not by itself prove all derivatives were removed. A compatible replacement may still have LGPL, attribution, patent or protocol constraints.

## Work required now versus distribution

**Before integrating UxPlay:** agree the neutral boundary; preserve the exact upstream revision, file origins and notices; keep copied code/native dependencies confined to the engine module; record dependency/plugin choices and patches during experiments. Review apparent incompatible dependency choices before relying on them. Private feasibility experiments can proceed without a completed public-release notice generator or root license decision. Publicly publishing source, test APKs, CI artifacts or giving copies to others is a separate conveyance assessment; “debug” does not exempt a distributed build.

**Before distributing an APK:** resolve ownership and combined-license scope; approve license files/notice strategy; close the actual dependency inventory and compatibility review. No UxPlay-enabled release is cleared by this assessment.

Planned distribution checklist:

- Identify the exact APK hash, source commit, upstream revisions, patches, ABIs, plugin allowlist and static/shared libraries actually included.
- Provide corresponding preferred source, AIDL/JNI interface definitions, patches, Gradle/CMake/native build and install scripts, dependency source/configuration and exact tool versions needed for the covered release. A moving branch, upstream URL or submodule pointer alone is not an adequate maintained source-delivery plan.
- Publish an immutable source archive alongside each downloadable APK, with clear adjacent directions and equivalent free access; retain third-party sources/patches as needed. Do not assume a public GitHub repository automatically satisfies every release or delivery channel.
- Include required copyright, license and modification notices; determine applicable interactive legal notices and provide an accessible in-app licenses/source entry. Do not remove inherited attribution when changing branding.
- Confirm recipients' modification/redistribution rights are not contradicted by app/store terms, anti-tamper controls or license checks. Review the intended distribution channel.
- Assess installation-information triggers for the actual transaction, including bundled hardware/OEM scenarios; document rebuilding, signing and installation of modified versions. Keep private keys private unless a legally required installation design cannot be met otherwise; resolve such a design before release rather than promising impossible installation rights.
- Satisfy selected LGPL replacement/relinking terms and other third-party notices. Review codec/patent/FairPlay concerns separately from copyright license compatibility.
- Build from the published source instructions, inspect the resulting artifact contents, and archive evidence. Do not publish user credentials, personal configuration or signing secrets in compliance bundles.

**Optional later:** automate SBOM/notices and source-archive checks, add a receiver-free CI variant, test replacement composition, and formalize contributor terms if outside contributions become material. No plugin framework or new DI system is needed.
