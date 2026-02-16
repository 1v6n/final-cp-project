package org.concurrent.project;

import org.ejml.data.DMatrixRMaj;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class TimeRestrictions {
    public enum FireEvaluation {
        ALLOWED,
        TOO_EARLY,
        EXPIRED,
        NOT_ENABLED
    }

    private static final long INFINITE_BETA = Long.MAX_VALUE;
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
        private boolean expired;

        private RuntimeState() {
            this.sensitized = false;
            this.enabledAtNs = 0L;
            this.expired = false;
        }
    }

    private final Map<Integer, TimingConfig> timedTransitions;
    private final Map<Integer, RuntimeState> runtimeStates;
    private final LongSupplier clockNs;

    public TimeRestrictions() {
        this(System::nanoTime);
    }

    TimeRestrictions(LongSupplier clockNs) {
        this.timedTransitions = new HashMap<>();
        this.runtimeStates = new HashMap<>();
        this.clockNs = clockNs;
    }

    /**
     * Configura una transición temporizada en modo delay [d, d] en semántica débil.
     *
     * @param transition número de transición
     * @param delayMs intervalo puntual en milisegundos
     */
    public void setTimedTransition(int transition, long delayMs) {
        setTimedTransition(transition, delayMs, delayMs);
    }

    /**
     * Configura una transición temporizada con intervalo [alpha, beta].
     *
     * @param transition número de transición
     * @param alphaMs ETF relativo al instante de sensibilización
     * @param betaMs LTF relativo al instante de sensibilización (Long.MAX_VALUE para infinito)
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
     * @param transition número de transición
     * @return true si es una transición con restricción de tiempo, false en caso contrario
     */
    public boolean isTimedTransition(int transition) {
        return timedTransitions.containsKey(transition);
    }

    /**
     * Refresca estado temporal de una transición según su sensibilización actual.
     *
     * @param transition número de transición
     * @param isSensitized true si la transición está sensibilizada en el marcado actual
     */
    public void updateSensitizationState(int transition, boolean isSensitized) {
        if (!isTimedTransition(transition)) {
            return;
        }
        RuntimeState state = runtimeStates.get(transition);
        if (isSensitized && !state.sensitized) {
            state.sensitized = true;
            state.enabledAtNs = clockNs.getAsLong();
            state.expired = false;
            return;
        }
        if (!isSensitized && state.sensitized) {
            state.sensitized = false;
            state.expired = false;
        }
    }

    /**
     * Refresca estado temporal de todas las transiciones temporizadas desde la matriz de sensibilización.
     *
     * @param sensitized matriz 1xN de transiciones sensibilizadas
     */
    public void updateFromSensitized(DMatrixRMaj sensitized) {
        for (Map.Entry<Integer, TimingConfig> entry : timedTransitions.entrySet()) {
            int transition = entry.getKey();
            boolean isSensitized = sensitized.get(0, transition) == 1;
            updateSensitizationState(transition, isSensitized);
        }
    }

    /**
     * Evalúa si una transición puede dispararse en el instante actual.
     *
     * @param transition número de transición
     * @return estado de evaluación temporal para el disparo
     */
    public FireEvaluation evaluateFire(int transition) {
        return evaluateFire(transition, true);
    }

    /**
     * Evalúa una transición sin mutar el estado temporal (no marca expiración).
     *
     * @param transition número de transición
     * @return estado de evaluación temporal para decisiones de selección/liberación
     */
    public FireEvaluation evaluateFireNoSideEffects(int transition) {
        return evaluateFire(transition, false);
    }

    private FireEvaluation evaluateFire(int transition, boolean mutateState) {
        if (!isTimedTransition(transition)) {
            return FireEvaluation.ALLOWED;
        }
        RuntimeState state = runtimeStates.get(transition);
        if (!state.sensitized) {
            return FireEvaluation.NOT_ENABLED;
        }
        if (state.expired) {
            return FireEvaluation.EXPIRED;
        }

        TimingConfig config = timedTransitions.get(transition);
        long elapsed = clockNs.getAsLong() - state.enabledAtNs;
        if (elapsed + TIMING_TOLERANCE_NS < config.alphaNs) {
            return FireEvaluation.TOO_EARLY;
        }
        if (config.betaNs != INFINITE_BETA && elapsed > config.betaNs + TIMING_TOLERANCE_NS) {
            if (mutateState) {
                // In weak mode on JVM, tight windows can expire due scheduling jitter.
                // Renew the timing window to avoid terminal starvation while still enforcing ETF waits.
                state.enabledAtNs = clockNs.getAsLong();
                state.expired = false;
                return FireEvaluation.TOO_EARLY;
            }
            return FireEvaluation.EXPIRED;
        }
        return FireEvaluation.ALLOWED;
    }

    /**
     * Tiempo restante para alcanzar ETF.
     *
     * @param transition número de transición
     * @return milisegundos restantes para ETF (0 si no aplica)
     */
    public long getRemainingToEarliest(int transition) {
        if (!isTimedTransition(transition)) {
            return 0L;
        }
        RuntimeState state = runtimeStates.get(transition);
        if (!state.sensitized || state.expired) {
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
     * @param transition número de transición
     * @param isStillSensitized true si continúa sensibilizada tras el disparo
     */
    public void onTransitionFired(int transition, boolean isStillSensitized) {
        if (!isTimedTransition(transition)) {
            return;
        }
        RuntimeState state = runtimeStates.get(transition);
        if (isStillSensitized) {
            state.enabledAtNs = clockNs.getAsLong();
            state.expired = false;
            state.sensitized = true;
        } else {
            state.sensitized = false;
            state.expired = false;
        }
    }
}
