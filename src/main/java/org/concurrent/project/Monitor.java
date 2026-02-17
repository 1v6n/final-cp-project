package org.concurrent.project;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
    /** Intervalo máximo de re-chequeo para esperas activas acotadas (ms). */
    private static final long WAIT_RECHECK_MS = 10L;
    /** Margen práctico para LTF en ejecución sobre JVM. */
    private static final long BETA_SLACK_MS = 20L;

    private final Semaphore entry;
    private final Queues Queues;
    private final RdP rdp;
    private final Policy policy;
    private final CopyOnWriteArrayList<String> successfullyFired;
    private final TimeRestrictions time;

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
    Monitor(RdP rdp, boolean timed) {
        entry = new Semaphore(1, true);
        this.rdp = rdp;
        Queues = new Queues();
        policy = new Policy(true);
        successfullyFired = new CopyOnWriteArrayList<>();
        time = new TimeRestrictions();

        if (timed) {
            // Semántica débil con beta finito: se agrega una pequeña ventana para evitar
            // expiraciones sistemáticas por jitter de planificación en JVM.
            time.setTimedTransition(1, 130, 130 + BETA_SLACK_MS);
            time.setTimedTransition(4, 70, 70 + BETA_SLACK_MS);
            time.setTimedTransition(5, 70, 70 + BETA_SLACK_MS);
            time.setTimedTransition(8, 100, 100 + BETA_SLACK_MS);
            time.setTimedTransition(9, 50, 50 + BETA_SLACK_MS);
            time.setTimedTransition(10, 50, 50 + BETA_SLACK_MS);
            List<Integer> newlyEnabledTransitions = time.updateFromSensitized(rdp.getSensitized());
            releaseWaitersOnFreshEnable(newlyEnabledTransitions);
        }
    }

    /**
     * Intenta disparar una transición bajo exclusión mutua y semántica temporal
     * débil.
     *
     * <p>
     * Valida índice, toma el monitor, refresca estado de sensibilización temporal
     * y evalúa si el disparo está permitido. Si la transición no puede dispararse
     * en
     * este ciclo (no sensibilizada, temprana o vencida), libera monitor y espera el
     * evento correspondiente antes de reintentar.
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
                System.out.println(Thread.currentThread().getName() + " in monitor.");
                List<Integer> newlyEnabledTransitions = time.updateFromSensitized(rdp.getSensitized());
                releaseWaitersOnFreshEnable(newlyEnabledTransitions);
                boolean isSensitized = (rdp.getSensitized().get(0, transition) == 1);

                if (isSensitized) {
                    TimeRestrictions.FireEvaluation evaluation = time.evaluateFire(transition);
                    switch (evaluation) {
                        case ALLOWED:
                            fireAndReleaseTransition(transition);
                            return true;
                        case TOO_EARLY:
                            System.out.println("T" + transition
                                    + " no puede ser disparada por restricción de tiempo. Esperando...");
                            long remainingMs = time.getRemainingToEarliest(transition);
                            long observedSequence = time.getEnableSequence(transition);
                            releaseMonitor();
                            monitorHeld = false;
                            sleepWithVersionCheck(remainingMs, transition, observedSequence);
                            continue;
                        case EXPIRED:
                            System.out.println(
                                    "T" + transition + " vencida para esta instancia. Esperando nueva habilitación.");
                            long expiredSequence = time.getEnableSequence(transition);
                            Queues.incrementWaitingCount(transition);
                            releaseMonitor();
                            monitorHeld = false;
                            while (true) {
                                if (time.getEnableSequence(transition) != expiredSequence) {
                                    break;
                                }
                                boolean acquired = Queues.getSemaphoreForTransition(transition)
                                        .tryAcquire(WAIT_RECHECK_MS, TimeUnit.MILLISECONDS);
                                if (acquired && time.getEnableSequence(transition) != expiredSequence) {
                                    break;
                                }
                            }
                            continue;
                        case NOT_ENABLED:
                            continue;
                    }
                }

                System.out.println("T" + transition + " no sensibilizada. Esperando en semáforo: "
                        + Thread.currentThread().getName());
                Queues.incrementWaitingCount(transition);
                releaseMonitor();
                monitorHeld = false;
                while (true) {
                    boolean acquired = Queues.getSemaphoreForTransition(transition)
                            .tryAcquire(WAIT_RECHECK_MS, TimeUnit.MILLISECONDS);
                    if (acquired) {
                        break;
                    }
                }
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
     * Duerme en intervalos cortos validando cambios de versión de habilitación.
     *
     * <p>
     * Evita esperas monolíticas largas para mejorar capacidad de respuesta ante
     * cambios de estado. Si cambia la versión observada, retorna antes del tiempo
     * objetivo para re-evaluar el disparo.
     *
     * @param totalSleepMs     tiempo total de espera deseado en milisegundos.
     * @param transition       transición observada durante la espera.
     * @param observedSequence versión de habilitación esperada.
     * @throws InterruptedException si el hilo es interrumpido durante el sueño.
     */
    private void sleepWithVersionCheck(
            long totalSleepMs,
            int transition,
            long observedSequence) throws InterruptedException {
        long remaining = Math.max(totalSleepMs, 0L);
        while (remaining > 0) {
            if (time.getEnableSequence(transition) != observedSequence) {
                return;
            }
            long step = Math.min(remaining, WAIT_RECHECK_MS);
            Thread.sleep(step);
            remaining -= step;
        }
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
        System.out.println("Catching Monitor");
        entry.acquire();
    }

    /**
     * Libera el monitor de exclusión mutua.
     *
     * <p>
     * Permite que otros hilos en espera ingresen al ciclo de evaluación/disparo.
     */
    private void releaseMonitor() {
        System.out.println("Releasing monitor");
        entry.release();
    }

    /**
     * Valida que un índice de transición pertenezca al rango definido por la RdP.
     *
     * @param transition índice de transición a validar.
     * @throws IllegalArgumentException si está fuera del rango válido.
     */
    private void validateTransitionIndex(int transition) {
        int totalTransitions = rdp.getIncidencia().numCols;
        if (transition < 0 || transition >= totalTransitions) {
            throw new IllegalArgumentException("Transition fuera de rango: " + transition);
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
        List<Integer> newlyEnabledTransitions = time.updateFromSensitized(rdp.getSensitized());
        time.onTransitionFired(transition, rdp.getSensitized().get(0, transition) == 1);
        releaseWaitersOnFreshEnable(newlyEnabledTransitions);
        updateSensitizedAndRelease();
        successfullyFired.add("T" + transition);
        printSuccessfullyFired();
        System.out.println("Transition " + transition + " fired successfully by " + Thread.currentThread().getName());
    }

    /**
     * Despierta hilos esperando por transiciones recién habilitadas.
     *
     * <p>
     * Para cada transición habilitada en este refresco, libera permisos en su
     * semáforo según el número de hilos actualmente contados en espera.
     *
     * @param newlyEnabledTransitions transiciones detectadas como nuevas
     *                                habilitadas.
     */
    private void releaseWaitersOnFreshEnable(List<Integer> newlyEnabledTransitions) {
        for (int transition : newlyEnabledTransitions) {
            while (Queues.getWaitingCounts().get(0, transition) >= 1) {
                Queues.decrementWaitingCount(transition);
                Queues.getSemaphoreForTransition(transition).release();
            }
        }
    }

    /**
     * Imprime un resumen del total de transiciones disparadas exitosamente.
     *
     * <p>
     * Se informa únicamente el conteo acumulado para reducir trazas en ejecuciones
     * largas.
     */
    private void printSuccessfullyFired() {
        System.out.println("Successfully fired transitions count: " + successfullyFired.size());
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
     * Libera esperas de transiciones estructuralmente sensibilizadas y
     * temporalmente permitidas.
     *
     * <p>
     * Calcula intersección entre transición sensibilizada y cantidad de hilos
     * esperando. Solo libera semáforos cuando la evaluación temporal sin efectos
     * laterales devuelve {@code ALLOWED}.
     */
    private void updateSensitizedAndRelease() {
        DMatrixRMaj sensitized = rdp.getSensitized();
        DMatrixRMaj WaitingCounts = Queues.getWaitingCounts();
        DMatrixRMaj result = new DMatrixRMaj(sensitized.numRows, sensitized.numCols);
        CommonOps_DDRM.elementMult(sensitized, WaitingCounts, result);
        List<Integer> availableTransitions = new ArrayList<>();

        for (int i = 0; i < result.numCols; i++) {
            if (result.get(0, i) >= 1
                    && time.evaluateFireNoSideEffects(i) == TimeRestrictions.FireEvaluation.ALLOWED) {
                availableTransitions.add(i);
            }
        }

        if (!availableTransitions.isEmpty()) {
            for (int transitionToRelease : availableTransitions) {
                System.out.printf("Transition %d sensitized. Releasing semaphore.%n", transitionToRelease);
                Queues.decrementWaitingCount(transitionToRelease);
                System.out.printf("Decremented waiting count for transition %d. New count: %.0f%n",
                        transitionToRelease, Queues.getWaitingCounts().get(0, transitionToRelease));
                Queues.getSemaphoreForTransition(transitionToRelease).release();
            }
        } else {
            System.out.println("No available transitions to release.");
        }
    }
}
