package org.concurrent.project;

/**
 * Contrato mínimo para el monitor de disparo de transiciones.
 * <p>
 * Abstrae la operación de disparo para desacoplar la lógica de hilos
 * de la implementación concreta de sincronización.
 */
public interface MonitorInterface {
    /**
     * Intenta disparar una transición de la red de Petri.
     * 
     * @param transition índice de transición a disparar.
     * @return {@code true} si el disparo se completó; {@code false} si el hilo
     *         fue interrumpido o no pudo completar el disparo.
     */
    boolean fireTransition(int transition);
}
