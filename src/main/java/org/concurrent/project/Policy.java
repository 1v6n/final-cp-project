package org.concurrent.project;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Random;

public class Policy {
    private boolean type;
    private AtomicInteger CounterT2;
    private AtomicInteger CounterT3;

    public Policy(boolean type) {
        this.type = type;
        this.CounterT2 = new AtomicInteger(0);
        this.CounterT3 = new AtomicInteger(0);
    }

    /**
     * Determines which transition to fire based on the given conditions.
     *
     * @param possibleTransitions List of possible transitions to consider.
     * @return The selected transition index.
     */
    public int whichTransition(ArrayList<Integer> possibleTransitions) {
        if (type) {
            // Condition 1: Handle specific transitions (e.g., T2 and T3)
            if (possibleTransitions.get(2) == 1 && possibleTransitions.get(3) == 1) {
                if (CounterT2.get() < CounterT3.get()) {
                    CounterT2.incrementAndGet();
                    return 2; // Prefer transition 2
                } else if (CounterT2.get() > CounterT3.get()) {
                    CounterT3.incrementAndGet();
                    return 3; // Prefer transition 3
                } else {
                    // Condition 3: Random selection if both are equal
                    Random random = new Random();
                    return random.nextBoolean() ? 2 : 3;
                }
            }
        }

        if (!possibleTransitions.isEmpty()) {
            Random random = new Random();
            return possibleTransitions.get(random.nextInt(possibleTransitions.size()));
        }

        throw new IllegalStateException("No possible transitions available.");
    }
}
