package ro.marioenache.votemanager.common;

import ro.marioenache.votemanager.common.database.DatabaseManager;
import ro.marioenache.votemanager.common.model.VoteParty;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Common functionality shared between Velocity and Spigot implementations
 */
public abstract class VoteManagerCommon {
    public DatabaseManager databaseManager; // Changed from protected to public
    protected boolean debugMode;
    
    /**
     * Initialize the VoteManager common components
     * 
     * @param databaseManager The database manager to use
     * @param debugMode Whether debug mode is enabled
     */
    public void initialize(DatabaseManager databaseManager, boolean debugMode) {
        this.databaseManager = databaseManager;
        this.debugMode = debugMode;
        
        // Initialize the database connection and tables
        this.databaseManager.initialize();
    }
    
    /**
     * Trigger a vote party for a server
     * 
     * @param server The server to trigger the vote party for
     */
    public abstract void triggerVoteParty(String server);
    
    /**
     * Get the logger for the plugin
     * 
     * @return The logger
     */
    public abstract Logger getLogger();
    
    /**
     * Reload the plugin configuration
     */
    public abstract void reload();
    
    /**
     * Check if debug mode is enabled
     * 
     * @return True if debug mode is enabled
     */
    public boolean isDebugEnabled() {
        return debugMode;
    }
}