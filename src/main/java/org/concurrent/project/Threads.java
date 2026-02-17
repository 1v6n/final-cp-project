package org.concurrent.project;

import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker ejecutor de secuencias de transiciones sobre el monitor.
 *
 * <p>Puede operar como hilo habilitador (T0) o como hilo de invariante.
 * En modo invariante, cada hilo reserva una ejecución completa mediante
 * permisos antes de intentar disparar su secuencia.
 */
public class Threads implements Runnable {
  private final Vector<Integer> path;
  private final int repeatCount;
  private final int totalInvariants;
  private final MonitorInterface monitor;
  private final Semaphore invariantPermits;
  private final AtomicInteger completedInvariants;
  private final boolean isThread0;

  /**
   * Construye un worker de ejecución de transiciones.
   *
   * @param path secuencia de transiciones a ejecutar por este hilo.
   * @param repeatCount cantidad nominal de repeticiones (reservado para compatibilidad).
   * @param rdp red de Petri asociada (no utilizada directamente en esta versión).
   * @param monitor monitor usado para disparar transiciones.
   * @param invariantPermits semáforo que limita invariantes completos a ejecutar.
   * @param completedInvariants contador global de invariantes completados.
   * @param totalInvariants objetivo total de invariantes.
   * @param isThread0 {@code true} si el hilo actúa como habilitador principal.
   */
  public Threads(Vector<Integer> path, int repeatCount, RdP rdp,
      MonitorInterface monitor, Semaphore invariantPermits, AtomicInteger completedInvariants,
      int totalInvariants,
      boolean isThread0) {
    this.path = path;
    this.repeatCount = repeatCount;
    this.totalInvariants = totalInvariants;
    this.monitor = monitor;
    this.invariantPermits = invariantPermits;
    this.completedInvariants = completedInvariants;
    this.isThread0 = isThread0;
  }

  /**
   * Ejecuta el ciclo principal del hilo según su rol.
   *
   * <p>Si es hilo habilitador, intenta disparar continuamente su transición de
   * entrada hasta alcanzar el objetivo global o ser interrumpido. Si es hilo de
   * invariante, reserva un permiso y ejecuta su secuencia completa.
   */
  @Override
  public void run() {
    if (isThread0) {
      // Hilo 0: mantiene el flujo de habilitación hasta completar invariantes.
      int transition = path.get(0);
      int run = 0;
      while (completedInvariants.get() < totalInvariants
          && !Thread.currentThread().isInterrupted()) {
        run++;
        boolean fired = monitor.fireTransition(transition);
        if (fired) {
          System.out.printf(
              "Thread %s: Successfully fired transition %d (Run %d).%n",
              Thread.currentThread().getName(), transition, run);
        } else {
          if (completedInvariants.get() >= totalInvariants
              || Thread.currentThread().isInterrupted()) {
            break;
          }
          System.out.printf(
              "Thread %s: Failed to fire transition %d (Run %d).%n",
              Thread.currentThread().getName(), transition, run);
        }
      }
      System.out.printf("Thread %s: Completed all runs.%n",
          Thread.currentThread().getName());
    } else {
      // Hilos de invariante: reservan una ejecución completa antes de correrla.
      while (invariantPermits.tryAcquire()) {
        for (int transition : path) {
          if (monitor.fireTransition(transition)) {
            System.out.printf("Thread %s: Successfully fired transition %d.%n",
                Thread.currentThread().getName(), transition);
          } else {
            System.out.printf("Thread %s: Failed to fire transition %d.%n",
                Thread.currentThread().getName(), transition);
            return;
          }
        }
        int completed = completedInvariants.incrementAndGet();
        int remaining = Math.max(totalInvariants - completed, 0);
        System.out.printf(
            "Thread %s: Completed one invariant. Remaining: %d.%n",
            Thread.currentThread().getName(), remaining);
      }
    }
    if (isThread0) {
      System.out.printf("Thread %s: Stopping worker thread.%n",
          Thread.currentThread().getName());
    } else if (completedInvariants.get() >= totalInvariants) {
      System.out.printf("Thread %s: Stopping as all invariants are complete.%n",
          Thread.currentThread().getName());
    } else {
      System.out.printf("Thread %s: Stopping (no invariant permit available).%n",
          Thread.currentThread().getName());
    }
  }
}
