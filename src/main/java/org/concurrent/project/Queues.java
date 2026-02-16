package org.concurrent.project;

import org.ejml.data.DMatrixRMaj;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class Queues {
    private final List<Semaphore> Queues;
    private final int numQueues;
    private final DMatrixRMaj waitingCount; // Tracks threads waiting for each transition

    public Queues() {
        this.numQueues = 12;
        this.Queues = new ArrayList<>(numQueues);
        this.waitingCount = new DMatrixRMaj(1, numQueues);
        initializeSemaphores();
    }

    public void initializeSemaphores() {
        for (int i = 0; i < numQueues; i++) {
            Queues.add(createSemaphore());
        }
    }

    /**
     * Calcula el estado actual de los permisos disponibles en cada cola y devuelve una matriz que
     * representa estos estados.
     *
     * @return Una matriz de una fila que contiene los permisos disponibles para cada semaforo
     *     asociado a las colas de transicion.
     */
    public DMatrixRMaj queuesEstan() {
        double[] queues = new double[this.numQueues];

        for (int i = 0; i < numQueues; i++) {
            queues[i] = Queues.get(i).availablePermits();
        }
        return new DMatrixRMaj(1, queues.length, true, queues);
    }

    /**
     * Retrieves the current waiting counts for all transitions.
     *
     * @return A matrix representing the waiting counts.
     */
    public DMatrixRMaj getWaitingCounts() {
        return waitingCount;
    }

    /**
     * Increments the waiting count for a transition.
     *
     * @param transition the index of the transition to increment the waiting count for.
     */
    public void incrementWaitingCount(int transition) {
        waitingCount.set(0, transition, waitingCount.get(0, transition) + 1);
    }

    /**
     * Decrements the waiting count for a transition.
     *
     * @param transition the index of the transition to decrement the waiting count for.
     */
    public void decrementWaitingCount(int transition) {
        double current = waitingCount.get(0, transition);
        waitingCount.set(0, transition, Math.max(0, current - 1));
    }


    public Semaphore getSemaphoreForTransition(int transition) {
        return Queues.get(transition);
    }

    private Semaphore createSemaphore() {
        return new Semaphore(0);
    }

    public List<Semaphore> getQueues() {
        return Queues;
    }
}
