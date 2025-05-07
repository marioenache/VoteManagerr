package ro.marioenache.votemanager.spigot.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ro.marioenache.votemanager.common.util.UUIDFetcher;
import ro.marioenache.votemanager.spigot.VoteManagerSpigot;

/**
 * Listener for player events
 */
public class PlayerListener implements Listener {
    private final VoteManagerSpigot plugin;

    /**
     * Create a new player listener
     * 
     * @param plugin The plugin instance
     */
    public PlayerListener(VoteManagerSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Update the UUID for this player in the database
        String playerName = event.getPlayer().getName();
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        
        // Make sure the user exists in the database
        plugin.getVoteManager().databaseManager.insertOrUpdateUser(uuid, playerName);
        
        // Cache the UUID for this player
        UUIDFetcher.cacheUUID(event.getPlayer().getName(), event.getPlayer().getUniqueId());
        
        // Process any pending votes
        plugin.processPlayerJoin(event.getPlayer());
    }
}