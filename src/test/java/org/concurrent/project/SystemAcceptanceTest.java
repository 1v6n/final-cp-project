package org.concurrent.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.concurrent.project.Policy.PolicyMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SystemAcceptanceTest {
  private static final int TARGET_INVARIANTS = 24;
  private static final double[] INITIAL_MARKING = {
      5, 1, 0, 0, 5, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0
  };

  @ParameterizedTest(name = "completesWithoutDeadlock[{0}]")
  @EnumSource(PolicyMode.class)
  void completesWithoutDeadlockAndPreservesPInvariants(PolicyMode mode)
      throws InterruptedException {
    RdP rdp = new RdP();
    Monitor monitor = new Monitor(rdp, false, null, new Policy(mode));
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger started = new AtomicInteger();
    AtomicBoolean running = new AtomicBoolean(true);
    List<Thread> workers = createWorkers(monitor, completed, started, running);

    workers.forEach(Thread::start);
    try {
      awaitCompletion(completed, Duration.ofSeconds(5));
    } finally {
      running.set(false);
      workers.forEach(Thread::interrupt);
      for (Thread worker : workers) {
        worker.join(1_000);
      }
    }

    assertEquals(TARGET_INVARIANTS, completed.get());
    assertEquals(TARGET_INVARIANTS, started.get());
    assertTrue(workers.stream().noneMatch(Thread::isAlive), "Quedaron workers vivos");
    assertTrue(Invariants.checkPInvariants(
        rdp.getMarcadoActual(),
        Invariants.defaultPInvariants()).stream().allMatch(Invariants.PInvariantResult::ok));
    assertArrayEquals(INITIAL_MARKING, rdp.getMarcadoActual().getData(),
        "Una ejecución completa no debe dejar trabajo parcial en la red");
  }

  private static List<Thread> createWorkers(
      Monitor monitor,
      AtomicInteger completed,
      AtomicInteger started,
      AtomicBoolean running) {
    List<WorkerDefinition> definitions = List.of(
        new WorkerDefinition("start", List.of(0, 1), false, 1),
        new WorkerDefinition("agent-upper", List.of(2), false, 1),
        new WorkerDefinition("agent-lower", List.of(3), false, 1),
        new WorkerDefinition("prepare-confirmation", List.of(5), false, 1),
        new WorkerDefinition("prepare-cancellation", List.of(4), false, 1),
        new WorkerDefinition("confirmation", List.of(6, 9, 10, 11), true, 2),
        new WorkerDefinition("cancellation", List.of(7, 8, 11), true, 2));

    List<Thread> workers = new ArrayList<>();
    for (WorkerDefinition definition : definitions) {
      for (int instance = 1; instance <= definition.instances(); instance++) {
        Threads task = new Threads(
            definition.path(),
            monitor,
            completed,
            started,
            TARGET_INVARIANTS,
            definition.countsCompletion(),
            running);
        workers.add(new Thread(task, definition.name() + "-" + instance));
      }
    }
    return workers;
  }

  private static void awaitCompletion(AtomicInteger completed, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (completed.get() < TARGET_INVARIANTS && System.nanoTime() < deadline) {
      LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
    }
    assertFalse(completed.get() < TARGET_INVARIANTS,
        () -> "La ejecución quedó bloqueada con " + completed.get()
            + "/" + TARGET_INVARIANTS + " invariantes");
  }

  private record WorkerDefinition(
      String name,
      List<Integer> path,
      boolean countsCompletion,
      int instances) {
  }
}
