package org.maboroshi.vessel.listener;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.maboroshi.vessel.Vessel;
import org.maboroshi.vessel.api.event.VesselReleaseEvent;
import org.maboroshi.vessel.config.ConfigManager;
import org.maboroshi.vessel.config.objects.FilterRule;
import org.maboroshi.vessel.config.settings.VesselTemplate;
import org.maboroshi.vessel.handler.CooldownHandler;
import org.maboroshi.vessel.manager.InFlightGuard;
import org.maboroshi.vessel.protection.ProtectionResult;
import org.maboroshi.vessel.storage.VesselDataException;
import org.maboroshi.vessel.storage.VesselPayloadStore;
import org.maboroshi.vessel.storage.VesselPayloadStore.ReadResult;
import org.maboroshi.vessel.storage.entity.CompoundEntityTree;
import org.maboroshi.vessel.util.Keys;
import org.maboroshi.vessel.util.Log;
import org.maboroshi.vessel.util.Messages;
import org.maboroshi.vessel.util.MythicHook;
import org.maboroshi.vessel.util.VesselUtils;

public class ReleaseListener implements Listener {
    private final Vessel plugin;
    private final ConfigManager config;
    private final CooldownHandler cooldownHandler;
    private final InFlightGuard inFlightGuard;

    public ReleaseListener(Vessel plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.cooldownHandler = plugin.getCooldownHandler();
        this.inFlightGuard = plugin.getInFlightGuard();
    }

    @EventHandler
    public void onRelease(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (!itemInHand.hasItemMeta()) return;

        String vesselType = VesselUtils.getTemplateId(itemInHand);
        if (vesselType == null) return;

        event.setCancelled(true);

        VesselTemplate template = config.getVesselTemplate(vesselType);
        if (template == null) return;

        if (!player.hasPermission("vessel.use." + vesselType.toLowerCase(Locale.ROOT))) {
            Messages.send(player, config.getMessageConfig().general.cannotUseVessel);
            return;
        }

        ItemMeta meta = itemInHand.getItemMeta();

        if (!meta.getPersistentDataContainer().has(Keys.MOB_DATA, PersistentDataType.STRING)) return;

        // Guards against the same vessel item being released twice from a concurrent/duplicate
        // interaction. Very old pre-VESSEL_ID items (legacy v0, captured before this field existed)
        // have no id to guard on yet — they proceed unguarded rather than being blocked outright.
        String vesselId = meta.getPersistentDataContainer().get(Keys.VESSEL_ID, PersistentDataType.STRING);
        boolean guarded = vesselId != null && inFlightGuard.tryAcquire(vesselId);
        if (vesselId != null && !guarded) return;
        try {
            releaseLocked(event, player, itemInHand, vesselType, template, meta);
        } finally {
            if (guarded) inFlightGuard.release(vesselId);
        }
    }

    private void releaseLocked(
            PlayerInteractEvent event,
            Player player,
            ItemStack itemInHand,
            String vesselType,
            VesselTemplate template,
            ItemMeta meta) {
        FilterRule worlds = template.restrictions.worlds;
        if (!VesselUtils.isAllowed(player.getWorld().getName(), worlds)) {
            Messages.send(
                    player,
                    config.getMessageConfig().general.cannotReleaseWorld,
                    Messages.tag("world", player.getWorld().getName()));
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) return;

        if (!Bukkit.isOwnedByCurrentRegion(block.getLocation())) {
            Messages.send(player, config.getMessageConfig().general.noSafeReleaseSpace);
            return;
        }

        Location loc = findSafeReleaseLocation(block, event.getBlockFace());
        if (loc == null) {
            Messages.send(player, config.getMessageConfig().general.noSafeReleaseSpace);
            return;
        }

        // Folia: this event already fires on the region owning the player, and the release point is
        // always near them, but region ownership is dynamic (merge/split), so it's not guaranteed to
        // be the same region at this exact moment. Everything below (GriefPrevention's claim lookup,
        // which is not itself Folia-aware, and the entity spawn) needs to run on the thread that
        // actually owns both the clicked block and `loc` — reject cleanly rather than cross regions.
        if (!Bukkit.isOwnedByCurrentRegion(loc)) {
            Log.debug("Release location is not owned by the current region thread; rejecting this release attempt.");
            Messages.send(player, config.getMessageConfig().general.noSafeReleaseSpace);
            return;
        }

        ProtectionResult protection = plugin.getProtectionService().canRelease(player, block.getLocation(), loc);
        if (!protection.allowed()) {
            Messages.send(player, config.getMessageConfig().general.cannotReleaseHere);
            if (protection.denialReason() != null) {
                Messages.send(
                        player,
                        config.getMessageConfig().general.protectionDenialReason,
                        Messages.tag("reason", protection.denialReason()));
            }
            return;
        }

        if (cooldownHandler.isOnCooldown(player.getUniqueId(), config.getMainConfig().cooldown)) {
            Log.debug("Player " + player.getName() + " attempted to release a vessel but is on cooldown.");
            return;
        }

        // Parse (and lazily migrate) before doing anything else: a corrupt/unknown/future-schema
        // payload must reject cleanly here and leave the item exactly as it was.
        ReadResult readResult;
        try {
            readResult = VesselPayloadStore.readAndMigrate(meta.getPersistentDataContainer());
        } catch (VesselDataException e) {
            Log.debug("Failed to read vessel payload: " + e.getReason() + " - " + e.getMessage());
            Messages.send(player, config.getMessageConfig().general.corruptedVesselData);
            return;
        }

        if (readResult.migrated()) {
            try {
                VesselPayloadStore.write(meta.getPersistentDataContainer(), readResult.payload());
                itemInHand.setItemMeta(meta);
                Log.debug("Migrated vessel " + readResult.payload().vesselId() + " to schema v"
                        + readResult.payload().schemaVersion() + ".");
            } catch (VesselDataException e) {
                // Pure reformat of data we just successfully read; failing to persist the upgrade is
                // not fatal to this release, it just means the next read migrates again.
                Log.debug("Failed to persist migrated vessel payload: " + e.getMessage());
            }
        }

        CompoundEntityTree tree;
        try {
            tree = CompoundEntityTree.fromSnbt(readResult.payload().payload());
            tree.populateSnapshots(Bukkit.getEntityFactory());
        } catch (VesselDataException e) {
            Log.debug("Failed to parse compound entity tree: " + e.getMessage());
            Messages.send(player, config.getMessageConfig().general.corruptedVesselData);
            return;
        }

        // Validate release permissions across all entity types in the riding hierarchy
        for (CompoundEntityTree node : tree.flatten()) {
            String nodeMobId = node.getEntityType().toLowerCase(Locale.ROOT);
            Entity tempMob = node.getSnapshot().createEntity(loc.getWorld());

            if (!player.hasPermission("vessel.release.*")
                    && !player.hasPermission("vessel.release." + nodeMobId)
                    && !VesselUtils.hasGroupPermission(player, tempMob, "release")) {
                Messages.send(
                        player,
                        config.getMessageConfig().general.cannotRelease,
                        Messages.tag("entity_type", nodeMobId));
                return;
            }
        }

        String mobId = tree.getEntityType().toLowerCase(Locale.ROOT);
        String savedName = meta.getPersistentDataContainer().get(Keys.MOB_NAME, PersistentDataType.STRING);
        String savedReason = meta.getPersistentDataContainer().get(Keys.SPAWN_REASON, PersistentDataType.STRING);

        VesselReleaseEvent releaseEvent = new VesselReleaseEvent(
                player, tree.getSnapshot(), loc, vesselType, savedName != null ? savedName : mobId, itemInHand);
        plugin.getServer().getPluginManager().callEvent(releaseEvent);

        if (releaseEvent.isCancelled()) return;

        Entity releasedMob = null;
        String mythicId = meta.getPersistentDataContainer().get(Keys.MYTHIC_ID, PersistentDataType.STRING);
        boolean mythic = mythicId != null && !mythicId.isEmpty();

        if (mythic && Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            try {
                releasedMob = MythicHook.spawnMob(mythicId, loc);

                if (releasedMob != null) {
                    if (savedReason != null) {
                        releasedMob
                                .getPersistentDataContainer()
                                .set(
                                        Keys.SPAWN_REASON,
                                        PersistentDataType.STRING,
                                        savedReason.toUpperCase(Locale.ROOT));
                    }
                    releasedMob.getPersistentDataContainer().set(Keys.FROM_VESSEL, PersistentDataType.BOOLEAN, true);
                }
            } catch (Exception e) {
                Log.error(
                        "Failed to spawn MythicMob type '" + mythicId + "', falling back to vanilla compound restore.");
            }
        }

        if (releasedMob == null) {
            org.maboroshi.vessel.storage.entity.CompoundEntityRestorer.RestoreResult restoreResult =
                    org.maboroshi.vessel.storage.entity.CompoundEntityRestorer.restore(
                            tree, loc, CreatureSpawnEvent.SpawnReason.CUSTOM, savedReason);
            if (!restoreResult.success()) {
                Log.warn("Failed to restore compound entity for " + player.getName() + " (vessel " + vesselIdOf(meta)
                        + ", " + mobId + " at " + loc.getWorld().getName() + " " + loc.getBlockX() + ","
                        + loc.getBlockY() + "," + loc.getBlockZ() + "); vessel kept: " + restoreResult.errorMessage());
                Messages.send(
                        player, config.getMessageConfig().general.releaseFailed, Messages.tag("entity_type", mobId));
                return;
            }
            releasedMob = restoreResult.rootEntity();
        }

        if (releasedMob == null) {
            Log.error("Failed to spawn entity tree from snapshot during release.");
            Messages.send(player, config.getMessageConfig().general.noSafeReleaseSpace);
            return;
        }

        VesselTemplate.BehaviorSettings behavior = template.behavior;

        if (behavior.consumeOnRelease) {
            if (behavior.returnEmptyVessel) {
                ItemStack cleanedVessel = plugin.getVesselManager().createEmptyVessel(vesselType);
                if (cleanedVessel != null) {
                    if (itemInHand.getAmount() > 1) {
                        itemInHand.subtract();
                        player.getInventory()
                                .addItem(cleanedVessel)
                                .values()
                                .forEach(leftover ->
                                        player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                    } else {
                        player.getInventory().setItemInMainHand(cleanedVessel);
                    }
                }
            } else {
                itemInHand.subtract();
            }
        }

        cooldownHandler.setCooldown(player.getUniqueId());
    }

    private static String vesselIdOf(ItemMeta meta) {
        String id = meta.getPersistentDataContainer().get(Keys.VESSEL_ID, PersistentDataType.STRING);
        return id != null ? id : "legacy";
    }

    private Location findSafeReleaseLocation(Block block, BlockFace face) {
        Block relativeBlock = block.getRelative(face);
        Location base = relativeBlock.getLocation().add(0.5, 0, 0.5);

        if (isSafeReleaseSpace(base)) return base;

        for (int yOffset = 0; yOffset <= 2; yOffset++) {
            for (int xOffset = -1; xOffset <= 1; xOffset++) {
                for (int zOffset = -1; zOffset <= 1; zOffset++) {
                    Location candidate = base.clone().add(xOffset, yOffset, zOffset);
                    if (isSafeReleaseSpace(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        for (int yOffset = 3; yOffset <= 5; yOffset++) {
            Location candidate = base.clone().add(0, yOffset, 0);
            if (isSafeReleaseSpace(candidate)) return candidate;
        }
        return null;
    }

    private boolean isSafeReleaseSpace(Location location) {
        // Check ownership before touching candidate blocks, including across a region boundary.
        return Bukkit.isOwnedByCurrentRegion(location)
                && location.getBlock().isPassable()
                && location.clone().add(0, 1, 0).getBlock().isPassable();
    }
}
