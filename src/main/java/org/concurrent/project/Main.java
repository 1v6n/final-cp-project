package org.concurrent.project;

import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.concurrent.project.Policy.Mode;

public class Main {

  private static final int TOTAL_RUNS = 186;
  private static final boolean timed = false;

  public static void main(String[] args) {

    RdP rdP = new RdP();
    Monitor monitor = new Monitor(rdP, timed, Mode.PRIORITY);

    long startTime = System.currentTimeMillis();

    int[][] invariants = {
        { 1, 2, 5, 6, 9, 10, 11 },
        { 1, 3, 4, 6, 9, 10, 11 },
        { 1, 2, 5, 7, 8, 11 },
        { 1, 3, 4, 7, 8, 11 }
    };

    // NUEVA ESTRUCTURA DE CONTROL

    Semaphore invariantPermits = new Semaphore(TOTAL_RUNS);
    Semaphore t0Permits = new Semaphore(TOTAL_RUNS * 10); // margen amplio para habilitaciones

    AtomicInteger completedInvariants = new AtomicInteger(0);

    // Thread T0
    Vector<Integer> pathForThread0 = new Vector<>();
    pathForThread0.add(0);

    Thread thread0 = new Thread(
        new Threads(
            pathForThread0,
            rdP,
            monitor,
            invariantPermits,
            t0Permits,
            completedInvariants,
            TOTAL_RUNS,
            true),
        "Thread-0");

    // Threads de invariantes
    Thread[] invariantThreads = new Thread[invariants.length];

    for (int i = 0; i < invariants.length; i++) {

      Vector<Integer> pathForInvariant = new Vector<>();

      for (int transition : invariants[i]) {
        pathForInvariant.add(transition);
      }

      invariantThreads[i] = new Thread(
          new Threads(
              pathForInvariant,
              rdP,
              monitor,
              invariantPermits,
              t0Permits,
              completedInvariants,
              TOTAL_RUNS,
              false),
          "Invariant-Thread-" + (i + 1));
    }

    // Lanzar hilos
    thread0.start();
    for (Thread thread : invariantThreads) {
      thread.start();
    }

    // Esperar finalización
    try {
      while (completedInvariants.get() < TOTAL_RUNS) {
        Thread.sleep(5);
      }

      // Interrumpimos TODOS los hilos
      thread0.interrupt();
      for (Thread t : invariantThreads) {
        t.interrupt();
      }

      // Esperamos que terminen
      thread0.join();
      for (Thread t : invariantThreads) {
        t.join();
      }

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      e.printStackTrace();
    }

    System.out.println("All threads have completed.");
    System.out.println("Total invariants completed: " + completedInvariants.get());
    System.out.println("Total time elapsed: " + (System.currentTimeMillis() - startTime) + " ms");
    monitor.getPolicy().printStatistics();
  }
}
