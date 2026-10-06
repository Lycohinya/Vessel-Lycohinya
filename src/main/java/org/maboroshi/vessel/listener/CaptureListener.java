package org.maboroshi.vessel.listener;

import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.maboroshi.vessel.Vessel;
import org.maboroshi.vessel.api.event.VesselCaptureEvent;
import org.maboroshi.vessel.config.ConfigManager;
import org.maboroshi.vessel.config.objects.FilterRule;
import org.maboroshi.vessel.config.settings.VesselTemplate;
import org.maboroshi.vessel.config.settings.VesselTemplate.ExclusionSettings;
import org.maboroshi.vessel.manager.InFlightGuard;
import org.maboroshi.vessel.protection.ProtectionResult;
import org.maboroshi.vessel.storage.EntitySnapshotAdapter;
import org.maboroshi.vessel.storage.EntitySnapshotAdapter.CaptureResult;
import org.maboroshi.vessel.storage.VesselDataException;
import org.maboroshi.vessel.storage.VesselPayloadStore;
import org.maboroshi.vessel.util.Keys;
import org.maboroshi.vessel.util.Log;
import org.maboroshi.vessel.util.Messages;
import org.maboroshi.vessel.util.MythicHook;
import org.maboroshi.vessel.util.VesselUtils;

public class CaptureListener implements Listener {
    private final Vessel plugin;
    private final ConfigManager config;
    private final InFlightGuard inFlightGuard;

    private static final MiniMessage mm = MiniMessage.miniMessage();

    public CaptureListener(Vessel plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.inFlightGuard = plugin.getInFlightGuard();
    }

    @EventHandler
    public void onCapture(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (!itemInHand.hasItemMeta()) return;

        String vesselType = VesselUtils.getTemplateId(itemInHand);
        if (vesselType == null) return;

        event.setCancelled(true);

        ItemMeta meta = itemInHand.getItemMeta();
        if (meta.getPersistentDataContainer().has(Keys.MOB_DATA, PersistentDataType.STRING)) return;

        Entity target = event.getRightClicked();
        if (!(target instanceof Mob clickedMob)) return;

        // Guards against the same target entity being captured twice from a concurrent/duplicate
        // interaction; released in the finally block below regardless of how this method returns.
        String captureGuardKey = clickedMob.getUniqueId().toString();
        if (!inFlightGuard.tryAcquire(captureGuardKey)) return;
        try {
            captureLocked(player, itemInHand, vesselType, clickedMob);
        } finally {
            inFlightGuard.release(captureGuardKey);
        }
    }

    private void captureLocked(Player player, ItemStack itemInHand, String vesselType, Mob clickedMob) {
        VesselTemplate template = config.getVesselTemplate(vesselType);
        if (template == null) return;

        if (!player.hasPermission("vessel.use." + vesselType.toLowerCase(Locale.ROOT))) {
            Messages.send(player, config.getMessageConfig().general.cannotUseVessel);
            return;
        }

        VesselTemplate.RestrictionSettings restrictions = template.restrictions;

        FilterRule worlds = restrictions.worlds;
        if (!VesselUtils.isAllowed(player.getWorld().getName(), worlds)) {
            Messages.send(
                    player,
                    config.getMessageConfig().general.cannotCaptureWorld,
                    Messages.tag("world", player.getWorld().getName()));
            return;
        }

        Location loc = clickedMob.getLocation();

        ProtectionResult protection = plugin.getProtectionService().canCapture(player, loc);
        if (!protection.allowed()) {
            Messages.send(player, config.getMessageConfig().general.cannotCaptureHere);
            if (protection.denialReason() != null) {
                Messages.send(
                        player,
                        config.getMessageConfig().general.protectionDenialReason,
                        Messages.tag("reason", protection.denialReason()));
            }
            return;
        }

        // Collect all entities in the riding subtree rooted at clickedMob
        java.util.List<Entity> entityTree = new java.util.ArrayList<>();
        collectEntityTree(clickedMob, entityTree);

        // Disallow capture if any passenger is a player
        for (Entity entity : entityTree) {
            if (entity instanceof Player) {
                Messages.send(player, config.getMessageConfig().general.cannotCaptureRiddenByPlayer);
                return;
            }
        }

        ExclusionSettings rules = restrictions.exclusions;
        FilterRule mobs = restrictions.entities;

        // Validate capture permissions and restrictions for all entities in the tree
        for (Entity entity : entityTree) {
            String mobId = entity.getType().name().toLowerCase(Locale.ROOT);
            String rawMobName = entity.getName() != null ? entity.getName() : mobId;
            String safeMobName =
                    mm.serialize(LegacyComponentSerializer.legacySection().deserialize(rawMobName));

            if (entity.getPersistentDataContainer().has(Keys.SPAWN_REASON, PersistentDataType.STRING)) {
                String reason = entity.getPersistentDataContainer().get(Keys.SPAWN_REASON, PersistentDataType.STRING);
                if (!VesselUtils.isAllowed(reason, rules.spawnReasons)) {
                    Messages.send(
                            player,
                            config.getMessageConfig().general.blacklistedEntity,
                            Messages.tag("entity_type", mobId),
                            Messages.tag("spawn_reason", reason),
                            Messages.tagParsed("entity_name", safeMobName));
                    Log.info("Capture denied by spawn-reason filter: " + player.getName() + " -> " + mobId + " "
                            + entity.getUniqueId() + " (vessel:spawn_reason=" + reason + ", from_vessel="
                            + entity.getPersistentDataContainer().has(Keys.FROM_VESSEL) + ", bukkit_reason="
                            + entity.getEntitySpawnReason() + ", template=" + vesselType + ")");
                    return;
                }
            }

            if (entity instanceof Tameable pet && pet.isTamed()) {
                UUID owner = pet.getOwnerUniqueId();
                if (owner != null) {
                    if (owner.equals(player.getUniqueId())) {
                        if (rules.tamed) {
                            Messages.send(player, config.getMessageConfig().general.cannotCaptureTamed);
                            return;
                        }
                    } else {
                        if (rules.othersTamed) {
                            Messages.send(player, config.getMessageConfig().general.cannotCaptureOthersTamed);
                            return;
                        }
                    }
                }
            }

            if (rules.named && entity.customName() != null) {
                Messages.send(
                        player,
                        config.getMessageConfig().general.cannotCaptureNamed,
                        Messages.tag("entity_type", mobId));
                return;
            }

            if (!VesselUtils.isAllowed(mobId, mobs)) {
                Log.info("Capture denied by entity filter: " + player.getName() + " -> " + mobId + " "
                        + entity.getUniqueId() + " (template=" + vesselType + ")");
                Messages.send(
                        player,
                        config.getMessageConfig().general.blacklistedEntity,
                        Messages.tag("entity_type", mobId),
                        Messages.tagParsed("entity_name", safeMobName));
                return;
            }

            if (!player.hasPermission("vessel.capture.*")
                    && !player.hasPermission("vessel.capture." + mobId)
                    && !VesselUtils.hasGroupPermission(player, entity, "capture")) {
                Messages.send(
                        player, config.getMessageConfig().general.cannotCapture, Messages.tag("entity_type", mobId));
                return;
            }
        }

        if (plugin.getCooldownHandler().isOnCooldown(player.getUniqueId(), config.getMainConfig().cooldown)) return;

        String rawTargetName = clickedMob.getName() != null
                ? clickedMob.getName()
                : clickedMob.getType().name();
        String mythicId = null;

        if (Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            mythicId = MythicHook.getInternalName(clickedMob);
            if (mythicId != null) {
                rawTargetName = MythicHook.getDisplayName(clickedMob);
            }
        }

        String targetName =
                mm.serialize(LegacyComponentSerializer.legacySection().deserialize(rawTargetName));

        // Serialize first: build and validate the fully-formed result item before touching the mob
        // or the vessel item in hand, so a failure here (e.g. entity too complex to store safely)
        // never consumes the vessel or removes the entity. The stored copy gets the cleaned custom
        // name; the live mob gets its original name back right after the snapshot, so a capture that
        // fails or is cancelled leaves it exactly as it was.
        String newVesselId = UUID.randomUUID().toString();
        CaptureResult captureResult;
        Component customNameComponent = clickedMob.customName();
        try {
            if (customNameComponent != null) {
                String legacyRepresentation =
                        LegacyComponentSerializer.legacySection().serialize(customNameComponent);
                clickedMob.customName(LegacyComponentSerializer.legacySection().deserialize(legacyRepresentation));
            }
            captureResult = EntitySnapshotAdapter.capture(clickedMob, newVesselId);
        } catch (VesselDataException e) {
            Log.info("Capture failed for " + player.getName() + " -> " + clickedMob.getType() + " "
                    + clickedMob.getUniqueId() + ": " + e.getReason() + " - " + e.getMessage());
            Messages.send(player, config.getMessageConfig().general.cannotCaptureTooComplex);
            return;
        } finally {
            if (customNameComponent != null) clickedMob.customName(customNameComponent);
        }

        ItemStack resultItem = plugin.getVesselManager().createFilledVessel(vesselType, clickedMob, targetName);
        if (resultItem == null) return;

        ItemMeta resultMeta = resultItem.getItemMeta();
        if (resultMeta == null) return;

        try {
            VesselPayloadStore.write(resultMeta.getPersistentDataContainer(), captureResult.payload());
        } catch (VesselDataException e) {
            Log.debug("Failed to persist capture payload for entity type " + clickedMob.getType() + ": "
                    + e.getMessage());
            Messages.send(player, config.getMessageConfig().general.cannotCaptureTooComplex);
            return;
        }
        resultMeta.getPersistentDataContainer().set(Keys.MOB_NAME, PersistentDataType.STRING, targetName);

        if (mythicId != null)
            resultMeta.getPersistentDataContainer().set(Keys.MYTHIC_ID, PersistentDataType.STRING, mythicId);

        String spawnReason = clickedMob.getPersistentDataContainer().get(Keys.SPAWN_REASON, PersistentDataType.STRING);
        // Bukkit's own spawn reason is only a source for creatures Vessel never released: on a released
        // one it is the release event's CUSTOM, not where the creature originally came from.
        if ((spawnReason == null || spawnReason.isEmpty())
                && !clickedMob.getPersistentDataContainer().has(Keys.FROM_VESSEL))
            spawnReason = clickedMob.getEntitySpawnReason().name();
        if (spawnReason != null && !spawnReason.isEmpty())
            resultMeta.getPersistentDataContainer().set(Keys.SPAWN_REASON, PersistentDataType.STRING, spawnReason);
        resultItem.setItemMeta(resultMeta);

        VesselCaptureEvent captureEvent =
                new VesselCaptureEvent(player, captureResult.snapshot(), loc, vesselType, targetName, resultItem);
        plugin.getServer().getPluginManager().callEvent(captureEvent);

        if (captureEvent.isCancelled()) return;

        // If clickedMob is a passenger inside a vehicle, dismount it so the vehicle remains untouched in the world
        if (clickedMob.isInsideVehicle()) {
            clickedMob.leaveVehicle();
        }

        itemInHand.subtract();

        player.getInventory()
                .addItem(resultItem)
                .values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));

        // Cleanly remove all captured entities in the subtree from the world
        for (Entity entity : entityTree) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
        plugin.getCooldownHandler().setCooldown(player.getUniqueId());
    }

    private void collectEntityTree(Entity root, java.util.List<Entity> list) {
        if (root == null) return;
        list.add(root);
        for (Entity passenger : root.getPassengers()) {
            collectEntityTree(passenger, list);
        }
    }
}
