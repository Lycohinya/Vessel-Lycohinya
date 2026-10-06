package org.maboroshi.vessel.storage.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredListener;
import org.maboroshi.vessel.util.Keys;
import org.maboroshi.vessel.util.Log;

/**
 * Handles the transactional, Folia-safe restoration of compound entity trees.
 *
 * <p>Spawns each entity node individually at {@code loc} within the owning Region thread
 * (ensuring no entity is ever spawned with snapshot coordinates in a foreign region),
 * and only establishes {@code addPassenger} relationships after all nodes have successfully spawned.
 * If any node fails to spawn, or does not survive the add-event chain, every entity added by this
 * operation is removed again so the caller can keep the vessel untouched.
 *
 * <p>The spawn event fired for a release carries the caller's release reason, not the creature's
 * original one: re-firing e.g. NATURAL or BREEDING made other plugins treat a release as a natural
 * spawn and cancel it. Each node's original source is the {@code vessel:spawn_reason} value its own
 * snapshot carried, and that value (not the release event's) is what the restored entity keeps.
 */
public final class CompoundEntityRestorer {
    private CompoundEntityRestorer() {}

    public record RestoreResult(
            Entity rootEntity, List<Entity> allSpawnedEntities, boolean success, String errorMessage) {}

    private record NodeFailure(String path, String entityType, String stage, String detail) {
        String describe() {
            return "node " + path + " (" + entityType + ") failed at " + stage + ": " + detail;
        }
    }

    /**
     * Atomically restores a compound entity tree at {@code loc}.
     *
     * @param tree the decomposed entity tree
     * @param loc the target release location (must be owned by the current region)
     * @param releaseReason spawn reason of the release event itself
     * @param rootFallbackReason original spawn reason recorded on the vessel item, used only for the
     *     root node when its own snapshot carries none; null when the item has none either
     * @return the result of the restoration operation
     */
    public static RestoreResult restore(
            CompoundEntityTree tree,
            Location loc,
            CreatureSpawnEvent.SpawnReason releaseReason,
            String rootFallbackReason) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(loc, "loc");

        List<Entity> spawned = new ArrayList<>();
        try {
            NodeFailure failure = spawnNode(tree, "0", loc, releaseReason, rootFallbackReason, spawned);
            if (failure == null) failure = attachPassengers(tree, "0");
            if (failure == null) failure = verifyAlive(tree, "0");
            if (failure != null) {
                rollback(spawned);
                return new RestoreResult(null, Collections.emptyList(), false, failure.describe());
            }
            return new RestoreResult(tree.getSpawnedEntity(), Collections.unmodifiableList(spawned), true, null);
        } catch (Exception e) {
            rollback(spawned);
            return new RestoreResult(
                    null,
                    Collections.emptyList(),
                    false,
                    "exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static NodeFailure spawnNode(
            CompoundEntityTree node,
            String path,
            Location loc,
            CreatureSpawnEvent.SpawnReason releaseReason,
            String rootFallbackReason,
            List<Entity> spawned) {
        EntitySnapshot snapshot = node.getSnapshot();
        if (snapshot == null) {
            snapshot = Bukkit.getEntityFactory().createEntitySnapshot(node.getSnbtPayload());
            node.setSnapshot(snapshot);
        }

        Entity entity;
        try {
            entity = snapshot.createEntity(loc.getWorld());
        } catch (RuntimeException e) {
            return new NodeFailure(path, node.getEntityType(), "instantiate", e.getMessage());
        }
        if (entity == null) {
            return new NodeFailure(path, node.getEntityType(), "instantiate", "createEntity returned null");
        }

        // The snapshot's own PersistentDataContainer is loaded before the entity enters the world.
        String originalReason = entity.getPersistentDataContainer().get(Keys.SPAWN_REASON, PersistentDataType.STRING);
        boolean validBefore = entity.isValid();

        RestoreTracker.Observation observation = RestoreTracker.begin(entity);
        boolean added;
        try {
            added = entity.spawnAt(loc, releaseReason);
        } catch (RuntimeException e) {
            // The entity can already be in the world when something later in the add chain throws.
            if (entity.isValid()) spawned.add(entity);
            return new NodeFailure(
                    path,
                    node.getEntityType(),
                    "spawnAt",
                    "exception " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (validAfter="
                            + entity.isValid() + ", spawnEvent=" + describeEvent(observation) + ")");
        } finally {
            RestoreTracker.end(observation);
        }

        if (!added) {
            // spawnAt=false is never treated as success; if the core left it in the world anyway,
            // make sure the rollback takes it out again.
            if (entity.isValid()) spawned.add(entity);
            return new NodeFailure(
                    path,
                    node.getEntityType(),
                    "spawnAt",
                    "spawnAt returned false (validBefore=" + validBefore + ", validAfter=" + entity.isValid()
                            + ", inWorld=" + entity.isInWorld() + ", dead=" + entity.isDead()
                            + ", spawnEvent=" + describeEvent(observation) + ", releaseReason=" + releaseReason
                            + ")");
        }

        spawned.add(entity);
        node.setSpawnedEntity(entity);

        if (!entity.isValid() || entity.isDead()) {
            return new NodeFailure(
                    path,
                    node.getEntityType(),
                    "post-spawn",
                    "entity was removed while being added (inWorld=" + entity.isInWorld() + ", spawnEvent="
                            + describeEvent(observation) + ")");
        }

        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(Keys.FROM_VESSEL, PersistentDataType.BOOLEAN, true);
        String restoredReason = originalReason;
        if ((restoredReason == null || restoredReason.isBlank()) && "0".equals(path)) {
            restoredReason = rootFallbackReason;
        }
        if (restoredReason != null && !restoredReason.isBlank()) {
            pdc.set(Keys.SPAWN_REASON, PersistentDataType.STRING, restoredReason.toUpperCase(Locale.ROOT));
        } else {
            // No trustworthy original source anywhere: leave it unrecorded instead of guessing one.
            pdc.remove(Keys.SPAWN_REASON);
            Log.info("Released " + node.getEntityType() + " (node " + path
                    + ") has no recorded original spawn reason; left unrecorded.");
        }

        List<CompoundEntityTree> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            NodeFailure failure =
                    spawnNode(children.get(i), path + "." + i, loc, releaseReason, rootFallbackReason, spawned);
            if (failure != null) return failure;
        }
        return null;
    }

    private static NodeFailure attachPassengers(CompoundEntityTree node, String path) {
        Entity parent = node.getSpawnedEntity();
        List<CompoundEntityTree> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            CompoundEntityTree child = children.get(i);
            String childPath = path + "." + i;
            Entity childEntity = child.getSpawnedEntity();
            if (parent == null || childEntity == null || !parent.addPassenger(childEntity)) {
                return new NodeFailure(
                        childPath, child.getEntityType(), "attach", "could not mount onto " + node.getEntityType());
            }
            NodeFailure failure = attachPassengers(child, childPath);
            if (failure != null) return failure;
        }
        return null;
    }

    private static NodeFailure verifyAlive(CompoundEntityTree node, String path) {
        Entity entity = node.getSpawnedEntity();
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return new NodeFailure(path, node.getEntityType(), "verify", "entity no longer in the world");
        }
        List<CompoundEntityTree> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            NodeFailure failure = verifyAlive(children.get(i), path + "." + i);
            if (failure != null) return failure;
        }
        return null;
    }

    private static String describeEvent(RestoreTracker.Observation observation) {
        if (!observation.eventSeen()) return "not-fired";
        if (!observation.eventCancelled()) return "allowed";
        return "cancelled (CreatureSpawnEvent listeners: " + spawnListenerPlugins() + ")";
    }

    private static String spawnListenerPlugins() {
        TreeSet<String> names = new TreeSet<>();
        for (RegisteredListener listener : CreatureSpawnEvent.getHandlerList().getRegisteredListeners()) {
            names.add(listener.getPlugin().getName());
        }
        return String.join(", ", names);
    }

    private static void rollback(List<Entity> spawned) {
        for (int i = spawned.size() - 1; i >= 0; i--) {
            Entity entity = spawned.get(i);
            try {
                if (entity == null) continue;
                for (Entity passenger : new ArrayList<>(entity.getPassengers())) {
                    entity.removePassenger(passenger);
                }
                if (entity.isInsideVehicle()) {
                    entity.leaveVehicle();
                }
                // Not gated on isValid(): anything this restore added must leave again, otherwise a
                // kept vessel plus a left-behind creature is a duplicate.
                entity.remove();
            } catch (Exception e) {
                Log.error("Error rolling back entity during failed restore: " + e.getMessage());
            }
        }
    }
}
