package org.concurrent.project;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker ejecutor de secuencias de transiciones sobre el monitor.
 *
 * <p>
 * Puede operar como hilo habilitador (T0) o como hilo de invariante.
 */
public class Threads implements Runnable {
  private final List<Integer> path;
  private final int totalInvariants;
  private final MonitorInterface monitor;
  private final AtomicInteger completedInvariants;
  private final boolean isThread0;
  private final AtomicBoolean running;

  /**
   * Construye un worker de ejecución de transiciones.
   *
   * @param path                secuencia de transiciones a ejecutar por este
   *                            hilo.
   * @param monitor             monitor usado para disparar transiciones.
   * @param completedInvariants contador global de invariantes completados.
   * @param totalInvariants     objetivo total de invariantes.
   * @param isThread0           {@code true} si el hilo actúa como habilitador
   *                            principal.
   */
  public Threads(List<Integer> path,
      MonitorInterface monitor,
      AtomicInteger completedInvariants, int totalInvariants,
      boolean isThread0, AtomicBoolean running) {
    this.path = path;
    this.totalInvariants = totalInvariants;
    this.monitor = monitor;
    this.completedInvariants = completedInvariants;
    this.isThread0 = isThread0;
    this.running = running;
  }

  /**
   * Ejecuta el ciclo principal del hilo según su rol.
   *
   * <p>
   * Si es hilo habilitador, intenta disparar continuamente su transición de
   * entrada hasta alcanzar el objetivo global o ser detenido. Si es hilo de
   * invariante, ejecuta su secuencia completa.
   */
  @Override
  public void run() {

    if (isThread0) {

      int transition = path.get(0);

      while (running.get()) {

        if (completedInvariants.get() >= totalInvariants) {
          running.set(false);
          break;
        }

        boolean fired = monitor.fireTransition(transition);

        if (!fired && !running.get()) {
          break;
        }
      }

    } else {

      while (running.get()) {

        // Chequeo global antes de empezar
        int current = completedInvariants.get();
        if (current >= totalInvariants) {
          running.set(false);
          break;
        }

        // Ejecutar secuencia completa
        boolean sequenceCompleted = true;
        for (int transition : path) {

          if (!running.get()) {
            sequenceCompleted = false;
            break;
          }

          if (completedInvariants.get() >= totalInvariants) {
            running.set(false);
            sequenceCompleted = false;
            break;
          }

          if (!monitor.fireTransition(transition)) {
            if (!running.get()) {
              sequenceCompleted = false;
              break;
            }
            sequenceCompleted = false;
            break;
          }
        }

        if (!running.get()) {
          break;
        }
        if (!sequenceCompleted) {
          continue;
        }

        // Reclamar slot global de manera atómica
        int completed = completedInvariants.incrementAndGet();

        if (completed > totalInvariants) {
          // Evita overshoot
          completedInvariants.decrementAndGet();
          running.set(false);
          break;
        }
        if (completed >= totalInvariants) {
          running.set(false);
        }

        int remaining = totalInvariants - completed;

        System.out.printf(
            "Thread %s: Completed one invariant. Remaining: %d.%n",
            Thread.currentThread().getName(), remaining);
      }
    }

    System.out.printf(
        "Thread %s: Stopping.%n",
        Thread.currentThread().getName());
  }
}
