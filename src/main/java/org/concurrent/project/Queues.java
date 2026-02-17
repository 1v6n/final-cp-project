package org.concurrent.project;

import org.ejml.data.DMatrixRMaj;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Administra colas de espera por transición y su contabilidad asociada.
 *
 * <p>Cada transición posee un semáforo dedicado para bloquear/despertar hilos.
 * Además, se mantiene un contador de espera por transición para decidir
 * liberaciones de forma consistente.
 */
public class Queues {
    private final List<Semaphore> Queues;
    private final int numQueues;
    // Cantidad de hilos esperando por transición.
    private final DMatrixRMaj waitingCount;

    /**
     * Construye la estructura de colas con un semáforo por transición.
     */
    public Queues() {
        this.numQueues = 12;
        this.Queues = new ArrayList<>(numQueues);
        this.waitingCount = new DMatrixRMaj(1, numQueues);
        initializeSemaphores();
    }

    /**
     * Inicializa los semáforos internos con cero permisos.
     */
    public void initializeSemaphores() {
        for (int i = 0; i < numQueues; i++) {
            Queues.add(createSemaphore());
        }
    }

    /**
     * Calcula el estado actual de los permisos disponibles en cada cola y devuelve una matriz que
     * representa estos estados.
     *
     * @return una matriz de una fila con permisos disponibles por semáforo
     *         asociado a cada transición.
     */
    public DMatrixRMaj queuesEstan() {
        double[] queues = new double[this.numQueues];

        for (int i = 0; i < numQueues; i++) {
            queues[i] = Queues.get(i).availablePermits();
        }
        return new DMatrixRMaj(1, queues.length, true, queues);
    }

    /**
     * Devuelve el conteo actual de hilos en espera por transición.
     *
     * @return matriz 1xT con cantidad de hilos esperando por transición.
     */
    public DMatrixRMaj getWaitingCounts() {
        return waitingCount;
    }

    /**
     * Incrementa el contador de espera de una transición.
     *
     * @param transition índice de transición.
     */
    public void incrementWaitingCount(int transition) {
        waitingCount.set(0, transition, waitingCount.get(0, transition) + 1);
    }

    /**
     * Decrementa el contador de espera de una transición sin bajar de cero.
     *
     * @param transition índice de transición.
     */
    public void decrementWaitingCount(int transition) {
        double current = waitingCount.get(0, transition);
        waitingCount.set(0, transition, Math.max(0, current - 1));
    }


    /**
     * Devuelve el semáforo asociado a una transición.
     *
     * @param transition índice de transición.
     * @return semáforo de la cola de esa transición.
     */
    public Semaphore getSemaphoreForTransition(int transition) {
        return Queues.get(transition);
    }

    private Semaphore createSemaphore() {
        return new Semaphore(0);
    }

    /**
     * Devuelve la lista completa de semáforos de cola.
     *
     * @return lista de semáforos internos por transición.
     */
    public List<Semaphore> getQueues() {
        return Queues;
    }
}
