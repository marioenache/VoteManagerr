package ro.marioenache.votemanager.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import ro.marioenache.votemanager.common.VoteManagerCommon;
import ro.marioenache.votemanager.common.database.DatabaseConfig;
import ro.marioenache.votemanager.common.database.DatabaseManager;
import ro.marioenache.votemanager.common.model.Vote;
import ro.marioenache.votemanager.common.model.VoteParty;
import ro.marioenache.votemanager.velocity.commands.VoteKeyCommand;
import ro.marioenache.votemanager.velocity.commands.VoteManagerCommand;
import ro.marioenache.votemanager.velocity.config.VelocityConfigManager;
import ro.marioenache.votemanager.velocity.listener.VotifierListener;
import ro.marioenache.votemanager.velocity.placeholders.VelocityPlaceholders;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Plugin(
        id = "votemanager",
        name = "VoteManager",
        version = "1.0",
        description = "Vote management plugin for Velocity",
        authors = {"marioenache"}
)
public class VoteManagerVelocity extends VoteManagerCommon {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    
    private VelocityConfigManager configManager;
    private VelocityPlaceholders placeholders;
    private VotifierListener votifierListener;
    
    // Thread pool for async operations
    private final ExecutorService asyncExecutor;
    
    // User vote cache to reduce database load during high traffic
    private final Map<UUID, Set<String>> playerVoteServices = new ConcurrentHashMap<>();
    
    // Performance metrics
    private final Map<String, Long> operationTimes = new ConcurrentHashMap<>();
    private final AtomicInteger totalVotesProcessed = new AtomicInteger(0);
    private final AtomicInteger cachedVotesProcessed = new AtomicInteger(0);
    
    private static final String PLUGIN_MESSAGE_CHANNEL = "votemanager:votes";
    private static final MinecraftChannelIdentifier CHANNEL_ID = MinecraftChannelIdentifier.create("votemanager", "votes");

    @Inject
    public VoteManagerVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        
        // Create a thread pool with reasonable limits
        this.asyncExecutor = new ThreadPoolExecutor(
            2,                  // Core pool size
            8,                  // Max pool size
            60, TimeUnit.SECONDS, // Keep alive time
            new LinkedBlockingQueue<>(100), // Queue with limit to prevent memory issues
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "VoteManager-Worker-" + counter.incrementAndGet());
                    thread.setDaemon(true); // Allow JVM to exit
                    return thread;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // If queue is full, run in the calling thread
        );
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        long startTime = System.currentTimeMillis();
        
        // Initialize the config manager
        this.configManager = new VelocityConfigManager(dataDirectory.toFile());
        this.configManager.saveDefaultConfigs();
        
        // Load debug mode setting
        this.debugMode = configManager.isDebugEnabled();
        
        // Initialize the database
        DatabaseConfig dbConfig = configManager.getDatabaseConfig();
        this.databaseManager = new DatabaseManager(new java.util.logging.Logger("VoteManager", null) {
            @Override
            public void log(java.util.logging.LogRecord record) {
                switch (record.getLevel().getName()) {
                    case "SEVERE":
                        logger.error(record.getMessage());
                        break;
                    case "WARNING":
                        logger.warn(record.getMessage());
                        break;
                    case "INFO":
                        logger.info(record.getMessage());
                        break;
                    default:
                        logger.debug(record.getMessage());
                        break;
                }
            }
        }, dbConfig);
        
        // Configure database cache TTLs
        long userCacheTTL = configManager.getUserCacheTTL();
        long votePartyCacheTTL = configManager.getVotePartyCacheTTL();
        long pendingVotesCacheTTL = configManager.getPendingVotesCacheTTL();
        databaseManager.setCacheTTL(userCacheTTL, votePartyCacheTTL, pendingVotesCacheTTL);
        
        // Initialize the common components
        initialize(databaseManager, debugMode);
        
        // Register plugin messaging channels
        server.getChannelRegistrar().register(CHANNEL_ID);
        
        // Register commands
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("votemanager")
                .plugin(this)
                .build(),
            new VoteManagerCommand(this)
        );
        
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("votekey")
                .plugin(this)
                .build(),
            new VoteKeyCommand(this)
        );
        
        // Register vote listener
        this.votifierListener = new VotifierListener(this);
        server.getEventManager().register(this, this.votifierListener);
        
        logger.info("VoteManager vote listener started on " + 
                votifierListener.getHost() + ":" + votifierListener.getPort());
        logger.info("Keys are stored in: " + votifierListener.getKeysFolder().getAbsolutePath());
        
        // Initialize placeholders without dependency
        this.placeholders = new VelocityPlaceholders(this);
        this.placeholders.register();
        
        // Schedule daily reminder reset
        scheduleReminderReset();
        
        // Initialize vote parties for all servers
        initializeVoteParties();
        
        // Start metrics task if enabled
        if (configManager.isMetricsEnabled()) {
            startMetricsTask();
        }
        
        // Schedule periodic cache cleanup
        scheduleCacheCleanup();
        
        long loadTime = System.currentTimeMillis() - startTime;
        logger.info("VoteManager has been initialized in " + loadTime + "ms");
    }
    
    /**
     * Start metrics reporting task
     */
    private void startMetricsTask() {
        server.getScheduler()
               .buildTask(this, this::reportMetrics)
               .repeat(Duration.ofMinutes(configManager.getMetricsInterval()))
               .schedule();
    }
    
    /**
     * Report metrics to console
     */
    private void reportMetrics() {
        Map<String, Object> dbStats = databaseManager.getStatistics();
        
        logger.info("===== VoteManager Metrics =====");
        logger.info("Total votes processed: " + totalVotesProcessed.get());
        logger.info("Cached votes processed: " + cachedVotesProcessed.get());
        logger.info("Cache hit rate: " + (totalVotesProcessed.get() > 0 ? 
            String.format("%.2f%%", (float)cachedVotesProcessed.get() / totalVotesProcessed.get() * 100) : "0%"));
        logger.info("Database queries: " + dbStats.get("totalQueries"));
        logger.info("DB Cache hit rate: " + String.format("%.2f%%", (double)dbStats.get("cacheHitRate") * 100));
        logger.info("DB Cache size: " + dbStats.get("cacheSize"));
        
        logger.info("Operation times (avg ms):");
        for (Map.Entry<String, Long> entry : operationTimes.entrySet()) {
            logger.info("  " + entry.getKey() + ": " + entry.getValue() + "ms");
        }
        
        logger.info("Database query times (avg ms):");
        @SuppressWarnings("unchecked")
        Map<String, Long> queryTimes = (Map<String, Long>) dbStats.get("queryExecutionTimes");
        for (Map.Entry<String, Long> entry : queryTimes.entrySet()) {
            logger.info("  " + entry.getKey() + ": " + entry.getValue() + "ms");
        }
        
        logger.info("Thread pool metrics:");
        ThreadPoolExecutor pool = (ThreadPoolExecutor) asyncExecutor;
        logger.info("  Active threads: " + pool.getActiveCount());
        logger.info("  Pool size: " + pool.getPoolSize());
        logger.info("  Queue size: " + pool.getQueue().size());
        logger.info("  Task count: " + pool.getTaskCount());
        logger.info("  Completed tasks: " + pool.getCompletedTaskCount());
        
        logger.info("===============================");
    }
    
    /**
     * Schedule periodic cache cleanup
     */
    private void scheduleCacheCleanup() {
        server.getScheduler()
               .buildTask(this, () -> {
                   // Clean player vote services cache entries older than 1 day
                   int count = cleanVoteServicesCache();
                   if (debugMode && count > 0) {
                       logger.info("Cleaned " + count + " expired vote services cache entries");
                   }
               })
               .repeat(Duration.ofHours(1))
               .schedule();
    }
    
    /**
     * Clean expired entries from the vote services cache
     */
    private int cleanVoteServicesCache() {
        int count = 0;
        for (Iterator<Map.Entry<UUID, Set<String>>> it = playerVoteServices.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Set<String>> entry = it.next();
            UUID uuid = entry.getKey();
            
            // If player is offline and not seen in a while, remove the cache entry
            Optional<Player> player = server.getPlayer(uuid);
            if (!player.isPresent()) {
                it.remove();
                count++;
            }
        }
        return count;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down VoteManager...");
        
        // Shutdown thread pool gracefully
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Thread pool did not terminate in time, forcing shutdown");
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Thread pool shutdown interrupted", e);
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        if (databaseManager != null) {
            databaseManager.close();
        }
        
        if (placeholders != null) {
            placeholders.unregister();
        }
        
        if (votifierListener != null) {
            votifierListener.stop();
        }
        
        logger.info("VoteManager has been disabled");
    }

    /**
     * Initialize vote parties for all servers
     */
    private void initializeVoteParties() {
        if (!configManager.isVotePartyEnabled()) {
            logger.info("Vote parties are disabled");
            return;
        }
        
        logger.info("Initializing vote parties for all servers");
        List<String> excludedServers = configManager.getExcludedServers();
        
        if (!excludedServers.isEmpty()) {
            logger.info("The following servers are excluded from vote parties: " + excludedServers);
        }
        
        // Use a CompletableFuture to perform database operations asynchronously
        CompletableFuture<Void> initFuture = CompletableFuture.runAsync(() -> {
            for (RegisteredServer server : this.server.getAllServers()) {
                String serverName = server.getServerInfo().getName();
                
                // Skip excluded servers
                if (configManager.isServerExcluded(serverName)) {
                    logger.info("Skipping vote party initialization for excluded server: " + serverName);
                    continue;
                }
                
                int goal = configManager.getVotePartyGoal(serverName);
                
                VoteParty voteParty = databaseManager.getVoteParty(serverName);
                voteParty.setGoal(goal); // Update with configured goal
                databaseManager.updateVoteParty(voteParty);
                
                logger.info("Initialized vote party for server " + serverName + " with goal " + goal + 
                          ", current votes: " + voteParty.getCurrentVotes());
            }
        }, asyncExecutor);
        
        // Handle exceptions
        initFuture.exceptionally(ex -> {
            logger.error("Error initializing vote parties", ex);
            return null;
        });
        
        logger.info("Vote party initialization started");
    }

    /**
     * Schedule the daily reminder reset task
     */
    private void scheduleReminderReset() {
        int resetHour = configManager.getVoteResetHour();
        
        // Calculate time until next reset
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReset = now.withHour(resetHour).withMinute(0).withSecond(0).withNano(0);
        
        if (now.isAfter(nextReset)) {
            nextReset = nextReset.plusDays(1);
        }
        
        // Calculate the delay until next reset
        long delaySeconds = ChronoUnit.SECONDS.between(now, nextReset);
        
        // Schedule the task
        server.getScheduler()
                .buildTask(this, this::resetReminders)
                .delay(Duration.ofSeconds(delaySeconds))
                .repeat(Duration.ofDays(1))
                .schedule();
        
        logger.info("Scheduled reminder reset at " + resetHour + ":00 daily (next in " + delaySeconds + " seconds)");
    }

    /**
     * Reset all reminder flags
     */
    private void resetReminders() {
        // Run in async thread pool
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            int count = databaseManager.resetAllReminded();
            long elapsedTime = System.currentTimeMillis() - startTime;
            
            logger.info("Reset reminded flag for " + count + " users in " + elapsedTime + "ms");
            
            // Track operation time
            operationTimes.put("resetReminders", 
                operationTimes.containsKey("resetReminders") ? 
                (operationTimes.get("resetReminders") + elapsedTime) / 2 : elapsedTime);
        }, asyncExecutor).exceptionally(ex -> {
            logger.error("Error resetting reminders", ex);
            return null;
        });
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPlayerLogin(LoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Run database operations asynchronously
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                // Ensure the user exists in the database
                databaseManager.insertOrUpdateUser(player.getUniqueId(), player.getUsername());
                
                // Register all servers with empty status if not already registered
                for (RegisteredServer server : this.server.getAllServers()) {
                    String serverName = server.getServerInfo().getName();
                    databaseManager.registerServer(uuid, serverName);
                }
                
                // Update any votes with this player name but no UUID
                int updatedVotes = databaseManager.updateVoteUUID(player.getUsername(), player.getUniqueId());
                
                if (debugMode && updatedVotes > 0) {
                    logger.info("Updated " + updatedVotes + " votes with UUID for player " + player.getUsername());
                }
                
                // Clear any cached vote services for this player
                playerVoteServices.remove(uuid);
                
                long elapsedTime = System.currentTimeMillis() - startTime;
                operationTimes.put("playerLogin", 
                    operationTimes.containsKey("playerLogin") ? 
                    (operationTimes.get("playerLogin") + elapsedTime) / 2 : elapsedTime);
            } catch (Exception e) {
                logger.error("Error processing player login", e);
            }
        }, asyncExecutor);
        
        // Schedule vote reminder (separate from database operations)
        server.getScheduler()
                .buildTask(this, () -> checkVoteReminder(player))
                .delay(Duration.ofSeconds(5))
                .schedule();
    }

    /**
     * Check if a player should receive a vote reminder
     * 
     * @param player The player to check
     */
    private void checkVoteReminder(Player player) {
        if (!player.isActive()) {
            return;
        }
        
        // Run asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                // Get the player's reminder status
                int reminded = databaseManager.getReminded(player.getUniqueId());
                
                // If reminded is 0, send a reminder and set to 1
                if (reminded == 0) {
                    Optional<ServerConnection> serverConnection = player.getCurrentServer();
                    if (serverConnection.isPresent()) {
                        String serverName = serverConnection.get().getServerInfo().getName();
                        
                        String reminderCommand = configManager.getVoteReminderCommand()
                                .replace("%player%", player.getUsername());
                        
                        if (debugMode) {
                            logger.info("Sending vote reminder for " + player.getUsername() + ": " + reminderCommand);
                        }
                        
                        // Forward command to the server
                        forwardCommandToServer(serverName, reminderCommand);
                        
                        // Update the reminded flag
                        databaseManager.setReminded(player.getUniqueId(), 1);
                        
                        // Reset server statuses to allow player to receive rewards again
                        resetPlayerServerStatus(player.getUniqueId());
                    }
                }
            } catch (Exception e) {
                logger.error("Error checking vote reminder", e);
            }
        }, asyncExecutor);
    }
    
    /**
     * Reset all server statuses for a player
     * 
     * @param uuid The UUID of the player
     * @return True if the server statuses were reset successfully
     */
    public boolean resetPlayerServerStatus(UUID uuid) {
        if (debugMode) {
            logger.info("Resetting server statuses for player " + uuid);
        }
        
        boolean result = databaseManager.resetUserServers(uuid);
        
        if (debugMode) {
            logger.info("Reset server statuses for player " + uuid + ": " + result);
        }
        
        return result;
    }

    /**
     * Process a vote from Votifier
     * 
     * @param serviceName The name of the vote service
     * @param username The username of the player
     * @return True if the vote was processed successfully
     */
    public boolean processVotifierVote(String serviceName, String username) {
        totalVotesProcessed.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        logger.info("processVotifierVote called with serviceName=" + serviceName + ", username=" + username);
        
        if (debugMode) {
            logger.info("Received vote from " + serviceName + " for player " + username);
        }
        
        // Check if we have a recent vote from this player for this service in cache
        Optional<Player> onlinePlayer = server.getPlayer(username);
        if (onlinePlayer.isPresent() && onlinePlayer.get().isActive()) {
            UUID uuid = onlinePlayer.get().getUniqueId();
            
            Set<String> services = playerVoteServices.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
            
            // If we've already seen this vote recently, it might be a duplicate from different vote sites
            if (services.contains(serviceName)) {
                if (debugMode) {
                    logger.info("Detected potential duplicate vote from " + serviceName + " for " + username);
                }
                cachedVotesProcessed.incrementAndGet();
                return true; // Return success, but skip processing
            }
            
            // Add to cache to prevent duplicates
            services.add(serviceName);
        }
        
        // Process the vote asynchronously
        CompletableFuture<Boolean> processFuture = CompletableFuture.supplyAsync(() -> {
            try {
                UUID uuid = null;
                
                // Find online player to get their UUID
                if (onlinePlayer.isPresent() && onlinePlayer.get().isActive()) {
                    uuid = onlinePlayer.get().getUniqueId();
                } else {
                    // Try to lookup the player's UUID 
                    Map<String, Object> user = databaseManager.getUserByUsername(username);
                    if (user != null) {
                        uuid = (UUID) user.get("uuid");
                    }
                }
                
                // If we don't have a UUID, we can't process the vote
                if (uuid == null) {
                    if (debugMode) {
                        logger.info("No UUID found for player " + username + ", vote will be ignored");
                    }
                    return false;
                }
                
                // Make sure the user exists in the database and increment total votes
                databaseManager.insertOrUpdateUser(uuid, username);
                databaseManager.incrementTotalVotes(uuid);
                
                // Register all servers with empty status if not already registered
                for (RegisteredServer server : this.server.getAllServers()) {
                    String serverName = server.getServerInfo().getName();
                    databaseManager.registerServer(uuid, serverName);
                }
                
                // Process the vote for each server - Global vote counting
                boolean success = true;
                
                // Prepare batch updates for efficiency
                Map<UUID, Map<String, String>> pendingServerUpdates = new HashMap<>();
                Map<String, String> serverStatuses = new HashMap<>();
                
                for (RegisteredServer server : this.server.getAllServers()) {
                    String serverName = server.getServerInfo().getName();
                    
                    // Skip excluded servers for vote party updates
                    if (configManager.isServerExcluded(serverName)) {
                        if (debugMode) {
                            logger.info("Skipping vote party update for excluded server: " + serverName);
                        }
                        continue;
                    }
                    
                    // Check if player is currently on this server
                    boolean onThisServer = false;
                    if (onlinePlayer.isPresent()) {
                        Optional<ServerConnection> currentServer = onlinePlayer.get().getCurrentServer();
                        if (currentServer.isPresent() && currentServer.get().getServerInfo().getName().equals(serverName)) {
                            onThisServer = true;
                        }
                    }
                    
                    // Update server status
                    String status = onThisServer ? "processed" : "pending";
                    serverStatuses.put(serverName, status);
                    
                    // If player is online on this specific server, process vote immediately
                    if (onThisServer) {
                        // Forward vote to the server
                        forwardVoteToServer(serverName, username, serviceName);
                    }
                    
                    // Update vote party regardless of player status (global counting)
                    if (configManager.isVotePartyEnabled()) {
                        VoteParty voteParty = databaseManager.getVoteParty(serverName);
                        
                        if (debugMode) {
                            logger.info("Vote party for " + serverName + ": " + voteParty.getCurrentVotes() + 
                                      "/" + voteParty.getGoal() + " votes");
                        }
                        
                        // Update goal from config if different
                        int configGoal = configManager.getVotePartyGoal(serverName);
                        if (voteParty.getGoal() != configGoal) {
                            voteParty.setGoal(configGoal);
                            if (debugMode) {
                                logger.info("Updated vote party goal for " + serverName + " to " + configGoal);
                            }
                        }
                        
                        // Increment vote count
                        voteParty.incrementVotes();
                        databaseManager.updateVoteParty(voteParty);
                        
                        if (debugMode) {
                            logger.info("Updated vote party for " + serverName + ": " + voteParty.getCurrentVotes() + 
                                      "/" + voteParty.getGoal() + " votes");
                        }
                        
                        // Check if vote party goal has been reached
                        if (voteParty.getCurrentVotes() >= voteParty.getGoal()) {
                            logger.info("Vote party goal reached for server " + serverName + "!");
                            
                            // Trigger vote party
                            triggerVoteParty(serverName);
                            
                            // Reset vote party
                            voteParty.trigger();
                            databaseManager.updateVoteParty(voteParty);
                            logger.info("Vote party triggered and reset for server " + serverName);
                        }
                    }
                }
                
                // Batch update all server statuses at once for efficiency
                if (!serverStatuses.isEmpty()) {
                    pendingServerUpdates.put(uuid, serverStatuses);
                    databaseManager.batchUpdateUserServerStatus(pendingServerUpdates);
                }
                
                return success;
            } catch (Exception e) {
                logger.error("Error processing vote", e);
                return false;
            }
        }, asyncExecutor);
        
        try {
            boolean result = processFuture.get(10, TimeUnit.SECONDS);
            long elapsedTime = System.currentTimeMillis() - startTime;
            
            operationTimes.put("processVote", 
                operationTimes.containsKey("processVote") ? 
                (operationTimes.get("processVote") + elapsedTime) / 2 : elapsedTime);
            
            return result;
        } catch (Exception e) {
            logger.error("Error waiting for vote processing", e);
            return false;
        }
    }

    /**
     * Forward a vote to a specific server
     * 
     * @param serverName The name of the server
     * @param username The username of the player
     * @param serviceName The name of the vote service
     */
    private void forwardVoteToServer(String serverName, String username, String serviceName) {
        Optional<RegisteredServer> serverOpt = server.getServer(serverName);
        if (serverOpt.isPresent()) {
            RegisteredServer registeredServer = serverOpt.get();
            try {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(stream);
                
                // Write message type (1 = vote)
                out.writeByte(1);
                // Write username
                out.writeUTF(username);
                // Write service name
                out.writeUTF(serviceName);
                
                registeredServer.sendPluginMessage(CHANNEL_ID, stream.toByteArray());
                
                if (debugMode) {
                    logger.info("Forwarded vote to server " + serverName);
                }
            } catch (Exception e) {
                logger.error("Failed to forward vote to server " + serverName, e);
            }
        }
    }

    /**
     * Forward a command to a specific server
     * 
     * @param serverName The name of the server
     * @param command The command to forward
     */
    private void forwardCommandToServer(String serverName, String command) {
        Optional<RegisteredServer> serverOpt = server.getServer(serverName);
        if (serverOpt.isPresent()) {
            RegisteredServer registeredServer = serverOpt.get();
            try {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(stream);
                
                // Write message type (2 = command)
                out.writeByte(2);
                // Write command
                out.writeUTF(command);
                
                registeredServer.sendPluginMessage(CHANNEL_ID, stream.toByteArray());
                
                if (debugMode) {
                    logger.info("Forwarded command to server " + serverName + ": " + command);
                }
            } catch (Exception e) {
                logger.error("Failed to forward command to server " + serverName, e);
            }
        }
    }

    @Override
    public void triggerVoteParty(String server) {
        // Check if the server is excluded
        if (configManager.isServerExcluded(server)) {
            if (debugMode) {
                logger.info("Not triggering vote party for excluded server: " + server);
            }
            return;
        }
        
        // Forward vote party trigger to the server
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(stream);
            
            // Write message type (3 = vote party)
            out.writeByte(3);
            
            Optional<RegisteredServer> serverOpt = this.server.getServer(server);
            if (serverOpt.isPresent()) {
                RegisteredServer registeredServer = serverOpt.get();
                registeredServer.sendPluginMessage(CHANNEL_ID, stream.toByteArray());
                
                if (debugMode) {
                    logger.info("Triggered vote party on server " + server);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to trigger vote party on server " + server, e);
        }
    }

    @Override
    public java.util.logging.Logger getLogger() {
        return new java.util.logging.Logger("VoteManager", null) {
            @Override
            public void log(java.util.logging.LogRecord record) {
                switch (record.getLevel().getName()) {
                    case "SEVERE":
                        logger.error(record.getMessage());
                        break;
                    case "WARNING":
                        logger.warn(record.getMessage());
                        break;
                    case "INFO":
                        logger.info(record.getMessage());
                        break;
                    default:
                        logger.debug(record.getMessage());
                        break;
                }
            }
        };
    }

    @Override
    public void reload() {
        configManager.reloadConfigs();
        this.debugMode = configManager.isDebugEnabled();
        
        // Update database cache TTLs on reload
        long userCacheTTL = configManager.getUserCacheTTL();
        long votePartyCacheTTL = configManager.getVotePartyCacheTTL();
        long pendingVotesCacheTTL = configManager.getPendingVotesCacheTTL();
        databaseManager.setCacheTTL(userCacheTTL, votePartyCacheTTL, pendingVotesCacheTTL);
        
        // Reload vote party configurations
        initializeVoteParties();
        
        logger.info("VoteManager has been reloaded");
    }

    public ProxyServer getServer() {
        return server;
    }

    public VelocityConfigManager getConfigManager() {
        return configManager;
    }

    public VotifierListener getVotifierListener() {
        return votifierListener;
    }
    
    public Path getDataDirectory() {
        return dataDirectory;
    }

    public Map<UUID, Set<String>> getPlayerVoteServices() {
        return playerVoteServices;
    }

    public int getTotalVotes(UUID uuid) {
        return databaseManager.getTotalVotes(uuid);
    }
    
    /**
     * Set the vote party goal for a server and save it
     * 
     * @param serverName The server name
     * @param goal The new goal
     * @return True if successful
     */
    public boolean setVotePartyGoal(String serverName, int goal) {
        // Check if the server is excluded
        if (configManager.isServerExcluded(serverName)) {
            logger.info("Cannot set vote party goal for excluded server: " + serverName);
            return false;
        }
        
        VoteParty voteParty = databaseManager.getVoteParty(serverName);
        voteParty.setGoal(goal);
        return databaseManager.updateVoteParty(voteParty);
    }
    
    /**
     * Get the current vote party progress for a server
     * 
     * @param serverName The server name
     * @return The vote party, or null if it doesn't exist
     */
    public VoteParty getVoteParty(String serverName) {
        // Check if the server is excluded
        if (configManager.isServerExcluded(serverName)) {
            logger.info("Cannot get vote party for excluded server: " + serverName);
            return null;
        }
        
        return databaseManager.getVoteParty(serverName);
    }
    
    /**
     * Reset the vote party for a server
     * 
     * @param serverName The server name
     * @return True if successful
     */
    public boolean resetVoteParty(String serverName) {
        // Check if the server is excluded
        if (configManager.isServerExcluded(serverName)) {
            logger.info("Cannot reset vote party for excluded server: " + serverName);
            return false;
        }
        
        VoteParty voteParty = databaseManager.getVoteParty(serverName);
        voteParty.reset();
        return databaseManager.updateVoteParty(voteParty);
    }
}