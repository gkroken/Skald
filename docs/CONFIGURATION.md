# Configuring Skald

Skald reads its settings from the `skald:` section of `application.yml` (or any Spring Boot
property source: environment variables, `--skald.x=y` on the command line, mounted files
through `spring.config.import: configtree:/run/secrets/`). Upstream's `proxy:` section is
documented by ShinyProxy and is not repeated here.

Everything below the generated marker comes from `spec/configuration-v1.json`, the one place
every key, type and default is written down; `ConfigurationSpecTest` fails when the code or
this page drifts from it. The same file generates
`schemas/config/skald-config.schema.json`: add

```yaml
# yaml-language-server: $schema=../schemas/config/skald-config.schema.json
```

as the first line of your `application.yml` (with the path to the schema) for completion and
validation in VS Code, IntelliJ, Neovim or Zed.

## Checking a configuration before you deploy it

Skald refuses to start on a configuration it cannot honour, and lists every problem at once:
an unknown key (with the key it was renamed to, or the one you probably meant), a value of
the wrong type or out of bounds, a rule between keys (a renewal slower than half its lease),
and, with the build worker enabled, every setting it needs. To run the same checks without
starting anything, in CI or before a deploy, add `--validate-config` with the configuration
you will deploy:

```sh
java -jar shinyproxy-<version>-exec.jar --validate-config \
    --spring.config.location=file:/opt/skald/application.yml
```

It prints `Skald's configuration is valid.` and exits 0, or prints every problem and exits 1.
It reads the files, the environment and the command line exactly as startup does, and opens
no database, Docker or network connection, so it can run anywhere the configuration and its
environment variables are. A secret's value is never printed.

## Starting from the example

`config/application.example.yml` holds every key at its default, each with its description; a
key with no default is commented out with an example value. Copy only the keys you change
into your `application.yml`: a key you leave out follows Skald's default, also when a later
release changes it.

## Sizing

- **One host** (Skald, its database and its builds on one machine): the defaults. One app
  build at a time (`skald.builds.max-running: 1`) and one package compile beside it
  (`skald.builds.binaries.max-running: 1`); one ready worker per published base
  (`skald.builds.worker.pool-size: 1`) so a first publish does not wait for a worker to start.
- **Several instances** sharing one database: every key marked *deployment* below must be the
  same on all of them, since the database counts against it (`max-running` is a limit for the
  whole deployment, not per instance); keys marked *instance* (the worker's Docker host, its
  workspace, its pool) are each instance's own. Turn the worker on only on instances whose
  Docker host should run builds.

<!-- BEGIN generated from spec/configuration-v1.json by ConfigurationSpecTest; do not edit by hand -->

## Conventions

- **names**: kebab-case. Every key may also be given as an environment variable (SKALD_BUILDS_MAX_RUNNING or SKALD_BUILDS_MAXRUNNING), and within each element case, dashes and underscores are ignored (maxRunning, max_running), as Spring's relaxed binding reads them; the startup check and the sections read the same names.
- **duration**: A duration with its unit: 300s, 20m, 3h, or ISO-8601 PT5M. A bare number is SECONDS everywhere under skald.*, including the worker's durations, where Spring alone would read milliseconds. parser settings-seconds: whole seconds only (a lease or deadline is kept to the second); parser spring: Spring's converter, sub-second values allowed.
- **size**: A size with its unit: 64MB, 2GB, 512B. A bare number is refused: '64' meant as megabytes would otherwise be 64 bytes.
- **secret**: A secret comes from the environment (SKALD_BUILDS_BINARIES_FORGE_TOKEN), from Spring's configtree: import of mounted files (/run/secrets), or as a ${VARIABLE} placeholder with no default. Written in plain text in a file, on the command line or as a system property (both visible in a process listing), or as a placeholder with a default (${TOKEN:abc123}, the secret in the file after all), it stops startup. It is never logged; an error about it never repeats its value.
- **scope**: deployment: every instance must agree (a shared database counts it); instance: this process and its Docker host only.
- **renamed**: Keys renamed in T7c (2026-10-19) before anything shipped, with no aliases: an old name stops startup and the error names the new one; a key whose unit changed also changed its name, so an old value is never read in a new unit.
- **parsers**: settings / settings-seconds: publisher.config.Settings inside a section parser that refuses unknown keys; spring: Spring's binder on a typed class; value: a single @Value or Environment read; extraction-limits: spec/extraction-limits-v1.json's own parser.

## skald.storage

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `endpoint` | url | unset | deployment | The S3-compatible object store (MinIO, Ceph, AWS S3) holding bundles and build logs. Unset: the publishing layer's storage is off and the application boots as upstream. |
| `region` | string | `us-east-1` | deployment | The store's region; most self-hosted stores accept any value. |
| `access-key` | string | unset | deployment | **Secret.** The store's access key id. Required with endpoint. |
| `secret-key` | string | unset | deployment | **Secret.** The store's secret key. Required with endpoint. |
| `path-style` | boolean | `true` | deployment | Path-style bucket addressing. On by default, unlike the SDK: a self-hosted store behind one hostname has no wildcard DNS. Turn off for AWS S3. |

## skald.dispatcher

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `require-override` | boolean | `true` | instance | Refuse to start when the dispatcher override that runtime-added content needs could not be installed. false starts anyway, and content published at run time will not open. |

## skald.builds

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `max-running` | integer, min 1 | `1` | deployment | App builds running at once across the whole deployment, counted in the database. |

## skald.builds.admission

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `queue-capacity` | integer, min 1 | `10` | deployment | Builds that may wait (QUEUED) across every content item. May be raised, never removed. |
| `per-publisher-active` | integer, min 1 | `3` | deployment | Queued, running and publishing builds one publisher may hold at once. |

## skald.builds.coordinator

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `lease` | duration, whole seconds | `300s` | deployment | How long a build's claim lasts without renewal before the reaper interrupts it. Long enough to outlast a stalled host: a holder survives a stall of lease minus renew-every. |
| `renew-every` | duration, whole seconds | `30s` | deployment | How often a running build renews its claim; at most half the lease. |
| `deadline` | duration, whole seconds | `20m` | deployment | A build's wall-clock deadline; past it the build is TIMED_OUT. |

## skald.builds.binaries

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `enabled` | boolean | `false` | deployment | Shared Linux binaries for R packages: compile each package once in a sandbox and serve it from forge. Off by default. The shared-binaries path has not had Skald's own security review yet: until a release says it has, enable it only on a deployment that accepts that a binary's integrity rests on forge and the uploader. Needs the worker and forge-url. |
| `max-running` | integer, min 1 | `1` | deployment | Package compiles running at once, beside the app builds. |
| `max-attempts` | integer, min 1 | `2` | deployment | Claims per package before it is FAILED and its apps keep restoring it from source. |
| `lease` | duration, whole seconds | `300s` | deployment | How long a compile's claim lasts without renewal. |
| `renew-every` | duration, whole seconds | `30s` | deployment | How often a compile renews its claim; at most half the lease. |
| `deadline` | duration, whole seconds | `3h` | deployment | One compile's wall-clock deadline. Large packages (duckdb) compile for well over an hour on a busy host. |
| `forge-url` | url | unset | deployment | forge's base URL, ending in /. Unset: shared binaries are off. No credentials, query or fragment in it. |
| `forge-binary-repository` | string | `cran-linux` | deployment | forge's repository for the binaries. |
| `forge-token` | string | unset | deployment | **Secret.** forge's write token, scoped to bin/linux/<codename>-<arch>/contrib/<rminor>/** of the binary repository. Required with forge-url. Never handed to any container. |
| `forge-registration-ttl` | duration, whole seconds | `60s` | deployment | How long forge's platform registration is trusted before it is read again. |
| `forge-timeout` | duration, whole seconds | `30s` | deployment | Connecting to forge and each small exchange. |
| `forge-upload-deadline` | duration, whole seconds | `10m` | deployment | One binary's upload, which grows with the binary. |
| `source-repository` | url | unset | deployment | forge's CRAN proxy member (never the group, which also serves hosted packages), ending in /, from which sources are verified against the public index. Unset: nothing is verified. |
| `source-max-index-size` | size | `64MB` | deployment | The largest package index read. |
| `source-max-size` | size | `256MB` | deployment | The largest source tarball read. |
| `source-max-expanded-size` | size | `2GB` | deployment | A source tarball's largest expanded size. |
| `source-fetch-timeout` | duration, whole seconds | `30s` | deployment | Connecting to the source repository. |
| `source-transfer-deadline` | duration, whole seconds | `10m` | deployment | One source or index transfer. |
| `source-index-ttl` | duration, whole seconds | `5m` | deployment | How long a fetched index is reused. |
| `source-behind-retry` | duration, whole seconds | `5m` | deployment | When the index is behind the lock, the first retry; it doubles from there. |
| `source-behind-max-retry` | duration, whole seconds | `6h` | deployment | The longest wait between retries of an index behind the lock. |
| `source-behind-bound` | duration, whole seconds | `48h` | deployment | How long after the first try a package whose index stays behind is refused INDEX_BEHIND; forge caches a proxy's index for 24 hours by default. |
| `source-archived` | boolean | `false` | deployment | Also build archived versions the index no longer lists, trusting their first fetch (its SHA-256 is recorded and never changes). |

## skald.builds.worker

| Key | Type | Default | Scope | Description |
|---|---|---|---|---|
| `enabled` | boolean | `false` | instance | Run builds on this instance's Docker host. Needs a database, object storage and every key the worker marks required. |
| `owner` | string | unset | instance | This instance's name on the builds it claims. Unset: the host name. |
| `worker-image` | image | unset | instance | **Required** with the worker. The build worker image (images/buildkit-worker), by digest. |
| `gateway-image` | image | unset | instance | **Required** with the worker. The egress gateway image (images/egress-gateway), by digest. |
| `registry` | string | unset | deployment | **Required** with the worker. The registry's host name as the worker reaches it (port 5000); built images and the published bases live there. |
| `registry-api` | url | unset | instance | **Required** with the worker. The registry as this instance reaches it, http(s)://host[:port], for publication's digest check. The credential travels in a Basic header: prefer https or an address on this host. |
| `registry-username` | string | unset | deployment | **Required** with the worker. The registry user builds push as. |
| `registry-password` | string | unset | deployment | **Secret.** **Required** with the worker. That user's password. |
| `outer-networks` | list | `[]` | instance | Docker networks the gateway joins to reach the registry and the mirrors. |
| `repos` | list | `[]` | instance | Hosts of the package repositories the gateway lets a build reach. |
| `private-mirrors` | list | `[]` | instance | Hosts of private package mirrors the gateway lets a build reach. |
| `cran-mirror` | url | unset | deployment | **Required** with the worker. The CRAN mirror R builds restore from (forge's CRAN group); its host must be in repos or private-mirrors. |
| `pypi-mirror` | url | unset | deployment | **Required** with the worker. The PyPI simple index Python builds install from; its host must be in repos or private-mirrors. |
| `cran-linux-binaries` | boolean | `false` | deployment | The CRAN mirror has forge's Linux binary layout (<cran-mirror>__linux__/<codename>/latest). Off by default: a mirror without it answers 404 and renv does not fall back to source. |
| `published-bases` | path | unset | instance | **Required** with the worker. The published-bases file dev/publish-bases.py wrote: each base's digest in the registry. |
| `workspace` | path | unset | instance | **Required** with the worker. A writable directory this instance alone extracts bundles into; locked at startup. |
| `architecture` | string | `amd64` | instance | The architecture this host builds for. |
| `quota` | size | `8GB` | instance | Each build's workspace quota. At least 1GB; a cold R build holds the extracted base, BuildKit's snapshots and the compiled library. |
| `poll` | duration | `1s` | instance | How often a running build's stop and log are checked; at most half the coordinator's renew-every. |
| `settle` | duration | `30s` | instance | How long a stopped build is given to wind down before its worker is removed. |
| `idle-pause` | duration | `2s` | instance | The build loop's pause when the queue is empty. |
| `sweep-period` | duration | `10m` | instance | How often the reconciliation sweep removes what no live attempt owns. |
| `sweep-grace` | duration | `15m` | instance | How long after a build ends the sweep leaves its attempt alone; longer than settle and the disposal's close wait. |
| `pool-size` | integer, min 0, max 8 | `1` | instance | Ready workers per published base, warmed ahead of any build. 0: every build launches its own. Each idle one holds a worker, a gateway, a loop device and a workspace image. |
| `parallel` | integer, min 1, max 32 | `1` | instance | Builds this instance's Docker host runs at once. At most skald.builds.max-running, the limit for the whole deployment, which every instance's builds count against. |
| `pool-ttl` | duration | `30m` | instance | How long a ready worker is kept before a fresh one replaces it. |
| `bundle-bucket` | string | `skald-bundles` | deployment | The object store bucket holding bundles. |
| `log-bucket` | string | `skald-logs` | deployment | The object store bucket holding build logs. |

## Renamed keys

Keys renamed in T7c (2026-10-19) before anything shipped, with no aliases: an old name stops startup and the error names the new one; a key whose unit changed also changed its name, so an old value is never read in a new unit.

| Old key | New key |
|---|---|
| `skald.builds.coordinator.max-running` | `skald.builds.max-running` |
| `skald.builds.coordinator.lease-seconds` | `skald.builds.coordinator.lease` |
| `skald.builds.coordinator.renew-every-seconds` | `skald.builds.coordinator.renew-every` |
| `skald.builds.coordinator.deadline-minutes` | `skald.builds.coordinator.deadline` |
| `skald.builds.binaries-enabled` | `skald.builds.binaries.enabled` |
| `skald.builds.binaries.lease-seconds` | `skald.builds.binaries.lease` |
| `skald.builds.binaries.renew-every-seconds` | `skald.builds.binaries.renew-every` |
| `skald.builds.binaries.deadline-minutes` | `skald.builds.binaries.deadline` |
| `skald.builds.binaries.forge-registration-ttl-seconds` | `skald.builds.binaries.forge-registration-ttl` |
| `skald.builds.binaries.forge-timeout-seconds` | `skald.builds.binaries.forge-timeout` |
| `skald.builds.binaries.forge-upload-deadline-seconds` | `skald.builds.binaries.forge-upload-deadline` |
| `skald.builds.binaries.source-max-index-bytes` | `skald.builds.binaries.source-max-index-size` |
| `skald.builds.binaries.source-max-bytes` | `skald.builds.binaries.source-max-size` |
| `skald.builds.binaries.source-max-expanded-bytes` | `skald.builds.binaries.source-max-expanded-size` |
| `skald.builds.binaries.source-fetch-timeout-seconds` | `skald.builds.binaries.source-fetch-timeout` |
| `skald.builds.binaries.source-transfer-deadline-seconds` | `skald.builds.binaries.source-transfer-deadline` |
| `skald.builds.binaries.source-index-ttl-seconds` | `skald.builds.binaries.source-index-ttl` |
| `skald.builds.binaries.source-behind-retry-seconds` | `skald.builds.binaries.source-behind-retry` |
| `skald.builds.binaries.source-behind-max-retry-seconds` | `skald.builds.binaries.source-behind-max-retry` |
| `skald.builds.binaries.source-behind-bound-hours` | `skald.builds.binaries.source-behind-bound` |
| `skald.builds.worker.quota-megabytes` | `skald.builds.worker.quota` |

## skald.bundle.limits

The bundle extraction bounds, one key per bound in that file (its name with - for _), each a whole number in the unit its name carries, at most its absolute_max. They keep that file's names: the T5 gate's corpus and oracle are written against them.

| Key | Unit | Default | Most | Description |
|---|---|---|---|---|
| `max-compressed-bytes` | bytes | `268435456` | 68719476736 | The uploaded archive as it arrived. Checked against the byte count before a single header is parsed, so it is the first bound anything hits. |
| `max-expanded-bytes` | bytes | `2147483648` | 274877906944 | Total bytes written across all members. Counted from bytes actually streamed, not from declared sizes, so a lying header cannot buy room. |
| `max-file-bytes` | bytes | `536870912` | 68719476736 | Any single regular member. |
| `max-entries` | count | `20000` | 5000000 | Physical headers, including directories, extended headers and anything else parsed and ignored. Metadata counts: an archive of nothing but ignored headers is still an archive the parser walked. |
| `max-manifest-bytes` | bytes | `4194304` | 67108864 | The manifest member. Distinct from max_file_bytes and always the smaller of the two in practice, because the manifest is parsed into memory as a document rather than streamed to disk. |
| `max-extended-header-bytes` | bytes | `65536` | 16777216 | One PAX extended header's own payload. tarfile never reports this, which is why the corpus fixture bomb-huge-pax-field is accepted by a library-only extractor. |
| `max-path-bytes` | bytes | `1024` | 4096 | The member path in UTF-8, after any approved PAX override, excluding the extraction root. |
| `max-segment-bytes` | bytes | `255` | 255 | One path segment in UTF-8. |
| `max-depth` | count | `32` | 256 | Path segments in a member, counted after validation. |
| `extraction-deadline-seconds` | seconds | `60` | 3600 | Wall clock for one extraction, enforced by the extractor itself. The corpus oracle applies its own outer deadline on top, because a bound a subject enforces on itself is a bound that has to be checked from outside. |

<!-- END generated -->
