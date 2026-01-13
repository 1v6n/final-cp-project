package org.concurrent.project;

import java.util.ArrayList;
import java.util.List;
import org.ejml.data.DMatrixRMaj;

public final class Invariants {

    private Invariants() {}

    public static final class PInvariant {
        public final String name;
        public final int[] places;
        public final int expected;

        public PInvariant(String name, int[] places, int expected) {
            this.name = name;
            this.places = places;
            this.expected = expected;
        }
    }

    /**
     * Resultado del chequeo de un P-invariante.
     *
     * <p>
     * Este record encapsula toda la información relevante del análisis de un
     * invariante sobre un marcado específico, permitiendo su posterior registro
     * o procesamiento sin necesidad de recalcular valores.
     *
     * @param name     Nombre del invariante evaluado.
     * @param ok       Indica si el invariante se cumple para el marcado dado.
     * @param got      Valor obtenido al sumar los tokens de las plazas.
     * @param expected Valor esperado según la definición del invariante.
     */
    public record PInvariantResult(
            String name,
            boolean ok,
            int got,
            int expected
    ) {}

    /**
     * Devuelve el conjunto de P-invariantes definidos para el modelo actual.
     *
     * <p>
     * Estos invariantes fueron obtenidos a partir del análisis estructural de la
     * red de Petri y representan restricciones que deben mantenerse a lo largo
     * de toda la ejecución concurrente.
     *
     * @return Lista inmutable de P-invariantes del sistema.
     */

    public static List<PInvariant> defaultPInvariants() {
        return List.of(

            new PInvariant("P1+P2=1", new int[]{1, 2}, 1),
            new PInvariant("P10+P11+P12+P13=1", new int[]{10, 11, 12, 13}, 1),
            new PInvariant("Global=5", new int[]{0, 11, 12, 13, 14, 2, 3, 5, 8, 9}, 5), 
            new PInvariant("P2+P3+P4=5", new int[]{2, 3, 4}, 5),
            new PInvariant("P5+P6=1", new int[]{5, 6}, 1),
            new PInvariant("P7+P8=1", new int[]{7, 8}, 1)
        );
    }


    /**
     * Verifica el cumplimiento de un conjunto de P-invariantes sobre un marcado
     * específico de la red de Petri.
     *
     * <p>
     * Para cada invariante, se calcula la suma de tokens en las plazas indicadas
     * y se compara con el valor esperado definido. El resultado de cada chequeo
     * se devuelve de forma independiente.
     *
     * <p>
     * Este método no genera efectos colaterales ni altera el estado del sistema.
     *
     * @param marking     Marcado actual de la red de Petri
     * @param invariants  Lista de P-invariantes a evaluar.
     * @return Lista de resultados correspondientes al chequeo de cada invariante.
     */
    public static List<PInvariantResult> checkPInvariants(DMatrixRMaj marking, List<PInvariant> invariants) {
        List<PInvariantResult> out = new ArrayList<>(invariants.size());

        for (PInvariant inv : invariants) {
            int sum = 0;

            for (int p : inv.places) {
              sum += (int) Math.round(marking.get(0, p));
            }

            boolean ok = (sum == inv.expected);
            out.add(new PInvariantResult(inv.name, ok, sum, inv.expected));
        }
        return out;
    }
}
