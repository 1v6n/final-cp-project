package org.concurrent.project;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import org.ejml.data.DMatrixRMaj;

/**
 * Monitor de exclusión mutua para control concurrente de una Red de Petri.
 * <p>
 * Coordina el disparo concurrente de transiciones de la RdP mediante exclusión
 * mutua.
 * <p>
 * Centraliza la exclusión mutua sobre la RdP, la aplicación de restricciones
 * temporales, la selección y señalización de hilos en espera, y el registro
 * consistente de cada disparo.
 */
public class Monitor implements MonitorInterface {
  /** Configuración base de transiciones temporizadas: {transition, alphaMs}. */
  private static final int[][] TIMED_TRANSITIONS_BASE_MS = {
      { 1, 100 }, { 4, 60 }, { 5, 60 }, { 8, 80 }, { 9, 40 }, { 10, 40 } };

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
   * @param rdp     red de Petri controlada por el monitor.
   * @param timed   indica si se habilitan restricciones temporales.
   * @param log     servicio de logging para eventos de disparo.
   * @param policy  modo de política para selección de transición a despertar entre
   *              múltiples habilitadas. {@code PolicyMode.NONE} desactiva la
   *              política y selecciona la primera transición elegible por
   *              índice.
   */
  Monitor(RdP rdp, boolean timed, LogService log, Policy policy) {
    entry = new Semaphore(1, true);
    this.rdp = rdp;
    this.log = log;
    queues = new Queues(rdp.getIncidencia().numCols);
    this.policy = policy;
    time = new TimeRestrictions(timed, TIMED_TRANSITIONS_BASE_MS);
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

    Ownership ownership = new Ownership();
    try {
      while (true) {
        // Un waiter señalizado vuelve con ownership activo por handoff. Después
        // de una espera temporal queda inactivo y debe adquirir entry nuevamente.
        if (!ownership.isOwned()) {
          ownership.acquire();
        }
        if (!rdp.isSensitized(transition)) {
          waitForSensitization(transition, ownership);
          continue;
        }

        switch (time.evaluateFire(transition)) {
          case ALLOWED:
            fireAndReleaseTransition(transition, ownership);
            return true;

          case TOO_EARLY:
            waitUntilEarliestFireTime(transition, ownership);
            continue;

          case NOT_ENABLED:
            throw new IllegalStateException(
                "Estado inconsistente: transición sensibilizada en RdP pero "
                    + "NOT_ENABLED en temporización. T" + transition);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      ownership.release();
    }
  }

  /**
   * Dispara la transición y resuelve la salida del monitor.
   * <p>
   * Construye el vector de disparo, actualiza la RdP y la temporización,
   * registra el resultado, notifica a la política y finalmente libera el mutex
   * o lo cede mediante handoff a un waiter elegible.
   *
   * @param transition transición a disparar.
   */
  private void fireAndReleaseTransition(int transition, Ownership ownership) {
    DMatrixRMaj firingVector = createFiringVector(transition);
    rdp.fireTransition(firingVector);
    time.updateFromSensitized(rdp.getSensitized());
    time.onTransitionFired(transition, rdp.getSensitized().get(0, transition) == 1);

    logFireResult(transition);
    policy.onTransitionFired(transition);
    selectWaiterOrRelease(ownership);
  }

  /**
   * Registra el resultado de un disparo en el log, incluyendo el chequeo de
   * P-invariantes.
   * <p>
   * Toma una instantánea del marcado actual, evalúa los P-invariantes y, si
   * hay un fallo, registra un evento {@code PINV_FAIL} antes de registrar el
   * disparo. Si el servicio de logging es {@code null}, no hace nada.
   * <p>
   * Se ejecuta bajo el mutex para preservar el orden observable de los eventos
   * y garantizar una instantánea consistente del marcado.
   *
   * @param transition transición recién disparada.
   */
  private void logFireResult(int transition) {
    if (log == null) {
      return;
    }
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

  /**
   * Despierta exactamente un hilo en espera y le cede el mutex (signal-and-exit).
   * <p>
   * Si existen hilos esperando por transiciones sensibilizadas, selecciona una
   * transición según el modo de política y libera su semáforo asociado. El
   * mutex no se libera: se transfiere al hilo despertado mediante
   * {@link Ownership#handoff()}, evitando que compita en la cola de entrada.
   * <p>
   * <b>Orden de operaciones:</b> primero se señaliza al waiter
   * ({@link #signalOneWaiter(int)}) y luego se ejecuta
   * {@link Ownership#handoff()}. La señalización siempre es efectiva porque
   * {@link #wakingCandidates()} garantiza que la transición elegida
   * tiene al menos un waiter, y todo el bloque se ejecuta bajo el mutex sin
   * liberarlo entre el chequeo y la señalización.
   * <p>
   * Si no hay candidatos, libera el mutex mediante
   * {@link Ownership#release()}. De esta forma, la decisión entre release y
   * handoff queda resuelta completamente dentro de este método.
   * <p>
   * La política recibe únicamente candidatos efectivos y decide incluso en
   * modo {@code NONE}, donde elige uno al azar.
   *
   * @param ownership posesión lógica que se libera o cede según existan waiters
   *                  elegibles.
   */
  private void selectWaiterOrRelease(Ownership ownership) {
    List<Integer> wakeEligibleTransitions = wakingCandidates();

    if (wakeEligibleTransitions.isEmpty()) {
      ownership.release();
      return;
    }

    int selectedTransition = policy.choose(wakeEligibleTransitions);

    signalOneWaiter(selectedTransition);
    ownership.handoff();
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
  private List<Integer> wakingCandidates() {
    List<Integer> wakeEligible = new ArrayList<>();
    for (int t = 0; t < rdp.getIncidencia().numCols; t++) {
      if (rdp.isSensitized(t) && queues.hasWaiters(t)) {
        wakeEligible.add(t);
      }
    }

    return wakeEligible;
  }

  /**
   * Despierta un hilo en espera para una transición específica.
   * <p>
   * Decrementa el contador de espera asociado y libera exactamente un permiso
   * en el semáforo de esa transición.
   * <p>
   * <b>Precondición:</b> la transición indicada tiene al menos un hilo
   * esperando. El caller ({@link #selectWaiterOrRelease}) garantiza esta
   * precondición al elegir la transición desde
   * {@link #wakingCandidates()}, que filtra por
   * {@code waitingCount > 0}, y todo el bloque se ejecuta bajo el mutex sin
   * liberarlo entre el chequeo y esta llamada.
   *
   * @param transition índice de la transición a señalizar.
   */
  private void signalOneWaiter(int transition) {
    queues.signalOne(transition);
  }

  /**
   * Espera hasta alcanzar ETF para una transición temporizada.
   * <p>
   * Libera el monitor antes de delegar la espera temporal.
   *
   * @param transition transición en estado {@code TOO_EARLY}.
   * @throws InterruptedException si el hilo es interrumpido durante la espera.
   */
  private void waitUntilEarliestFireTime(int transition, Ownership ownership) throws InterruptedException {
    ownership.release();
    time.awaitUntilEarliestFireTime(transition);
  }

  /**
   * Bloquea el hilo hasta que la transición vuelva a sensibilizarse y sea
   * señalada.
   * <p>
   * Incrementa contabilidad de espera, libera el monitor y aguarda en el
   * semáforo asociado a la transición.
   * <p>
   * Si el {@code acquire()} es interrumpido (p.ej. parada vía
   * {@code Thread.interrupt()}), el contador de espera se decrementa en el
   * {@code finally} para evitar un leak que dejaría a la transición con un
   * waiter fantasma y podría gatillar despertares espurios o un handoff sin
   * receptor.
   *
   * @param transition transición actualmente no sensibilizada.
   * @throws InterruptedException si el hilo es interrumpido durante la espera.
   */
  private void waitForSensitization(int transition, Ownership ownership) throws InterruptedException {
    queues.incrementWaitingCount(transition);
    ownership.release();
    boolean acquired = false;
    try {
      queues.getSemaphoreForTransition(transition).acquire();
      acquired = true;
    } finally {
      if (!acquired) {
        queues.decrementWaitingCount(transition);
      }
    }
    ownership.wakeFromQueue();
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
   * Posesión lógica del monitor para un único hilo.
   * <p>
   * El semáforo {@code entry} no tiene ownership por hilo; esta clase modela
   * localmente si el hilo actual "posee" el mutex, lo liberó o lo cedió a otro
   * (signal-and-exit). Toda la lógica de adquisición, liberación y transferencia
   * pasa por aquí para que sea imposible liberar o ceder dos veces.
   * <p>
   * La instancia es local a cada invocación de {@link #fireTransition(int)} y
   * sólo se toca desde el hilo dueño, por lo que el flag {@code owned} no
   * necesita {@code volatile}.
   */
  private class Ownership {
    private boolean owned = false;

    /**
     * Toma el mutex (bloquea si está ocupado).
     *
     * @throws InterruptedException si el hilo es interrumpido mientras espera.
     */
    public void acquire() throws InterruptedException {
      Monitor.this.catchMonitor();
      owned = true;
    }

    /**
     * Libera el mutex; defensivo: no-op si ya no se posee.
     * <p>
     * La liberación se delega en {@link Monitor#releaseMonitor()}, que
     * aserta que {@code entry} no quede con más de un permiso.
     */
    public void release() {
      if (owned) {
        Monitor.this.releaseMonitor();
        owned = false;
      }
    }

    /**
     * Transfiere la posesión a un hilo recién despertado sin tocar
     * {@code entry}.
     * <p>
     * Es la operación clave del signal-and-exit: el permiso sigue existiendo,
     * pero lógicamente pertenece al despertado, que no deberá competir por
     * {@code entry}.
     */
    public void handoff() {
      owned = false;
    }

    /**
     * Recupera la posesión lógica tras despertar de una cola de transición.
     * <p>
     * El waiter recibió una señal asociada a un handoff y asume la
     * responsabilidad lógica del mutex sin volver a adquirir {@code entry}.
     */
    public void wakeFromQueue() {
      owned = true;
    }

    /**
     * Indica si el hilo actual posee lógicamente el mutex.
     *
     * @return {@code true} si el hilo posee el mutex; {@code false} si lo
     *         liberó o lo cedió.
     */
    public boolean isOwned() {
      return owned;
    }
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
     *
     * @param numQueues cantidad de transiciones (una cola por transición).
     */
    Queues(int numQueues) {
      this.numQueues = numQueues;
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

    private Semaphore createSemaphore() {
      return new Semaphore(0);
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

    private boolean hasWaiters(int transition) {
      return waitingCount.get(0, transition) > 0;
    }

    private void signalOne(int transition) {
      decrementWaitingCount(transition);
      getSemaphoreForTransition(transition).release();
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
  }
}
