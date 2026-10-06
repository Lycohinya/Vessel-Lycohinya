package org.maboroshi.vessel.storage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.maboroshi.vessel.storage.VesselDataException.Reason;

/**
 * The single point of contact with Paper's entity-serialization APIs.
 *
 * <p>{@link EntitySnapshot#getAsString()} and {@link org.bukkit.entity.EntityFactory} are marked
 * {@code @Experimental} by Paper, and the underlying NBT string is explicitly documented as "should
 * not be relied upon as a serializable value" — Paper does not run its DataFixer over this payload
 * on load the way it does for items, and the string carries no embedded Minecraft DataVersion.
 * Vessel's own {@link VesselPayload} envelope exists specifically to compensate for that: it stamps
 * a DataVersion and schema version at capture time so a future migrator has something to key off of.
 *
 * <p>All calls into Paper's Experimental entity-snapshot surface are confined to this class so a
 * future API change only needs to be absorbed here, not chased through every listener.
 */
public final class EntitySnapshotAdapter {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String CODEC_ID = "paper_entity_snapshot_v1";

    /**
     * Vanilla NBT strings are written via {@code DataOutput#writeUTF}, which hard-caps modified-UTF-8
     * encoded length at 65,535 bytes and silently saves an empty string past that limit instead of
     * failing loudly. This threshold leaves headroom below that cap; entities that serialize larger
     * than this must be rejected at capture time rather than risk silent data loss on the next save.
     */
    private static final int MAX_PAYLOAD_BYTES = 60_000;

    private EntitySnapshotAdapter() {}

    /** The result of capturing an entity: the storable envelope plus the snapshot taken from it. */
    public record CaptureResult(VesselPayload payload, EntitySnapshot snapshot) {}

    /** Captures {@code entity} into a fresh, current-schema {@link VesselPayload}. */
    public static CaptureResult capture(Entity entity, String vesselId) throws VesselDataException {
        // No fallback to a freshly created default entity of the same type: that would store a
        // different creature (no trades, name, equipment, PDC) under this one's vessel.
        EntitySnapshot snapshot = entity.createSnapshot();
        if (snapshot == null) {
            throw new VesselDataException(
                    Reason.MALFORMED, "Failed to create an EntitySnapshot for entity type " + entity.getType());
        }

        String payload = snapshot.getAsString();
        validateSize(payload, entity.getType().name());

        VesselPayload vesselPayload = new VesselPayload(
                CURRENT_SCHEMA_VERSION,
                dataVersion(),
                CODEC_ID,
                snapshot.getEntityType().name(),
                payload,
                ChecksumUtil.compute(payload),
                vesselId);
        return new CaptureResult(vesselPayload, snapshot);
    }

    /** Parses a current-schema payload's entity data back into an {@link EntitySnapshot}. */
    public static EntitySnapshot toSnapshot(VesselPayload payload) throws VesselDataException {
        if (!CODEC_ID.equals(payload.codecId())) {
            throw new VesselDataException(Reason.MALFORMED, "Unknown codec id: " + payload.codecId());
        }
        try {
            return Bukkit.getServer().getEntityFactory().createEntitySnapshot(payload.payload());
        } catch (RuntimeException e) {
            throw new VesselDataException(Reason.MALFORMED, "Failed to parse stored entity data", e);
        }
    }

    /** Parses a legacy v0 raw {@code EntitySnapshot#getAsString()} value (no envelope). */
    public static EntitySnapshot legacySnapshot(String rawPayload) throws VesselDataException {
        try {
            return Bukkit.getServer().getEntityFactory().createEntitySnapshot(rawPayload);
        } catch (RuntimeException e) {
            throw new VesselDataException(Reason.MALFORMED, "Failed to parse legacy v0 entity data", e);
        }
    }

    /**
     * Rejects payloads that would silently truncate to an empty string on Paper's next NBT save
     * (see {@link #MAX_PAYLOAD_BYTES}). Also used to re-validate payloads coming out of a migrator,
     * since a future schema's transform could in principle grow the payload.
     */
    static void validateSize(String payload, String entityTypeLabel) throws VesselDataException {
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new VesselDataException(
                    Reason.TOO_LARGE,
                    "Serialized entity data for " + entityTypeLabel + " exceeds the safe PDC string size");
        }
    }

    private static int dataVersion() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (RuntimeException e) {
            return VesselPayload.UNKNOWN_DATA_VERSION;
        }
    }
}
