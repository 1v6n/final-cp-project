package org.concurrent.project;

import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

public class Threads implements Runnable {
  private final Vector<Integer> path;
  private final int repeatCount;
  private final Monitor monitor;
  private final AtomicInteger invariantCounter;
  private final boolean isThread0;

  public Threads(Vector<Integer> path, int repeatCount, RdP rdp,
      Monitor monitor, AtomicInteger invariantCounter,
      boolean isThread0) {
    this.path = path;
    this.repeatCount = repeatCount;
    this.monitor = monitor;
    this.invariantCounter = invariantCounter;
    this.isThread0 = isThread0;
  }

  @Override
  public void run() {
    if (isThread0) {
      // Thread 0: Repeatedly fire transition 0
      int transition = path.get(0);
      for (int i = 0; i < repeatCount; i++) {
        if (monitor.fireTransition(transition)) {
          System.out.printf(
              "Thread %s: Successfully fired transition %d (Run %d).%n",
              Thread.currentThread().getName(), transition, i + 1);
        } else {
          System.out.printf(
              "Thread %s: Failed to fire transition %d (Run %d).%n",
              Thread.currentThread().getName(), transition, i + 1);
        }
      }
      System.out.printf("Thread %s: Completed all runs.%n",
          Thread.currentThread().getName());
    } else {
      // Invariant Threads
      while (invariantCounter.get() > 0) {
        invariantCounter.getAndDecrement();
        for (int transition : path) {
          if (monitor.fireTransition(transition)) {
            System.out.printf("Thread %s: Successfully fired transition %d.%n",
                Thread.currentThread().getName(), transition);
          } else {
            System.out.printf("Thread %s: Failed to fire transition %d.%n",
                Thread.currentThread().getName(), transition);
          }
        }
        System.out.printf(
            "Thread %s: Completed one invariant. Remaining: %d.%n",
            Thread.currentThread().getName(), invariantCounter.get());
      }
    }
    System.out.printf("Thread %s: Stopping as all invariants are complete.%n",
        Thread.currentThread().getName());
  }
}
