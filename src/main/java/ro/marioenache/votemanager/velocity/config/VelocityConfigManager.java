package ro.marioenache.votemanager.velocity.config;

import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import ro.marioenache.votemanager.common.config.ConfigManager;
import ro.marioenache.votemanager.common.database.DatabaseConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Config manager for Velocity
 */
public class VelocityConfigManager extends ConfigManager {
    private ConfigurationNode config;
    private final File configFile;
    
    private DatabaseConfig databaseConfig;
    
    private boolean debugEnabled;
    private String voteReminderCommand;
    private int voteResetHour;
    
    // Votifier settings
    private String votifierHost;
    private int votifierPort;
    
    // Vote party settings
    private boolean votePartyEnabled;
    private boolean globalVoteCounting;
    private int defaultVotePartyGoal;
    private Map<String, Integer> serverVotePartyGoals;
    private List<String> excludedServers;
    
    // Cache settings
    private long userCacheTTL;
    private long votePartyCacheTTL;
    private long pendingVotesCacheTTL;
    
    // Performance metrics settings
    private boolean metricsEnabled;
    private int metricsInterval;

    /**
     * Create a new Velocity config manager
     * 
     * @param dataFolder The data folder to store config files in
     */
    public VelocityConfigManager(File dataFolder) {
        super(dataFolder);
        this.configFile = new File(dataFolder, "config.yml");
    }

    @Override
    public void saveDefaultConfigs() {
        // Create the default config if it doesn't exist
        if (!configFile.exists()) {
            try {
                Path path = configFile.toPath();
                Files.createDirectories(path.getParent());
                
                // Copy default config from resources
                try (var inputStream = getClass().getClassLoader().getResourceAsStream("velocity-config.yml")) {
                    if (inputStream != null) {
                        Files.copy(inputStream, path, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        // Create a basic config file manually
                        String defaultConfig = 
                            "# VoteManager Configuration (Velocity)\n" +
                            "debug: false\n\n" +
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
                            "# Vote Settings\n" +
                            "votes:\n" +
                            "  reset-hour: 4       # Hour of day when votes reset (0-23)\n" +
                            "  reminder-command: \"tellraw %player% {\\\"text\\\":\\\"Don't forget to vote today!\\\",\\\"color\\\":\\\"gold\\\"}\"\n\n" +
                            "# Votifier Configuration\n" +
                            "votifier:\n" +
                            "  host: 0.0.0.0      # The host to bind the vote listener to\n" +
                            "  port: 25567         # The port to listen for votes on\n\n" +
                            "# Vote Party Settings\n" +
                            "vote-party:\n" +
                            "  enabled: true\n" +
                            "  global-counting: true    # Count votes globally for all servers\n" +
                            "  default-goal: 25         # Default number of votes to trigger a party\n" +
                            "  server-goals:            # Per-server goals (overrides default)\n" +
                            "    lobby: 20\n" +
                            "    survival: 30\n" +
                            "    skyblock: 25\n" +
                            "  excluded-servers:        # Servers to exclude from vote parties\n" +
                            "    - lobby\n";
                        
                        Files.writeString(path, defaultConfig);
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
        try {
            YAMLConfigurationLoader loader = YAMLConfigurationLoader.builder()
                    .setFile(configFile)
                    .build();
            
            config = loader.load();
            
            // Load database config
            ConfigurationNode dbNode = config.getNode("database");
            String host = dbNode.getNode("host").getString("localhost");
            int port = dbNode.getNode("port").getInt(3306);
            String database = dbNode.getNode("name").getString("votemanager");
            String username = dbNode.getNode("username").getString("root");
            String password = dbNode.getNode("password").getString("password");
            
            databaseConfig = new DatabaseConfig(host, port, database, username, password);
            
            // Load other settings
            debugEnabled = config.getNode("debug").getBoolean(false);
            
            // Load performance settings
            ConfigurationNode performanceNode = config.getNode("performance");
            ConfigurationNode cacheTTLNode = performanceNode.getNode("cache-ttl");
            userCacheTTL = cacheTTLNode.getNode("user").getLong(5 * 60 * 1000);
            votePartyCacheTTL = cacheTTLNode.getNode("vote-party").getLong(60 * 1000);
            pendingVotesCacheTTL = cacheTTLNode.getNode("pending-votes").getLong(30 * 1000);
            
            // Load metrics settings
            ConfigurationNode metricsNode = performanceNode.getNode("metrics");
            metricsEnabled = metricsNode.getNode("enabled").getBoolean(true);
            metricsInterval = metricsNode.getNode("interval").getInt(5);
            
            ConfigurationNode votesNode = config.getNode("votes");
            voteResetHour = votesNode.getNode("reset-hour").getInt(4);
            voteReminderCommand = votesNode.getNode("reminder-command").getString(
                    "tellraw %player% {\"text\":\"Don't forget to vote today!\",\"color\":\"gold\"}");
            
            // Load votifier settings
            ConfigurationNode votifierNode = config.getNode("votifier");
            votifierHost = votifierNode.getNode("host").getString("0.0.0.0");
            votifierPort = votifierNode.getNode("port").getInt(25567);
            
            // Load vote party settings
            ConfigurationNode votePartyNode = config.getNode("vote-party");
            votePartyEnabled = votePartyNode.getNode("enabled").getBoolean(true);
            globalVoteCounting = votePartyNode.getNode("global-counting").getBoolean(true);
            defaultVotePartyGoal = votePartyNode.getNode("default-goal").getInt(25);
            
            // Load server-specific vote party goals
            serverVotePartyGoals = new HashMap<>();
            ConfigurationNode serverGoalsNode = votePartyNode.getNode("server-goals");
            if (!serverGoalsNode.isVirtual()) {
                Map<Object, ? extends ConfigurationNode> serverGoals = serverGoalsNode.getChildrenMap();
                for (Map.Entry<Object, ? extends ConfigurationNode> entry : serverGoals.entrySet()) {
                    String serverName = entry.getKey().toString();
                    int goal = entry.getValue().getInt(defaultVotePartyGoal);
                    serverVotePartyGoals.put(serverName, goal);
                }
            }
            
            // Load excluded servers list
            excludedServers = new ArrayList<>();
            ConfigurationNode excludedServersNode = votePartyNode.getNode("excluded-servers");
            if (!excludedServersNode.isVirtual()) {
                List<String> servers = excludedServersNode.getList(Object::toString);
                excludedServers.addAll(servers);
                
                if (debugEnabled) {
                    System.out.println("Loaded excluded servers for vote parties: " + excludedServers);
                }
            }
            
        } catch (IOException e) {
            e.printStackTrace();
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
     * Get the vote reminder command
     * 
     * @return The vote reminder command
     */
    public String getVoteReminderCommand() {
        return voteReminderCommand;
    }

    /**
     * Get the hour of day when votes reset (0-23)
     * 
     * @return The hour of day when votes reset
     */
    public int getVoteResetHour() {
        return voteResetHour;
    }
    
    /**
     * Get the host to bind the vote listener to
     * 
     * @return The vote listener host
     */
    public String getVotifierHost() {
        return votifierHost;
    }
    
    /**
     * Get the port to listen for votes on
     * 
     * @return The vote listener port
     */
    public int getVotifierPort() {
        return votifierPort;
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
     * Check if global vote counting is enabled
     * 
     * @return True if global vote counting is enabled
     */
    public boolean isGlobalVoteCounting() {
        return globalVoteCounting;
    }
    
    /**
     * Get the default vote party goal
     * 
     * @return The default vote party goal
     */
    public int getDefaultVotePartyGoal() {
        return defaultVotePartyGoal;
    }
    
    /**
     * Get the vote party goal for a server
     * 
     * @param serverName The server name
     * @return The vote party goal
     */
    public int getVotePartyGoal(String serverName) {
        return serverVotePartyGoals.getOrDefault(serverName, defaultVotePartyGoal);
    }
    
    /**
     * Get all server vote party goals
     * 
     * @return Map of server names to vote party goals
     */
    public Map<String, Integer> getServerVotePartyGoals() {
        return serverVotePartyGoals;
    }
    
    /**
     * Get the list of servers to exclude from vote parties
     * 
     * @return List of excluded server names
     */
    public List<String> getExcludedServers() {
        return excludedServers;
    }
    
    /**
     * Check if a server is excluded from vote parties
     * 
     * @param serverName The server name to check
     * @return True if the server is excluded
     */
    public boolean isServerExcluded(String serverName) {
        return excludedServers.contains(serverName);
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