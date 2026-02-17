package org.concurrent.project;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Random;

/**
 * Define una política de selección entre transiciones candidatas.
 *
 * <p>En modo balanceado prioriza alternancia entre T2 y T3 cuando ambas están
 * disponibles. En otros casos selecciona una transición posible al azar.
 */
public class Policy {
    private boolean type;
    private AtomicInteger CounterT2;
    private AtomicInteger CounterT3;

    /**
     * Construye la política de selección.
     *
     * @param type tipo de política activa.
     */
    public Policy(boolean type) {
        this.type = type;
        this.CounterT2 = new AtomicInteger(0);
        this.CounterT3 = new AtomicInteger(0);
    }

    /**
     * Determina qué transición seleccionar bajo la política configurada.
     *
     * @param possibleTransitions lista de transiciones candidatas.
     * @return índice de transición seleccionada.
     */
    public int whichTransition(ArrayList<Integer> possibleTransitions) {
        if (type) {
            // Balanceo explícito para el conflicto entre T2 y T3.
            if (possibleTransitions.get(2) == 1 && possibleTransitions.get(3) == 1) {
                if (CounterT2.get() < CounterT3.get()) {
                    CounterT2.incrementAndGet();
                    return 2;
                } else if (CounterT2.get() > CounterT3.get()) {
                    CounterT3.incrementAndGet();
                    return 3;
                } else {
                    // Si están empatadas, desempata aleatoriamente.
                    Random random = new Random();
                    return random.nextBoolean() ? 2 : 3;
                }
            }
        }

        if (!possibleTransitions.isEmpty()) {
            Random random = new Random();
            return possibleTransitions.get(random.nextInt(possibleTransitions.size()));
        }

        throw new IllegalStateException("No possible transitions available.");
    }
}
