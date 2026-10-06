package org.maboroshi.vessel.storage.entity;

import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Marks the entity Vessel is currently adding to the world, so spawn listeners can tell a release
 * apart from every other spawn and the restorer can see what the add-event chain did to it.
 *
 * <p>Restores run synchronously on the region thread that owns the release point, and
 * {@link Entity#spawnAt} fires its spawn event on that same thread before returning, so a
 * thread-local is enough to correlate the two.
 */
public final class RestoreTracker {
    private static final ThreadLocal<Observation> CURRENT = new ThreadLocal<>();

    private RestoreTracker() {}

    /** What the spawn event chain reported for one {@code spawnAt} call. */
    public static final class Observation {
        private final UUID entityId;
        private final Observation previous;
        private boolean eventSeen;
        private boolean eventCancelled;

        private Observation(UUID entityId, Observation previous) {
            this.entityId = entityId;
            this.previous = previous;
        }

        public boolean eventSeen() {
            return eventSeen;
        }

        public boolean eventCancelled() {
            return eventCancelled;
        }
    }

    static Observation begin(Entity entity) {
        Observation observation = new Observation(entity.getUniqueId(), CURRENT.get());
        CURRENT.set(observation);
        return observation;
    }

    /** Ends {@code observation}, restoring whichever restore (if any) it was nested inside. */
    static void end(Observation observation) {
        if (observation.previous != null) {
            CURRENT.set(observation.previous);
        } else {
            CURRENT.remove();
        }
    }

    /** True while {@code entity} is the one Vessel is restoring on this thread. */
    public static boolean isRestoring(Entity entity) {
        Observation observation = CURRENT.get();
        return observation != null && observation.entityId.equals(entity.getUniqueId());
    }

    /** Called from a MONITOR listener: records the final cancelled state of our own spawn event. */
    public static void observe(CreatureSpawnEvent event) {
        Observation observation = CURRENT.get();
        if (observation == null
                || !observation.entityId.equals(event.getEntity().getUniqueId())) return;
        observation.eventSeen = true;
        observation.eventCancelled = event.isCancelled();
    }
}
