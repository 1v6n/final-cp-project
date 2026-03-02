package org.concurrent.project;

import org.concurrent.project.Policy.Mode;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CopyOnWriteArrayList;

public class Monitor implements MonitorInterface {
  private final Semaphore entry;
  private final Queues Queues;
  private final RdP rdp;
  private final Policy policy;
  private final CopyOnWriteArrayList<String> successfullyFired;
  private final TimeRestrictions time;

  /**
   * Constructor.
   * <p>
   * Este metodo constructor crea una instancia del monitor que controla la red de
   * Petri (RdP).
   * Inicializa los semáforos de entrada, las colas necesarias para manejar las
   * transiciones,
   * y la política que se aplicará en caso de conflictos dentro de la red.
   *
   * @param rdp La red de Petri que será controlada por el monitor.
   */
  Monitor(RdP rdp, boolean timed, Mode mode) {
    entry = new Semaphore(1, true);
    this.rdp = rdp;
    Queues = new Queues();
    policy = new Policy(mode);
    successfullyFired = new CopyOnWriteArrayList<>();
    time = new TimeRestrictions();

    if (timed) {
      // Transiciones con restricciones de tiempo {T1, T4, T5, T8, T9, T10}
      time.setTimedTransition(1, 130);
      time.setTimedTransition(4, 70);
      time.setTimedTransition(5, 70);
      time.setTimedTransition(8, 100);
      time.setTimedTransition(9, 50);
      time.setTimedTransition(10, 50);
    }
  }

  /**
   * Intenta disparar la transición especificada de forma segura y bloqueante.
   *
   * <p>
   * Este metodo realiza un intento de disparar una transición sensibilizada en un
   * modelo concurrente.
   * Si la transición no está sensibilizada, el hilo actual se bloquea en un
   * semáforo hasta que la transición
   * esté disponible para dispararse. El metodo gestiona correctamente el acceso
   * concurrente mediante el uso
   * de un monitor y semáforos asociados a las transiciones.
   *
   * <p>
   * Las principales operaciones del metodo son:
   * <ul>
   * <li>Adquirir el monitor para sincronizar el acceso al modelo.</li>
   * <li>Verificar si la transición está sensibilizada.</li>
   * <li>Si la transición está sensibilizada, dispararla y liberar el
   * monitor.</li>
   * <li>Si no está sensibilizada, liberar el monitor y bloquear el hilo en el
   * semáforo asociado a la transición.</li>
   * <li>Gestionar interrupciones durante la espera en el semáforo.</li>
   * </ul>
   *
   * @param transition el identificador de la transición que se intenta disparar.
   *                   Debe ser un número entero válido que represente una
   *                   transición en el modelo.
   * @return {@code true} si la transición fue disparada exitosamente;
   *         {@code false} si se interrumpió el hilo.
   * @throws IllegalArgumentException si el identificador de la transición no es
   *                                  válido.
   * @throws IllegalStateException    si el sistema presenta un estado
   *                                  inconsistente.
   */
  @Override
  public boolean fireTransition(int transition) {

    boolean k = true;

    try {

      entry.acquire();

      while (k) {

        boolean structuralEnabled = rdp.getSensitized().get(0, transition) == 1;

        boolean temporalEnabled = !time.isTimedTransition(transition) || time.canFire(transition);

        // =====================================================
        // NO HABILITADA → dormir
        // =====================================================

        if (!structuralEnabled || !temporalEnabled) {

          if (structuralEnabled && !temporalEnabled) {

            long sleepTime = time.getRemainingTime(transition);

            entry.release();
            Thread.sleep(sleepTime);
            entry.acquire();
            continue;
          }

          Queues.incrementWaitingCount(transition);
          entry.release();
          Queues.getSemaphoreForTransition(transition).acquire();
          entry.acquire();
          continue;
        }

        // =====================================================
        // DISPARO
        // =====================================================

        boolean fired = rdp.fireTransition(transition);

        if (!fired) {
          continue;
        }

        policy.registerFire(transition);
        time.reset(transition);

        // =====================================================
        // POST-DISPARO: señalización normal
        // =====================================================
        updateSensitizedAndRelease();
        k = false;
      }

      entry.release();
      return true;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void updateSensitizedAndRelease() {

    DMatrixRMaj sensitized = rdp.getSensitized();
    DMatrixRMaj waiting = Queues.getWaitingCounts();

    boolean t2Ready = sensitized.get(0, 2) == 1 && waiting.get(0, 2) > 0;
    boolean t3Ready = sensitized.get(0, 3) == 1 && waiting.get(0, 3) > 0;

    boolean t6Ready = sensitized.get(0, 6) == 1 && waiting.get(0, 6) > 0;
    boolean t7Ready = sensitized.get(0, 7) == 1 && waiting.get(0, 7) > 0;

    // =====================================
    // AGENTES
    // =====================================
    if (t2Ready || t3Ready) {
      int elegido = policy.choose(List.of(2, 3));
      System.out.printf("EL ELEGIDO ES: --- %d\n", elegido);
      Queues.decrementWaitingCount(elegido);
      Queues.getSemaphoreForTransition(elegido).release();
    }

    // =====================================
    // RESERVAS
    // =====================================
    if (t6Ready || t7Ready) {

      int elegido = policy.choose(List.of(6, 7));
      System.out.printf("EL ELEGIDO ES: --- %d\n", elegido);
      Queues.decrementWaitingCount(elegido);
      Queues.getSemaphoreForTransition(elegido).release();
    }

    // =====================================
    // RESTO NORMAL
    // =====================================
    for (int i = 0; i < sensitized.numCols; i++) {

      if (i == 2 || i == 3 || i == 6 || i == 7)
        continue;

      if (sensitized.get(0, i) == 1 && waiting.get(0, i) > 0) {

        Queues.decrementWaitingCount(i);
        Queues.getSemaphoreForTransition(i).release();
      }
    }
  }

  private boolean catchMonitor() throws InterruptedException {
    System.out.println("Catching Monitor");
    entry.acquire();
    return false;
  }

  private boolean releaseMonitor() {
    System.out.println("Releasing monitor");
    entry.release();
    return true;
  }

  /**
   * Dispara y libera la transición especificada.
   *
   * <p>
   * Este metodo realiza las siguientes operaciones en orden:
   * <ul>
   * <li>Crea un vector de disparo para la transición especificada.</li>
   * <li>Dispara la transición en el modelo utilizando el vector de disparo.</li>
   * <li>Actualiza las transiciones sensibilizadas y libera los semáforos
   * asociados.</li>
   * <li>Imprime un mensaje en la consola indicando que la transición fue
   * disparada exitosamente.</li>
   * </ul>
   *
   * <p>
   * Este metodo es parte de un sistema que maneja transiciones sensibilizadas
   * y asegura que las operaciones concurrentes se gestionen correctamente
   * mediante semáforos.
   *
   * @param transition el identificador de la transición que se va a disparar.
   *                   Debe ser un número entero válido que represente una
   *                   transición definida en el sistema.
   * @throws IllegalArgumentException si el identificador de la transición no es
   *                                  válido o no está definido.
   * @throws IllegalStateException    si no es posible disparar la transición
   *                                  debido a un estado inconsistente del
   *                                  sistema.
   */

  private void printSuccessfullyFired() {
    System.out.println("Successfully fired transitions: " + successfullyFired);
  }

  public Policy getPolicy() {
    return policy;
  }
}
