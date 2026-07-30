package org.concurrent.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.concurrent.project.Policy.PolicyMode;
import org.ejml.data.DMatrixRMaj;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class LogServiceTest {

  @TempDir
  Path tempDirectory;

  @Test
  void monitorLogsOneSuccessfulFireWithAConsistentMarking() throws Exception {
    Path logPath = tempDirectory.resolve("successful-fire.log");

    try (LogService log = new LogService(logPath)) {
      Monitor monitor = new Monitor(
          new RdP(),
          false,
          log,
          new Policy(PolicyMode.NONE));
      assertTrue(monitor.fireTransition(0));
    }

    List<String> lines = Files.readAllLines(logPath);
    assertEquals(1, lines.size());
    assertTrue(lines.getFirst().contains("| tr=T0 | ok=true |"));
    assertTrue(lines.getFirst().contains("| pinv=OK"));
    assertFalse(lines.getFirst().contains("PINV_FAIL"));
  }

  @Test
  void monitorLogsPInvariantFailureBeforeTheCorruptFireRecord() throws Exception {
    Path logPath = tempDirectory.resolve("failed-invariant.log");

    try (LogService log = new LogService(logPath)) {
      Monitor monitor = new Monitor(
          new CorruptingRdP(),
          false,
          log,
          new Policy(PolicyMode.NONE));
      assertTrue(monitor.fireTransition(0));
    }

    List<String> lines = Files.readAllLines(logPath);
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("| ev=PINV_FAIL "));
    assertTrue(lines.get(1).contains("| tr=T0 | ok=true |"));
    assertTrue(lines.get(1).contains("| pinv=FAIL("));
  }

  @Test
  @Timeout(3)
  void concurrentEventsAreWrittenAsCompleteNonInterleavedLines() throws Exception {
    Path logPath = tempDirectory.resolve("concurrent.log");
    int threadCount = 6;
    int eventsPerThread = 25;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> writers = new ArrayList<>();

    try (LogService log = new LogService(logPath)) {
      for (int writer = 0; writer < threadCount; writer++) {
        int writerId = writer;
        Thread thread = new Thread(() -> {
          ready.countDown();
          try {
            start.await();
            for (int event = 0; event < eventsPerThread; event++) {
              log.logEvent("logger-" + writerId, "event-" + event);
            }
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        }, "log-writer-" + writer);
        writers.add(thread);
        thread.start();
      }

      assertTrue(ready.await(1, TimeUnit.SECONDS));
      start.countDown();
      for (Thread writer : writers) {
        writer.join(1_000);
        assertFalse(writer.isAlive());
      }
    }

    List<String> lines = Files.readAllLines(logPath);
    assertEquals(threadCount * eventsPerThread, lines.size());
    assertTrue(lines.stream().allMatch(line ->
        line.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"
            + " \\| thr=logger-\\d+ \\| ev=event-\\d+")));
  }

  private static final class CorruptingRdP extends RdP {
    @Override
    public void fireTransition(DMatrixRMaj firingVector) {
      super.fireTransition(firingVector);
      getMarcadoActual().add(0, 1, 1);
    }
  }
}
