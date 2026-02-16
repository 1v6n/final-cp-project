package org.concurrent.project;

import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class Threads implements Runnable {
  private final Vector<Integer> path;
  private final int repeatCount;
  private final int totalInvariants;
  private final Monitor monitor;
  private final Semaphore invariantPermits;
  private final AtomicInteger completedInvariants;
  private final boolean isThread0;

  public Threads(Vector<Integer> path, int repeatCount, RdP rdp,
      Monitor monitor, Semaphore invariantPermits, AtomicInteger completedInvariants,
      int totalInvariants,
      boolean isThread0) {
    this.path = path;
    this.repeatCount = repeatCount;
    this.totalInvariants = totalInvariants;
    this.monitor = monitor;
    this.invariantPermits = invariantPermits;
    this.completedInvariants = completedInvariants;
    this.isThread0 = isThread0;
  }

  @Override
  public void run() {
    if (isThread0) {
      // Thread 0: Keep enabling work until all invariants complete.
      int transition = path.get(0);
      int run = 0;
      while (completedInvariants.get() < totalInvariants
          && !Thread.currentThread().isInterrupted()) {
        run++;
        boolean fired = monitor.fireTransition(
            transition, () -> completedInvariants.get() < totalInvariants);
        if (fired) {
          System.out.printf(
              "Thread %s: Successfully fired transition %d (Run %d).%n",
              Thread.currentThread().getName(), transition, run);
        } else {
          if (completedInvariants.get() >= totalInvariants
              || Thread.currentThread().isInterrupted()) {
            break;
          }
          System.out.printf(
              "Thread %s: Failed to fire transition %d (Run %d).%n",
              Thread.currentThread().getName(), transition, run);
        }
      }
      System.out.printf("Thread %s: Completed all runs.%n",
          Thread.currentThread().getName());
    } else {
      // Invariant Threads: reserve one full invariant atomically before running it.
      while (invariantPermits.tryAcquire()) {
        for (int transition : path) {
          if (monitor.fireTransition(transition)) {
            System.out.printf("Thread %s: Successfully fired transition %d.%n",
                Thread.currentThread().getName(), transition);
          } else {
            System.out.printf("Thread %s: Failed to fire transition %d.%n",
                Thread.currentThread().getName(), transition);
            return;
          }
        }
        int completed = completedInvariants.incrementAndGet();
        int remaining = Math.max(totalInvariants - completed, 0);
        System.out.printf(
            "Thread %s: Completed one invariant. Remaining: %d.%n",
            Thread.currentThread().getName(), remaining);
      }
    }
    if (isThread0) {
      System.out.printf("Thread %s: Stopping worker thread.%n",
          Thread.currentThread().getName());
    } else if (completedInvariants.get() >= totalInvariants) {
      System.out.printf("Thread %s: Stopping as all invariants are complete.%n",
          Thread.currentThread().getName());
    } else {
      System.out.printf("Thread %s: Stopping (no invariant permit available).%n",
          Thread.currentThread().getName());
    }
  }
}
