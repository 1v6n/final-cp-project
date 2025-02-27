package org.concurrent.project;

import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final int TOTAL_RUNS = 186;

    public static void main(String[] args) {
        RdP rdP = new RdP();
        Monitor monitor = new Monitor(rdP);

        int[][] invariants = {
                {1, 2, 5, 6, 9, 10, 11},
                {1, 3, 4, 6, 9, 10, 11},
                {1, 2, 5, 7, 8, 11},
                {1, 3, 4, 7, 8, 11}
        };

        AtomicInteger invariantCounter = new AtomicInteger(TOTAL_RUNS);
        Vector<Integer> pathForThread0 = new Vector<>();
        pathForThread0.add(0);
        Thread thread0 = new Thread(new Threads(pathForThread0, TOTAL_RUNS, rdP, monitor, invariantCounter, true), "Thread-0");

        Thread[] invariantThreads = new Thread[invariants.length];
        for (int i = 0; i < invariants.length; i++) {
            Vector<Integer> pathForInvariant = new Vector<>();
            for (int transition : invariants[i]) {
                pathForInvariant.add(transition);
            }
            invariantThreads[i] = new Thread(new Threads(pathForInvariant, 0, rdP, monitor, invariantCounter, false),
                    "Invariant-Thread-" + (i + 1));
        }

        thread0.start();
        for (Thread thread : invariantThreads) {
            thread.start();
        }

        try {
            thread0.join();
            for (Thread thread : invariantThreads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }

        System.out.println("All threads have completed.");
    }
}
