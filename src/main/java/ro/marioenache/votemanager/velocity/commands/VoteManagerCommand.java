package ro.marioenache.votemanager.velocity.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import ro.marioenache.votemanager.common.model.VoteParty;
import ro.marioenache.votemanager.velocity.VoteManagerVelocity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command handler for the VoteManager plugin
 */
public class VoteManagerCommand implements SimpleCommand {
    private final VoteManagerVelocity plugin;

    /**
     * Create a new VoteManager command
     * 
     * @param plugin The plugin instance
     */
    public VoteManagerCommand(VoteManagerVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        if (args.length == 0) {
            showHelp(source);
            return;
        }
        
        switch (args[0].toLowerCase()) {
            case "reload":
                if (source.hasPermission("votemanager.admin.reload")) {
                    plugin.reload();
                    source.sendMessage(Component.text("VoteManager has been reloaded.").color(NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text("You don't have permission to reload the plugin.").color(NamedTextColor.RED));
                }
                break;
            case "voteparty":
                handleVotePartyCommand(source, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "help":
                showHelp(source);
                break;
            default:
                source.sendMessage(Component.text("Unknown command. Use /votemanager help for a list of commands.").color(NamedTextColor.RED));
                break;
        }
    }

    /**
     * Handle vote party subcommands
     * 
     * @param source The command source
     * @param args The command arguments
     */
    private void handleVotePartyCommand(CommandSource source, String[] args) {
        if (!source.hasPermission("votemanager.admin.voteparty")) {
            source.sendMessage(Component.text("You don't have permission to manage vote parties.").color(NamedTextColor.RED));
            return;
        }
        
        if (args.length == 0) {
            showVotePartyHelp(source);
            return;
        }
        
        switch (args[0].toLowerCase()) {
            case "info":
                if (args.length < 2) {
                    source.sendMessage(Component.text("Please specify a server name.").color(NamedTextColor.RED));
                    return;
                }
                
                String serverName = args[1];
                VoteParty voteParty = plugin.getVoteParty(serverName);
                if (voteParty == null) {
                    source.sendMessage(Component.text("Server not found: " + serverName).color(NamedTextColor.RED));
                    return;
                }
                
                source.sendMessage(Component.text("=== Vote Party Info for " + serverName + " ===").color(NamedTextColor.GOLD));
                source.sendMessage(Component.text("Current votes: " + voteParty.getCurrentVotes()).color(NamedTextColor.YELLOW));
                source.sendMessage(Component.text("Goal: " + voteParty.getGoal()).color(NamedTextColor.YELLOW));
                source.sendMessage(Component.text("Progress: " + String.format("%.1f", voteParty.getProgress()) + "%").color(NamedTextColor.YELLOW));
                
                if (voteParty.getLastTriggered() != null) {
                    source.sendMessage(Component.text("Last triggered: " + voteParty.getLastTriggered()).color(NamedTextColor.YELLOW));
                } else {
                    source.sendMessage(Component.text("Last triggered: Never").color(NamedTextColor.YELLOW));
                }
                break;
            case "set":
                if (args.length < 3) {
                    source.sendMessage(Component.text("Usage: /votemanager voteparty set <server> <goal>").color(NamedTextColor.RED));
                    return;
                }
                
                serverName = args[1];
                int goal;
                try {
                    goal = Integer.parseInt(args[2]);
                    if (goal <= 0) {
                        source.sendMessage(Component.text("Goal must be a positive number.").color(NamedTextColor.RED));
                        return;
                    }
                } catch (NumberFormatException e) {
                    source.sendMessage(Component.text("Goal must be a number.").color(NamedTextColor.RED));
                    return;
                }
                
                boolean success = plugin.setVotePartyGoal(serverName, goal);
                if (success) {
                    source.sendMessage(Component.text("Vote party goal for " + serverName + " set to " + goal).color(NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text("Failed to set vote party goal. Server not found?").color(NamedTextColor.RED));
                }
                break;
            case "reset":
                if (args.length < 2) {
                    source.sendMessage(Component.text("Please specify a server name.").color(NamedTextColor.RED));
                    return;
                }
                
                serverName = args[1];
                success = plugin.resetVoteParty(serverName);
                if (success) {
                    source.sendMessage(Component.text("Vote party progress for " + serverName + " has been reset.").color(NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text("Failed to reset vote party. Server not found?").color(NamedTextColor.RED));
                }
                break;
            case "trigger":
                if (args.length < 2) {
                    source.sendMessage(Component.text("Please specify a server name.").color(NamedTextColor.RED));
                    return;
                }
                
                serverName = args[1];
                plugin.triggerVoteParty(serverName);
                source.sendMessage(Component.text("Vote party triggered for " + serverName).color(NamedTextColor.GREEN));
                break;
            default:
                showVotePartyHelp(source);
                break;
        }
    }
    
    /**
     * Show vote party help
     * 
     * @param source The command source
     */
    private void showVotePartyHelp(CommandSource source) {
        source.sendMessage(Component.text("=== Vote Party Commands ===").color(NamedTextColor.GOLD));
        source.sendMessage(Component.text("/votemanager voteparty info <server> - Show vote party info").color(NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/votemanager voteparty set <server> <goal> - Set vote party goal").color(NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/votemanager voteparty reset <server> - Reset vote party progress").color(NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/votemanager voteparty trigger <server> - Trigger vote party").color(NamedTextColor.YELLOW));
    }

    /**
     * Show the help message
     * 
     * @param source The command source
     */
    private void showHelp(CommandSource source) {
        source.sendMessage(Component.text("--- VoteManager Help ---").color(NamedTextColor.GOLD));
        source.sendMessage(Component.text("/votemanager reload - Reload the plugin").color(NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/votemanager voteparty - Manage vote parties").color(NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/votemanager help - Show this help message").color(NamedTextColor.YELLOW));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        List<String> suggestions = new ArrayList<>();
        
        if (args.length == 1) {
            if (source.hasPermission("votemanager.admin.reload")) {
                suggestions.add("reload");
            }
            
            if (source.hasPermission("votemanager.admin.voteparty")) {
                suggestions.add("voteparty");
            }
            
            suggestions.add("help");
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("voteparty")) {
            if (args.length == 2) {
                suggestions.add("info");
                suggestions.add("set");
                suggestions.add("reset");
                suggestions.add("trigger");
            } else if (args.length == 3) {
                // Suggest server names
                List<String> serverNames = plugin.getServer().getAllServers().stream()
                        .map(server -> server.getServerInfo().getName())
                        .collect(Collectors.toList());
                suggestions.addAll(serverNames);
            }
        }
        
        return suggestions;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("votemanager.admin");
    }
}