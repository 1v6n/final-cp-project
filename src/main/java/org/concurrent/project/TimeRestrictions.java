package org.concurrent.project;

import java.util.HashMap;
import java.util.Map;

public class TimeRestrictions {
    private final Map<Integer, Long> timedTransitions;
    private final Map<Integer, Long> timeStamp;
    private final Map<Integer, Boolean> waitingTransitions;

    public TimeRestrictions() {
        this.timedTransitions = new HashMap<>();
        this.timeStamp = new HashMap<>();
        this.waitingTransitions = new HashMap<>();
    }

    /**
     * Configura el tiempo de habilitación para una transición específica. Setea el
     * timestamp de habilitación al momento actual.
     * @param transition número de transición
     * @param delayMs tiempo de espera en milisegundos
     */
    public void setTimedTransition(int transition, long delayMs) {
        timedTransitions.put(transition, delayMs);
        timeStamp.put(transition, System.currentTimeMillis());
        waitingTransitions.put(transition, false);
    }

    /**
     * Verifica si una transición es una transición con restricción de tiempo
     * o una transición instantanea.
     * @param transition número de transición
     * @return true si es una transición con restricción de tiempo, false en caso contrario
     */
    public boolean isTimedTransition(int transition) {
        return timedTransitions.containsKey(transition);
    }

    /**
     * Devuelve si la transición está siendo esperada por un hilo.
     * @param transition número de transición
     * @return {@code true} si la transición está siendo esperada por un hilo, {@code false} en caso contrario.
     */
    public boolean isWaiting(int transition) {
        return waitingTransitions.getOrDefault(transition, false);
    }

    /**
     * Verifica si la transicion puede ser disparada.
     * @param transition numero de transición
     * @return {@code true} si puede ser disparada, {@code false} en caso contrario
     */
    public boolean canFire(int transition) {
        if (!isTimedTransition(transition)) {
            return true;
        } else {
            Long enabledTime = timeStamp.get(transition);
            long alpha = System.currentTimeMillis() - enabledTime;
            long beta = timedTransitions.get(transition);
            return (alpha >= beta);
        }
    }

    /**
     * Resetea el timestamp de una transición al tiempo actual y marca la transición como no esperando.
     * @param transition número de transición
     */
    public void reset(int transition) {
        if (isTimedTransition(transition)) {
            timeStamp.replace(transition, System.currentTimeMillis());
            waitingTransitions.replace(transition, false);
        }
    }

    /**
     * Devuelve el tiempo restante para que una transición pueda ser disparada.
     * @param transition número de transición
     * @return tiempo restante en milisegundos
     */
    public long getRemainingTime(int transition) {
        if (!isTimedTransition(transition)) {
            return 0;
        } else {
            Long enabledTime = timeStamp.get(transition);
            long elapsed = System.currentTimeMillis() - enabledTime;
            long remaining = timedTransitions.get(transition) - elapsed;

            return Math.max(remaining, 0);
        }
    }

    /**
     * Resetea el timestamp de una transición al tiempo actual.
     * @param transition número de transición
     */
    public void setTimeStamp(int transition) {
        if (isTimedTransition(transition)) {
            timeStamp.replace(transition, System.currentTimeMillis());
        }
    }

    /**
     * Marca la transición como esperando.
     * @param transition número de transición
     */
    public void setWaiting(int transition) {
        if (isTimedTransition(transition)) {
            waitingTransitions.replace(transition, true);
        }
    }
}
