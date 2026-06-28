package org.concurrent.project;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

/**
 * Modelo de Red de Petri con marcado y sensibilización estructural.
 * <p>
 * Contiene matrices de incidencia, marcado actual y cálculo de transiciones
 * sensibilizadas. El disparo actualiza el marcado si no produce tokens
 * negativos.
 */
public class RdP {
    private DMatrixRMaj Incidencia;
    private DMatrixRMaj IncidenciaEntradaTraspuesta;
    private DMatrixRMaj MarcadoActual;
    private DMatrixRMaj Sensibilizadas;

    private final double[] M0 = { 5, 1, 0, 0, 5, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0 };
    private final double[][] MatrixIncidencia = {
            { -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 },
            { -1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
            { 1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0 },
            { -1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 0 },
            { 0, 0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, -1, 1, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 1, -1, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 1, 1, -1, -1, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, -1, -1, 1, 0, 1, 0 },
            { 0, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 1, -1, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, -1, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, -1 },
    };

    private final double[][] MatrixIncidenciaEntradaTraspuesta = {
            { 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0 },
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 },
    };

    /**
     * Inicializa la red con marcado inicial y matrices de incidencia predefinidas.
     */
    public RdP() {
        this.MarcadoActual = new DMatrixRMaj(1, M0.length, true, M0);
        this.Incidencia = new DMatrixRMaj(MatrixIncidencia);
        this.IncidenciaEntradaTraspuesta = new DMatrixRMaj(MatrixIncidenciaEntradaTraspuesta);
        this.Sensibilizadas = new DMatrixRMaj(1, Incidencia.numCols);
        this.transicionesSensibilizadas();
    }

    /**
     * Dispara una transición en la red de Petri.
     * <p>
     * Valida que el vector de disparo sea unitario (un único {@code 1} y el
     * resto {@code 0}), calcula el cambio de marcado y rechaza el disparo si
     * produciría tokens negativos. Si es válido, actualiza el marcado y
     * recalcula las transiciones sensibilizadas.
     *
     * @param firingVector vector de disparo a aplicar.
     * @throws IllegalArgumentException si el vector no cumple el formato esperado.
     */
    public void fireTransition(DMatrixRMaj firingVector) throws IllegalArgumentException {
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
                throw new IllegalArgumentException("Disparo inválido: produciría tokens negativos en la plaza " + i);
            }
        }

        CommonOps_DDRM.addEquals(MarcadoActual, CommonOps_DDRM.transpose(change, null));
        transicionesSensibilizadas();
    }

    /**
     * Actualiza el estado de sensibilización de las transiciones.
     * <p>
     * Una transición se marca como sensibilizada si todas sus plazas de entrada
     * poseen tokens suficientes según la matriz de incidencia de entrada traspuesta.
     */
    private void transicionesSensibilizadas() {
        for (int t = 0; t < IncidenciaEntradaTraspuesta.numRows; t++) {
            boolean puedeDisparar = true;
            for (int p = 0; p < MarcadoActual.numCols; p++) {
                if (MarcadoActual.get(0, p) < IncidenciaEntradaTraspuesta.get(t, p)) {
                    puedeDisparar = false;
                    break;
                }
            }
            Sensibilizadas.set(0, t, puedeDisparar ? 1 : 0);
        }
    }

    /**
     * Devuelve el marcado actual de la red.
     *
     * @return matriz 1xP con tokens actuales por plaza.
     */
    public DMatrixRMaj getMarcadoActual() {
        return MarcadoActual;
    }

    /**
     * Devuelve la matriz de incidencia de la red.
     *
     * @return matriz de incidencia (plazas x transiciones).
     */
    public DMatrixRMaj getIncidencia() {
        return Incidencia;
    }

    /**
     * Devuelve el vector de transiciones sensibilizadas.
     *
     * @return matriz 1xT con 1 para transiciones sensibilizadas y 0 en caso
     *         contrario.
     */
    public DMatrixRMaj getSensitized() {
        return Sensibilizadas;
    }
}
