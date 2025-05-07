package ro.marioenache.votemanager.common.model;

import java.time.LocalDateTime;

/**
 * Represents a vote party for a specific server
 */
public class VoteParty {
    private final String server;
    private int goal;
    private int currentVotes;
    private LocalDateTime lastReset;
    private LocalDateTime lastTriggered;

    /**
     * Create a new vote party
     * 
     * @param server The server this vote party is for
     * @param goal The goal to reach to trigger the vote party
     */
    public VoteParty(String server, int goal) {
        this.server = server;
        this.goal = goal;
        this.currentVotes = 0;
        this.lastReset = LocalDateTime.now();
        this.lastTriggered = null;
    }

    /**
     * Create a vote party with all properties
     * 
     * @param server The server this vote party is for
     * @param goal The goal to reach to trigger the vote party
     * @param currentVotes The current number of votes
     * @param lastReset The last time the vote party was reset
     * @param lastTriggered The last time the vote party was triggered
     */
    public VoteParty(String server, int goal, int currentVotes, 
                    LocalDateTime lastReset, LocalDateTime lastTriggered) {
        this.server = server;
        this.goal = goal;
        this.currentVotes = currentVotes;
        this.lastReset = lastReset;
        this.lastTriggered = lastTriggered;
    }

    public String getServer() {
        return server;
    }

    public int getGoal() {
        return goal;
    }

    public void setGoal(int goal) {
        this.goal = goal;
    }

    public int getCurrentVotes() {
        return currentVotes;
    }

    public void setCurrentVotes(int currentVotes) {
        this.currentVotes = currentVotes;
    }

    public void incrementVotes() {
        this.currentVotes++;
    }

    public LocalDateTime getLastReset() {
        return lastReset;
    }

    public void setLastReset(LocalDateTime lastReset) {
        this.lastReset = lastReset;
    }

    public LocalDateTime getLastTriggered() {
        return lastTriggered;
    }

    public void setLastTriggered(LocalDateTime lastTriggered) {
        this.lastTriggered = lastTriggered;
    }

    /**
     * Reset the vote party
     */
    public void reset() {
        this.currentVotes = 0;
        this.lastReset = LocalDateTime.now();
    }

    /**
     * Trigger the vote party
     */
    public void trigger() {
        this.lastTriggered = LocalDateTime.now();
        reset();
    }

    /**
     * Get the progress of the vote party
     * 
     * @return The progress as a percentage
     */
    public double getProgress() {
        return (double) currentVotes / goal * 100;
    }
}