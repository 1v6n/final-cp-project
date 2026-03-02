package org.concurrent.project;

import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker ejecutor de secuencias de transiciones sobre el monitor.
 *
 * <p>
 * Puede operar como hilo habilitador (T0) o como hilo de invariante.
 * En modo invariante, cada hilo reserva una ejecución completa mediante
 * permisos antes de intentar disparar su secuencia.
 */
public class Threads implements Runnable {
  private final Vector<Integer> path;
  private final int totalInvariants;
  private final MonitorInterface monitor;
  private final Semaphore invariantPermits;
  private final Semaphore t0Permits;
  private final AtomicInteger completedInvariants;
  private final boolean isThread0;

  /**
   * Construye un worker de ejecución de transiciones.
   *
   * @param path                secuencia de transiciones a ejecutar por este
   *                            hilo.
   * @param rdp                 red de Petri asociada (no utilizada directamente
   *                            en esta versión).
   * @param monitor             monitor usado para disparar transiciones.
   * @param invariantPermits    semáforo que limita invariantes completos a
   *                            ejecutar.
   * @param t0Permits           semáforo que limita disparos de T0.
   * @param completedInvariants contador global de invariantes completados.
   * @param totalInvariants     objetivo total de invariantes.
   * @param isThread0           {@code true} si el hilo actúa como habilitador
   *                            principal.
   */
  public Threads(Vector<Integer> path, RdP rdp,
      MonitorInterface monitor, Semaphore invariantPermits,
      Semaphore t0Permits,
      AtomicInteger completedInvariants, int totalInvariants,
      boolean isThread0) {
    this.path = path;
    this.totalInvariants = totalInvariants;
    this.monitor = monitor;
    this.invariantPermits = invariantPermits;
    this.t0Permits = t0Permits;
    this.completedInvariants = completedInvariants;
    this.isThread0 = isThread0;
  }

  /**
   * Ejecuta el ciclo principal del hilo según su rol.
   *
   * <p>
   * Si es hilo habilitador, intenta disparar continuamente su transición de
   * entrada hasta alcanzar el objetivo global o ser interrumpido. Si es hilo de
   * invariante, reserva un permiso y ejecuta su secuencia completa.
   */
  @Override
  public void run() {

    if (isThread0) {

      int transition = path.get(0);

      while (!Thread.currentThread().isInterrupted()) {

        if (completedInvariants.get() >= totalInvariants)
          break;

        boolean fired = monitor.fireTransition(transition);

        if (!fired) {
          if (completedInvariants.get() >= totalInvariants ||
              Thread.currentThread().isInterrupted()) {
            break;
          }
        }
      }

    } else {

      while (!Thread.currentThread().isInterrupted()) {

        // Chequeo global antes de empezar
        int current = completedInvariants.get();
        if (current >= totalInvariants)
          break;

        // Ejecutar secuencia completa
        for (int transition : path) {

          if (completedInvariants.get() >= totalInvariants)
            return;

          if (!monitor.fireTransition(transition)) {
            return;
          }
        }

        // Reclamar slot global de manera atómica
        int completed = completedInvariants.incrementAndGet();

        if (completed > totalInvariants) {
          // Evita overshoot
          completedInvariants.decrementAndGet();
          break;
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
