[![Vessel Banner](https://raw.githubusercontent.com/MaboroshiKobo/branding/refs/heads/main/projects/vessel/banners/vessel_2048.png)](https://docs.maboroshi.org/projects/vessel)

<div align="center">
  <p>
    <img alt="paper" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/paper_vector.svg">
    <img alt="purpur" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/purpur_vector.svg">
    <img alt="spigot" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/unsupported/spigot_vector.svg">
  </p>

  <p>
    <a href="https://github.com/MaboroshiKobo/Vessel"><img alt="github" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg"></a>
    <a href="https://hangar.papermc.io/Maboroshi/Vessel"><img alt="hangar" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/hangar_vector.svg"></a>
    <a href="https://modrinth.com/plugin/vessel"><img alt="modrinth" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg"></a>
  </p>

  <p>
    <a href="https://docs.maboroshi.org/projects/vessel"><img alt="generic" height="56" src="https://raw.githubusercontent.com/MaboroshiKobo/branding/refs/heads/main/socials/128x/domain_icon_bg.png"></a>
    <a href="https://discord.maboroshi.org"><img alt="discord-singular" height="56" src="https://raw.githubusercontent.com/MaboroshiKobo/branding/refs/heads/main/socials/128x/discord_icon_bg.png"></a>
  </p>
</div>

## Custom entity capture and item-based mob transportation

Vessel is a utility plugin that lets players capture, store, and transport entities using highly customizable pocket items. It provides administrators complete control over item behaviors, reuse rules, entity restrictions, and world filters to safely manage mob movement on a server.

> **About this fork.** This repository (`Vessel-Lycohinya`) is a fork of upstream Vessel maintained
> specifically for the Lycohinya server's own infrastructure — GriefPrevention version pinning,
> Folia/Lophinya compatibility notes, and the storage/migration work in this README are all written
> against Lycohinya's actual deployed stack, not general-purpose guarantees for every server. If
> you're not running Lycohinya's server, the general Vessel feature set below still applies, but
> version-specific claims (GriefPrevention 16.18.7, Lophinya's compat shims, etc.) may not.

## Features

* Create infinite item templates with distinct textures, custom lore, and targeted permission groups.
* Configure vessels as single-use consumables or infinitely reusable item containers.
* Apply material overrides to change a vessel's item appearance based on the specific entity caught inside.
* Restrict captures by world, entity type, spawn reasons, nametagged status, or pet ownership.
* Trigger customized sound and particle effects instantly during capture and release actions.

## Prerequisites

Vessel is compatible with the following plugins:

* [Nexo](https://www.nexomc.com/) (Optional for custom item models)
* [MythicMobs](https://mythiccraft.io/) (Optional for custom entity captures)
* [WorldGuard](https://enginehub.org/worldguard/) (Optional for region protection)
* [Towny](https://www.townyadvanced.com/) (Optional for town protection)
* [GriefPrevention](https://github.com/GriefPrevention/GriefPrevention) (Optional for claim protection)
* [PlaceholderAPI](https://placeholderapi.com/) (Optional)
* [PluginUpdater](https://modrinth.com/plugin/plugin-updater) (Optional for update checking and automatic updates)

Vessel declares `folia-supported: true` and is tested against both Paper and Folia (including
Folia-derived forks). See [Folia support](#folia-support) below for what that guarantees.

## Storage format & migration

Captured entity data is stored in a versioned envelope on the vessel item's PersistentDataContainer:
Vessel schema version, the Minecraft DataVersion at capture time, the codec id, the entity type, the
serialized entity payload, a CRC32 checksum, and a per-instance Vessel ID. Items captured before this
envelope existed ("legacy v0" — a bare `EntitySnapshot#getAsString()` value with no envelope) are
detected automatically and migrated the next time the item is used; there is no server-wide scan.

**What this can and cannot guarantee**, based on reading Paper 26.2's actual `EntitySnapshot`/
`EntityFactory`/`UnsafeValues` source and Javadocs (not assumptions):

* `EntitySnapshot#getAsString()` is Paper's own `@Experimental` API, and its Javadoc explicitly says
  the string "should not be relied upon as a serializable value." It is still the least-bad public
  option for entity serialization — the alternative, `UnsafeValues#serializeEntity`, sits on an
  interface that has been blanket-`@Deprecated` since Bukkit 1.7.2. Vessel uses `EntitySnapshot`
  because it is the newer, actively-maintained surface, not because it's promised to be stable.
* The stored string carries **no embedded Minecraft DataVersion** on its own — Vessel stamps one
  itself at capture time (`UnsafeValues#getDataVersion()`) so a future migrator has something to
  key off of. Payloads migrated from legacy v0 predate this and are marked with an explicit "unknown"
  sentinel rather than a guessed value.
* Paper does **not** run its DataFixerUpper over this payload on load the way it does for item JSON
  (`UnsafeValues#deserializeItemFromJson` is explicitly documented as migrating to the latest data
  version; nothing on the entity-snapshot path makes that claim). Practically: a payload captured on
  one Minecraft version is expected to load correctly on the same or an adjacent Paper build, but
  Vessel makes no promise it survives a large version jump unmodified — that's exactly the gap the
  schema-version/migrator chain exists to eventually close, not something solved today.
* Vanilla NBT strings hard-cap at 65,535 modified-UTF-8 bytes and silently save as an empty string
  past that limit — with no runtime warning. Vessel checks the encoded payload size *before* writing
  it and refuses to capture an entity whose data would exceed a safe margin under that cap, rather
  than risk silently losing it on the next save.
* A checksum-mismatched, unparsable, or future-schema-version payload is rejected outright — the
  item is left completely untouched (no consumption, no attempted spawn).

## GriefPrevention integration

If GriefPrevention is installed, capture and release are each gated by a configurable claim
permission (`config.yml` → `griefprevention.capture-permission` / `release-permission`, accepted
values `ACCESS`, `CONTAINER`, `BUILD`; invalid values fall back to a safe default with a warning).
Everything else — claim ownership, trust lists, subdivisions, Admin Claims, public trust, and the
`/ignoreclaims` toggle — is delegated entirely to GriefPrevention's own `Claim#checkPermission`, not
reimplemented. When GriefPrevention denies an action, its specific denial reason is shown to the
player alongside Vessel's own message. GriefPrevention is a soft dependency: Vessel starts up
normally without it, and this integration does not affect WorldGuard/Towny support.

Release checks both the clicked block and the safe spawn destination. Searching for free space
cannot bypass a protected claim at its edge or above its ceiling; owner and trusted permissions
still come from GriefPrevention. Candidate blocks outside the current Folia region are skipped
before accessing them. HanaToki protects its registered dungeon worlds by cancelling
`VesselReleaseEvent` before any snapshot/Mythic spawn or item consumption, using its existing
`hanatoki.build` permission. This does not depend on the captured entity's original spawn reason.

**GriefPrevention on Lophinya (Lycohinya's Folia fork): works, but needs Lophinya's compat shim.**
Plain GriefPrevention 16.18.7 (what Lycohinya's server actually runs) does not declare
`folia-supported` and internally calls the legacy `Bukkit.getScheduler().scheduleSyncRepeatingTask`
API, which a stock Folia build rejects outright (confirmed by actually hitting this on an unpatched
Folia/Luminol build: `UnsupportedOperationException`, plugin disables itself). Lophinya specifically
ships two compatibility patches for exactly this: one that opens the `folia-supported` load gate for
any Paper plugin, and one (`LophinyaPluginSchedulerDispatch`) that redispatches GriefPrevention's
specific sync-scheduler tasks to the correct scheduler by a SHA-256-keyed rule table matched to the
**exact 16.18.7 jar** — not a general "make any plugin Folia-safe" shim. This was confirmed live this
session: GriefPrevention 16.18.7 loads and enables cleanly on Lophinya with
`-Dlophinya.compat.pluginSchedulerDispatch=true` set, and Vessel enables right after with zero
errors. Region-ownership checks (`TickThread.ensureTickThread`) still apply to everything else
GriefPrevention does — the shim only covers the specific scheduler calls in its rule table.
Practical implications for anyone running this fork:
* A generic/vanilla Folia build (no Lophinya patches) will **not** run GriefPrevention 16.18.x at
  all — Vessel handles that gracefully (a crashed-and-disabled GriefPrevention is treated the same
  as it being absent), but the integration is simply inert there.
* On Lophinya, the JVM flag above must be set, and the GriefPrevention jar must match the version the
  rule table is keyed to (16.18.7 as of this writing) — a different GriefPrevention build reintroduces
  the crash even on Lophinya. See `TESTING.md` for the full live-verification writeup.

## Folia support

Capture/release run inside the Bukkit events they're triggered from, which Folia already dispatches
on the region owning the interacting player — no extra scheduler hop is needed there. The one
location Vessel computes itself (the release point, found near the clicked block) is re-validated
with `Bukkit.isOwnedByCurrentRegion(...)` immediately before anything (GriefPrevention's check, the
entity spawn) touches it, since Folia's region ownership can shift between ticks; if that check
fails, the release is cleanly rejected rather than risking a cross-region thread violation. Actions
that fan out to every online player (broadcast sounds, `global: true` command actions) are scheduled
per-player onto each player's own `EntityScheduler`, since a single calling thread cannot safely read
or act on players owned by other regions.

## Permissions

Vessel declares no `permissions:` defaults (unchanged from upstream) — grant these through your
permissions plugin.

* `vessel.use.<type>` — use a specific vessel template (e.g. `vessel.use.consumable`), required for
  both capture and release.
* `vessel.capture.<entity_type>` / `vessel.release.<entity_type>` — per-species capture/release (e.g.
  `vessel.capture.cow`); `vessel.capture.*` / `vessel.release.*` grant all species.
* `vessel.capture.<group>` / `vessel.release.<group>` — capture/release by category instead of
  per-species: `animals`, `monsters`, `golems`, `fish`, `watermobs`, `ambient`, `raiders`, `bosses`,
  `illagers`, `tameable`, `npcs`.
* `vessel.command.about`, `vessel.command.help`, `vessel.command.reload`, `vessel.command.give` —
  the `/vessel` subcommands.

## Known limitations

* Entity data captured on one Minecraft/Paper version is not guaranteed to load correctly after a
  large version jump — see "Storage format & migration" above.
* Data larger than the safe PDC string threshold cannot be captured at all (the player is told why).
  No entity type is hardcoded as excluded for this reason; it depends on how much state that specific
  entity instance is carrying (trades, passengers, custom NBT, etc.).
* Vessel compiles against `com.griefprevention:GriefPrevention:16.18.2-SNAPSHOT` (the newest version
  actually published to Maven) but Lycohinya's real server runs a newer, non-Maven-published
  **16.18.7**. Both were checked directly via `javap`: 16.18.2-SNAPSHOT's `ClaimPermission` enum has
  `Inventory` but not `Container`; 16.18.7 has both. `GriefPreventionProtectionAdapter` deliberately
  maps to `Inventory` — the one name confirmed present in both. If a future Maven-published version
  drops `Inventory` entirely, that mapping needs to move to `Container` (and re-verify against
  whatever GriefPrevention version Lycohinya is running by then).
* On a plain/vanilla Folia build without Lophinya's compat patches, GriefPrevention 16.18.x does not
  run at all — Vessel handles that gracefully (treated the same as GriefPrevention being absent), but
  the integration is inert there. On Lophinya specifically (with its compat shim and the
  `-Dlophinya.compat.pluginSchedulerDispatch=true` flag), GriefPrevention 16.18.7 does run — confirmed
  live this session, see `TESTING.md`.
* Live client-driven capture/release specifically on Folia/Lophinya was not completed this session —
  blocked by mineflayer bot-tooling instability on the experimental Folia builds tested (disconnects
  during normal play, unrelated to Vessel's code or to the GriefPrevention compat question, which was
  separately confirmed via server logs). Folia/Lophinya's clean plugin load/enable (with GriefPrevention
  actually working) and the code-level thread-safety audit are verified; a full interactive
  capture/release pass on Folia specifically still needs a real Minecraft client. See `TESTING.md`.

## Documentation & Support

For configurations, commands, and permissions, check out our [wiki](https://docs.maboroshi.org/projects/vessel). For bugs, questions, or updates, visit our [Discord server](https://discord.maboroshi.org) or open a [GitHub Issue](https://github.com/MaboroshiKobo/Vessel/issues).

## Statistics

This plugin utilizes [bStats](https://bstats.org/plugin/bukkit/Vessel/31642) to collect anonymous usage metrics.

![bStats Metrics](https://bstats.org/signatures/bukkit/Vessel.svg)

## Building

To build the project from source, ensure you have a Java 25 environment configured.

```bash
./gradlew build
```
