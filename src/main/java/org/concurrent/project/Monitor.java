package org.concurrent.project;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public class Monitor implements MonitorInterface {
    private static final long EXPIRED_RECHECK_SLEEP_MS = 2L;
    private static final long WAIT_RECHECK_MS = 10L;

    private final Semaphore entry;
    private final Queues Queues;
    private final RdP rdp;
    private final Policy policy;
    private final CopyOnWriteArrayList<String> successfullyFired;
    private final TimeRestrictions time;

    /**
     * Constructor.
     * <p>
     * Este metodo constructor crea una instancia del monitor que controla la red de Petri (RdP).
     * Inicializa los semáforos de entrada, las colas necesarias para manejar las transiciones,
     * y la política que se aplicará en caso de conflictos dentro de la red.
     *
     * @param rdp La red de Petri que será controlada por el monitor.
     */
    Monitor(RdP rdp, boolean timed) {
        entry = new Semaphore(1, true);
        this.rdp = rdp;
        Queues = new Queues();
        policy = new Policy(true);
        successfullyFired = new CopyOnWriteArrayList<>();
        time = new TimeRestrictions();

        if (timed) {
            // Transiciones temporizadas con semántica débil y beta finito [alpha, beta].
            time.setTimedTransition(1, 130, 130);
            time.setTimedTransition(4, 70, 70);
            time.setTimedTransition(5, 70, 70);
            time.setTimedTransition(8, 100, 100);
            time.setTimedTransition(9, 50, 50);
            time.setTimedTransition(10, 50, 50);
            time.updateFromSensitized(rdp.getSensitized());
        }
    }

    /**
     * Intenta disparar la transición especificada de forma segura y bloqueante.
     *
     * <p>Este metodo realiza un intento de disparar una transición sensibilizada en un modelo concurrente.
     * Si la transición no está sensibilizada, el hilo actual se bloquea en un semáforo hasta que la transición
     * esté disponible para dispararse. El metodo gestiona correctamente el acceso concurrente mediante el uso
     * de un monitor y semáforos asociados a las transiciones.
     *
     * <p>Las principales operaciones del metodo son:
     * <ul>
     *   <li>Adquirir el monitor para sincronizar el acceso al modelo.</li>
     *   <li>Verificar si la transición está sensibilizada.</li>
     *   <li>Si la transición está sensibilizada, dispararla y liberar el monitor.</li>
     *   <li>Si no está sensibilizada, liberar el monitor y bloquear el hilo en el semáforo asociado a la transición.</li>
     *   <li>Gestionar interrupciones durante la espera en el semáforo.</li>
     * </ul>
     *
     * @param transition el identificador de la transición que se intenta disparar.
     *                   Debe ser un número entero válido que represente una transición en el modelo.
     * @return {@code true} si la transición fue disparada exitosamente; {@code false} si se interrumpió el hilo.
     * @throws IllegalArgumentException si el identificador de la transición no es válido.
     * @throws IllegalStateException si el sistema presenta un estado inconsistente.
     */
    @Override
    public boolean fireTransition(int transition) {
        return fireTransition(transition, () -> true);
    }

    /**
     * Intenta disparar una transición con una condición externa de continuidad.
     *
     * <p>Este metodo extiende la lógica de disparo bloqueante del monitor incorporando una condición
     * de corte evaluada periódicamente durante la espera. Resulta útil para escenarios de finalización
     * controlada del trabajo, donde un hilo debe poder abandonar esperas largas (semáforo o temporización)
     * cuando una condición global de parada deja de cumplirse.
     *
     * <p>La condición {@code shouldContinue} se verifica:
     * <ul>
     *   <li>Antes de entrar a cada iteración principal.</li>
     *   <li>Durante las esperas temporales fraccionadas por ETF.</li>
     *   <li>Después de esperas acotadas en semáforos de transición.</li>
     * </ul>
     *
     * @param transition identificador de la transición a intentar disparar.
     * @param shouldContinue condición de continuidad del intento de disparo.
     * @return {@code true} si la transición se disparó; {@code false} si se cancela por condición de parada
     *         o por interrupción del hilo.
     * @throws IllegalArgumentException si el índice de transición está fuera de rango.
     */
    public boolean fireTransition(int transition, BooleanSupplier shouldContinue) {
        validateTransitionIndex(transition);

        while (true) {
            if (!shouldContinue.getAsBoolean()) {
                return false;
            }
            boolean monitorHeld = false;
            try {
                catchMonitor();
                monitorHeld = true;
                System.out.println(Thread.currentThread().getName() + " in monitor.");
                time.updateFromSensitized(rdp.getSensitized());
                boolean isSensitized = (rdp.getSensitized().get(0, transition) == 1);

                if (isSensitized) {
                    TimeRestrictions.FireEvaluation evaluation = time.evaluateFire(transition);
                    switch (evaluation) {
                        case ALLOWED:
                            fireAndReleaseTransition(transition);
                            return true;
                        case TOO_EARLY:
                            System.out.println("T" + transition + " no puede ser disparada por restricción de tiempo. Esperando...");
                            long remainingMs = time.getRemainingToEarliest(transition);
                            releaseMonitor();
                            monitorHeld = false;
                            sleepWithStopCheck(remainingMs, shouldContinue);
                            continue;
                        case EXPIRED:
                            System.out.println("T" + transition + " vencida para esta instancia. Reintentando al refrescar sensibilización.");
                            releaseMonitor();
                            monitorHeld = false;
                            Thread.sleep(EXPIRED_RECHECK_SLEEP_MS);
                            continue;
                        case NOT_ENABLED:
                            // Si ocurre, reintentar ciclo; la sensibilización pudo cambiar entre chequeos.
                            continue;
                    }
                }

                System.out.println("T" + transition + " no sensibilizada. Esperando en semáforo: " + Thread.currentThread().getName());
                Queues.incrementWaitingCount(transition);
                releaseMonitor();
                monitorHeld = false;
                boolean acquired = Queues.getSemaphoreForTransition(transition).tryAcquire(WAIT_RECHECK_MS, TimeUnit.MILLISECONDS);
                if (!acquired && !shouldContinue.getAsBoolean()) {
                    return false;
                }
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
     * Duerme en intervalos cortos validando una condición de continuidad.
     *
     * <p>Este metodo evita esperas monolíticas largas para mejorar la capacidad de respuesta
     * del hilo frente a cambios de estado global. En cada tramo de sueño se vuelve a consultar
     * {@code shouldContinue}; si la condición deja de cumplirse, la espera termina de forma anticipada.
     *
     * @param totalSleepMs tiempo total de espera deseado en milisegundos.
     * @param shouldContinue condición de continuidad para mantener la espera.
     * @throws InterruptedException si el hilo es interrumpido durante el sueño.
     */
    private void sleepWithStopCheck(long totalSleepMs, BooleanSupplier shouldContinue) throws InterruptedException {
        long remaining = Math.max(totalSleepMs, 0L);
        while (remaining > 0) {
            if (!shouldContinue.getAsBoolean()) {
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
     * <p>Este metodo serializa el acceso al estado compartido de la RdP y a las estructuras
     * auxiliares del monitor, garantizando que la evaluación de sensibilización, tiempos y disparo
     * se realice de manera atómica respecto de otros hilos.
     *
     * @throws InterruptedException si el hilo es interrumpido mientras espera el monitor.
     */
    private void catchMonitor() throws InterruptedException {
        System.out.println("Catching Monitor");
        entry.acquire();
    }

    /**
     * Libera el monitor de exclusión mutua.
     *
     * <p>Este metodo habilita el progreso de otros hilos que esperan ingresar al monitor
     * para continuar con su ciclo de evaluación/disparo de transiciones.
     */
    private void releaseMonitor() {
        System.out.println("Releasing monitor");
        entry.release();
    }

    /**
     * Valida que el identificador de transición pertenezca al rango de la RdP.
     *
     * <p>La validación se realiza contra la cantidad de transiciones definida por
     * la matriz de incidencia. Si el valor no es válido, se detiene la operación
     * con una excepción descriptiva.
     *
     * @param transition índice de transición a validar.
     * @throws IllegalArgumentException si {@code transition} es menor a cero o mayor/igual al total.
     */
    private void validateTransitionIndex(int transition) {
        int totalTransitions = rdp.getIncidencia().numCols;
        if (transition < 0 || transition >= totalTransitions) {
            throw new IllegalArgumentException("Transition fuera de rango: " + transition);
        }
    }

    /**
     * Dispara y libera la transición especificada.
     *
     * <p>Este metodo realiza las siguientes operaciones en orden:
     * <ul>
     *   <li>Crea un vector de disparo para la transición especificada.</li>
     *   <li>Dispara la transición en el modelo utilizando el vector de disparo.</li>
     *   <li>Actualiza las transiciones sensibilizadas y libera los semáforos asociados.</li>
     *   <li>Imprime un mensaje en la consola indicando que la transición fue disparada exitosamente.</li>
     * </ul>
     *
     * <p>Este metodo es parte de un sistema que maneja transiciones sensibilizadas
     * y asegura que las operaciones concurrentes se gestionen correctamente mediante semáforos.
     *
     * @param transition el identificador de la transición que se va a disparar.
     *                   Debe ser un número entero válido que represente una transición definida en el sistema.
     * @throws IllegalArgumentException si el identificador de la transición no es válido o no está definido.
     * @throws IllegalStateException si no es posible disparar la transición debido a un estado inconsistente del sistema.
     */
    private void fireAndReleaseTransition(int transition) {
        DMatrixRMaj firingVector = createFiringVector(transition);
        rdp.fireTransition(firingVector, transition);
        time.updateFromSensitized(rdp.getSensitized());
        time.onTransitionFired(transition, rdp.getSensitized().get(0, transition) == 1);
        updateSensitizedAndRelease();
        successfullyFired.add("T" + transition);
        printSuccessfullyFired();
        System.out.println("Transition " + transition + " fired successfully by " + Thread.currentThread().getName());
    }

    /**
     * Imprime un resumen del total de transiciones disparadas exitosamente.
     *
     * <p>Se informa únicamente el conteo acumulado para evitar trazas excesivas
     * y mantener bajo el costo de logging durante ejecuciones largas.
     */
    private void printSuccessfullyFired() {
        System.out.println("Successfully fired transitions count: " + successfullyFired.size());
    }

    /**
     * Crea un vector de disparo para una transición específica.
     * <p>
     * Este metodo genera un vector de disparo para la transición especificada.
     * El vector de disparo es un array de doubles donde todas las posiciones
     * son 0 excepto la posición correspondiente a la transición, que se establece en 1.
     * Luego, este array se convierte en una matriz de EJML.
     *
     * @param transition El índice de la transición para la cual se creará el vector de disparo.
     * @return DMatrixRMaj  Una matriz que representa el vector de disparo para la transición especificada.
     */
    private DMatrixRMaj createFiringVector(int transition) {
        double[] firing = new double[rdp.getIncidencia().numCols];
        firing[transition] = 1;
        return new DMatrixRMaj(firing.length, 1, true, firing);
    }

    /**
     * Actualiza las transiciones sensibilizadas y libera los semáforos correspondientes.
     *
     * <p>Este metodo evalúa las transiciones sensibilizadas y las condiciones adicionales
     * (como los permisos disponibles en las colas de espera) mediante una operación lógica
     * AND entre las matrices de transiciones sensibilizadas y los conteos de espera. Para
     * cada transición que cumple ambas condiciones, se libera el semáforo correspondiente,
     * permitiendo que los hilos bloqueados puedan continuar.
     *
     * <p>El metodo utiliza operaciones de matriz de la biblioteca EJML para realizar
     * multiplicaciones elemento por elemento de manera eficiente.
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
