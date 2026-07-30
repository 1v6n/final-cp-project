package org.concurrent.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ejml.data.DMatrixRMaj;
import org.junit.jupiter.api.Test;

class InvariantsTest {

  @Test
  void allDefaultPInvariantsHoldForTheInitialMarking() {
    List<Invariants.PInvariantResult> results = Invariants.checkPInvariants(
        new RdP().getMarcadoActual(),
        Invariants.defaultPInvariants());

    assertEquals(Invariants.defaultPInvariants().size(), results.size());
    assertTrue(results.stream().allMatch(Invariants.PInvariantResult::ok));
  }

  @Test
  void reportsTheExactInvariantThatWasBroken() {
    DMatrixRMaj marking = new RdP().getMarcadoActual().copy();
    marking.set(0, 1, 0);

    List<Invariants.PInvariantResult> results = Invariants.checkPInvariants(
        marking,
        Invariants.defaultPInvariants());

    Invariants.PInvariantResult p1p2 = results.stream()
        .filter(result -> result.name().equals("P1+P2=1"))
        .findFirst()
        .orElseThrow();

    assertFalse(p1p2.ok());
    assertEquals(0, p1p2.got());
    assertEquals(1, p1p2.expected());
  }
}
