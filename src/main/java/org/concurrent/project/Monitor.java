package org.concurrent.project;

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
     * Este metodo constructor crea una instancia del monitor que controla la red de Petri (RdP).
     * Inicializa los semáforos de entrada, las colas necesarias para manejar las transiciones,
     * y la política que se aplicará en caso de conflictos dentro de la red.
     *
     * @param rdp La red de Petri que será controlada por el monitor.
     */
    Monitor(RdP rdp) {
        entry = new Semaphore(1, true);
        this.rdp = rdp;
        Queues = new Queues();
        policy = new Policy(true);
        successfullyFired = new CopyOnWriteArrayList<>();
        time = new TimeRestrictions();

        // Transiciones con restricciones de tiempo {T1, T4, T5, T8, T9, T10}
        time.setTimedTransition(1, 130);
        time.setTimedTransition(4, 30);
        time.setTimedTransition(5, 20);
        time.setTimedTransition(8, 50);
        time.setTimedTransition(9, 60);
        time.setTimedTransition(10, 70);

        time.markEnabled(1);
        time.markEnabled(4);
        time.markEnabled(5);
        time.markEnabled(8);
        time.markEnabled(9);
        time.markEnabled(10);
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
        boolean isSensitized = false;

        while (true) {
            try {
                catchMonitor();
                System.out.println(Thread.currentThread().getName() + " in monitor.");
                isSensitized = (rdp.getSensitized().get(0, transition) == 1);

                if (isSensitized && time.canFire(transition)) {
                    fireAndReleaseTransition(transition);
                    time.reset(transition);
                    return true;

                } else if (isSensitized) {
                    long waitTime = time.getRemainingTime(transition);
                    System.out.println("T" + transition + " waiting " + waitTime + "ms. in " + Thread.currentThread().getName());
                    Thread.sleep(waitTime);
                    releaseMonitor();
                } else {
                    System.out.println("T" + transition + " no sensibilizada. Esperando en semáforo: " + Thread.currentThread().getName());
                    Queues.incrementWaitingCount(transition);
                    releaseMonitor();
                    Queues.getSemaphoreForTransition(transition).acquire();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;

            } finally {
                if (isSensitized && entry.availablePermits() == 0) {
                    releaseMonitor();
                }
            }
        }
    }

    private void catchMonitor() throws InterruptedException {
        entry.acquire();
    }

    private void releaseMonitor() {
        System.out.println("Releasing monitor");
        entry.release();
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
        updateSensitizedAndRelease();
        successfullyFired.add("T" + transition);
        printSuccessfullyFired();
        System.out.println("Transition " + transition + " fired successfully by " + Thread.currentThread().getName());
        time.reset(transition);
    }

    private void printSuccessfullyFired() {
        System.out.println("Successfully fired transitions: " + successfullyFired);
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
            if (result.get(0, i) >= 1) {
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
