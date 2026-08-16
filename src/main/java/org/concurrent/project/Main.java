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
  private static final boolean timed = true;
  /** Policy mode applied by the monitor. Change to compare behaviors. */
  private static final PolicyMode POLICY_MODE = PolicyMode.PRIORITIZED;

  private record WorkerSpec(
      String name,
      List<Integer> path,
      boolean countsCompletion,
      int instances) {
  }

  public static void main(String[] args) {
    Path logPath = Paths.get("logs", "run.log");

    try (LogService logService = new LogService(logPath)) {
      RdP rdP = new RdP();
      Policy policy = new Policy(POLICY_MODE);
      Monitor monitor = new Monitor(rdP, timed, logService, policy);

      long startTime = System.currentTimeMillis();
      AtomicInteger completedInvariants = new AtomicInteger(0);
      AtomicInteger startedInvariants = new AtomicInteger(0);
      AtomicBoolean running = new AtomicBoolean(true);

      Thread[] workers = createWorkers(monitor, completedInvariants, startedInvariants, running);

      startThreads(workers);

      try {
        // Start progress bar in a separate thread
        ProgressBar progressBar = new ProgressBar(TOTAL_RUNS, completedInvariants, 30);
        Thread progressThread = new Thread(() -> {
          try {
            while (running.get() && completedInvariants.get() < TOTAL_RUNS) {
              progressBar.update();
              Thread.sleep(100); // Update every 100ms
            }
            progressBar.update(); // Final update
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            progressBar.close();
          }
        });
        progressThread.setDaemon(true);
        progressThread.start();

        awaitCompletion(running, completedInvariants);
        stopThreads(running, workers);
        joinThreads(workers);
      } catch (InterruptedException e) {
        stopThreads(running, workers);
        Thread.currentThread().interrupt();
      }

      printSummary(startTime, completedInvariants, policy);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static Thread[] createWorkers(Monitor monitor, AtomicInteger completedInvariants, AtomicInteger startedInvariants, AtomicBoolean running) {
      List<WorkerSpec> workerSpecs = List.of(
        new WorkerSpec("Thread-1", List.of(0, 1), false, 1),
        new WorkerSpec("Thread-2", List.of(2, 5), false, 1),
        new WorkerSpec("Thread-3", List.of(3, 4), false, 1),
        new WorkerSpec("Thread-4", List.of(6, 9, 10), false, 1),
        new WorkerSpec("Thread-5", List.of(7, 8), false, 1),
        new WorkerSpec("Thread-6", List.of(11), true, 1));

    int totalWorkers = workerSpecs.stream().mapToInt(WorkerSpec::instances).sum();
    Thread[] workers = new Thread[totalWorkers];
    int workerIndex = 0;

    for (WorkerSpec spec : workerSpecs) {
      for (int instance = 1; instance <= spec.instances(); instance++) {
        String name = spec.instances() == 1 ? spec.name() : spec.name() + "-" + instance;
        workers[workerIndex++] = new Thread(
            new Threads(
                spec.path(),
                monitor,
                completedInvariants,
                startedInvariants,
                TOTAL_RUNS,
                spec.countsCompletion(),
                running),
            name);
      }
    }

    return workers;
  }

  private static void startThreads(Thread[] workers) {
    for (Thread worker : workers) {
      worker.start();
    }
  }

  private static void awaitCompletion(AtomicBoolean running, AtomicInteger completedInvariants)
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

  private static void joinThreads(Thread[] workers) throws InterruptedException {
    for (Thread worker : workers) {
      worker.join();
    }
  }

  private static void printSummary(long startTime, AtomicInteger completedInvariants, Policy policy) {
    System.out.println("All threads have completed.");
    System.out.println("Total time elapsed: " + (System.currentTimeMillis() - startTime));
    System.out.println("Total ran invariants:" + completedInvariants.get());
    policy.printSummary();
  }
}
