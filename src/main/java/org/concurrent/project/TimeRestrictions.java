package org.concurrent.project;

import java.util.HashMap;
import java.util.Map;

public class TimeRestrictions {
    private final Map<Integer, Long> timedTransitions;
    private final Map<Integer, Long> timeStamp;

    public TimeRestrictions() {
        this.timedTransitions = new HashMap<>();
        this.timeStamp = new HashMap<>();
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
     * Verifica si la transicion puede ser disparada.
     * @param transition numero de transición
     * @return true si puede ser disparada, false en caso contrario
     */
    public boolean canFire(int transition) {
        if(!isTimedTransition(transition)) {
            return true;
        } else {
            Long enabledTime = timeStamp.get(transition);
            long elapsed = System.currentTimeMillis() - enabledTime;
            return elapsed >= timedTransitions.get(transition);
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
     * Resetea el tiempo de habilitación de una transición.
     * @param transition número de transición
     */
    public void reset(int transition) {
        if (isTimedTransition(transition)) {
            timeStamp.replace(transition, System.currentTimeMillis());
        }
    }
}
