/*
 *            This file is part of  LibelulaProtectionBlocks.
 *
 *   LibelulaProtectionBlocks is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *   LibelulaProtectionBlocks is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with  LibelulaProtectionBlocks.
 *  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package me.libelula.pb;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Diego D'Onofrio <ddonofrio@member.fsf.org>
 */
public final class Main extends JavaPlugin {

    public final TextManager tm = new TextManager(this);
    public final ProtectionManager pm = new ProtectionManager(this);
    public final Shop sh = new Shop(this);
    private final ConsoleCommandSender console = getServer().getConsoleSender();
    private final CommandManager cm = new CommandManager(this);
    private final EventManager em = new EventManager(this);
    private String prefix;
    private WorldGuardManager wg;
    private Economy eco;

    @Override
    public void onEnable() {
        prefix = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("prefix"));
        try {
            tm.initialize();
        } catch (MalformedURLException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }

        wg = new WorldGuardManager(this);
        wg.initialize();

        Bukkit.getScheduler().runTaskLater(this, () -> {
            ProtectionStonesImporter importer
                    = new ProtectionStonesImporter(this);
            boolean tryToImport = false;
            if (importer.isOldPsActive()) {
                importer.disableOldPs();
                sendMessage(
                        console, "&6Old fashioned plugin ProtectionStones found and disabled.");
                tryToImport = true;
            }

            File configFile = new File(getDataFolder(), "config.yml");

            if (!configFile.exists()) {
                saveResource("config.yml", true);
                sendMessage(console, "Default config.yml saved.");
            }

            if (getConfig().getInt("config-version") != 3) {
                prefix = "";
                alert("The version of this plugin is incompatible with "
                        + "actual directory. You have to rename or erase "
                        + "LibelulaProtectionBlocks diretory from the plugin "
                        + "folder and restart your server.");
                disable();
                return;
            }

            try {
                if (!wg.isWorldGuardActive()) {
                    alert(tm.getText("wg_not_initialized"));
                    disable();
                    return;
                }

                cm.initialize();
                em.initialize();

                Bukkit.getScheduler().runTaskAsynchronously(this, pm::initialize);

                if (!setupEconomy()) {
                    alert(tm.getText("vault-plugin-not-loaded"));
                } else {
                    sendMessage(getServer().getConsoleSender(),
                            tm.getText("vault-plugin-linked"));
                    if (getConfig().getBoolean("shop.enable")) {
                        sh.initialize();
                        sendMessage(getServer().getConsoleSender(),
                                tm.getText("shop_enabled"));
                    }
                }

                if (getConfig().getBoolean("auto-save.enabled") && getConfig().getInt("auto-save.interval-minutes") > 0) {
                    int autoSaveInterval = getConfig().getInt("auto-save.interval-minutes") * 20 * 60;
                    Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                        if (getConfig().getBoolean("auto-save.log-messages")) {
                            sendMessage(console, tm.getText("saving"));
                        }
                        pm.save();
                    }, autoSaveInterval, autoSaveInterval);

                    if (tryToImport) {
                        if (importer.isImportNeeded()) {
                            importer.importFromOldPS();
                        } else {
                            alert(tm.getText("old_ps_already_imported"));
                        }
                    }
                } else {
                    alert(tm.getText("auto_save_disabled"));
                }
            } catch (NoClassDefFoundError | IOException ex) {
                alert(tm.getText("unexpected_error", ex));
                disable();
            }
        }, 20);
    }

    @Override
    public void onDisable() {
        pm.save();
    }

    public void sendMessage(Player player, final String message) {
        sendMessage((CommandSender) player, message);
    }

    public void sendMessage(final CommandSender cs, List<String> messages) {
        for (String message : messages) {
            sendMessage(cs, message, 1);
        }
    }

    public void sendMessage(final CommandSender cs, final String message) {
        sendMessage(cs, message, 1);
    }

    public void sendMessage(final CommandSender cs, final String message, long later) {
        if (prefix == null) {
            prefix = "";
        }
        if (isEnabled()) {
            Bukkit.getScheduler().runTaskLater(this, () -> cs.sendMessage(prefix + ChatColor.translateAlternateColorCodes('&', message)), later);
        } else {
            cs.sendMessage(prefix + ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    public void alert(String message) {
        console.sendMessage(prefix + ChatColor.RED + message);
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.hasPermission("pb.notification.receive")) {
                player.sendMessage(prefix + ChatColor.RED + message);
            }
        }
    }

    public String getPrefix() {
        return prefix;
    }

    public ConsoleCommandSender getConsole() {
        return console;
    }

    public WorldGuardManager getWG() {
        return wg;
    }

    public void disable() {
        String text = tm.getText("plugin_disabled");
        console.sendMessage(prefix + ChatColor.RED
                + text);
        this.getPluginLoader().disablePlugin(this);
    }

    private boolean setupEconomy() {
        eco = null;
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer()
                .getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        eco = rsp.getProvider();
        return eco != null;
    }

    public void reloadLocalConfig() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (tm.isInitialized()) {
                sendMessage(console, tm.getText("reloading_config"));
            }
            if (getWG() != null) {
                getWG().reloadConfig();
                pm.load();
            }
            if (tm.isInitialized()) {
                alert(ChatColor.YELLOW + tm.getText("config_reloaded"));
            }
        });
    }

    public void logTranslated(String message, Object... params) {
        sendMessage(console, tm.getText(message, params));
    }

    public Economy getEco() {
        return eco;
    }

}
