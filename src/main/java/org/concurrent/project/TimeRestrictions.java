package org.concurrent.project;

import org.ejml.data.DMatrixRMaj;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Gestiona restricciones temporales de disparo para transiciones de la RdP.
 * <p>
 * Implementa evaluación en semántica débil sobre EFT (alpha) por instancia
 * de habilitación, con LFT infinito (sin expiración).
 */
public class TimeRestrictions {
    /** Resultado de evaluación temporal para un intento de disparo. */
    public static final long INFINITE_BETA = Long.MAX_VALUE;

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
    TimeRestrictions(int[][] timedTransitionsConfig) {
        timedTransitions = new HashMap<>();
        runtimeStates = new HashMap<>();
        clockNs = System::nanoTime;

        for (int[] config : timedTransitionsConfig) {
            setTimedTransition(config[0], config[1], INFINITE_BETA);
        }
    }

    /**
     * Configura una transición temporizada con EFT (alpha) y LFT (beta).
     * <p>
     * En la configuración actual del monitor se usa beta infinito por
     * defecto, pero se admite cualquier beta válido (beta >= alpha).
     *
     * @param transition número de transición.
     * @param alphaMs    EFT relativo al instante de sensibilización.
     * @param betaMs     LFT relativo al instante de sensibilización.
     */
    public void setTimedTransition(int transition, long alphaMs, long betaMs) {
        if (alphaMs < 0) {
            throw new IllegalArgumentException("alphaMs debe ser >= 0");
        }

        if (betaMs < alphaMs) {
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
     * @return {@code true} si la transición es temporizada; {@code false} en caso
     *         contrario.
     */
    public boolean isTimedTransition(int transition) {
        return timedTransitions.containsKey(transition);
    }

    /**
     * Refresca estado temporal de una transición según su sensibilización actual.
     *
     * @param transition   número de transición.
     * @param isSensitized {@code true} si la transición está sensibilizada.
     */
    public void updateSensitizationState(int transition, boolean isSensitized) {
        if (!isTimedTransition(transition)) {
            return;
        }

        RuntimeState state = runtimeStates.get(transition);
        if (isSensitized && !state.sensitized) {
            state.sensitized = true;
            state.enabledAtNs = clockNs.getAsLong();
            return;
        }

        if (!isSensitized && state.sensitized) {
            state.sensitized = false;
        }
    }

    /**
     * Refresca estado temporal de todas las transiciones temporizadas desde la
     * matriz de sensibilización y, para la transición recién disparada, reinicia
     * la ventana de tiempo si permanece sensibilizada.
     *
     * @param sensitized    matriz 1xN de transiciones sensibilizadas.
     * @param firedTransition transición que acaba de dispararse.
     */
    public void refreshTimedState(DMatrixRMaj sensitized, int firedTransition) {
        for (Map.Entry<Integer, TimingConfig> entry : timedTransitions.entrySet()) {
            int transition = entry.getKey();
            boolean isSensitized = sensitized.get(0, transition) == 1;

            if (transition == firedTransition) {
                RuntimeState state = runtimeStates.get(transition);

                if (isSensitized) {
                    state.enabledAtNs = clockNs.getAsLong();
                    state.sensitized = true;
                } else {
                    state.sensitized = false;
                }
            } else {
                updateSensitizationState(transition, isSensitized);
            }
        }
    }

    /**
     * Evalúa si una transición puede dispararse en el instante actual o no.
     *
     * @param transition número de transición.
     * @return {@code true} si la transición puede dispararse; {@code false} en caso
     *         contrario.
     */
    public boolean canFire(int transition) throws InterruptedException {
        if (!isTimedTransition(transition)) {
            return true;
        }

        RuntimeState state = runtimeStates.get(transition);
        if (!state.sensitized) {
            throw new IllegalStateException();
        }

        TimingConfig config = timedTransitions.get(transition);
        long elapsed = clockNs.getAsLong() - state.enabledAtNs;

        return elapsed >= config.alphaNs;
    }

    /**
     * Tiempo restante para alcanzar EFT.
     *
     * @param transition número de transición.
     * @return milisegundos restantes para EFT ({@code 0} si no aplica).
     */
    public long getRemainingToEFT(int transition) {
        if (!isTimedTransition(transition)) {
            return 0L;
        }

        RuntimeState state = runtimeStates.get(transition);
        if (!state.sensitized) {
            return 0L;
        }

        TimingConfig config = timedTransitions.get(transition);
        long elapsed = clockNs.getAsLong() - state.enabledAtNs;
        long remainingNs = Math.max(0L, config.alphaNs - elapsed);
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNs);

        if (remainingNs > 0 && remainingMs == 0L) {
            return 1L;
        }

        return remainingMs;
    }

    /**
     * Espera hasta el próximo instante de disparo permitido de una transición.
     * El cálculo de la demora queda encapsulado junto al estado temporal.
     *
     * @param transition transición en estado {TOO_EARLY}.
     * @throws InterruptedException si el hilo es interrumpido durante la espera.
     */
    void awaitUntilEFT(int transition) throws InterruptedException {
        long remainingMs;
        while ((remainingMs = getRemainingToEFT(transition)) > 0) {
            Thread.sleep(remainingMs);
        }
    }
}
