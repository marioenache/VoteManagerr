package ro.marioenache.votemanager.spigot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import ro.marioenache.votemanager.common.VoteManagerCommon;
import ro.marioenache.votemanager.common.database.DatabaseConfig;
import ro.marioenache.votemanager.common.database.DatabaseManager;
import ro.marioenache.votemanager.common.model.Vote;
import ro.marioenache.votemanager.common.model.VoteParty;
import ro.marioenache.votemanager.common.util.UUIDFetcher;
import ro.marioenache.votemanager.spigot.commands.VoteManagerCommand;
import ro.marioenache.votemanager.spigot.config.SpigotConfigManager;
import ro.marioenache.votemanager.spigot.listener.PlayerListener;
import ro.marioenache.votemanager.spigot.model.VotePartyCommand;
import ro.marioenache.votemanager.spigot.placeholders.SpigotPlaceholders;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class VoteManagerSpigot extends JavaPlugin implements PluginMessageListener {
    private SpigotVoteManager voteManager;
    private SpigotConfigManager configManager;
    private SpigotPlaceholders placeholders;
    
    // Set to track already executed commands per player session
    private final Map<UUID, Set<String>> executedCommandsMap = new ConcurrentHashMap<>();
    
    // Performance metrics
    private long totalProcessedVotes = 0;
    private long totalProcessedCommands = 0;
    private final Map<String, Long> operationTimes = new ConcurrentHashMap<>();
    
    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        
        // Initialize config
        configManager = new SpigotConfigManager(getDataFolder());
        configManager.saveDefaultConfigs();
        
        // Initialize database
        DatabaseConfig dbConfig = configManager.getDatabaseConfig();
        DatabaseManager databaseManager = new DatabaseManager(getLogger(), dbConfig);
        
        // Configure database cache TTLs
        long userCacheTTL = configManager.getUserCacheTTL();
        long votePartyCacheTTL = configManager.getVotePartyCacheTTL();
        long pendingVotesCacheTTL = configManager.getPendingVotesCacheTTL();
        databaseManager.setCacheTTL(userCacheTTL, votePartyCacheTTL, pendingVotesCacheTTL);
        
        // Initialize vote manager
        voteManager = new SpigotVoteManager(this, configManager.isDebugEnabled());
        voteManager.initialize(databaseManager, configManager.isDebugEnabled());
        
        // Register plugin messaging channel
        Bukkit.getServer().getMessenger().registerIncomingPluginChannel(this, "votemanager:votes", this);
        
        // Register commands
        getCommand("votemanager").setExecutor(new VoteManagerCommand(this));
        
        // Register event listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        
        // Initialize placeholders
        placeholders = new SpigotPlaceholders(this);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholders.register();
            getLogger().info("PlaceholderAPI found! Registering placeholders...");
        } else {
            getLogger().info("PlaceholderAPI not found. Placeholders will not be available.");
        }
        
        // Start metrics task
        if (configManager.isMetricsEnabled()) {
            startMetricsTask();
        }
        
        long loadTime = System.currentTimeMillis() - startTime;
        getLogger().info("VoteManager has been enabled in " + loadTime + "ms");
    }
    
    /**
     * Start periodic metrics reporting
     */
    private void startMetricsTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            Map<String, Object> dbStats = voteManager.databaseManager.getStatistics();
            
            getLogger().info("===== VoteManager Metrics =====");
            getLogger().info("Total processed votes: " + totalProcessedVotes);
            getLogger().info("Total processed commands: " + totalProcessedCommands);
            getLogger().info("Database queries: " + dbStats.get("totalQueries"));
            getLogger().info("Cache hit rate: " + String.format("%.2f%%", (double)dbStats.get("cacheHitRate") * 100));
            getLogger().info("Cache size: " + dbStats.get("cacheSize"));
            
            getLogger().info("Operation times (avg ms):");
            for (Map.Entry<String, Long> entry : operationTimes.entrySet()) {
                getLogger().info("  " + entry.getKey() + ": " + entry.getValue() + "ms");
            }
            
            getLogger().info("Database query times (avg ms):");
            @SuppressWarnings("unchecked")
            Map<String, Long> queryTimes = (Map<String, Long>) dbStats.get("queryExecutionTimes");
            for (Map.Entry<String, Long> entry : queryTimes.entrySet()) {
                getLogger().info("  " + entry.getKey() + ": " + entry.getValue() + "ms");
            }
            
            getLogger().info("===============================");
        }, 20 * 60, 20 * 60 * configManager.getMetricsInterval()); // Run every configured minutes
    }
    
    @Override
    public void onDisable() {
        if (voteManager != null) {
            voteManager.shutdown();
        }
        
        if (placeholders != null && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                placeholders.unregister();
            } catch (Exception e) {
                // Ignore, PlaceholderAPI might have issues
            }
        }
        
        Bukkit.getServer().getMessenger().unregisterIncomingPluginChannel(this);
        
        getLogger().info("VoteManager has been disabled");
    }
    
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("votemanager:votes")) {
            return;
        }
        
        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(message);
            DataInputStream in = new DataInputStream(stream);
            
            byte messageType = in.readByte();
            
            switch (messageType) {
                case 1: // Vote
                    String username = in.readUTF();
                    String serviceName = in.readUTF();
                    
                    if (voteManager.isDebugEnabled()) {
                        getLogger().info("Received vote from Velocity for " + username + " from service " + serviceName);
                    }
                    
                    // Process vote asynchronously
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        long startTime = System.currentTimeMillis();
                        voteManager.processPlayerVote(username, serviceName);
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        operationTimes.put("processVote", 
                            operationTimes.containsKey("processVote") ? 
                            (operationTimes.get("processVote") + elapsedTime) / 2 : elapsedTime);
                        totalProcessedVotes++;
                    });
                    break;
                    
                case 2: // Command
                    String command = in.readUTF();
                    
                    if (voteManager.isDebugEnabled()) {
                        getLogger().info("Executing command from Velocity: " + command);
                    }
                    
                    // Commands need to run on the main thread
                    Bukkit.getScheduler().runTask(this, () -> {
                        long startTime = System.currentTimeMillis();
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        operationTimes.put("executeCommand", 
                            operationTimes.containsKey("executeCommand") ? 
                            (operationTimes.get("executeCommand") + elapsedTime) / 2 : elapsedTime);
                        totalProcessedCommands++;
                    });
                    break;
                    
                case 3: // Vote party
                    if (voteManager.isDebugEnabled()) {
                        getLogger().info("Received vote party trigger from Velocity");
                    }
                    
                    // Process vote party async but execute commands sync
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                        long startTime = System.currentTimeMillis();
                        voteManager.executeVoteParty();
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        operationTimes.put("executeVoteParty", 
                            operationTimes.containsKey("executeVoteParty") ? 
                            (operationTimes.get("executeVoteParty") + elapsedTime) / 2 : elapsedTime);
                    });
                    break;
            }
        } catch (Exception e) {
            getLogger().warning("Error processing plugin message: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public SpigotVoteManager getVoteManager() {
        return voteManager;
    }
    
    public SpigotConfigManager getConfigManager() {
        return configManager;
    }
    
    /**
     * Process votes for a player that just joined
     * 
     * @param player The player that joined
     */
    public void processPlayerJoin(Player player) {
        // Run asynchronously to avoid blocking the main thread
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            long startTime = System.currentTimeMillis();
            
            UUID uuid = player.getUniqueId();
            String serverName = configManager.getServerName();
            
            // Clear any existing executed commands for this player
            clearExecutedCommands(uuid);
            
            if (voteManager.isDebugEnabled()) {
                getLogger().info("Processing join for player " + player.getName() + " on server " + serverName);
            }
            
            // Check for pending votes
            int pendingVotes = voteManager.databaseManager.getPendingVotes(uuid, serverName);
            
            if (voteManager.isDebugEnabled()) {
                getLogger().info("Vote status for " + player.getName() + " on server " + serverName + ": " + 
                        (pendingVotes > 0 ? "pending" : "processed"));
            }
            
            // Process pending votes
            if (pendingVotes > 0) {
                if (voteManager.isDebugEnabled()) {
                    getLogger().info("Processing pending votes for " + player.getName() + " on server " + serverName);
                }
                
                // IMPORTANT: First, update the status to processed to prevent duplicate processing
                // This needs to happen BEFORE executing any commands
                boolean updated = voteManager.databaseManager.clearPendingVotes(uuid, serverName);
                
                if (updated && voteManager.isDebugEnabled()) {
                    getLogger().info("Updated status to processed for " + player.getName() + " on server " + serverName);
                }
                
                // Check if they should get permission rewards first
                boolean receivedPermissionRewards = false;
                if (configManager.isPermissionRewardsEnabled()) {
                    // Run on main thread but use CompletableFuture to wait for result
                    CompletableFuture<Boolean> permRewardsFuture = new CompletableFuture<>();
                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            boolean result = voteManager.executePermissionRewards(player);
                            permRewardsFuture.complete(result);
                        } catch (Exception e) {
                            permRewardsFuture.completeExceptionally(e);
                        }
                    });
                    
                    try {
                        receivedPermissionRewards = permRewardsFuture.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        getLogger().warning("Error executing permission rewards: " + e.getMessage());
                        if (voteManager.isDebugEnabled()) {
                            e.printStackTrace();
                        }
                    }
                }
                
                // Only execute default rewards if no permission rewards were given
                if (!receivedPermissionRewards) {
                    // Execute a single reward command set using our deduplication mechanism
                    // Run on main thread but use CompletableFuture to wait for completion
                    CompletableFuture<Void> commandsFuture = new CompletableFuture<>();
                    Bukkit.getScheduler().runTask(this, () -> {
                        try {
                            voteManager.executeVoteCommands(player, "default");
                            commandsFuture.complete(null);
                        } catch (Exception e) {
                            commandsFuture.completeExceptionally(e);
                        }
                    });
                    
                    try {
                        commandsFuture.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        getLogger().warning("Error executing vote commands: " + e.getMessage());
                        if (voteManager.isDebugEnabled()) {
                            e.printStackTrace();
                        }
                    }
                }
                
                if (voteManager.isDebugEnabled()) {
                    getLogger().info("Completed processing pending votes for " + player.getName());
                }
            }
            
            // Clear executed commands when done to prevent memory leaks
            clearExecutedCommands(uuid);
            
            long elapsedTime = System.currentTimeMillis() - startTime;
            operationTimes.put("processPlayerJoin", 
                operationTimes.containsKey("processPlayerJoin") ? 
                (operationTimes.get("processPlayerJoin") + elapsedTime) / 2 : elapsedTime);
        });
    }
    
    /**
     * Track a command as executed for a player
     * 
     * @param uuid The player UUID
     * @param command The command
     * @return true if the command has not been executed yet, false if it was already executed
     */
    public boolean trackCommand(UUID uuid, String command) {
        Set<String> playerCommands = executedCommandsMap.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        return playerCommands.add(command);
    }
    
    /**
     * Clear executed commands for a player
     * 
     * @param uuid The player UUID
     */
    public void clearExecutedCommands(UUID uuid) {
        executedCommandsMap.remove(uuid);
    }
    
    /**
     * Spigot implementation of VoteManagerCommon
     */
    public class SpigotVoteManager extends VoteManagerCommon {
        private final VoteManagerSpigot plugin;
        private final boolean debugMode;
        
        public SpigotVoteManager(VoteManagerSpigot plugin, boolean debugMode) {
            this.plugin = plugin;
            this.debugMode = debugMode;
        }
        
        /**
         * Process a vote for a player
         * 
         * @param username The username of the player
         * @param serviceName The name of the vote service
         * @return True if the vote was processed successfully
         */
        public boolean processPlayerVote(String username, String serviceName) {
            // Get the player if they're online
            Player player = Bukkit.getPlayerExact(username);
            
            if (player != null) {
                // Clear any existing executed commands for this player
                clearExecutedCommands(player.getUniqueId());
                
                // Check if they should get permission rewards first
                boolean receivedPermissionRewards = false;
                if (configManager.isPermissionRewardsEnabled()) {
                    receivedPermissionRewards = executePermissionRewards(player);
                }
                
                // Only execute default rewards if no permission rewards were given
                if (!receivedPermissionRewards) {
                    // Execute rewards
                    executeVoteCommands(player, serviceName);
                }
                
                // Mark the vote as processed in the database
                UUID uuid = player.getUniqueId();
                String serverName = configManager.getServerName();
                databaseManager.updateUserServerStatus(uuid, serverName, "processed");
                
                // Clear executed commands when done
                clearExecutedCommands(player.getUniqueId());
                
                return true;
            } else {
                if (debugMode) {
                    getLogger().info("Player " + username + " is not online, vote will be stored as pending");
                }
                return false;
            }
        }
        
        /**
         * Execute vote commands for a player with deduplication
         * 
         * @param player The player to execute commands for
         * @param serviceName The name of the vote service
         */
        public void executeVoteCommands(Player player, String serviceName) {
            List<String> commands = configManager.getVoteCommands(serviceName);
            
            for (String command : commands) {
                String parsedCommand = command.replace("%player%", player.getName());
                
                // Check if this command has already been executed for this player
                if (trackCommand(player.getUniqueId(), parsedCommand)) {
                    if (debugMode) {
                        getLogger().info("Executing vote command: " + parsedCommand);
                    }
                    
                    Bukkit.getScheduler().runTask(plugin, () -> 
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand)
                    );
                } else if (debugMode) {
                    getLogger().info("Skipping duplicate command: " + parsedCommand);
                }
            }
        }
        
        /**
         * Execute permission rewards for a player with deduplication
         * 
         * @param player The player to execute permission rewards for
         * @return True if any permission rewards were given, false otherwise
         */
        public boolean executePermissionRewards(Player player) {
            Map<String, List<String>> permissionRewards = configManager.getPermissionRewards();
            boolean rewardsGiven = false;
            
            if (debugMode) {
                getLogger().info("Checking permission rewards for " + player.getName());
                getLogger().info("Total permission rewards configured: " + permissionRewards.size());
                for (String perm : permissionRewards.keySet()) {
                    getLogger().info("Available permission reward: " + perm);
                }
            }
            
            for (Map.Entry<String, List<String>> entry : permissionRewards.entrySet()) {
                String permission = entry.getKey();
                
                if (debugMode) {
                    getLogger().info("Checking if " + player.getName() + " has permission: " + permission);
                    getLogger().info("Player has permission: " + player.hasPermission(permission));
                }
                
                // Check for permission using both the literal permission string and by converting dots to underscores
                if (player.hasPermission(permission) || 
                    player.hasPermission(permission.replace('_', '.'))) {
                    List<String> commands = entry.getValue();
                    rewardsGiven = true;
                    
                    if (debugMode) {
                        getLogger().info("Player " + player.getName() + " has permission " + permission + ", executing " + commands.size() + " commands");
                        for (String cmd : commands) {
                            getLogger().info("Permission reward command to execute: " + cmd);
                        }
                    }
                    
                    for (String command : commands) {
                        String parsedCommand = command.replace("%player%", player.getName());
                        
                        // Check if this command has already been executed for this player
                        if (trackCommand(player.getUniqueId(), parsedCommand)) {
                            if (debugMode) {
                                getLogger().info("Executing permission reward command: " + parsedCommand);
                            }
                            
                            Bukkit.getScheduler().runTask(plugin, () -> 
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand)
                            );
                        } else if (debugMode) {
                            getLogger().info("Skipping duplicate permission command: " + parsedCommand);
                        }
                    }
                }
            }
            
            return rewardsGiven;
        }
        
        /**
         * Execute a vote party
         */
        public void executeVoteParty() {
            if (!configManager.isVotePartyEnabled()) {
                getLogger().info("Vote party is disabled in config");
                return;
            }

            // Check if we should use randomized commands
            if (configManager.useRandomizedCommands()) {
                executeRandomizedVoteParty();
            } else {
                executeLegacyVoteParty();
            }
        }

        /**
         * Execute a vote party using the new randomized commands system
         */
        private void executeRandomizedVoteParty() {
            List<VotePartyCommand> commands = configManager.getVotePartyCommandsWithChance();
            if (commands.isEmpty()) {
                getLogger().warning("No randomized vote party commands configured");
                return;
            }

            // Random generator for chances
            Random random = new Random();
            
            // Track accumulated delay for scheduling
            AtomicLong accumulatedDelay = new AtomicLong(0);
            
            // Check if we should only reward players who have voted
            boolean onlyRewardVoters = configManager.isOnlyRewardVoters();
            
            if (onlyRewardVoters) {
                getLogger().info("Vote party will only reward players who have voted");
                
                // Get server name
                String serverName = configManager.getServerName();
                
                // Process each online player in a batch
                List<Player> rewardedPlayers = new ArrayList<>();
                Map<UUID, Map<String, String>> pendingUpdates = new HashMap<>();
                
                // First, find all players who have voted
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    
                    // Get player vote status from database
                    Map<String, Object> user = databaseManager.getUser(uuid);
                    
                    if (user != null) {
                        org.json.simple.JSONObject servers = (org.json.simple.JSONObject) user.get("servers");
                        
                        // Check if player has voted on this server
                        if (servers.containsKey(serverName) && "processed".equals(servers.get(serverName))) {
                            rewardedPlayers.add(player);
                        }
                    }
                }
                
                // Now process all global commands once
                Set<String> executedGlobalCommands = new HashSet<>();
                
                // Reset accumulated delay
                accumulatedDelay.set(0);
                
                // Process global commands first
                for (VotePartyCommand commandData : commands) {
                    final String command = commandData.getCommand();
                    final double chance = commandData.getChance();
                    final long delay = commandData.getDelay();
                    
                    // If command doesn't contain %player% or contains it but is the same when replaced
                    // (i.e., not actually targeting a specific player), it's a global command
                    if (!command.contains("%player%") || 
                        command.equals(command.replace("%player%", "SomeDummyPlayer"))) {
                        
                        // Roll for chance
                        if (random.nextDouble() <= chance) {
                            if (!executedGlobalCommands.contains(command)) {
                                executedGlobalCommands.add(command);
                                
                                // Calculate current delay
                                final long currentDelay = accumulatedDelay.get();
                                
                                if (debugMode) {
                                    getLogger().info("Scheduling global vote party command with delay " + 
                                                  currentDelay + " ticks: " + command);
                                }
                                
                                // Schedule the command execution with delay
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    if (debugMode) {
                                        getLogger().info("Executing global vote party command: " + command);
                                    }
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                                }, currentDelay);
                            }
                        } else if (debugMode) {
                            getLogger().info("Skipping global vote party command due to chance: " + command);
                        }
                        
                        // Accumulate delay for global commands
                        accumulatedDelay.addAndGet(delay);
                    }
                }
                
                // Then process all player-specific commands
                final long globalDelay = accumulatedDelay.get();
                
                // Use a batched approach for player commands to reduce main thread overhead
                Map<Long, List<Runnable>> delayedTasks = new HashMap<>();
                
                for (Player player : rewardedPlayers) {
                    UUID uuid = player.getUniqueId();
                    clearExecutedCommands(uuid);
                    
                    // Reset player-specific accumulated delay
                    AtomicLong playerDelay = new AtomicLong(globalDelay);
                    
                    for (VotePartyCommand commandData : commands) {
                        final String command = commandData.getCommand();
                        final double chance = commandData.getChance();
                        final long delay = commandData.getDelay();
                        
                        // If command contains %player% and changes when replaced
                        // (i.e., actually targeting a specific player), it's a player-specific command
                        if (command.contains("%player%") && 
                            !command.equals(command.replace("%player%", "SomeDummyPlayer"))) {
                            
                            final String parsedCommand = command.replace("%player%", player.getName());
                            
                            // Roll for chance
                            if (random.nextDouble() <= chance) {
                                if (trackCommand(uuid, parsedCommand)) {
                                    // Calculate current delay
                                    final long currentDelay = playerDelay.get();
                                    
                                    if (debugMode) {
                                        getLogger().info("Scheduling vote party command for player " + 
                                                      player.getName() + " with delay " + currentDelay + 
                                                      " ticks: " + parsedCommand);
                                    }
                                    
                                    // Group commands by delay for batch execution
                                    delayedTasks.computeIfAbsent(currentDelay, k -> new ArrayList<>()).add(() -> {
                                        if (debugMode) {
                                            getLogger().info("Executing vote party command for player " + 
                                                          player.getName() + ": " + parsedCommand);
                                        }
                                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand);
                                    });
                                } else if (debugMode) {
                                    getLogger().info("Skipping duplicate vote party command: " + parsedCommand);
                                }
                            } else if (debugMode) {
                                getLogger().info("Skipping vote party command due to chance for player " + 
                                              player.getName() + ": " + parsedCommand);
                            }
                            
                            // Accumulate delay for next command
                            playerDelay.addAndGet(delay);
                        }
                    }
                    
                    // Schedule a task to clear executed commands
                    final long clearDelay = playerDelay.get() + 20; // Add a buffer of 1 second
                    delayedTasks.computeIfAbsent(clearDelay, k -> new ArrayList<>()).add(() -> {
                        clearExecutedCommands(uuid);
                    });
                }
                
                // Schedule all batched tasks
                for (Map.Entry<Long, List<Runnable>> entry : delayedTasks.entrySet()) {
                    final long delay = entry.getKey();
                    final List<Runnable> tasks = entry.getValue();
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        for (Runnable task : tasks) {
                            task.run();
                        }
                    }, delay);
                }
            } else {
                // Execute commands for all players with randomness
                Set<String> executedGlobalCommands = new HashSet<>();
                
                // Reset accumulated delay
                accumulatedDelay.set(0);
                
                // Process global commands first
                for (VotePartyCommand commandData : commands) {
                    final String command = commandData.getCommand();
                    final double chance = commandData.getChance();
                    final long delay = commandData.getDelay();
                    
                    // If command doesn't contain %player% or contains it but is the same when replaced
                    // (i.e., not actually targeting a specific player), it's a global command
                    if (!command.contains("%player%") || 
                        command.equals(command.replace("%player%", "SomeDummyPlayer"))) {
                        
                        // Roll for chance
                        if (random.nextDouble() <= chance) {
                            if (!executedGlobalCommands.contains(command)) {
                                executedGlobalCommands.add(command);
                                
                                // Calculate current delay
                                final long currentDelay = accumulatedDelay.get();
                                
                                if (debugMode) {
                                    getLogger().info("Scheduling global vote party command with delay " + 
                                                  currentDelay + " ticks: " + command);
                                }
                                
                                // Schedule the command execution with delay
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    if (debugMode) {
                                        getLogger().info("Executing global vote party command: " + command);
                                    }
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                                }, currentDelay);
                            }
                        } else if (debugMode) {
                            getLogger().info("Skipping global vote party command due to chance: " + command);
                        }
                        
                        // Accumulate delay for global commands
                        accumulatedDelay.addAndGet(delay);
                    }
                }
                
                // Now process player-specific commands with batching
                final long globalDelay = accumulatedDelay.get();
                Map<Long, List<Runnable>> delayedTasks = new HashMap<>();
                
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    clearExecutedCommands(uuid);
                    
                    // Save the global accumulated delay
                    // Reset player-specific accumulated delay
                    AtomicLong playerDelay = new AtomicLong(globalDelay);
                    
                    for (VotePartyCommand commandData : commands) {
                        final String command = commandData.getCommand();
                        final double chance = commandData.getChance();
                        final long delay = commandData.getDelay();
                        
                        // If command contains %player% and changes when replaced
                        // (i.e., actually targeting a specific player), it's a player-specific command
                        if (command.contains("%player%") && 
                            !command.equals(command.replace("%player%", "SomeDummyPlayer"))) {
                            
                            final String parsedCommand = command.replace("%player%", player.getName());
                            
                            // Roll for chance
                            if (random.nextDouble() <= chance) {
                                if (trackCommand(uuid, parsedCommand)) {
                                    // Calculate current delay
                                    final long currentDelay = playerDelay.get();
                                    
                                    if (debugMode) {
                                        getLogger().info("Scheduling vote party command for player " + 
                                                      player.getName() + " with delay " + currentDelay + 
                                                      " ticks: " + parsedCommand);
                                    }
                                    
                                    // Group commands by delay for batch execution
                                    delayedTasks.computeIfAbsent(currentDelay, k -> new ArrayList<>()).add(() -> {
                                        if (debugMode) {
                                            getLogger().info("Executing vote party command for player " + 
                                                          player.getName() + ": " + parsedCommand);
                                        }
                                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand);
                                    });
                                } else if (debugMode) {
                                    getLogger().info("Skipping duplicate vote party command: " + parsedCommand);
                                }
                            } else if (debugMode) {
                                getLogger().info("Skipping vote party command due to chance for player " + 
                                              player.getName() + ": " + parsedCommand);
                            }
                            
                            // Accumulate delay for next command
                            playerDelay.addAndGet(delay);
                        }
                    }
                    
                    // Schedule a task to clear executed commands
                    final long clearDelay = playerDelay.get() + 20; // Add a buffer of 1 second
                    delayedTasks.computeIfAbsent(clearDelay, k -> new ArrayList<>()).add(() -> {
                        clearExecutedCommands(uuid);
                    });
                }
                
                // Schedule all batched tasks
                for (Map.Entry<Long, List<Runnable>> entry : delayedTasks.entrySet()) {
                    final long delay = entry.getKey();
                    final List<Runnable> tasks = entry.getValue();
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        for (Runnable task : tasks) {
                            task.run();
                        }
                    }, delay);
                }
            }
        }

        /**
         * Execute a vote party using the legacy system
         */
        private void executeLegacyVoteParty() {
            List<String> commands = configManager.getVotePartyCommands();
            Set<String> executedPartyCommands = new HashSet<>();
            
            // Check if we should only reward players who have voted
            boolean onlyRewardVoters = configManager.isOnlyRewardVoters();
            if (onlyRewardVoters) {
                getLogger().info("Vote party will only reward players who have voted");
                
                // Get server name
                String serverName = configManager.getServerName();
                
                // Process players in batches to reduce database load
                List<Player> rewardedPlayers = new ArrayList<>();
                
                // First, find all players who have voted
                CompletableFuture<Void> playerCheckFuture = CompletableFuture.runAsync(() -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        UUID uuid = player.getUniqueId();
                        
                        // Get player vote status from database
                        Map<String, Object> user = databaseManager.getUser(uuid);
                        
                        if (user != null) {
                            org.json.simple.JSONObject servers = (org.json.simple.JSONObject) user.get("servers");
                            
                            // Check if player has voted on this server
                            if (servers.containsKey(serverName) && "processed".equals(servers.get(serverName))) {
                                rewardedPlayers.add(player);
                                
                                // Clear executed commands for this player on the main thread
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    clearExecutedCommands(uuid);
                                });
                            }
                        }
                    }
                });
                
                try {
                    // Wait for player check to complete
                    playerCheckFuture.get(5, TimeUnit.SECONDS);
                    
                    // Now run commands on the main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (Player player : rewardedPlayers) {
                            for (String command : commands) {
                                String parsedCommand = command.replace("%player%", player.getName());
                                
                                // For player-specific commands, deduplicate per player
                                if (parsedCommand.contains(player.getName()) && !parsedCommand.equals(command)) {
                                    if (trackCommand(player.getUniqueId(), parsedCommand)) {
                                        if (debugMode) {
                                            getLogger().info("Executing vote party command for player " + 
                                                          player.getName() + ": " + parsedCommand);
                                        }
                                        
                                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand);
                                    } else if (debugMode) {
                                        getLogger().info("Skipping duplicate vote party command: " + parsedCommand);
                                    }
                                } else {
                                    // For global commands like broadcasts, use the global set
                                    // Replace %player% with player.getName() even in global commands
                                    String parsedGlobalCommand = command.replace("%player%", player.getName());
                                    
                                    if (executedPartyCommands.add(parsedGlobalCommand)) {
                                        if (debugMode) {
                                            getLogger().info("Executing global vote party command: " + parsedGlobalCommand);
                                        }
                                        
                                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedGlobalCommand);
                                    } else if (debugMode) {
                                        getLogger().info("Skipping duplicate global vote party command: " + parsedGlobalCommand);
                                    }
                                }
                            }
                            
                            // Clear executed commands for this player
                            clearExecutedCommands(player.getUniqueId());
                        }
                    });
                } catch (Exception e) {
                    getLogger().warning("Error processing vote party: " + e.getMessage());
                    if (debugMode) {
                        e.printStackTrace();
                    }
                }
            } else {
                // Execute commands for all players
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        UUID uuid = player.getUniqueId();
                        clearExecutedCommands(uuid);
                        
                        for (String command : commands) {
                            String parsedCommand = command.replace("%player%", player.getName());
                            
                            // For player-specific commands, deduplicate per player
                            if (parsedCommand.contains(player.getName()) && !parsedCommand.equals(command)) {
                                if (trackCommand(player.getUniqueId(), parsedCommand)) {
                                    if (debugMode) {
                                        getLogger().info("Executing vote party command for player " + 
                                                      player.getName() + ": " + parsedCommand);
                                    }
                                    
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand);
                                } else if (debugMode) {
                                    getLogger().info("Skipping duplicate vote party command: " + parsedCommand);
                                }
                            } else {
                                // For global commands, use a global set for deduplication
                                if (executedPartyCommands.add(parsedCommand)) {
                                    if (debugMode) {
                                        getLogger().info("Executing global vote party command: " + parsedCommand);
                                    }
                                    
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand);
                                } else if (debugMode) {
                                    getLogger().info("Skipping duplicate global vote party command: " + parsedCommand);
                                }
                            }
                        }
                        
                        clearExecutedCommands(uuid);
                    }
                });
            }
        }
        
        /**
         * Shut down the vote manager
         */
        public void shutdown() {
            if (databaseManager != null) {
                databaseManager.close();
            }
        }
        
        @Override
        public void triggerVoteParty(String server) {
            if (server.equals(configManager.getServerName())) {
                executeVoteParty();
            }
        }
        
        @Override
        public Logger getLogger() {
            return plugin.getLogger();
        }
        
        @Override
        public void reload() {
            plugin.configManager.reloadConfigs();
            
            // Update database cache TTLs on reload
            long userCacheTTL = configManager.getUserCacheTTL();
            long votePartyCacheTTL = configManager.getVotePartyCacheTTL();
            long pendingVotesCacheTTL = configManager.getPendingVotesCacheTTL();
            databaseManager.setCacheTTL(userCacheTTL, votePartyCacheTTL, pendingVotesCacheTTL);
            
            plugin.getLogger().info("VoteManager has been reloaded");
        }
        
        @Override
        public boolean isDebugEnabled() {
            return debugMode;
        }
    }
}