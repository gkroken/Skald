# WORKPLAN — Spine #2: Bundles + builds

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done

**Status: planning only, 2026-09-17. No implementation or security claim is certified by
this document.** Prepared against branch `skald`, commit `475651f`, and ContainerProxy
tag `v1.2.4`. Implementation stays on `skald`, not `master`.

Model: **Opus 5** for the manifest, extraction, isolation contract, lifecycle and review;
**Sonnet 5** for driver and log plumbing after those contracts and the isolation proof
are accepted. Extraction **and build isolation** require an independent Opus 5 security
review mid-track, by a session that did not implement them. Neither delegation nor a
passing author-written suite constitutes that review.

**Who performs it, decided 2026-09-18 by the user: the independent reviewer agent, not a
human.** The coding agent implements and does **not** perform it; the two-session split in
`code_review/` is the mechanism. See T5 for what that does and does not buy, and for the
conditions that make it worth anything.

The LOCKED section distinguishes inherited constraints from decisions made by this plan.
Items marked **OPEN / sign-off** are not locked by implication; task 0 records their
decision gates and each is resolved before dependent work. In particular, the concrete BuildKit sandbox configuration is not yet
proven. Do not weaken an inherited invariant to make a demo work.

## Problem

Spine #1 can register an image and run it. It cannot accept source, decide whether a bundle
is safe to unpack, restore dependencies, produce an image, or explain a failed build.
This track connects **bundle → build attempt → immutable image → content version → explicit
activation**, without changing content identity, ACL semantics or the serving address.

The actual handoff, verified in code rather than inferred from older workplan prose:

| Existing behavior | Consequence for #2 |
|---|---|
| `V1__content_registry.sql` has `content`, `content_version`, `content_acl`, `content_env`, `content_path`, `audit_event` in `skald`; `content_version.image` is NOT NULL | Add **V2**, never edit V1 or repair its checksum to make tests pass. A failed build need not be a runnable version. |
| `ContentAdminService.addVersion` allocates under a content-row lock and activates unless `activate=false` | Reuse/refactor allocation and auditing, but do not call its default-activating path from a background build. |
| `ContentSpecRepository.toProxySpec` reads `spec_json` in SQL but does not use it, and fixes port 3838 | Define a small server-generated runtime contract; merely writing arbitrary JSON does nothing. Include every consumed runtime field in the spec fingerprint. |
| Spec IDs are `c<content UUID without hyphens>--v<n>`; paths are independent | No path, title or owner name belongs in durable storage keys or registry repository identity. |
| `findSpec` resolves active versions and superseded versions with live proxies | Preserve this. Image cleanup must protect **all retained versions**, not only the active one. |
| `ContentAdminService.delete` refuses live proxies; versions otherwise cascade on deletion | Add build/deletion coordination and durable cleanup records before introducing external artifacts. Do not lose their keys in a cascade. |
| `content_env` is a table only | No assumed encryption or secret injection. This track does not write it. |

Engine source anchors are under `~/projects/containerproxy/src/main/java/eu/openanalytics/containerproxy/`:
`service/ProxyService.getUserProxies` resolves specs through `canAccess`;
`stopProxy` needs the dispatcher, not a fresh spec; `startProxy(...).run()` starts
synchronously. `backend/docker/DockerEngineBackend.startContainer` consumes the image,
user, CPU/memory limits, network and volume fields from `model/spec/ContainerSpec`.
Its default image pull policy is `IfNotPresent`. Use immutable image digests, not a tag
whose meaning changes under that policy. #2 uses a separate build driver, not
ContainerProxy's interactive-proxy lifecycle as a job scheduler.

Older counts and the “not yet reviewed” paragraph in `WORKPLAN.md` are historical, not
acceptance evidence. The serving layer has since been reviewed and fixed. Task 0 records
new executions, their commit and environment; no recorded green is carried forward.

## Decisions (LOCKED)

### 1. Keep the spine boundaries and the existing identity model

**Inherited:** our own `.tar.gz` plus `manifest.json`, no Connect/rsconnect/pins API
(ADR-0003); Docker single-host v1 with backend-independent storage/build interfaces
(ADR-0004); S3-compatible durable storage from the start (ADR-0007); immutable content
and version identity, renameable `/c/<path>` addresses (ADR-0011).

**Rejected:** Git checkout as a second input path (#3 follow-on, already deferred), a
filesystem storage fallback, a new ContainerProxy fork, API keys or publisher UI in #2.
These either contradict locked scope or expand the track without proving its core path.
If a second ContainerProxy override becomes necessary, stop and bring back ADR-0001's
fork decision. Do not conceal it in the build driver.

**User-confirmed scope (2026-09-16): both R and Python Shiny now.** Each gets its own
real upload/build/activate/rollback acceptance, not just an image or a schema branch.
R/Python base-image contracts for later tracks are also specified below. No static
serving, anonymous access, API sharing or artifact-serving feature is marked delivered here.

### 2. A versioned, declarative manifest; no publisher-selected build program

**Plan decision, contract detailed below:** `schemas/manifest.schema.json` is the
authoritative JSON Schema, dialect 2020-12, with an integer `schema_version`. Freeze the
first supported format after task 1's contract review and before accepting durable bundles.
Keep every released schema readable; changed meanings require a new schema version.
Keep immutable versioned schema copies and select by `schema_version`; a future change
to the convenience file at `schemas/manifest.schema.json` cannot alter validation of v1.
Unknown properties and unsupported versions fail explicitly. There is no remote schema
or `$ref` fetching from an upload.

The manifest selects a supported type, exact language version, entrypoint and lockfile;
the server chooses a digest-pinned base and generates the recipe. The file inventory
binds the manifest to bytes. The server verifies it; a publisher's hash is not trusted
as proof of authenticity or as authorization.

**Rejected:** publisher Dockerfiles, shell build commands, arbitrary base images, package
manager flags, OS-package lists, remote context URLs and raw ContainerProxy/SpEL fields.
They expose additional execution/configuration surfaces and make a platform policy
unenforceable. Dependency install hooks and application code are still **untrusted code**;
rejecting Dockerfiles is not itself a sandbox. Evidence: T2, T3, T5, T7.

### 3. Immutable S3 objects addressed by IDs; layouts are explicitly versioned

**Plan decision:** use the three existing configurable bucket roles and the `v1/` key
layout below. UUIDs are lowercase canonical UUID strings; a content version's DB UUID
and its display number are different fields. Bucket names/endpoints are deployment
configuration, not bundle fields. Object keys never depend on a content path or title.

**Rejected:** mutable `latest/` objects as the authority, a single appendable log object,
keys derived from URLs, and direct S3 credentials for content or builds. Renames must not
move artifacts; concurrent writers need immutable results; S3 multipart uploads are not
a live append stream. The DB owns current pointers; completion descriptors own complete
artifact inventories. The layout is documented sufficiently for later R/Python helpers
to interpret authorized objects without Java classes or a Connect board protocol.
Evidence: T4, T8, T10.

### 4. Build attempts are not content versions

**Plan decision:** server-generated `bundle_id` identifies one accepted upload;
`build_id` identifies **one execution attempt**, independently of the bundle hash, content
version and worker/container ID. A retry creates a new build ID with `retry_of`; it can
reuse the same immutable validated bundle. The request's idempotency key prevents an
HTTP retry from accidentally becoming a new execution attempt.

Create **no `content_version` row before success**. Only after image publication and
verification, runtime validation and final log persistence does one DB transaction insert
the version, link it uniquely to its build, and mark the attempt successful. Preserve
`image NOT NULL`. Failed/cancelled/timed-out/interrupted attempts retain their status and
logs and create no version or spec ID. Version numbers follow successful publication
order, not upload/start order. Never renumber or reuse a published version number.

**Rejected:** placeholder image strings, making pending versions resolvable, overwriting a
failed attempt on retry, and implicitly activating the build that happens to finish last.
These mix diagnostics with runnable state and permit slow older builds to displace a
newer deployment. Evidence: T6, T9, T10.

### 5. Digest-pinned images; retention follows references, not “active”

**Plan decision:** generated images use a configured registry authority and repository
`skald/content/<content-uuid>`, unique tag `build-<build-uuid>`, and an OCI digest recorded
as `<authority>/skald/content/<content-uuid>@sha256:<digest>` in `content_version.image`.
Record tag and digest in the artifact ledger. No `latest` tag is used for execution.
Architecture is recorded explicitly; #2 produces one native Linux architecture per build,
not an accidentally platform-dependent multi-architecture manifest.

Use one registry authority that the host Docker daemon and build exporter can both reach.
`localhost:5000` inside a builder is not the host registry, and Compose `registry:5000`
is not automatically resolvable by the host daemon. Task 5 must prove push **and a cold
ContainerProxy pull** using the chosen name, TLS/auth and any dev-only configuration.

Retain every successfully published version's image and bundle until explicit content
deletion or a future explicit version-retention policy. Superseded is not disposable.
An artifact collector handles unreferenced failed-build images, expired caches and
abandoned objects; the registry's native GC reclaims blobs in a maintenance window.

**Rejected:** build ID as spec ID, slug-based repositories, moving release tags, blind
registry age policies and `docker system prune` as application GC. They respectively
break #1 identity, rename independence, reproducibility or rollback. Evidence: T9, T10.

### 6. Isolation is an acceptance contract, not the word “rootless”

**Inherited minimum, made testable here:** no Docker socket or TCP daemon access from
untrusted code; no privileged build, host filesystem bind mount, host PID/IPC/network
namespace, host-root identity or access to another workload; bounded CPU, memory, PIDs,
disk, logs and lifetime. No platform, database, MinIO admin or registry-admin credentials
in a dependency install or application process. This applies to extraction helpers,
dependency hooks and smoke-started images, not only the final `RUN` instruction.

**Plan decision:** one disposable rootless BuildKit worker per attempt, no worker reused
across publishers; a trusted launcher controls its lifecycle. **The worker runtime is a
configuration seam, decided 2026-09-18 by the user** — the launcher selects a runtime
(`runc`, `runsc`, a microVM runtime) and receives the isolation profile as one policy
object, rather than having one runtime and a scatter of flags compiled into it. Rootless
BuildKit under `runc` is the **default and the only profile #2 ships**; the seam exists so
that an operator whose publisher population is wider than "people we issued accounts to",
or whose host has KVM, can raise the boundary without a redesign. **A runtime that cannot
enforce every bound in the configured profile must refuse to start, never run weaker** —
a seam that silently degrades is worse than no seam, because the profile then describes an
intention rather than a state. The launcher may use the
platform's existing Docker control-plane access, but that socket is never mounted into
the worker or exposed through a worker-callable Docker API proxy. Transfer inputs through
a bounded BuildKit session/stream, not a bind mount of the repository, platform temp
directory or home. Driver handles stay opaque outside the Docker implementation.

**Rejected:** Docker-in-Docker with `--privileged`, mounting `docker.sock`, a shared
rootful build daemon, a flag-reading “isolation test”, and silently adding
`--oci-worker-no-process-sandbox` to get nested builds working. The last option weakens
process separation and process cleanup; rootless is not evidence to the contrary.

**Required gate; Q2 DECIDED 2026-09-18, the profile still owes its proof:** the precise rootless worker, namespace, seccomp/AppArmor,
cgroup and storage configuration must pass T3/T5 and the T5 agent review **before** the
production build driver is connected; a human review of the deployment profile remains
owed to the operator and is not discharged by that gate. No unrestricted profile or host-wide security
disablement is pre-approved by this plan. A requirement for one is a concrete proposal
to bring back, not an implementation detail. The same applies if a separate VM becomes
necessary: that changes the Docker single-host deployment contract and needs sign-off.

### 7. Lockfile caching is scoped reuse, not a reproducibility promise

**Plan decision:** the dependency-layer key is a SHA-256 of a canonical, domain-separated
record containing recipe revision, exact base digest, target architecture, language and
package-manager versions, repository/mirror policy revision, exact lockfile bytes and
hashes of any local dependency inputs. BuildKit cache references are scoped to content
UUID plus that key. No cross-content writable cache or secret-bearing cache mount.

Restore dependencies before copying changing application sources. For permitted local
dependencies, include their full trees in the dependency input and key; never exclude
them merely because they are “source”. Reject unsupported local/VCS dependency forms
clearly rather than caching an incomplete key. Full build provenance additionally binds
the validated bundle inventory, generated recipe and effective isolation/limit policy.

**Repository policy is configuration (Q3).** The "repository/mirror policy revision" in the
cache key is the identity of the *configured* repository set, not a fixed list: changing
which mirror a deployment resolves against must miss the cache, because the same lockfile
can resolve differently against a different mirror.

**Rejected:** a bare lockfile hash as a global cache key, caller-supplied cache refs, and
claiming equal lockfiles guarantee byte-identical images. R/package repositories and
install scripts can change behavior or disappear. Save resolved package checksums and
sources; use controlled repositories and exact bases; describe the achievable guarantee
as recorded inputs plus immutable published output. Rollback uses the existing digest,
never a rebuild. Evidence: T7, T10.

### 8. Explicit activation; no environment-secret feature in #2

**Plan decision:** success stages a version. Activation/rollback is the existing explicit
content-scoped operation and changes only `content.active_version_id`, transactionally
with its audit event. No build-completion callback starts/stops/replaces user proxies.
The direct-image admin endpoint remains an explicitly trusted legacy/admin path; preserve
its existing API behavior and distinguish its versions from managed build artifacts.
The new pipeline never inherits its default `activate=true` behavior.

`content_env` encryption stays with **spine #3, before its first write or read/injection
feature**; #4 can expose the resulting feature. #2 accepts no secret values, private
repository credentials, secret build arguments or environment-setting manifest fields.
Operator credentials for S3/registry are separate service configuration, never content
environment values, bundle bytes, image layers or user-visible logs.

**Rejected:** storing plaintext “temporarily”, putting decryption into an image, trying
to scrub arbitrary publisher secrets after executing them, or delaying mandatory
encryption until after a write API ships. Without an injected secret there is no promise
to detect a secret a publisher deliberately includes in source or prints. Source and
logs remain private regardless. Evidence: T7, T8, T9.

## Design

### Components and boundaries

New implementation stays under `eu.openanalytics.shinyproxy.publisher.*`:

| Component | Responsibility |
|---|---|
| `bundle/ManifestValidator`, `BundleValidator`, `SafeExtractor` | Bounded parsing, schema and inventory checks, private extraction; never run bundle code in the JVM |
| `storage/ArtifactStore`, S3 implementation | Immutable put/get/head/delete, multipart abort, bounded streams; no `File` or Docker types in its public contract |
| `build/BuildService`, `BuildRepository`, `BuildCoordinator` | Admission, durable status, idempotency, leases, completion and cancellation |
| `build/BuildDriver`, Docker/BuildKit implementation | `submit`, `observe`, `cancel`, `reconcile`; immutable input/result descriptors and opaque execution handle |
| `build/RecipeCatalog`, `BuildLogStore` | Server-owned recipes/base digests; sequenced durable log chunks and replay |
| `registry/VersionPublicationService` | Shared locked version allocation, managed runtime projection, audit; no automatic activation |
| `admin/` additions | Admin upload/build/status/log/cancel operations, using existing authorization service |
| `images/`, `schemas/`, `dev/` | Trusted image recipes/catalog, released schemas, runnable acceptance tools and fixtures |

No interface pretends that DB + S3 + registry form a distributed transaction. The
coordinator is a durable state machine with idempotent steps and reconciliation. Never
hold a JDBC transaction, servlet thread or servlet authentication object for a build.
Persist the initiating actor and target explicitly for background work and audit.

### Manifest contract (R and Python Shiny are both required)

One gzip member containing one POSIX tar archive. Root `manifest.json` is the first
logical regular file; payload entries are under `app/`. Optional directory headers are
permitted subject to the same limits and duplicate rules. PAX support is narrowly
specified in the extractor contract below, not delegated implicitly to library defaults.

| Field | Format/version-1 meaning |
|---|---|
| `schema_version` | Required integer `1`; unknown versions fail before execution |
| `type` | Required registered content type; must equal the target `content.type` and be enabled by the reviewed recipe matrix |
| `runtime` | Required for executable source: `language` (`r` or `python`) and exact `version`; no `latest`, ranges or download URL |
| `entrypoint` | Recipe-specific validated path relative to `app/`. **Python Shiny names a regular `.py` file; R Shiny names a directory** (`.`, meaning the payload root, allowed here only). Never a shell command or import expression. A directory entrypoint is never itself an inventory member — see the resolution rule below |
| `dependencies` | Required `{format, path}`; `renv` + `renv.lock` for R, `pip-hashed` + `requirements.lock` for Python; semantics below |
| `files` | Required inventory of every regular payload file: relative `path`, integer `size`, lowercase hex SHA-256, boolean `executable` default false; excludes `manifest.json` |

No fields for content ID, owner, visibility, public path, version number, image, registry,
privilege, mounts, network, build flags or secrets. Those belong to authenticated requests
or server policy. All objects reject unknown keys. Reject duplicate JSON keys, invalid
UTF-8, non-integer sizes, overflow, deep nesting, duplicate inventory paths and manifest
type/runtime disagreement. Schema validation alone does not prove filesystem containment.
**Resolving the entrypoint against the inventory.** `files` lists regular payload files
only, so a directory entrypoint can never appear in it. Requiring every entrypoint to be an
inventory member would make a valid root-level R application unrepresentable — entrypoint
`.` with `app/app.R` and `app/renv.lock` — and the only way to satisfy it would be to put a
directory into a regular-file inventory, which this same table forbids. The rule is
therefore split by what the entrypoint denotes:

- **`dependencies.path`**, for both languages, names a regular file that must be present in
  the verified inventory. No exception.
- **A file entrypoint** (Python Shiny) must be present in the verified inventory and end in
  `.py`. An entrypoint that resolves to a directory is rejected for this language.
- **A directory entrypoint** (R Shiny) is validated *through* the inventory rather than by
  its own entry. It must be `.` or a contained relative directory path, and the inventory
  must carry, relative to it, either `app.R`, or **both** `ui.R` and `server.R` — the two
  layouts `shiny::runApp` accepts. Exact lower-case names: payload paths are
  case-preserving, case collisions are already rejected, and the runtime container is
  case-sensitive. An entrypoint that resolves to a regular file is rejected for this
  language.

**Implicit directories are sufficient, and are the expected case.** Directory headers are
optional in the archive, so a directory exists for validation purposes exactly when the
inventory contains a file beneath it. A directory header neither satisfies this rule nor is
required by it, and an entrypoint whose only evidence is a directory header with no
qualifying files beneath it is rejected. Do not add directory objects to `files` to make a
validator simpler; that changes an irreversible contract to avoid writing one predicate.

The first enabled branches are `shiny` + `r` + `renv` and `shiny` + `python` +
`pip-hashed`. Include Python Core and Express application fixtures; do not silently
mistake a Core-only import wrapper for general Shiny CLI behavior. A DB enum is not a
promise that a bundle type is supported. Other types return an explicit unsupported-type
error until their reviewed manifest branch/recipe exists. Future formats extend through
versioned schemas, not a permissive `additionalProperties` escape hatch. Do not ship
guessed Quarto/data/API field semantics that their owning tracks must later live with.

### Image references

`spec/image-references-v1.json` fixes the strings: the configured authority, the repository
`skald/content/<content-uuid>`, the per-attempt tag `build-<build-uuid>`, and the
digest-pinned execution reference stored in `content_version.image`. Decision 5 fixes the
policy; this fixes the grammar, because a reference that does not parse fails at the one
moment nothing is watching — when ContainerProxy tries to pull.

**The digest pin is a requirement, not a preference.** `DockerEngineBackend`'s default pull
policy is `IfNotPresent`, so a tag whose meaning changed is never re-fetched and a host keeps
running whatever it cached. The runner therefore checks the *negative* that matters: the
execution pattern must refuse a well-formed **tag** reference, and refuse an unpinned one with
no digest at all. Malformed input is the easy case; a perfectly valid tag reference is the one
that would quietly serve a stale image.

**The repository is keyed on identity, never on address.** A rename must not move an image,
and two content items must not share a repository — which is also what makes "retain every
published version of this content" expressible to registry GC. The tag is per *attempt*, so a
retry never overwrites the image of the attempt it retried; tags exist for humans and for GC,
which cannot see an untagged manifest as referenced, and are not how anything executes.

**One authority string, resolvable from both sides.** `localhost:5000` inside a rootless
builder is the builder's own loopback, and a Compose service name is not resolvable by the
host daemon unless the host is on that network. T5 and T7 prove a push *and a cold pull* with
the configured string, not two strings that each work in their own context.

**A bare name is not an authority**, and this is the trap with this repository's name on it.
Docker reads the first path component as a registry domain only when it contains `.` or `:`
or is exactly `localhost`; otherwise it is a repository path on Docker Hub. Verified against
Docker 27.1.2: `docker pull registry/skald/content/<uuid>@sha256:<digest>` answers *"pull
access denied … may require 'docker login'"*, and the configured registry is never contacted,
while `registry:5000/…` resolves the host as intended. The dev stack's Compose service is
named `registry`, so the bare form is the first string an implementer would reach for — and
the grammar refuses it, rather than leaving T5's cold-pull proof as the only thing that would
notice.

### Semantic validation, and what the schema deliberately does not do

JSON Schema decides the *shape* of a manifest. It cannot decide whether a path stays inside
the payload, whether the entrypoint resolves, whether the inventory describes the bytes that
actually arrived, or whether a `renv.lock` makes sense under a Python runtime. Those are the
semantic validator's, and `dev/fixtures/manifests/expectations.json` already classifies
eleven manifests as `semantic_invalid` — structurally valid documents the schema **must
accept**, recorded so that "the schema accepted it" reads as expected rather than as a hole.

This table gives every one of them an owning rule, and names the rules that have no fixture
yet so the gap is visible rather than implied. A rule without a case is a rule nobody has
watched fail.

`dev/schema-fixture-check.py` parses the **Fixture** column of this table and requires every
`semantic_invalid` fixture to be cited by a row whose first cell is a rule id. That is a
narrow parser of one known table, and it is deliberately not a substring search: the first
version asked whether a fixture's name appeared anywhere in this document, which stayed green
after every rule row was deleted so long as the names survived in a note saying they had no
rule. Editing the table therefore has consequences — deleting a row, or removing a rule's id,
fails the run, as does citing a fixture that does not exist or removing the table entirely.

| # | Rule | Rejects | Fixture |
|---|---|---|---|
| S1 | Every `files[].path` resolves inside the payload root after normalisation | `../escape.txt`, and anything whose real target escapes | `path-traversal` |
| S2 | No `.` or `..` segment, no empty segment, no trailing slash on a file | `a/./app.R` | `path-dot-segment` |
| S3 | Inventory paths are unique after NFC normalisation and case folding | a repeated path; `App.R` beside `app.R` | `duplicate-inventory-path`; case and NFC cases **owed** |
| S4 | A file entrypoint is a regular inventory member and matches its language's extension | Python naming a directory, or a path absent from `files` | `python-entrypoint-names-a-directory`, `python-entrypoint-absent` |
| S5 | A directory entrypoint resolves through the inventory: `app.R`, or both `ui.R` and `server.R`, beneath it | R naming a file; a directory with neither layout; `ui.R` alone | `r-entrypoint-names-a-file`, `r-directory-without-layout`, `r-only-ui-no-server` |
| S6 | `dependencies.path` is a regular inventory member | a lockfile named but not listed | `lockfile-absent-from-inventory` |
| S7 | `dependencies.format` agrees with `runtime.language` | `pip-hashed` under an R runtime, or `renv` under Python | `language-format-disagreement` |
| S8 | `type` is enabled by the recipe matrix, not merely registered | `quarto_static` before its recipe exists | `registered-but-unsupported-type` |
| S9 | `type` equals the target `content.type` | a `shiny` bundle uploaded to `plumber` content | **owed** — needs a target, so it belongs with T8's upload path |
| S10 | Each declared `size` and `sha256` matches the bytes actually extracted | a truthful-looking manifest describing different bytes | no manifest fixture: needs real archives. **Closed at T5** by the extractor (size from the header before any content is read, digest as it streams), held by the bundle corpus's inventory-size-mismatch and inventory-hash-mismatch |
| S11 | The inventory and the payload agree exactly: no extracted file missing from `files`, no listed file absent from the archive | a smuggled extra file, or a phantom entry | no manifest fixture: needs real archives. **Closed at T5** by the extractor (an undeclared file refused before it is written; declared-but-absent at the end), held by the bundle corpus's inventory-missing-file and inventory-extra-file |

S1 and S2 look like duplicates of the extraction contract's path rules and are not. Extraction
validates the *archive's* member names as they stream past; these validate the *manifest's*
claims. A bundle can carry a well-behaved archive and a lying inventory, and only one of the
two checks sees that. Both run, and neither is permitted to assume the other did.

**Ordering matters, and the reason is not performance.** Schema validation runs first, then
S1-S9 against the manifest alone, then extraction under the operator limits, then S10-S11
against what was extracted. Nothing is written outside the private extraction directory until
every one of them has passed, and no build is queued. The point is that S10 and S11 are the
only rules that require having read the payload, so everything cheap and everything hostile
is decided before a single byte of untrusted content is committed anywhere a later step could
mistake for validated.

**A rejection names its rule.** "S5: entrypoint `dashboards` contains neither `app.R` nor both
`ui.R` and `server.R`" is actionable by the publisher who wrote the manifest; "invalid bundle"
sends them to an administrator. This is the same reasoning as Q3's decision to surface a build
log rather than guess at a package name: say precisely what was observed, and do not
editorialise beyond it.

**What the semantic validator must not do.** It does not repair, normalise away or "helpfully"
accept anything in the list above — no stripping a `..`, no lower-casing a colliding path, no
inferring an entrypoint when none resolves. Every one of those turns a rejection into a
silently different bundle, which is how an uploader and a server come to disagree about what
was published. Reject, name the rule, and stop.

### Identifiers and lifecycles

`spec/lifecycle-v1.json` holds the bundle and build state machines and the meaning of every
identifier. It is the single source: T6's tables and CHECK constraints, the coordinator and
the admin API are written from it, and this document explains it rather than repeating it.
Restating a policy in two places is what produced finding `d4f5baf-F1`, where a retention
decision and its collector instructions disagreed for two commits.

It is **not** under `schemas/` and is not a JSON Schema. A document at a schema path is
loadable as a schema whether or not it is one, and one that is not becomes a validator that
accepts everything — which is finding `dd46cac-F1`, and is exactly the mistake that naming
tempts you into.

Nothing validates against it, which is precisely why it is machine-checked:
`dev/schema-fixture-check.py` refuses a state machine with an unreachable state, a terminal
state that has grown an outgoing edge, a state missing from the terminal list, a transition
to an undeclared state, or an unknown actor. Each of those is a coordinator that hangs or
loses an attempt, and none of them is visible by reading.

Three decisions in that file are worth defending here, because they are choices rather than
descriptions.

**`build_id` identifies an attempt, not a build of a bundle.** A retry is a new id carrying
`retry_of`, reusing the same immutable validated bundle. Reopening a terminal attempt would
mean its logs, its artifacts and its retention clock describe two different executions.

**Operator cancellation is refused once `PUBLISHING` has begun, and a deadline is not.** By
then a push may already have produced a digest, and a cancel that races publication is how an
image comes to exist with no row describing it. A deadline is different in kind: the worker's
lifetime bound covers publication too, because a push or a verification can stall after the
build has finished, so `PUBLISHING` has a `TIMED_OUT` outcome and the lease generation fences
a late worker out of inserting a version. Recovery from a refused cancellation is deleting the
*content* — the only delete the admin API has. There is no version-delete endpoint, and this
rationale does not invent one.

**`INTERRUPTED` is distinct from `FAILED`.** A failure is a build that ran and did not work;
an interruption is an attempt the platform lost — worker death, lease loss, a restart across
it — about which nothing is known, including how far it got. They are retained identically
but must be distinguishable, because reconciliation can conclude something about the second
that it cannot conclude about the first.

### Storage layout and completion protocol

`C`, `U`, `B`, `V`, `R` below mean content UUID, bundle UUID, build UUID, version UUID
and rendition UUID. They are not user-supplied object keys.

| Bucket role | Key |
|---|---|
| bundles (`skald-bundles` in dev) | `v1/content/C/bundles/U/bundle.tar.gz` |
| bundles | `v1/content/C/bundles/U/manifest.json`, `inventory.json`, `receipt.json` |
| logs (`skald-logs`) | `v1/content/C/builds/B/chunks/000000000001.jsonl` and subsequent sequences |
| logs | `v1/content/C/builds/B/index.json`, `final.json` |
| output (`skald-output`) | `v1/content/C/versions/V/renditions/R/files/<validated-relative-path>` |
| output | `v1/content/C/versions/V/renditions/R/descriptor.json` |

Bundle bytes are immutable once a receipt is committed. Receipt includes layout/schema
version, IDs, compressed SHA-256, byte counts and inventory digest. `receipt.json` is
written last; only a DB `VALIDATED` bundle may be built. Incomplete uploads and descriptors
do not make objects public. Use server-calculated SHA-256, not S3 ETag as a content hash.
Copy/re-extraction by a worker verifies the recorded digest again.

Logs are independent completed objects, not an uncompleted multipart upload. An index
advertises only persisted contiguous chunks; `final.json` includes last sequence, byte
count, build outcome and completeness/truncation flags. `index.json` is a mutable,
rebuildable progress hint, the explicit exception to immutable artifact data. The DB's
fenced committed cursor is authoritative while building; `final.json` is immutable after
completion. A single trusted log writer conditionally creates chunks and rejects an
attempt to replace different bytes at an existing sequence. Late-generation writes
cannot advance the committed cursor or publish completion. Readers may lag but cannot
observe a completion index pointing at absent chunks. Prove this with late-writer tests.

The **output layout is reserved and documented now**, with a descriptor round-trip test,
but rendering, “current rendition” publication and downloads belong to #5/#6/#8. The
descriptor has `layout_version`, C/V/R, content version number, producer kind/ID, creation
time and file list (relative path, size, SHA-256, media type). Files are written first,
descriptor last. R/Python helpers later follow authorized descriptors, not bucket listing
or a mutable object named `latest`. No DB output table or pins-compatible API is smuggled
into V2. Evidence: T4.

S3 roles are least-privilege, buckets private, credentials absent from worker payloads.
Transient scratch is permitted; it is quota-limited and disposable, never a filesystem
durability fallback. The platform reads/writes S3; a build does not receive bucket admin
credentials or arbitrary object keys. MinIO outage must be an explicit storage failure.

### V2 and lifecycle

`V2__bundles_and_builds.sql` is forward-only and tested on both a fresh schema and a
populated V1 database. Suggested tables/columns, finalized together in task 6:

- `bundle`: UUID, content FK, initiating actor, upload/idempotency information, status,
  raw object key and digest, validated manifest/inventory digest, byte counts, timestamps,
  validation/error code. Its states and their transitions are in `spec/lifecycle-v1.json`.
- `build`: UUID, content/bundle FKs, `retry_of`, initiating actor, idempotency key and input
  fingerprint, status, recipe/base/platform/cache descriptors, effective limits,
  lease owner/expiry/fencing generation, opaque worker handle, output digest, error code,
  log cursor/completeness, timestamps. Uniqueness makes completion/idempotency enforceable.
- `content_version`: nullable unique `build_id` link for managed versions; old direct-image
  rows remain valid. `image` stays NOT NULL; `spec_json` is server-generated metadata,
  not the uploaded manifest and never a deserialized unrestricted ProxySpec.
- `artifact`/`artifact_gc`: durable ledger/outbox of server-generated object/image refs,
  owning IDs, kind, digest, pin/retention and deletion state. Cleanup records survive
  content deletion; their denormalized subject UUID is not a cascading FK.

Build states and every legal transition are in `spec/lifecycle-v1.json`, not restated here.
A lost worker lease fences subsequent updates and publication from the old generation.

1. Admin admission checks authorization, quotas, media type and idempotency before reading
   significant bytes. A bounded upload/validation step stores a durable bundle or rejects
   it without scheduling execution. HTTP disconnect aborts partial upload work.
2. A short transaction creates/returns a build request. Same content/idempotency key plus
   same inputs returns the existing attempt; different inputs give 409. The key is scoped
   to the content item, as `spec/lifecycle-v1.json` (frozen in T1) defines it, not to the
   actor: anyone who may build that content may see its builds, so two of its publishers
   sharing a key get the same attempt or a 409, never a second execution.
3. A coordinator claims with a DB lease and fenced state transition. One running build
   globally initially; bounded queue. Enforced admission limits cannot wait until #9.
4. The driver restores verified input into a private workspace, runs the trusted recipe,
   persists logs and pushes the immutable image. No request thread waits for this.
5. Verify the pushed digest and recorded platform/configuration, and perform a bounded
   isolated startup/health probe. Publish a complete log descriptor. Then lock the content
   row, verify it still exists, allocate a version through the shared allocator, insert
   image/runtime metadata and unique build link, mark success and audit in **one** commit.
6. Explicit activation selects a successful version. Failed image pull or application
   startup cannot quietly change the active pointer. Activation does not rebuild.

Crash after push but before the transaction leaves an orphan image, never a half-version.
On restart reconcile DB records, worker labels/handles and artifact receipts. For a
`PUBLISHING` attempt, verify persisted results and finish exactly once if safe; otherwise
mark interrupted and retain diagnostics. Do not trust a stale worker callback. Retries
are new attempts. Simulate crashes at each boundary, including after DB commit before
HTTP reply. Evidence: T6/T9.

Deletion takes the same content-row coordination lock as admission/finalization, refuses
nonterminal builds, and retains the existing live-proxy refusal. Enqueue artifact cleanup
records in the content-deletion transaction before cascading owned metadata. Fence new
admission against deletion. Content path reservations remain untouched. Do not claim a DB
lock synchronizes ContainerProxy's in-memory proxy store; test the existing runtime race
separately and bring a discovered conflict back rather than invent a stronger guarantee.

### Extraction contract and independent fixture corpus

T2 supplies the fixture bytes, expected decisions and outside-root sentinels **before**
the extractor exists. Its builder/oracle must not import the extractor, path validator or
manifest validator. Use an independent tar producer plus hand-constructed header cases;
check fixture hashes and a machine-readable expectation table into `dev/fixtures/bundles/`.
The independent reviewer adds withheld cases at T5. A fixture generated through the same
normalizer being tested does not establish containment.

**Limits are operator configuration, not constants (Q3, decided 2026-09-17).** A laptop and
a build host do not want the same ceilings, so every bound is a configured property
with its documented default recorded beside it in `spec/extraction-limits-v1.json`, and the
extractor reads them rather than embedding them. Two consequences the implementation owes: a configured value is itself untrusted
input and is validated at startup — positive, within a sane absolute maximum, and refused
rather than silently clamped — and the defaults must appear in one place that the
documentation and the code agree on, or they will drift.

**The values are in `spec/extraction-limits-v1.json` and are deliberately not restated
here.** Ten bounds, each with its default, the absolute maximum past which the bound stops
meaning anything, and why that maximum is where it is. `ExtractionLimits` reads that file
from the classpath at startup instead of carrying constants, so the one place the code and
the documentation must agree on is a place neither of them copies;
`dev/schema-fixture-check.py` holds the corpus profile to the same file and fails if this
section starts restating a number again. Bounded parser memory is an invariant of the
implementation rather than a number an operator sets, and is recorded there as one. Check
totals against **actual streamed bytes** as well as declared sizes; arithmetic must be
overflow-safe. Limits apply to ignored metadata too.

Making them configurable does not make them optional. A deployment may raise a ceiling; it
may not remove one. The corpus in T2 therefore parameterises the boundary cases off the
configured values rather than hard-coding the entry cap, so that the N/N+1 pairs still
straddle the real limit after an operator changes it.

Accept only regular files and directories. Reject all symlinks and hardlinks, even ones
apparently pointing inside the tree; there is no build functionality requiring them in
the upload. Reject device nodes, FIFOs, sockets, sparse encodings, setuid/setgid/sticky
bits and unknown tar types. Strip ownership, timestamps, xattrs and ACL metadata; create
private directories and controlled file modes, with executability from the manifest.
Library defaults must not create files or preserve permissions behind the validator.

Paths must be relative slash-separated UTF-8 in NFC. Reject, rather than normalize away,
absolute/UNC/drive paths, backslashes, `.`/`..` segments, empty segments, control/NUL
characters, invalid encoding and aliases. A conventional trailing slash is allowed only
on a directory header and removed for duplicate detection. Case is preserved and case
collisions are rejected for portability. `%2e%2e` in an archive filename is literal text,
not URL-decoded; never apply a URL decoder during extraction or key creation.

"Control characters" means every Unicode Cc code point: U+0000–U+001F, DEL (U+007F) and
the C1 range U+0080–U+009F. C1 is the half a byte-level check misses, because in UTF-8 it
is `C2 80`–`C2 9F`. NEL (U+0085) is a line break to many log readers, and U+009B is a
terminal's CSI. The extractor, the manifest validator (the same parser) and the rendition
key builder all apply this one definition (`t5-e5e3071-F1`). The manifest schema's own
path pattern stops at U+001F. It is a frozen T1 contract and stays as it is: the semantic
layer refuses the rest, and it already refuses more than the schema does.

**Invisible characters are refused too** (`t5-e5e3071-F3`, widened by `5c5715b-F1`; the
rule was decided by the user on 2026-09-26). A name may not contain a code point that
renders as nothing: Unicode's Default_Ignorable_Code_Point set (zero-width characters,
variation selectors, the combining grapheme joiner, Hangul fillers, tag characters) and the
general categories Cf, Zl and Zp (bidi embeddings, overrides and isolates, BOM, interlinear
annotation, line and paragraph separators). `app/report<U+202E>ld.R` lists as
`app/reportR.dl`. Two names that differ only by a zero-width character look identical to
the operator reading an admin listing of code the platform is about to build and run.
Escaping at display time protects log lines, but not a publisher's own UI, a `tar -t` or an
operator's file manager, and the name persists into the image.

**The joiners follow the internet's domain-name rule** (RFC 5892 Appendix A, ContextJ).
ZWNJ and ZWJ are allowed directly after a virama, and ZWNJ is also allowed between two
Arabic-script letters that would otherwise join. So Persian and Indic file names keep
working, and a joiner used anywhere else is refused, including in emoji sequences. The
accepted cost: emoji built with joiners or with VS16 (a red heart) cannot appear in file
names. The rule is `publisher.names.InvisibleCharacters`, shared by the extractor, the
manifest validator (same parser) and the rendition key builder. Its tables are generated
from the Unicode 15.0.0 data files (`dev/unicode/generate_invisible_tables.py`, pinned by
SHA-256, `--check` verifies) and not taken from the JDK, so the refused set does not move
when the JDK is upgraded. Moving to a newer Unicode is a deliberate regeneration.

**Not covered, and not claimed:** homoglyphs. A Cyrillic `a`, a different space character
or U+2800 BRAILLE PATTERN BLANK are visible characters that look like others. That class is
open, and the tests pin all three as accepted, so the gap stays visible. Nor is IDNA's
script-mixing policy part of "the domain-name rule" here: ContextJ looks only at the code
point before a joiner. So `a<U+094D><ZWNJ>b` (a stray Devanagari virama on a Latin letter,
then a ZWNJ) is accepted, and it looks like `a<U+094D>b`. Narrow, since the stray virama is
itself visible, and recorded here so it is decided rather than accidental (804fae5 review
note N1).

Validate the **effective** path after any approved PAX `path` override. Reject global PAX,
linkpath, sparse/xattr/ACL overrides and unknown interpretation-changing extensions;
allowlisted innocuous metadata may be ignored under bounds. Accept a single bounded gzip
member and tar end marker; reject concatenated archives/members and non-padding trailing
data. Any library unable to expose these distinctions is unsuitable without an outer
validator; do not just document rejection that the API cannot enforce.

Extract into a newly created private directory, with no other writer. Every parent walk
and create must refuse links, with descriptor-relative/no-follow operations where
available; a lexical `normalize().startsWith(root)` is insufficient. A substituted or
symlinked root/parent must fail closed. If the platform cannot supply the chosen safe
filesystem primitives, reject startup/build admission on that platform. Freeze the
validated tree before handing it to a worker. Delete it on all failure/cancellation paths.

| Corpus group | Required cases and observable result |
|---|---|
| Positive controls | Minimal real R and Python Shiny apps; nested assets; spaces/Unicode; empty file; exact-limit files; legitimate executable bit; supported PAX filename. Correct bytes/hash/modes, runnable output. |
| Traversal/absolute paths | `../`, embedded traversal, `/etc/...`, Windows drive/UNC/backslash forms, dot/empty-segment aliases, PAX override. Reject; outside sentinels unchanged. |
| Links and parent substitution | Symlink file, symlink directory followed by child, chained links, hardlink target outside/inside, pre-existing symlinked parent/root, rename race during extraction. Reject without outside reads/writes. |
| Bombs and malformed sizes | Entry cap N/N+1; expanded cap N/N+1; tiny compressed huge expansion; huge PAX field; sparse claimed file; negative/overflow size; truncated gzip/tar; many empty entries; concatenated members. Reject within measured memory/time/disk bounds. |
| Paths and types | Segment/path/depth boundary pairs; invalid UTF-8/NUL/control; device/FIFO/socket; setuid/setgid/sticky. Reject with typed error; never materialize a device or privileged mode. |
| Duplicate/alias ordering | Duplicate regular entries, duplicate manifest, file→directory and directory→file, repeated directories, case/Unicode aliases, file before its conflicting parent. Reject; no last-entry-wins overwrite. |
| Manifest/inventory | Missing/late/duplicate manifest, duplicate JSON keys, unsupported schema/type/runtime, missing/extra file, hash/size mismatch, missing lockfile/entrypoint, remote `$ref`. Entrypoint kind mismatches both ways: R naming a file, Python naming a directory, an R directory with neither accepted layout, and one evidenced only by a directory header. No queued job and no network schema lookup. |

For every negative case assert both rejection **and** no version, no worker, bounded
temporary state and unchanged sentinels. Each case has a positive control proving the
harness can create/read the target it claims is protected. Deliberately remove a bound,
link check or duplicate guard and record the specific assertion that fails.

### Build sandbox and dependency network

This is a target contract requiring T3/T5 evidence, not a claim that existing Compose
already implements it. Current Compose has a shared dev network and a plain local
registry; attaching workers there would violate the contract.

Start with a pinned rootless BuildKit image and native snapshotter candidate to avoid a
blanket host device grant. The launcher owns a disposable worker workspace/volume, never
a host bind mount. UID 0 inside an inner user namespace must map to an unprivileged host
UID; record and test both identities. Package build steps run as a non-root image user.
No insecure entitlements, user-selected frontends, host network or process-sandbox disable.
Document all allowed syscalls/capabilities and why; rootless setup helpers must not be
assumed compatible with `no-new-privileges` until the actual configuration is tested.

Initial ceilings: 2 CPUs, 4 GiB RAM with bounded/no extra swap, 512 PIDs, 10 GiB build
scratch, 20-minute wall deadline, 32 MiB log output, one active worker, finite queue (10).
Each can be made stricter by the operator; no manifest can raise it. Cgroup limits include
the daemon and descendants; disk is a real quota, not a best-effort free-space check.
The external supervisor kills the worker and descendants on timeout/cancel, independently
of the servlet, BuildKit RPC connection or an untrusted process ignoring SIGTERM.

Network policy defaults deny. Workers are isolated from the dev/runtime service network
and from each other. Approved dependency traffic goes through an operator-controlled
egress proxy/mirror with a finite repository allowlist; no direct egress. Block host
gateway, RFC1918/link-local/metadata destinations, IPv6 equivalents, DNS rebinding and
redirect/CONNECT tricks. “Internet is allowed” is not this policy. Deny direct access to
Postgres, Keycloak, MinIO, platform management, registry admin, Docker Unix/TCP sockets
and canaries in other containers. Test numeric addresses as well as service names.

Registry/base/cache transport is a trusted control-plane operation with scoped credentials,
not an unauthenticated shared registry writable by dependency hooks. BuildKit registry
auth travels via its trusted session; it is not a build arg, environment variable or
mounted Docker config. T3 must demonstrate that an ExecOp cannot read session credentials,
use a reachable daemon API to escalate, or push/overwrite another build's artifacts. If
the selected rootless networking model cannot separate these capabilities, stop at Q2.
Do not advertise network containment on the strength of `--network none` while the actual
restore or exporter uses an untested network.

| Isolation assertion | Required live attempt and positive control (T3, repeated at T5/T10) |
|---|---|
| No daemon/host access | From a real dependency hook, attempt Unix and TCP Docker APIs, read/write host sentinels, mount, namespace entry and device creation. The trusted harness proves targets exist. |
| No lateral network | Reach active host and sibling-container HTTP canaries by DNS/IP, Docker bridge gateway, service ports, IPv4/IPv6 and rebinding/redirect variants. Trusted peer reaches them; job cannot. |
| Controlled dependency egress | Fetch an allowed package successfully; disallowed host/private-IP/redirect target cannot receive a canary payload. Record server-side receipts, not just curl's exit status. |
| Process isolation and cleanup | Try ptrace/signalling the worker daemon, fork children and orphan grandchildren, ignore TERM. No unauthorized control; timeout/cancel leaves zero job descendants and worker handles. |
| Bounded resources | Allocate memory, fork, burn CPU, fill disk and flood logs. Observe actual enforcement and a responsive platform/control request; never exhaust the host to prove the limit. |
| No credentials/shared data | Enumerate env/files/proc and attempt known credential canaries, previous build files/cache namespaces and other object keys; no disclosure or cross-job writes. |

These tests support a specific deployed configuration; they do not prove resistance to
all kernel vulnerabilities. Pin and patch the worker/runtime tools and record their
versions with evidence. Unsupported hosts fail admission, not silently fall back.

### Base images, recipes and runtime projection

`images/` contains reviewed Dockerfiles and a machine-readable catalog keyed by content
type, language, exact language version, architecture and recipe revision. Each entry
resolves to a base digest and fixed launcher. OS packages are installed while maintaining
these trusted bases, not by arbitrary uploaded commands. Record their provenance/licenses.

The delivery matrix, incorporating the user's R-and-Python requirement, is:

| Family | #2 responsibility | Later track |
|---|---|---|
| R / Shiny | Full locked-dependency build, non-root image, fixed launcher on `0.0.0.0:3838`, real app acceptance | #3 clients generate this manifest |
| Python / Shiny | Full hash-locked dependency build, non-root image, fixed Shiny launcher on `0.0.0.0:3838`, real app acceptance | #3 Python CLI generates this manifest |
| R / Plumber; Python / FastAPI | Versioned base/launcher contract and base smoke tests; executable bundle branches deferred | #7 sharing, identity headers and API-key serving |
| R / Quarto + R Markdown; Python / Quarto | Versioned render-base contract and tool/version smoke tests; no public rendition activation here | #5 rendering/output, #6 schedules |
| Data / pre-rendered output | No unnecessary image; reserved output descriptor contract | #5/#8 admission and serving |

Select at least one maintained exact R version and one maintained exact Python version
in T1, pin their base digests and package-manager versions, and test them on the actual
host architecture. Record exact Quarto versions for render bases too. Additional supported
language versions are additive catalog entries, not a reason to put `latest` in a manifest.

Python's `pip-hashed` contract is a complete transitive lock in `requirements.lock`, with
exact package versions and approved SHA-256 hashes. The server supplies repository and
pip options. Reject editable/VCS/URL requirements, recursive includes, index overrides,
unhashed dependencies and source distributions initially; restore hash-verified wheels
only. This avoids silently downloading unpinned PEP 517 build dependencies. A Python app
needing a source-only package gets a clear unsupported-dependency failure; adding source
build support requires its own fully pinned build-dependency contract. `shiny` itself is
part of the lock. The chosen trusted launcher must support both accepted application
styles without importing application code during validation. R uses `renv.lock`, an exact
R/base/renv toolchain and recorded package sources/checksums; start restoration with a
trusted `R --vanilla` invocation, not an uploaded bootstrap/profile in the platform.

Private sources requiring credentials are unsupported in #2. Permit no `renv.lock` URL
outside the egress policy. The initial R recipe rejects remote mutable VCS references and
local package dependencies; future local-dependency support must obey decision 7's full
input key. Public repository R source installation remains arbitrary untrusted code and
is part of the live sandbox acceptance. Include at least one such dependency hook in T3.

For managed images write a versioned **allowlisted** runtime description into `spec_json`
(contract version, recipe ID, port, fixed user and resource policy); provenance belongs
to the build record. Keep {} compatible for existing direct-image versions. Map only
server-owned values to `ContainerSpec`, including non-root user and actual CPU/memory
limits. Do not deserialize uploaded JSON to privileged/volume/network/SpEL properties.
Maintain stable spec objects and include the normalized runtime description in the cache
fingerprint. Test a runtime-field-only change invalidates it without breaking index views.

Run the built image both through the isolated readiness probe and through ContainerProxy.
Assert effective user, no host mounts/socket, enforced limits and HTTP behavior in the
real container. A passing BuildKit build does not prove a safe or runnable deployment.
If a runtime guarantee needs unsupported engine fields or a second override, report it
under ADR-0001 instead of labelling the build sandbox as a runtime guarantee.

### Status, logs, admin transport and cleanup

#2 adds admin-only endpoints under `/admin/content/{id}` for upload, build creation,
status/list, log replay/tail and cancellation. **The exact shapes are now fixed, in
`spec/admin-transport-v1.json`**, and are not restated here. `/__api__/v1`, API keys and
owner/editor publishing remain #3.

Three of those shapes are choices rather than transcription. **Upload is two steps** — a JSON
create that returns a bundle id, then a raw `PUT` of the bytes — so the binary endpoint
carries no metadata and stays a pure stream, and so the bundle exists in `UPLOADING` before
any byte arrives. **The upload is `PUT`, not `POST`**, because it is idempotent against one
bundle id — in the sense set out below, which is narrower than it first sounds. And **cancel is
`POST`, not `DELETE`** — cancelling removes nothing, and it can be refused, which `DELETE`
would make an odd thing to answer 409 to.

**`PUT` here means "never a second bundle and never rewritten bytes", not "send it again and
it works".** Once the receipt is committed the bytes are immutable and the bundle has left
`UPLOADING`, so a repeat is refused with 409 and its current state; a client whose response
was lost recovers by *reading* status, not by uploading again. Different bytes at the same id
get the same 409, because a bundle id names particular bytes a build may already have read —
replacement means a fresh id. The earlier wording promised a replay would "re-upload the same
bundle", which would have meant either rewriting an immutable object or reopening a terminal
state.

The CSRF rule is the one to read before adding an endpoint. ShinyProxy protects `POST /login`
alone, so "accepts no media type an HTML form can produce" *is* the defence for everything
here — and the obvious way to build a file upload, `multipart/form-data`, is exactly the one a
cross-site form can forge. `dev/schema-fixture-check.py` refuses any endpoint that accepts a
form-producible type, any endpoint outside `/admin`, an upload without its custom header, and
a `cancel_refused_states` entry that disagrees with the lifecycle. Shared services
take explicit actor/target/policy inputs so #3 does not need to copy the pipeline.

Upload is a bounded raw `application/gzip` body with a required custom upload header,
not an HTML multipart form or a caller-selected filesystem/object-store path. Existing
security only protects POST `/login` with CSRF: the JSON-only defense on old endpoints
does **not** automatically cover a new binary/stream endpoint. Reject form media types,
missing custom header and unauthorized CORS preflight. Test a hostile-origin browser
form/fetch and real session-authenticated positive upload. No new broad CORS permission.
If this needs changes to the global CSRF matcher, bring the concrete auth change back
for sign-off before implementing it.

Build creation returns 202 plus build ID/status URL; it never waits for image construction.
Status and paginated logs are usable over HTTP/curl before any UI exists. Provide cursor
replay plus SSE (or an equivalently reviewed streaming transport) backed by committed
chunks: sequence IDs, bounded payloads, keepalive, resume after disconnect, finite
subscriber queues and terminal event. Slow/disconnected clients do not block the build.
“Viewable” means an admin can read/tail an in-progress and failed build now, not a promise
of a future UI. Source/build logs are admin-only here; runtime viewers do not inherit
source/log access through `canAccess`.

Flush chunks at most every two seconds or 256 KiB. Escape logs as text, cap line lengths,
neutralize terminal controls in viewers, and do not interpolate them into HTML. On a
MinIO outage use only bounded scratch buffering and bounded retry; if durability cannot
be recovered, stop the build and report a storage/log failure rather than mark a silent
success. Reaching the log cap stops execution with a typed limit error and a truncation
marker. **Superseded for build logs by the user's T7 decision (2026-10-02): an oversized
build log keeps its head AND its tail, with a cut marker between, and the build goes on;
see T7, part 3d-2b.** Do not expose registry/S3 credentials in process arguments, audit details or logs.

Cleanup uses the artifact ledger, grace periods and repeatable mark/recheck/delete steps.

**Retention policy, decided in Q3 on 2026-09-17. This table is the only authoritative
statement; earlier proposals elsewhere in this document are superseded.** The earlier draft
said failed artifacts were collectable after 24 hours, which would have destroyed evidence
28 days before the decided policy allows, and let a successful build's logs live forever by
attaching them to the version. A collector cannot be written against two policies.

| Artifact | Retained | Clock starts |
|---|---|---|
| Incomplete upload (no committed receipt) | 1 hour | last write to the multipart upload |
| Bundle of a **published** version | until the content is deleted | — |
| Image of a **published** version | until the content is deleted | — |
| Artifacts of a `FAILED`, `CANCELLED`, `TIMED_OUT` or `INTERRUPTED` attempt | **30 days** | the attempt reached that terminal status |
| **Build logs, whatever the outcome** — `SUCCEEDED` and every terminal alternative alike | **90 days** | the attempt reached its terminal status |
| Unused dependency cache | 14 days | last cache hit |

**Logs are retained for 90 days regardless of how the build ended.** An earlier revision of
this table gave a failed attempt's logs 30 days so that they expired alongside its artifacts,
on the reasoning that a log outliving its artifacts might imply the build is recoverable.
That was a policy invented while implementing one, and it is exactly backwards: a failed
build's log is the *more* valuable of the two, because it is the only record of why the
failure happened, and nobody diagnoses a three-week-old failure from an image that was never
produced. The decision on record is 90 days for build logs and 30 days for failed-build
artifacts, and the two clocks are independent by design.

A published version's **image and bundle are pinned indefinitely** while its logs expire on
the same 90-day clock as any other build's: the artifacts are what rollback needs, the logs
are diagnostics, and treating them as one thing is what made the first draft retain logs
forever.

**Build** retention clocks start at a **terminal status**, never at creation, so a build that
runs for six hours cannot have its logs aged out from under it while it is still running.
That rule is about builds only — the multipart row is measured from its last write and the
cache row from its last hit, because neither has a terminal status to measure from.

The safety rule is not optional and is not a retention period: never collect retained
versions, live-proxy images, nonterminal attempts, leased work or in-flight publication,
whatever the table says. An expiry is permission to collect, not an instruction to collect
something still referenced. Recheck pins before destructive work and pause publication
during registry maintenance. Keep referenced digest tags so registry `delete-untagged`
cannot silently erase DB-referenced images. Never GC external/direct-image admin
repositories.

Registry manifest deletion and blob reclamation are different operations. The selected
registry's GC runs with writes stopped/read-only, with a dry-run report first. Object
deletion retries and worker/volume cleanup are idempotent; restart reconciles leftovers.
Test failed cleanup without losing ledger records, and test rollback after a real GC pass.

## Tasks

Order matters. T2's independently authored corpus precedes T3/T5 extraction work. T5 is
the mandatory independent security gate **before** wiring an upload to production builds;
it is performed by the reviewer agent, and T5 records both what that buys and what it
leaves owed to a human.
Every task records commands, versions, commit, assertions and actual outcomes; no checks
below are marked passed by this planning document.

- [x] **T0. Confirm scope and baseline.** Record Q1's confirmed R/Python scope, Q2's proof
      gate and Q3's proposed defaults; record explicit sign-offs where required. The actual
      Q2 deployment profile is decided after T3, not assumed here. Read current source,
      verify branch and ContainerProxy tag. Run `make dev`, `bash dev/smoke.sh`,
      `bash dev/acl-live.sh`, `make test`; retain output and actual totals. Capture V1's
      checksum and a populated V1 database fixture. Do not silently fix failures or copy
      prior totals into the new track. **Pass:** reproducible baseline plus decision log;
      unresolved locked-scope conflict stops dependent work.

      **Done 2026-09-17.** Every number below was produced by the run recorded here, not
      carried forward from spine #1's records.

      *Environment, as verified rather than assumed.*

      | Fact | Value | How |
      |---|---|---|
      | Branch / commit | `skald` @ `475651f606889945e55a67a3a83e05f491b753a8` | `git rev-parse` |
      | Engine source | `~/projects/containerproxy` tag `v1.2.4`, `0170bc5c0ef1948cdf8f18da598b8cf039d5523e` | `git -C … describe --tags` |
      | Engine dependency | `containerproxy.version` = `1.2.4` (`pom.xml:34`) — matches the source checkout | `grep` |
      | Docker Engine | 27.1.2 | `docker version` |
      | Kernel | 6.18.33.2-microsoft-standard-WSL2 | `uname -r` |
      | PostgreSQL | 16 (dev stack) | `docker-compose.dev.yml` |

      *V1 checksums, both forms, so a later accidental edit is detectable either way.*
      Flyway `skald.flyway_schema_history` records version 1 as checksum **`-1235024336`**,
      `success = t`, script `V1__content_registry.sql`. The file's SHA-256 is
      **`4d7ebe2d556b57f41a44d85e09fa031849c27318c3d01f066a293314a843b02f`**. V2 is additive;
      neither value may change.

      *Baseline runs.* `make dev` exit 0. `bash dev/smoke.sh` **47/47**, exit 0.
      `bash dev/acl-live.sh` **18/18**, exit 0. `make test` **138/138**, BUILD SUCCESS,
      exit 0. Nothing failed, so nothing was fixed.

      *State found, disclosed rather than tidied away.* The dev registry was not empty: one
      content row `e85071c6…` at path `host-probe`, left by the `Host`-header probe in the
      review of `475651f`. It is residue from that investigation, not product state; the
      suites clear content at start and did. Recorded because "the registry is empty at
      rest" is an invariant this project relies on, and a silent cleanup would have hidden
      that it had been broken.

      *Populated V1 fixture:* `dev/fixtures/v1-populated.sql`. Insert-only, no DDL, fixed
      UUIDs, valid before and after V2 exists. Verified by applying `V1__content_registry.sql`
      and then the fixture to a scratch database on the dev PostgreSQL (dropped afterwards):
      5 content, 7 versions, 5 live paths, 1 retired path, 2 orphaned reservations
      (`content_id IS NULL`), 4 ACL rows, 1 `content_env` row, 6 audit events, 1 content whose
      **active version is not its highest** and 1 content with **no version at all**. Those
      last two are the shapes a migration written against the happy path quietly breaks.

      *Q1 — CLOSED.* R and Python Shiny, both end to end, per the user's decision of
      2026-09-16. No change.

      *Q2 — OPEN, and T0 found two host facts that constrain the T3 proof. Neither is a
      relaxation; both are recorded so T3 measures rather than assumes.*
      - **AppArmor is not enabled** on this host (`/sys/module/apparmor/parameters/enabled`
        = `N`). The isolation profile therefore cannot claim AppArmor confinement here.
        seccomp is available (`Seccomp` present in `/proc/self/status`), user namespaces are
        (`user.max_user_namespaces` = 63771), and `gakro` has a subuid/subgid range
        (100000:65536), so rootless is viable — but by userns + seccomp + cgroups, and the
        eventual write-up must say so rather than listing AppArmor as if it applied.
      - **cgroup v2 is in use, but the user session has only `memory pids` delegated** —
        not `cpu`, not `io`
        (`/sys/fs/cgroup/user.slice/user-1000.slice/user@1000.service/cgroup.controllers`).
        The locked isolation contract requires bounded **CPU** as well as memory and PIDs, so
        as this host stands a rootless worker cannot enforce a CPU quota. T3 has to resolve
        that — a systemd `Delegate=` drop-in for `user@.service`, or a system-level slice the
        trusted launcher owns — and must not quietly drop the CPU bound to get a green.
        Finding it here costs a paragraph; finding it at T3 costs a redesign.

        > **Superseded 2026-09-18 by T3(a).** That cgroup path does not exist on this
        > host, and a worker launched through the rootful Docker daemon is bounded by the
        > daemon rather than by a user manager. CPU, memory, PIDs and disk are all
        > measured as enforced; see the T3(a) table and the Q2 gate row. The concern was
        > recorded honestly from what was visible then and is superseded by measurement,
        > not contradicted — it still stands for a *rootless* worker, which is a choice
        > this project has not yet made.

      *Q3 — proposed, NOT confirmed.* The limits in "Extraction contract" and the retention,
      repository and OS-package proposals are recorded as this track's starting profile and
      are **awaiting the user/operator decision the plan assigns to T0/T1**. T0 does not
      confirm them on the user's behalf. T1 cannot freeze the contract until they are
      confirmed, which is the stop condition, not a formality.

      *Q4 — no dependency added yet.* Commons Compress, networknt JSON Schema Validator and
      the AWS SDK v2 S3 client remain candidates only; exact versions and transitives get
      flagged with their licences at the point of addition, per CLAUDE.md.

      *No locked-scope conflict found.* The plan's "Existing behavior" table was re-checked
      against the source at `475651f` and matches, including `content_version.image NOT NULL`,
      the content-row lock in `addVersion`, `spec_json` being selected but unused, and the
      fixed port 3838 in `ContentSpecRepository`.

- [x] **T1. Review and freeze contracts — Opus 5.** Depends T0. Finalize schema v1,
      examples, supported type/language/lockfile matrix, output descriptor, IDs/states,
      admin transport and object/image layouts. Specify literal safe launch arguments
      and all semantic validators. Add schema validation fixtures, including future-version
      rejection and compatibility fixtures. Flag dependency licenses before adding them.
      **Pass:** producer examples for both languages from independent tooling validate;
      invalid examples fail the named rule; no unresolved one-way door is hidden in code.
      Entrypoint resolution is acceptance-tested in both directions, because the first draft
      of this contract made a valid R application unrepresentable (finding `00a1b2d-F1`):
      positives — root R app at `.`, nested R app, single-file `app.R` and two-file
      `ui.R` + `server.R`, each with and without optional directory headers, plus the Python
      file entrypoint as a control; negatives — R entrypoint whose directory has neither
      layout, R entrypoint naming a regular file, Python entrypoint naming a directory,
      entrypoint evidenced only by a directory header with no files beneath it, and a
      directory smuggled into `files`.

      **T1(a) done 2026-09-17 — the manifest contract only.** T1 covers several contracts;
      this is the first and most irreversible of them. Still open within T1: the output
      descriptor, ID/state vocabulary, admin transport, object/image layout wording, literal
      safe launch arguments, and the semantic validator specification.

      - `schemas/manifest/v1.schema.json` — authoritative and immutable, dialect 2020-12,
        `additionalProperties: false` throughout, `schema_version` pinned by `const`.
      - `schemas/manifest.schema.json` carries the **real schema**, byte-identical to
        `schemas/manifest/v1.schema.json`, and every fixture is validated through both with
        identical verdicts required.

        *An earlier revision of this commit put a non-schema "index" there and claimed the
        two requirements in decision 2 could not both hold. That was wrong, and instructive.*
        JSON Schema ignores unknown keywords, so the index **was** a schema — one that
        accepted `null`, `42`, `{}` and every malformed manifest. Anything loading the
        published path would have validated literally anything, and the runner's assertion
        that the file "must not be a schema" locked the bypass in. There is no tension to
        resolve: a published copy and immutable versioned documents coexist by being equal
        and checked for equality (finding `dd46cac-F1`).
      - 46 fixtures in `dev/fixtures/manifests/`, split three ways by
        `expectations.json`: **7 valid**, **28 schema-invalid**, **11 semantic-invalid**.
        The third group is the load-bearing one. Those manifests are structurally valid on
        purpose — `../escape.txt` in the inventory, a duplicate path, an R entrypoint naming
        a file, a Python entrypoint naming a directory, `renv` declared under a Python
        runtime — and the schema **must accept them**, because containment, inventory
        membership and entrypoint resolution are not expressible in JSON Schema. Recording
        them as expected-accepted is what stops a later reader mistaking the gap for a hole,
        and what keeps the debt visible for the semantic validator in T5.
      - `dev/validate-manifests.sh` runs the fixtures through **python `jsonschema` 4.26.0
        in a container** — deliberately not networknt, which the server will use. One
        implementation agreeing with itself is not evidence that a document says what it is
        meant to say. Result: all 40 behaved as specified.
      - **Patterns are anchored with `(?![\s\S])`, not `$`.** `$` does not mean end of
        string in either implementation this schema will meet: Python's `re` and
        `java.util.regex` both let it match immediately before a final newline. Appending one
        `U+000A` to `sha256`, `runtime.version`, `entrypoint`, `files[].path` or
        `dependencies.path` was therefore accepted — a **65-character SHA-256** passed, and
        paths carried the very control character their description says is rejected
        (finding `dd46cac-F2`). Six fixtures now cover trailing and leading newlines, and
        `sha256` additionally carries `minLength`/`maxLength` 64 so the digest is bounded
        even if a pattern is later loosened.
      - The runner cross-checks the raw patterns against **ECMA-262 via node**, because the
        anchors have to hold in the server's engine too and one implementation agreeing with
        itself proves nothing about the other.
      - Guards mutation-tested rather than assumed: restoring the bare `$` anchor makes
        `trailing-newline-in-file-path` and `-dependency-path` report "ACCEPTED by the
        schema"; putting the index back at the published path fails with "…differ; the
        published path could validate something the released version rejects".
      - Positive fixtures carry **real** sizes and SHA-256 values of the payload bytes they
        describe, so T2 can build archives from them rather than inventing a second set.
      - Q4: no Maven dependency added. networknt remains a candidate for the server side;
        the fixture runner is a container-only dev tool and ships nothing.

      **T1(b) done 2026-09-17 — the output descriptor.** Reserved and specified now, as this
      plan requires, while rendering, current-rendition publication and downloads stay with
      #5, #6 and #8. Nothing writes or reads a rendition yet.

      - `schemas/output-descriptor/v1.schema.json` carries the field list decision 3 fixes:
        `layout_version`, content/version/rendition UUIDs, the version's display number,
        producer kind and id, RFC 3339 creation time with a mandatory offset, and a file list
        of relative path, size, SHA-256 and media type. 25 fixtures — 4 valid, 21 invalid —
        including `producer.kind = render`, reserved for #6 so that adding it later is not a
        layout change, and an **empty** file list, since a render that legitimately produced
        nothing is a complete rendition and is not the same as an incomplete one, which has
        no descriptor at all.
      - **The input and output path rules are checked for drift.** `payloadPath` and
        `renditionPath` are duplicated rather than `$ref`'d across files — a released schema
        that resolves a reference into another file has a meaning that depends on what that
        file says later — so the runner asserts they agree on type, pattern and both bounds.
        An output path and an input path face the same hostile input and must not diverge by
        accident.
      - **Names needing URL encoding are pinned**: spaces, `#`, a literal `%`, a literal
        `%25`, accented and Japanese characters, `+`, `&` and an apostrophe. The descriptor
        carries them literally, so that whatever encodes them for an S3 key or a URL does it
        at one boundary rather than inheriting something already mangled.
      - The ECMA-262 cross-check now covers the descriptor's UUID, timestamp, media-type and
        path patterns too, and rejects **CR and CRLF** as well as LF — a value from a
        Windows-authored file carries those, and `$` is not the only lenient anchor.
      - **`format: date-time` is enforced as an assertion, and the runner refuses to start
        without the checker.** Two separate things have to be true and neither is a default:
        in JSON Schema 2020-12 `format` is an *annotation* unless a validator is told to
        assert, and in this library being told is not enough — `date-time` only exists in the
        checker registry when a date-time implementation is installed alongside. With
        `pip install jsonschema` alone, passing `FORMAT_CHECKER` silently checks nothing.
        Month 99, **February 30th**, hour 99 and offset `+99:99` all validated against a
        schema advertising RFC 3339 (finding `c225d50-F1`). Six impossible-value fixtures and
        three positive controls — a leap day, a negative offset, fractional seconds — now
        cover it, because "rejects everything" and "works" look identical without them.
      - **The server-side validator inherits this requirement and must be proved, not
        assumed.** networknt follows the same 2020-12 default, so reading this schema is not
        enough to enforce the timestamp. Run the `created-at-impossible-*` fixtures against it
        when it lands. Not verified here: no Java validator exists in the tree yet.
      - Guards mutation-tested: widening `renditionPath.maxLength` reports the drift;
        percent-encoding one fixture filename reports the mismatch against the stated
        expectation; stripping `type`/`required` from the descriptor schema trips the vacuity
        probe on `null`, `42` and `{}`; removing `rfc3339-validator` from the container aborts
        with "format checker(s) not installed: date-time" and exit 2 rather than passing; and
        keeping the package but not passing the checker to the validator reports every
        `created-at-impossible-*` case as "ACCEPTED by the schema". Both halves fail
        independently, which is the property that matters — a missing dependency must not be
        able to quietly restore the green this once had.
      - Q4: `jsonschema==4.26.0` and `rfc3339-validator==0.1.4`, both MIT, both container-only
        dev tools pinned in `dev/validate-manifests.sh`. Neither ships in the jar and neither
        is a Maven dependency.
      - The round-trip check compares the parsed fixture against `url_encoding_expected_paths`
        in `expectations.json`, not against its own re-serialisation. An earlier draft did the
        latter, which is true whatever the fixture contains — a check that cannot fail is
        worse than no check, and this project has shipped two of those already.
      - The two fixture runners moved out of shell heredocs into `dev/schema-fixture-check.py`
        and `dev/schema-regex-check.js`, so `$schema`, `$defs` and `$ref` are not exposed to
        shell expansion and each can be read on its own.

      **T1(d) and T1(e) FROZEN 2026-09-18 by the user, as proposed and unchanged.**
      `spec/lifecycle-v1.json` (the ID/state vocabulary) and `spec/admin-transport-v1.json`
      (the nine admin endpoints) are public API shapes, so `CLAUDE.md` puts them to the user
      rather than to this plan. Both were taken as written. What freezing them commits to,
      stated here because a later reader will meet the consequences before the rationale:

      - Cancellation is **refused** once a build reaches `PUBLISHING`, and the recovery is
        deleting the *content*. **A version-delete endpoint was offered and declined**: it
        would have to prove nothing is running the version and nothing references its image,
        which is the live-proxy cleanup race this track deliberately does not open. It
        remains available to a later track that brings the machinery for it.
      - A **deadline** is not refused there, so `PUBLISHING` has a `TIMED_OUT` outcome and
        lease generation fences a late worker out of inserting a `content_version`.
      - `INTERRUPTED` stays distinct from `FAILED`, because reconciliation can conclude
        things about a build that ran and broke which it cannot conclude about one the
        platform lost.
      - The admin transport's CSRF defence is that **no endpoint accepts a media type an
        HTML form can produce** — ShinyProxy protects `POST /login` alone — so upload is a
        raw `application/gzip` `PUT` with a required `X-Skald-Upload` header and explicitly
        not `multipart/form-data`.

      These are now the source T6's tables and CHECK constraints, the coordinator and T8's
      controllers are written from. Changing them after this point is a migration and a
      client break, not an edit.

      **T1(g) done 2026-09-18 — the literal safe launch arguments. T1 is complete.**
      `spec/isolation-profile-v1.json`. The last item T1 owed, unblocked by the Q2 decision
      of the same day.

      - **Nine bounds, each carrying its literal argument and the probe that proves it** —
        `--cpus`, `--memory`, `--pids-limit`, `--security-opt=no-new-privileges`,
        `--read-only`, a `noexec,nosuid,nodev` sized tmpfs, the loop-backed workspace
        volume, the network, and `--security-opt=seccomp`. Thirteen forbidden arguments are
        listed **literally** — `--privileged`, `--cap-add`, the three `unconfined`
        security-opts, the four `host` namespaces, `--device`, the Docker socket bind and
        `--oci-worker-no-process-sandbox` — so a driver can be checked against them
        mechanically instead of being read for intent.
      - **The correspondence with `dev/sandbox-probe.py` is checked in both directions**,
        by parsing the probe with `ast` rather than trusting a list. A bound whose probe
        does not exist fails; a probe no bound claims fails. One direction alone is what
        lets a bound be written down and never measured — which is the entire failure this
        file exists to prevent, since a launch argument nobody measured is a sentence.
      - **A name the probe records as a FACT may not be a bound's proof.** This is finding
        `7b6e931-F2` made permanent instead of fixed once: AppArmor is unavailable here and
        `--storage-opt size` is refused outright, and both are recorded as facts. A profile
        citing either as proof of an enforced bound now fails the runner.
      - **The seam's refuse-to-start rule is machine-checked, not prose.** A runtime is
        selectable only if its status is `measured` and it enforces every bound. Today that
        is `runc-rootful` alone — and the file therefore says out loud that **the default,
        `runc-rootless`, is unmeasured and not selectable**, with the three things it owes
        listed against it. `runsc` is a candidate; `kata` is marked unavailable on this host
        because there is no `/dev/kvm`. Recording the intention as though it were the
        measurement is precisely the move this gate exists to refuse.
      - Mutation-tested, **fifteen mutations, every one caught by the branch it was aimed
        at**: a dropped bound, a probe that does not exist, a fact cited as proof, two
        bounds sharing a probe, an unmeasured runtime marked selectable, a selectable
        runtime missing a bound, an emptied forbidden list, a forbidden argument used as a
        bound, a deleted profile, emptied runtimes, emptied bounds, a runtime enforcing
        something unmeasured, a broken probe parser, and no selectable runtime at all. The
        last needed its own case: the obvious mutation for it was caught by a *different*
        check, which is the f1078e5-F1 lesson — a mutation caught for the wrong reason
        leaves the branch it was meant to cover untested.
      - The forbidden-argument check was itself wrong on first write: it compared the flag
        *name* before `=`, so `--security-opt=seccomp=unconfined` reduced to
        `--security-opt` and failed five times against a correct profile. It now compares
        whole flags.
      - `bash dev/validate-sandbox.sh` re-run on the host the same day rather than quoted
        from T3(a): **9 of 9 bounds, 2 facts**, docker 27.1.2. The `measured` status in the
        file is that run, not a recollection of one.
      - **Corrected before T1 could be relied on (`0164896-F1`, `0164896-F2`).** The first
        version of this file matched a bound to a probe by NAME only, and the sentence
        above — "each carrying its literal argument and the probe that proves it" — was
        therefore false for two of the nine. A bound could specify `--cpu-shares`, a
        relative weight that bounds nothing on an idle host, and be reported green as
        "proved by a distinct probe"; and two bounds in the shipping file were already
        wrong that way. The tmpfs bound specified `noexec,nosuid,nodev` while the probe
        measured only the size cap — on the bound whose own rationale is "noexec is not
        decoration". The seccomp bound was proved by `docker info` reporting that the
        **daemon supports** seccomp, a string that does not change when a worker runs
        `seccomp=unconfined`, which is the "flag-reading isolation test" decision 6 rejects
        by name. Both are now measured on the worker: noexec demonstrated behaviourally
        with the mount options read back from `/proc/mounts`, and seccomp shown as
        `Seccomp: 2` plus a denied syscall against an unconfined control at `Seccomp: 0`.
        The checker now compares the **literal argument** in both directions, so an option
        dropped from the profile fails as surely as one the probe never ran. Eighteen
        mutations, each caught by its own branch.

- [x] **T2. Author the adversarial corpus independently — before extractor code.**
      Depends T1. Implement the corpus/oracle and external sentinel harness described above
      without production validation helpers. Store fixture generator, hashes and expectations.
      Test the oracle against a deliberately unsafe disposable extractor and a deliberately
      unbounded variant under outer test limits. **Pass:** the oracle detects outside-root
      changes, duplicate overwrite, limit bypass and unexpected acceptance; it is not a
      collection of negative fixtures that would “pass” because nothing runs.

      **T2(a) done 2026-09-17 — the corpus and its self-check.** The oracle, the sentinels
      and the deliberately unsafe extractor are T2(b) and are what the Pass line above
      actually turns on; this is the material they will be pointed at. Counts below are as
      of 2026-09-23: 91 fixtures, 20 accepted, 80 predicates, 18 hostile properties.

      - `dev/fixtures/bundles/generate.py` builds **91 fixtures** across all seven required
        groups: 15 positive controls, 10 traversal, 6 links, 20 bombs, 13 types and path
        limits, 8 duplicate/alias, 19 manifest and inventory. The fifteenth positive is
        `pos-leading-dir-header`, added at T5 after finding e670073-F1: a directory header
        before the manifest, which the contract permits and which nothing here covered. Standard library only, no
        import of any Skald class, and hand-written tar headers wherever a polite writer
        would refuse — a negative size, a NUL inside a name, a bad checksum, a GNU sparse
        member, an undefined typeflag. A corpus built solely with a well-behaved library
        omits exactly the cases worth having.
      - Seventeen fixtures are marked **accept**, not fourteen: three boundary halves live
        in the bombs group because that is where their pair is. The suite keys the hostile
        sweep on the fixture's expected decision rather than on its group name, so a
        boundary half cannot escape it by being filed elsewhere.
      - **Archives are generated, not checked in.** The plan asks for the generator, hashes
        and expectations; storing 3.4 MB of bombs as well would add a second place for the
        corpus to drift. Every SHA-256 is recorded and regeneration is byte-identical, so a
        generator change that alters a fixture fails loudly instead of redefining the test.
      - Boundary fixtures **and the predicates that police them** are parameterised off the
        configured limits, so an N/N+1 pair still straddles the real value after an operator
        changes one. Verified by reconfiguring all six limits at once (entries 20000→500,
        expanded 2 GiB→64 MiB, per-file 512 MiB→16 MiB, path 1024→256, segment 255→64,
        depth 32→8) and re-running the whole suite green.
      - `dev/bundle-corpus-check.py` asserts **every negative fixture exhibits the property
        its name claims**, with one predicate per fixture rather than per group. This is the
        part that matters: a corpus of negatives that nothing runs against reports itself as
        complete forever, and the way it rots is a fixture quietly becoming benign while
        still being counted. Accepted fixtures are checked to exhibit **none** of fifteen
        hostile properties, so "rejects everything" stays distinguishable from "works".
        Eleven of the eighteen are about size and shape against the configured limits.
      - A sweep can only say what a fixture is **not**, so nine accepted fixtures also
        carry a predicate of their own: each boundary half proves it sits *at* its limit,
        and the PAX control proves its override resolves to a member really present. They
        are marked `pins` in the generator and the checker refuses to run without a
        predicate for each, so a future boundary half cannot be added without one.
      - Its tar reader is hand-written rather than `tarfile`-based, because several fixtures
        are malformed on purpose and a library that refuses to parse them would leave
        precisely those uninspectable. It walks a **stream**: a member declaring two
        gigabytes has its header read and its payload skipped at constant memory, so the
        total expanded size and any later oversized member are still measurable. A bounded
        in-memory prefix is kept alongside it for the questions that are about raw bytes.
      - Mutation-tested: a traversal fixture made benign, a symlink added to a positive
        control, the setuid fixture losing its bit, the case-alias fixture ceasing to
        collide, a fixture's bytes changing without its hash, an at-limit control pushed
        over the limit, an over-limit negative pulled inside it, an accepted fixture
        shipping a file its manifest never declares, the streaming walker reverted to a
        bounded buffer, each boundary half shrunk until it straddles nothing, a PAX path
        record emptied and then pointed at a member that does not exist, and a pinning
        fixture stripped of its predicate. Each reported and failed.

      Two defects in the checker were found by the checker, both of the class this project
      keeps meeting. Its tar walker could not resolve GNU long names, so every fixture whose
      point is a long path was being inspected through a `././@LongLink` placeholder — which
      first surfaced as a *positive* control tripping the traversal check, because the
      placeholder contains `/./`. And the decompression bound discarded its buffer, so the
      expansion bombs' headers were invisible and `bomb-expanded-over-limit` appeared not to
      exhibit its own property.

      Review of 29857f7 found four more, and they share a single shape: **the checker had no
      predicate for the limits, so nothing policed the fixtures that are only about limits.**
      The one at-limit positive control was four bytes over the segment cap and invisible;
      three predicates compared against a literal rather than the configured limit, one of
      them against the 100-byte tar name field instead of `max_path_bytes`; and several
      required cases were absent, including any positive control with a PAX filename, which
      let an extractor that rejects every PAX header pass the whole corpus. The corrections
      are in the commit that references those findings. Review of that commit then found
      the mirror image, 3438045-F1: four accepted fixtures were swept for hostile
      properties but asserted nothing, so a boundary half could stop straddling its limit
      in silence — the same pair failing to pin anything, from the other side. The lesson
      is recorded here because it recurs: a checker inherits the blind spots of whoever
      wrote the thing it checks.

      **T2(b) in progress — the bounds, then the oracle.**

      - **Every documented bound is now configuration and every one has a fixture**
        (2026-09-18). Three were missing from `LIMITS`: 256 MiB compressed, 64 KiB per
        extended header, and the 60-second extraction deadline. Two of them had already
        produced the defect this corpus keeps finding — `bomb-huge-pax-field` compared
        against a literal `64 * 1024`, and `manifest-over-limit` against a literal 4 MiB
        with a 60000-entry fixture that a raised cap would have made benign while the
        predicate still called it covered. The deadline is not expressible in archive
        bytes; it lives in `LIMITS` because Q3 requires one home for the defaults, and the
        oracle meters it.
      - Four fixtures: compressed cap N/N+1 and extended-header cap N/N+1, all four
        parameterised. The compressed pair is the expensive one — 256 MiB of deterministic
        incompressible payload each — and it takes the suite from 47s to ~73s. Worth it:
        the compressed cap is the first bound anything hits, checked on the uploaded byte
        count before a header is parsed, and it was the only documented bound with no
        fixture at all.
      - **Nothing holds an archive.** Both sides stream: the generator writes the tar body
        to a temp file and gzips it to disk in chunks, and the checker hashes and walks
        from the file. The only payloads kept are the two that *are* content — PAX records
        and the manifest — and both are read by the walker rather than out of a
        fixed-size decompression prefix, so no size question depends on how much of a
        payload the checker chose to keep. Peak resident memory for the whole suite is **69 MiB**, against
        924 MiB in the checker and 2094 MiB in the generator when the pair was first
        added. The suite prints its own peak, including the generator's, so the next
        fixture that buffers something shows up there rather than in a CI runner being
        killed. Disk is the remaining cost: a generation writes 518 MiB into a temporary
        directory.
      - Hitting the cap **exactly** needs two levers. The tar body only moves in 512-byte
        blocks and deflate's output on incompressible data jitters a few bytes either side
        of the trend, so a search on payload size oscillates and never lands (measured:
        consecutive sizes gave 268435459, 268435461, 268435460). The remainder is taken up
        by the gzip member's stored original filename, which is outside the deflate stream
        and costs exactly its own length. The N+1 half is the same payload with one more
        byte of filename, which is what makes it a true pair.

      - **The outside-root sentinels** (2026-09-18), `dev/bundle_sentinels.py` and
        `dev/validate-sentinels.sh`. A disposable world — a private extraction root, a
        pre-created file where a relative traversal lands, a symlinked root, an ordinary
        sibling — plus a snapshot/diff over it and over the absolute targets the corpus
        actually names (`/etc/passwd`, `/etc/skald-escape.txt`, `/tmp/escape.txt`). No
        extractor is involved; it photographs the world, lets something else run, and
        photographs it again.
      - The suite's job is to prove the harness **can fail**. It performs each change it
        claims to detect — overwrite, create, delete, re-mode, retarget a symlink,
        replace a file with a directory, write through a symlinked root, and rewrite a
        file in place at the same size with mtime restored — and asserts the diff reports
        it. Three things it refuses to fake: a walk that hits its bound raises instead of
        returning a shorter snapshot; a sentinel target this process cannot write to is
        reported WEAK and fails the run, because watching an unwritable path reports
        "unchanged" forever; and read detection is *measured* rather than claimed.
      - Two of those came from review (6d7b790-F1/F2) and are worth keeping in mind for
        anything else that watches a filesystem. **ctime is the timestamp a writer cannot
        set**: above the hash limit, mtime can be restored after a same-size overwrite,
        and ctime is what survives it. And **taking the picture is itself an access** —
        hashing a file and listing a directory both move atime, so each entry records
        where the snapshot's own access left the clock, and a comparison runs "after the
        earlier walk" against "before the later walk".
      - Read detection is honestly unavailable. `read_detection()` asks the question that
        actually decides it — does a read move atime when atime is *already recent*? —
        and the answer under the default relatime is no, because the snapshot's own read
        consumes the single update. The harness prints the regime and the self-test
        asserts the blindness rather than papering over it. Verifying "reject without
        outside reads" needs syscall tracing, which belongs with the extractor.
      - It therefore runs as root in a disposable container with `--network none` and the
        repository mounted read-only. Unprivileged, the same suite exits 1 with "2
        sentinel target(s) this process cannot touch, so watching them proves nothing" —
        verified both ways.

      - **The oracle and the disposable extractor** (2026-09-18).
        `dev/fixtures/bundles/disposable_extractor.py` is test scaffolding — clearly
        labelled, standard library only, no shared code with the corpus checker, because
        the checker is the inspector and this is the subject. It has seven removable
        guards (`--without path|types|duplicates|limits|framing|pax|manifest`), plus
        `--unsafe`, which is `tarfile.extractall(filter="fully_trusted")` and really does
        write to `/etc` and through symlinks.
      - The corpus immediately earned its keep: a tarfile-only extractor **accepts**
        `bomb-bad-checksum` and `bomb-declared-size-negative` (tarfile reads an
        unparseable header as the end of the archive and returns cleanly — "a lenient
        parser may skip past into attacker-chosen bytes", silently, as an accept),
        accepts `bomb-huge-pax-field` (tarfile never reports an extended header's own
        size), accepts `trav-pax-override` (it does not apply that override, so the
        *effective* path is never checked) and crashes on `path-invalid-utf8`. That is
        the plan's "any library unable to expose these distinctions is unsuitable without
        an outer validator", demonstrated rather than assumed — the extractor now walks
        the raw 512-byte headers itself before tarfile sees them.
      - `dev/bundle-oracle.py` extracts every fixture for real, in a fresh world, and
        judges more than the return code: the decision matches the corpus, a **crash is
        never a rejection**, nothing outside the root moved, a rejected bundle left an
        empty root, an accepted bundle produced its declared inventory byte for byte with
        no extra files and no privileged modes, and the run stayed inside an outer
        deadline and disk budget. It imports no extractor — the extractor is a subprocess
        named on the command line, so the same oracle judges the real one at T5.
      - It runs a **reduced limit profile** by default (`generate.py --limit k=v`, which
        refuses to write expectations so the committed ones can only describe the
        documented defaults). At the defaults the bombs alone write about three gigabytes
        per pass; the boundary properties are parameterised, so they hold at any profile.
        `--full` uses the defaults.

      - **The Pass line is met and policed** (2026-09-18). `dev/bundle-oracle-matrix.py`
        and `dev/validate-oracle-matrix.sh` hand the oracle twenty-one subjects, each
        wrong in one specific way, and assert *which* finding comes back on *which*
        fixture — not that something failed, which an oracle failing everything for the
        wrong reason would satisfy. ~87s.
      - The four classes the Pass line names, each with the subject that demonstrates it:
        outside-root change (an extractor whose *decisions are all correct* and which
        plants one file outside the root — a decision-only oracle passes it), duplicate
        overwrite, limit bypass, and unexpected acceptance. Plus residue after rejection,
        unexpected *rejection*, and the outer deadline.
      - The most useful row is "rejects everything": it passes **71 of 90**, and the 19
        it fails are exactly the accepted fixtures. The positive controls are the only
        thing between a do-nothing extractor and a clean report.
      - **Removing one bound is usually masked by another guard.** With only `--without
        limits`, the inventory check rejects most bombs first — a bomb that ships an
        undeclared file is caught as inventory-extra-file long before any cap is
        consulted; `dup-regular` is likewise caught by the manifest hash check, not by
        the duplicate guard. Good news about layering, bad news for single-guard
        matrices, and the reason the limit-bypass and duplicate-overwrite scenarios
        remove two guards: to isolate the guard under test, not to inflate the numbers.
      - **Coverage is by construction, not by enumeration.** The oracle declares the
        thirteen finding kinds it can emit in one list and asserts it never emits an
        undeclared one; the matrix compares that list against the kinds its own
        scenarios assert and fails on any it does not police. Three commits in a row
        were corrected for "a check that nothing checks" (64f5dd8-F1, 3e23732-F1), each
        fix closing the kinds that had been *named* and leaving the ones nobody had
        counted — the enumeration was the defect. A kind added later now fails the suite
        the day it is added.
      - The dangerous omission was `crash`: under `--unsafe` the oracle sees nine crashes
        on fixtures the corpus expects to be *rejected*, so an oracle that folded crash
        into rejection would have turned all nine into silent passes with nothing
        objecting.
      - The matrix is mutation-tested against the oracle, which is the only way a suite
        asserting that another suite can fail is itself not vacuous. Neutering the
        outside-root detection fails three scenarios; the residue, crash, disk-budget,
        no-verdict, extra-output, wrong-output and privileged-mode checks each fail
        exactly their own; and declaring a kind with no scenario behind it fails the
        coverage check.

      - **A symlinked root fails closed** (2026-09-18). `--symlinked-root` points the
        subject at `<world>/via-symlink` instead of the real root, and every fixture —
        positives included — must then be rejected. This is not expressible as a fixture:
        every path check passes and every member still lands wherever the link points.
        The guarded extractor got it wrong until this was measured — extracting
        `pos-r-root` into the symlinked root wrote three files into
        `via-symlink-target` and reported **accept**. It now refuses the root up front,
        and `O_NOFOLLOW` covers the root itself and not only the components below it.

      - **The rename race** (2026-09-18), T2's last case. `--rename-race` swaps a
        directory inside the root for a symlink pointing out of it, over and over, while
        the subject writes 400 files into it — the classic time-of-check/time-of-use:
        every path the extractor validated was fine, and by the time it opens the parent
        the parent is a link. Under a race **either decision is acceptable** — refusing
        is correct and finishing is correct — so only containment is judged.
      - A negative result from a race is worth nothing on its own, so two things back it:
        a run where the racer never swapped **anywhere** is a failure with its own kind,
        `race-not-raced`, and the same race against `--without path` must escape. It
        does, 32 outside-root changes over 8 repeats, stable across five consecutive
        suite runs.
      - The racer gets exactly **one swap per repeat**, and the reason is the result:
        the guarded extractor fails closed on the first swap it meets
        (`parent-not-a-directory`), which ends the extraction. Eight repeats are
        therefore eight genuine encounters, each survived — and the vulnerable variant
        escapes on that same single swap, which is what calibrates the window as wide
        enough to matter.
      - Finding the racer's own bug is the point worth keeping: it first raced
        `<root>/www` only, and an extractor with the path guard removed does not strip
        the payload root, so it writes `<root>/app/www`. The *vulnerable* variant was
        never raced at all, and its clean run read as a pass. It now races both spellings.

      **T2 is done.** One thing in the plan's links row is not verifiable and is not
      claimed: "reject without outside **reads**". Reads are only observable through
      atime, and the default relatime mount makes them invisible once the harness has
      taken its own picture — the sentinels measure and report this rather than asserting
      silence. Verifying it needs syscall tracing of the extractor, which belongs with
      the extractor at T5.

- [~] **T3. Prove the sandbox on the actual Docker host — Opus 5 leads.** Depends T0/T2.
      Pin a rootless BuildKit candidate and prototype only the launcher/worker contract,
      quota enforcement, egress gateway and trusted registry transport. Exercise malicious
      code in a real dependency-install hook, not merely a sibling test container. Include
      a second worker/canary despite normal concurrency one. Run every isolation attempt
      above and an allowed package build/push as positive controls. **Pass:** measured
      containment and cleanup plus the exact deployment profile. If it requires a weakened
      invariant, stop and present the failed probe and alternatives for Q2 sign-off.

      **T3(a) done 2026-09-18 — what this host can actually enforce.**
      `dev/sandbox-probe.py` and `dev/validate-sandbox.sh`. **Nine bounds**, each
      exercised against a container that tries to exceed it and failing the run if it is
      not demonstrably enforced, plus **two recorded facts** about what the host offers.
      The two are counted separately: "AppArmor is not available" is not an enforced
      bound however prominently the detail line says so. It runs on the host, not in a
      container, because the host is the subject.

      | Bound | Measured |
      |---|---|
      | CPU | `--cpus=0.5` → 0.51 cores, `--cpus=2` → 2.01, unrestricted → 3.99 |
      | Memory | 256 MiB allocated under a 64 MiB limit → exit 137 (SIGKILL) |
      | PIDs | `--pids-limit=32` → fork blocked at 30 |
      | no-new-privileges | `NoNewPrivs: 1` with the flag, `0` without |
      | Read-only rootfs | writes to `/root` and `/tmp` both refused |
      | Sized tmpfs | 200 MiB asked, 64 MiB written |
      | Loop-backed volume | 200 MiB asked into a 64 MiB volume, 53 MiB written |
      | Network | `--network none` → outbound connect refused |
      | seccomp | `name=seccomp,profile=builtin` |

      **Q2 — the CPU worry T0 raised does not apply, and the disk one is solved.**
      - T0 recorded that the user session delegates only `memory pids`. That path
        (`/sys/fs/cgroup/user.slice/user-1000.slice/user@1000.service`) **does not exist
        on this host**; this shell's cgroup is `0::/` and the root cgroup delegates
        `cpuset cpu io memory hugetlb pids rdma`. Workers run under the rootful Docker
        daemon, which applies the quota itself — measured above, scaling exactly. The
        finding was recorded honestly and is simply superseded by measurement.
      - **Disk was the real gap.** `--storage-opt size=` is refused outright (overlay2 on
        extfs needs xfs with `pquota`), and an unbounded container wrote 200 MiB into its
        own layer at 3.4 GB/s. The contract calls for a real quota, not a free-space
        check. Solved without changing the host filesystem: a **loop-backed ext4 volume**
        plus `--read-only`. A sparse image is made and attached by one-off *privileged
        setup containers* — that privilege is the trusted launcher's; the worker receives
        the volume, never the device, and cannot write anywhere else.
      - **AppArmor stays unavailable** (`enabled = N`), confirming T0. Confinement here
        rests on user namespaces, seccomp and cgroups, and no profile may claim otherwise.

      Mutation-tested, because a probe suite that cannot fail measures nothing: dropping
      the CPU quota reports `0.5 -> 4.00 cores`; dropping the memory limit reports
      `exited 0`; swapping the 64 MiB volume for a 1 GiB tmpfs reports the full 200 MiB
      written. Each turns the run red.

      **T3(b) done 2026-09-19 — five of the six owed items, and the sixth is blocked.**
      Four new suites, each with a self-test that weakens the thing it measures and
      requires the matching check to notice.

      | Owed item | Suite | Outcome |
      |---|---|---|
      | Malicious code in a real dependency-install hook | `dev/validate-hostile-deps.sh` | **9 escape attempts** in `dev/hostile-dep-probe.py` from inside a real `setup.py`, across 11 detection sites, all contained; 9 deliberate holes, all detected |
      | Second worker/canary | same | A sibling workspace with data in it, looked for and not found; proved by mounting it |
      | Egress gateway and allowlist | `dev/validate-egress.sh` | Deny-all except the configured host, 6 checks, 7 weakenings detected |
      | Rootless BuildKit | `dev/validate-rootless-buildkit.sh` | ~~Builds under Docker's deny-by-default profile plus 2 syscalls~~ — **false, corrected 2026-09-24:** the RUN never ran; the check matched the step's name. A RUN runs under the default plus **7 namespace-management syscalls, `keyctl` answered ENOSYS**, `process-mode:sandbox` intact, each member shown necessary by `--self-test` (`dev/buildkit_worker_profile.py`) |
      | Launcher/worker contract | `dev/validate-launcher-contract.sh` | One daemon per attempt by its own id, no socket, no context mount, no credentials, none outliving its attempt |
      | Trusted registry transport | same | Base pulled from the registry **through** the allowlisted gateway; since 2026-09-24 the built image is pushed through it too and pulled back by the launcher, holding its RUN's file |

      **Q2's default needed no relaxation.** Every published rootless-BuildKit recipe
      reaches it through `seccomp=unconfined`, `apparmor=unconfined` or
      `--oci-worker-no-process-sandbox`. Decision 6 rejects all three and the first is in
      the isolation profile's own `forbidden_arguments`. None is used: Docker's default
      profile plus `clone` and `mount` is enough, which is a *named* profile rather than
      the absence of one. No sign-off was required and none is requested.

      > **Corrected 2026-09-24 (T5): "clone and mount is enough" was false.** The probe
      > counted a build as done when its marker appeared in buildctl's output, and the
      > marker was in the Dockerfile, which BuildKit prints as the step's name whether or
      > not the step runs. Under that profile rootlesskit starts, and every `RUN` fails at
      > `failed to unshare remaining namespaces`. The conclusion that no *relaxation* is
      > needed survives; the size of the named profile does not. A nested runc needs
      > `clone, mount, umount2, unshare, setns, sethostname` — six calls Docker's default
      > grants a container holding CAP_SYS_ADMIN, acting on namespaces the worker itself
      > created — **plus `pivot_root`, which Docker's default denies to every container
      > whatever its capabilities**, so it is outside anything Docker grants
      > (`98c00fb-F1` corrected an earlier version of this sentence that grouped it with
      > the six). runc cannot jail a RUN container without it and the alternative,
      > no-pivot plus chroot, is a weaker jail; **the user signed it off on 2026-09-24**,
      > and any further addition outside Docker's default needs the same. `keyctl` is
      > **still denied**, answered with ENOSYS instead of EPERM because runc tolerates only
      > the former (Docker's own profile does the same for `clone3`). All of this is the
      > *worker's* filter: BuildKit puts each RUN container under a second, stricter
      > profile, so build code gets none of these calls — and since that is what makes
      > the wider worker profile acceptable, both probes now assert it with a hostile RUN,
      > and the self-test shows the assertion fails when the inner profile is dropped
      > (`98c00fb-F2`). No `seccomp=unconfined`, `apparmor=unconfined` or
      > `--oci-worker-no-process-sandbox`. The profile is now one definition shared by both
      > probes, against Docker's default **pinned by commit and hash**: it had been fetched
      > from `main`, which changed twice in August 2026. The success check reads back a
      > file the `RUN` wrote, with content the shell computes so no step name can supply
      > it, and `--self-test` requires a failing `RUN` not to count and every member of the
      > profile to be necessary. The exact profile is the T5 gate's to review.

      **A minimal syscall set is a property of a configuration, not of a program.**
      *(Superseded 2026-09-24: there was no configuration dependence and no intermittency
      to explain. Both "sets" were measured with the check above, which could not fail;
      `umount2` is required in every configuration, like the other six.)* The
      two above were measured where the base is pulled over ordinary Docker egress. Where
      a layer is *extracted* from a registry pull, that set intermittently fails on
      `umount2`, so `umount2` is carried in a separate `INTERMITTENTLY_NEEDED` list — its
      justification is an observed flake, and the self-test would rightly refuse to
      certify it as required.

      **RESOLVED 2026-09-24, inside T5 (user decision the same day to fix it there,
      before the gate's Phase 2, rather than let it gate T7).** The `RUN` failed every
      time, in both probes; it looked intermittent and configuration-specific only because
      the simple probe's success check could not fail. Diagnosed one variable at a time,
      each failure naming the next denied call — `unshare`, `setns`, `keyctl`,
      `pivot_root`, `sethostname` — and then the push, which had two causes of its own:
      `registry.insecure=true` on the client output sent it over HTTPS direct to a name the
      internal network cannot resolve, and the tinyproxy gateway dropped pooled keep-alive
      connections, which fails a blob-upload POST: pushes 13 of 22 through tinyproxy (6 of
      them with its idle timeout raised to 600 s, which did not help) and 16 of 16 through
      squid with the same one-host rule; every `RUN` that executed, rather than hitting the
      cache, completed under both. The launcher probe
      now uses squid, and the gateway T7 picks must be tested with a push, not only a pull.
      The snapshotter experiment planned first was not needed: no failure involved it. The
      launcher suite asserts the `RUN` and the push by pulling each image back and reading
      the file its `RUN` wrote. The snapshot of what T3(b) recorded follows, kept because
      the record of a wrong conclusion is the useful part.

      The nested `RUN` step does not reliably complete in
      the full configuration — internal network, proxied egress, registry pull,
      TCP-addressed worker, session-streamed context. Two intermittent failures seen,
      `failed to unmount ...: operation not permitted` and `nsexec: failed to sync with
      stage-1`, and widening the syscall set to
      `clone,mount,umount2,setns,unshare,pivot_root` did not help (0 of 2). The host is
      not the cause: `dev/validate-rootless-buildkit.sh`, which builds on the default
      bridge with a mounted context, passed 5/5 immediately before and after. The launcher
      suite therefore asserts reaching build stage 2 — which proves the context streamed
      and the base pulled through the gateway — and does not claim the RUN or the push.
      **Decision 6's gate holds: no production build driver connects until this is
      understood.**

      One finding against the earlier work, recorded because no review caught it:
      `dev/rootless-buildkit-probe.py` bind-mounts the build context into the worker,
      which decision 6 forbids in terms. The launcher suite streams instead and asserts
      the mount's absence by reading the container.

- [x] **T4. S3 adapter and layout proof.** Depends T1. Use real MinIO and scoped service
      credentials; implement immutable artifacts, checksums, completion descriptors,
      multipart abort and log chunk replay. Round-trip bundle/log/output descriptors
      through an independent small reader, including filenames requiring URL encoding.
      Kill writes between object and descriptor completion; revoke permissions and stop
      MinIO. **Pass:** no partial artifact advertised, no public/cross-key read, observable
      storage failure, restart-safe retries and no filesystem durability fallback.

      **T4 done 2026-09-19.** `publisher/storage/`: `ObjectKeys`, `ObjectStore` +
      `S3ObjectStore`, `BundleWriter` + `BundleReceipt`, `BuildLogWriter` + `LogIndex` +
      `LogFinal`. **47 tests** in that package, 33 of them against a real MinIO container
      pinned by digest and 14 needing none (46 and 32 when T4 closed; the 47th is T5's
      lost-response test, see "Object-store stalls" under Risks) — `ObjectKeysTest` and `AwsSdkClasspathTest`
      earn their keep precisely by being true without a container. `make test` 184/184,
      up from 138 before this track.

      - **The key layout is parsed from this file's storage table, not restated.** A
        mistake in a key is a migration, not a bug fix. The one component the platform does
        not generate — the path inside a rendition — is validated here, because the output
        descriptor schema says containment "is the writer's to enforce"; and the limit that
        binds is the **key** in UTF-8 bytes, not the path in characters, which a
        1024-character path producing a 1172-byte key made plain (`2b483fb-F1`).
      - **Digests are ours.** SHA-256 computed over the bytes that moved, never the ETag,
        and recomputed on a verified read. A streamed put cannot record its digest as
        metadata — headers precede the body — so `receipt.json` is the durable record and
        `StoredObject.sha256()` is an `Optional` so that absence cannot be missed
        (`f440ce4-F2`).
      - **Completion is the receipt and nothing else.** Bytes first, `receipt.json` last,
        every object create-only, and `readReceipt` deliberately never looks at the archive.
        Commit checks the receipt's claims against the objects rather than only their
        existence (`d319b14-F1`); `archiveSha256` is the one claim taken on trust, because
        verifying it would mean re-reading the archive, and the javadoc says so.
      - **The log index advertises a contiguous run**, never the highest sequence present,
        so completion can never point past a hole. `complete`, `truncated` and the build
        outcome are three separate facts. The generation guard is a **compare-and-swap**,
        not a read-then-write, after the read-then-write was shown to fail under
        interleaving (`4f81ee0-F1`).
      - **Every Pass condition has a control.** The scoped-credential denials are measured
        against an object placed by the root user and asserted present; "no multipart
        upload to abort" is measured against one created deliberately, seen, and aborted.
        An absence proves nothing unless the probe can see a presence.

      **Not covered, and not claimed.** Chunk replay against a *committed cursor* is T6's,
      since the cursor is the DB's; this track built the storage half. Stopping MinIO is
      exercised as an unreachable endpoint rather than by killing the container mid-write.
      `head()` returning 403 rather than 404 for a forbidden object is MinIO's behaviour;
      real S3 depends on whether the caller holds `s3:ListBucket`, and that is unverified
      here.

- [x] **T5. Extractor plus mandatory independent mid-track security review.** Depends
      T1–T4. Implement bounded extraction, validation and private workspace handling,
      including rules S1–S11 of "Semantic validation" above — S10 and S11 in particular,
      which no earlier task can prove because they need real extracted bytes.
      Run the untouched T2 corpus and resource measurements; mutation-test each defense.
      A new reviewer/session reads the extractor and sandbox configuration, adds withheld
      archive/escape fixtures and drives T3's live attempts independently. Review all
      security comments against named tests. **Pass:** a completed review with every
      finding resolved, the five conditions below satisfied and recorded, and no skipped
      isolation probe; same corpus through the upload pipeline once T8 exists.
      Driver/log plumbing may proceed only against reviewed boundaries after this gate.

      **Closed 2026-10-02.** Gate Phase 2 (e5e3071, 8 findings) and two re-verification
      rounds (f4f5f32, 4 findings; 67b4ef5, the final gate round by the user's 2026-10-01
      decision, 1 finding) are all resolved through per-commit-reviewed fixes, the last of
      them 39e8c37, 264efb9, d8aaa14 and 4c5101d (the egress rule's private-destination
      tightening and its IPv4-embedding follow-up). The gate reports are in `code_review/`.
      Decision 6's driver gate is open: F1 (P1) was re-verified by the gate itself. What it
      does NOT provide, as every gate report says: a human security review of the
      deployment profile and the extractor, before untrusted bundles from real users are
      accepted, is still owed to the operator.

      **T3's blocked nested-`RUN` item is T5's to close (user decision 2026-09-24).** The
      launcher-contract probe asserts only that the build reaches stage 2, because the
      nested `RUN` does not reliably complete in the full configuration (see T3(b)). That
      is a skipped isolation probe in all but name, and this gate's Pass admits none. So it
      is diagnosed and fixed as part of T5, before the gate's Phase 2 begins, rather than
      left to gate T7; the probe then asserts the `RUN` and the push, and the Phase 2
      session re-runs it like the others. Decision 6 bounds the fix: no relaxation of the
      seccomp/AppArmor profile or the process sandbox without the user's sign-off.
      **Closed 2026-09-24** — see T3(b). None of decision 6's named relaxations was
      needed; the named profile grew from two syscalls to seven plus `keyctl` → ENOSYS, one
      of the seven (`pivot_root`) outside Docker's default and signed off by the user, and
      the Phase 2 session should
      review that profile (`dev/buildkit_worker_profile.py`) as part of the sandbox
      configuration.

      **Gate Phase 2 finding F6 is T5's (user decision 2026-09-26): the shipping worker's
      isolation probes run here, before T5 passes.** Part 1, the launch
      (`dev/validate-shipping-worker.sh`): the rootless worker starts under every argument
      of the build-worker profile, read from `spec/isolation-profile-v1.json`, except
      `no-new-privileges`. rootlesskit's `newuidmap` needs its file capability, and the flag
      disables file capabilities, so the worker cannot start with it. **The user signed off
      on 2026-09-26 on waiving it for runc-rootless**, replaced by three probe-enforced
      checks. The waiver and its replacements are in the spec, and
      `dev/schema-fixture-check.py` refuses a waiver without them. Two more findings came
      out of that launch, and the derived worker image now fixes both: the upstream image
      carries a setuid-root `fusermount3` and a setgid `unix_chkpwd` (cleared), and it runs
      as uid 1000 with subordinate ids 100000+, which on a rootful host are real host ids
      (on this machine, the login user's own). It now runs as a dedicated uid 2401 with ids
      from 3000000, checked against the host's own account files.

      Part 2, attacks from inside a RUN step (`dev/validate-run-attacks.sh`): under the
      worker part 1 launches, each attempt in decision 6's table is made from a real RUN and
      refused, with an allow control beside it. Two were reachable and are now closed. Build
      code reached buildkitd on `tcp://0.0.0.0:1234` (a RUN shares the worker's network); the
      **user chose (2026-09-26) a unix socket on a volume shared only with the trusted
      client** over mutual TLS, so there is no port and the RUN never gets the socket. And an
      unauthenticated registry took writes from build code; the registry now requires a
      credential the trusted client has and build code does not, so a build-code write gets
      401. Contained without a change: the RUN is alone in its PID namespace (buildkitd not
      visible), nothing it backgrounds outlives it, and it sees no credential, no other
      workspace and no Docker socket. **Owed:** the launcher-contract probe
      (`dev/launcher-contract-probe.py`) and T7's driver must adopt the socket transport;
      it still starts buildkitd on TCP.

      Part 3, resource bounds from inside a RUN step (`dev/validate-run-bounds.sh`): under
      the shipping worker, on the loop-backed quota volume (`dev/quota_volume.py`, mounted
      `nosuid,nodev`), build code tries to exceed memory, fork, CPU, disk and log volume,
      each beside an allow control in the same RUN, and `--self-test` restarts the worker
      without each defence and requires every check to go red. The first measurement
      found the memory bound was twice what it read: `--memory` alone lets Docker add the
      same amount in swap, and a RUN reached 3968 MiB under a 2g worker. The profile now
      carries `--memory-swap` equal to the limit (`fba1089`). Logs were bounded only by
      BuildKit's defaults, which nobody had set; they are now pinned as the profile's
      `daemon_bounds` and proved by this probe.

      Part 4, the egress deny matrix from inside a RUN step (`dev/validate-run-egress.sh`),
      through squid as the probes now ship it (`dev/egress_gateway.py`): an allowlisted
      repository is fetched, and every unlisted host, IP literal, private address, the
      metadata endpoint, an ALLOWLISTED name resolving to the metadata endpoint, a suffix
      of an allowed name, a redirect out of an allowed host, CONNECT to an unlisted host or
      a non-443 port, a direct socket around the gateway, external DNS, and an IP literal whose reverse DNS
      names an allowlisted host (gate re-verification finding `t5-f4f5f32-F1`: squid's
      `dstdomain` follows the PTR of an IP-literal URL unless given `-n`, and an address's
      owner sets its PTR) are refused;
      the registry answers but refuses writes. Each refusal must be squid's own
      `ERR_ACCESS_DENIED`, not an unreachable host. Private and non-public destinations
      (RFC 1918, CGNAT, this host, IPv6 ULA) are refused by rule whatever name they were
      reached through, which is what refuses DNS rebinding; only a host the operator
      DECLARES a private mirror may resolve to one (user decision 2026-10-01, on the
      coder's recommendation; it replaced an earlier rule that let any configured name
      resolve privately, which this section's "Block ... RFC1918 ... DNS rebinding" did not
      allow). T7's repository configuration therefore marks an entry private. The tinyproxy
      suite (`dev/validate-egress.sh`) stays as T3's record.

      With parts 1-4 in, `runc-rootless` is **measured and selectable** in
      `spec/isolation-profile-v1.json` (2026-09-27). Every bound it enforces names its
      proof -- a probe file and the check in it that measured the bound under the rootless
      worker -- and `dev/schema-fixture-check.py` refuses a proof that is missing, names a
      check the file does not record, or lives in a file none of the runtime's
      `measured_by` scripts runs (so no runtime can borrow another's measurement).
      No-new-privileges is covered only through its waiver. The runtime's three former
      "owes" are recorded as resolved, with reasons. Selectable is not "connect the
      driver": decision 6's gate still waits for the T5 gate to re-verify F6.

      **The reviewer is the independent reviewer AGENT, decided 2026-09-18 by the user.**
      There will be no human security review at this gate. The two-session split in
      `code_review/` is the mechanism: the coding agent implements and commits, a separate
      session that cannot see its reasoning reads the commit and the tree, writes its own
      probes and fixtures, and records findings in a report. The earlier wording said
      "signed review", which implied a human signature; it is struck rather than quietly
      redefined to mean something else.

      **What this genuinely provides, on the evidence of T2 and T3(a).** Across seventeen
      review cycles (`3f8bdd2..2124414`, ten of them CHANGES_REQUIRED) the reviewer found
      defects the author had missed, several of them in things the author had just asserted
      were fixed: an at-limit fixture four bytes over its limit (`29857f7-F1`), four
      predicates comparing against literals instead of the configured limit (`29857f7-F2`,
      `29857f7-F3`), a 924 MiB peak a commit had described only in seconds (`6987f01-F1`),
      a check with nothing asserting it, three commits running (`6d7b790-F1`, `64f5dd8-F1`,
      `3e23732-F1`), and commit messages asserting what was not so — a coverage claim in T2
      that named groups while required cases inside them were missing (`29857f7-F4`) and,
      in T3(a), a claim that this workplan said something it did not (`7b6e931-F1`). It
      wrote its own extractors, mutations and probes rather than re-running the author's.
      That is a real control, not a formality.

      The IDs are here rather than a bare count because this paragraph's whole authority is
      that its numbers are checkable. Each draft of this sentence has been wrong about that
      in a different way — a miscount first, then three citations that did not support the
      claims attached to them — which is the argument for the paragraph, not against it.

      **What it does not provide, and this plan must not pretend otherwise.** It is not an
      external human security review. Both agents share a model and therefore share blind
      spots; a class of mistake invisible to one is likely invisible to the other, and no
      number of review cycles fixes a correlated blind spot. There is no accountable
      signature. Nothing here discharges the need for a human to look before untrusted
      bundles from real users are accepted — that remains **owed, and owed to the
      operator, not to this plan**.

      **Conditions that make the agent review worth something.** Each is a requirement of
      this gate, not advice:
      - The reviewer **authors its withheld archive/escape fixtures before reading the
        extractor**, and records that it did. Fixtures written afterwards test the
        implementation's own idea of the problem.
      - The review runs in a **fresh session**, not one that watched the extractor being
        built commit by commit. Incremental familiarity is anchoring. This **disqualifies
        the standing per-commit reviewer**, which watches the extractor land commit by
        commit by design (`code_review/README.md`), so the T5 gate is a separately arranged
        session — and, because its fixtures must predate its reading of the extractor, one
        arranged *before* T5's implementation commits begin. Who arranges it is not settled
        here; that it must be arranged in advance is, since a condition nobody schedules is
        not a control.
      - The reviewer **re-runs T2's corpus and T3's probes itself**, from the committed
        tree, rather than accepting the author's reported output.
      - Every finding class gets a **permanent test**, not a one-off fix. T2 produced the
        same finding — "a check that nothing checks" — three commits running
        (`6d7b790-F1`, `64f5dd8-F1`, `3e23732-F1`), because each fix closed only the
        instances that had been named. It stopped when the matrix was made to derive its
        coverage from the kinds the oracle can emit instead of enumerating them (`6024d51`),
        which is what "permanent test" means here.
      - A review that produces **no findings is itself a finding** at this gate: the
        reviewer states what it attacked and failed to break, rather than reporting
        silence.

      **Coverage-guided fuzzing, added 2026-09-23** (Jazzer, test scope, approved by the
      user after an outside review noted that a hand-written parser is an attack surface
      Skald owns). `BundleFuzzTest` has seven targets: the gzip layer, tar headers, PAX
      records, member paths, the tar stream, the manifest, and the whole extractor with
      the filesystem checked afterwards. The shared invariant is that nothing but a
      `BundleRejection` comes out. `make test` replays every seed and every saved finding
      as a regression; `make fuzz` fuzzes each target for `FUZZ_SECONDS`. Seeds are derived
      from the corpus by `dev/fuzz/seeds.py`, which never deletes a saved finding. **The
      first run found a crash in 37 seconds**: a 44-byte manifest whose number overflows a
      double to Infinity made the schema validator throw instead of refusing. It was fixed,
      and it is kept as a permanent regression input. The gate's Phase 2 should include a
      long `make fuzz` run from the committed tree and report its duration and outcome, as
      it does for the corpus.

      **The author's long run, 2026-09-25/26: seven targets, 30 minutes each, nothing
      found.** `make fuzz FUZZ_SECONDS=1800`. The first run (from 18:40 UTC, tree `55cabe4`)
      finished `gzipMember`, `tarHeader`, `paxRecords`, `memberPath` and `tarStream` clean,
      each for its full 1800 s, and was then cut off partway through `manifest` when the
      session crashed; its log was in the session's scratch directory and went with it, so
      those five rest on the live observation, not a kept record. The rerun of the other
      two, `FUZZ_TARGETS="manifest extractor"` on the same tree, kept its log: `manifest`
      110,484,846 runs in 1801 s, `extractor` (the whole pipeline, writing to disk)
      920,395 runs in 1801 s at about 510/s, exit 0, no finding and no new saved input.
      The one saved finding, the first run's manifest crash, replays as a pass. This is
      the author's run; Phase 2 still owes its own, from the committed tree.

      **"Freeze the validated tree", done 2026-09-24.** An accepted extraction ends with
      `ExtractionRoot.freeze()`: files `r--------` (`r-x------` where the manifest says
      executable), directories and the root `r-x------`, all through descriptors, and the
      object refuses further writes. `deleteTree` still removes a frozen tree. What it buys:
      nothing in the platform can change validated content by accident. What it does not:
      a process running as the platform's own user can change the modes back. The boundary
      against untrusted build code is that the worker is sent the context as a stream and
      never sees this directory (T3's launcher contract), not these modes.

      **Resource measurements** come from `bash dev/measure-extraction.sh [--full]`: the
      same oracle over the same corpus, with the adapter recording each extraction's wall
      time, the JVM's peak resident memory and the most bytes its root held at any 20 ms
      sample (a floor). The oracle enforces the deadline and disk budget; this records what
      was actually spent, so a gate can compare its own run with the author's. **One thing
      it established that no limit states:** memory grows with `max_entries`, because the
      duplicate index and the parsed manifest hold one entry per file. The entry-cap
      fixtures are by far the most memory-hungry in the corpus. The defaults fit under the
      adapter's 256 MiB heap. An operator raising `max_entries` towards its absolute maximum
      must size the platform's heap with it, and T8's deployment notes owe that sentence.

      **"No reads outside the root", traced, done 2026-09-24** (deferred from T2). The
      oracle's snapshots prove nothing outside the root *changed*; they cannot see a read or
      a refused attempt, and this host's atime regime does not record reads. `bash
      dev/trace-extraction.sh` runs the same oracle over the same corpus with each JVM
      under `strace -f -y` and `dev/trace-check.py` reads every trace: no path the
      extractor named, and no descriptor it used, may be a traversal target —
      `escape.txt`, `pax-escape.txt`, `neighbour/`, `via-symlink-target/` beside the
      root, and `/etc/skald-escape.txt`, `/etc/passwd`, `/tmp/escape.txt`: every
      destination the corpus aims at, read from the one table in `dev/bundle_sentinels.py`.
      The first version kept its own list of five and missed the last two
      (`f352115-F1`: a mutation stat'ing `/tmp/escape.txt` in every extraction passed both
      the oracle and the trace). The JVM's own startup read of `/etc/passwd` is allowed in
      its exact form, once per trace; anything else naming it is a touch. A single read in
      precisely that form is indistinguishable from the JVM's, which the checker states.
      Any link or rename reaching outside the root is a touch too (the extractor makes
      neither), so creating `link-symlink-dir-then-child`'s link to `/tmp` is caught.
      `-y` is what makes this meaningful: the extractor
      works through descriptors, including one on the root's *parent*, which is the
      directory the targets live in, and `-y` resolves each descriptor-relative call to a
      real path. Result in the reduced, `--full` and `--symlinked-root` modes: 91 of 91
      traces clean each time. `--rename-race` is excluded because its racer deliberately
      renames the root to the neighbour's name; the oracle's containment check covers it.
      The checker's self-test makes real traces of each target touched each way and a
      control, and refuses a trace made without `-y`. A mutation that makes the shipped
      `ExtractionRoot.adopt` read `escape.txt` through the parent's descriptor is invisible
      to the oracle (91/91 clean) and fails 90 of 90 traces (the one fixture refused before
      the root is opened never names the world). strace (GPL-2.0-or-later) is used only in
      the throwaway image `dev/oracle-java-trace`, never shipped, as the user approved.
      Not covered, and stated in the checker: listing a directory (`getdents`) takes no
      path, so learning a target's *name* by listing the parent is not seen.

- [x] **T6. V2, admission and durable coordinator.** Depends T1/T4/T5. Apply V2 to fresh
      and populated V1 PostgreSQL, preserve V1 checksum/rows/IDs/paths, test NOT NULL and
      uniqueness constraints. Add idempotency, leases/fencing, cancellation and admission
      quotas. Use a fake driver for deterministic crash/concurrency tests **only here**.
      Exercise concurrent version allocation with the legacy image endpoint; content
      deletion vs queued/finalizing work; same-key/different-input 409. **Pass:** no failed
      build produces a version, exactly one version per success, no accidental activation,
      no orphan artifacts whose ledger record was cascaded away.

      **Decisions, taken 2026-10-01 by the user on the coder's recommendations** (and the V2
      schema delegated to the coder, recorded in the commit that writes it):
      1. A new build request for content that already has a QUEUED build supersedes it:
         the queued one becomes CANCELLED with `superseded_by` set (nothing ran, so this is
         the frozen machine's QUEUED -> CANCELLED edge). A RUNNING build continues.
         Activation stays explicit, so a late finisher never goes live by itself.
      2. Admission: one running build globally and a queue of 10 (as above), plus at most 3
         non-terminal builds per publisher. Operator-configurable and stricter-only; no
         manifest can raise them.
      3. The coordinator runs in-process in ShinyProxy, with its leases in PostgreSQL and a
         fencing generation per build, which stays correct with several instances.
      4. A lease that expires makes the build INTERRUPTED. No automatic retry: retries are
         new attempts a user starts (decision 4). Lease 60 s, renewed every 20 s,
         configurable. (Amended 2026-10-18, user: 300 s renewed every 30 s, for app builds
         and package compiles alike, after a stalled host let the reaper expire healthy
         compiles; package deadline 180 min, for duckdb.)
      5. Idempotency keys are kept for the life of the build row (unique per content), never
         expired, so a late HTTP retry behaves exactly like an early one.

      **Done 2026-10-02**, every part per-commit reviewed (a00a190 .. this commit):
      `V2__bundles_and_builds.sql` (fresh and populated-V1 migration tests, same-content
      composite keys); `BuildAdmission` (idempotent, 409 on a reused key, supersede, queue
      and per-publisher limits, all race-tested); `BuildCoordinator` + `BuildRunner` (leased
      claims fenced by generation, owner, lease and deadline; a heartbeat whose stop signal
      is a function of time, so neither a throwing nor a hanging renewal lets a lost worker
      run on; cancellation per the machine; reaping with no retry); `succeed()` (the version
      and SUCCEEDED in one transaction through the shared `VersionAllocator`, log required,
      nothing activated, a crash inside it leaving no half-version); `ContentDeletionGuard`
      (refused while builds are unfinished, cleanup enqueued before the cascade, admission
      fenced by the content lock); the artifact ledger (one image record per attempt, pinned
      on success, a success it cannot pin refused); `BuildsConfig` + `BuildReaper` (wired
      when a database is configured, reaping on a timer that cannot die). The Pass line's
      four properties each have tests that go red under the matching mutant.
      **Deferred to T7, by design:** finishing a PUBLISHING attempt after a restart "if safe"
      needs the pushed digest verified in the registry, which is the driver's. Until T7
      provides that, a PUBLISHING attempt whose lease expires is reaped INTERRUPTED; its
      image is in the ledger, and a retry that reproduces the digest gets its own pinned
      record (6ae3868-F1).

- [x] **T7. Trusted bases, recipes, BuildKit driver and cache.** Depends T3/T5/T6.
      Add `images/` catalog and selected language/type bases, fixed non-root launchers,
      dependency restore for both languages, scoped cache import/export and digest publication. Test source-only
      edit gives a dependency cache hit; lockfile/base/architecture/recipe/local-dependency
      change misses; another content cannot read/poison the cache; cold-cache build works.
      Inject failed restores, unreachable repositories, push failure, timeout, cancellation
      and worker death. **Pass:** actual built image with recorded digest/provenance, useful
      failed-build log, bounded teardown, and cold runtime pull from the chosen registry
      authority. No mocked “build succeeded” counts toward this task.
      **Decisions, 2026-10-02 (user):** bases are `rocker/r-ver` and the official
      `python:*-slim`, digest-pinned; an oversized build log keeps head AND tail; NO Posit
      Package Manager -- R and Python packages resolve through forge (the user's own
      repository manager, github.com/gkroken/forge) in front of CRAN and PyPI, declared as a
      private mirror; cold R builds compile from source and warm ones hit decision 7's cache.
      (Clarified 2026-10-18, user: "no Posit" means no Posit product in Skald's deployment. A
      publisher's lock resolved on Posit's copy of CRAN, `Repository: RSPM`, is accepted and
      restored from the mirror like any CRAN package.)
      User-experienced forge problems met on the way go to `code_review/forge-issues/`.
      **Part 1 (2026-10-02):** `images/catalog.json` with R 4.6.1 and Python 3.13.16 (T1 was to
      pin exact versions and never did; T7 pinned them), the reviewed Dockerfiles under
      `images/`, forge in the dev stack, and `dev/validate-bases.sh`, which builds every base
      through forge and checks each recorded fact inside the built image. rocker defaults R to
      PPM (p3m.dev) with PPM's user agent; the R base removes both.
      **Carried from T6, for this track:** (a) implement `BuildDriver` within its documented
      bound: poll the stop signal at least every renew_every / 2, including while blocked in
      a long call, and on stop dispose of the WHOLE worker (`worker_disposal`), with a test
      driving a driver blocked in a long call (cab64b9 review N1); (b) restart reconciliation
      of PUBLISHING attempts: verify the pushed digest and the complete log, then finish
      exactly once through `succeed()` if both hold; until then the reaper interrupts them;
      (c) wire a `BuildRunner` loop around the real driver that logs and continues on
      anything `runOnce` throws.
      **Carried from T5's gate finding F6 (part 3), for this track:** (1) when a RUN step is
      OOM-killed inside the worker, `docker inspect` reports the WORKER as
      `State.OOMKilled=true` though it keeps running and building; the driver must not read
      that flag as worker death. (2) BuildKit's log bounds (`daemon_bounds` in
      `spec/isolation-profile-v1.json`) clip a step's log and keep its HEAD, while a failed
      build's error is usually at its tail; what is kept and shown to a publisher, and the
      production values, are this track's. (3) The workspace is the loop-backed quota volume
      `dev/quota_volume.py` makes, mounted `nosuid,nodev`; its attach step is the launcher's
      privilege, and the production size is this track's.
      **Part 3d-2b (build logs):** `storage.HeadTailLog` keeps the head live (a chunk per 2 s
      or 256 KiB) and the newest tail in memory, written at the end after a cut marker;
      1 MiB each, 4 KiB lines, by default. The driver follows the client's stderr into it,
      and finishes it with the outcome. A chunk that cannot persist after bounded retries
      stops the build as `LOG_STORAGE`, and a success whose log cannot be finished complete
      is a `LOG_STORAGE` failure, never a silent success. While the tail is in use a reader
      sees nothing new until the end (T8), and a crash loses the in-memory tail: part 4's
      reconciliation must finish such a log as interrupted (0adc78e N2).
      **Part 3e (base digests, 4dd3f76 N1):** a built base is not byte-reproducible, so its
      digest is the operator's, not the repository's. `dev/publish-bases.py` builds each
      catalog base through the mirror, runs bases-probe's static and live checks, pushes it to
      `<registry>/skald/base/<id>`, and writes a published-bases file naming the catalog it was
      built from by SHA-256; nothing is written if any check or push fails.
      `publisher.recipe.BaseCatalog` loads the shipped catalog with that file, refuses a file
      from another catalog and any reference that is not `<registry>/skald/base/<id>@sha256:`,
      and resolves a manifest to the newest recipe revision's digest-pinned `Base`.
      **Part 4b (decision 7's cache):** `recipe.DependencyCacheKey` hashes a length-prefixed,
      domain-separated record of the recipe revision, base digest, architecture, language and
      version, package-manager versions, configured mirrors and the rendered lock. The build
      imports and exports BuildKit's registry cache at `<registry>:5000/skald/cache/<content
      UUID>:<key>` (mode=min, export errors ignored; no cache mounts). Measured: a source-only
      edit reuses the restore step; a lock change and another content item do not.
      **Part 4c (wiring):** `skald.builds.worker.enabled=true` starts a `BuildLoop` (logs and
      continues on anything `runOnce` throws, T6 carry (c)) around the real driver. It refuses
      to start, listing every problem at once, unless a database, object storage, the
      registry and its credential, the images, a writable workspace and both mirrors are
      configured; the published bases load and name `<registry>:5000`; each mirror's host
      is allowed by the gateway; and the poll is at most renew_every / 2. The workspace quota
      defaults to 8 GiB (F6 carry (3)). An interrupted runner (shutdown) writes nothing: the
      lease expires into INTERRUPTED.
      **Decided in 4c (coder, recorded for the reviewer and the user):** T6 carry (b) said
      T7's reconciliation finishes a verified PUBLISHING attempt "exactly once through
      `succeed()`". `succeed()` takes the version's `spec_json`, the server-generated runtime
      contract that T9 defines ("Consume only the server-generated `spec_json` contract"), and
      step 5's version transaction is T6/T9 evidence. So the `succeed()` call moves to T9
      with that contract. T7 verifies the pushed digest and finishes the log; a PUBLISHING
      attempt whose lease expires is reaped INTERRUPTED until T9. **Approved by the user
      2026-10-03** (raised by the reviewer as 4b8c6f8 N1).
      **Part 4d-1 (publication verified):** inside the lease, after PUBLISHING, the runner's
      publishing hook (`worker.PublishVerifier`) HEADs the manifest by digest through the
      registry's API (`skald.builds.worker.registry-api`, basic authentication; a bearer
      registry is refused by name) and requires 200 with that exact Docker-Content-Digest,
      and requires final.json complete, BUILT and of this lease generation. Its last sequence
      becomes the committed log cursor (`markLogComplete`). Anything else is PUBLISHING ->
      FAILED `PUBLISH_VERIFY`. The version transaction stays T9's.
      **Part 4d-2 (reconciliation sweep):** `worker.AttemptSweeper` runs at startup and every
      10 minutes. It takes the host's inventory of attempts FIRST (attempt-labelled containers,
      volumes and networks, quota images, loop devices), then reads the unfinished builds
      (QUEUED, RUNNING, PUBLISHING, whatever the lease says), and disposes of a build-id
      attempt only if its build is not in that set: never a live one, on any instance. A
      build that finished within `sweep-grace` (15 min, longer than the settle) is left alone
      too: its work thread may outlive it in a daemon call, and a late bind of a removed
      volume makes the daemon re-create it unlabelled. Disposal therefore also removes a
      volume under the attempt's names that the daemon made for a bind (no labels, local, no
      options), and the inventory finds one by name after a crash (2fc53d9-F1). It
      finishes the log of a build lost (INTERRUPTED or TIMED_OUT) within the last day that has
      no final.json, with outcome `INTERRUPTED` (0adc78e N2, e4306c6 N1). `registry-api`
      carries the build credential in a Basic header: operators should use https or a
      host-local address (5cd0826 N2).
      **Part 4e-1 (provenance):** V3 adds `skald.build.provenance` (jsonb, schema
      `skald.build-provenance/v1`): the bundle's receipt digests, the manifest as validated,
      the base by digest with its catalog entry, the generated recipe's digests, the uploaded
      lock's digest, the cache key, the mirrors, the effective limits (also written to
      `effective_limits`), the egress policy, the isolation profile (spec and seccomp by
      digest, every bound, the waived ones) and the worker and gateway images by the id their
      containers ran. A build with an image has provenance (a CHECK); a failure records what it
      got as far as. Every pushed image also carries BuildKit's SLSA v1 provenance
      attestation (`mode=max`; no secret is a build input, and the only build arguments are
      the gateway proxy's, an internal address), so the pushed
      digest is an OCI index. An SBOM attestation is not part of T7: it needs BuildKit's
      scanner image as a new trusted image (user sign-off).
      **Part 4e-1b (the pass probe):** `dev/t7-pass-probe.py` runs the fixture apps through
      the WIRED worker (the application's own configuration classes, against forge on :80
      and an authenticated registry with the published bases), then pulls each image cold by
      digest and runs it hardened. It found four defects, fixed: the workspace's fsyncs cost
      ~460 ms each on a loop device (a cold base did not extract within the deadline; now
      ext4 without a journal, mounted barrier=0); pip ignores a plain-http index unless it
      is named trusted (now `--trusted-host` for http only, hashes required); RUN steps had
      no proxy (now BuildKit's predefined proxy build arguments, outside the cache key); and
      could not resolve the gateway's name (now its address). Python passes: built, pulled
      cold, served, uid 10001, /app read-only. R does not yet: the native snapshotter copies
      the whole parent per layer and step, and the r-shiny base alone exhausts the 8 GiB
      workspace; kernel overlayfs (no device needed) is the next part, with the isolation
      probes rerun on it.
      **Part 4e-1c (overlayfs, and the pass):** buildkitd runs the kernel overlayfs snapshotter
      (ADR-0013, user 2026-10-10). Its RUN root does not inherit the workspace's nosuid, so
      the worker's entrypoint is `rootlesskit setpriv --nnp buildkitd`, set by the launcher
      whatever the image's own, and a launch whose buildkitd lacks the flag is refused
      (61635b7-F1): buildkitd and every RUN step run with no_new_privs (RUN steps had none before), proved from a RUN by the
      bounds probe; the waiver's three replacements are unchanged. The isolation probes'
      daemon is now tied to the launcher's by a test. The R image's push then failed
      intermittently: squid 6.x aborted large uploads (a zero-size read taken for the
      client's EOF; reproduced 4-13 of 24 300 MB uploads lost, 0 direct), so the gateway
      moves to alpine 3.24's squid 7.6 (24 of 24), and FTP is denied outright
      (CVE-2026-47729 lives in its listing parser). **The pass probe's build half holds** (the
      cache rows are part 4e-2, the injected failures 4e-3): both fixture apps
      built cold through the wired worker (Python ~70-110 s, R ~250-380 s, compiling from
      source), pulled cold by digest, served, uid 10001, /app read-only, host left clean;
      the failed builds of parts 4e-1b/c left complete, readable logs that named each defect.
      **Part 4e-2 (decision 7's cache, through the wired worker):** judged from each build's
      log as its owner reads it (the restore step settled with no output of its own =
      reused). Python: a source-only edit reuses the restore, and so does a comment-only
      lock edit (the key is over the rendered lock); a second hash on one package, a
      different content item with the identical bundle (which imports only its own scope
      and leaves the first item's cache ref byte-identical) and a different configured
      mirror each rebuild it. R: a source-only edit reuses the from-source restore (145 s
      against 593 s cold). Every image pulled cold and served as uid 10001. Each attempt
      starts on an empty workspace, so the keyed ref is the only thing a build can reuse;
      the key's base, architecture, recipe and package-manager inputs are covered by
      DependencyCacheKeyTest. A warm Python publish is still ~50 s: that is the fixed
      launch overhead the T10 latency work targets.
      **Part 4e-3 (the injections, through the wired worker):** a failed restore (a wrong
      hash: FAILED BUILD_FAILED, the log shows pip's hash refusal), an unreachable repository
      (an allowed mirror host with nothing behind it: FAILED BUILD_FAILED, the log names it),
      a push failure (the registry stopped once the restore runs: FAILED BUILD_FAILED, the
      log ends "failed to push <registry>/content/...: 503"), a cancellation during the
      restore (CANCELLED, log STOPPED, "[skald] stopped: a cancellation was requested"), the
      worker killed during the restore (FAILED WORKER_DIED) and a one-minute deadline on a
      from-source R build (TIMED_OUT DEADLINE_EXCEEDED, log STOPPED, "[skald] stopped: the
      build passed its deadline of 1 minute(s)"). Each log is complete and names its cause
      (each pattern checked against a successful build's log as a control); nothing the
      attempt owned is left within seconds of the build settling (the launcher's own
      per-attempt check included); and the worker builds and publishes again afterwards.
      The review of this part found a product race (01683f9-F1): the sweep finished a
      just-settled build's log INTERRUPTED before its stopping worker could, so a timed-out
      build's log lost its tail and said nothing of the deadline. The sweep now leaves a
      lost build's log to its writer for the sweep grace, and the runner tells the driver
      why it stops (deadline, cancellation, expired lease, another worker), which the log
      states. **For T8/T10:** an unreachable mirror reads to the owner as pip's "from
      versions: none" after "Looking in indexes: <mirror>": the gateway's error page looks
      like an empty index. A line from the platform naming the gateway's verdict would
      make it unmistakable.
      **Part 4e-4 (a crash, then a restart):** the probe runs Postgres and MinIO itself so
      they outlive a JVM, starts a build in one, and `docker kill`s that JVM's container
      once the restore runs: no shutdown hook, no disposal, no log finish, and its worker is
      left running. A new process on the same database and store settled the attempt
      INTERRUPTED (the reaper, after the lease), disposed of everything it owned and
      finished its log INTERRUPTED (the sweep, after its grace: about 120 s with a 1 min
      grace and 30 s period), then built and published an app that pulled cold and served.
      Its first run found two leaks, both fixed here. The killed process's frozen
      `extract-*` tree stayed in the build workspace forever: the workspace is now locked by
      one process at a time (`WorkspaceLock`, an OS file lock the kernel releases on any
      death), a second process on it is refused at startup, and the holder removes the
      `extract-*` trees a dead one left (never following a link). And the new process,
      shut down while its sweep ran, left the sweep's helper container: `stop()` now waits
      (bounded) for the sweep to end, and a helper's removal is finished under an
      interrupt. Finishing a PUBLISHING attempt after a restart stays with T9's `succeed()`.
      **Measured in part 3d-2a, carried to part 4 and T10:** the stop bound has two halves.
      The driver ISSUES the worker's SIGKILL within one poll of the stop (measured 0.5-0.8
      s). The daemon then needs time to end the container (measured 2.9-3.7 s alone; 6.1, 9.3,
      10.9, 14.8, 18.4, 38.0 and 52.5 s during make test, always the stop-during-RUN case;
      once 14.2 s while the shared host was busy). At the defaults the runner flips the stop 10 s
      (renew_every / 2) before the database could expire the lease, so on a slow daemon a
      next claim can start while the old worker is still dying. The margin is the
      operator's (lease and renew_every are configurable); T10 measures it on the target
      host.
      **Done 2026-10-10**, every part per-commit reviewed (4dd3f76 .. this commit). The Pass
      line, on real builds through the wired worker (T7PassProbeTest + dev/t7-pass-probe.py,
      nothing mocked): both fixture apps built cold, each image carrying its digest, its
      provenance record (V3) and BuildKit's SLSA v1 attestation, pulled cold by digest from
      the probe registry and served as uid 10001 with /app read-only (4e-1); decision 7's
      cache reused within its key and content item only, a lock, mirror or other-content
      change missing, and another item never importing nor changing it (a direct read
      through the gateway is T9's attack row; e418c3c N1) (4e-2); a failed restore,
      an unreachable repository, a push failure, a cancellation, the worker's death and a
      timeout each ending in its own state with a complete log that names its cause,
      nothing left on the host, and the worker building again (4e-3); and a process killed
      mid-build reconciled by the next (4e-4). On the way: the kernel overlayfs snapshotter
      with no_new_privs below rootlesskit, enforced by the launcher (ADR-0013); squid 7.6 for
      whole pushes and no FTP; a stopped build's log names why; the sweep leaves a settled
      build's log to its writer for the grace; the build workspace is locked per process and
      cleared of what a dead one left. Every defence named in these parts has a mutant that
      turns its test red, or the commit says why not. Full `make test`: 1282 run, 0
      failures; one class (MigrationV2Test) errored on a Postgres container that did not
      start within its timeout on the loaded host, and passed 9/9 rerun alone.
      **Carried on:** finishing a PUBLISHING attempt after a restart, with `succeed()`, to
      T9; signed provenance, the SBOM and the deployment notes to T10; build speed (shared
      Linux R binaries, the fixed launch cost) to T7b.

- [x] **T7b. Build speed: shared Linux R binaries and a warm path.** Depends T7. Placed
      before T8 by the user (2026-10-10): a publish should take about a minute, and an R
      app's cold restore compiles every package from source (330-590 s measured in T7's
      pass probe). Target, measured per phase in the pass probe: a source-only republish
      under 30 s, a small first publish under 60 s, and an R first publish whose packages
      all have binaries close to the Python one. **Accepted (user, 2026-10-19): about 70 s
      for that R first publish on this host; the probe holds it to 75 s** (59e8619 N1).
      **Shared binaries, built once per server (option 3b, user 2026-10-10).** forge now
      serves Linux binaries for CRAN packages (`.../cran-public/__linux__/<distro>/latest`;
      renv sends R's version and CPU, forge answers with a binary for that platform or the
      source). A binary is shared by every app on the platform, so how one is made decides
      what every later build installs. The rules:
      - Only public sources enter the shared tree (c18c5ab-F1). A binary is served to
        every app by name, version and platform, so only a package whose source tarball
        comes from the configured public repository set (forge's CRAN proxy), verified
        against that repository's digest for that name and version, is ever built for it.
        A lockfile's remotes (GitHub, URLs), packages local to a bundle and per-tenant or
        private repositories always build from source inside the app's own restore, as
        today, and are never uploaded; the binary's provenance records its source
        repository and digest, and the uploader refuses anything else. Sharing binaries of
        a private repository would need a tree per audience: its own decision. A probe
        row: a lockfile naming a remote package with a CRAN package's name and version
        never produces an upload, and the CRAN package's binary is unchanged. Which
        digest is trusted (604d63b N1): CRAN's index publishes MD5sum only, so the
        builder trusts forge's SHA-256 of the tarball as first fetched, cross-checked
        against CRAN's MD5sum; a tarball CRAN republishes under the same version is
        refused and built from source in the app, never overwriting a shared binary.
        A package's sandbox installs its dependencies only from the shared public tree,
        never from the triggering app's lockfile (604d63b N2): otherwise a tenant's
        remote override of an Imports or LinkingTo dependency would run inside the
        compile of a shared public binary.
      - Skald compiles each such package version a build needs and forge lacks, ONCE per
        platform, each package in its OWN sandbox (the T5 worker profile, no secrets, the
        package's dependencies installed as binaries), so one package's install scripts
        can never touch another package's binary. Never inside an app's restore step,
        which builds every package of the app in one library.
      - The binary is uploaded by a trusted Skald component OUTSIDE the sandbox, holding a
        forge token scoped to the platform's tree; nothing in a sandbox ever holds it.
        The upload records provenance (the package, version, source digest, builder
        image digest, LinkingTo versions; forge keeps them too).
      - Residual, stated: a LinkingTo-only dependency runs during the compile and could
        tamper with that one package's binary; forge records LinkingTo versions, so it is
        traceable, and removal is one audited forge call.
      - The first app's build does not wait at first: it restores from source as today,
        and the per-package compiles run beside it for the next apps. Waiting on parallel
        per-package compiles is a measured follow-up, adopted if it beats the serial
        restore. Until then the first build and the queue compile the same packages at
        once, about twice the CPU: the queue's admission limits leave room for running
        builds, so a burst of first R publishes cannot starve them (c18c5ab N1).
      - The platform the binaries are registered for is Skald's published R base (its
        digest is forge's platform registration), so a binary always matches the runtime
        it is installed into. A base change is a new platform registration.
      - An operator's own CI builder (forge's documented helper) remains possible beside
        it; both upload to the same platform tree.
      - New trust boundary: flag the forge write credential's storage (encrypted, never
        logged), the uploader's egress (forge only), and the per-package queue's limits
        (admission, retries, a package that never builds falls back to source with a
        visible reason). Security review before it is enabled.
      Steps: **Step 1, done (this commit's part):** the new forge pinned; each catalog
      base records its OS codename (checked against /etc/os-release by the bases probe);
      `skald.builds.worker.cran-linux-binaries` (opt-in: a mirror without forge's layout
      404s and renv does not fall back) makes R restore from
      `<cran-mirror>__linux__/<codename>/latest`; the switch is in the cache key only when
      on, so keys made before it are unchanged (pinned by a test), and in provenance. The
      pass probe runs with it on: cold R 295 s, still from source (no binaries yet).
      **Step 2, the binary builder (design, 2026-10-11):**
      1. *Whose platform (53b63ec N2).* forge binds each key `<codename>-<arch>/<rminor>`
         to ONE builder image ID and refuses uploads that report another; the URL names
         only the distro. So the operator registers Skald's published R base image (its
         image ID, the config digest) as the platform for that base's key, and gives Skald
         a write token scoped to exactly that key's tree,
         `bin/linux/<codename>-<arch>/contrib/<rminor>/**` (c365d95 N3: a codename-wide
         scope would reach keys registered to other builders), stored like every secret:
         encrypted, never logged. Skald reads the registration at startup and again
         before each R build and each upload (a cached read with a short TTL; c365d95 N1:
         an admin can re-register a key while Skald runs), and turns binaries on only for
         a base whose image ID it names; any other base, including a second R base of the
         same minor release, restores from the plain mirror, and an upload is refused,
         with the reason logged. Reading a registration needs a forge admin
         today: code_review/forge-issues/004 asks for a read with ordinary access; step 2
         depends on it.
      2. *Which packages.* After an R build succeeds, the packages of its rendered lock
         (RenvLockPolicy already refuses remote, VCS and local sources) are candidates.
         One is built only if its source tarball, fetched from the mirror on the trusted
         side, has the SHA-256 forge serves and matches the MD5sum in the PUBLIC index of
         forge's CRAN proxy member (not the group, which also holds the operator's hosted
         packages); else never. A version whose tarball changed since its first fetch is
         refused and left to source builds. Missing is asked per package: a HEAD on the
         binary URL with the base's R User-Agent, `X-Forge-Package-Type: binary|source`.
      3. *The queue.* A table of its own (one row per platform, package and version:
         state, attempts, lease, source digest, provenance, the reason a row was refused),
         claimed with the build coordinator's lease pattern, so several Skald instances
         share it; a row runs only once its dependencies (Depends, Imports, LinkingTo,
         read from the verified tarball on the trusted side; base packages excluded) have
         binaries, and missing dependencies are queued first. Concurrency is its own limit
         (default 1), and admission leaves app builds their slots (c18c5ab N1). A schema
         change: V4, planned here, reviewed with the code.
      4. *The sandbox.* One package per worker, the T5 profile, no secrets, and NO
         network: everything it installs is handed in by the trusted side (c365d95-F1).
         Its context holds the verified source tarball and, for every dependency in its
         CLOSURE (everything reachable over its edges: R loads the whole Depends/Imports
         tree; 0fd04e4-F1), the binary the trusted side fetched from Skald's own binary tree for this key and
         checked against that dependency's recorded upload digest; never the group's
         URL, which also serves the operator's hosted packages and falls back to source,
         and never an app's lock (604d63b N2). A row waits while its dependencies can
         still become Skald-built binaries (point 3 queues them first), and is refused,
         with the reason, once one cannot (refused, failed, or a binary Skald did not
         produce; 1fba61d N3); nothing compiles a dependency inside another package's
         sandbox. `R CMD INSTALL` builds the package
         into an empty library, which is packed and exported to the trusted side.
      5. *The upload, outside the sandbox.* The trusted side checks the archive (one top
         directory named for the package; DESCRIPTION Package and Version as queued;
         Built: the base's R and platform), computes LinkingTo versions from what IT
         handed in, rechecks the registration, and PUTs it with the base's image ID.
         Before the PUT it records an upload intent (the digest about to be sent and its
         provenance, fenced, append-only, kept across the reaper; 1fba61d-F1): the PUT and
         the row's write are not atomic, and a restart or a lost lease between them leaves
         Skald's binary in forge with a row that says nothing of it. A 409 fetches the
         existing binary's digest: one of the row's own intents is an earlier attempt's
         upload and makes the row UPLOADED with that intent's provenance; any other digest
         is "uploaded by another, digest X", visible rather than claimed (c365d95 N2).
         Provenance per binary: package, version, source SHA-256 and MD5, builder image,
         LinkingTo versions, worker profile, attempt.
      6. *The rows that must go red under their mutants:* a second app with the same
         packages installs them as binaries (and the time it saves); a remote package with
         a CRAN name and version is never uploaded and the CRAN binary is unchanged; a
         hosted package shadowing a public dependency's name never reaches a public
         package's sandbox; a sandbox has no network; a changed tarball is refused; no sandbox sees the token (probed from inside one);
         one package's sandbox cannot touch another's output; a base whose registration
         names another image falls back to source. The probe runs its own CRAN-like
         upstream behind forge's proxy, so it can publish test packages as "public".
      7. Off by default, enabled per deployment only after the security review T7b
         requires; the review records that integrity then rests on forge and the uploader
         (renv.lock's hashes cover DESCRIPTION metadata, not binary bytes: 53b63ec N3).
      **Step 2a, done (this commit's part): the queue.** V4 adds `skald.binary_build` (one
      row per key, package and version; the key, name and version in forge's and R's forms
      only, since each becomes a forge path segment) and `skald.binary_build_dep` (edges
      within one key). Two leased phases: PENDING_SOURCE -> VERIFYING (the trusted side
      fetches and checks the source, step 2b) -> QUEUED with the source digests and the
      edges; QUEUED -> RUNNING (a compile, step 2c) -> UPLOADED with Skald's own binary
      digest and provenance. PRESENT_FOREIGN records a binary Skald did not produce, with
      its digest, and is never handed in (89a33dc N1); REFUSED names its code; FAILED after
      `max-attempts` claims of a phase (default 2). A lost lease goes back to its phase's
      waiting state. A row is claimed only when every dependency is UPLOADED, and the claim
      carries exactly those digests; a dependency that ends any other way refuses its
      dependents (transitively, with the reason); a dependency cycle is refused when its
      edges are recorded, under a per-key lock. The database holds that the identity and the
      source digests recorded at first fetch never change (a trigger), so a tarball
      republished under the same version can only be refused. Compiles yield to app builds:
      claimed only when no app build is QUEUED, fewer than the app limit run, and fewer than
      `skald.builds.binaries.max-running` (default 1) compiles run; app claims never count
      compiles, so the host's worst case is the two limits added. Not wired yet (step 2e).
      Fixed in review (1fba61d-F1): each attempt records its upload intent before the PUT,
      and a 409 whose digest is one of the row's intents is Skald's own (point 5).
      **Re-opening (1fba61d N1, step 2e):** REFUSED, FAILED and PRESENT_FOREIGN are final for
      the queue itself, so a flaky dependency cannot loop. An operator re-opens them by an
      audited admin action: a FAILED row (after fixing the cause), a PRESENT_FOREIGN one
      (after removing that binary from forge; the action checks forge no longer holds it),
      and the rows refused because of it, which go back to waiting. V4 allows exactly that
      transition and nothing else: a foreign digest may be cleared only as its row returns
      to waiting; an UPLOADED digest and the source digests never change.
      **Step 2b, done (this commit's part): the source check.** `SourceVerifier` claims a
      PENDING_SOURCE row and reads, from `skald.builds.binaries.source-repository` (forge's
      CRAN PROXY member, never the group; unset by default), the index
      `src/contrib/PACKAGES` (cached per TTL) and the tarball, in one streaming pass that
      digests every byte received (SHA-256 and MD5) and reads only `<pkg>/DESCRIPTION`
      (`SourceTarball`: one top directory named for the package, no '.'/'..' segments,
      DESCRIPTION one regular file, one name source per member, checksummed headers, bounded
      bytes, expansion and wall time, the lease renewed while it streams). Refused, with the
      code: NO_LOCK, NO_PUBLIC_INDEX, NOT_IN_PUBLIC_INDEX, NOT_IN_PUBLIC_SOURCE, MD5_MISMATCH
      (against the index), SOURCE_CHANGED (against a first fetch), DESCRIPTION_MISMATCH /
      _UNREADABLE, NOT_IN_LOCK, CONSTRAINT_UNMET, TOO_LARGE, and the tarball's own
      (TARBALL_UNREADABLE, NOT_ONE_TOP_DIRECTORY); a transfer or repository error is a failed
      attempt. Dependencies (Depends, Imports, LinkingTo; R and the 14 base packages
      excluded; recommended packages queued like any CRAN package) resolve to the versions
      of the triggering build's rendered lock, which 2e records once per build
      (`skald.binary_lock`, append-only; a planned V4 addition). Which digest is trusted:
      forge has no stable API for a version's SHA-256 (only its UI's JSON), so the trusted
      side computes it over the bytes it read, cross-checked against the index's MD5sum,
      and V4 keeps it from then on; forge checks the source again at upload.
      **Archived versions (decided by the user 2026-10-11: option 2; the paragraph below is
      the question as it was put).** With `skald.builds.binaries.source-archived=true` (off by
      default), a version the index does not list -- gone from it, or older than the current
      release -- is fetched from the proxy member's `src/contrib/Archive/<pkg>/`, its first
      fetch's SHA-256 and MD5 recorded and never changed (SOURCE_CHANGED for any later
      different fetch) and `source_kind` 'archive' (V4) in place of 'index'; an index older
      than the lock is still deferred, never taken as archived. forge serves Archive paths
      and checks an upload against them too (linux.go:195), though from its sourceRepo, which
      its docs set to the group: forge-issues/005. The question as it was put:
      **Archived versions.** CRAN's index lists only each package's
      CURRENT version; an archived version (any lock older than the package's last
      release) has no published checksum, so under point 2's rule it is refused
      (NOT_IN_PUBLIC_INDEX, the detail says "archived") and its apps restore it from
      source, as today. Building it anyway would trust the proxy's first fetch alone
      (no cross-check); that relaxes point 2's rule and is the user's call.
      Measured (e149183 N4) on the R fixture lock (dev/fixtures/apps/r-shiny, dated
      2026-10-02) against CRAN's index of 2026-10-11: 11 of its 46 packages are no longer
      current (KernSmooth, MASS, Matrix, class, cluster, lattice, nlme, nnet, spatial,
      survival -- R's recommended packages -- and renv), and refusal cascades to one more
      (mgcv): 12 of 46 refused on a nine-day-old lock, more as locks age. Ten of the eleven
      are recommended packages the base image already carries in R's system library, at
      the version R shipped. **Planned for 2c (decided, best for users, no rule relaxed):**
      a dependency whose locked version equals the one in the base image's system library
      is not queued; the sandbox already has it from the trusted base, and the base's
      digest is the platform registration, so the binary matches what every app has. That
      leaves the archived question to genuinely archived CRAN packages. The base image's
      library versions come from the trusted side (the catalog or the platform
      registration), never from inside a sandbox (9262bd8 N2).
      **An index behind the lock (9262bd8-F1):** forge caches a proxy's index for 24 h by
      default, so a version released today can be newer than the index for a day. Such a
      row is deferred, not failed: back to PENDING_SOURCE with no attempt counted, waiting
      5 min doubling to 6 h, and refused INDEX_BEHIND only 48 h after its first deferral
      (`source-behind-*`). Renewals and deadlines run on separate timers, so a renewal
      blocked in the database cannot delay a deadline (9262bd8 N1).
      **Step 2c, part 1, done (this commit's part): the sandbox's recipe and the check of
      its output, no Docker yet.** The catalog's R base records `r_platform` and
      `r_recommended` (R's system library of recommended packages by exact version, the 15
      R 4.6.1 ships), checked against the built image by dev/bases-probe.py; BaseCatalog
      requires them for an R base and refuses them on any other, and derives forge's key
      (`noble-x86_64/4.6`). The source check no longer queues a dependency the base carries
      at the locked version (e149183 N4). `PackageRecipe` renders one package's sandbox:
      `FROM <base by digest> AS build`, the base's non-root user, the source and each
      dependency's binary COPYed in after their digests are checked one last time against
      the queue's record (89a33dc N1), one `RUN --network=none` (89a33dc N2) that unpacks
      the dependencies into a library of their own, `R CMD INSTALL`s the package into an
      empty one, requires it to hold exactly that package, and packs it; and a `FROM
      scratch` stage exporting only `/binary.tar.gz`. `BinaryArchive` checks that file on
      the trusted side before any upload: one top directory named for the package, regular
      files and directories only, no unsafe segment, DESCRIPTION once naming this package
      and version, `Built:` this R, this platform or none, unix, `Meta/package.rds`
      present, bytes / expansion / entries bounded. Smoke-run by hand with docker buildx on
      the pinned rocker upstream: rlang 1.3.0 (compiled) built in 12 s with no network and
      passed the check; ellipsis 0.3.3 built against rlang's handed-in binary. Part 2: the
      run in a Skald worker (local output collected by the trusted side), the driver, and
      its probe rows (a two-level chain among them).
      Fixed in review (0fd04e4-F1): the claim hands in the dependency closure, not only the
      first level (A -> B -> C failed without C: "lazy loading failed"); the recipe refuses
      two versions of one package in a closure. (Superseded in part 2: two versions are resolved to the one
      the claimed row's lock fixes, ba5dd88 N1.)
      **Step 2c, part 2, done (this commit's part): the sandbox run.** `PackageBuildDriver`
      hands in (the source and every closure member's binary, fetched by a `HandIn` the
      trusted side implements over forge in 2d), resolves a closure's two versions of one
      package to the one the claimed row's lock fixes (else a failed attempt,
      DEPENDENCY_CONFLICT; ba5dd88 N1), renders PackageRecipe, launches a T5 worker whose
      gateway allows the base's registry and nothing else (its Config refuses mirrors and
      private repositories), stages the context, runs a local-output client (buildctl
      `--output type=local` into the attempt's own output volume; the registry credential the
      client's alone, as for a push; no proxy build arguments, no attestation), collects the
      exported file through the Docker API from the stopped client (exactly `/out/binary.tar.gz`,
      bounded; anything else exported is refused), and checks it with BinaryArchive. Outcomes:
      Built (bytes + checked), Refused (HANDIN_DIGEST, OUTPUT, the archive's codes), Failed
      (HANDIN_FETCH, WORKER_LAUNCH, STAGE, BUILD_FAILED with the last line, WORKER_DIED,
      DEPENDENCY_CONFLICT), Stopped (the lease's signal; worker killed, everything disposed).
      Package attempts are named "p" + 24 hex of the row id + 8 hex of the lease generation
      (33 characters), never an app build's 32 hex, so T7's attempt
      sweeper never disposes of a running sandbox; the sweep's by-name volume inventory covers
      the output volume and is held to the launcher's volume list by a test (9159de6-F1).
      **Carried to 2e:** sweeping a dead
      sandbox's leftovers against the queue's leases; the sandbox's full log to object
      storage (today the failure's last line); the version-conflict rate measured on two
      fixture locks weeks apart (ba5dd88 N1); the probe rows on a real R base (a two-level
      chain, no network from inside the install, no sandbox touching another's output).
      **Step 2d, part 1, done (this commit's part): forge's registration and the gate.**
      forge is pinned to 8545709 (CI's image, sha256:55579712...; forge-issues/004 fixed:
      a registration is readable with any valid token; since 2e-3, aade5e3, sha256:170575bc...,
      which also fixes 005-008). `skald.builds.binaries.forge-*`:
      forge's URL, the binary repository (cran-linux), the write token -- a secret given by
      the deployment's configuration like the registry password: never in the database,
      never logged (toString masks it), only ever sent by the trusted side's HTTP client --
      the registration TTL (60 s) and a timeout. `ForgePlatforms` reads
      `/api/v1/cran/platforms/<key>` with that token, requires the answer to be that key's
      registration with a sha256 builder digest, caches it for the TTL (c365d95 N1) and never
      caches a failure. The published bases record each base's image ID (`image_ids`, its
      config digest, written by dev/publish-bases.py from the image it pushed; optional and
      checked by BaseCatalog), since forge's registration names the image ID, not the
      manifest digest. `BinaryGate` turns binaries on for a base only if forge registers its
      key for that very image ID and R platform; anything else -- no key, no image ID on
      record, an unreadable registration, none, another builder or platform -- is off, with
      the reason in words. Wired before each R build and each upload in 2e/2d part 2.
      Published bases must be published again (the catalog changed in 2c, and the image IDs
      are new).
      **Step 2d, part 2, done (this commit's part): the hand-in and the upload over forge.**
      `ForgeHandIn` (binaries; the interface `PackageHandIn` moved there so the worker package
      depends on binaries and not back) fetches the source from the proxy member -- the
      Archive path for an 'archive' row -- and each closure member's binary from Skald's own
      tree for the key with the token, bounded in bytes and by a TransferGuard deadline; every
      failure an IOException (a failed attempt, never a refusal: 9159de6 N2); the lock is the
      requesting build's (BuildClaim now carries requested_by_build and source_kind).
      `BinaryUploader`: the gate again (and the base's key must be the row's), LinkingTo
      versions from what was handed in or the base's library, the intent with provenance
      (`skald.binary-provenance/v1`: source digests and kind, base reference and image ID,
      the resolved closure handed in -- 9159de6 N4 --, LinkingTo, attempt, sandbox profile)
      recorded before the PUT, then the PUT with X-Forge-Builder-Digest and
      X-Forge-Linking-To; 201 UPLOADED, 409 the existing binary's digest to the queue (Skald's
      own earlier upload or another's), 400 REFUSED with forge's reason, 403/5xx/transport a
      failed attempt. No redirects anywhere; the token in no record, audit or message.
      Fixed in review (ae287bf-F1): every forge response body (the PUT's answer, the existing
      binary after a 409, the registration) is read under a TransferGuard deadline, like the
      hand-in's; an upload has its own deadline (`forge-upload-deadline-seconds`, 600 s).
      **For 2e (ae287bf N2):** the claim's lease must be renewed by the binary loop across
      the whole attempt -- hand-in, sandbox and upload -- since only the sandbox phase polls.
      **Step 2e, part 1a, done (this commit's part): where app builds meet the binaries.**
      `BinaryHook` (binaries) is consulted by `BundleContextSource` for every R build when
      cran-linux-binaries is on: the gate decides for that build (c365d95 N1), the restore
      uses forge's __linux__ URL only when it says this very base is registered, otherwise
      the plain mirror, and the build's provenance records the decision and its reason (the
      cache key follows the mirrors actually used). The prepared context carries an
      `onBuilt` callback the driver runs once the image is built (never the build's
      failure); for an R build with binaries on it hands the rendered lock to
      `BinaryEnqueuer`, which records the lock (dependencies resolve against it) and queues
      every package version on the base's key except those the base carries at the locked
      version, each independently. `PackageBuildDriver.Built` carries the closure actually
      handed in, for the upload. Part 1b: the binary loop, the reaper, the package-attempt
      sweep and the wiring (off by default).
      Fixed in review (a16cf2a-F1): onBuilt runs only once the attempt's outcome is settled as
      Built, on its own daemon thread, so neither its length nor a stop can undo a pushed
      image; the enqueuer no longer asks forge again (N2: the prepare-time decision, and the
      uploader's recheck before any upload). **For 2e (a16cf2a N1):** a lock entry the build
      installed from forge's HOSTED member (a private package) is queued too, then deferred
      for 48 h at the proxy and refused INDEX_BEHIND: measure it, and consider a cheap
      "absent at the proxy" check at enqueue time.
      **Step 2e, part 1b, done (this commit's part): the loop and the wiring, off by default.**
      `BinariesConfig` exists only with `skald.builds.binaries-enabled=true` beside an
      enabled worker, and refuses to start half-configured (a database, forge-url/token,
      source-repository, the worker's cran-linux-binaries switch), every problem at once;
      forge's clients are created once. It hands the build loop's context source the
      BinaryHook, and runs `PackageBuildLoop`: one thread verifying sources, `max-running`
      threads compiling. A compile asks the gate for its key BEFORE any sandbox (ef929f5 N1:
      none registered -> a failed attempt, REGISTRATION), renews the lease on a timer across
      hand-in, sandbox and upload (ae287bf N2) with a failed renewal as the sandbox's stop
      signal, and maps the outcome (Built -> the upload; Refused -> refuse; Failed -> fail;
      Stopped -> nothing; every write fenced, so a lost lease writes nothing). One driver per
      published R base, the base's registry its only egress. `BinaryHousekeeping` reaps on the
      renewal cadence and sweeps, on the worker's sweep period, every "p" attempt on the host
      that no RUNNING row owns at that generation (never an app build's).
      Fixed in review (fca2f81-F1): the sweep takes the host's inventory before the live
      leases, as AttemptSweeper does, so a sandbox claimed and launched during the inventory
      is never disposed of.
      **Step 2e, part 2, done (this commit's part): re-opening, the queue's side.**
      `BinaryBuildQueue.reopen(row, actor, forgeNoLongerHoldsIt)`: a FAILED, REFUSED or
      PRESENT_FOREIGN row back to PENDING_SOURCE, verified again (its source must match its
      first fetch's digests, its dependencies are read again: a cycle refusal never compiles
      without its edges, 90cf453-F1), attempts, deferrals and the old deadline reset, and every row refused DEPENDENCY_NOT_SKALD_BUILT
      that depends on it, transitively, back to QUEUED; audited with the actor; source digests,
      kind and upload intents kept. PRESENT_FOREIGN only once the caller confirms forge no
      longer holds that binary (its digest is cleared). A row FAILED by a lost upload write on
      its last attempt (932cbd1 N1) is re-opened WITHOUT removing its binary from forge: the
      next 409 matches its own intent. **Needs the user:** an HTTP admin endpoint for it is a
      new public API shape (CLAUDE.md: ask first); until then it is the queue's method only.
      Then: the per-package builder and uploader; the pass probe measures a cold R build
      (binaries absent), the per-package compiles, then a second app's R build (binaries
      present), with a probe row proving a package's sandbox cannot write another
      package's binary and that no sandbox sees the token.
      **Step 2e, part 3, done (this commit's part): the probe, end to end.**
      `python3 dev/t7b-probe.py` (Java half `T7bProbeTest`, enabled only by
      `-Dskald.t7b.forge-port`): forge aade5e3 with `-auth` and a config file, its CRAN proxy
      fronting the probe's own CRAN-like upstream (`dev/t7b-upstream.py`: CRAN's index with the
      probe's packages from `dev/fixtures/t7b/packages/` merged in), `cran-linux` with
      `sourceRepo: cran-proxy`, the platform registered by forge's own manifest script (its
      builder digest equal to the published base's image ID), a token scoped to
      `bin/linux/<key>/contrib/4.6/**`; the application's own configuration with the worker and
      binaries on. Rows (run of 2026-10-12): A, the fixture lock plus nine probe packages, cold:
      restore 154 s from source; B admitted at once (the compiles yield); 40 rows
      uploaded in 3792 s at max-running 3, each served by forge with its recorded digest
      and, to an R client of the platform, as a binary through the group's `__linux__` URL;
      the three-level chain in order; skald.old 1.0 from the Archive; skald.shadowed built from
      the PUBLIC tarball (its recorded digest), and served to clients as the hosted source
      that shadows it; skald.private (hosted only) deferred, never uploaded; skald.changes
      republished upstream once verified: forge pins the bytes it cached first, and the
      binary is of those (forge-issues/008: HANDIN_DIGEST and SOURCE_CHANGED stay unit-tested
      only, since forge never serves changed bytes); C, the same packages with the binaries:
      restore 19 s (8.0x); D, the registration re-pointed at another image: back to
      source, with the reason in provenance, no rows added; E, a remote package: refused,
      no row. skald.netprobe's install, read back from its binary in forge: interfaces `lo`
      only, forge unreachable, no token in any process environment or file, uid 10001.
      Measured: hosted-only packages become a deferred row each (a16cf2a N1: one here, 4
      deferrals over the run, never a binary); version conflicts in the probe's locks 0.
      Forge issues found and fixed on the way: 005's addendum (a member `sourceRepo` was
      never served), 008 (index and pinned tarball disagreed); open: 009 (HEAD 404, cosmetic).
      **The rows under their mutants (5b80946 N1-N3):** `dev/t7b-probe.py --mutants all`
      (short mode: skald.netprobe and skald.shadowed queued as an R build's success queues
      them, a fresh forge per run). Control: every row green. Each mutant red on exactly its
      row: the install RUN with the worker's network (interfaces `lo eth0`, a refused connect
      to the on-link bridge counts as reached -- N1: the old name-based check could not
      fail); the forge token as an ENV, as a context file, and only on a tmpfs mount written
      from base64 (N2: the search no longer stops at the root filesystem); a docker config's
      `auths` in the context (N3); sources read from cran-hosted (caught first by CranIndex:
      forge's hosted index has no MD5sum, so the row defers) and, with that layer and the
      MD5 check removed too, the row records the HOSTED digest -- and forge's own upload
      check against cran-proxy refuses the binary (FORGE_REJECTED, DESCRIPTION Packaged).
      **The fixed cost (moved here from T10, user 2026-10-10):** ~45-60 s before any
      recipe step (six launch containers in sequence, and every attempt pulling and
      extracting its base into an empty workspace). Pre-extract each published base into
      a platform-owned Docker volume the worker mounts read-only and never writes (never
      a host path: builds get no host mounts; no tenant data is shared), and keep a small
      pool of pre-launched, never-used workers. (Superseded for the base by step c's route below:
      each pooled worker extracts its base into its own store, no shared volume.)
      **Measured per phase (2026-10-16, before any of it).** Each attempt now writes where its
      time went: an app build's log ends with `[skald] phases: total T s: ...` (the pass
      probe records it per build with buildctl's step times, results.json `phases`/`steps`),
      a package attempt logs the same line (dev/t7b-probe.py prints each phase's median and
      maximum). The pass probe's cache rows, seconds from the driver's start to the end of its
      disposal (the claim and the PUBLISHING write are outside it; 0acd9f2 N2):
      | phase (s)            | Py cold | Py source-only | R cold | R source-only |
      | launch.network       |   0.9   |   0.7          |  0.4   |   0.5         |
      | launch.conf (helper) |   4.2   |   3.1          |  2.9   |   3.3         |
      | launch.gateway       |   5.7   |   3.1          |  3.1   |   3.9         |
      | launch.quota (helper)|   4.4   |   4.1          |  3.3   |   4.0         |
      | launch.chown (helper)|   3.4   |   3.2          |  3.6   |   3.4         |
      | launch.worker        |   5.7   |   2.8          |  2.2   |   2.7         |
      | stage + client.start |   4.4   |   5.5          |  3.8   |   3.9         |
      | build (buildctl)     |  38.5   |  13.4          | 206.7  |  24.1         |
      | dispose (2 helpers)  |  11.6   |  10.8          |  10.9  |  11.4         |
      | total                |  78.9   |  46.8          | 237.0  |  57.2         |
      So about 33 s of every attempt is fixed cost outside buildctl (launch ~20, stage and
      client ~4, disposal ~11), each helper container costing 3-4 s on this host. Inside
      buildctl, R's source-only build spends 12.4 s on the cached restore step, most of it
      extracting the base's layers again (6.9 s for the largest) and the cached layer, and
      10.7 s exporting and pushing. Targets: a source-only republish under 30 s (R 57, Python
      47 today), a small first publish under 60 s (Python cold 79).
      **Step b, done (this commit's part): fewer containers.** A bare `docker run --rm busybox
      true` takes 3.2 s on this host, so each container avoided saves about that. One
      privileged setup helper (network none, a fixed command; the two texts still travel as
      environment values written with printf %s) writes squid.conf and buildkitd.toml, gives
      the socket volume (and the output volume, `Request.output`, made by the launch now) to
      the worker's uid, and makes the workspace with `mkfs.ext4 -E root_owner=2401:2401`: the
      conf, quota, chown and outchown helpers are gone. The gateway and the worker then start
      side by side (`inParallel`: returns only once both ended, whatever either did). The
      disposal's cleanup helper prints the quota state after its work, so the check helper no
      longer runs in dispose(); the containers are removed on parallel threads, the worker's
      first. Measured through LauncherCli, three cycles each: launch 16.7-17.2 s -> 10.3-10.5 s
      (setup 3.9, gateway and worker 4.1-4.3), dispose 11.6-11.9 s -> 7.7-8.5 s (containers 3.8,
      the worker's kill bounding it; cleanup 3.2-3.9).
      **Steps c and d, part 1, done (this commit's part): the warm pool, not wired yet.**
      *Route change for step c (ef9afe2 N1), decided for users:* the base is pre-extracted
      INSIDE each pooled worker, not into a shared read-only volume. BuildKit's OCI worker
      (v0.33, overlayfs snapshotter) keeps its content store, snapshots and metadata under its
      own writable --root and has no option to take external read-only layers or a shared
      content store (that is the containerd worker's); a volume holding an extracted rootfs
      could only become a build's base by copying it (COPY --from a named context: a full
      copy, slower than the pull). So each never-used pool entry is launched exactly as an
      attempt's worker and then warmed by a trusted client (`FROM <base by digest>` and
      `RUN ["true"]`, no output, the registry credential the client's alone, the Dockerfile on
      its own tmpfs): the base is pulled and extracted into that worker's own store before any
      attempt sees it. Same effect on the critical path (WorkerPoolTest: a warmed entry's
      build neither downloads nor extracts the base; a cold worker's does), and better on
      isolation: no new mount in the worker and nothing shared between attempts, so T5's
      isolation probes and the bounds probe's mounts row are unaffected by step c (they are
      still rerun when T7b closes, since steps b and d changed the launch around the worker).
      *The pool (`WorkerPool`):* per published base, `size` ready entries (default 1; 0: no
      pool), each used by one attempt and then disposed of with it. An entry's id is "w", its
      launch time (8 hex, epoch seconds) and 24 random hex digits: never a build's 32 hex nor
      a package attempt's "p" id, so neither sweeper takes it, and any instance can tell its
      age. An attempt takes one by a marker volume (`skald-taken-<entry>`, labelled with the
      attempt and the entry; a second attempt's take of the same entry is refused); the
      attempt's kill, container removal, disposal and leftovers follow its markers, so the
      drivers' stop paths and both sweepers reach a taken entry through the attempt. One pool
      thread makes every Docker call of the pool: it disposes of its own untaken entries at
      the ttl (30 min) and replaces them, hands out only entries younger than ttl - 5 min,
      and every 5 min disposes of any instance's UNTAKEN entry older than ttl + 5 min (a dead
      process's: no live owner keeps one that long); a taken one is its attempt's. A failed
      launch or warm-up is disposed of and the base waits a minute.
      **Step d, part 2, done (this commit's part): the drivers take from the pool.**
      BuildKitDriver takes an entry by the build's base (inputs.base.reference) and falls back
      to its own launch when none is ready; the package driver takes by its R base. One pool
      per driver, each with that driver's own request (egress, output, quota), so an app build
      never gets a package sandbox's entry nor the reverse (4838310 N2). The provenance records
      the entry ("pool": entry, what warmed it, never_used). The refill counts only takeable
      entries (younger than ttl - 5 min), so an idle base always has one to hand out (4838310
      N1). A taken entry's disposal skips the attempt's own cleanup helper when the attempt
      made no network of its own. For app builds, once the outcome is settled (never after a
      stop), the disposal runs on a `Disposer` thread: the outcome is reported before the ~8 s
      disposal, the work thread having ended (no create in flight) and the sweep's grace
      (15 min; refused at 3 min or less) covering a crash. Package attempts dispose inline: the
      row settles when the driver returns and the package sweep has no grace (bc66e6f-F1). Configuration: `skald.builds.worker.pool-size` (0-8, default 1; 0 turns the
      pool off) and `pool-ttl` (at least 15 min, default 30 min). Idle cost (4838310 N4): each
      ready entry holds a gateway, a worker, a loop device and its quota image, so pool-size x
      the published bases for the app builds, plus the same per R base for the package
      sandboxes. Pass probe, pooled (43/43 green): Python source-only 46.8 -> 18.5 s, R
      source-only 57.2 -> 25.3 s, Python lock-comment 15.7 s, a small first publish of new
      content (Python other-content) 37.7 s; both targets met. A refill runs beside the next
      build and competes with it on this host (Python cold's client start took 29.8 s once
      while the R base refilled).
      **Pass:** the targets above met and measured per phase; the binary rows' isolation,
      source and token rows red under their mutants; T5's isolation probes rerun green,
      the bounds probe's mounts row among them, since steps b and d changed the launch around
      the worker (c18c5ab N2; 4838310 N3).
      **Done (2026-10-16, HEAD 4160e3a).** Full suite through skald-test.sh: 1537 run, 0
      failures, 7 skipped. T5's RUN-step probes through the platform's own launcher
      (SKALD_LAUNCHER=java): attacks 6/6 contained, egress 31/31, bounds 11/11 held (the
      workspace mount still nosuid,nodev; the loop device released). The T7b probe 25/25:
      40 binaries in forge, each served with its recorded digest; the sandbox rows (network,
      forge token, registry credential, user) green; restore 210 s from source -> 22 s with
      binaries (9.4x); nothing left on the host. Its mutants: the control green, all 7 red
      on their own rows. Package attempts (40, three in parallel, beside the pool's refills):
      median 207 s, of which the build is 56 s; the launch's setup helper (median 43 s, 3.9 s
      on an idle host) and the loop's release (13 s) dominate the rest. That is host
      contention under the queue's parallelism, and a pool of 1 per R base is mostly empty
      at that rate; it costs queue throughput only, which no user waits on.
      **Re-closed (2026-10-18, 14fd2e4; 3d18996-F1).** The third target, an R first publish
      whose packages all have binaries, was measured only as a restore ratio and took 138 s:
      every first push uploaded the whole base again, to the image's repository and to its
      cache's. The platform now mounts the base's blobs into both before the build
      (BlobMounter, about 1 s). The probe's row "first publish, binaries" (C, start to
      publishing) is 50.6 s, the reviewer's rerun 52.2 s, against 60 s. Its condition is a
      settled host (user, 2026-10-17): before C the probe waits, at most 15 min, until both
      pool groups are warm, no other build or helper runs and the 1-minute load average is
      under 2, and the row fails if the host never settles. Without that condition the same
      code took 86.9 s (no wait) and 102.6 s (right after the 40-package drain): a first
      publish that lands while the package queue drains takes well over a minute on this
      host. T7c's capacity-bounded pool is where that case belongs.
      **Remeasured (2026-10-19, 2c7fb45, R base r3; 4f218cd N1, 0a9386d N2, 2c7fb45 N1).**
      With the app build's two solves (0a9386d) the full probe is 25 of 26 green; row C
      took 71.0 s against 60 s, after a 320 s wait for a settled host: client.start 3.6 s,
      build 63.2 s (restore 20.9 s with 41 packages downloaded in 0.6 s, the second solve
      all CACHED, then layers exported in 8.3 s and pushed in 36.7 s while the cache export
      ran 28.9 s beside it). The second solve is not where the time went: an A/B of six
      app builds each (80 MB layer, the probe's worker defaults, interleaved on this host)
      gave a minimum of 31.4 s with two solves and 30.8 s with one, medians 53 s and 64 s,
      ranges 31-153 s and 31-122 s. The gap to 50.6 s is the push to the local registry
      on this WSL host, whose spread a single C sample cannot resolve. **The user accepted
      about 70 s (2026-10-19)**: the probe's target for C is now 75 s, so this run's 71.0 s
      passes and a real regression still fails.
      The 40-package drain took 6758 s with no package lease lost (d6fbcde, 4219fc0); the
      run's four reaps are the app builds A-D, each reaped INTERRUPTED one lease (now
      300 s) after PUBLISHING, as designed until T9. The session was NOT always kept
      (2b9902b-F1): jsonlite 2.0.0's attempt 2 failed after a 804.8 s build with
      "failed to solve: DeadlineExceeded: no active session for val03nrits62l2cjmn94h294g:
      context deadline exceeded", and magrittr 2.0.5's attempt 2, ending 4 s later after
      432.8 s, with "failed to solve: Canceled: rpc error: code = Canceled desc = context
      canceled" (2b9902b N1); both uploaded on attempt 3. Both builds spanned a stall of
      the whole host: the 5-minute housekeeping sweep logged nothing from 01:03:21 to
      01:13:31 (and from 00:46 to 00:57, 01:25 to 01:36), a pool entry's warm took 803.8 s,
      and both attempts ended as it came back. The failure is kept only in the row's
      error_detail, so no log showed it; which of the two solves lost the session is not
      in the run's artefacts (package build logs go with the teardown). The probe now
      judges a row "session kept" over every row snapshot's error_detail, the app builds
      and every log, and lists each failed attempt (red on this run's results.json; green
      with the rows' details removed). The host after the probe and after the A/B: nothing of the run's
      left, the A/B's test edits restored and checked by sha256.
      **Carried on:** the security review T7b requires before binaries are enabled in any
      deployment (integrity then rests on forge and the uploader); the HTTP re-open endpoint,
      now needed only for PRESENT_FOREIGN (a person removes forge's foreign binary first),
      since a FAILED row goes back to the queue by itself (T7c step 4, user 2026-10-19); its
      shape still waits for the user's sign-off, proposed for T8 as
      `POST /admin/binaries/{id}/reopen` (JSON only, under /admin's existing admin gate); the release notes' note that
      pool-size defaults to 1 (an idle worker, gateway, loop device and quota image per
      published base, bc66e6f N3); and two more for the release notes (2026-10-19): a package
      that never builds can hold a compile slot for up to 6 h (180-minute deadline, two
      attempts) before it is FAILED, blocking the other compiles at max-running 1
      (d6fbcde N2), and a package holder that hangs inside its own process is recovered at
      its deadline, not after its lease, since this process's reaper spares its live
      holders (4219fc0 N2). And an open exposure (2b9902b-F1): the two solves (91908cd,
      0a9386d) keep the session through a long RUN with the client stalled, but a stall
      of the whole host still lost a package attempt to "no active session", and one more
      to "context canceled"; a package that loses both its attempts that way is FAILED
      until it is re-opened. To establish before binaries are enabled: which solve, and
      what in it, still needs the client's session (candidates: the context or Dockerfile
      read lazily, the base's registry auth through the session, the export).

- [x] **T7c. Configuration as code, then build concurrency: several builds per instance,
      a pool bounded by capacity.** Depends T7b. Placed before T8 (user, 2026-10-16). Today
      app builds run one at a time in the whole deployment:
      `skald.builds.coordinator.max_running` (`skald.builds.max-running` after step 0)
      defaults to 1 and is counted across instances, and each instance has ONE build-loop
      thread, so raising it helps only with more instances. The package queue already runs
      `max_running` compile threads, but its pools refill beside running builds and fight
      them for the host (T7b probe: the setup helper's median 43 s at three in parallel,
      3.9 s idle).
      *Best practice checked first (user, 2026-10-16):* GitLab Runner separates one process's
      capacity (`concurrent`) from a narrower cap (`limit`); job queues on Postgres scale by
      claiming with SKIP LOCKED, no coordination between processes; ARC sizes runners as a
      warm floor ON TOP OF demand (minRunners plus the jobs assigned); GitLab's `idle_count`
      keeps instances free for future demand beside the busy ones, and a warm floor must never
      hold up a job (GitLab's preemptive-mode pitfall: Skald already launches cold when the
      pool is empty); an EC2 warm pool's cap counts instances still starting. One fresh,
      discarded sandbox per job is the norm for untrusted code (Netlify, firerunner), so a
      shared long-lived builder is not a scaling route; the build cache stays in the registry
      (`--import-cache`/`--export-cache type=registry`, as T7 does), so fresh workers lose no
      cache. This changed two points of the first plan: concurrency is two settings, not
      one, and the pool is a spare floor bounded by the host's capacity, refilled at once,
      not "pool = max_running, refilled when the queue is empty" (which would leave it empty
      exactly when busy).
      0. Configuration an admin keeps as code, first, since steps 1-3 add settings (user,
         2026-10-16). *Best practice checked:* Spring Boot's externalized config (kebab-case
         canonical form, relaxed binding to environment variables, ${VAR} placeholders,
         `configtree:` for mounted secrets); yaml-language-server `$schema` modelines for
         editor completion and validation; Helm's values.schema.json; `promtool check
         config` and `alloy validate` (exit 0/1 with diagnostics).
         a. One style: every `skald.*` section typed `@ConfigurationProperties`, kebab-case;
            the map sections (admission, coordinator, binaries) become typed. Concurrency
            grouped: `skald.builds.max-running` (deployment), `skald.builds.worker.parallel`
            (instance), `pool-size`, `binaries.max-running`. Nothing has shipped, so keys are
            renamed outright, with no aliases. *As built:* the map sections keep their own
            parsers rather than becoming bound classes, because each already refuses an
            unknown key and names the known ones, which Spring's binder reports less
            usefully; what changes is the values (durations and sizes with units, through
            one shared `publisher.config.Settings`), kebab-case names and full key paths in
            every error. Durations: a bare number is seconds everywhere (the worker's too,
            not Spring's milliseconds), and leases, deadlines and timers must be whole
            seconds; sizes always carry their unit. One exception: `skald.bundle.limits.*`
            keep `spec/extraction-limits-v1.json`'s names and units (`max-file-bytes`,
            `extraction-deadline-seconds`), which the T5 gate's corpus and oracle are
            written against; the reference documents them as they are.
         b. Unknown keys under `skald.*` refused at startup (Spring ignores them, so a
            mistyped `pool-sise` is silently the default today), every problem reported at
            once with its path. *As built:* an EnvironmentPostProcessor
            (`publisher.config.ConfigurationCheck`) checks every name against
            `ConfigurationSpec` and every set value against its type and bounds before any
            bean exists, naming the source, a rename's new key or a likely key. Sources
            (8144a20 N1): in the sections the spec owns (`storage`, `dispatcher`, `builds`,
            `bundle`) an unknown name is refused from any source, environment and system
            properties included; any other `skald.*` name is refused in files and on the
            command line but only warned about in the environment and system properties,
            whose names tools share (`SKALD_DOCKER_CONFIG`, the probes' `skald.t7b.*`).
         c. One source: `spec/configuration-v1.json` (key, type, default, bounds, secret flag,
            description). A test holds the Java classes to it (every field there, defaults
            equal); generated from it and drift-checked: `schema/skald-config.schema.json`
            (the `skald:` section, for editors, CI and later a Helm values schema) and
            `docs/CONFIGURATION.md`. *As built:* the schema is
            `schemas/config/skald-config.schema.json`, beside `schemas/manifest`; the
            generator is test code (no new dependency), and the test's
            `-Dskald.regenerate=true` rewrites both; the spec also records which parser
            reads each key and the worker's required keys, and a source scan fails on a
            `skald.*` literal the spec lacks.
         d. A validate mode (`--validate-config`): loads the configuration exactly as startup
            does, runs the static checks only (no database, Docker or network), prints every
            problem with its key path, exits 0 or 1, for CI and pre-deploy. *As built:* the
            main class is upstream's, so the flag is read by 0b's post-processor, which then
            runs `ConfigurationValidator` (the key check, each section's own parser for
            relations a type cannot express, and the worker's own refusals and published
            bases file when it is enabled; not the images' presence, which needs Docker),
            prints and exits before any bean exists; a section the key check refused is not
            parsed again, so each problem is listed once.
         e. Secrets never in the file: `${VAR}` or `configtree:`; the reference marks secret
            keys and the example uses placeholders. *As built:* the key check reads each
            secret's raw value in its own source, before `${}` resolution (8144a20 N2): from
            the environment or a `configtree:` import anything goes; anywhere else -- a file,
            the command line, a system property -- only a placeholder without a default (or
            an empty value, unset) passes, and the refusal never shows the value.
         f. `config/application.example.yml`: complete, commented, every `skald` key at its
            default, with the schema modeline; a test loads it and asserts no problems.
            `dev/application-dev.yml` overrides only what the dev stack needs; sizing
            profiles (one host; several instances) in `docs/CONFIGURATION.md`. *As built:*
            the example is generated from the spec like the reference, a key with no default
            commented out with the spec's `example` value (every unset and list key has one;
            a secret's is a `${VARIABLE}` without the SKALD_ prefix, so it draws no warning);
            the test validates it through `--validate-config`'s checks and the generated
            JSON Schema, and again with every example set and the worker on, so the examples
            are checked against each other (mirror hosts, gateway ports). The dev stack's
            file sets no `skald:` key, so it is unchanged.
         Scope: the `skald:` section; upstream's `proxy:` section stays as upstream documents it.
      1. `skald.builds.worker.parallel` (default 1): how many builds THIS instance's Docker
         host takes; the build loop runs that many claim-and-build threads.
         `max-running` stays the deployment-wide cap; the startup checks report a `parallel`
         above it (threads that could never claim). Each thread keeps the loop's lease,
         stop, settle and sweep behaviour; the stop and disposal paths already handle
         concurrent attempts per instance. *As built:* `BuildLoop` runs `parallel` threads
         of the same loop (claims are serialized by the coordinator's advisory lock, which
         counts running builds across the deployment); `stop` interrupts every thread's
         pause and returns only once every thread's current attempt has ended; a
         `parallel` above `max-running` is refused at startup and by --validate-config.
      2. The pools replace a taken entry at once, but an instance's running attempts, its
         warming entries and its ready entries together never exceed `parallel` +
         `pool-size` (per driver: the app loop's, and each package platform's with the
         queue's `max_running`); a refill waits for a slot instead of competing with a
         running build. A cold launch is never held up by the pool. *As built (a change
         from the formula above, with its reason):* pools are per base, so "running +
         warming + ready <= parallel + pool-size" either never binds or caps ready entries
         below pool-size per base; and the measured harm was warm-ups competing with running
         builds, app and package alike, on one Docker host (8144a20 N3). So one
         `HostCapacity` per instance, of `parallel` + (binaries on) `binaries.max-running`
         slots: every running attempt of either driver holds one and is never refused, a
         pool entry holds one while it is launched and warmed, and a refill waits for a free
         slot, the pool woken the moment one is released; an idle ready entry holds none
         (memory and disk, no CPU). Step 3's disposing sandboxes will hold one too.
      3. A package attempt's disposal leaves the compile slot (measured 2026-10-18, 63 ICES
         package attempts on a loaded host: compile 6440 s in all, sandbox disposal 5650 s,
         cold launch 3950 s; median attempt 219 s against a median compile of 63 s). Today
         it is inline before the driver returns (bc66e6f-F1: the grace-less package sweep
         must never race it). It moves off the slot with a disposing marker the package
         sweep skips while it exists, as the taken-entry markers work, and a disposing
         sandbox counts against the capacity bound of step 2 until it is gone, so freeing
         the slot sooner never adds load the host has no room for. *As built:* every
         container (every process, untrusted code included) is removed before run()
         returns (8144a20 N4); then the attempt is marked (a volume labelled with the
         attempt and the time), a disposer thread holding a HostCapacity slot of its own,
         taken before the compile's is released, disposes of the rest, and the marker goes
         last, only once nothing else is left. The package sweep, which reads it after the
         live leases, leaves a marked attempt alone for 30 minutes (the slowest measured
         disposal was about 20); a marker a crash left behind then no longer holds it off.
         A stop, a container that would not go or a marker that could not be made keep the
         whole disposal inline, as before. A queued disposal holds its slot from submission,
         not from its start: conservative, bounded by the compile rate; revisit only if
         refills measure slow behind a burst of finished compiles (7bed632 N2).
      4. A FAILED binary goes back to the queue when an app build has compiled that package
         itself (user, 2026-10-19). Today a row FAILED after its two attempts stays FAILED
         until an operator re-opens it, so two temporary failures -- jsonlite's lost session
         and magrittr's cancel during the 2026-10-19 host stall -- would have cost a good
         package its binary for good. Instead, when an app build reaches PUBLISHING, every
         FAILED row of that build's builder image whose package and version are in its lock
         is re-opened through `BinaryBuildQueue.reopen` (actor: the build), as an operator's
         re-open does, dependents refused because of it included. A successful build proves
         the package compiles on that base; a package that is really broken also fails the
         app builds, so it never loops, with no timer. Publishing never waits on the queue:
         a package without a binary is compiled inside the app's own build, as today, and
         fails that publish if it cannot build, as Connect does. A new base revision already
         starts every package fresh (binaries are keyed on the builder image). *As built:*
         in `BinaryEnqueuer.built`, the hook BuildKitDriver runs off the build's thread once
         its outcome is Built (image built and pushed: what proves the packages compiled; the
         row turns PUBLISHING right after, and a build that is not Built never calls it); the
         rows are those of the build's platform key, which is how rows are keyed;
         `BinaryBuildQueue.reopenFailed` re-opens under the row lock only from FAILED and
         returns empty otherwise (843ad74 N1, N2); audited as "build <id>" (N5). What it
         cannot prove (N3, N4) is in its javadoc: a binary-only failure may fail again, up to
         max-attempts compiles per published build naming it; a row FAILED by a lost last
         upload has Skald's own binary in forge, so its re-open is a retry that ends UPLOADED.
      **Pass:** for step 0, a test per refusal (an unknown key, a bad bound, a secret in
      plain text where the reference forbids it) with a mutant each, the generated schema
      and reference drift-checked, the example file loading clean, and `--validate-config`
      exiting 1 with every problem listed on a broken file; for steps 1-3, unit tests for
      the thread count, the capacity bound (a refill held while the host is full, released
      when a slot frees) and the startup check, each with a mutant
      that turns it red; the pass probe green with `parallel=3`; three concurrent builds
      measured per phase (launch.setup and time to outcome) against today's serial run; the
      T7b probe's package phases measured against 4160e3a's (setup median 43 s); a package's
      slot time measured with its disposal moved off it (a disposal racing the sweep: a test
      and a mutant); for step 4, a FAILED row re-opened by a published build of its lock and
      left FAILED by a failed one, by another base's build and by a lock without it, each
      with a mutant; host clean.
      **Measured (2026-10-19, closing T7c; forge's kind cluster stopped for each timing run):**
      the full pass probe at `parallel` 3 green, 68 of 68, host clean, after two rounds of
      fixes (85f042e: three probe defects; 5923c7a: a pool stop that left a ready entry
      behind once its fixed 3-minute wait ran out, now a wait per entry, and the probe's
      worker-death injection aimed at a taken entry's worker). Three cold builds of the
      Python fixture admitted at once: with `parallel` 1 they finished 40, 387 and 718 s
      after admission (attempts 26-39 s; the gaps are each PUBLISHING attempt holding the
      loop's slot for a lease until T9); with `parallel` 3 all three finished in 69-70 s
      (attempts 67-68 s, launch.setup 9.4-10.4 s against 5.6 s alone). The T7b probe twice:
      package launch.setup median 36.6 s and 28.5 s against 4160e3a's 43 s; with step 3 a
      compile slot keeps only the container removal (median 14.9 s), and 3915 s of
      disposal over 40 attempts (median 28.8 s, max 489 s) ran off the slots. Row C, the
      first publish with binaries: 272 s in the first run (its registry push took 175 s
      beside a 240 s R warm-up, on a host slow throughout: A 653 s against r3's 434 s) and
      63 s in the second, within the 75 s target; the warm-up beside C is as in r3 (71 s),
      so no change is made for it. In the second run a forge stall timed out
      skald.private's fetch twice, 30 s apart, which used both attempts: FAILED
      SOURCE_FETCH, never fetched elsewhere, and the last app build re-opened it (step 4),
      back on the deferral path; the probe accepts that case and no other. Full suite
      green: 1599 tests, 0 failed, 8 skipped.
      **Not here:** time-windowed pool sizes (GitLab's policy windows; small, later), several
      Docker hosts with a capacity each and a Kubernetes runtime (the later multi-host
      track), microVM isolation (needs KVM; this host has none).

- [ ] **T8. Admin upload, status and live logs.** Depends T4–T7. Add the narrow admin
      transport, streaming/replay and plain-text view; exercise the complete T2 corpus
      through HTTP. Real OIDC Alice allow/Bob deny/signed-out deny for every endpoint,
      cross-content IDs and every log cursor/object lookup; hostile-origin form/fetch
      tests paired with successful authorized uploads. Break MinIO mid-log, disconnect
      clients and resume by cursor. **Pass:** durable in-progress and failed logs are
      readable, no slow client stalls a build, no new CSRF or source/log disclosure path.
      **Carried from T5's reviews, for this track:** (1) a rejection's getMessage() is
      printable ASCII by construction (`t5-e5e3071-F2`), but a cleanup failure is attached
      with addSuppressed, and an IOException from deleting the tree can name a hostile
      path raw. Log the rule and getMessage(), not the throwable with its chain, or escape
      the chain (18368a9 review N1). (2) GzipMember treats a source read of 0 bytes as end
      of upload. A blocking InputStream never returns 0, but if this track hands the
      extractor a non-blocking or adapter stream, make a 0 fail closed first (b991250
      review note). (3) Call `BundleExtractor.requireUtf8FileNames()` when publishing
      starts, so a JVM without a UTF-8 locale refuses to start publishing rather than
      refusing every bundle with a non-ASCII file name; `extract()` also calls it before
      touching the disk. The deployment notes must say the service needs a UTF-8 locale
      (e.g. `LANG=C.UTF-8` in the unit file): path encoding follows `sun.jnu.encoding`, not
      `file.encoding` (gate re-verification finding `t5-f4f5f32-F4`). (4) A cancel requested
      while RUNNING does not stop a driver that finishes first: the attempt goes on to
      PUBLISHING with `cancel_requested_at` set, which is the machine's rule (no
      RUNNING -> CANCELLED without a confirmed stop). Show it as "cancel requested; the
      build had already finished", not as an ignored request (071ef50 review N3). (5) The
      plan's "Simulate crashes at each boundary, including after DB commit before HTTP
      reply" has its database half in T6 (BuildFinalizationTest, BuildCoordinatorTest,
      ContentDeletionTest); the HTTP half is this track's: a client whose reply was lost after
      the commit retries and gets the same attempt (idempotency key), the same bundle state
      (PUT replay is 409 with the current state), or the same cancellation outcome
      (8808b29 review N2).
      **Carried from T7 (fe07c50 review N1), for T8's live logs:** a log whose writer died
      is finished by the sweep only after the sweep grace, so a reader can sit on a head
      that will not grow for up to grace + period (25 min at the defaults); the log view
      should say "finishing" for a settled build with no final record yet.
      **T8 plan (design, 2026-10-20; the shapes below need the user's sign-off before any
      code).** The nine endpoints, their paths, methods, media types and success codes are
      frozen in `spec/admin-transport-v1.json` (T1) and do not change. What T1 left open, and
      this plan fixes: request and response bodies, the error shape, the log cursor and the
      replay and stream formats, and the rules for auth, CSRF and untrusted text. After
      sign-off the shapes are added to that spec file as additive keys (the frozen keys stay
      byte for byte) and `dev/schema-fixture-check.py` checks them.

      *Envelope and naming.* Every JSON body uses ContainerProxy's `ApiResponse` envelope,
      `{"status": "success", "data": ...}`, as spine #1's `/admin/content` does, with
      camelCase field names like its `activeSpecId`. One admin API, one convention. A
      refusal is `{"status": "fail", "data": {"code", "message", "retryable", ...}}`, and
      a server-side error uses `"status": "error"` with the same `data`. Spine #1 puts a bare string in
      `data`; the new endpoints use an object because #3's CLI must branch on `code`, never
      on message text. Spine #1's own errors are left as they are. Request bodies are
      strict: an unknown field is 400 `BAD_REQUEST`, as in the manifest.

      *Untrusted text.* Rejection details, error details and log lines come from bundle or
      build output. JSON responses are written with non-ASCII escaped, so every byte on the
      wire is ASCII and a bidi override or C1 control arrives as `\uXXXX`, never raw. The
      text view neutralises the same set (Cc except tab, Cf, Zl, Zp, and default-ignorables:
      the `InvisibleCharacters` rule) as `\x{1B}`. Every log response carries
      `X-Content-Type-Options: nosniff` and `Content-Security-Policy: default-src 'none';
      sandbox`, so a browser opened on a log URL cannot be made to render it as a page.

      *bundle.create.* `POST .../bundles`, body `{}` (JSON, so a cross-site form cannot send
      it; no fields in v1). 201:
      `{id, contentId, state: "UPLOADING", uploadUrl, uploadDeadline, maxBytes}`. The
      deadline is one hour (the incomplete-upload rule); the reaper moves an UPLOADING bundle
      past it to REJECTED, rule `UPLOAD_ABANDONED`. `maxBytes` is the configured
      `max_compressed_bytes`, so a client can refuse locally before sending anything.

      *bundle.upload.* `PUT .../bundles/{bundleId}/content`, `Content-Type: application/gzip`,
      `X-Skald-Upload: 1`, and `Content-Length` (required: 411 without it; 413
      `BUNDLE_TOO_LARGE` before any byte is read when it is over `maxBytes`). The body
      streams to the bundle bucket and is counted against `Content-Length`. 200 at the
      PUT's commit point: the bundle status with `state: "VALIDATING"`.
      **Where each record is written** (c50e4fa-F1). The PUT's commit point is the
      database row moving UPLOADING -> VALIDATING, with `object_key`, `object_sha256`
      and `object_bytes` from the archive put (or from the read-back in stage (ii)
      below), under the row lock. It is not `receipt.json`. Validation then writes
      `manifest.json`, `inventory.json`, then `receipt.json` (BundleWriter.commit, which
      requires all three objects first, as T4 built it), then sets the row VALIDATED.
      So "receipt present" keeps meaning "usable bundle", which readReceipt and
      BundleContextSource rely on. spec/lifecycle-v1.json says VALIDATING means "the
      receipt is committed". That wording is wrong against T4's receipt, which is written
      last. (a) records the correction as an additive key (the bytes are complete and
      their digest is on the row) and leaves the frozen text unedited. **Validation is
      asynchronous**: a request thread could be held for a minute by a bundle near the
      2 GiB expanded limit, and its client may disconnect half-way. The client polls
      bundle.status; a small bundle is VALIDATED within a second.
      **A VALIDATING row is claimed work, not a submitted task** (6a23bcb-F1), on the
      pattern T6 built for build attempts. Each instance runs
      `skald.bundles.validation.parallel` (default 2) validator threads. Each claims the
      oldest VALIDATING row with no live lease (`FOR UPDATE SKIP LOCKED`), bumps its
      generation, and renews a lease (60 s, renewed every 20 s: the build defaults) while
      it validates from the stored archive. Its final transition is conditional on that
      generation, so a validator that lost its lease is fenced. A restart, a crash or an OOM
      loses nothing: the lease lapses and another thread, on any instance, runs validation
      again, which is safe because every write is create-only and compared on replay. The
      PUT wakes the local threads after its commit point. A full pool means the row waits,
      never that the PUT is refused. The lease needs columns, so this adds **V5** (forward
      only, additive): `bundle.validating_at`, `validation_owner`,
      `validation_lease_expires_at`, `validation_generation`, `validation_claims` and
      `validation_storage_error`. A CHECK makes the lease columns null outside VALIDATING.
      The T6 decision delegated schema to the coder, and this schema has not shipped
      anywhere yet. Refusals: 409 `BUNDLE_NOT_UPLOADING` with the
      current state (repeat PUT, different bytes, or the loser of two concurrent PUTs); 400
      `UPLOAD_INCOMPLETE` when fewer bytes than `Content-Length` arrive. The bundle stays
      UPLOADING and the same PUT may be sent again. 503 `STORAGE_UNAVAILABLE`,
      `retryable: true`, with `Retry-After`, for the store's "outcome unknown", the
      unexplained `400 IncompleteBody`, and throttling that outlasted the SDK's retries (the
      Q4 risk row). The bundle stays UPLOADING, and that is never a verdict on the bundle.
      **Replays after an unknown outcome, per stage** (ad2d195-F1). The archive key is
      fixed by the v1 layout and its put is create-only, and a streamed put records no
      digest, so a stored archive cannot simply be trusted. The stages are keyed on the
      row and the archive object, never on the receipt. (i) Row UPLOADING, no archive
      object: the replay writes it as a first PUT would. (ii) Row still UPLOADING, archive
      present (the long streamed put whose answer was lost, or a row update that failed
      after it): the replay's body is streamed to a digest and size only, not stored, then
      the stored archive is read back once and hashed (bounded by `maxBytes`; a rare
      path). Both match: the row moves to VALIDATING with the read-back digest, which
      describes the stored bytes, never what the request claims. No match: 409
      `BUNDLE_ARCHIVE_CONFLICT` ("make a new bundle"); the bundle stays UPLOADING, so the
      one-hour rule rejects it (`UPLOAD_ABANDONED`) and the lifecycle gains no new
      transition. A read-back the store cannot serve is 503 `STORAGE_UNAVAILABLE` again.
      (iii) Row has left UPLOADING: 409 `BUNDLE_NOT_UPLOADING` with its state, as T1
      froze. The frozen "a later PUT restarts it cleanly" holds through (ii): the restart
      is verified, not a rewrite. A storage failure inside validation (reading the
      archive, or writing the manifest, inventory or receipt) is the platform's problem,
      not the client's. The validator records it in `validation_storage_error`, releases
      the claim and retries with backoff. **The deadline rejects a bundle only for what was
      observed.** The deadline is `validating_at` plus `skald.bundles.validation.deadline`
      (default 15 min). Past it the reaper rejects with `VALIDATION_STORAGE` when a storage
      failure is recorded, which names the store and not the bundle, and with
      `VALIDATION_STALLED` when nothing finished and nothing was recorded (no validator
      ran, e.g. publishing switched off on every instance). `validation_claims` counts only claims
      that ended by **lease lapse** (claimed, never released, lease expired: the validator
      died). A claim released after a recorded storage error is not counted (95e2185-F1).
      A bundle whose claims lapsed `skald.bundles.validation.max-claims` times (default 3)
      is rejected `VALIDATION_INTERRUPTED`: something in it ends validators (an OOM or a
      crash). That stops a poison bundle taking down instance after instance, and the rule
      says exactly that. When both could apply, a recorded storage error wins
      (`VALIDATION_STORAGE`), since it is the observed cause. **Validation's outputs are
      canonical** (95e2185 N1): manifest.json and inventory.json are serialised with
      sorted keys and members sorted by path, and hold no time, host or validator id, so
      two validations of one archive, even concurrent ones after a stalled lease, write
      byte-identical objects, and the create-only compare on replay holds. (b) tests that. Each of these rules is retryable by uploading a new bundle, and none is
      reported as a fault in the bundle's content. (f)'s injection test faults the archive
      put, the read-back, the row update, the archive read and each of validation's three writes, each separately, and kills
      the instance mid-validation: after the restart the bundle reaches VALIDATED (or
      REJECTED for its own rule) with no re-upload. A storage fault lasting longer than
      max-claims retries ends VALIDATED once the store returns within the deadline, or
      `VALIDATION_STORAGE` after it, never `VALIDATION_INTERRUPTED`.

      *bundle.status.* 200: `{id, contentId, state, createdBy, createdAt, updatedAt,
      finishedAt, uploadDeadline, archive: {sha256, bytes} | null, manifestSha256,
      inventory: {files, bytes} | null, rejection: {rule, detail} | null}`. `rule` is the
      `BundleRule` id (S1..S11 and the extractor's), so "S5: ..." reaches the publisher as
      the plan requires. S9 (type equals `content.type`) is checked here, at validation,
      which closes that rule's "owed" row.

      *build.create.* `POST .../builds`,
      `{bundleId, idempotencyKey, retryOf?}`. `retryOf` names a terminal attempt of the
      same content item; `bundleId` may then be left out and defaults to that attempt's
      bundle, and naming a different one is 400 (a retry reuses the bundle,
      lifecycle-v1). `retryOf` is part of the input fingerprint, so one key with a
      different `retryOf` is 409. 202 with `Location` and the build status (below). Same key, same inputs:
      202 with the same build, `created: false`, so a client whose reply was lost gets its
      own attempt again (T6 carry 5). Refusals are `AdmissionRefusal`'s, one code each:
      `KEY_INVALID` 400, `CONTENT_NOT_FOUND` / `BUNDLE_NOT_FOUND` 404,
      `BUNDLE_NOT_VALIDATED` 409 (with the bundle's state), `KEY_REUSED_WITH_DIFFERENT_INPUTS`
      409, `PUBLISHER_LIMIT` 429, `QUEUE_FULL` 503 `retryable`. A superseded queued attempt
      is named in `supersededBuildId`.

      *build.status* and the items of *build.list*: `{id, contentId, bundleId, retryOf,
      supersededBy, state, createdBy, createdAt, startedAt, publishingAt, finishedAt,
      deadlineAt, error: {code, detail} | null, image, recipe, effectiveLimits,
      cancel: {requestedAt, outcome} | null, log: {state, final}, links: {self, logs,
      stream, cancel}}`. Left out on purpose: lease owner, generation and the worker handle,
      which are internals and name infrastructure. `cancel.outcome` is `requested`, `overtaken`,
      `cancelled`, or `too_late`, the last one shown as "cancel requested; the build had
      already finished" (T5 carry 4). `log.state` is `pending` (nothing written yet), `live`,
      `finishing` (the build has settled and there is no final record yet, which can last
      up to the sweep grace plus its period, 25 min by default: T7 carry), or `final`, with
      `final: {outcome, complete, truncated}` from final.json. `image` stays null until T9
      gives success.
      build.list: newest first, `?limit=` (default 50, max 200), `?state=` (repeatable),
      `?before=<buildId>` (keyset on created_at, id); 200 `{items, next}`, where `next` is
      the `before` value for the next page, or null.

      *The log cursor.* An opaque string, documented as `<chunk sequence>.<record index>`
      (`"12.37"`) so a person can read it, but clients must not build one. It names a
      position after one record; `0.0` (or no cursor) is the start. Readers serve only
      chunks the log advertises (final.json once it exists, else index.json), never past
      a hole, so a cursor handed out is always valid again later. A malformed cursor is 400
      `CURSOR_INVALID`; one past what is advertised waits (stream) or returns nothing
      (replay), never an error, since the reader may only be ahead of the index. A log
      removed by retention (90 days) is 410 `LOG_EXPIRED`.

      *build.logs.replay.* `GET .../logs?after=<cursor>&limit=<records>`, picked by
      `Accept`; no Accept, or `*/*`, gets text/plain, so plain curl works. Both carry
      `X-Skald-Log-State` (as above), `X-Skald-Build-State` and, when more is advertised,
      `X-Skald-Next-Cursor`.
      `application/x-ndjson`: one record per line, the stored record plus its position:
      `{"cursor":"3.17","n":412,"t":"...","cut":true}` for a line (`cut` only when it was
      shortened), `{"cursor":"3.18","gap":{"lines":L,"bytes":B}}` for the head/tail marker
      (renamed from the stored `cut` object so the two meanings of `cut` do not share a
      key), and, when the reader has reached the end of a final log, a last record
      `{"end":{"outcome","complete","truncated","buildState"}}`. Default limit 1000, max
      10000.
      `text/plain`: one line per record, `<n right-aligned> | <text>`, and a shortened line
      ends in ` [cut]`. Platform lines have no ` | `, so build output cannot pass for one:
      `----- skald: L lines (B bytes) dropped here; head and tail kept -----` and, at the
      end, `----- skald: log final: FAILED:BUILD_FAILED, complete -----`. No default limit:
      a log is bounded by its head and tail budgets, and it streams from storage.
      An attempt with no log yet (QUEUED) is 200 and empty, with state `pending`.

      *build.logs.stream* (SSE). `GET .../logs/stream`, resumed from `Last-Event-ID` or
      `?after=` (the same cursor). Events: `line` and `gap` (id = cursor, data = the ndjson
      record), `state` when the build or log state changes, and `end` (the same data as
      the ndjson end record), after which the server closes. A client must close on `end`;
      a reconnect after it gets `end` again at once. `retry: 5000`, a `: keepalive`
      comment every 15 s. The stream is pull-based: each subscriber reads committed
      chunks from storage when its previous write has gone out, polling the index each
      second (chunks flush at most every 2 s). Nothing the build writes waits for a reader,
      and a slow reader holds no buffer beyond the chunk it is sending. Bounds, all
      configurable: 64 open streams per instance, 8 per user (429 `STREAM_LIMIT`), 30 min
      per connection, then the server closes without `end` and the client resumes by
      cursor. A storage outage sends `state` with `logState: "storage_unavailable"` and
      keeps retrying with backoff until the connection limit; it does not end the stream.
      This pull model **supersedes the frozen stream note's "bounded subscriber queue"**:
      there is no queue at all, which is the stronger form of the same bound. (a) records
      the supersession as an additive key and leaves the frozen note unedited (ad2d195 N2).

      *build.cancel.* `POST .../cancel`, body `{}` (JSON for the CSRF rule). The reply is
      a function of the attempt's state and whether it carries a cancel request
      (`cancel_requested_at`), read under the row lock (ad2d195-F2). `CancelOutcome` gains
      the values this needs:

      | State | Cancel requested before? | HTTP | `code` / `outcome` | `cancel.outcome` in status |
      |---|---|---|---|---|
      | QUEUED | (never kept) | 202 | `cancelled`; moves to CANCELLED | `cancelled` |
      | RUNNING | either | 202 | `requested`; recorded once, final when the worker is confirmed stopped | `requested` |
      | PUBLISHING | no | 409 | `CANCEL_REFUSED_PUBLISHING` | none |
      | PUBLISHING | yes | 409 | `CANCEL_TOO_LATE` | `too_late` |
      | CANCELLED | yes | 202 | `cancelled` | `cancelled` |
      | SUCCEEDED, FAILED | yes | 409 | `CANCEL_TOO_LATE` | `too_late` |
      | TIMED_OUT, INTERRUPTED | yes | 409 | `ALREADY_FINISHED` | `overtaken` |
      | any terminal | no | 409 | `ALREADY_FINISHED` | none |

      `too_late` reads "cancel requested; the build had already finished" (T5 carry 4):
      the driver finished before the stop and the attempt went on to PUBLISHING. A request
      during PUBLISHING itself is the refusal row, never `too_late`. `overtaken` reads
      "cancel requested; the attempt ended <state> before a stop was confirmed", since a
      timeout or a lost worker is not a finished build. A superseded attempt is CANCELLED
      without a request of its own, so a cancel of it is `ALREADY_FINISHED`. **The replay
      guarantee is "a retry reports the cancel's current outcome"** (T6 carry 5), which is
      what the coordinator can give: a first 202 `requested` whose reply was lost, retried
      after the build ended, answers with the row it has reached, e.g. 202 `cancelled` or
      409 `CANCEL_TOO_LATE`. A retry never makes a second cancel or a different
      transition.

      *Auth, CSRF and IDs.* Admins only, as T1 froze it: `/admin/**` is gated by
      `UISecurityConfig`, with no second matcher. A non-admin gets upstream's 403; a
      signed-out caller gets the redirect to log in (302). Those two responses are
      upstream's, not this envelope. Every lookup is by `(contentId, id)` together, so another
      content item's bundle, build or log is a 404 identical to a missing one, never a 403
      (no oracle), and log objects are only reached through keys built from that pair. The
      CSRF rule is T1's and unchanged: every body is `application/json` or, for the upload,
      `application/gzip` plus `X-Skald-Upload`. Form types are 415 and a missing header is
      400 `UPLOAD_HEADER_MISSING`. A body with no `Content-Type` at all can also be sent
      cross-site without a preflight (`navigator.sendBeacon`, or `fetch` in no-cors mode
      with an untyped Blob). `consumes` reads it as `application/octet-stream` and refuses
      it with 415, so it is covered. It is stated here because nothing else would show it:
      (a)'s checker and (f)'s hostile-origin set include an untyped POST to bundle.create
      and to build.cancel (ad2d195 N1). The GET endpoints change nothing. Cross-origin reads of
      them, EventSource included, are refused by CORS for any origin the deployment has
      not configured. No CORS change, and no change to the CSRF matcher. Audit:
      `bundle.create`, `bundle.upload`, `bundle.validated` / `bundle.rejected`,
      `build.create` (the existing `build.cancel.*` stays).

      *Not in T8.* No bundle list endpoint (a build names its bundle). No log download as a
      file. No UI. **Placeholder only, not designed and not approved:** the binary re-open
      endpoint (`POST /admin/binaries/{id}/reopen`, PRESENT_FOREIGN rows only;
      decision_binary_retry), which waits for its own sign-off.

      *Carried items, placed:* (1) rejection logging names the rule and getMessage(),
      never the throwable chain (18368a9 N1); (2) the upload adapter stream fails closed on
      a 0-byte read before GzipMember sees it (b991250); (3) `requireUtf8FileNames()` runs
      when the upload service starts, and the deployment notes require a UTF-8 locale
      (t5-f4f5f32-F4); (4) `cancel.outcome = too_late`; (5) the HTTP half of the crash
      boundaries: a lost reply after commit, retried, gets the same attempt, the same
      bundle state, or the same cancel outcome; T7's `finishing` log state; the Q4
      storage-outcome row (503 `STORAGE_UNAVAILABLE`, tested with "outcome unknown",
      IncompleteBody and a 503 SlowDown injected, each ending in a correct replayed
      outcome).

      *Order of work after sign-off*, one reviewed commit each: (a) the shapes into
      `spec/admin-transport-v1.json` plus the fixture checker; (b) the upload service and
      bundle endpoints (V5, the claimed validator loop, reaper rules, S9); (c) build create, status,
      list and cancel; (d) log replay (cursor, both formats, neutraliser); (e) the SSE
      stream; (f) T2's whole corpus through HTTP, Alice allow / Bob deny / signed-out deny
      per endpoint with deny cases first, cross-content IDs and cursors, hostile-origin
      form and fetch beside a real authorised upload, MinIO broken mid-log, clients
      disconnected and resumed, `dev/smoke.sh` extended; then the full suite to close T8.
      *(b1) as built* (V5, the claimed validator, its reaper, S9): four departures from the
      text above, each for the end user. The keys are `skald.bundle.validation.*`, singular
      like the existing `skald.bundle.limits.*`, so an operator finds one `bundle:` section.
      The lease is the build's 300 s renewed every 30 s, not "60 s / 20 s": the build
      defaults this plan meant to copy changed in d6fbcde (129 s host stalls), and a second
      run is harmless. The deadline is 30 min rather than 15, so that max-claims (3) full
      leases fit inside it and a bundle that kills validators is called INTERRUPTED, not
      STALLED; the configuration refuses a deadline that does not exceed them. V5 has one
      more column, `validation_retry_at`, the backoff after a storage release, so that
      `validation_lease_expires_at` means one thing. And manifest.json is stored exactly as it
      arrived in the archive, not re-serialised with sorted keys: those bytes are the same on
      every run (the point of 95e2185 N1), and re-serialising would store a manifest that
      differs from the publisher's. inventory.json is canonical as planned.

      *(c) as built* (build.create/status/list/cancel): three additions to the text above,
      each for the end user. `log.state` has three more values, recorded in the shapes as
      `log_states_added`: `none` for an attempt that ended without running (cancelled or
      superseded while QUEUED), which will never have a log, so `pending` would promise one;
      `expired` for a terminal attempt past the 90-day retention with no final record;
      `storage_unavailable` when the log store cannot be read, the stream's own word, so
      that a status poll during a storage outage still reports the build's state instead of
      a 503. A `final.json` once read is cached in memory (bounded): it is create-only, and
      without the cache a list page would cost a storage round trip per item every time.
      Past retention the store is asked again, so a deleted record reads `expired`; and
      the first failed storage read of a request ends that request's reads, so a page
      during an outage does not wait out the SDK's retries once per item.
      *(d) as built* (build.logs.replay): the cursor's record index counts from 1 within
      its chunk, and `s.0` is the position before chunk s's first record, so a chunk
      boundary has two names and both stay valid (shapes.cursor.record_index). A page is
      read whole from storage and then written, since a log is bounded by its head and
      tail budgets (2 MiB by default): a storage failure is then a 503 before any byte goes
      out, never a truncated 200. An advertised chunk that is not in the store is
      STORAGE_UNAVAILABLE, not a silent skip. `limit` may also be given to the text view,
      up to the same 10000; without it the text view is unlimited. The query takes only
      `after` and `limit`. Accept is ranked by quality, the most specific range deciding
      each type, and a tie goes to the type named outright; anything unreadable, or no
      Accept at all, gets text/plain. The text end line also says `incomplete` and
      `truncated` when they apply (shapes.log_records.text_end).
      *(e) as built* (build.logs.stream): one virtual thread per stream pulls pages through
      the same reads as replay, so the two views cannot disagree on what is advertised.
      Everything that can refuse an open (cursor, build, expired log, the two bounds) is
      decided before the response is committed, so a refusal is the envelope, not a broken
      stream. Last-Event-ID wins over `?after`: a browser's EventSource reconnects with the
      URL it was opened with, so its `after` is the stale one. The first `state` event is
      sent at once, then one per change, `storage_unavailable` included. The bounds are
      `skald.builds.log-stream.{per-instance, per-user, connection}`, and per-user may not
      exceed per-instance. A client that leaves is noticed at the stream's next write, so
      its slot is freed within a keepalive (15 s) at most. The response also carries
      `Cache-Control: no-cache` and `X-Accel-Buffering: no`, so a buffering proxy does not
      hold events back.
      build.list refuses an unknown query parameter (400), the GET counterpart of strict
      bodies, so a misspelt `?status=` does not quietly mean "no filter". Refusals carry the
      state they were decided on: `bundleState` on BUNDLE_NOT_VALIDATED, `retryOfState` on
      RETRY_NOT_TERMINAL, `state` on each cancel refusal. `supersededBuildId` is read from
      the row, so a replay names what the first request superseded. The audit row for a
      create stays T6's `build.admitted` (it now names `retryOf`), not a new
      `build.create`.

- [ ] **T9. Managed publication and unchanged runtime semantics.** Depends T7/T8.
      Wire successful builds into the shared version allocator with explicit activation.
      Consume only the server-generated `spec_json` contract; test its fingerprint and
      legacy {} behavior. Start real built R and Python content through `/c/<path>/`; verify
      HTTP plus Shiny WebSocket interaction, effective user/limits/mounts and identity. Fault-inject
      every push/log/DB/response boundary; replay callbacks and late cancellation. **Pass:**
      success produces one staged digest-pinned version, failed/interrupted work never
      becomes active, and old direct-image tests still pass without a second override.
      **Carried from T5 (fba1089 review note):** published app containers get the memory
      gap the build worker had: ContainerProxy 1.2.4's `DockerEngineBackend` sets the
      memory limit and never a memory-swap limit, so Docker allows as much again in swap.
      Owed here: memory-swap equal to the limit, or a documented host-level swap policy. It
      is an engine (upstream-class) change, so it goes through `docs/UPSTREAM_CHANGES.md`
      and asks first.
      **Carried from T7 (94b4dae review N3):** the build client pushes with ONE registry
      credential that can write every repository, and buildkitd holds it in memory for the
      pulls and the push (buildctl's session answers its credential requests). An escape
      into the daemon could therefore push under any repository's tag. Deployment by digest
      limits the damage; per-repository push scope (a token scoped to the attempt's
      repository) belongs with the registry choice made here.
      **Carried from T7 (6886413 review N2):** a RUN step can reach the registry through the
      gateway (`http_access allow registry registry_port`); what keeps it out of another
      content item's cache or image is only that RUN steps carry no registry credential.
      With the per-repository scope above, add an attack row: a RUN fetching
      `/v2/skald/cache/<other>/manifests/<key>` through the gateway gets 401.

- [ ] **T10. Live acceptance, rollback, GC and final independent regression review.**
      Depends all above. **Signed provenance (user, 2026-10-10):** the SLSA attestation T7
      attaches is unsigned (SLSA Build L1, written inside the worker that runs the bundle's
      steps; ADR-0012, b45c0a9 N1). Sign it outside the worker -- a platform key or keyless
      signing -- so `cosign verify-attestation` / slsa-verifier accept it; flag the
      dependency and its licence first, and test that an index replaced in the registry fails
      verification. **After T7 (user, 2026-10-10):** an SBOM attestation from BuildKit's
      scanner (docker/buildkit-syft-scanner, pinned by digest, flagged as a new trusted image
      in the build path, licence checked), with the provenance record naming it.
      **Build latency:** moved to T7b (user, 2026-10-10).
      **Carried from T7 (6890924 review N2, N3), for the deployment docs:** the build
      workspace must be local disk: WorkspaceLock is an fcntl lock, which NFS without lockd
      and some FUSE or CIFS mounts do not honour, so a second process would not be refused.
      And AttemptSweeper.stop() waits up to 70 s for a sweep's helper removal: under
      Kubernetes' default 30 s terminationGracePeriodSeconds a pod can be killed first (not
      a leak: the next process removes a stale sweep helper), so the Helm chart sets the
      grace period above that or says why not.
      **Carried from T7 (c939203 review N1):** every (content, key) pair
      leaves a BuildKit cache ref at `skald/cache/<content UUID>:<key>`; the GC (artifact
      ledger, mark/recheck/delete) owns those refs too and deletes them with the content
      item. Add re-runnable `dev/bundles-live.sh` using unique IDs/paths and
      asserted cleanup, described in Done when. Include restart during build, failed build
      retry, log retention, orphan/multipart cleanup and real registry GC with a referenced
      old image protected. **Retention is asserted per row of the policy table above, on both
      sides of each boundary, and the failed-attempt rows are asserted separately from the log
      row because they are on different clocks** — a failed attempt's *artifacts* present at 29
      days and collected at 31, while that same attempt's *logs* are still present at 31 and
      survive to 89, being collected only at 91; a successful build's logs likewise present at
      89 and collected at 91; a published version's image and bundle still present after all of
      those; an `INTERRUPTED` attempt treated as terminal like the rest; and a nonterminal
      attempt never collected however old its clock would make it. "Cleanup ran" is not the assertion;
      "cleanup collected exactly what the policy names, and nothing it protects" is. Re-run the extraction and isolation suite against the final
      integrated artifact; independent reviewer signs off changes since T5. Run
      `make build`, `make dev`, smoke, ACL, bundle live tests and `make test` anew.
      **Pass:** actual output and named failed mutations, no vacuous deny cases, no
      unresolved security finding or unapproved relaxation.
      **Carried from T6, for the collector:** (1) the artifact ledger has one image record
      per build ATTEMPT (ref = the attempt's tag, digest in its own column), so two attempts
      can carry one digest. A digest may be collected only when NO pinned record carries it;
      collecting by record would delete a published version's image whenever a failed
      attempt produced the same digest (6ae3868-F1). (2) VersionAllocator allocates
      max(version) + 1, which never reuses a number only while no version is ever deleted.
      If retention or GC deletes versions, keep a high-water mark (e.g. a next_version column
      on content) instead: a reused number reuses the spec id c<id>--v<n>, and ContainerProxy
      memoises authorization per spec id (0c8add6 review N1).

## Risks and open questions

| Question/risk | Recommendation, decision owner and stop condition |
|---|---|
| **Q1 — CLOSED: runnable breadth** | User selected **R and Python Shiny now**. Both are required end to end. T1 selects exact maintained language/tool versions and freezes the two manifest branches; additional version support is an additive catalog choice. Static/API/data behavior remains with its owning track. |
| **Q2 — DECIDED 2026-09-17, superseded 2026-09-18; the profile still owes its proof** | The user takes the systemd route: a `Delegate=cpu cpuset io memory pids` drop-in for `user@.service`, so the user manager can enforce a CPU bound on a rootless worker. T0 found the host delegating only `memory pids`, and the parent slice offering `cpuset cpu io memory hugetlb pids rdma`, so the mechanism exists and was merely switched off. **Not yet verified, and the configuration check is not the proof:** `cgroup.controllers` listing `cpu` says the delegation happened; `systemd-run --user --scope -p CPUQuota=50% sleep 1` succeeding says a quota can be *applied* to a scope. Neither measures **enforcement**, and a sleeping process consumes no CPU, so that command is a preflight and nothing more. T3 still owes a CPU-burning probe against the real worker *and its descendants*, and T5 repeats it independently; a successful preflight does not satisfy either. Record both outputs against this gate before T3 relies on it, labelled as preflight. AppArmor remains unavailable on this host — a fact, not a decision: the profile is confined by user namespaces, seccomp and cgroups, and the write-up must say that rather than listing AppArmor. Privileged mode and a disabled process sandbox remain unaccepted. **Measured 2026-09-18 (T3a) — the premise changed, and the decision above may no longer be needed.** The `user@.service` path T0 read does not exist on this host; this shell's cgroup is `0::/` and the root cgroup already delegates `cpuset cpu io memory hugetlb pids rdma`. More to the point, a worker launched through the **rootful Docker daemon** is bounded by the daemon, not by a user manager, so the `Delegate=` drop-in is moot for that architecture. The CPU-burning probe this row demanded has been run against a container **and its four forked descendants**: `--cpus=0.5` → 0.51 cores, `--cpus=2` → 2.01, unrestricted → 3.99, with memory (SIGKILL at the cap), PIDs (fork blocked at 30 of 32) and a real disk quota measured alongside — `bash dev/validate-sandbox.sh`, 9 of 9 bounds. **What was still the user's call, ANSWERED 2026-09-18 by the decision below:** whether Skald should nonetheless run workers rootless under the user manager, in which case the drop-in and a repeat of this probe in that mode are both still owed. (It should; both are still owed. The paragraph is kept unrewritten because the history is the useful part, but it is no longer an open question and must not be read as one.) This note does not retire the decision; it records that the gap it was taken to close is not present on this host. **DECIDED 2026-09-18 by the user: rootless stays the default, and the worker runtime becomes a configuration seam (decision 6).** The reasoning, because the premise this row spent two rounds on was the wrong premise: "rootless" answers *does the platform need root to build*, which is a deployment question; it does not answer *what happens when build code defeats the kernel*, which is the tenancy question, and only a separate kernel — gVisor, or a microVM under a hypervisor — answers that. T3(a) measured cgroup bounds, which the rootful daemon supplies either way; it never measured escape, so the measurement retired the CPU-quota argument for rootless and left its actual argument untouched. **Where the line falls is this plan's judgement and NOT the cited guidance's, and the guidance leans the other way.** An earlier draft of this row said "the published guidance is equally clear that a hypervisor for builds originating from accounts the operator issued is architecture for its own sake". It says no such thing, and the correction matters because this paragraph is what the T5 review and the human look still owed to the operator will read as the state of the evidence. What the sources actually say: pandastack's test is "whether a person you have never met can cause a command to run on your builder", it limits rootless BuildKit's strengths to "single-tenant scenarios", and it states plainly that "RUN still executes the submitter's code in your namespace, on your kernel"; emirb calls hardened containers "legitimate for trusted workloads of your own code" and names "CI scripts from hundreds of developers, multi-tenant platforms" among the **strongest** microVM cases; fly.io draws its line at whether "the code is yours". Skald's publishers hold accounts in the operator's directory, but a directory account is weaker than the commit access pandastack names, and one node serves many publishers — which is nearer emirb's many-developer CI than to "your own code". **The argument for the default is ours:** an operator issues, revokes and audits those accounts and can bind a bundle to a person, which is a control the container-versus-microVM literature does not weigh at all. That argument is defensible and it is the one the user took; it is not a consensus, and recording it as one would have hidden the strongest objection exactly where a reader goes looking for it. So: rootless by default **because of the account-issuance argument**, seam for the deployments where that argument does not hold — and the contrary reading above is the reason the refuse-to-start clause and the still-owed human review are not decoration. **A hard constraint, measured on this host 2026-09-18 and not a matter of effort:** there is no `/dev/kvm` and no `vmx`/`svm` in `/proc/cpuinfo`, so Firecracker and Kata cannot run here at all and a microVM profile cannot be *measured* — and T3/T5 accept measured containment only. gVisor needs no KVM and is provable here, but BuildKit under it fights overlay-on-overlay and wants a tmpfs upper layer, so it is a candidate profile and not a default. **Still owed by the rootless default, unchanged by this decision:** the `Delegate=` drop-in or an equivalent, a repeat of T3(a)'s nine probes in rootless mode, and a replacement for the loop-backed ext4 disk quota, which today is attached by *privileged setup containers* a rootless worker will not have. **Resolved 2026-09-27 by gate finding t5-e5e3071-F6, recorded under `runtimes.runc-rootless.resolved` in the spec:** the probes were repeated in rootless mode, from inside RUN steps (T5 entry, parts 1-4), and `runc-rootless` is measured; the `Delegate=` drop-in is not needed for the shipped deployment, because the worker is rootless BuildKit in a container the platform's Docker daemon starts, and the RUN-step measurements show the daemon's cgroup bounds reach build code; the quota volume stays launcher-attached (`dev/quota_volume.py`), which decision 6 allows, and the worker never gets the device. A deployment running workers under a user manager instead would owe the drop-in again. |
| **Q3 — CLOSED 2026-09-17** | Four answers, and two of them change the design rather than filling in a number. **(1) Sizes, counts and timeouts are configurable per deployment** with the documented defaults above, so an operator sizes them to their own host; a configured value is validated at startup and a limit may be raised but not removed. **(2) Retention** is decided, and the per-artifact table in "Status, logs, admin transport and cleanup" is the only authoritative statement of it — this row deliberately does not repeat the numbers, because restating them in two places is exactly what produced finding `d4f5baf-F1`. In summary: a published version's bundle and image are pinned until the content is deleted, while logs and failed-attempt artifacts expire. **(3) Package sources are configurable** — default CRAN and PyPI, an operator may point at their own Nexus, Artifactory, Posit Package Manager or a forge-style proxy. The egress rule therefore becomes **deny all except the configured repository hosts**, which is stricter to state and easier to implement than a hard-coded allowlist, and it is what lets a private mirror work without special-casing. R and Python remain the only languages in v1. **(4) System packages are the administrator's**, installed into the base images; a build never installs one. A build that fails for a missing system library **surfaces the build log** and stops there — the publisher takes it to their administrator. A curated error-to-package-name mapping was considered and **rejected as scope**: it can never be complete, and a half-complete lookup that confidently names the wrong package is worse than the compiler's own message. |
| **Q4 — dependencies and base-image licensing** | **AWS SDK S3 LANDED 2026-09-18**, and the version decision is not the obvious one: `s3` and `url-connection-client` are declared at **2.31.21**, pinned to the version ContainerProxy already brings, not to the newest release. The engine carries the entire 2.31.21 module set transitively — `s3` included — so declaring the then-latest 2.55.0 did not sit beside the engine's modules, it *upgraded the engine's own* `s3` and left `sso`/`sts`/`ecs` behind, producing a mixed-version classpath that Maven resolves silently and that fails at runtime rather than at build time. Measured: 33 awssdk artifacts before, 34 after, the one addition being `url-connection-client`. All resolved artifacts are Apache-2.0, so no sign-off was triggered. **Upgrading the set to a newer SDK is flagged and NOT taken**: it changes what ContainerProxy runs with, not only what Skald runs with, and it becomes forced if an advisory lands against 2.31.21. `AwsSdkClasspathTest` fails the build if the single-version invariant breaks. Remaining candidate Java additions: Commons Compress (Apache-2.0), networknt JSON Schema Validator (Apache-2.0); BuildKit (Apache-2.0). Flag exact versions/transitives before adding. Check base-image/package license inventory too; R/toolchain images contain their own licenses. Any new GPL/AGPL dependency follows CLAUDE.md's explicit sign-off rule; the presence of dev MinIO is not blanket approval for an AGPL SDK. |
| Existing ACL decision cache | Keep ACL/visibility mutations out of #2; don't introduce viewer-to-builder authorization via cached `canAccess`. Admin gating remains centralized. #4 still owns revocation/fork question. Test warm sessions as well as fresh ones. |
| Runtime isolation vs build isolation | The engine exposes user/CPU/memory/volume/network settings, but this is not evidence for every sandbox guarantee. T9 tests what the launched image actually gets; missing required engine support is escalated under ADR-0001. |
| Dependency reproducibility | A lockfile cache is not an artifact mirror or deterministic-build guarantee. Record resolved provenance and make cold builds fail clearly when dependencies disappear. Never rebuild to roll back. |
| DB/storage/registry disagreement | Durable intents, unique completion, fenced callbacks and collector grace periods; crash tests are mandatory. Do not report success while image/log evidence is missing. |
| Destructive cleanup and live-proxy races | Protect all retained versions and inspect actual proxy state; no automatic version pruning in #2. Exercise races, retain dry-run evidence, and fail cleanup closed if reference discovery fails. |
| Object-store stalls, and conditional writes under retry (found at T5, 2026-09-23) | **Two things, one of them fixed.** (1) A stalled object store makes the SDK time out and retry. A retried *conditional* put (If-None-Match / If-Match) cannot tell its own first attempt from someone else's write: when the store had written and the response was lost, the retry's 412 was reported as "already there" — the platform's own write read as a competitor's. Reproduced deterministically and **fixed**: a conditional put is retried only when the store proved it did not apply it -- throttling as the store names it (the SDK's throttling classification, which covers 429 and 503 SlowDown, plus MinIO's SlowDownRead/SlowDownWrite; **not** a bare 503, which a reverse proxy can return after forwarding) and a connection never established -- and an ambiguous failure (timeout, I/O after sending began, any other 5xx) is not retried and surfaces as "outcome unknown", which the protocol's restart-safe replay settles (`S3ObjectStoreTest.conditionalPutsRetryOnlyWhatProvablyDidNotHappen`). **Every write the platform makes is conditional**, so this is its write policy, not a corner of it. A first version dropped all retries, including throttling, and turned an ordinary throttled write into a failure (7833943-F1). A retried *streaming* put also used to return a doubled size and a wrong digest -- the counter and digest did not rewind with the body -- which BundleWriter would have committed as the receipt's integrity record; fixed, and the returned values are now asserted (6e73bc0-F1). Also given up, knowingly: the SDK's retry on 403 RequestTimeTooSkewed (clock skew). It is a definite refusal and now surfaces as one; an operator's clock is not something a retry should paper over. (2) **Open:** a stalled store also produced `400 IncompleteBody` in the test suite, and how a stall becomes that 400 is not reproduced (three mechanisms tested and ruled out; see commit 9991ff1). The tests no longer hit it because their MinIO runs on tmpfs; a slow production store still could. Our client reports it as an `ObjectStoreException`, loudly. **T8 owes:** the upload path's error UX and replay must treat "outcome unknown", this 400, and throttling that outlasted the store's retries ("nothing was written ... safe to try again later") as retryable-by-replay, never as a verdict on the bundle; owner T8, stop condition: an upload test that injects each, including a 503 SlowDown, and ends in a correct, replayed outcome. |

External implementation references checked during planning (not proof that Skald meets them):

- Isolation guidance consulted for the **Q2 decision of 2026-09-18**, recorded because the
  decision turns on what they separate rather than on a number any of them gives:
  [Firecracker vs gVisor](https://fly.io/learn/firecracker-vs-gvisor/),
  [Kata vs Firecracker vs gVisor](https://northflank.com/blog/kata-containers-vs-firecracker-vs-gvisor),
  ["Your container is not a sandbox"](https://emirb.github.io/blog/microvm-2026/) and
  [untrusted image builds in microVMs](https://www.pandastack.ai/blog/microvm-container-image-builds/).
  The distinction they draw, and the one this plan adopts: rootless answers whether the
  platform needs root to build; only a separate kernel answers what happens when build code
  defeats the shared one. The same sources say a hypervisor is unwarranted where every build
  comes from an account the operator issued, which is Skald's case. Reading them is not
  evidence Skald meets anything — T3 and T5 measure that, and the no-KVM constraint on this
  host bounds what can be measured at all.
- [Docker in gVisor](https://gvisor.dev/docs/tutorials/docker-in-gvisor/), for why gVisor is
  a candidate profile and not the default: it needs no KVM, so it is provable on this host,
  but overlay-on-overlay is refused by Linux and it wants a tmpfs upper layer.
- [BuildKit rootless requirements and process-sandbox caveats](https://github.com/moby/buildkit/blob/master/docs/rootless.md).
  Its container examples relax security profiles; `--oci-worker-no-process-sandbox` has
  documented process-control/cleanup drawbacks. This is why T3 precedes integration.
- [BuildKit configuration](https://github.com/moby/buildkit/blob/master/docs/buildkitd.toml.md)
  and [registry cache exporter](https://docs.docker.com/build/cache/backends/registry/).
  Cache export is separate from image publication; select and pin actual tool versions
  in the spike rather than relying on moving documentation examples.
- [S3 multipart lifecycle](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html).
  A completed upload exposes the object; unfinished uploads require abort/cleanup, and an
  ETag is not a reliable content digest. Live logs therefore use completed chunks.
- [Distribution garbage collection](https://distribution.github.io/distribution/about/garbage-collection/).
  Blob reclamation requires write coordination; registry age alone is not a DB reference check.
- [JSON Schema 2020-12](https://json-schema.org/draft/2020-12),
  [renv restore model](https://rstudio.github.io/renv/articles/renv.html),
  [Shiny for Python launch API](https://shiny.posit.co/py/api/core/run_app.html),
  [pip hash-checking and wheel-only installs](https://pip.pypa.io/en/stable/topics/secure-installs/),
  [Commons Compress security reports](https://commons.apache.org/proper/commons-compress/security.html),
  [networknt license](https://github.com/networknt/json-schema-validator/blob/master/LICENSE),
  [AWS SDK license](https://github.com/aws/aws-sdk-java-v2/blob/master/LICENSE.txt),
  [BuildKit license](https://github.com/moby/buildkit/blob/master/LICENSE).

## Done when

The operator can run one documented live script against `make dev`, without hand-editing
SQL or providing a prebuilt application image, and observe all of the following. Run the
full build/activation/rollback/cache sequence for **both R and Python Shiny**, including
Python Core and Express positive fixtures; neither language may be represented by a mock.

1. Alice uploads real locked-dependency R and Python Shiny bundles, sees validation/build status and
   logs **while building**, obtains an immutable image-backed staged version, explicitly
   activates it, and interacts with the application at its `/c/<path>/` URL. Bob is denied
   upload, build controls, source and logs; ordinary runtime ACL allow/deny still works.
2. A modified bundle builds version 2. Keep actual version-1 and version-2 containers
   alive across activation and rollback. Rollback points new requests at version 1 while
   both existing proxy IDs stay listed and reachable through their existing routes; neither
   container is restarted or silently rebuilt. Confirm instance-specific state remains.
   `/c/` resolves the currently active version per request: no promise is made to pin a
   fresh `/c/` navigation to an old version. Existing WebSocket connections are tested.
3. A failed dependency build and a timed-out malicious build create no runnable version,
   do not move the active pointer and leave viewable durable failure logs. Retry has a new
   build ID; an HTTP retry has the same ID. Platform restart, client disconnect and MinIO
   or registry outage do not manufacture success or leak workers/volumes indefinitely.
4. The independent extraction corpus rejects escapes and bombs with unchanged outside
   sentinels; real build-code attempts cannot reach the Docker daemon, host or sibling
   workload canaries. Positive controls prove the tests are live. All limits and cleanup
   are measured, and the independent mid-track and final security reviews are recorded.
5. A source-only edit reuses the dependency layer, relevant dependency/toolchain changes
   miss it, another content cannot poison it, and rollback still works after real cleanup
   with all retained-version artifacts protected. No privileged worker, Docker socket
   mount, broad egress bypass or insecure fallback was used to achieve a green.
6. V1 checksum is unchanged; V2 upgrades real existing content without changing IDs,
   paths, ACLs or legacy behavior. `content_env` is still unwritten and no secret injection
   is falsely advertised. The branch builds and all upstream/new tests and live scripts
   pass with actual counts and logs recorded for the implementation commit.

**Next agenda item:** T0/T1 with the confirmed R/Python scope, followed by the Q2 isolation
proof and independent review. Implementation proceeds
only against the agreed contract; this workplan itself adds no schema, code, dependency,
image, service or authorization change.
