/*
 * This file is part of PlaceholderAPI
 *
 * PlaceholderAPI
 * Copyright (c) 2015 - 2026 PlaceholderAPI Team
 *
 * PlaceholderAPI free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PlaceholderAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package me.clip.placeholderapi;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

import me.clip.placeholderapi.commands.PlaceholderCommandRouter;
import me.clip.placeholderapi.configuration.PlaceholderAPIConfig;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Version;
import me.clip.placeholderapi.expansion.manager.CloudExpansionManager;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import me.clip.placeholderapi.listeners.ServerLoadEventListener;
import me.clip.placeholderapi.scheduler.UniversalScheduler;
import me.clip.placeholderapi.scheduler.scheduling.schedulers.TaskScheduler;
import me.clip.placeholderapi.updatechecker.UpdateChecker;
import me.clip.placeholderapi.util.ExpansionSafetyCheck;
import me.clip.placeholderapi.util.Msg;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Yes I have a shit load of work to do...
 *
 * @author Ryan McCarthy
 */
public final class PlaceholderAPIPlugin extends JavaPlugin {

    @NotNull
    private static final Version VERSION;
    private static PlaceholderAPIPlugin instance;

    static {
        // The version string here is only used by the deprecated VersionSpecific mechanism and the
        // bStats "using_spigot" chart. It must NEVER throw — otherwise the entire PlaceholderAPIPlugin
        // class fails to initialize (ExceptionInInitializerError) and every later call to
        // PlaceholderAPI.setPlaceholders(...) throws NoClassDefFoundError, breaking Plan, TAB, etc.
        //
        // Older code parsed Bukkit#getBukkitVersion() which on forks like Canvas/Folia can embed
        // non-numeric build suffixes (e.g. "1.21.b821"), causing NumberFormatException on parseInt.
        // We now prefer Server#getMinecraftVersion() (Paper/Folia 1.20.5+, returns clean "1.21.11")
        // and fall back to getBukkitVersion() only when getMinecraftVersion() is unavailable.
        String version;
        try {
            version = normalizeServerVersion(resolveMinecraftVersion());
        } catch (final Throwable throwable) {
            // Cannot use Msg.warn or Bukkit#getLogger() here — both depend on PlaceholderAPIPlugin
            // being initialized / Bukkit.server being set, which is not the case during <clinit>.
            // Use System.err directly so the failure is still visible without risking a second
            // exception that would re-throw ExceptionInInitializerError.
            System.err.println("[PlaceholderAPI] Failed to resolve server version; defaulting to 'vunknown_R1'");
            throwable.printStackTrace(System.err);
            version = "vunknown_R1";
        }

        boolean isSpigot;
        try {
            Class.forName("org.spigotmc.SpigotConfig");
            isSpigot = true;
        } catch (final ExceptionInInitializerError | ClassNotFoundException ignored) {
            isSpigot = false;
        }

        VERSION = new Version(version, isSpigot);
    }

    /**
     * Resolves the running Minecraft version string, preferring the clean
     * {@link org.bukkit.Server#getMinecraftVersion()} (Paper/Folia 1.20.5+, returns e.g. {@code "1.21.11"})
     * and falling back to {@link org.bukkit.Server#getBukkitVersion()} stripped of its {@code -...} suffix
     * on older Bukkit/Spigot builds where {@code getMinecraftVersion()} does not exist.
     *
     * @return the raw Minecraft version string (e.g. {@code "1.21.11"} or {@code "1.21"})
     */
    @NotNull
    private static String resolveMinecraftVersion() {
        try {
            return Bukkit.getServer().getMinecraftVersion();
        } catch (final NoSuchMethodError | UnknownError ignored) {
            // Pre-1.20.5 Bukkit/Spigot — fall back to the legacy, messier source.
            return Bukkit.getServer().getBukkitVersion().split("-")[0];
        }
    }

    /**
     * Normalizes a Minecraft version string (e.g. {@code "1.21.11"} or {@code "1.21"}) into the
     * legacy NMS-package format expected by deprecated {@link VersionSpecific} expansions
     * (e.g. {@code "v1_21_R2"}).
     *
     * <p>For a two-segment input ({@code "1.21"}) the patch is assumed to be {@code 1}
     * → {@code "v1_21_R1"}.</p>
     *
     * <p>For a three-segment input ({@code "1.21.11"}) the patch number is used as the
     * {@code R<n>} suffix directly (so {@code "1.21.11"} → {@code "v1_21_R11"}).
     * Non-numeric patch segments are stripped to their leading digit, and if that fails the
     * whole segment is treated as {@code 1}.</p>
     *
     * @param raw the raw Minecraft version string, never {@code null}
     * @return a normalized {@code v<major>_<minor>_R<n>} string
     */
    @NotNull
    private static String normalizeServerVersion(@NotNull final String raw) {
        final String[] parts = raw.split("\\.");
        if (parts.length < 3) {
            // e.g. "1.21" → "v1_21_R1"
            return 'v' + raw.replace('.', '_') + "_R1";
        }

        final String major = parts[0];
        final String minor = parts[1];
        final int patch = parsePatchSegment(parts[2]);

        return "v" + major + '_' + minor + "_R" + patch;
    }

    /**
     * Parses the patch segment of a Minecraft version into an {@code R<n>} number.
     *
     * <p>Accepts both pure-numeric patches ({@code "11"} → {@code 11}) and fork-augmented
     * patches that start with a digit ({@code "11b821"} → {@code 11}). If the segment does
     * not start with a digit (e.g. Canvas' {@code "b821"}), falls back to {@code 1} so the
     * plugin still loads.</p>
     *
     * @param patchSegment the third dot-separated segment of the version string
     * @return a positive patch number
     */
    private static int parsePatchSegment(@NotNull final String patchSegment) {
        // Pull the leading run of digits out of the segment (handles "11", "11b821", etc.).
        int end = 0;
        while (end < patchSegment.length() && Character.isDigit(patchSegment.charAt(end))) {
            end++;
        }

        if (end == 0) {
            // No leading digit (e.g. "b821") — can't infer a patch number, default safely.
            return 1;
        }

        try {
            final int parsed = Integer.parseInt(patchSegment.substring(0, end));
            return parsed > 0 ? parsed : 1;
        } catch (final NumberFormatException ignored) {
            return 1;
        }
    }

    @NotNull
    private final PlaceholderAPIConfig config = new PlaceholderAPIConfig(this);

    @NotNull
    private final LocalExpansionManager localExpansionManager = new LocalExpansionManager(this);
    @NotNull
    private final CloudExpansionManager cloudExpansionManager = new CloudExpansionManager(this);
    @NotNull
    private final TaskScheduler scheduler = UniversalScheduler.getScheduler(this);

    private BukkitAudiences adventure;
    private boolean safetyCheck = false;


    /**
     * Gets the static instance of the main class for PlaceholderAPI. This class is not the actual API
     * class, this is the main class that extends JavaPlugin. For most API methods, use static methods
     * available from the class: {@link PlaceholderAPI}
     *
     * @return PlaceholderAPIPlugin instance
     */
    @NotNull
    public static PlaceholderAPIPlugin getInstance() {
        return instance;
    }

    /**
     * Get the configurable {@linkplain String} value that should be returned when a boolean is true
     *
     * @return string value of true
     */
    @NotNull
    public static String booleanTrue() {
        return getInstance().getPlaceholderAPIConfig().booleanTrue();
    }

    /**
     * Get the configurable {@linkplain String} value that should be returned when a boolean is false
     *
     * @return string value of false
     */
    @NotNull
    public static String booleanFalse() {
        return getInstance().getPlaceholderAPIConfig().booleanFalse();
    }

    /**
     * Get the configurable {@linkplain SimpleDateFormat} object that is used to parse time for
     * generic time based placeholders
     *
     * @return date format
     */
    @NotNull
    public static SimpleDateFormat getDateFormat() {
        try {
            return new SimpleDateFormat(getInstance().getPlaceholderAPIConfig().dateFormat());
        } catch (final IllegalArgumentException ex) {
            Msg.warn("Configured date format ('%s') is invalid! Defaulting to 'MM/dd/yy HH:mm:ss'",
                    ex, getInstance().getPlaceholderAPIConfig().dateFormat());
            return new SimpleDateFormat("MM/dd/yy HH:mm:ss");
        }
    }

    @Deprecated
    public static Version getServerVersion() {
        return VERSION;
    }

    @Override
    public void onLoad() {
        saveDefaultConfig();

        safetyCheck = new ExpansionSafetyCheck(this).runChecks();

        if (safetyCheck) {
            return;
        }

        instance = this;
    }

    @Override
    public void onEnable() {
        if (safetyCheck) {
            return;
        }

        setupCommand();
        setupMetrics();
        setupExpansions();

        adventure = BukkitAudiences.create(this);

        if (config.isCloudEnabled()) {
            getCloudExpansionManager().load();
        }

        if (config.checkUpdates()) {
            new UpdateChecker(this).fetch();
        }
    }

    @Override
    public void onDisable() {
        if (safetyCheck) {
            return;
        }

        getCloudExpansionManager().kill();
        getLocalExpansionManager().kill();

        HandlerList.unregisterAll(this);

        scheduler.cancelTasks(this);

        adventure.close();
        adventure = null;

        instance = null;
    }

    public void reloadConf(@NotNull final CommandSender sender) {
        getLocalExpansionManager().kill();

        reloadConfig();

        getLocalExpansionManager().load(sender);

        if (config.isCloudEnabled()) {
            getCloudExpansionManager().load();
        } else {
            getCloudExpansionManager().kill();
        }
    }

    @NotNull
    public LocalExpansionManager getLocalExpansionManager() {
        return localExpansionManager;
    }

    @NotNull
    public CloudExpansionManager getCloudExpansionManager() {
        return cloudExpansionManager;
    }

    @NotNull
    public BukkitAudiences getAdventure() {
        if (adventure == null) {
            throw new IllegalStateException("Tried to access Adventure when the plugin was disabled!");
        }

        return adventure;
    }

    @NotNull
    public TaskScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Obtain the configuration class for PlaceholderAPI.
     *
     * @return PlaceholderAPIConfig instance
     */
    @NotNull
    public PlaceholderAPIConfig getPlaceholderAPIConfig() {
        return config;
    }

    private void setupCommand() {
        final PluginCommand pluginCommand = getCommand("placeholderapi");
        if (pluginCommand == null) {
            return;
        }

        final PlaceholderCommandRouter router = new PlaceholderCommandRouter(this);
        pluginCommand.setExecutor(router);
        pluginCommand.setTabCompleter(router);
    }

    private void setupMetrics() {
        final Metrics metrics = new Metrics(this, 438);
        metrics.addCustomChart(new SimplePie("using_expansion_cloud",
                () -> getPlaceholderAPIConfig().isCloudEnabled() ? "yes" : "no"));

        metrics.addCustomChart(new SimplePie("using_spigot", () -> getServerVersion().isSpigot() ? "yes" : "no"));

        metrics.addCustomChart(new AdvancedPie("expansions_used", () -> {
            final Map<String, Integer> values = new HashMap<>();

            for (final PlaceholderExpansion expansion : getLocalExpansionManager().getExpansions()) {
                values.put(expansion.getRequiredPlugin() == null ? expansion.getIdentifier()
                        : expansion.getRequiredPlugin(), 1);
            }

            return values;
        }));
    }

    private void setupExpansions() {
        Bukkit.getPluginManager().registerEvents(getLocalExpansionManager(), this);

        try {
            Class.forName("org.bukkit.event.server.ServerLoadEvent");
            new ServerLoadEventListener(this);
        } catch (final ClassNotFoundException ignored) {
            scheduler
                    .runTaskLater(() -> getLocalExpansionManager().load(Bukkit.getConsoleSender()), 1);
        }
    }

}
