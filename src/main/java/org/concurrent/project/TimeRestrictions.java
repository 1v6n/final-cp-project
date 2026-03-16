package org.concurrent.project;

import org.ejml.data.DMatrixRMaj;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Gestiona restricciones temporales de disparo para transiciones de la RdP.
 *
 * <p>Implementa evaluación en semántica débil sobre ETF (alpha) por instancia
 * de habilitación, con LTF infinito (sin expiración).
 */
public class TimeRestrictions {
    /** Resultado de evaluación temporal para un intento de disparo. */
    public enum FireEvaluation {
        ALLOWED,
        TOO_EARLY,
        NOT_ENABLED
    }

    public static final long INFINITE_BETA = Long.MAX_VALUE;
    private static final long TIMING_TOLERANCE_NS = TimeUnit.MILLISECONDS.toNanos(2);

    private static class TimingConfig {
        private final long alphaNs;
        private final long betaNs;

        private TimingConfig(long alphaNs, long betaNs) {
            this.alphaNs = alphaNs;
            this.betaNs = betaNs;
        }
    }

    private static class RuntimeState {
        private boolean sensitized;
        private long enabledAtNs;

        private RuntimeState() {
            this.sensitized = false;
            this.enabledAtNs = 0L;
        }
    }

    private final Map<Integer, TimingConfig> timedTransitions;
    private final Map<Integer, RuntimeState> runtimeStates;
    private final LongSupplier clockNs;

    /**
     * Construye el gestor temporal usando {@link System#nanoTime()} como reloj.
     */
    public TimeRestrictions() {
        this(System::nanoTime);
    }

    /**
     * Construye el gestor temporal con un proveedor de tiempo inyectable.
     *
     * <p>Visible a paquete para facilitar pruebas determinísticas del
     * comportamiento temporal.
     *
     * @param clockNs proveedor de tiempo en nanosegundos.
     */
    TimeRestrictions(LongSupplier clockNs) {
        this.timedTransitions = new HashMap<>();
        this.runtimeStates = new HashMap<>();
        this.clockNs = clockNs;
    }

    /**
     * Configura una transición temporizada con ETF (alpha) y beta infinito.
     *
     * @param transition número de transición.
     * @param alphaMs ETF relativo al instante de sensibilización.
     */
    public void setTimedTransition(int transition, long alphaMs) {
        setTimedTransition(transition, alphaMs, INFINITE_BETA);
    }

    /**
     * Configura una transición temporizada con ETF (alpha) y LTF (beta).
     *
     * <p>En la configuración actual del monitor se usa beta infinito por
     * defecto, pero se admite cualquier beta válido (beta >= alpha).
     *
     * @param transition número de transición.
     * @param alphaMs ETF relativo al instante de sensibilización.
     * @param betaMs LTF relativo al instante de sensibilización.
     */
    public void setTimedTransition(int transition, long alphaMs, long betaMs) {
        if (alphaMs < 0) {
            throw new IllegalArgumentException("alphaMs debe ser >= 0");
        }
        if (betaMs != INFINITE_BETA && betaMs < alphaMs) {
            throw new IllegalArgumentException("betaMs debe ser >= alphaMs o infinito");
        }
        long alphaNs = TimeUnit.MILLISECONDS.toNanos(alphaMs);
        long betaNs = (betaMs == INFINITE_BETA) ? INFINITE_BETA : TimeUnit.MILLISECONDS.toNanos(betaMs);
        timedTransitions.put(transition, new TimingConfig(alphaNs, betaNs));
        runtimeStates.put(transition, new RuntimeState());
    }

    /**
     * Verifica si una transición tiene restricciones de tiempo.
     *
     * @param transition número de transición.
     * @return {@code true} si la transición es temporizada; {@code false} en caso contrario.
     */
    public boolean isTimedTransition(int transition) {
        return timedTransitions.containsKey(transition);
    }

    /**
     * Refresca estado temporal de una transición según su sensibilización actual.
     *
     * @param transition número de transición.
     * @param isSensitized {@code true} si la transición está sensibilizada.
     * @return {@code true} si se detectó una nueva habilitación en esta actualización;
     *         {@code false} en cualquier otro caso.
     */
    public boolean updateSensitizationState(int transition, boolean isSensitized) {
        if (!isTimedTransition(transition)) {
            return false;
        }
        RuntimeState state = runtimeStates.get(transition);
        if (isSensitized && !state.sensitized) {
            state.sensitized = true;
            state.enabledAtNs = clockNs.getAsLong();
            return true;
        }
        if (!isSensitized && state.sensitized) {
            state.sensitized = false;
        }
        return false;
    }

    /**
     * Refresca estado temporal de todas las transiciones temporizadas desde la matriz de sensibilización.
     *
     * @param sensitized matriz 1xN de transiciones sensibilizadas.
     * @return lista de transiciones que pasaron de no sensibilizadas a sensibilizadas.
     */
    public List<Integer> updateFromSensitized(DMatrixRMaj sensitized) {
        List<Integer> newlyEnabledTransitions = new ArrayList<>();
        for (Map.Entry<Integer, TimingConfig> entry : timedTransitions.entrySet()) {
            int transition = entry.getKey();
            boolean isSensitized = sensitized.get(0, transition) == 1;
            if (updateSensitizationState(transition, isSensitized)) {
                newlyEnabledTransitions.add(transition);
            }
        }
        return newlyEnabledTransitions;
    }

    /**
     * Evalúa si una transición puede dispararse en el instante actual.
     *
     * @param transition número de transición.
     * @return resultado de evaluación temporal para el disparo.
     */
    public FireEvaluation evaluateFire(int transition) {
        if (!isTimedTransition(transition)) {
            return FireEvaluation.ALLOWED;
        }
        RuntimeState state = runtimeStates.get(transition);
        if (!state.sensitized) {
            return FireEvaluation.NOT_ENABLED;
        }

        TimingConfig config = timedTransitions.get(transition);
        long elapsed = clockNs.getAsLong() - state.enabledAtNs;
        if (elapsed + TIMING_TOLERANCE_NS < config.alphaNs) {
            return FireEvaluation.TOO_EARLY;
        }
        if (elapsed - TIMING_TOLERANCE_NS > config.betaNs) {
            return FireEvaluation.NOT_ENABLED;
        }
        return FireEvaluation.ALLOWED;
    }

    /**
     * Tiempo restante para alcanzar ETF.
     *
     * @param transition número de transición.
     * @return milisegundos restantes para ETF ({@code 0} si no aplica).
     */
    public long getRemainingToEarliest(int transition) {
        if (!isTimedTransition(transition)) {
            return 0L;
        }
        RuntimeState state = runtimeStates.get(transition);
        if (!state.sensitized) {
            return 0L;
        }
        TimingConfig config = timedTransitions.get(transition);
        long elapsed = clockNs.getAsLong() - state.enabledAtNs;
        long remainingNs = Math.max(config.alphaNs - elapsed - TIMING_TOLERANCE_NS, 0L);
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNs);
        if (remainingNs > 0 && remainingMs == 0L) {
            return 1L;
        }
        return remainingMs;
    }

    /**
     * Marca nueva instancia de habilitación tras disparar la transición (si continúa sensibilizada).
     *
     * @param transition número de transición.
     * @param isStillSensitized {@code true} si continúa sensibilizada tras el disparo.
     */
    public void onTransitionFired(int transition, boolean isStillSensitized) {
        if (!isTimedTransition(transition)) {
            return;
        }
        RuntimeState state = runtimeStates.get(transition);
        if (isStillSensitized) {
            state.enabledAtNs = clockNs.getAsLong();
            state.sensitized = true;
        } else {
            state.sensitized = false;
        }
    }
}
