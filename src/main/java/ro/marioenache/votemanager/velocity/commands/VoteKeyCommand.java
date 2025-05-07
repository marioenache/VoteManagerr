package ro.marioenache.votemanager.velocity.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import ro.marioenache.votemanager.velocity.VoteManagerVelocity;

import java.util.Collections;
import java.util.List;

/**
 * Command to get the public key for the vote listener
 */
public class VoteKeyCommand implements SimpleCommand {
    private final VoteManagerVelocity plugin;

    /**
     * Create a new vote key command
     * 
     * @param plugin The plugin instance
     */
    public VoteKeyCommand(VoteManagerVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        
        if (source.hasPermission("votemanager.admin")) {
            String publicKey = plugin.getVotifierListener().getPublicKeyBase64();
            
            source.sendMessage(Component.text("--- VoteManager Votifier Public Key ---").color(NamedTextColor.GOLD));
            source.sendMessage(Component.text("Keys Location: " + plugin.getVotifierListener().getKeysFolder().getAbsolutePath()).color(NamedTextColor.YELLOW));
            source.sendMessage(Component.text("Server Address: " + plugin.getVotifierListener().getHost()).color(NamedTextColor.YELLOW));
            source.sendMessage(Component.text("Server Port: " + plugin.getVotifierListener().getPort()).color(NamedTextColor.YELLOW));
            source.sendMessage(Component.text("Public Key:").color(NamedTextColor.YELLOW));
            source.sendMessage(Component.text(publicKey).color(NamedTextColor.GREEN));
            source.sendMessage(Component.text("Use this information when setting up your server on voting sites.").color(NamedTextColor.GRAY));
        } else {
            source.sendMessage(Component.text("You don't have permission to use this command.").color(NamedTextColor.RED));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("votemanager.admin");
    }
}