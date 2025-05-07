package ro.marioenache.votemanager.velocity.placeholders;

import ro.marioenache.votemanager.common.model.VoteParty;
import ro.marioenache.votemanager.velocity.VoteManagerVelocity;

import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Register placeholders for Velocity
 */
public class VelocityPlaceholders {
    private final VoteManagerVelocity plugin;

    /**
     * Create a new Velocity placeholders instance
     * 
     * @param plugin The plugin instance
     */
    public VelocityPlaceholders(VoteManagerVelocity plugin) {
        this.plugin = plugin;
    }

    /**
     * Register placeholders
     */
    public void register() {
        // MiniPlaceholders integration removed to avoid dependencies
        plugin.getLogger().info("MiniPlaceholders integration not available");
    }

    /**
     * Unregister placeholders
     */
    public void unregister() {
        // Nothing to do
    }

    /**
     * Get a player placeholder value
     * 
     * @param uuid The UUID of the player
     * @param params The placeholder parameters
     * @return The placeholder value
     */
    public String getPlayerPlaceholder(UUID uuid, String params) {
        return null;
    }

    /**
     * Get a global placeholder value
     * 
     * @param server The server name
     * @param params The placeholder parameters
     * @return The placeholder value
     */
    public String getGlobalPlaceholder(String server, String params) {
        if (params.equalsIgnoreCase("voteparty_progress")) {
            VoteParty voteParty = plugin.databaseManager.getVoteParty(server);
            return voteParty.getCurrentVotes() + "/" + voteParty.getGoal();
        }
        
        if (params.equalsIgnoreCase("voteparty_percentage")) {
            VoteParty voteParty = plugin.databaseManager.getVoteParty(server);
            DecimalFormat df = new DecimalFormat("#.##");
            return df.format(voteParty.getProgress()) + "%";
        }
        
        if (params.equalsIgnoreCase("voteparty_goal")) {
            VoteParty voteParty = plugin.databaseManager.getVoteParty(server);
            return String.valueOf(voteParty.getGoal());
        }
        
        if (params.equalsIgnoreCase("voteparty_current")) {
            VoteParty voteParty = plugin.databaseManager.getVoteParty(server);
            return String.valueOf(voteParty.getCurrentVotes());
        }
        
        if (params.equalsIgnoreCase("voteparty_last_triggered")) {
            VoteParty voteParty = plugin.databaseManager.getVoteParty(server);
            
            if (voteParty.getLastTriggered() == null) {
                return "Never";
            }
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return voteParty.getLastTriggered().format(formatter);
        }
        
        return null;
    }
}