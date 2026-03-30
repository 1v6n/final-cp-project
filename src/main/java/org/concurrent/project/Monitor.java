package org.concurrent.project;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import org.concurrent.project.Policy.PolicyMode;
import org.concurrent.project.TimeRestrictions.FireEvaluation;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

/**
 * Coordina el disparo concurrente de transiciones de la RdP mediante exclusión
 * mutua.
 *
 * <p>
 * Este monitor centraliza tres responsabilidades: serializar acceso al marcado,
 * aplicar restricciones temporales de semántica débil y despertar hilos en
 * espera
 * cuando una transición vuelve a estar habilitada para disparo.
 */
public class Monitor implements MonitorInterface {
  private static final long INFINITE_BETA_MS = TimeRestrictions.INFINITE_BETA;
  /** Configuración base de transiciones temporizadas: {transition, alphaMs}. */
  private static final int[][] TIMED_TRANSITIONS_BASE_MS = {
      { 1, 130 }, { 4, 70 }, { 5, 70 }, { 8, 100 }, { 9, 50 }, { 10, 50 } };

  private enum TransitionStep {
    FIRED,
    RETRY_MONITOR_RELEASED,
    RETRY_MONITOR_HELD
  }

  private final Semaphore entry;
  private final Queues Queues;
  private final RdP rdp;
  private final Policy policy;
  private final CopyOnWriteArrayList<String> successfullyFired;
  private final TimeRestrictions time;
  private final LogService log;
  private final List<Invariants.PInvariant> pInvariants = Invariants.defaultPInvariants();

  /**
   * Construye el monitor y configura transiciones temporizadas opcionales.
   *
   * <p>
   * Inicializa semáforo de entrada, colas de espera y utilidades de tiempo.
   * Si {@code timed} es {@code true}, registra las transiciones temporizadas
   * definidas para esta simulación y libera hilos de instancias recién
   * habilitadas.
   *
   * @param rdp   red de Petri controlada por el monitor.
   * @param timed indica si se habilitan restricciones temporales.
   */
  Monitor(RdP rdp, boolean timed, LogService log, PolicyMode mode) {
    entry = new Semaphore(1, true);
    this.rdp = rdp;
    this.log = log;
    Queues = new Queues();
    policy = new Policy(mode);
    successfullyFired = new CopyOnWriteArrayList<>();
    time = new TimeRestrictions();

    configureTimedTransitions(timed);
  }

  /**
   * Aplica configuración temporal inicial en forma declarativa.
   *
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
   *
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
  private TransitionStep handleSensitizedTransition(int transition)
      throws InterruptedException {

    TimeRestrictions.FireEvaluation evaluation = time.evaluateFire(transition);

    switch (evaluation) {

      case ALLOWED:
        // PolicyDecision policyDecision = policy.evaluate(transition,
        // getPolicyEligibleTransitions()); if (!policyDecision.shouldFire()) {
        // return TransitionStep.RETRY_MONITOR_HELD;
        // }

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
   *
   * <p>
   * Libera el monitor antes de esperar y duerme una única vez por el
   * tiempo restante hacia ETF.
   *
   * @param transition transición en estado {@code TOO_EARLY}.
   * @throws InterruptedException si el hilo es interrumpido durante la espera.
   */
  private void waitUntilEarliestFireTime(int transition)
      throws InterruptedException {
    System.out.println(
        "T" + transition +
            " no puede ser disparada por restricción de tiempo. Esperando...");
    long remainingMs = time.getRemainingToEarliest(transition);
    releaseMonitor();
    if (remainingMs > 0) {
      Thread.sleep(remainingMs);
    }
  }

  /**
   * Bloquea el hilo hasta que la transición vuelva a sensibilizarse y sea
   * señalada.
   *
   * <p>
   * Incrementa contabilidad de espera, libera el monitor y aguarda en el
   * semáforo asociado a la transición.
   *
   * @param transition transición actualmente no sensibilizada.
   * @throws InterruptedException si el hilo es interrumpido durante la espera.
   */
  private void waitForSensitization(int transition)
      throws InterruptedException {
    Queues.incrementWaitingCount(transition);

    releaseMonitor();

    Queues.getSemaphoreForTransition(transition).acquire();
  }

  /**
   * Adquiere el monitor de exclusión mutua de la red.
   *
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
   *
   * <p>
   * Permite que otros hilos en espera ingresen al ciclo de evaluación/disparo.
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
   *
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
    rdp.fireTransition(firingVector, transition);
    time.updateFromSensitized(rdp.getSensitized());
    time.onTransitionFired(transition,
        rdp.getSensitized().get(0, transition) == 1);
    updateSensitizedAndRelease();
    successfullyFired.add("T" + transition);
    printSuccessfullyFired();
    System.out.println("Transition " + transition + " fired successfully by " +
        Thread.currentThread().getName());

    if (log != null) {
      DMatrixRMaj markingMatrix = rdp.getMarcadoActual();
      List<Invariants.PInvariantResult> results = Invariants.checkPInvariants(markingMatrix, pInvariants);

      String pinv = "OK";
      Invariants.PInvariantResult firstFail = null;

      for (Invariants.PInvariantResult r : results) {
        if (!r.ok()) {
          firstFail = r;
          pinv = "FAIL(" + r.name() + ",got=" + r.got() +
              ",exp=" + r.expected() + ")";
          break;
        }
      }

      if (firstFail != null) {
        log.logEvent(Thread.currentThread().getName(),
            "PINV_FAIL name=" + firstFail.name() + " got=" +
                firstFail.got() + " exp=" + firstFail.expected());
      }

      log.logFire(Thread.currentThread().getName(), transition, true,
          snapshotMarking(), pinv);
    }
  }

  private int[] snapshotMarking() {
    DMatrixRMaj m = rdp.getMarcadoActual(); // 1xP
    int[] out = new int[m.numCols];
    for (int i = 0; i < m.numCols; i++)
      out[i] = (int) m.get(0, i);
    return out;
  }

  /**
   * Imprime un resumen del total de transiciones disparadas exitosamente.
   *
   * <p>
   * Se informa únicamente el conteo acumulado para reducir trazas en
   * ejecuciones largas.
   */
  private void printSuccessfullyFired() {
    System.out.println("Successfully fired transitions count: " +
        successfullyFired.size());
  }

  /**
   * Crea el vector de disparo unitario para una transición.
   *
   * <p>
   * El vector contiene un único valor 1 en la posición de la transición y 0
   * en el resto de posiciones.
   *
   * @param transition índice de transición objetivo.
   * @return matriz columna EJML con el vector de disparo.
   */
  private DMatrixRMaj createFiringVector(int transition) {
    double[] firing = new double[rdp.getIncidencia().numCols];
    firing[transition] = 1;
    return new DMatrixRMaj(firing.length, 1, true, firing);
  }

  /**
   * Libera esperas de transiciones sensibilizadas con filtrado de política.
   *
   * <p>
   * Construye candidatas de wake-up como transiciones estructuralmente
   * sensibilizadas y con al menos un hilo esperando. Levanta todos los un
   * hilo por transición esperando y sensibilizada
   */
  private void updateSensitizedAndRelease() {
    List<Integer> wakeEligibleTransitions = getWakeEligibleTransitions();

    if (wakeEligibleTransitions.isEmpty()) {
      return;
    }

    int selectedTransition = policy.choose(wakeEligibleTransitions);

    if (selectedTransition == -1) {
      return;
    }

    DMatrixRMaj waiting = Queues.getWaitingCounts();
    if (waiting.get(0, selectedTransition) > 0) {
      Queues.decrementWaitingCount(selectedTransition);
      Queues.getSemaphoreForTransition(selectedTransition).release();
    }
    /*
     * DMatrixRMaj waiting = Queues.getWaitingCounts();
     * for (int transition : wakeEligibleTransitions) {
     * if (waiting.get(0, transition) > 0) {
     * Queues.decrementWaitingCount(transition);
     * Queues.getSemaphoreForTransition(transition).release();
     * }
     * }
     */
  }

  public void printPolicySummary() {
    policy.printSummary();
  }

  private boolean isTransitionAllowed(int t) {
    return rdp.getSensitized().get(0, t) == 1 &&
        time.evaluateFire(t) == FireEvaluation.ALLOWED;
  }

  private List<Integer> getPolicyEligibleTransitions() {
    List<Integer> enabled = new java.util.ArrayList<>();
    int totalTransitions = rdp.getIncidencia().numCols;
    for (int t = 0; t < totalTransitions; t++) {
      if (isTransitionAllowed(t)) {
        enabled.add(t);
      }
    }
    return enabled;
  }

  private List<Integer> getWakeEligibleTransitions() {
    List<Integer> wakeEligible = new java.util.ArrayList<>();
    DMatrixRMaj sensitized = rdp.getSensitized();
    DMatrixRMaj waiting = Queues.getWaitingCounts();

    DMatrixRMaj waitingMask = new DMatrixRMaj(1, waiting.numCols);
    for (int t = 0; t < waiting.numCols; t++) {
      waitingMask.set(0, t, waiting.get(0, t) > 0 ? 1.0 : 0.0);
    }

    DMatrixRMaj eligibleMask = new DMatrixRMaj(1, sensitized.numCols);
    CommonOps_DDRM.elementMult(sensitized, waitingMask, eligibleMask);

    for (int t = 0; t < eligibleMask.numCols; t++) {
      if (eligibleMask.get(0, t) == 1.0) {
        wakeEligible.add(t);
      }
    }
    return wakeEligible;
  }
}
