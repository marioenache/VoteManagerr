package ro.marioenache.votemanager.spigot.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import ro.marioenache.votemanager.common.model.VoteParty;
import ro.marioenache.votemanager.spigot.VoteManagerSpigot;

import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;

/**
 * Register placeholders for Spigot
 */
public class SpigotPlaceholders extends PlaceholderExpansion {
    private final VoteManagerSpigot plugin;

    /**
     * Create a new Spigot placeholders instance
     * 
     * @param plugin The plugin instance
     */
    public SpigotPlaceholders(VoteManagerSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getAuthor() {
        return "marioenache";
    }

    @Override
    public String getIdentifier() {
        return "votemanager";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        
        if (params.equalsIgnoreCase("voteparty_progress")) {
            String serverName = plugin.getConfigManager().getServerName();
            VoteParty voteParty = plugin.getVoteManager().databaseManager.getVoteParty(serverName);
            return voteParty.getCurrentVotes() + "/" + voteParty.getGoal();
        }
        
        if (params.equalsIgnoreCase("voteparty_percentage")) {
            String serverName = plugin.getConfigManager().getServerName();
            VoteParty voteParty = plugin.getVoteManager().databaseManager.getVoteParty(serverName);
            DecimalFormat df = new DecimalFormat("#.##");
            return df.format(voteParty.getProgress()) + "%";
        }
        
        if (params.equalsIgnoreCase("voteparty_goal")) {
            String serverName = plugin.getConfigManager().getServerName();
            VoteParty voteParty = plugin.getVoteManager().databaseManager.getVoteParty(serverName);
            return String.valueOf(voteParty.getGoal());
        }
        
        if (params.equalsIgnoreCase("voteparty_current")) {
            String serverName = plugin.getConfigManager().getServerName();
            VoteParty voteParty = plugin.getVoteManager().databaseManager.getVoteParty(serverName);
            return String.valueOf(voteParty.getCurrentVotes());
        }
        
        if (params.equalsIgnoreCase("voteparty_last_triggered")) {
            String serverName = plugin.getConfigManager().getServerName();
            VoteParty voteParty = plugin.getVoteManager().databaseManager.getVoteParty(serverName);
            
            if (voteParty.getLastTriggered() == null) {
                return "Never";
            }
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return voteParty.getLastTriggered().format(formatter);
        }
        
        return null;
    }
}