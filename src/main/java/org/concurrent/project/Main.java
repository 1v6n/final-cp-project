package org.concurrent.project;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.concurrent.project.Policy.PolicyMode;

/**
 * Entry point for the concurrent Petri net simulation.
 */
public class Main {
  private static final int TOTAL_RUNS = 186;
  private static final boolean timed = false;

  private record WorkerSpec(String name, List<Integer> path,
      boolean countsCompletion) {
  }

  public static void main(String[] args) {
    Path logPath = Paths.get("logs", "run.log");

    try (LogService logService = new LogService(logPath)) {
      RdP rdP = new RdP();
      Monitor monitor = new Monitor(rdP, timed, logService,
          PolicyMode.PRIORITIZED);

      long startTime = System.currentTimeMillis();
      AtomicInteger completedInvariants = new AtomicInteger(0);
      AtomicBoolean running = new AtomicBoolean(true);

      Thread[] workers = createWorkers(monitor, completedInvariants, running);

      startThreads(workers);

      try {
        awaitCompletion(running, completedInvariants);
        stopThreads(running, workers);
        joinThreads(workers);
      } catch (InterruptedException e) {
        stopThreads(running, workers);
        Thread.currentThread().interrupt();
      }

      printSummary(startTime, completedInvariants, monitor);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static Thread[] createWorkers(Monitor monitor,
      AtomicInteger completedInvariants,
      AtomicBoolean running) {
    List<WorkerSpec> workerSpecs = List.of(
        /*new WorkerSpec("ReservationConfirmTailWorker", List.of(9, 10, 11),
            true),
        new WorkerSpec("ReservationCancelTailWorker", List.of(8, 11), true),
        new WorkerSpec("ReservationConfirmChoiceWorker", List.of(6), false),
        new WorkerSpec("ReservationCancelChoiceWorker", List.of(7), false),
        new WorkerSpec("AgentSuperiorTailWorker", List.of(5), false),
        new WorkerSpec("AgentInferiorTailWorker", List.of(4), false),
        new WorkerSpec("AgentSuperiorChoiceWorker", List.of(2), false),
        new WorkerSpec("AgentInferiorChoiceWorker", List.of(3), false),
        new WorkerSpec("InputWorker", List.of(0, 1), false));*/

        new WorkerSpec("ReservationConfirmTailWorker", List.of(9, 10, 11), true),
        new WorkerSpec("ReservationCancelTailWorker", List.of(8, 11), true),
        new WorkerSpec("ReservationConfirmChoiceWorker", List.of(6), false),
        new WorkerSpec("ReservationCancelChoiceWorker", List.of(7), false),
        new WorkerSpec("AgentSuperiorChoiceWorker", List.of(2, 5), false),
        new WorkerSpec("AgentInferiorChoiceWorker", List.of(3, 4), false),
        new WorkerSpec("InputWorker", List.of(0, 1), false)
    );

    Thread[] workers = new Thread[workerSpecs.size()];
    for (int i = 0; i < workerSpecs.size(); i++) {
      WorkerSpec spec = workerSpecs.get(i);
      workers[i] = new Thread(
          new Threads(spec.path(), monitor, completedInvariants, TOTAL_RUNS,
              spec.countsCompletion(), running),
          spec.name());
    }

    return workers;
  }

  private static void startThreads(Thread[] workers) {
    for (Thread worker : workers) {
      worker.start();
    }
  }

  private static void awaitCompletion(AtomicBoolean running,
      AtomicInteger completedInvariants)
      throws InterruptedException {
    while (running.get() && completedInvariants.get() < TOTAL_RUNS) {
      Thread.sleep(5);
    }
  }

  private static void stopThreads(AtomicBoolean running, Thread[] workers) {
    running.set(false);
    for (Thread worker : workers) {
      worker.interrupt();
    }
  }

  private static void joinThreads(Thread[] workers)
      throws InterruptedException {
    for (Thread worker : workers) {
      worker.join();
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
