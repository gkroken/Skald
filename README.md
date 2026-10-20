# Skald

Self-service publishing for R and Python data apps, built on
[ShinyProxy](https://github.com/openanalytics/shinyproxy).

A publisher uploads a bundle (an app plus its lock file); Skald builds it into an image in a
sandbox and serves it through ShinyProxy. No administrator edits `application.yml` per app.

**Status: pre-release, not yet usable end to end.** Bundles are validated, stored and built;
admin upload, live build logs (T8) and managed publication (T9) are still to come. The plan
and its progress are in [`WORKPLAN-BUNDLES.md`](WORKPLAN-BUNDLES.md).

## How it fits together

- **ShinyProxy and ContainerProxy run the apps.** Skald is a plugin layer: new code lives in
  `eu.openanalytics.shinyproxy.publisher.*`, and upstream classes change only where there is no
  extension point, each change recorded in [`docs/UPSTREAM_CHANGES.md`](docs/UPSTREAM_CHANGES.md).
  [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) maps the seams.
- **Builds run in sandboxes.** Every bundle is treated as untrusted code: each build gets its
  own rootless BuildKit worker with no Docker socket, no host mounts, resource and disk
  limits, and egress only to the package mirrors and the registry it pushes to.
- **Builds start from trusted bases** (rocker for R, python-slim for Python) and restore
  packages from the bundle's lock file (`renv.lock`, or a hash-pinned `requirements.lock`).
- **Package mirrors come from [forge](https://github.com/gkroken/forge)**, a separate service
  that sits in front of CRAN and PyPI. Optionally, Skald compiles each R package once into a
  shared Linux binary and serves it from forge, so later builds install binaries instead of
  compiling.

## Requirements

- Linux with Docker. There is no local JDK or Maven: everything builds in containers.
- PostgreSQL, S3-compatible storage and an OCI registry (the dev stack provides all three).
- A forge instance, or other CRAN and PyPI mirrors the build workers may reach.

## Build, run, test

```bash
make build       # the executable jar, target/shinyproxy-<version>-exec.jar
make dev         # build, then start the dev stack: app, PostgreSQL, Keycloak, MinIO, registry
bash dev/smoke.sh  # drive the OIDC flow for two users; checks access is allowed and denied
make test        # the test suite; it starts real containers
```

## Configuration

Every Skald setting is listed, with its type, default and bounds, in
[`docs/CONFIGURATION.md`](docs/CONFIGURATION.md), generated from
[`spec/configuration-v1.json`](spec/configuration-v1.json); a commented example is in
[`config/application.example.yml`](config/application.example.yml). Unknown or misspelled keys
and secrets written in plain text are refused at startup. To check a configuration without
starting the server:

```bash
java -jar target/shinyproxy-<version>-exec.jar --validate-config
```

## Documentation

| | |
|---|---|
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Architecture decisions, with their reasons |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | ShinyProxy and ContainerProxy extension points |
| [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) | Every setting |
| [`docs/UPSTREAM_CHANGES.md`](docs/UPSTREAM_CHANGES.md) | Every change to upstream code |
| [`WORKPLAN.md`](WORKPLAN.md), [`WORKPLAN-BUNDLES.md`](WORKPLAN-BUNDLES.md) | The plan, track by track |
| [`README-shinyproxy.md`](README-shinyproxy.md) | Upstream's README |

## License and attribution

Apache License 2.0; see [`LICENSE`](LICENSE).

Skald is built on ShinyProxy and ContainerProxy, Copyright (C) 2016-2026 Open Analytics NV,
also under Apache 2.0. Their files keep their original headers; Skald modifies none of them
(it overrides behaviour from its own packages instead, each override listed in
[`docs/UPSTREAM_CHANGES.md`](docs/UPSTREAM_CHANGES.md)). Skald is not affiliated with or
endorsed by Open Analytics; the code keeps the ShinyProxy name, the product does not (see
ADR-0002 in
[`docs/DECISIONS.md`](docs/DECISIONS.md)). Shiny is a trademark of Posit Software, PBC.
