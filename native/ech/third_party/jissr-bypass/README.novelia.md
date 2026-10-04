# Novelia ECH resolver fork

Source: https://github.com/inqadh/jissr-bypass/tree/v0.1.1

This directory contains the upstream root Go package and its tests, under the
original Apache-2.0 license in `LICENSE`. Mobile adapters, examples and release tooling
are omitted. `native/ech/go.mod` selects this source-controlled fork with `replace`.

Novelia changes:

- Network generations cancel in-flight DNS work and isolate shared queries/cache
  writes across resets.
- Wire and JSON DoH collect A and AAAA addresses from the same provider as ECH;
  cache expiry respects selected record and CNAME TTLs, capped by ResolutionTTL.
- Resolution snapshots support conditional cache invalidation and authenticated
  ECH retry configuration updates without extending DNS lifetime.

Novelia's own transport performs staggered multi-address TLS/ECH dialing and is
also used for probes. Upstream HTTP convenience APIs are retained for source
compatibility, but are not used by the Android bridge.

Offline checks (with the pinned Go toolchain and populated module cache):

```text
go test ./...
go test github.com/inqadh/jissr-bypass
```
