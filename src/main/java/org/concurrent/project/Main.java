package org.concurrent.project;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.concurrent.project.Policy.PolicyMode;

/**
 * Punto de entrada de la simulación concurrente de la red de Petri.
 *
 * <p>
 * Inicializa modelo, monitor y pool de hilos (habilitador + invariantes),
 * y espera finalización al completar la cantidad objetivo de invariantes.
 */
public class Main {
  private static final int TOTAL_RUNS = 186;
  private static final boolean timed = false;

  /**
   * Ejecuta la simulación.
   *
   * @param args argumentos de línea de comandos.
   */
  public static void main(String[] args) {

    Path logPath = Paths.get("logs", "run.log");

    try (LogService logService = new LogService(logPath)) {

      RdP rdP = new RdP();
      Monitor monitor = new Monitor(rdP, timed, logService, PolicyMode.PRIORITIZED);

      long startTime = System.currentTimeMillis();
      int[][] invariants = buildInvariants();

      AtomicInteger completedInvariants = new AtomicInteger(0);
      AtomicBoolean running = new AtomicBoolean(true);

      Thread thread0 = createThread0(monitor, completedInvariants, running);
      Thread[] invariantThreads = createInvariantThreads(
          invariants, monitor, completedInvariants, running);

      startThreads(thread0, invariantThreads);

      try {
        awaitCompletion(running, completedInvariants);
        stopThreads(running, thread0, invariantThreads);
        joinThreads(thread0, invariantThreads);
      } catch (InterruptedException e) {
        stopThreads(running, thread0, invariantThreads);
        Thread.currentThread().interrupt();
      }

      printSummary(startTime, completedInvariants, monitor);

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static int[][] buildInvariants() {
    return new int[][] { { 1, 2, 5, 6, 9, 10, 11 },
        { 1, 3, 4, 6, 9, 10, 11 },
        { 1, 2, 5, 7, 8, 11 },
        { 1, 3, 4, 7, 8, 11 } };
  }

  private static Thread createThread0(Monitor monitor,
      AtomicInteger completedInvariants,
      AtomicBoolean running) {
    List<Integer> pathForThread0 = List.of(0);

    return new Thread(new Threads(pathForThread0, monitor, completedInvariants,
        TOTAL_RUNS, true, running),
        "Thread-0");
  }

  private static Thread[] createInvariantThreads(int[][] invariants, Monitor monitor,
      AtomicInteger completedInvariants,
      AtomicBoolean running) {
    Thread[] invariantThreads = new Thread[invariants.length];

    for (int i = 0; i < invariants.length; i++) {
      List<Integer> path = new ArrayList<>();
      for (int transition : invariants[i]) {
        path.add(transition);
      }

      invariantThreads[i] = new Thread(new Threads(path, monitor, completedInvariants, TOTAL_RUNS,
          false, running),
          "Invariant-Thread-" + (i + 1));
    }

    return invariantThreads;
  }

  private static void startThreads(Thread thread0, Thread[] invariantThreads) {
    thread0.start();
    for (Thread thread : invariantThreads) {
      thread.start();
    }
  }

  private static void awaitCompletion(AtomicBoolean running,
      AtomicInteger completedInvariants)
      throws InterruptedException {
    while (running.get() && completedInvariants.get() < TOTAL_RUNS) {
      Thread.sleep(5);
    }
  }

  private static void stopThreads(AtomicBoolean running, Thread thread0,
      Thread[] invariantThreads) {
    running.set(false);

    // Interrumpimos TODOS los hilos solo para desbloquear esperas
    thread0.interrupt();
    for (Thread thread : invariantThreads) {
      thread.interrupt();
    }
  }

  private static void joinThreads(Thread thread0, Thread[] invariantThreads)
      throws InterruptedException {
    thread0.join();
    for (Thread thread : invariantThreads) {
      thread.join();
    }
  }

  private static void printSummary(long startTime,
      AtomicInteger completedInvariants,
      Monitor monitor) {
    System.out.println("All threads have completed.");
    System.out.println("Total time elapsed: " +
        (System.currentTimeMillis() - startTime));
    System.out.println("Total invariants runned:" + completedInvariants.get());
    monitor.printPolicySummary();
  }
}
