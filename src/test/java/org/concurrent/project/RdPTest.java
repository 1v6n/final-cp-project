package org.concurrent.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.ejml.data.DMatrixRMaj;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RdPTest {
  private static final double[] INITIAL_MARKING = {
      5, 1, 0, 0, 5, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0
  };

  @Test
  void startsWithExpectedMarkingAndOnlyT0Sensitized() {
    RdP rdp = new RdP();

    assertArrayEquals(INITIAL_MARKING, rdp.getMarcadoActual().getData());
    for (int transition = 0; transition < rdp.getIncidencia().numCols; transition++) {
      assertEquals(transition == 0 ? 1.0 : 0.0, rdp.getSensitized().get(0, transition));
    }
  }

  @Test
  void publicIsSensitizedAgreesWithTheSensitizedVector() throws Exception {
    RdP rdp = new RdP();
    Method isSensitized = RdP.class.getMethod("isSensitized", int.class);

    for (int transition = 0; transition < rdp.getIncidencia().numCols; transition++) {
      assertEquals(
          rdp.getSensitized().get(0, transition) == 1.0,
          isSensitized.invoke(rdp, transition));
    }
  }

  @Test
  void rejectsVectorsWithInvalidShapeWithoutChangingTheMarking() {
    RdP rdp = new RdP();
    double[] before = rdp.getMarcadoActual().getData().clone();

    assertThrows(IllegalArgumentException.class,
        () -> rdp.fireTransition(new DMatrixRMaj(1, rdp.getIncidencia().numCols)));
    assertArrayEquals(before, rdp.getMarcadoActual().getData());
  }

  @Test
  void rejectsVectorsWithoutExactlyOneBinarySelection() {
    RdP rdp = new RdP();
    int transitions = rdp.getIncidencia().numCols;

    assertThrows(IllegalArgumentException.class,
        () -> rdp.fireTransition(new DMatrixRMaj(transitions, 1)));

    DMatrixRMaj twoTransitions = new DMatrixRMaj(transitions, 1);
    twoTransitions.set(0, 0, 1);
    twoTransitions.set(1, 0, 1);
    assertThrows(IllegalArgumentException.class, () -> rdp.fireTransition(twoTransitions));

    DMatrixRMaj nonBinary = new DMatrixRMaj(transitions, 1);
    nonBinary.set(0, 0, 0.5);
    assertThrows(IllegalArgumentException.class, () -> rdp.fireTransition(nonBinary));
  }

  @Test
  void rejectsAnUnsensitizedTransitionWithoutChangingTheMarking() {
    RdP rdp = new RdP();
    double[] before = rdp.getMarcadoActual().getData().clone();

    assertThrows(IllegalArgumentException.class, () -> rdp.fireTransition(firingVector(rdp, 1)));

    assertArrayEquals(before, rdp.getMarcadoActual().getData());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tInvariants")
  void everyCompleteTInvariantPreservesMarkingAndPInvariants(
      String name,
      List<Integer> transitions) {
    RdP rdp = new RdP();

    for (int transition : transitions) {
      assertEquals(1.0, rdp.getSensitized().get(0, transition),
          () -> name + ": T" + transition + " no estaba sensibilizada");
      rdp.fireTransition(firingVector(rdp, transition));
      assertNoNegativeTokens(rdp);
      assertAllPInvariants(rdp, name + " después de T" + transition);
    }

    assertArrayEquals(INITIAL_MARKING, rdp.getMarcadoActual().getData(), name);
  }

  private static Stream<Object[]> tInvariants() {
    return Stream.of(
        new Object[] { "upper-confirmed", List.of(0, 1, 2, 5, 6, 9, 10, 11) },
        new Object[] { "upper-cancelled", List.of(0, 1, 2, 5, 7, 8, 11) },
        new Object[] { "lower-confirmed", List.of(0, 1, 3, 4, 6, 9, 10, 11) },
        new Object[] { "lower-cancelled", List.of(0, 1, 3, 4, 7, 8, 11) });
  }

  private static DMatrixRMaj firingVector(RdP rdp, int transition) {
    DMatrixRMaj vector = new DMatrixRMaj(rdp.getIncidencia().numCols, 1);
    vector.set(transition, 0, 1);
    return vector;
  }

  private static void assertNoNegativeTokens(RdP rdp) {
    for (double token : rdp.getMarcadoActual().getData()) {
      assertTrue(token >= 0, "El marcado contiene tokens negativos");
    }
  }

  private static void assertAllPInvariants(RdP rdp, String context) {
    for (Invariants.PInvariantResult result : Invariants.checkPInvariants(
        rdp.getMarcadoActual(),
        Invariants.defaultPInvariants())) {
      assertTrue(result.ok(),
          () -> context + ": " + result.name() + " obtuvo " + result.got()
              + " y esperaba " + result.expected());
    }
  }
}
