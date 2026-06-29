package org.concurrent.project;

import java.util.List;
import java.util.concurrent.Semaphore;
import org.concurrent.project.Policy.PolicyMode;
import org.ejml.data.DMatrixRMaj;
import java.util.ArrayList;

/**
 * Monitor de exclusión mutua para control concurrente de una Red de Petri.
 * <p>
 * Coordina el disparo concurrente de transiciones de la RdP mediante exclusión
 * mutua.
 * <p>
 * Este monitor centraliza tres responsabilidades: serializar acceso al marcado,
 * aplicar restricciones temporales de semántica débil y despertar hilos en
 * espera cuando una transición vuelve a estar habilitada para disparo.
 */
public class Monitor implements MonitorInterface {
  private static final long INFINITE_BETA_MS = TimeRestrictions.INFINITE_BETA;
  /** Configuración base de transiciones temporizadas: {transition, alphaMs}. */
  private static final int[][] TIMED_TRANSITIONS_BASE_MS = {
      { 1, 100 }, { 4, 60 }, { 5, 60 }, { 8, 80 }, { 9, 40 }, { 10, 40 } };

  private enum TransitionStep {
    FIRED,
    RETRY_MONITOR_RELEASED
  }

  private final Semaphore entry;
  private final Queues queues;
  private final RdP rdp;
  private final Policy policy;
  private final TimeRestrictions time;
  private final LogService log;
  private final List<Invariants.PInvariant> pInvariants = Invariants.defaultPInvariants();

  /**
   * Construye el monitor y configura transiciones temporizadas opcionales.
   * <p>
   * Inicializa semáforo de entrada, colas de espera y utilidades de tiempo.
   *
   * @param rdp   red de Petri controlada por el monitor.
   * @param timed indica si se habilitan restricciones temporales.
   * @param log   servicio de logging para eventos de disparo.
   * @param mode  modo de política para selección de transición a despertar entre
   *              múltiples habilitadas. {@code PolicyMode.NONE} desactiva la
   *              política y despierta todas las elegibles.
   */
  Monitor(RdP rdp, boolean timed, LogService log, PolicyMode mode) {
    entry = new Semaphore(1, true);
    this.rdp = rdp;
    this.log = log;
    queues = new Queues();
    policy = new Policy(mode);
    time = new TimeRestrictions();

    configureTimedTransitions(timed);
  }

  /**
   * Aplica configuración temporal inicial en forma declarativa.
   * <p>
   * Si el modo temporizado está activo, registra cada transición con ETF
   * (alpha) y beta infinito, y sincroniza estado temporal inicial con la
   * sensibilización actual de la red.
   *
   * @param timed indica si deben activarse restricciones temporales.
   */
  private void configureTimedTransitions(boolean timed) {
    if (!timed) {
      return;
    }
    for (int[] transitionConfig : TIMED_TRANSITIONS_BASE_MS) {
      int transition = transitionConfig[0];
      long alphaMs = transitionConfig[1];
      time.setTimedTransition(transition, alphaMs, INFINITE_BETA_MS);
    }
    time.updateFromSensitized(rdp.getSensitized());
  }

  /**
   * Intenta disparar una transición bajo exclusión mutua y semántica temporal
   * débil.
   * <p>
   * Valida índice, toma el monitor, refresca estado de sensibilización temporal
   * y evalúa si el disparo está permitido. Si la transición no puede dispararse
   * en
   * este ciclo (no sensibilizada o temprana), libera monitor y espera
   * el evento correspondiente antes de reintentar.
   *
   * @param transition identificador de transición a disparar.
   * @return {@code true} si se disparó; {@code false} si el hilo fue
   *         interrumpido.
   * @throws IllegalArgumentException si el índice de transición es inválido.
   */
  @Override
  public boolean fireTransition(int transition) {
    validateTransitionIndex(transition);

    while (true) {
      boolean monitorHeld = false;

      try {
        catchMonitor();
        monitorHeld = true;
        boolean isSensitized = (rdp.getSensitized().get(0, transition) == 1);

        if (isSensitized) {
          TransitionStep step;

          try {
            step = handleSensitizedTransition(transition);
          } catch (InterruptedException e) {
            monitorHeld = false;
            throw e;
          }

          if (step == TransitionStep.FIRED) {
            return true;
          }
          if (step == TransitionStep.RETRY_MONITOR_RELEASED) {
            monitorHeld = false;
          }
          continue;
        }

        monitorHeld = false;
        waitForSensitization(transition);
        continue;

      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;

      } finally {
        if (monitorHeld) {
          releaseMonitor();
        }
      }
    }
  }

  /**
   * Resuelve el flujo de una transición sensibilizada según su evaluación
   * temporal.
   *
   * @param transition transición sensibilizada a evaluar.
   * @return próximo paso de control para el ciclo principal.
   * @throws InterruptedException si el hilo es interrumpido durante una espera.
   */
  private TransitionStep handleSensitizedTransition(int transition) throws InterruptedException {
    TimeRestrictions.FireEvaluation evaluation = time.evaluateFire(transition);

    switch (evaluation) {
      case ALLOWED:
        fireAndReleaseTransition(transition);
        policy.onTransitionFired(transition);
        return TransitionStep.FIRED;

      case TOO_EARLY:
        waitUntilEarliestFireTime(transition);
        return TransitionStep.RETRY_MONITOR_RELEASED;

      case NOT_ENABLED:
        throw new IllegalStateException(
            "Estado inconsistente: transición sensibilizada en RdP pero "
                + "NOT_ENABLED en temporización. T" + transition);

      default:
        throw new IllegalStateException("FireEvaluation no soportada: " +
            evaluation);
    }
  }

  /**
   * Espera hasta alcanzar ETF para una transición temporizada.
   * <p>
   * Libera el monitor antes de esperar y duerme una única vez por el
   * tiempo restante hacia ETF.
   *
   * @param transition transición en estado {@code TOO_EARLY}.
   * @throws InterruptedException si el hilo es interrumpido durante la espera.
   */
  private void waitUntilEarliestFireTime(int transition) throws InterruptedException {
    long remainingMs = time.getRemainingToEarliest(transition);
    releaseMonitor();

    if (remainingMs > 0) {
      Thread.sleep(remainingMs);
    }
  }

  /**
   * Bloquea el hilo hasta que la transición vuelva a sensibilizarse y sea
   * señalada.
   * <p>
   * Incrementa contabilidad de espera, libera el monitor y aguarda en el
   * semáforo asociado a la transición.
   *
   * @param transition transición actualmente no sensibilizada.
   * @throws InterruptedException si el hilo es interrumpido durante la espera.
   */
  private void waitForSensitization(int transition) throws InterruptedException {
    queues.incrementWaitingCount(transition);
    releaseMonitor();
    queues.getSemaphoreForTransition(transition).acquire();
  }

  /**
   * Adquiere el monitor de exclusión mutua de la red.
   * <p>
   * Serializa el acceso a estado compartido de RdP y estructuras auxiliares,
   * para que evaluación temporal y disparo sean atómicos frente a otros hilos.
   *
   * @throws InterruptedException si el hilo es interrumpido mientras espera.
   */
  private void catchMonitor() throws InterruptedException {
    entry.acquire();
  }

  /**
   * Libera el monitor de exclusión mutua.
   * <p>
   * Permite que otros hilos en espera ingresen al ciclo de evaluación/disparo.
   * 
   * @throws IllegalStateException si el semáforo queda con más de un permiso tras
   *                               la liberación.
   */
  private void releaseMonitor() {
    entry.release();

    int after = entry.availablePermits();
    if (after > 1) {
      throw new IllegalStateException("Monitor roto: entry quedó con " + after +
          " permisos tras release de " +
          Thread.currentThread().getName());
    }
  }

  /**
   * Valida que un índice de transición pertenezca al rango definido por la
   * RdP.
   *
   * @param transition índice de transición a validar.
   * @throws IllegalArgumentException si está fuera del rango válido.
   */
  private void validateTransitionIndex(int transition) {
    int totalTransitions = rdp.getIncidencia().numCols;
    if (transition < 0 || transition >= totalTransitions) {
      throw new IllegalArgumentException("Transition fuera de rango: " +
          transition);
    }
  }

  /**
   * Dispara la transición y actualiza estructuras de sensibilización/colas.
   * <p>
   * Construye vector de disparo, ejecuta disparo en RdP, refresca temporización
   * para nueva instancia habilitada, libera esperas relevantes y registra
   * métricas
   * de transiciones exitosas.
   *
   * @param transition transición a disparar.
   */
  private void fireAndReleaseTransition(int transition) {
    DMatrixRMaj firingVector = createFiringVector(transition);
    rdp.fireTransition(firingVector);
    time.updateFromSensitized(rdp.getSensitized());
    time.onTransitionFired(transition, rdp.getSensitized().get(0, transition) == 1);
    updateSensitizedAndRelease();

    if (log != null) {
      DMatrixRMaj markingMatrix = rdp.getMarcadoActual();
      List<Invariants.PInvariantResult> results = Invariants.checkPInvariants(markingMatrix, pInvariants);
      String pinv = "OK";
      Invariants.PInvariantResult firstFail = null;

      for (Invariants.PInvariantResult r : results) {
        if (!r.ok()) {
          firstFail = r;
          pinv = "FAIL(" + r.name() + ",got=" + r.got() + ",exp=" + r.expected() + ")";
          break;
        }
      }

      if (firstFail != null) {
        log.logEvent(Thread.currentThread().getName(),
            "PINV_FAIL name=" + firstFail.name() + " got=" + firstFail.got() + " exp=" + firstFail.expected());
      }

      log.logFire(Thread.currentThread().getName(), transition, true,
          snapshotMarking(), pinv);
    }
  }

  /**
   * Toma una instantánea del marcado actual de la RdP.
   * <p>
   * Convierte la matriz de marcado de EJML a un arreglo de enteros para
   * facilitar su registro en el log.
   * 
   * @return arreglo de enteros representando el marcado actual.
   */
  private int[] snapshotMarking() {
    DMatrixRMaj m = rdp.getMarcadoActual(); // 1xP
    int[] out = new int[m.numCols];
    for (int i = 0; i < m.numCols; i++)
      out[i] = (int) m.get(0, i);
    return out;
  }

  /**
   * Crea el vector de disparo unitario para una transición.
   * <p>
   * El vector contiene un único valor 1 en la posición de la transición y 0
   * en el resto de posiciones.
   *
   * @param transition índice de transición objetivo.
   * @return matriz fila EJML con el vector de disparo.
   */
  private DMatrixRMaj createFiringVector(int transition) {
    double[] firing = new double[rdp.getIncidencia().numCols];
    firing[transition] = 1;
    return new DMatrixRMaj(firing.length, 1, true, firing);
  }

  /**
   * Libera esperas de transiciones sensibilizadas con filtrado de política.
   * <p>
   * Construye candidatas de wake-up como transiciones estructuralmente
   * sensibilizadas y con al menos un hilo esperando. Si la política está
   * desactivada (modo {@code PolicyMode.NONE}), despierta
   * un hilo por cada transición elegible y deja que el monitor + scheduler
   * resuelvan naturalmente. Si la política está activa, delega en ella la
   * elección de una única transición a despertar.
   */
  private void updateSensitizedAndRelease() {
    List<Integer> wakeEligibleTransitions = getWakeEligibleTransitions();
    if (wakeEligibleTransitions.isEmpty()) {
      return;
    }

    if (!policy.isEnabled()) {
      releaseAllEligible(wakeEligibleTransitions);
      return;
    }

    int selectedTransition = policy.choose(wakeEligibleTransitions);
    if (selectedTransition == -1) {
      return;
    }

    releaseSelectedTransition(selectedTransition);
  }

  /**
   * Despierta un hilo en espera por cada transición elegible.
   * <p>
   * Se utiliza cuando la política está desactivada para que todas las
   * transiciones actualmente sensibilizadas y con hilos bloqueados tengan
   * oportunidad de reintentar el disparo. La exclusión mutua del monitor
   * garantiza que, aunque varios hilos sean liberados, el acceso efectivo al
   * marcado siga siendo serializado.
   *
   * @param wakeEligibleTransitions lista de transiciones sensibilizadas con al
   *                                menos un hilo esperando.
   */
  private void releaseAllEligible(List<Integer> wakeEligibleTransitions) {
    for (int transition : wakeEligibleTransitions) {
      releaseSelectedTransition(transition);
    }
  }

  /**
   * Despierta un hilo en espera para una transición específica.
   * <p>
   * Si la transición indicada no tiene hilos bloqueados, no realiza ninguna
   * acción. En caso contrario, decrementa el contador de espera asociado y
   * libera exactamente un permiso en el semáforo de esa transición.
   *
   * @param transition índice de la transición a señalizar.
   */
  private void releaseSelectedTransition(int transition) {
    if (queues.getWaitingCounts().get(0, transition) <= 0) {
      return;
    }
    queues.decrementWaitingCount(transition);
    queues.getSemaphoreForTransition(transition).release();
  }

  /**
   * Imprime un resumen de la política de despertar.
   */
  public void printPolicySummary() {
    policy.printSummary();
  }

  /**
   * Determina qué transiciones son elegibles para despertar hilos en espera.
   * <p>
   * Una transición es elegible si está estructuralmente sensibilizada (según
   * la matriz de sensibilización de RdP) y tiene al menos un hilo esperando en
   * su semáforo. Construye una lista de índices de transiciones que cumplen ambos
   * criterios para que la política pueda seleccionar entre ellas.
   *
   * @return lista de índices de transiciones elegibles para despertar.
   */
  private List<Integer> getWakeEligibleTransitions() {
    List<Integer> wakeEligible = new java.util.ArrayList<>();
    DMatrixRMaj sensitized = rdp.getSensitized();
    DMatrixRMaj waiting = queues.getWaitingCounts();

    for (int t = 0; t < sensitized.numCols; t++) {
      boolean isSensitized = (sensitized.get(0, t) == 1.0);
      boolean hasThreadsWaiting = (waiting.get(0, t) > 0);

      if (isSensitized && hasThreadsWaiting) {
        wakeEligible.add(t);
      }
    }

    return wakeEligible;
  }

  /**
   * Administra colas de espera por transición y su contabilidad asociada.
   * <p>
   * Cada transición posee un semáforo dedicado para bloquear/despertar hilos.
   * Además, se mantiene un contador de espera por transición para decidir
   * liberaciones de forma consistente.
   */
  private static class Queues {
    private final List<Semaphore> queuesList;
    private final int numQueues;
    private final DMatrixRMaj waitingCount;

    /**
     * Construye la estructura de colas con un semáforo por transición.
     */
    Queues() {
      this.numQueues = 12;
      this.queuesList = new ArrayList<>(numQueues);
      this.waitingCount = new DMatrixRMaj(1, numQueues);
      initializeSemaphores();
    }

    /**
     * Inicializa los semáforos internos con cero permisos.
     */
    private void initializeSemaphores() {
      for (int i = 0; i < numQueues; i++) {
        queuesList.add(createSemaphore());
      }
    }

    /**
     * Devuelve el conteo actual de hilos en espera por transición.
     *
     * @return matriz 1xT con cantidad de hilos esperando por transición.
     */
    private DMatrixRMaj getWaitingCounts() {
      return waitingCount;
    }

    /**
     * Incrementa el contador de espera de una transición.
     *
     * @param transition índice de transición.
     */
    private void incrementWaitingCount(int transition) {
      waitingCount.set(0, transition, waitingCount.get(0, transition) + 1);
    }

    /**
     * Decrementa el contador de espera de una transición sin bajar de cero.
     *
     * @param transition índice de transición.
     */
    private void decrementWaitingCount(int transition) {
      double current = waitingCount.get(0, transition);
      waitingCount.set(0, transition, Math.max(0, current - 1));
    }

    /**
     * Devuelve el semáforo asociado a una transición.
     *
     * @param transition índice de transición.
     * @return semáforo de la cola de esa transición.
     */
    private Semaphore getSemaphoreForTransition(int transition) {
      return queuesList.get(transition);
    }

    private Semaphore createSemaphore() {
      return new Semaphore(0);
    }
  }
}
