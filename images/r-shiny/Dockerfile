# Skald trusted base: R / Shiny (WORKPLAN-BUNDLES.md "Base images, recipes and runtime
# projection"; T7). Reviewed by hand; built by the operator when maintaining bases, never by
# an upload. images/catalog.json records what this resolves to.
#
# The upstream is pinned by digest (the multi-arch index digest of rocker/r-ver:4.6.1).
ARG UPSTREAM=rocker/r-ver@sha256:4e6e6696ff54a86625c7f80046478e67014a2184e942b85eeff43db7b992d93e
FROM ${UPSTREAM}

# The repository this base is BUILT through -- the operator's configured mirror (forge in the
# dev stack). Used only to install renv below; a publisher's build gets its repository from the
# server, never from this image.
ARG CRAN_MIRROR
ARG RENV_VERSION=1.3.0
# The SHA-256 of CRAN's own renv_1.3.0.tar.gz (fetched from cloud.r-project.org, whose
# PACKAGES index gives MD5 c815ae95cfee17ea8fd34dfb89c5732e for it -- matched). renv is the
# toolchain every R build's restore runs, so the bytes are pinned, not just the version a
# DESCRIPTION claims (4dd3f76-F1).
ARG RENV_SHA256=c6dd55e6ad50e846bb91e2d2ced99b32c08a110dc9f5337cb7ea29749f049c5f

# rocker points R at Posit Package Manager (p3m.dev) and sets PPM's binary-selecting user
# agent in Rprofile.site. Skald does not use PPM (user decision 2026-10-02), and a default
# repository baked into the image is one a build could silently resolve against. Replaced
# with an Rprofile that sets no repository at all.
RUN printf 'options(download.file.method = "libcurl")\n' > /usr/local/lib/R/etc/Rprofile.site

# The system libraries CRAN packages compile against. Revision 1 had only what the shiny tree
# needs (fs: libuv's headers, httpuv: zlib's, both through pkg-config). Revision 2 (user,
# 2026-10-18) adds what common apps need, found by publishing a real one (leaflet, plotly,
# duckdb, DT): curl and openssl's headers (curl, openssl, httr, s2), libxml2 (xml2), png, jpeg
# and tiff (png, jpeg, tiff, raster output), xz to unpack sources shipped as .tar.xz
# (duckdb), fontconfig, freetype, harfbuzz and fribidi (systemfonts, textshaping, ragg), ICU
# (stringi without its bundled copy), cmake (packages that build vendored C++), and the
# geospatial stack leaflet alone pulls in through terra: GDAL, GEOS, PROJ, SQLite, udunits
# (terra, sf, s2, units). Headers ship with the runtime libraries they belong to, so the same
# base serves the build's restore and the running app. System packages are the
# administrator's, installed here, never by an upload (Q3(4)). They come from Ubuntu's
# archive, whose signed Release files apt verifies; versions are not pinned, because the
# archive drops superseded versions and a pin would only make the base unbuildable later --
# the catalog records the NAMES, and bases-probe checks each is installed in the built image.
RUN apt-get update \
 && apt-get install -y --no-install-recommends cmake libcurl4-openssl-dev libfontconfig1-dev \
      libfreetype-dev libfribidi-dev libgdal-dev libgeos-dev libharfbuzz-dev libicu-dev \
      libjpeg-dev libpng-dev libproj-dev libsqlite3-dev libssl-dev libtiff-dev \
      libudunits2-dev libuv1-dev libxml2-dev pkg-config xz-utils zlib1g-dev \
 && rm -rf /var/lib/apt/lists/*

# renv at an EXACT version, from the mirror's current tree or its Archive, verified against
# the pinned SHA-256 BEFORE anything of it runs (installing a source package runs its code).
# A mismatch fails the base build; so does a version that does not install exactly.
RUN test -n "$CRAN_MIRROR" && Rscript --vanilla -e ' \
      m <- sub("/$", "", Sys.getenv("CRAN_MIRROR")); f <- sprintf("renv_%s.tar.gz", Sys.getenv("RENV_VERSION")); \
      ok <- tryCatch({ download.file(sprintf("%s/src/contrib/%s", m, f), "/tmp/renv.tar.gz", quiet = TRUE); TRUE }, \
                     error = function(e) FALSE, warning = function(w) FALSE); \
      if (!ok) download.file(sprintf("%s/src/contrib/Archive/renv/%s", m, f), "/tmp/renv.tar.gz", quiet = TRUE)' \
 && echo "$RENV_SHA256  /tmp/renv.tar.gz" | sha256sum --check --strict - \
 && Rscript --vanilla -e 'install.packages("/tmp/renv.tar.gz", repos = NULL, type = "source"); \
      v <- Sys.getenv("RENV_VERSION"); \
      if (as.character(packageVersion("renv")) != v) stop("renv ", packageVersion("renv"), " is not ", v)' \
 && rm /tmp/renv.tar.gz

# A fixed, unprivileged identity for both the build's restore step and the running app.
RUN groupadd --gid 10001 skald && useradd --uid 10001 --gid 10001 --no-create-home \
      --home-dir /app --shell /usr/sbin/nologin skald
