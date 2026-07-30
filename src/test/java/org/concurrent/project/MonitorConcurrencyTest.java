package org.concurrent.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import org.concurrent.project.Policy.PolicyMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MonitorConcurrencyTest {

  @Test
  void invalidTransitionDoesNotLeaveTheMonitorLocked() {
    Monitor monitor = monitor(new RdP(), false, new Policy(PolicyMode.NONE));

    assertThrows(IllegalArgumentException.class, () -> monitor.fireTransition(-1));
    assertThrows(IllegalArgumentException.class, () -> monitor.fireTransition(12));
    assertTrue(monitor.fireTransition(0));
  }

  @Test
  void policyIsNotConsultedWhenThereAreNoRealWaiters() {
    RecordingPolicy policy = new RecordingPolicy(PolicyMode.PRIORITIZED);
    Monitor monitor = monitor(new RdP(), false, policy);

    firePath(monitor, 0, 1, 2, 5);

    assertEquals(List.of(), policy.candidateCalls(),
        "T6 y T7 sensibilizadas sin waiters no constituyen un conflicto real");
  }

  @Test
  @Timeout(3)
  void allowedReservationIsNeverArtificiallyDeferred() throws InterruptedException {
    AlwaysChooseT6Policy policy = new AlwaysChooseT6Policy();
    Monitor monitor = monitor(new RdP(), false, policy);
    firePath(monitor, 0, 1, 2, 5);

    FireCall t7 = startFire(monitor, 7, "direct-T7");
    t7.thread().join(1_000);
    try {
      assertFalse(t7.thread().isAlive(),
          "T7 estaba ALLOWED y no debía esperar una autorización de Policy");
      assertEquals(Boolean.TRUE, t7.result().get());
      assertEquals(0, policy.chooseCalls(),
          "Policy sólo debe consultarse al seleccionar un waiter real");
    } finally {
      stop(t7);
    }
  }

  @Test
  @Timeout(3)
  void unsensitizedTransitionWaitsAndContinuesAfterSignalAndExit() throws InterruptedException {
    RdP rdp = new RdP();
    Monitor monitor = monitor(rdp, false, new Policy(PolicyMode.NONE));
    FireCall t1 = startFire(monitor, 1, "waiting-T1");

    awaitSemaphoreWait(t1.thread());
    assertTrue(monitor.fireTransition(0));
    t1.thread().join(1_000);

    try {
      assertFalse(t1.thread().isAlive());
      assertEquals(Boolean.TRUE, t1.result().get());
      assertEquals(1.0, rdp.getMarcadoActual().get(0, 3),
          "T1 debía haber producido el token de P3");
    } finally {
      stop(t1);
    }
  }

  @Test
  @Timeout(4)
  void signalOneWakesExactlyOneWaiter() throws InterruptedException {
    Monitor monitor = monitor(new RdP(), false, new Policy(PolicyMode.NONE));
    FireCall first = startFire(monitor, 1, "first-T1");
    FireCall second = startFire(monitor, 1, "second-T1");

    try {
      awaitSemaphoreWait(first.thread());
      awaitSemaphoreWait(second.thread());

      assertTrue(monitor.fireTransition(0));
      awaitCondition(
          () -> completedCount(first, second) == 1,
          Duration.ofSeconds(1),
          "No completó exactamente un waiter después de la primera señal");

      assertEquals(1, completedCount(first, second));
      FireCall stillWaiting = first.result().get() == null ? first : second;
      assertTrue(stillWaiting.thread().isAlive());

      assertTrue(monitor.fireTransition(0));
      first.thread().join(1_000);
      second.thread().join(1_000);

      assertEquals(Boolean.TRUE, first.result().get());
      assertEquals(Boolean.TRUE, second.result().get());
    } finally {
      stop(first, second);
    }
  }

  @Test
  @Timeout(4)
  void firstRealAgentConflictSelectsT3AndLeavesT2Waiting() throws InterruptedException {
    RecordingPolicy policy = new RecordingPolicy(PolicyMode.PRIORITIZED);
    Monitor monitor = monitor(new RdP(), false, policy);
    FireCall t1 = startFire(monitor, 1, "waiting-T1");
    FireCall t2 = startFire(monitor, 2, "waiting-T2");
    FireCall t3 = startFire(monitor, 3, "waiting-T3");

    try {
      awaitSemaphoreWait(t1.thread());
      awaitSemaphoreWait(t2.thread());
      awaitSemaphoreWait(t3.thread());
      assertTrue(monitor.fireTransition(0));

      awaitCondition(
          () -> t2.result().get() != null || t3.result().get() != null,
          Duration.ofSeconds(1),
          "Ninguna rama del conflicto de agentes fue despertada");

      assertEquals(Boolean.TRUE, t3.result().get(),
          "Con agentResidue=0, la primera decisión debía elegir la alternativa T3");
      assertNull(t2.result().get(), "El waiter T2 no seleccionado debía seguir esperando");
      assertTrue(t2.thread().isAlive());
      assertTrue(policy.candidateCalls().contains(List.of(2, 3)),
          () -> "Policy no recibió el conflicto real [2, 3]: " + policy.candidateCalls());
    } finally {
      stop(t1, t2, t3);
    }
  }

  @Test
  @Timeout(5)
  void firstRealReservationConflictSelectsT7WithoutDeferral() throws InterruptedException {
    RecordingPolicy policy = new RecordingPolicy(PolicyMode.PRIORITIZED);
    Monitor monitor = monitor(new RdP(), false, policy);

    firePath(monitor, 0, 1, 2, 5, 6);
    firePath(monitor, 0, 1, 2, 5);

    FireCall t6 = startFire(monitor, 6, "waiting-T6");
    FireCall t7 = startFire(monitor, 7, "waiting-T7");
    try {
      awaitSemaphoreWait(t6.thread());
      awaitSemaphoreWait(t7.thread());

      assertTrue(monitor.fireTransition(9));
      assertTrue(monitor.fireTransition(10));

      awaitCondition(
          () -> t6.result().get() != null || t7.result().get() != null,
          Duration.ofSeconds(1),
          "Ninguna rama del conflicto de reservas fue despertada");

      assertEquals(Boolean.TRUE, t7.result().get(),
          "Con reservationResidue=0, la primera decisión debía elegir la alternativa T7");
      assertNull(t6.result().get(), "El waiter T6 no seleccionado debía seguir esperando");
      assertTrue(t6.thread().isAlive());
      assertTrue(policy.candidateCalls().contains(List.of(6, 7)),
          () -> "Policy no recibió el conflicto real [6, 7]: " + policy.candidateCalls());
    } finally {
      stop(t6, t7);
    }
  }

  @Test
  @Timeout(3)
  void interruptionWhileWaitingRemovesTheWaiterAndPreservesTheFlag()
      throws InterruptedException {
    Monitor monitor = monitor(new RdP(), false, new Policy(PolicyMode.NONE));
    FireCall t1 = startFire(monitor, 1, "interrupted-T1");

    awaitSemaphoreWait(t1.thread());
    t1.thread().interrupt();
    t1.thread().join(1_000);

    assertFalse(t1.thread().isAlive());
    assertEquals(Boolean.FALSE, t1.result().get());
    assertTrue(t1.interruptedAfterCall().get());

    assertTrue(monitor.fireTransition(0),
        "El waiter interrumpido no debía dejar un handoff fantasma");
    assertTrue(monitor.fireTransition(1),
        "El monitor debía seguir utilizable después de limpiar el waiter");
  }

  @Test
  @Timeout(5)
  void tooEarlyTimedWaitReleasesTheMonitorForAnotherTransition()
      throws InterruptedException {
    Monitor monitor = monitor(new RdP(), true, new Policy(PolicyMode.NONE));

    assertTrue(monitor.fireTransition(0));
    assertTrue(monitor.fireTransition(1));
    assertTrue(monitor.fireTransition(0));

    FireCall timedT1 = startFire(monitor, 1, "timed-T1");
    try {
      awaitThreadState(timedT1.thread(), Thread.State.TIMED_WAITING, Duration.ofSeconds(1));

      assertTrue(monitor.fireTransition(2));
      assertTrue(timedT1.thread().isAlive(),
          "T1 todavía debía estar durmiendo mientras T2 usaba el monitor");

      timedT1.thread().join(1_000);
      assertEquals(Boolean.TRUE, timedT1.result().get());
    } finally {
      stop(timedT1);
    }
  }

  private static Monitor monitor(RdP rdp, boolean timed, Policy policy) {
    return new Monitor(rdp, timed, null, policy);
  }

  private static void firePath(Monitor monitor, int... transitions) {
    for (int transition : transitions) {
      assertTrue(monitor.fireTransition(transition), "No se pudo disparar T" + transition);
    }
  }

  private static FireCall startFire(Monitor monitor, int transition, String name) {
    AtomicReference<Boolean> result = new AtomicReference<>();
    AtomicBoolean interruptedAfterCall = new AtomicBoolean();
    Thread thread = new Thread(() -> {
      result.set(monitor.fireTransition(transition));
      interruptedAfterCall.set(Thread.currentThread().isInterrupted());
    }, name);
    thread.start();
    return new FireCall(thread, result, interruptedAfterCall);
  }

  private static void awaitSemaphoreWait(Thread thread) {
    awaitThreadState(thread, Thread.State.WAITING, Duration.ofSeconds(1));
  }

  private static void awaitThreadState(Thread thread, Thread.State state, Duration timeout) {
    awaitCondition(
        () -> thread.getState() == state,
        timeout,
        "El hilo " + thread.getName() + " no alcanzó el estado " + state
            + "; estado final=" + thread.getState());
  }

  private static void awaitCondition(
      BooleanSupplier condition,
      Duration timeout,
      String failureMessage) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
    assertTrue(condition.getAsBoolean(), failureMessage);
  }

  private static int completedCount(FireCall... calls) {
    int completed = 0;
    for (FireCall call : calls) {
      if (call.result().get() != null) {
        completed++;
      }
    }
    return completed;
  }

  private static void stop(FireCall... calls) throws InterruptedException {
    for (FireCall call : calls) {
      if (call != null && call.thread().isAlive()) {
        call.thread().interrupt();
      }
    }
    for (FireCall call : calls) {
      if (call != null) {
        call.thread().join(1_000);
      }
    }
  }

  private record FireCall(
      Thread thread,
      AtomicReference<Boolean> result,
      AtomicBoolean interruptedAfterCall) {
  }

  private static class RecordingPolicy extends Policy {
    private final List<List<Integer>> candidateCalls =
        Collections.synchronizedList(new ArrayList<>());

    RecordingPolicy(PolicyMode mode) {
      super(mode);
    }

    @Override
    public int choose(List<Integer> candidates) {
      candidateCalls.add(List.copyOf(candidates));
      return super.choose(candidates);
    }

    List<List<Integer>> candidateCalls() {
      synchronized (candidateCalls) {
        return List.copyOf(candidateCalls);
      }
    }
  }

  private static final class AlwaysChooseT6Policy extends Policy {
    private int chooseCalls;

    AlwaysChooseT6Policy() {
      super(PolicyMode.PRIORITIZED);
    }

    @Override
    public int choose(List<Integer> candidates) {
      chooseCalls++;
      if (candidates.contains(6)) {
        return 6;
      }
      return candidates.getFirst();
    }

    int chooseCalls() {
      return chooseCalls;
    }
  }
}
