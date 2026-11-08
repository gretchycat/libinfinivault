# Runtime capabilities — vault

Updated 2026-11-04.

## Linked dependencies

Stream, Store and Sound audio-api are explicit Gradle API dependencies. Standalone settings include the corresponding sibling source builds. Store never depends on Vault. Existing 7-Zip/native filesystem dependencies remain declared.

Dependencies must be packaged/linked, not assumed to be present. Standalone source
builds resolve sibling repositories through includeBuild (or
`-PinfiniLibrariesDir=/path/to/libraries`). Android modules need SDK 36, min API 26.
A library dependency is allowed; an InfiniFolder application/UI dependency is not.

## Public discovery and execution

`VaultCapabilities.registry`, `ContainerCapabilityHandler`; ArchiveReader list/open/extract dispatches registered container handlers before its compatibility reader. Existing VaultStreams exposes nested entry SourceResources.

Use `RuntimeCapabilities.library("vault")` for the common query, probe, callback and
revision API. The schema and registration contract are documented in the linked
libinfinistream repository's `CAPABILITIES.md`. Providers ship a standard
`META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider` resource.
Constructing a provider must not connect, open input or initialize the application.

Implement list and openEntry against the request/source contracts, register a container handler/provider. Archive recognition, MIME association and real browser execution pick up its description. Strong identityMagic or a registered FormatProbe can identify extensionless content.

Queries retain overlapping handlers. Availability is separate from metadata-only
identity. Resource probes are bounded/cancellable and can narrow operations; real
API execution still validates input. Use existing Stream sources, positional I/O,
owned outputs and typed processors; do not duplicate decoders in consumers.

## Limits

The existing archive/filesystem dispatcher is a single CONDITIONAL compatibility handler: format signatures are provisional, native/parser subsets are checked by actual reads. No universal WRITE operation is advertised. Writes stay governed by mounted resource capabilities, existing safe publication and filesystem-specific rules. New read handlers do not implicitly gain write support.

Discovery is for installed classpath components, not a downloaded code loader.
Android/native display and live remote behavior require device checks in addition
to JVM tests. Descriptive catalog exports do not prove runtime support.

## Standalone build and tests

```sh
gradle :vault-android:testDebugUnitTest :vault-android:assembleDebug --offline
```

No browser process is required. Keep existing format regression suites; capability
tests invoke actual services/parsers rather than scripted success responses.

## New format modules

New archive and filesystem-image handlers must validate structure with the actual parser, expose bounded metadata through `MetadataCapabilityHandler`, and test both a valid container and malformed/extension-mismatched input. Do not treat matching extensions or ZIP-like bytes as proof that the container can be browsed. Follow the shared [Stream conformance rule](../libinfinistream/CAPABILITIES.md#format-module-validation-and-metadata-requirements).

## Host audit record

InfiniFolder's 2026-11-04 runtime-discovery audit exercises this repository through
its standalone Gradle test task, and also builds the main and combined standalone
viewer APKs. The host `docs/runtime-capabilities.md` records exact commands, fresh
counts, failures repaired during the gate and unverified device paths. This is
separate from the historical extraction evidence in SOURCE_PROVENANCE.md.
