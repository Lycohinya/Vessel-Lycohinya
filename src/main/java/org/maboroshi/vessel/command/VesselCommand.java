package org.maboroshi.vessel.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Flag;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.maboroshi.vessel.Vessel;
import org.maboroshi.vessel.config.ConfigManager;
import org.maboroshi.vessel.util.Log;
import org.maboroshi.vessel.util.Messages;

public class VesselCommand {

    private final Vessel plugin;

    public VesselCommand(Vessel plugin) {
        this.plugin = plugin;
    }

    @Suggestions("vesselTypes")
    public List<String> suggestVesselTypes(CommandContext<CommandSourceStack> context, String input) {
        return plugin.getConfigManager().getTemplateKeys().stream().toList();
    }

    @Suggestions("vesselAmounts")
    public List<String> suggestVesselAmounts(CommandContext<CommandSourceStack> context, String input) {
        return IntStream.rangeClosed(1, 64).mapToObj(String::valueOf).toList();
    }

    @Command("vessel [fallback]")
    @Permission("vessel.command.help")
    public void onHelpFallback(CommandSourceStack source, @Argument("fallback") @Nullable String fallback) {
        help(source);
    }

    @Command("vessel about")
    @Permission("vessel.command.about")
    public void onAbout(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        ConfigManager config = plugin.getConfigManager();

        Messages.send(
                sender,
                config.getMessageConfig().prefix
                        + "Plugin Version: <gray><version></gray>, Authors: <gray><authors></gray>",
                Messages.tag("version", plugin.getPluginMeta().getVersion()),
                Messages.tag("authors", String.join(", ", plugin.getPluginMeta().getAuthors())));
    }

    @Command("vessel reload")
    @Permission("vessel.command.reload")
    public void onReload(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (plugin.reload()) {
            Messages.send(sender, plugin.getConfigManager().getMessageConfig().commands.reloadSuccess);
        } else {
            Messages.send(
                    sender,
                    plugin.getConfigManager().getMessageConfig().commands.reloadFail,
                    Messages.tag("error", "Check console for details"));
        }
    }

    @Command("vessel help")
    @Permission("vessel.command.help")
    public void help(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        ConfigManager config = plugin.getConfigManager();

        Messages.send(sender, config.getMessageConfig().help.header);
        Messages.send(sender, config.getMessageConfig().help.about);
        Messages.send(sender, config.getMessageConfig().help.help);
        Messages.send(sender, config.getMessageConfig().help.give, Messages.tag("player", "<player>"));
        Messages.send(sender, config.getMessageConfig().help.reload);
    }

    @Command("vessel give <player> <type> <amount>")
    @Permission("vessel.command.give")
    public void give(
            CommandSourceStack source,
            @Argument("player") Player player,
            @Argument(value = "type", suggestions = "vesselTypes") String type,
            @Argument(value = "amount", suggestions = "vesselAmounts") int amount,
            @Flag(value = "silent", aliases = "s") boolean isSilent) {

        CommandSender sender = source.getSender();
        ConfigManager config = plugin.getConfigManager();

        if (amount < 1 || amount > 64) {
            Messages.send(
                    sender,
                    config.getMessageConfig().commands.invalidAmount,
                    Messages.tag("min", 1),
                    Messages.tag("max", 64));
            return;
        }

        ItemStack item = plugin.getVesselManager().createEmptyVessel(type);
        if (item == null) {
            Messages.send(sender, config.getMessageConfig().commands.invalidType);
            return;
        }

        item.setAmount(amount);

        final String targetName = player.getName();
        final AtomicBoolean completed = new AtomicBoolean(false);

        Runnable onRetired = () -> {
            if (completed.compareAndSet(false, true)) {
                Log.warn("Failed to give vessel(s): player " + targetName + " retired or disconnected before delivery.");
                Messages.send(
                        sender,
                        "<prefix> <red>Failed to give vessel(s): player retired or disconnected before delivery.</red>");
            }
        };

        if (!player.isOnline()) {
            onRetired.run();
            return;
        }

        ScheduledTask scheduledTask = player.getScheduler().run(
                plugin,
                task -> {
                    if (completed.get()) {
                        return;
                    }
                    if (!player.isOnline() || !player.isValid()) {
                        onRetired.run();
                        return;
                    }

                    try {
                        Location loc = player.getLocation();
                        World world = player.getWorld();

                        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
                        if (!leftovers.isEmpty()) {
                            for (ItemStack leftover : leftovers.values()) {
                                world.dropItemNaturally(loc, leftover);
                            }
                        }

                        if (completed.compareAndSet(false, true)) {
                            if (!isSilent) {
                                Messages.send(
                                        sender,
                                        config.getMessageConfig().commands.giveSender,
                                        Messages.tag("target", player.getName()),
                                        Messages.tag("amount", amount),
                                        Messages.tag("type", type));
                                Messages.send(
                                        player,
                                        config.getMessageConfig().commands.givePlayer,
                                        Messages.tag("amount", amount),
                                        Messages.tag("type", type));
                            } else {
                                Log.info("Gave " + player.getName() + " " + amount + " " + type + " vessel(s) silently.");
                            }
                        }
                    } catch (Throwable t) {
                        Log.error("Error during vessel delivery to " + targetName + ": " + t.getMessage());
                        if (completed.compareAndSet(false, true)) {
                            Messages.send(
                                    sender,
                                    "<prefix> <red>An error occurred while giving vessel(s) to <target>.</red>",
                                    Messages.tag("target", targetName));
                        }
                    }
                },
                onRetired);

        if (scheduledTask == null) {
            onRetired.run();
        }
    }
}
