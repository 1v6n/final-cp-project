package org.concurrent.project;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Vector;
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

      int[][] invariants = {
          { 1, 2, 5, 6, 9, 10, 11 },
          { 1, 3, 4, 6, 9, 10, 11 },
          { 1, 2, 5, 7, 8, 11 },
          { 1, 3, 4, 7, 8, 11 }
      };

      AtomicInteger completedInvariants = new AtomicInteger(0);
      AtomicBoolean running = new AtomicBoolean(true);

      // ----------- Thread 0 -----------
      Vector<Integer> pathForThread0 = new Vector<>();
      pathForThread0.add(0);

      Thread thread0 = new Thread(
          new Threads(pathForThread0, monitor,
              completedInvariants,
              TOTAL_RUNS,
              true, running),
          "Thread-0");

      // ----------- Invariant Threads -----------
      Thread[] invariantThreads = new Thread[invariants.length];

      for (int i = 0; i < invariants.length; i++) {

        Vector<Integer> path = new Vector<>();
        for (int t : invariants[i]) {
          path.add(t);
        }

        invariantThreads[i] = new Thread(
            new Threads(path, monitor,
                completedInvariants,
                TOTAL_RUNS,
                false, running),
            "Invariant-Thread-" + (i + 1));
      }

      // ----------- Start -----------
      thread0.start();
      for (Thread t : invariantThreads) {
        t.start();
      }

      // ----------- Wait for completion -----------
      try {
        while (running.get() && completedInvariants.get() < TOTAL_RUNS) {
          Thread.sleep(5);
        }

        running.set(false);

        // Interrumpimos TODOS los hilos solo para desbloquear esperas
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
        running.set(false);
        thread0.interrupt();
        for (Thread t : invariantThreads) {
          t.interrupt();
        }
        Thread.currentThread().interrupt();
      }

      System.out.println("All threads have completed.");
      System.out.println("Total time elapsed: " +
          (System.currentTimeMillis() - startTime));

      System.out.println("Total invariants runned:" + completedInvariants.get());

      monitor.printPolicySummary();

    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
