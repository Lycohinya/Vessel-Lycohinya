package org.maboroshi.vessel.config.settings;

import de.exlll.configlib.Comment;
import de.exlll.configlib.ConfigLib;
import de.exlll.configlib.Configuration;
import de.exlll.configlib.NameFormatters;
import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurations;
import java.io.File;
import java.nio.file.Path;

public class MessageConfig {

    public static MessageConfiguration load(File dataFolder) {
        YamlConfigurationProperties properties = ConfigLib.BUKKIT_DEFAULT_PROPERTIES.toBuilder()
                .setNameFormatter(NameFormatters.LOWER_KEBAB_CASE)
                .build();
        Path messagesFile = new File(dataFolder, "messages.yml").toPath();
        return YamlConfigurations.update(messagesFile, MessageConfiguration.class, properties);
    }

    @Configuration
    public static class MessageConfiguration {
        @Comment("The global prefix used in messages. Use <prefix> in other messages to include it.")
        public String prefix = "<color:#F2CDCD><bold>Vessel</bold> ➟</color>";

        @Comment("Core gameplay messages for capturing and releasing entities.")
        public GeneralMessages general = new GeneralMessages();

        @Comment("Command-related messages and responses.")
        public CommandMessages commands = new CommandMessages();

        @Comment("Help command messages and entry format.")
        public HelpMessages help = new HelpMessages();

        @Configuration
        public static class GeneralMessages {
            @Comment("Message shown when a player lacks permission to use a vessel entirely.")
            public String cannotUseVessel = "<prefix> You cannot use this vessel!";

            @Comment("Message shown when a player lacks permission node access to capture a specific entity class.")
            public String cannotCapture = "<prefix> You cannot capture <gray><entity_type></gray>!";

            @Comment("Message shown when a player lacks permission node access to release a specific entity class.")
            public String cannotRelease = "<prefix> You cannot release <gray><entity_type></gray>!";

            @Comment("Message shown when a player tries to capture their own tamed pet while disallowed.")
            public String cannotCaptureTamed = "<prefix> You cannot capture your own pet!";

            @Comment("Message shown when a player tries to capture another player's tamed pet while disallowed.")
            public String cannotCaptureOthersTamed = "<prefix> You cannot capture someone else's pet!";

            @Comment("Message shown when a player tries to capture a named mob while named mobs are excluded.")
            public String cannotCaptureNamed = "<prefix> You cannot capture this named mob!";

            @Comment("Message shown when a player tries to capture an entity that is being ridden by a player.")
            public String cannotCaptureRiddenByPlayer =
                    "<prefix> You cannot capture an entity while a player is riding it!";

            @Comment(
                    "Message shown when a player attempts to use the vessel on a blacklisted entity type or restricted spawn reason.")
            public String blacklistedEntity = "<prefix> You cannot use the vessel on <gray><entity_type></gray>!";

            @Comment("Message shown when a player cannot capture in the current protected area.")
            public String cannotCaptureHere = "<prefix> You cannot capture mobs here!";

            @Comment("Message shown when a player cannot capture in the current world.")
            public String cannotCaptureWorld = "<prefix> You cannot capture mobs in <gray><world></gray>!";

            @Comment("Message shown when a player cannot release in the current protected area.")
            public String cannotReleaseHere = "<prefix> You cannot release mobs here!";

            @Comment("Message shown when a player cannot release in the current world.")
            public String cannotReleaseWorld = "<prefix> You cannot release mobs in <gray><world></gray>!";

            @Comment(
                    "Message shown when no safe space could be found to release into (also shown on Folia/region-boundary edge cases where the release point isn't safely reachable from this interaction).")
            public String noSafeReleaseSpace = "<prefix> There is no safe space to release this vessel.";

            @Comment(
                    "Message shown when the server refused to add the stored creature to the world (for example another plugin cancelled the spawn). The vessel is kept unchanged.")
            public String releaseFailed =
                    "<prefix> The <gray><entity_type></gray> could not be released here. Your vessel was kept.";

            @Comment(
                    "Follow-up line shown after a capture/release denial when the protection plugin (e.g. GriefPrevention) provided a specific reason. Supports <reason>.")
            public String protectionDenialReason = "<prefix> <gray><reason></gray>";

            @Comment("Message shown when an entity's serialized data is too large to safely store.")
            public String cannotCaptureTooComplex = "<prefix> This creature is too complex to be captured!";

            @Comment(
                    "Message shown when a vessel's stored entity data is corrupted, unreadable, or from a future/unknown schema version. The original item is left untouched.")
            public String corruptedVesselData =
                    "<prefix> <red>This vessel's data is corrupted and cannot be used.</red>";
        }

        @Configuration
        public static class CommandMessages {
            @Comment("Message shown when reload succeeds.")
            public String reloadSuccess = "<prefix> <green>Vessel configuration reloaded.</green>";

            @Comment("Message shown when reload fails.")
            public String reloadFail = "<prefix> <red>Failed to reload configuration: <gray><error></gray></red>";

            @Comment("Invalid type message")
            public String invalidType =
                    "<prefix> Invalid vessel type or module disabled! Valid types: <gray>consumable, reusable</gray>.";

            @Comment("Invalid amount message. Supports <min> and <max> tags.")
            public String invalidAmount = "<prefix> Amount must be between <gray><min></gray> and <gray><max></gray>.";

            @Comment("Message sent to command sender when giving vessels. Supports <target>, <amount>, <type> tags.")
            public String giveSender = "<prefix> <green>Gave <target> <amount> <type> vessel(s).</green>";

            @Comment("Message sent to player when receiving vessels. Supports <amount>, <type> tags.")
            public String givePlayer = "<prefix> <green>You received <amount> <type> vessel(s).</green>";
        }

        @Configuration
        public static class HelpMessages {
            @Comment("Header for the help command")
            public String header = "<prefix> Help Menu";

            @Comment("Show usage for plugin about/info command")
            public String about = "<prefix> /vessel about <gray>- Shows plugin about information</gray>";

            @Comment("Show usage for vessel help command")
            public String help = "<prefix> /vessel help <gray>- Shows this help menu</gray>";

            @Comment("Show usage for vessel give command")
            public String give = "<prefix> /vessel give <player> <type> <amount> [-s] <gray>- Give vessels</gray>";

            @Comment("Show usage for vessel reload command")
            public String reload = "<prefix> /vessel reload <gray>- Reloads plugin config</gray>";
        }
    }
}
