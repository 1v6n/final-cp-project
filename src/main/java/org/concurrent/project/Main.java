package org.concurrent.project;

import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Punto de entrada de la simulación concurrente de la red de Petri.
 *
 * <p>
 * Inicializa modelo, monitor y pool de hilos (habilitador + invariantes),
 * y espera finalización al completar la cantidad objetivo de invariantes.
 */
public class Main {
    private static final int TOTAL_RUNS = 186;
    private static final boolean timed = true;

    /**
     * Ejecuta la simulación.
     *
     * @param args argumentos de línea de comandos.
     */
    public static void main(String[] args) {
        RdP rdP = new RdP();
        Monitor monitor = new Monitor(rdP, timed);
        long startTime = System.currentTimeMillis();

        int[][] invariants = {
                { 1, 2, 5, 6, 9, 10, 11 },
                { 1, 3, 4, 6, 9, 10, 11 },
                { 1, 2, 5, 7, 8, 11 },
                { 1, 3, 4, 7, 8, 11 }
        };

        Semaphore invariantPermits = new Semaphore(TOTAL_RUNS, true);
        AtomicInteger completedInvariants = new AtomicInteger(0);
        Vector<Integer> pathForThread0 = new Vector<>();
        pathForThread0.add(0);
        Thread thread0 = new Thread(new Threads(pathForThread0, TOTAL_RUNS, rdP, monitor, invariantPermits,
                completedInvariants, TOTAL_RUNS, true), "Thread-0");

        Thread[] invariantThreads = new Thread[invariants.length];

        for (int i = 0; i < invariants.length; i++) {
            Vector<Integer> pathForInvariant = new Vector<>();
            for (int transition : invariants[i]) {
                pathForInvariant.add(transition);
            }
            invariantThreads[i] = new Thread(new Threads(pathForInvariant, 0, rdP, monitor, invariantPermits,
                    completedInvariants, TOTAL_RUNS, false),
                    "Invariant-Thread-" + (i + 1));
        }

        thread0.start();
        for (Thread thread : invariantThreads) {
            thread.start();
        }

        try {
            while (completedInvariants.get() < TOTAL_RUNS) {
                Thread.sleep(10);
            }

            thread0.interrupt();
            for (Thread thread : invariantThreads) {
                thread.join();
            }
            thread0.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }

        System.out.println("All threads have completed.");
        System.out.println("Total time elapsed: " + (System.currentTimeMillis() - startTime));
    }
}
