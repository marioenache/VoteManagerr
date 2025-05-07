package ro.marioenache.votemanager.common.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ro.marioenache.votemanager.common.model.Vote;
import ro.marioenache.votemanager.common.model.VoteParty;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * Manages database connections and operations for the VoteManager plugin
 */
public class DatabaseManager {
    private final Logger logger;
    private final DatabaseConfig config;
    private HikariDataSource dataSource;
    
    // Cache settings
    private long userCacheTTL = 300000; // 5 minutes
    private long votePartyCacheTTL = 60000; // 1 minute
    private long pendingVotesCacheTTL = 30000; // 30 seconds
    
    // Caches
    private final Map<UUID, Map<String, Object>> userCache = new ConcurrentHashMap<>();
    private final Map<String, VoteParty> votePartyCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Integer>> pendingVotesCache = new ConcurrentHashMap<>();
    
    // Cache expiration timestamps
    private final Map<UUID, Long> userCacheExpiration = new ConcurrentHashMap<>();
    private final Map<String, Long> votePartyCacheExpiration = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingVotesCacheExpiration = new ConcurrentHashMap<>();
    
    // Performance metrics
    private long totalQueries = 0;
    private long cacheHits = 0;
    private final Map<String, Long> queryExecutionTimes = new ConcurrentHashMap<>();
    
    // Scheduler for cache cleanup
    private final ScheduledExecutorService cacheCleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    
    // Prepared statement cache for frequently used queries
    private final Map<String, String> preparedStatementCache = new HashMap<>();

    /**
     * Create a new database manager
     *
     * @param logger The logger to use
     * @param config The database configuration
     */
    public DatabaseManager(Logger logger, DatabaseConfig config) {
        this.logger = logger;
        this.config = config;
        
        // Initialize prepared statement cache
        initPreparedStatementCache();
        
        // Schedule cache cleanup
        cacheCleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredCache, 5, 5, TimeUnit.MINUTES);
    }
    
    /**
     * Initialize the prepared statement cache with common queries
     */
    private void initPreparedStatementCache() {
        preparedStatementCache.put("getUserByUuid", "SELECT * FROM users WHERE uuid = ?");
        preparedStatementCache.put("getUserByUsername", "SELECT * FROM users WHERE username = ?");
        preparedStatementCache.put("updateUserServerStatus", "UPDATE users SET servers = ? WHERE uuid = ?");
        preparedStatementCache.put("getVoteParty", "SELECT * FROM vote_parties WHERE server = ?");
        preparedStatementCache.put("updateVoteParty", "UPDATE vote_parties SET goal = ?, current_votes = ?, last_reset = ?, last_triggered = ? WHERE server = ?");
        preparedStatementCache.put("getPendingVotes", "SELECT servers FROM users WHERE uuid = ?");
        preparedStatementCache.put("clearPendingVotes", "UPDATE users SET servers = ? WHERE uuid = ?");
        preparedStatementCache.put("getTotalVotes", "SELECT total FROM users WHERE uuid = ?");
        preparedStatementCache.put("getReminded", "SELECT reminded FROM users WHERE uuid = ?");
        preparedStatementCache.put("resetAllReminded", "UPDATE users SET reminded = 0");
    }

    /**
     * Initialize the database connection and tables
     */
    public void initialize() {
        setupDataSource();
        createTables();
    }

    /**
     * Set up the database connection pool
     */
    private void setupDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.setPoolName("VoteManagerHikariPool");

        // Connection pool settings - adjusted for better performance
        hikariConfig.setMaximumPoolSize(20);
        hikariConfig.setMinimumIdle(5);
        hikariConfig.setIdleTimeout(300000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setLeakDetectionThreshold(60000);
        
        // Optimized MySQL settings
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "500");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "4096");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        hikariConfig.addDataSourceProperty("useLocalSessionState", "true");
        hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
        hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
        hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
        hikariConfig.addDataSourceProperty("elideSetAutoCommits", "true");
        hikariConfig.addDataSourceProperty("maintainTimeStats", "false");
        hikariConfig.addDataSourceProperty("alwaysSendSetIsolation", "false");
        hikariConfig.addDataSourceProperty("useLocalTransactionState", "true");
        hikariConfig.addDataSourceProperty("cacheCallableStmts", "true");
        hikariConfig.addDataSourceProperty("tcpKeepAlive", "true");

        try {
            dataSource = new HikariDataSource(hikariConfig);
            logger.info("Database connection established");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not initialize database connection", e);
        }
    }

    /**
     * Create the necessary tables if they don't exist
     */
    private void createTables() {
        String usersTable = "CREATE TABLE IF NOT EXISTS users (" +
                "uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                "username VARCHAR(16) NOT NULL, " +
                "servers TEXT NOT NULL, " +
                "reminded TINYINT NOT NULL DEFAULT 0, " +
                "total INT NOT NULL DEFAULT 0, " +
                "last_vote DATETIME NOT NULL, " +
                "INDEX idx_username (username)" +
                ")";

        String votePartiesTable = "CREATE TABLE IF NOT EXISTS vote_parties (" +
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                "server VARCHAR(64) NOT NULL UNIQUE, " +
                "goal INT NOT NULL, " +
                "current_votes INT NOT NULL DEFAULT 0, " +
                "last_reset DATETIME NOT NULL, " +
                "last_triggered DATETIME NULL" +
                ")";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(usersTable);
            stmt.execute(votePartiesTable);
            logger.info("Database tables verified");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not create database tables", e);
        }
    }

    /**
     * Get a connection from the connection pool
     *
     * @return A database connection
     * @throws SQLException If a database access error occurs
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Close the data source and all connections
     */
    public void close() {
        cacheCleanupScheduler.shutdownNow();
        
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Database connection closed");
        }
    }
    
    /**
     * Set cache TTL values
     * 
     * @param userTTL User cache TTL in milliseconds
     * @param votePartyTTL Vote party cache TTL in milliseconds
     * @param pendingVotesTTL Pending votes cache TTL in milliseconds
     */
    public void setCacheTTL(long userTTL, long votePartyTTL, long pendingVotesTTL) {
        this.userCacheTTL = userTTL;
        this.votePartyCacheTTL = votePartyTTL;
        this.pendingVotesCacheTTL = pendingVotesTTL;
        
        logger.info("Cache TTL values set: user=" + userTTL + "ms, voteParty=" + 
                votePartyTTL + "ms, pendingVotes=" + pendingVotesTTL + "ms");
    }
    
    /**
     * Clean up expired cache entries
     */
    private void cleanupExpiredCache() {
        long now = System.currentTimeMillis();
        
        // Clean user cache
        for (Iterator<Map.Entry<UUID, Long>> it = userCacheExpiration.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Long> entry = it.next();
            if (entry.getValue() <= now) {
                userCache.remove(entry.getKey());
                it.remove();
            }
        }
        
        // Clean vote party cache
        for (Iterator<Map.Entry<String, Long>> it = votePartyCacheExpiration.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() <= now) {
                votePartyCache.remove(entry.getKey());
                it.remove();
            }
        }
        
        // Clean pending votes cache
        for (Iterator<Map.Entry<UUID, Long>> it = pendingVotesCacheExpiration.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Long> entry = it.next();
            if (entry.getValue() <= now) {
                pendingVotesCache.remove(entry.getKey());
                it.remove();
            }
        }
    }
    
    /**
     * Get database statistics for performance monitoring
     * 
     * @return A map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("totalQueries", totalQueries);
        stats.put("cacheHits", cacheHits);
        stats.put("cacheHitRate", totalQueries > 0 ? (double)cacheHits / totalQueries : 0);
        
        stats.put("userCacheSize", userCache.size());
        stats.put("votePartyCacheSize", votePartyCache.size());
        stats.put("pendingVotesCacheSize", pendingVotesCache.size());
        
        stats.put("cacheSize", userCache.size() + votePartyCache.size() + pendingVotesCache.size());
        
        stats.put("queryExecutionTimes", new HashMap<>(queryExecutionTimes));
        
        return stats;
    }

    /**
     * Insert a new user or update an existing one
     *
     * @param uuid The user's UUID
     * @param username The user's name
     * @return True if the user was inserted or updated successfully
     */
    public boolean insertOrUpdateUser(UUID uuid, String username) {
        // Check if user exists
        String checkSql = preparedStatementCache.getOrDefault("getUserByUuid", 
                "SELECT uuid FROM users WHERE uuid = ?");

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setString(1, uuid.toString());

            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    // User exists, update username
                    String updateSql = "UPDATE users SET username = ? WHERE uuid = ?";
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, username);
                        updateStmt.setString(2, uuid.toString());
                        boolean result = updateStmt.executeUpdate() > 0;
                        
                        // Update cache if exists
                        if (userCache.containsKey(uuid)) {
                            Map<String, Object> user = userCache.get(uuid);
                            user.put("username", username);
                        }
                        
                        return result;
                    }
                } else {
                    // User doesn't exist, insert new user
                    String insertSql = "INSERT INTO users (uuid, username, servers, reminded, total, last_vote) " +
                            "VALUES (?, ?, '{}', 0, 0, ?)";
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, uuid.toString());
                        insertStmt.setString(2, username);
                        insertStmt.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                        boolean result = insertStmt.executeUpdate() > 0;
                        
                        // Clear cache if insert was successful
                        if (result) {
                            userCache.remove(uuid);
                            userCacheExpiration.remove(uuid);
                        }
                        
                        return result;
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not insert or update user", e);
            return false;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("insertOrUpdateUser", 
                queryExecutionTimes.containsKey("insertOrUpdateUser") ?
                (queryExecutionTimes.get("insertOrUpdateUser") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Register a server for a user with empty status if not already registered
     *
     * @param uuid The user's UUID
     * @param server The server name
     * @return True if the server was registered successfully
     */
    public boolean registerServer(UUID uuid, String server) {
        String sql = "SELECT servers FROM users WHERE uuid = ?";

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String serversJson = rs.getString("servers");
                    JSONObject servers;

                    try {
                        if (serversJson == null || serversJson.isEmpty() || serversJson.equals("{}")) {
                            servers = new JSONObject();
                        } else {
                            JSONParser parser = new JSONParser();
                            servers = (JSONObject) parser.parse(serversJson);
                        }

                        // Only add server if it doesn't exist
                        if (!servers.containsKey(server)) {
                            servers.put(server, "");

                            // Update the user record
                            String updateSql = "UPDATE users SET servers = ? WHERE uuid = ?";
                            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                                updateStmt.setString(1, servers.toJSONString());
                                updateStmt.setString(2, uuid.toString());
                                
                                boolean result = updateStmt.executeUpdate() > 0;
                                
                                // Update cache if exists
                                if (result && userCache.containsKey(uuid)) {
                                    Map<String, Object> user = userCache.get(uuid);
                                    user.put("servers", servers);
                                }
                                
                                return result;
                            }
                        }
                        return true; // Server was already registered
                    } catch (ParseException e) {
                        logger.log(Level.SEVERE, "Could not parse servers JSON", e);
                        return false;
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not register server for user", e);
            return false;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("registerServer", 
                queryExecutionTimes.containsKey("registerServer") ?
                (queryExecutionTimes.get("registerServer") + executionTime) / 2 : executionTime);
        }
        return false;
    }

    /**
     * Update a user's server status
     *
     * @param uuid The user's UUID
     * @param server The server name
     * @param status The status (pending or processed)
     * @return True if the server status was updated successfully
     */
    public boolean updateUserServerStatus(UUID uuid, String server, String status) {
        // Only allow "", "pending", or "processed" as status
        if (!status.equals("") && !status.equals("pending") && !status.equals("processed")) {
            logger.warning("Invalid status: " + status + ". Must be '', 'pending', or 'processed'");
            return false;
        }

        String sql = preparedStatementCache.getOrDefault("getUserByUuid", 
                "SELECT servers FROM users WHERE uuid = ?");
        
        long startTime = System.currentTimeMillis();
        totalQueries++;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String serversJson = rs.getString("servers");
                    JSONObject servers;

                    try {
                        if (serversJson == null || serversJson.isEmpty() || serversJson.equals("{}")) {
                            servers = new JSONObject();
                        } else {
                            JSONParser parser = new JSONParser();
                            servers = (JSONObject) parser.parse(serversJson);
                        }

                        // Update the status
                        servers.put(server, status);

                        // Update the user record
                        String updateSql = preparedStatementCache.getOrDefault("updateUserServerStatus", 
                                "UPDATE users SET servers = ? WHERE uuid = ?");
                        
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                            updateStmt.setString(1, servers.toJSONString());
                            updateStmt.setString(2, uuid.toString());
                            
                            boolean result = updateStmt.executeUpdate() > 0;
                            
                            // Update cache if exists
                            if (result && userCache.containsKey(uuid)) {
                                Map<String, Object> user = userCache.get(uuid);
                                user.put("servers", servers);
                                
                                // Also update pending votes cache if applicable
                                if (pendingVotesCache.containsKey(uuid)) {
                                    Map<String, Integer> pendingVotes = pendingVotesCache.get(uuid);
                                    pendingVotes.put(server, status.equals("pending") ? 1 : 0);
                                }
                            }
                            
                            return result;
                        }
                    } catch (ParseException e) {
                        logger.log(Level.SEVERE, "Could not parse servers JSON", e);
                        return false;
                    }
                } else {
                    // User doesn't exist
                    return false;
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not update user server status", e);
            return false;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("updateUserServerStatus", 
                queryExecutionTimes.containsKey("updateUserServerStatus") ?
                (queryExecutionTimes.get("updateUserServerStatus") + executionTime) / 2 : executionTime);
        }
    }
    
    /**
     * Batch update user server statuses for multiple users/servers
     * 
     * @param updates Map of UUID to map of server name to status
     * @return Number of users updated successfully
     */
    public int batchUpdateUserServerStatus(Map<UUID, Map<String, String>> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
        
        int successCount = 0;
        
        // Process in batches to reduce database overhead
        Map<String, PreparedStatement> preparedStatements = new HashMap<>();
        
        long startTime = System.currentTimeMillis();
        totalQueries += updates.size();
        
        try (Connection conn = getConnection()) {
            // Disable auto-commit for batch operations
            conn.setAutoCommit(false);
            
            for (Map.Entry<UUID, Map<String, String>> entry : updates.entrySet()) {
                UUID uuid = entry.getKey();
                Map<String, String> serverStatuses = entry.getValue();
                
                // Get current servers JSON
                String sql = "SELECT servers FROM users WHERE uuid = ?";
                PreparedStatement stmt = preparedStatements.computeIfAbsent("select", 
                        k -> {
                            try {
                                return conn.prepareStatement(sql);
                            } catch (SQLException e) {
                                logger.log(Level.SEVERE, "Error preparing statement", e);
                                return null;
                            }
                        });
                
                if (stmt == null) continue;
                
                stmt.setString(1, uuid.toString());
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    String serversJson = rs.getString("servers");
                    JSONObject servers;
                    
                    try {
                        if (serversJson == null || serversJson.isEmpty() || serversJson.equals("{}")) {
                            servers = new JSONObject();
                        } else {
                            JSONParser parser = new JSONParser();
                            servers = (JSONObject) parser.parse(serversJson);
                        }
                        
                        // Update all server statuses
                        for (Map.Entry<String, String> serverEntry : serverStatuses.entrySet()) {
                            servers.put(serverEntry.getKey(), serverEntry.getValue());
                        }
                        
                        // Update the user record
                        String updateSql = "UPDATE users SET servers = ? WHERE uuid = ?";
                        PreparedStatement updateStmt = preparedStatements.computeIfAbsent("update", 
                                k -> {
                                    try {
                                        return conn.prepareStatement(updateSql);
                                    } catch (SQLException e) {
                                        logger.log(Level.SEVERE, "Error preparing statement", e);
                                        return null;
                                    }
                                });
                        
                        if (updateStmt == null) continue;
                        
                        updateStmt.setString(1, servers.toJSONString());
                        updateStmt.setString(2, uuid.toString());
                        updateStmt.addBatch();
                        
                        // Update cache if exists
                        if (userCache.containsKey(uuid)) {
                            Map<String, Object> user = userCache.get(uuid);
                            user.put("servers", servers);
                            
                            // Also update pending votes cache if applicable
                            if (pendingVotesCache.containsKey(uuid)) {
                                Map<String, Integer> pendingVotes = pendingVotesCache.get(uuid);
                                for (Map.Entry<String, String> serverEntry : serverStatuses.entrySet()) {
                                    pendingVotes.put(serverEntry.getKey(), 
                                            serverEntry.getValue().equals("pending") ? 1 : 0);
                                }
                            }
                        }
                        
                        successCount++;
                    } catch (ParseException e) {
                        logger.log(Level.SEVERE, "Could not parse servers JSON for " + uuid, e);
                    }
                    
                    rs.close();
                }
            }
            
            // Execute all batches
            if (preparedStatements.containsKey("update")) {
                PreparedStatement updateStmt = preparedStatements.get("update");
                int[] results = updateStmt.executeBatch();
                conn.commit();
            }
            
            // Close all prepared statements
            for (PreparedStatement stmt : preparedStatements.values()) {
                if (stmt != null) {
                    stmt.close();
                }
            }
            
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error in batch update of user server statuses", e);
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("batchUpdateUserServerStatus", 
                queryExecutionTimes.containsKey("batchUpdateUserServerStatus") ?
                (queryExecutionTimes.get("batchUpdateUserServerStatus") + executionTime) / 2 : executionTime);
        }
        
        return successCount;
    }
    
    /**
     * Reset all server statuses for a user to empty
     * 
     * @param uuid The user's UUID
     * @return True if the server statuses were reset successfully
     */
    public boolean resetUserServers(UUID uuid) {
        String sql = "SELECT servers FROM users WHERE uuid = ?";
        
        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String serversJson = rs.getString("servers");
                    JSONObject servers;
                    
                    try {
                        if (serversJson == null || serversJson.isEmpty() || serversJson.equals("{}")) {
                            servers = new JSONObject();
                        } else {
                            JSONParser parser = new JSONParser();
                            servers = (JSONObject) parser.parse(serversJson);
                            
                            // Reset all server statuses to empty
                            for (Object key : servers.keySet()) {
                                servers.put(key, "");
                            }
                            
                            // Update the user record
                            String updateSql = "UPDATE users SET servers = ? WHERE uuid = ?";
                            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                                updateStmt.setString(1, servers.toJSONString());
                                updateStmt.setString(2, uuid.toString());
                                
                                boolean result = updateStmt.executeUpdate() > 0;
                                
                                // Update cache if exists
                                if (result && userCache.containsKey(uuid)) {
                                    Map<String, Object> user = userCache.get(uuid);
                                    user.put("servers", servers);
                                    
                                    // Also clear pending votes cache if applicable
                                    pendingVotesCache.remove(uuid);
                                    pendingVotesCacheExpiration.remove(uuid);
                                }
                                
                                return result;
                            }
                        }
                    } catch (ParseException e) {
                        logger.log(Level.SEVERE, "Could not parse servers JSON", e);
                        return false;
                    }
                }
                return false; // User not found
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not reset user servers", e);
            return false;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("resetUserServers", 
                queryExecutionTimes.containsKey("resetUserServers") ?
                (queryExecutionTimes.get("resetUserServers") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Set the reminded flag for a user
     *
     * @param uuid The user's UUID
     * @param reminded Whether the user has been reminded to vote (0 or 1)
     * @return True if the reminded flag was set successfully
     */
    public boolean setReminded(UUID uuid, int reminded) {
        String sql = "UPDATE users SET reminded = ? WHERE uuid = ?";

        // Check if user has any processed votes before setting reminded to 1
        if (reminded == 1) {
            Map<String, Object> user = getUser(uuid);
            if (user != null) {
                JSONObject servers = (JSONObject) user.get("servers");
                boolean hasProcessedVotes = false;

                for (Object value : servers.values()) {
                    if ("processed".equals(value)) {
                        hasProcessedVotes = true;
                        break;
                    }
                }

                if (!hasProcessedVotes) {
                    logger.info("Not setting reminded to 1 for user " + uuid +
                            " because they have no processed votes");
                    return true; // Don't update if no processed votes
                }
            }
        }

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, reminded);
            stmt.setString(2, uuid.toString());

            boolean result = stmt.executeUpdate() > 0;
            
            // Update cache if exists
            if (result && userCache.containsKey(uuid)) {
                Map<String, Object> user = userCache.get(uuid);
                user.put("reminded", reminded);
            }
            
            return result;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not set reminded flag", e);
            return false;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("setReminded", 
                queryExecutionTimes.containsKey("setReminded") ?
                (queryExecutionTimes.get("setReminded") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Get the reminded status for a user
     *
     * @param uuid The user's UUID
     * @return 1 if the user has been reminded, 0 if not, or -1 if user not found
     */
    public int getReminded(UUID uuid) {
        // Check cache first
        if (userCache.containsKey(uuid)) {
            Map<String, Object> user = userCache.get(uuid);
            cacheHits++;
            return (int) user.get("reminded");
        }
        
        String sql = preparedStatementCache.getOrDefault("getReminded", 
                "SELECT reminded FROM users WHERE uuid = ?");

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("reminded");
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not get reminded status", e);
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("getReminded", 
                queryExecutionTimes.containsKey("getReminded") ?
                (queryExecutionTimes.get("getReminded") + executionTime) / 2 : executionTime);
        }

        return -1;
    }

    /**
     * Reset reminded flag for all users at reset hour
     *
     * @return Number of users updated
     */
    public int resetAllReminded() {
        String sql = preparedStatementCache.getOrDefault("resetAllReminded", 
                "UPDATE users SET reminded = 0");

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            int result = stmt.executeUpdate(sql);
            
            // Clear user cache since we've modified all users
            userCache.clear();
            userCacheExpiration.clear();
            
            return result;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not reset reminded flags", e);
            return 0;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("resetAllReminded", 
                queryExecutionTimes.containsKey("resetAllReminded") ?
                (queryExecutionTimes.get("resetAllReminded") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Increment the total vote count for a user
     *
     * @param uuid The user's UUID
     * @return True if the total vote count was incremented successfully
     */
    public boolean incrementTotalVotes(UUID uuid) {
        String sql = "UPDATE users SET total = total + 1, last_vote = ? WHERE uuid = ?";

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setString(2, uuid.toString());

            boolean result = stmt.executeUpdate() > 0;
            
            // Update cache if exists
            if (result && userCache.containsKey(uuid)) {
                Map<String, Object> user = userCache.get(uuid);
                int total = (int) user.get("total");
                user.put("total", total + 1);
                user.put("lastVote", LocalDateTime.now());
            }
            
            return result;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not increment total votes", e);
            return false;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("incrementTotalVotes", 
                queryExecutionTimes.containsKey("incrementTotalVotes") ?
                (queryExecutionTimes.get("incrementTotalVotes") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Get a user by UUID
     *
     * @param uuid The user's UUID
     * @return A map containing the user's data, or null if the user doesn't exist
     */
    public Map<String, Object> getUser(UUID uuid) {
        // Check cache first
        if (userCache.containsKey(uuid) && 
            userCacheExpiration.containsKey(uuid) && 
            userCacheExpiration.get(uuid) > System.currentTimeMillis()) {
            
            cacheHits++;
            return userCache.get(uuid);
        }
        
        String sql = preparedStatementCache.getOrDefault("getUserByUuid", 
                "SELECT * FROM users WHERE uuid = ?");

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> user = new HashMap<>();
                    user.put("uuid", UUID.fromString(rs.getString("uuid")));
                    user.put("username", rs.getString("username"));

                    // Parse the servers JSON
                    String serversJson = rs.getString("servers");
                    try {
                        if (serversJson == null || serversJson.isEmpty() || serversJson.equals("{}")) {
                            user.put("servers", new JSONObject());
                        } else {
                            JSONParser parser = new JSONParser();
                            JSONObject servers = (JSONObject) parser.parse(serversJson);
                            user.put("servers", servers);
                        }
                    } catch (ParseException e) {
                        logger.log(Level.SEVERE, "Could not parse servers JSON", e);
                        user.put("servers", new JSONObject());
                    }

                    user.put("reminded", rs.getInt("reminded"));
                    user.put("total", rs.getInt("total"));
                    user.put("lastVote", rs.getTimestamp("last_vote").toLocalDateTime());
                    
                    // Cache the result
                    userCache.put(uuid, user);
                    userCacheExpiration.put(uuid, System.currentTimeMillis() + userCacheTTL);

                    return user;
                } else {
                    return null;
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not get user", e);
            return null;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("getUser", 
                queryExecutionTimes.containsKey("getUser") ?
                (queryExecutionTimes.get("getUser") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Get a user by username
     *
     * @param username The user's name
     * @return A map containing the user's data, or null if the user doesn't exist
     */
    public Map<String, Object> getUserByUsername(String username) {
        String sql = preparedStatementCache.getOrDefault("getUserByUsername",
                "SELECT * FROM users WHERE username = ?");

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> user = new HashMap<>();
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    user.put("uuid", uuid);
                    user.put("username", rs.getString("username"));

                    // Parse the servers JSON
                    String serversJson = rs.getString("servers");
                    try {
                        if (serversJson == null || serversJson.isEmpty() || serversJson.equals("{}")) {
                            user.put("servers", new JSONObject());
                        } else {
                            JSONParser parser = new JSONParser();
                            JSONObject servers = (JSONObject) parser.parse(serversJson);
                            user.put("servers", servers);
                        }
                    } catch (ParseException e) {
                        logger.log(Level.SEVERE, "Could not parse servers JSON", e);
                        user.put("servers", new JSONObject());
                    }

                    user.put("reminded", rs.getInt("reminded"));
                    user.put("total", rs.getInt("total"));
                    user.put("lastVote", rs.getTimestamp("last_vote").toLocalDateTime());
                    
                    // Cache the result
                    userCache.put(uuid, user);
                    userCacheExpiration.put(uuid, System.currentTimeMillis() + userCacheTTL);

                    return user;
                } else {
                    return null;
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not get user by username", e);
            return null;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("getUserByUsername", 
                queryExecutionTimes.containsKey("getUserByUsername") ?
                (queryExecutionTimes.get("getUserByUsername") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Save a vote to the database
     *
     * @param vote The vote to save
     * @return True if the vote was saved successfully
     */
    public boolean saveVote(Vote vote) {
        // If UUID is null (user not registered), we ignore this vote
        if (vote.getUuid() == null) {
            logger.info("Ignoring vote with null UUID: " + vote);
            return false;
        }

        // First, make sure the user exists
        if (!insertOrUpdateUser(vote.getUuid(), vote.getPlayerName())) {
            logger.warning("Failed to insert or update user for vote: " + vote);
            return false;
        }

        // Update the server status for this user
        if (!updateUserServerStatus(vote.getUuid(), vote.getServer(), vote.isProcessed() ? "processed" : "pending")) {
            logger.warning("Failed to update server status for vote: " + vote);
            return false;
        }

        // Set reminded to 1 since they've voted
        if (!setReminded(vote.getUuid(), 1)) {
            logger.warning("Failed to update reminded status for vote: " + vote);
        }

        // The vote was processed successfully
        return true;
    }

    /**
     * Update a vote in the database
     *
     * @param vote The vote to update
     * @return True if the vote was updated successfully
     */
    public boolean updateVote(Vote vote) {
        if (vote.getUuid() == null) {
            // We can't update votes for unknown users in the new system
            return false;
        }

        // Update the server status for this user
        return updateUserServerStatus(vote.getUuid(), vote.getServer(), vote.isProcessed() ? "processed" : "pending");
    }

    /**
     * Update the UUID for votes with a specific player name
     *
     * @param playerName The player name to update the UUID for
     * @param uuid The UUID to set
     * @return The number of votes that were updated
     */
    public int updateVoteUUID(String playerName, UUID uuid) {
        // First, check if the user exists
        Map<String, Object> user = getUserByUsername(playerName);

        if (user != null) {
            // User exists, update the UUID
            String sql = "UPDATE users SET uuid = ? WHERE username = ?";

            long startTime = System.currentTimeMillis();
            totalQueries++;
            
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, playerName);

                int result = stmt.executeUpdate();
                
                // Update cache
                if (result > 0) {
                    userCache.remove(user.get("uuid"));
                    userCacheExpiration.remove(user.get("uuid"));
                    
                    // Add entry for new UUID
                    user.put("uuid", uuid);
                    userCache.put(uuid, user);
                    userCacheExpiration.put(uuid, System.currentTimeMillis() + userCacheTTL);
                }
                
                return result;
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Could not update vote UUID", e);
                return 0;
            } finally {
                long executionTime = System.currentTimeMillis() - startTime;
                queryExecutionTimes.put("updateVoteUUID", 
                    queryExecutionTimes.containsKey("updateVoteUUID") ?
                    (queryExecutionTimes.get("updateVoteUUID") + executionTime) / 2 : executionTime);
            }
        } else {
            // User doesn't exist, create a new one
            insertOrUpdateUser(uuid, playerName);
            return 1;
        }
    }

    /**
     * Update the player name for votes with a specific UUID
     *
     * @param uuid The UUID to update the player name for
     * @param playerName The player name to set
     * @return The number of votes that were updated
     */
    public int updateVotePlayerName(UUID uuid, String playerName) {
        // Update the username for this user
        String sql = "UPDATE users SET username = ? WHERE uuid = ?";

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerName);
            stmt.setString(2, uuid.toString());

            int result = stmt.executeUpdate();
            
            // Update cache if exists
            if (result > 0 && userCache.containsKey(uuid)) {
                userCache.get(uuid).put("username", playerName);
            }
            
            return result;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not update vote player name", e);
            return 0;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("updateVotePlayerName", 
                queryExecutionTimes.containsKey("updateVotePlayerName") ?
                (queryExecutionTimes.get("updateVotePlayerName") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Get a vote party for a server
     *
     * @param server The server to get the vote party for
     * @return The vote party, or a new one if none exists
     */
    public VoteParty getVoteParty(String server) {
        // Check cache first
        if (votePartyCache.containsKey(server) && 
            votePartyCacheExpiration.containsKey(server) && 
            votePartyCacheExpiration.get(server) > System.currentTimeMillis()) {
            
            cacheHits++;
            return votePartyCache.get(server);
        }
        
        String sql = preparedStatementCache.getOrDefault("getVoteParty",
                "SELECT * FROM vote_parties WHERE server = ?");

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, server);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int goal = rs.getInt("goal");
                    int currentVotes = rs.getInt("current_votes");
                    LocalDateTime lastReset = rs.getTimestamp("last_reset").toLocalDateTime();
                    LocalDateTime lastTriggered = rs.getTimestamp("last_triggered") != null ?
                            rs.getTimestamp("last_triggered").toLocalDateTime() : null;

                    VoteParty voteParty = new VoteParty(server, goal, currentVotes, lastReset, lastTriggered);
                    
                    // Cache the result
                    votePartyCache.put(server, voteParty);
                    votePartyCacheExpiration.put(server, System.currentTimeMillis() + votePartyCacheTTL);
                    
                    return voteParty;
                } else {
                    // Create a new vote party for this server with default values
                    VoteParty voteParty = new VoteParty(server, 25); // Default goal of 25 votes

                    String insertSql = "INSERT INTO vote_parties (server, goal, current_votes, last_reset) " +
                            "VALUES (?, ?, ?, ?)";

                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, server);
                        insertStmt.setInt(2, voteParty.getGoal());
                        insertStmt.setInt(3, voteParty.getCurrentVotes());
                        insertStmt.setTimestamp(4, Timestamp.valueOf(voteParty.getLastReset()));

                        insertStmt.executeUpdate();
                    }
                    
                    // Cache the result
                    votePartyCache.put(server, voteParty);
                    votePartyCacheExpiration.put(server, System.currentTimeMillis() + votePartyCacheTTL);

                    return voteParty;
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not get vote party for server " + server, e);
            return new VoteParty(server, 25); // Return default on error
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("getVoteParty", 
                queryExecutionTimes.containsKey("getVoteParty") ?
                (queryExecutionTimes.get("getVoteParty") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Update a vote party in the database
     *
     * @param voteParty The vote party to update
     * @return True if the vote party was updated successfully
     */
    public boolean updateVoteParty(VoteParty voteParty) {
        String sql = preparedStatementCache.getOrDefault("updateVoteParty",
                "UPDATE vote_parties SET goal = ?, current_votes = ?, last_reset = ?, last_triggered = ? " +
                "WHERE server = ?");

        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, voteParty.getGoal());
            stmt.setInt(2, voteParty.getCurrentVotes());
            stmt.setTimestamp(3, Timestamp.valueOf(voteParty.getLastReset()));
            stmt.setTimestamp(4, voteParty.getLastTriggered() != null ?
                    Timestamp.valueOf(voteParty.getLastTriggered()) : null);
            stmt.setString(5, voteParty.getServer());

            boolean result = stmt.executeUpdate() > 0;
            
            // Update cache
            if (result) {
                votePartyCache.put(voteParty.getServer(), voteParty);
                votePartyCacheExpiration.put(voteParty.getServer(), System.currentTimeMillis() + votePartyCacheTTL);
            }
            
            return result;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not update vote party", e);
            return false;
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("updateVoteParty", 
                queryExecutionTimes.containsKey("updateVoteParty") ?
                (queryExecutionTimes.get("updateVoteParty") + executionTime) / 2 : executionTime);
        }
    }

    /**
     * Get the number of pending votes for a player on a server
     *
     * @param uuid The UUID of the player
     * @param server The server to get pending votes for
     * @return The number of pending votes (0 or 1)
     */
    public int getPendingVotes(UUID uuid, String server) {
        // Check cache first
        if (pendingVotesCache.containsKey(uuid) && 
            pendingVotesCacheExpiration.containsKey(uuid) && 
            pendingVotesCacheExpiration.get(uuid) > System.currentTimeMillis()) {
            
            Map<String, Integer> serverVotes = pendingVotesCache.get(uuid);
            if (serverVotes.containsKey(server)) {
                cacheHits++;
                return serverVotes.get(server);
            }
        }
        
        Map<String, Object> user = getUser(uuid);

        if (user != null) {
            JSONObject servers = (JSONObject) user.get("servers");

            if (servers.containsKey(server)) {
                String status = (String) servers.get(server);

                // Cache the result
                pendingVotesCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                    .put(server, status.equals("pending") ? 1 : 0);
                pendingVotesCacheExpiration.put(uuid, System.currentTimeMillis() + pendingVotesCacheTTL);
                
                if (status.equals("pending")) {
                    return 1; // In the new system, we only track whether a vote is pending or not
                }
            }
        }

        return 0;
    }

    /**
     * Clear pending votes for a player on a server
     *
     * @param uuid The UUID of the player
     * @param server The server to clear pending votes for
     * @return True if the pending votes were cleared successfully
     */
    public boolean clearPendingVotes(UUID uuid, String server) {
        boolean result = updateUserServerStatus(uuid, server, "processed");
        
        // Update cache
        if (result && pendingVotesCache.containsKey(uuid)) {
            pendingVotesCache.get(uuid).put(server, 0);
        }
        
        return result;
    }

    /**
     * Get the total number of votes for a player across all servers
     *
     * @param uuid The UUID of the player
     * @return The total number of votes
     */
    public int getTotalVotes(UUID uuid) {
        // Check cache first
        if (userCache.containsKey(uuid)) {
            Map<String, Object> user = userCache.get(uuid);
            cacheHits++;
            return (int) user.get("total");
        }
        
        // Fallback to direct query for performance in case user object is large
        String sql = preparedStatementCache.getOrDefault("getTotalVotes",
                "SELECT total FROM users WHERE uuid = ?");
                
        long startTime = System.currentTimeMillis();
        totalQueries++;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total");
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not get total votes", e);
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            queryExecutionTimes.put("getTotalVotes", 
                queryExecutionTimes.containsKey("getTotalVotes") ?
                (queryExecutionTimes.get("getTotalVotes") + executionTime) / 2 : executionTime);
        }

        return 0;
    }

    /**
     * Check if a player has voted today
     *
     * @param uuid The UUID of the player
     * @param resetHour The hour of the day when the vote count resets (0-23)
     * @return True if the player has voted today
     */
    public boolean hasVotedToday(UUID uuid, int resetHour) {
        Map<String, Object> user = getUser(uuid);

        if (user != null) {
            LocalDateTime lastVote = (LocalDateTime) user.get("lastVote");

            // Calculate the start of the voting day based on the reset hour
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startOfVotingDay;

            if (now.getHour() < resetHour) {
                // If current hour is before reset hour, voting day started yesterday
                startOfVotingDay = now.minusDays(1).withHour(resetHour).withMinute(0).withSecond(0).withNano(0);
            } else {
                // Otherwise, voting day started today
                startOfVotingDay = now.withHour(resetHour).withMinute(0).withSecond(0).withNano(0);
            }

            return lastVote.isAfter(startOfVotingDay) || lastVote.isEqual(startOfVotingDay);
        }

        return false;
    }
}