package org.maboroshi.vessel;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.maboroshi.vessel.command.VesselCommand;
import org.maboroshi.vessel.config.ConfigManager;
import org.maboroshi.vessel.handler.ActionHandler;
import org.maboroshi.vessel.handler.CooldownHandler;
import org.maboroshi.vessel.handler.EffectHandler;
import org.maboroshi.vessel.handler.VesselEventHandler;
import org.maboroshi.vessel.listener.CaptureListener;
import org.maboroshi.vessel.listener.ReleaseListener;
import org.maboroshi.vessel.listener.SpawnReasonListener;
import org.maboroshi.vessel.manager.InFlightGuard;
import org.maboroshi.vessel.manager.VesselManager;
import org.maboroshi.vessel.protection.ProtectionService;
import org.maboroshi.vessel.util.Keys;
import org.maboroshi.vessel.util.Log;
import org.maboroshi.vessel.util.Messages;

public final class Vessel extends JavaPlugin {
    private static Vessel plugin;

    private ConfigManager configManager;
    private EffectHandler effectHandler;
    private CooldownHandler cooldownHandler;
    private ActionHandler actionHandler;
    private VesselManager vesselManager;
    private ProtectionService protectionService;
    private final InFlightGuard inFlightGuard = new InFlightGuard();

    @Override
    public void onEnable() {
        plugin = this;
        Keys.init(this);
        this.configManager = new ConfigManager(getDataFolder());

        Log.init(
                getComponentLogger(),
                () -> configManager != null
                        && configManager.getMainConfig() != null
                        && configManager.getMainConfig().debug);

        try {
            configManager.loadConfig();
            configManager.loadMessages();
        } catch (Exception e) {
            Log.error("Failed to load configuration: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Messages.init(this.configManager);

        this.effectHandler = new EffectHandler(this);
        this.cooldownHandler = new CooldownHandler();
        this.actionHandler = new ActionHandler(this);
        this.vesselManager = new VesselManager(this);
        this.protectionService = ProtectionService.create(this);

        getServer().getPluginManager().registerEvents(new VesselEventHandler(this), this);
        getServer().getPluginManager().registerEvents(new SpawnReasonListener(this), this);
        getServer().getPluginManager().registerEvents(new CaptureListener(this), this);
        getServer().getPluginManager().registerEvents(new ReleaseListener(this), this);

        PaperCommandManager<CommandSourceStack> commandManager = PaperCommandManager.builder()
                .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
                .buildOnEnable(this);

        AnnotationParser<CommandSourceStack> annotationParser =
                new AnnotationParser<>(commandManager, CommandSourceStack.class);

        annotationParser.parse(new VesselCommand(this));

        @SuppressWarnings("unused")
        Metrics metrics = new Metrics(this, 31642);
    }

    public boolean reload() {
        try {
            configManager.loadConfig();
            configManager.loadMessages();

            if (cooldownHandler != null) {
                cooldownHandler.clearCooldowns();
            }

            this.vesselManager = new VesselManager(this);

            for (Player player : Bukkit.getOnlinePlayers()) {
                player.updateCommands();
            }

            return true;
        } catch (Exception e) {
            Log.error("Failed to reload Vessel configuration: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onDisable() {
        // PlugMan-hotswap safety: null out static state so nothing outlives this classloader across
        // an unload/reload cycle. Listeners/commands are torn down by Bukkit's own plugin-disable
        // handling; command delivery uses transient entity scheduler tasks bound to this plugin,
        // and there are no persistent schedulers, open connections, or background tasks of Vessel's own
        // to stop here (CooldownHandler's Guava cache and InFlightGuard's set both just get GC'd
        // with this instance).
        plugin = null;
    }

    public static Vessel getPlugin() {
        return plugin;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public EffectHandler getEffectHandler() {
        return effectHandler;
    }

    public CooldownHandler getCooldownHandler() {
        return cooldownHandler;
    }

    public VesselManager getVesselManager() {
        return vesselManager;
    }

    public ActionHandler getActionHandler() {
        return actionHandler;
    }

    public ProtectionService getProtectionService() {
        return protectionService;
    }

    public InFlightGuard getInFlightGuard() {
        return inFlightGuard;
    }
}
