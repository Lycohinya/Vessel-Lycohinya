package org.maboroshi.vessel.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.maboroshi.vessel.Vessel;
import org.maboroshi.vessel.storage.entity.RestoreTracker;
import org.maboroshi.vessel.util.Keys;

public class SpawnReasonListener implements Listener {
    public SpawnReasonListener(Vessel plugin) {}

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // A Vessel release is not a new spawn: the creature keeps the original source its snapshot
        // carried, which CompoundEntityRestorer re-applies once the entity is in the world.
        if (RestoreTracker.isRestoring(event.getEntity())) return;
        event.getEntity()
                .getPersistentDataContainer()
                .set(
                        Keys.SPAWN_REASON,
                        org.bukkit.persistence.PersistentDataType.STRING,
                        event.getSpawnReason().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreatureSpawnResult(CreatureSpawnEvent event) {
        RestoreTracker.observe(event);
    }
}
