package ro.marioenache.votemanager.spigot.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ro.marioenache.votemanager.common.config.ConfigManager;
import ro.marioenache.votemanager.common.database.DatabaseConfig;
import ro.marioenache.votemanager.spigot.model.VotePartyCommand;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Config manager for Spigot
 */
public class SpigotConfigManager extends ConfigManager {
    private FileConfiguration config;
    private final File configFile;
    
    private DatabaseConfig databaseConfig;
    
    private boolean debugEnabled;
    private String serverName;
    private boolean permissionRewardsEnabled;
    private Map<String, List<String>> voteCommands;
    private Map<String, List<String>> permissionRewards;
    
    // Vote party settings
    private boolean votePartyEnabled;
    private int votePartyGoal;
    private List<String> votePartyCommands; // Legacy format
    private List<VotePartyCommand> votePartyCommandsWithChance; // New format
    private boolean onlyRewardVoters;
    private boolean useRandomizedCommands;
    
    // Cache settings
    private long userCacheTTL;
    private long votePartyCacheTTL;
    private long pendingVotesCacheTTL;
    
    // Performance metrics settings
    private boolean metricsEnabled;
    private int metricsInterval;

    /**
     * Create a new Spigot config manager
     * 
     * @param dataFolder The data folder to store config files in
     */
    public SpigotConfigManager(File dataFolder) {
        super(dataFolder);
        this.configFile = new File(dataFolder, "config.yml");
    }

    @Override
    public void saveDefaultConfigs() {
        // Create the default config if it doesn't exist
        if (!configFile.exists()) {
            try {
                configFile.getParentFile().mkdirs();
                
                // Copy default config from resources
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("spigot-config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        // Create a basic config file manually
                        String defaultConfig = 
                            "# VoteManager Configuration (Spigot)\n" +
                            "debug: false\n\n" +
                            "# Server name (must match the name in Velocity)\n" +
                            "server-name: lobby\n\n" +
                            "# Database Configuration\n" +
                            "database:\n" +
                            "  host: localhost\n" +
                            "  port: 3306\n" +
                            "  name: votemanager\n" +
                            "  username: root\n" +
                            "  password: password\n\n" +
                            "# Performance Settings\n" +
                            "performance:\n" +
                            "  # Cache TTL (Time To Live) in milliseconds\n" +
                            "  cache-ttl:\n" +
                            "    user: 300000        # 5 minutes\n" +
                            "    vote-party: 60000   # 1 minute\n" +
                            "    pending-votes: 30000 # 30 seconds\n" +
                            "  # Performance metrics\n" +
                            "  metrics:\n" +
                            "    enabled: true\n" +
                            "    interval: 5         # Report interval in minutes\n\n" +
                            "# Vote Commands\n" +
                            "vote-commands:\n" +
                            "  default: # Default commands for all vote services\n" +
                            "    - \"give %player% diamond 1\"\n" +
                            "    - \"minecraft:give %player% minecraft:gold_ingot 1\"\n" +
                            "  CraftServers: # Commands for a specific vote service\n" +
                            "    - \"give %player% diamond 2\"\n" +
                            "    - \"minecraft:give %player% minecraft:emerald 1\"\n\n" +
                            "# Permission Rewards\n" +
                            "permission-rewards:\n" +
                            "  enabled: true\n" +
                            "  rewards:\n" +
                            "    votemanager_vip:\n" +
                            "      - \"give %player% diamond 5\"\n" +
                            "    votemanager_donor:\n" +
                            "      - \"give %player% diamond_block 1\"\n\n" +
                            "# Vote Party\n" +
                            "vote-party:\n" +
                            "  enabled: true\n" +
                            "  goal: 25              # Votes needed to trigger a party (override from proxy)\n" +
                            "  use-randomized-commands: true # Whether to use the new randomized command system\n" +
                            "  commands:             # Legacy command format (used if use-randomized-commands is false)\n" +
                            "    - \"broadcast &a&lVote Party! &fEveryone who voted gets rewards!\"\n" +
                            "    - \"execute as @a run give @s diamond 5\"\n" +
                            "  randomized-commands:  # New format with chance and delay\n" +
                            "    - command: \"broadcast &a&lVote Party! &fEveryone gets rewards!\"\n" +
                            "      chance: 1.0       # 100% chance to execute (between 0.0 and 1.0)\n" +
                            "      delay: 0          # Execute immediately (in ticks, 20 ticks = 1 second)\n" +
                            "    - command: \"give %player% diamond 5\"\n" +
                            "      chance: 0.8       # 80% chance to execute for each player\n" +
                            "      delay: 20         # 1 second delay after previous command\n" +
                            "    - command: \"give %player% emerald 2\"\n" +
                            "      chance: 0.5       # 50% chance to execute for each player\n" +
                            "      delay: 20         # 1 second delay after previous command\n" +
                            "    - command: \"give %player% diamond_block 1\"\n" +
                            "      chance: 0.2       # 20% chance to execute for each player\n" +
                            "      delay: 20         # 1 second delay after previous command\n" +
                            "    - command: \"broadcast &e%player% got lucky in the vote party!\"\n" +
                            "      chance: 0.1       # 10% chance to execute for each player\n" +
                            "      delay: 40         # 2 second delay after previous command\n" +
                            "  # Only reward players with processed votes\n" +
                            "  only-reward-voters: true";
                        
                        Files.writeString(configFile.toPath(), defaultConfig);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        // Load the config
        reloadConfigs();
    }

    @Override
    public void reloadConfigs() {
        config = YamlConfiguration.loadConfiguration(configFile);
        
        // Load database config
        ConfigurationSection dbSection = config.getConfigurationSection("database");
        String host = dbSection.getString("host", "localhost");
        int port = dbSection.getInt("port", 3306);
        String database = dbSection.getString("name", "votemanager");
        String username = dbSection.getString("username", "root");
        String password = dbSection.getString("password", "password");
        
        databaseConfig = new DatabaseConfig(host, port, database, username, password);
        
        // Load other settings
        debugEnabled = config.getBoolean("debug", false);
        serverName = config.getString("server-name", "lobby");
        
        // Load performance settings
        ConfigurationSection performanceSection = config.getConfigurationSection("performance");
        if (performanceSection != null) {
            ConfigurationSection cacheTTLSection = performanceSection.getConfigurationSection("cache-ttl");
            if (cacheTTLSection != null) {
                userCacheTTL = cacheTTLSection.getLong("user", TimeUnit.MINUTES.toMillis(5));
                votePartyCacheTTL = cacheTTLSection.getLong("vote-party", TimeUnit.MINUTES.toMillis(1));
                pendingVotesCacheTTL = cacheTTLSection.getLong("pending-votes", TimeUnit.SECONDS.toMillis(30));
            } else {
                // Default values
                userCacheTTL = TimeUnit.MINUTES.toMillis(5);
                votePartyCacheTTL = TimeUnit.MINUTES.toMillis(1);
                pendingVotesCacheTTL = TimeUnit.SECONDS.toMillis(30);
            }
            
            ConfigurationSection metricsSection = performanceSection.getConfigurationSection("metrics");
            if (metricsSection != null) {
                metricsEnabled = metricsSection.getBoolean("enabled", true);
                metricsInterval = metricsSection.getInt("interval", 5);
            } else {
                // Default values
                metricsEnabled = true;
                metricsInterval = 5;
            }
        } else {
            // Default values if performance section is missing
            userCacheTTL = TimeUnit.MINUTES.toMillis(5);
            votePartyCacheTTL = TimeUnit.MINUTES.toMillis(1);
            pendingVotesCacheTTL = TimeUnit.SECONDS.toMillis(30);
            metricsEnabled = true;
            metricsInterval = 5;
        }
        
        // Load vote commands
        voteCommands = new HashMap<>();
        ConfigurationSection voteCommandsSection = config.getConfigurationSection("vote-commands");
        if (voteCommandsSection != null) {
            for (String key : voteCommandsSection.getKeys(false)) {
                voteCommands.put(key, voteCommandsSection.getStringList(key));
            }
        }
        
        // Load permission rewards
        permissionRewardsEnabled = config.getBoolean("permission-rewards.enabled", true);
        permissionRewards = new HashMap<>();
        ConfigurationSection permissionRewardsSection = config.getConfigurationSection("permission-rewards.rewards");
        
        if (permissionRewardsSection != null) {
            getLogger().info("Loading permission rewards from config");
            
            for (String permission : permissionRewardsSection.getKeys(false)) {
                List<String> commands = permissionRewardsSection.getStringList(permission);
                permissionRewards.put(permission, commands);
                
                if (debugEnabled) {
                    getLogger().info("Loaded permission reward: " + permission + " with " + commands.size() + " commands");
                    for (String cmd : commands) {
                        getLogger().info(" - Command: " + cmd);
                    }
                }
            }
            
            if (debugEnabled) {
                getLogger().info("Loaded permission rewards: " + permissionRewards.size() + " permissions");
                for (Map.Entry<String, List<String>> entry : permissionRewards.entrySet()) {
                    getLogger().info("Permission: " + entry.getKey() + ", Commands: " + entry.getValue());
                }
            }
        } else {
            if (debugEnabled) {
                getLogger().info("No permission rewards section found in config");
            }
        }
        
        // Load vote party settings
        ConfigurationSection votePartySection = config.getConfigurationSection("vote-party");
        if (votePartySection != null) {
            votePartyEnabled = votePartySection.getBoolean("enabled", true);
            votePartyGoal = votePartySection.getInt("goal", 25);
            votePartyCommands = votePartySection.getStringList("commands");
            onlyRewardVoters = votePartySection.getBoolean("only-reward-voters", true);
            
            // Load the new randomized commands setting
            useRandomizedCommands = votePartySection.getBoolean("use-randomized-commands", false);
            
            // Load the new randomized commands format
            votePartyCommandsWithChance = new ArrayList<>();
            List<Map<?, ?>> randomizedCommands = votePartySection.getMapList("randomized-commands");
            for (Map<?, ?> commandMap : randomizedCommands) {
                String command = (String) commandMap.get("command");
                double chance = commandMap.containsKey("chance") ? 
                    ((Number) commandMap.get("chance")).doubleValue() : 1.0;
                long delay = commandMap.containsKey("delay") ? 
                    ((Number) commandMap.get("delay")).longValue() : 0;
                
                // Ensure chance is between 0 and 1
                chance = Math.max(0.0, Math.min(1.0, chance));
                
                votePartyCommandsWithChance.add(new VotePartyCommand(command, chance, delay));
            }
            
            // If no randomized commands are defined but use-randomized-commands is true,
            // convert legacy commands to the new format
            if (useRandomizedCommands && votePartyCommandsWithChance.isEmpty() && !votePartyCommands.isEmpty()) {
                for (String command : votePartyCommands) {
                    votePartyCommandsWithChance.add(new VotePartyCommand(command, 1.0, 0));
                }
            }
        } else {
            votePartyEnabled = true;
            votePartyGoal = 25;
            votePartyCommands = new ArrayList<>();
            votePartyCommandsWithChance = new ArrayList<>();
            onlyRewardVoters = true;
            useRandomizedCommands = false;
        }
    }

    /**
     * Get the database configuration
     * 
     * @return The database configuration
     */
    public DatabaseConfig getDatabaseConfig() {
        return databaseConfig;
    }

    /**
     * Check if debug mode is enabled
     * 
     * @return True if debug mode is enabled
     */
    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * Get the server name
     * 
     * @return The server name
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Check if permission rewards are enabled
     * 
     * @return True if permission rewards are enabled
     */
    public boolean isPermissionRewardsEnabled() {
        return permissionRewardsEnabled;
    }

    /**
     * Get the vote commands for a service
     * 
     * @param serviceName The name of the vote service
     * @return The vote commands
     */
    public List<String> getVoteCommands(String serviceName) {
        List<String> commands = new ArrayList<>();
        
        // Add default commands
        if (voteCommands.containsKey("default")) {
            commands.addAll(voteCommands.get("default"));
        }
        
        // Add service-specific commands
        if (voteCommands.containsKey(serviceName)) {
            commands.addAll(voteCommands.get(serviceName));
        }
        
        return commands;
    }

    /**
     * Get the permission rewards
     * 
     * @return The permission rewards
     */
    public Map<String, List<String>> getPermissionRewards() {
        return permissionRewards;
    }
    
    /**
     * Check if vote party is enabled
     * 
     * @return True if vote party is enabled
     */
    public boolean isVotePartyEnabled() {
        return votePartyEnabled;
    }
    
    /**
     * Get the vote party goal
     * 
     * @return The vote party goal
     */
    public int getVotePartyGoal() {
        return votePartyGoal;
    }

    /**
     * Get the vote party commands (legacy format)
     * 
     * @return The vote party commands
     */
    public List<String> getVotePartyCommands() {
        return votePartyCommands;
    }
    
    /**
     * Get the vote party commands with chance and delay
     * 
     * @return The vote party commands with chance and delay
     */
    public List<VotePartyCommand> getVotePartyCommandsWithChance() {
        return votePartyCommandsWithChance;
    }
    
    /**
     * Check if only voters should be rewarded in vote parties
     * 
     * @return True if only voters should be rewarded
     */
    public boolean isOnlyRewardVoters() {
        return onlyRewardVoters;
    }
    
    /**
     * Check if randomized commands should be used
     * 
     * @return True if randomized commands should be used
     */
    public boolean useRandomizedCommands() {
        return useRandomizedCommands;
    }
    
    /**
     * Get the logger for the SpigotConfigManager
     * 
     * @return The logger for the SpigotConfigManager
     */
    private Logger getLogger() {
        return Logger.getLogger("VoteManager");
    }
    
    /**
     * Get the user cache TTL in milliseconds
     * 
     * @return The user cache TTL
     */
    public long getUserCacheTTL() {
        return userCacheTTL;
    }
    
    /**
     * Get the vote party cache TTL in milliseconds
     * 
     * @return The vote party cache TTL
     */
    public long getVotePartyCacheTTL() {
        return votePartyCacheTTL;
    }
    
    /**
     * Get the pending votes cache TTL in milliseconds
     * 
     * @return The pending votes cache TTL
     */
    public long getPendingVotesCacheTTL() {
        return pendingVotesCacheTTL;
    }
    
    /**
     * Check if metrics reporting is enabled
     * 
     * @return True if metrics reporting is enabled
     */
    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }
    
    /**
     * Get the metrics reporting interval in minutes
     * 
     * @return The metrics reporting interval
     */
    public int getMetricsInterval() {
        return metricsInterval;
    }
}