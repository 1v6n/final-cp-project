package org.concurrent.project;

import java.util.HashMap;
import java.util.Map;

public class TimeRestrictions {
    private final Map<Integer, Long> timedTransitions;
    private final Map<Integer, Long> enabledTimestamp;

    public TimeRestrictions() {
        this.timedTransitions = new HashMap<>();
        this.enabledTimestamp = new HashMap<>();
    }

    /**
     * Configura el tiempo de habilitación para una transición específica.
     * @param transition número de transición
     * @param delayMs tiempo de espera en milisegundos
     */
    public void setTimedTransition(int transition, long delayMs) {
        timedTransitions.put(transition, delayMs);
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
     * Marca cuando una transicion es habilitada.
     * @param transition número de transición
     */
    public void markEnabled(int transition) {
        enabledTimestamp.put(transition, System.currentTimeMillis());
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
            Long enabledTime = enabledTimestamp.get(transition);
            if (enabledTime == null) { return false; }

            long elapsed = System.currentTimeMillis() - enabledTime;
            return elapsed >= getTimeForTransition(transition);
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
            Long enabledTime = enabledTimestamp.get(transition);
            if (enabledTime == null) {
                return timedTransitions.get(transition); // No se ha marcado como habilitada
            }

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
        if (timedTransitions.containsKey(transition)) {
            enabledTimestamp.remove(transition);
            enabledTimestamp.put(transition, System.currentTimeMillis());
        }
    }

    /**
     * Obtiene el tiempo configurado para una transición específica.
     * @param transition número de transición
     * @return tiempo en milisegundos
     */
    public long getTimeForTransition(int transition) {
        return timedTransitions.getOrDefault(transition, 0L);
    }
}
