package org.concurrent.project;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

public class RdP {

    private DMatrixRMaj Incidencia;
    private DMatrixRMaj IncidenciaSalida;
    private DMatrixRMaj MarcadoActual;
    private DMatrixRMaj Sensibilizadas;

    private final double[] M0 = {5, 1, 0, 0, 5, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0};
    private final double[][] MatrixIncidencia = {
            {-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
            {-1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, 1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0},
            {-1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 0},
            {0, 0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, -1, 1, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 1, -1, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 1, 1, -1, -1, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, -1, -1, 1, 0, 1, 0},
            {0, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 1, -1, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 1, -1, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, -1},
    };

    private final double[][] MatrixIncidenciaSalida = {
            {1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
    };

    public RdP() {
        this.MarcadoActual = new DMatrixRMaj(1, M0.length, true, M0);
        this.Incidencia = new DMatrixRMaj(MatrixIncidencia);
        this.IncidenciaSalida = new DMatrixRMaj(MatrixIncidenciaSalida);
        this.Sensibilizadas = new DMatrixRMaj(1, Incidencia.numCols);
        this.transicionesSensibilizadas();
    }

    /**
     * Dispara una transición en la red de Petri.
     *
     * <p>Este método intenta disparar una transición específica en la red de Petri basándose en un
     * vector de disparo. Primero, verifica que el vector de disparo contenga exactamente un '1' y el
     * resto '0's, indicando que unicamente quiere disparar 1 transición. Luego, calcula el cambio en
     * el marcado de la red resultante de disparar la transición. Si el disparo resulta en un estado
     * inválido de la red (por ejemplo, un marcado negativo), se cancela el disparo y se notifica.
     * Finalmente, si el disparo es válido, se actualiza el marcado actual de la red y se recalculan
     * las transiciones sensibilizadas basadas en el nuevo estado de la red.
     *
     * @param firingVector Vector que indica la transición a disparar, debe contener un solo '1'.
     * @param transition El índice de la transición a disparar.
     * @throws IllegalArgumentException Si el vector de disparo no cumple con los requisitos.
     */
    public void fireTransition(DMatrixRMaj firingVector, int transition) {
        if (firingVector.numCols != 1 || firingVector.numRows != Incidencia.numCols) {
            throw new IllegalArgumentException(
                    "El vector de disparo debe tener 1 columna y " + Incidencia.numCols + " filas.");
        }
        int contarUnos = 0;
        for (int i = 0; i < firingVector.numRows; i++) {
            if (firingVector.get(i, 0) == 1) {
                contarUnos++;
            } else if (firingVector.get(i, 0) != 0) {
                throw new IllegalArgumentException("El vector de disparo solo debe contener 0 y 1.");
            }
        }
        if (contarUnos != 1) {
            throw new IllegalArgumentException("El vector de disparo solo debe tener un 1.");
        }

        DMatrixRMaj change = new DMatrixRMaj(Incidencia.numRows, 1);
        CommonOps_DDRM.mult(Incidencia, firingVector, change);

        for (int i = 0; i < change.numRows; i++) {
            if (MarcadoActual.get(0, i) + change.get(i, 0) < 0) {
                System.out.println("No se puede disparar la transición por insuficiencia de tokens.");
                return;
            }
        }

        CommonOps_DDRM.addEquals(MarcadoActual, CommonOps_DDRM.transpose(change, null));
        transicionesSensibilizadas();
        System.out.println(
                "Transición " + transition + " disparada por " + Thread.currentThread().getName());
    }

    /**
     * Actualiza el estado de sensibilización de las transiciones.
     *
     * <p>Este método revisa cada transición de la red de Petri para determinar si está sensibilizada,
     * es decir, si cumple con las condiciones necesarias para ser disparada dada la configuración
     * actual de tokens en los lugares de la red. Utiliza la matriz de marcado actual y la matriz de
     * incidencia de salida para realizar esta verificación. Para cada transición, si todos los
     * lugares relevantes tienen suficientes tokens según lo especificado en la matriz de transición
     * posible, la transición se marca como sensibilizada. De lo contrario, se marca como no
     * sensibilizada. El resultado de este proceso es la actualización de la matriz de transiciones
     * sensibilizadas, reflejando el estado actual de qué transiciones pueden ser disparadas.
     */
    private void transicionesSensibilizadas() {
        for (int t = 0; t < IncidenciaSalida.numRows; t++) {
            boolean puedeDisparar = true;
            for (int p = 0; p < MarcadoActual.numCols; p++) {
                if (MarcadoActual.get(0, p) < IncidenciaSalida.get(t, p)) {
                    puedeDisparar = false;
                    break;
                }
            }
            Sensibilizadas.set(0, t, puedeDisparar ? 1 : 0);
        }
    }

    public void printMarcadoActual() {
        for (int i = 0; i < MarcadoActual.numRows; i++) {
            for (int j = 0; j < MarcadoActual.numCols; j++) {
                System.out.print(MarcadoActual.get(i, j) + " ");
            }
            System.out.println();
        }
    }

    public DMatrixRMaj getMarcadoActual() {
        return MarcadoActual;
    }

    public DMatrixRMaj getIncidencia() {
        return Incidencia;
    }

    public DMatrixRMaj getSensitized() {
        return Sensibilizadas;
    }
}
