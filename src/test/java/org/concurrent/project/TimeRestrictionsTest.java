package org.concurrent.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.ejml.data.DMatrixRMaj;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TimeRestrictionsTest {

  @Test
  void disabledConfigurationDoesNotCreateTimedTransitions() {
    TimeRestrictions time = new TimeRestrictions(false, new int[][] { { 1, 100 } });

    assertFalse(time.isTimedTransition(1));
    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(1));
  }

  @Test
  void rejectsInvalidTimingWindows() {
    TimeRestrictions time = new TimeRestrictions(() -> 0L);

    assertThrows(IllegalArgumentException.class,
        () -> time.setTimedTransition(1, -1, TimeRestrictions.INFINITE_BETA));
    assertThrows(IllegalArgumentException.class,
        () -> time.setTimedTransition(1, 100, 99));
  }

  @Test
  void timedTransitionMovesFromNotEnabledToTooEarlyToAllowed() {
    AtomicLong now = new AtomicLong();
    TimeRestrictions time = configured(now, 2, 100, TimeRestrictions.INFINITE_BETA);

    assertEquals(TimeRestrictions.FireEvaluation.NOT_ENABLED, time.evaluateFire(2));

    time.updateSensitizationState(2, true);
    assertEquals(TimeRestrictions.FireEvaluation.TOO_EARLY, time.evaluateFire(2));

    now.set(milliseconds(99));
    assertEquals(TimeRestrictions.FireEvaluation.TOO_EARLY, time.evaluateFire(2));

    now.set(milliseconds(100));
    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(2));
  }

  @Test
  void repeatedSensitizedUpdateDoesNotRestartTheClock() {
    AtomicLong now = new AtomicLong();
    TimeRestrictions time = configured(now, 2, 100, TimeRestrictions.INFINITE_BETA);

    time.updateSensitizationState(2, true);
    now.set(milliseconds(75));
    time.updateSensitizationState(2, true);
    now.set(milliseconds(100));

    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(2));
  }

  @Test
  void losingAndRecoveringSensitizationStartsANewTimingInstance() {
    AtomicLong now = new AtomicLong();
    TimeRestrictions time = configured(now, 2, 100, TimeRestrictions.INFINITE_BETA);

    time.updateSensitizationState(2, true);
    now.set(milliseconds(100));
    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(2));

    time.updateSensitizationState(2, false);
    assertEquals(TimeRestrictions.FireEvaluation.NOT_ENABLED, time.evaluateFire(2));

    now.set(milliseconds(150));
    time.updateSensitizationState(2, true);
    now.set(milliseconds(249));
    assertEquals(TimeRestrictions.FireEvaluation.TOO_EARLY, time.evaluateFire(2));
    now.set(milliseconds(250));
    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(2));
  }

  @Test
  void finiteBetaIsInclusiveAndExpiresOnlyAfterItsLimit() {
    AtomicLong now = new AtomicLong();
    TimeRestrictions time = configured(now, 2, 100, 150);
    time.updateSensitizationState(2, true);

    now.set(milliseconds(150));
    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(2));

    now.set(milliseconds(151));
    assertEquals(TimeRestrictions.FireEvaluation.NOT_ENABLED, time.evaluateFire(2));
  }

  @Test
  void remainingTimeNeverReturnsZeroBeforeEarliestFireTime() {
    AtomicLong now = new AtomicLong();
    TimeRestrictions time = configured(now, 2, 100, TimeRestrictions.INFINITE_BETA);
    time.updateSensitizationState(2, true);

    now.set(milliseconds(99) + 500_000L);
    assertEquals(1L, time.getRemainingToEarliest(2));

    now.set(milliseconds(100));
    assertEquals(0L, time.getRemainingToEarliest(2));
  }

  @Test
  void updateFromSensitizedOnlyUpdatesConfiguredTransitions() {
    AtomicLong now = new AtomicLong();
    TimeRestrictions time = configured(now, 2, 10, TimeRestrictions.INFINITE_BETA);
    DMatrixRMaj sensitized = new DMatrixRMaj(1, 4);
    sensitized.set(0, 2, 1);

    time.updateFromSensitized(sensitized);
    now.set(milliseconds(10));

    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(2));
    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(3));
  }

  @Test
  void firingAStillSensitizedTransitionRestartsItsClock() {
    AtomicLong now = new AtomicLong();
    TimeRestrictions time = configured(now, 2, 100, TimeRestrictions.INFINITE_BETA);
    time.updateSensitizationState(2, true);
    now.set(milliseconds(100));

    time.onTransitionFired(2, true);
    assertEquals(TimeRestrictions.FireEvaluation.TOO_EARLY, time.evaluateFire(2));

    now.set(milliseconds(200));
    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(2));
  }

  @Test
  void firingATransitionThatStopsBeingSensitizedDisablesItsClock() {
    AtomicLong now = new AtomicLong();
    TimeRestrictions time = configured(now, 2, 0, TimeRestrictions.INFINITE_BETA);
    time.updateSensitizationState(2, true);

    time.onTransitionFired(2, false);

    assertEquals(TimeRestrictions.FireEvaluation.NOT_ENABLED, time.evaluateFire(2));
  }

  @Test
  @Timeout(2)
  void awaitUntilEarliestFireTimeSleepsUntilTheWindowOpens() throws Throwable {
    TimeRestrictions time = new TimeRestrictions(true, new int[0][0]);
    time.setTimedTransition(2, 40, TimeRestrictions.INFINITE_BETA);
    time.updateSensitizationState(2, true);

    long startedAt = System.nanoTime();
    invokeAwaitUntilEarliestFireTime(time, 2);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertTrue(elapsed.toMillis() >= 30,
        () -> "La espera terminó demasiado pronto: " + elapsed.toMillis() + " ms");
    assertEquals(TimeRestrictions.FireEvaluation.ALLOWED, time.evaluateFire(2));
  }

  @Test
  @Timeout(2)
  void awaitUntilEarliestFireTimePropagatesInterruption() throws Exception {
    TimeRestrictions time = new TimeRestrictions(true, new int[0][0]);
    time.setTimedTransition(2, 1_000, TimeRestrictions.INFINITE_BETA);
    time.updateSensitizationState(2, true);
    Method awaitMethod = awaitMethod();
    CountDownLatch aboutToWait = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread sleeper = new Thread(() -> {
      aboutToWait.countDown();
      try {
        invoke(awaitMethod, time, 2);
      } catch (Throwable error) {
        failure.set(error);
      }
    }, "time-restrictions-sleeper");

    sleeper.start();
    assertTrue(aboutToWait.await(1, TimeUnit.SECONDS));
    sleeper.interrupt();
    sleeper.join(1_000);

    assertFalse(sleeper.isAlive());
    assertInstanceOf(InterruptedException.class, failure.get());
  }

  private static TimeRestrictions configured(
      AtomicLong now,
      int transition,
      long alphaMs,
      long betaMs) {
    TimeRestrictions time = new TimeRestrictions(now::get);
    time.setTimedTransition(transition, alphaMs, betaMs);
    return time;
  }

  private static long milliseconds(long value) {
    return TimeUnit.MILLISECONDS.toNanos(value);
  }

  private static Method awaitMethod() throws NoSuchMethodException {
    Method method = TimeRestrictions.class.getDeclaredMethod(
        "awaitUntilEarliestFireTime",
        int.class);
    method.setAccessible(true);
    return method;
  }

  private static void invokeAwaitUntilEarliestFireTime(TimeRestrictions time, int transition)
      throws Throwable {
    invoke(awaitMethod(), time, transition);
  }

  private static void invoke(Method method, TimeRestrictions time, int transition) throws Throwable {
    try {
      method.invoke(time, transition);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }
}
