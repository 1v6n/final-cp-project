package org.concurrent.project;

import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

public class Threads implements Runnable {
  private Vector<Integer> path;
  private int repeatCount;
  private Monitor monitor;
  private AtomicInteger invariantCounter;
  private boolean isThread0;

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
          runThread0();
      } else {
          runInvariantThread();
      }
  }

  private void runThread0() {
      int transition = path.get(0);
      for (int i = 0; i < repeatCount; i++) {
          monitor.fireTransition(transition);
      }
  }

  private void runInvariantThread() {
      while (true) {
          int remaining = invariantCounter.getAndDecrement();
          if (remaining <= 0) {
              break;
          }
           for (int transition : path) {
              monitor.fireTransition(transition);
          }
      }
  }
}
