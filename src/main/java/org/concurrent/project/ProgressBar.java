package org.concurrent.project;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe progress bar for console output.
 * <p>
 * Displays a visual progress bar that updates periodically without
 * flooding the console with messages.
 */
public class ProgressBar implements AutoCloseable {
    private final int total;
    private final AtomicInteger completed;
    private final int barWidth;
    private final PrintStream out;
    private int lastPercent = -1;
    private boolean completed_printed = false;

    /**
     * Creates a progress bar with the specified total and completed counters.
     *
     * @param total     Total work to be done (constant)
     * @param completed AtomicInteger representing the completed work
     * @param barWidth  Width of the progress bar in characters
     */
    public ProgressBar(int total, AtomicInteger completed, int barWidth) {
        this.total = total;
        this.completed = completed;
        this.barWidth = barWidth;
        this.out = System.out;
    }

    /**
     * Updates the progress bar display if progress has changed.
     * Should be called periodically during execution.
     */
    public synchronized void update() {
        int current = completed.get();
        
        if (total <= 0) return;
        
        int percent = (current * 100) / total;
        
        // Only update if percentage changed or it's the first time
        if (percent != lastPercent || lastPercent == -1) {
            lastPercent = percent;
            render(current, percent);
        }
        
        // Print completion message if done
        if (current >= total && !completed_printed) {
            completed_printed = true;
            out.println();
            out.println("All invariants completed.");
        }
    }

    private void render(int current, int percent) {
        // Clear line and print progress
        out.print("\rExecuting invariants... [");
        
        int filledWidth = (percent * barWidth) / 100;
        
        for (int i = 0; i < barWidth; i++) {
            if (i < filledWidth) {
                out.print('=');
            } else if (i == filledWidth) {
                out.print('>');
            } else {
                out.print(' ');
            }
        }
        
        out.printf("] %d%% (%d/%d)", percent, current, total);
        out.flush();
    }

    /**
     * Closes the progress bar, ensuring a clean line ending.
     */
    @Override
    public void close() {
        out.println();
        out.flush();
    }
}
