package ro.marioenache.votemanager.spigot.model;

/**
 * Represents a vote party command with chance and delay
 */
public class VotePartyCommand {
    private final String command;
    private final double chance;
    private final long delay;

    /**
     * Create a new vote party command
     * 
     * @param command The command to execute
     * @param chance The chance of execution (0.0 to 1.0)
     * @param delay The delay before execution in ticks
     */
    public VotePartyCommand(String command, double chance, long delay) {
        this.command = command;
        this.chance = chance;
        this.delay = delay;
    }

    /**
     * Get the command
     * 
     * @return The command
     */
    public String getCommand() {
        return command;
    }

    /**
     * Get the chance of execution
     * 
     * @return The chance (0.0 to 1.0)
     */
    public double getChance() {
        return chance;
    }

    /**
     * Get the delay before execution
     * 
     * @return The delay in ticks
     */
    public long getDelay() {
        return delay;
    }
}