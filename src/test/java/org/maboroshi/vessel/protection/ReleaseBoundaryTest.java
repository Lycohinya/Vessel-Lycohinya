package org.maboroshi.vessel.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ReleaseBoundaryTest {
    @Test
    void bothInteractionOriginAndSafeDestinationNeedPermission() {
        Location protectedBlock = new Location(null, 0, 64, 0);
        Location outsideClaim = new Location(null, 1, 65, 0);
        ProtectionAdapter claims = new ProtectionAdapter() {
            @Override
            public ProtectionResult canCapture(Player player, Location location) {
                throw new AssertionError("Release must use release permissions");
            }

            @Override
            public ProtectionResult canRelease(Player player, Location location) {
                return location == protectedBlock
                        ? ProtectionResult.denied("build trust required")
                        : ProtectionResult.ALLOWED;
            }

            @Override
            public String getName() {
                return "claim fixture";
            }
        };
        ProtectionService service = new ProtectionService(List.of(claims));
        // A safe location outside the claim must not authorize clicking its protected wall.
        assertFalse(service.canRelease(null, protectedBlock, outsideClaim).allowed());
        assertFalse(service.canRelease(null, outsideClaim, protectedBlock).allowed());
        assertTrue(service.canRelease(null, outsideClaim, outsideClaim).allowed());

        // An owner/trusted result remains usable at either location; no blanket world/claim ban.
        ProtectionService trusted = new ProtectionService(List.of(new ProtectionAdapter() {
            @Override
            public ProtectionResult canCapture(Player player, Location location) {
                return ProtectionResult.ALLOWED;
            }

            @Override
            public ProtectionResult canRelease(Player player, Location location) {
                return ProtectionResult.ALLOWED;
            }

            @Override
            public String getName() {
                return "trusted claim fixture";
            }
        }));
        assertTrue(trusted.canRelease(null, protectedBlock, outsideClaim).allowed());
        assertTrue(trusted.canRelease(null, outsideClaim, protectedBlock).allowed());
    }
}
