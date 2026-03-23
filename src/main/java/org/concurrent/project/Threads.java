package org.concurrent.project;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker ejecutor de secuencias de transiciones sobre el monitor.
 *
 * <p>
 * Cada hilo queda asociado a un camino fijo de la red. Segun su configuracion,
 * completar ese camino puede o no contar como una invariante completada.
 */
public class Threads implements Runnable {
  private final List<Integer> path;
  private final int totalInvariants;
  private final MonitorInterface monitor;
  private final AtomicInteger completedInvariants;
  private final boolean countsCompletion;
  private final AtomicBoolean running;

  /**
   * Construye un worker de ejecucion de transiciones.
   *
   * @param path secuencia fija de transiciones a ejecutar por este hilo.
   * @param monitor monitor usado para disparar transiciones.
   * @param completedInvariants contador global de invariantes completados.
   * @param totalInvariants objetivo total de invariantes.
   * @param countsCompletion {@code true} si completar el camino debe acreditar
   *        una invariante finalizada.
   * @param running bandera global de ejecucion/paro compartida por todos los
   *        workers.
   */
  public Threads(List<Integer> path,
      MonitorInterface monitor,
      AtomicInteger completedInvariants,
      int totalInvariants,
      boolean countsCompletion,
      AtomicBoolean running) {
    this.path = path;
    this.totalInvariants = totalInvariants;
    this.monitor = monitor;
    this.completedInvariants = completedInvariants;
    this.countsCompletion = countsCompletion;
    this.running = running;
  }

  /**
   * Ejecuta el ciclo principal del hilo segun su camino configurado.
   *
   * <p>
   * El worker intenta recorrer siempre la misma secuencia de transiciones. Si
   * logra completar toda la secuencia y su rol lo requiere, reclama
   * atomicamente un slot del contador global de invariantes.
   */
  @Override
  public void run() {
    while (running.get()) {
      // Chequeo global antes de empezar una nueva vuelta completa.
      if (completedInvariants.get() >= totalInvariants) {
        running.set(false);
        break;
      }

      // Ejecutar la secuencia completa asociada a este worker.
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
          sequenceCompleted = false;
          break;
        }
      }

      // Si el sistema ya esta frenando, no seguir procesando resultados.
      if (!running.get()) {
        break;
      }
      // Los workers intermedios no acreditan invariantes; solo alimentan a los
      // caminos que terminan en T11.
      if (!sequenceCompleted || !countsCompletion) {
        continue;
      }

      // Reclamar slot global de manera atomica.
      int completed = completedInvariants.incrementAndGet();
      if (completed > totalInvariants) {
        // Evita overshoot si dos hilos completan casi al mismo tiempo.
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

    System.out.printf(
        "Thread %s: Stopping.%n",
        Thread.currentThread().getName());
  }
}
