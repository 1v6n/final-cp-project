package org.concurrent.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.concurrent.project.Policy.PolicyMode;
import org.junit.jupiter.api.Test;

class MainConfigurationTest {

  @Test
  void workerSpecIncludesTheNumberOfInstances() throws Exception {
    Class<?> workerSpec = Class.forName("org.concurrent.project.Main$WorkerSpec");
    Set<String> componentNames = new HashSet<>();
    for (RecordComponent component : workerSpec.getRecordComponents()) {
      componentNames.add(component.getName());
    }

    assertTrue(componentNames.contains("instances"),
        "WorkerSpec debe expresar multiplicidad sin duplicar especificaciones");
  }

  @Test
  void createsNineDistinctWorkersForConfiguredMultiplicities() throws Exception {
    Monitor monitor = new Monitor(
        new RdP(),
        false,
        null,
        new Policy(PolicyMode.PRIORITIZED));
    Method createWorkers = Main.class.getDeclaredMethod(
        "createWorkers",
        Monitor.class,
        AtomicInteger.class,
        AtomicInteger.class,
        AtomicBoolean.class);
    createWorkers.setAccessible(true);

    Thread[] workers = (Thread[]) createWorkers.invoke(
        null,
        monitor,
        new AtomicInteger(),
        new AtomicInteger(),
        new AtomicBoolean(true));

    assertEquals(9, workers.length);
    assertEquals(9, Arrays.stream(workers).map(Thread::getName).distinct().count(),
        "Cada instancia debe tener un nombre de hilo distinguible");
  }
}
