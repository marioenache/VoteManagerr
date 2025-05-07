package ro.marioenache.votemanager.spigot.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import ro.marioenache.votemanager.common.model.VoteParty;
import ro.marioenache.votemanager.spigot.VoteManagerSpigot;

import java.util.ArrayList;
import java.util.List;

/**
 * Command handler for the VoteManager plugin
 */
public class VoteManagerCommand implements CommandExecutor, TabCompleter {
    private final VoteManagerSpigot plugin;

    /**
     * Create a new VoteManager command
     * 
     * @param plugin The plugin instance
     */
    public VoteManagerCommand(VoteManagerSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "reload":
                if (sender.hasPermission("votemanager.admin.reload")) {
                    plugin.getVoteManager().reload();
                    sender.sendMessage(ChatColor.GREEN + "VoteManager has been reloaded.");
                } else {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to reload the plugin.");
                }
                break;
            case "voteparty":
                handleVotePartyCommand(sender, args);
                break;
            case "help":
                showHelp(sender);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "Unknown command. Use /votemanager help for a list of commands.");
                break;
        }
        
        return true;
    }
    
    /**
     * Handle vote party subcommands
     * 
     * @param sender The command sender
     * @param args The command arguments
     */
    private void handleVotePartyCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("votemanager.admin.voteparty")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to manage vote parties.");
            return;
        }
        
        if (args.length < 2) {
            showVotePartyHelp(sender);
            return;
        }
        
        String serverName = plugin.getConfigManager().getServerName();
        
        switch (args[1].toLowerCase()) {
            case "info":
                VoteParty voteParty = plugin.getVoteManager().databaseManager.getVoteParty(serverName);
                
                sender.sendMessage(ChatColor.GOLD + "=== Vote Party Info ===");
                sender.sendMessage(ChatColor.YELLOW + "Server: " + serverName);
                sender.sendMessage(ChatColor.YELLOW + "Current votes: " + voteParty.getCurrentVotes());
                sender.sendMessage(ChatColor.YELLOW + "Goal: " + voteParty.getGoal());
                sender.sendMessage(ChatColor.YELLOW + "Progress: " + String.format("%.1f", voteParty.getProgress()) + "%");
                
                if (voteParty.getLastTriggered() != null) {
                    sender.sendMessage(ChatColor.YELLOW + "Last triggered: " + voteParty.getLastTriggered());
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "Last triggered: Never");
                }
                
                break;
            case "trigger":
                if (!plugin.getConfigManager().isVotePartyEnabled()) {
                    sender.sendMessage(ChatColor.RED + "Vote parties are disabled in the config.");
                    return;
                }
                
                plugin.getVoteManager().executeVoteParty();
                sender.sendMessage(ChatColor.GREEN + "Vote party triggered!");
                break;
            default:
                showVotePartyHelp(sender);
                break;
        }
    }
    
    /**
     * Show vote party help
     * 
     * @param sender The command sender
     */
    private void showVotePartyHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Vote Party Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/votemanager voteparty info - Show vote party info");
        sender.sendMessage(ChatColor.YELLOW + "/votemanager voteparty trigger - Trigger vote party");
    }

    /**
     * Show the help message
     * 
     * @param sender The command sender
     */
    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "--- VoteManager Help ---");
        sender.sendMessage(ChatColor.YELLOW + "/votemanager reload - Reload the plugin");
        sender.sendMessage(ChatColor.YELLOW + "/votemanager voteparty - Manage vote parties");
        sender.sendMessage(ChatColor.YELLOW + "/votemanager help - Show this help message");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            if (sender.hasPermission("votemanager.admin.reload")) {
                completions.add("reload");
            }
            if (sender.hasPermission("votemanager.admin.voteparty")) {
                completions.add("voteparty");
            }
            completions.add("help");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("voteparty")) {
            if (sender.hasPermission("votemanager.admin.voteparty")) {
                completions.add("info");
                completions.add("trigger");
            }
        }
        
        return completions;
    }
}