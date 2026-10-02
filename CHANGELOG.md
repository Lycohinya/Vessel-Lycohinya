# Changelog

All notable changes to this fork are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed

- Minecraft 26.3: cloud-paper 2.0.1 finds Paper 26.3's renamed `CraftItemStack` mirror methods;
  2.0.0 failed at enable with "Couldn't find asBukkitCopy or asCraftMirror method".

### Added

- Versioned entity payload storage: captured entity data is now wrapped in an envelope recording
  Vessel's schema version, the Minecraft DataVersion at capture time, the codec id, the entity type,
  the serialized payload, a CRC32 checksum, and the Vessel ID. Legacy pre-envelope items (raw
  `EntitySnapshot#getAsString()`, no version metadata — now called "schema v0") are detected
  automatically and migrated lazily the next time the item is used, with no server-wide scan.
- Malformed, checksum-mismatched, or future-schema-version payloads are now rejected outright rather
  than risk spawning garbage or losing the original item: the item is left completely untouched and
  the player is told the vessel's data is corrupted.
- Entities whose serialized data would exceed the safe PersistentDataContainer string size (vanilla
  NBT strings silently truncate to empty past 65,535 bytes) are now rejected at capture time instead
  of risking silent data loss on the next world save.
- GriefPrevention support (optional soft dependency): capture requires a configurable claim
  permission (default `CONTAINER`), release requires another (default `BUILD`). Claim
  ownership/trust/subdivisions/Admin Claims/public trust/`ignoreclaims` are all delegated to
  GriefPrevention's own `Claim#checkPermission` — not reimplemented — and its specific denial reason
  is shown to the player when available.
- An in-flight guard keyed by Vessel ID (release) / target entity UUID (capture) prevents the same
  vessel or the same targeted mob from being processed twice by overlapping interactions.
- `Bukkit.isOwnedByCurrentRegion(...)` re-validation before acting on a computed release location, and
  per-player `EntityScheduler` dispatch for any action that fans out to all online players (broadcast
  sounds, `global: true` command actions) — both previously assumed a single main thread and would
  have been unsafe under Folia if exercised.

### Changed

- `ProtectionAdapter` (and the WorldGuard/Towny adapters) now return a `ProtectionResult` carrying an
  optional denial reason instead of a bare `boolean`, so a specific reason (currently only surfaced by
  the new GriefPrevention adapter) can reach the player.
- Capture now serializes and validates the result item fully before removing the target mob or
  consuming the vessel in hand; a failure at any point leaves the mob alive and the item untouched.

### Fixed

- `Vessel.onDisable()` was a no-op; the static plugin instance is now cleared so a PlugMan-style
  unload/reload doesn't leak the previous classloader.

### Notes on Folia/Lophinya + GriefPrevention (confirmed live this session, see `TESTING.md`)

- Plain GriefPrevention does not run on a stock/vanilla Folia build (no `folia-supported` declaration,
  calls the legacy `Bukkit.getScheduler().scheduleSyncRepeatingTask` API, which Folia's threading
  model rejects). Vessel handles that gracefully — a crashed-and-disabled GriefPrevention is treated
  the same as "not installed."
- On **Lophinya** (Lycohinya's own Folia fork) specifically, GriefPrevention 16.18.7 works correctly
  via Lophinya's own compat patches (`LophinyaFoliaSupportedGate` +
  `LophinyaPluginSchedulerDispatch`, a version-locked scheduler-redispatch shim), launched with
  `-Dlophinya.compat.pluginSchedulerDispatch=true`. Confirmed by actually starting it: GriefPrevention
  16.18.7 boots cleanly and Vessel enables right after with zero errors. An initial test against an
  unpatched Luminol build and the wrong GriefPrevention version (16.18.2) reached the wrong
  conclusion — corrected once retested against Lophinya's actual patched build and the actual
  deployed GriefPrevention version.

## [2.1.1] and earlier

See upstream [MaboroshiKobo/Vessel](https://github.com/MaboroshiKobo/Vessel) releases — this fork's
history before the changes above matches upstream.
