package org.concurrent.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ThreadsTest {

  @Test
  @Timeout(2)
  void executesACompletePathInOrderAndCountsItOnce() throws InterruptedException {
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger started = new AtomicInteger();
    List<Integer> calls = Collections.synchronizedList(new ArrayList<>());
    MonitorInterface monitor = transition -> {
      calls.add(transition);
      return true;
    };

    Thread worker = worker(
        List.of(6, 9, 10, 11),
        monitor,
        completed,
        started,
        1,
        true,
        running);
    worker.start();
    worker.join(1_000);

    assertFalse(worker.isAlive());
    assertEquals(List.of(6, 9, 10, 11), calls);
    assertEquals(1, completed.get());
    assertEquals(0, started.get());
    assertFalse(running.get());
  }

  @Test
  @Timeout(2)
  void aPathBeginningWithT0ClaimsOneStartedInvariant() throws InterruptedException {
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger started = new AtomicInteger();

    Thread worker = worker(
        List.of(0, 1),
        transition -> true,
        completed,
        started,
        1,
        true,
        running);
    worker.start();
    worker.join(1_000);

    assertFalse(worker.isAlive());
    assertEquals(1, started.get());
    assertEquals(1, completed.get());
  }

  @Test
  @Timeout(2)
  void stopsTheCurrentPathWhenAFireReturnsFalse() throws InterruptedException {
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger completed = new AtomicInteger();
    List<Integer> calls = Collections.synchronizedList(new ArrayList<>());
    MonitorInterface monitor = transition -> {
      calls.add(transition);
      if (transition == 2) {
        running.set(false);
        return false;
      }
      return true;
    };

    Thread worker = worker(
        List.of(1, 2, 3),
        monitor,
        completed,
        new AtomicInteger(),
        1,
        true,
        running);
    worker.start();
    worker.join(1_000);

    assertFalse(worker.isAlive());
    assertEquals(List.of(1, 2), calls);
    assertEquals(0, completed.get());
  }

  @Test
  @Timeout(2)
  void intermediatePathsNeverCountCompletions() throws InterruptedException {
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger completed = new AtomicInteger();
    MonitorInterface monitor = transition -> {
      running.set(false);
      return true;
    };

    Thread worker = worker(
        List.of(2),
        monitor,
        completed,
        new AtomicInteger(),
        1,
        false,
        running);
    worker.start();
    worker.join(1_000);

    assertFalse(worker.isAlive());
    assertEquals(0, completed.get());
  }

  @Test
  @Timeout(2)
  void concurrentCompletionWorkersNeverOvershootTheTarget() throws InterruptedException {
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger started = new AtomicInteger();
    CountDownLatch bothInsideMonitor = new CountDownLatch(2);
    CountDownLatch releaseBoth = new CountDownLatch(1);
    MonitorInterface monitor = transition -> {
      bothInsideMonitor.countDown();
      try {
        return releaseBoth.await(1, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      }
    };

    Thread first = worker(List.of(11), monitor, completed, started, 1, true, running);
    Thread second = worker(List.of(11), monitor, completed, started, 1, true, running);
    first.start();
    second.start();
    assertTrue(bothInsideMonitor.await(1, TimeUnit.SECONDS));
    releaseBoth.countDown();
    first.join(1_000);
    second.join(1_000);

    assertFalse(first.isAlive());
    assertFalse(second.isAlive());
    assertEquals(1, completed.get());
  }

  private static Thread worker(
      List<Integer> path,
      MonitorInterface monitor,
      AtomicInteger completed,
      AtomicInteger started,
      int total,
      boolean countsCompletion,
      AtomicBoolean running) {
    return new Thread(
        new Threads(path, monitor, completed, started, total, countsCompletion, running));
  }
}
