package org.concurrent.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.concurrent.project.Policy.PolicyMode;
import org.junit.jupiter.api.Test;

class PolicyTest {

  @Test
  void balancedAgentsStartWithAlternativeAndThenAlternate() {
    assertSequence(
        new Policy(PolicyMode.BALANCED),
        List.of(2, 3),
        List.of(3, 2, 3, 2, 3, 2));
  }

  @Test
  void balancedReservationsStartWithAlternativeAndThenAlternate() {
    assertSequence(
        new Policy(PolicyMode.BALANCED),
        List.of(6, 7),
        List.of(7, 6, 7, 6, 7, 6));
  }

  @Test
  void prioritizedAgentsUseSeventyFivePercentBresenhamFromZero() {
    Policy policy = new Policy(PolicyMode.PRIORITIZED);
    List<Integer> actual = chooseRepeatedly(policy, List.of(2, 3), 8);

    assertEquals(List.of(3, 2, 2, 2, 3, 2, 2, 2), actual);
    assertEveryPrefixSatisfiesBresenham(actual, 2, 75);
  }

  @Test
  void prioritizedReservationsUseEightyPercentBresenhamFromZero() {
    Policy policy = new Policy(PolicyMode.PRIORITIZED);
    List<Integer> actual = chooseRepeatedly(policy, List.of(6, 7), 10);

    assertEquals(List.of(7, 6, 6, 6, 6, 7, 6, 6, 6, 6), actual);
    assertEveryPrefixSatisfiesBresenham(actual, 6, 80);
  }

  @Test
  void agentAndReservationResiduesAreIndependent() {
    Policy policy = new Policy(PolicyMode.PRIORITIZED);

    assertEquals(7, policy.choose(List.of(6, 7)));
    assertEquals(3, policy.choose(List.of(2, 3)));
    assertEquals(6, policy.choose(List.of(6, 7)));
    assertEquals(2, policy.choose(List.of(2, 3)));
  }

  @Test
  void choosingWithoutAConflictDoesNotConsumeReservationResidue() {
    Policy policy = new Policy(PolicyMode.PRIORITIZED);

    assertEquals(6, policy.choose(List.of(6)));
    assertEquals(7, policy.choose(List.of(6, 7)));
  }

  @Test
  void realFiresDoNotAdvanceConflictResidues() {
    Policy policy = new Policy(PolicyMode.PRIORITIZED);

    policy.onTransitionFired(2);
    policy.onTransitionFired(6);

    assertEquals(3, policy.choose(List.of(2, 3)));
    assertEquals(7, policy.choose(List.of(6, 7)));
  }

  @Test
  void agentConflictHasPrecedenceWithoutConsumingReservationResidue() {
    Policy policy = new Policy(PolicyMode.BALANCED);

    assertEquals(3, policy.choose(List.of(2, 3, 6, 7)));
    assertEquals(7, policy.choose(List.of(6, 7)));
  }

  @Test
  void noneModeSelectsOnlyFromEffectiveCandidates() {
    Policy policy = new Policy(PolicyMode.NONE);
    List<Integer> candidates = List.of(1, 4, 9);

    for (int i = 0; i < 100; i++) {
      assertTrue(candidates.contains(policy.choose(candidates)));
    }
  }

  @Test
  void aSingleCandidateIsAlwaysSelectedInEveryMode() {
    for (PolicyMode mode : PolicyMode.values()) {
      assertEquals(9, new Policy(mode).choose(List.of(9)));
    }
  }

  @Test
  void chooseRejectsNullOrEmptyCandidates() {
    for (PolicyMode mode : PolicyMode.values()) {
      Policy policy = new Policy(mode);
      assertThrows(IllegalArgumentException.class, () -> policy.choose(null));
      assertThrows(IllegalArgumentException.class, () -> policy.choose(List.of()));
    }
  }

  private static void assertSequence(Policy policy, List<Integer> candidates, List<Integer> expected) {
    assertEquals(expected, chooseRepeatedly(policy, candidates, expected.size()));
  }

  private static List<Integer> chooseRepeatedly(Policy policy, List<Integer> candidates, int times) {
    List<Integer> selections = new ArrayList<>(times);
    for (int i = 0; i < times; i++) {
      selections.add(policy.choose(candidates));
    }
    return selections;
  }

  private static void assertEveryPrefixSatisfiesBresenham(
      List<Integer> selections,
      int preferredTransition,
      int preferredPercent) {
    int selectedPreferred = 0;
    for (int n = 1; n <= selections.size(); n++) {
      if (selections.get(n - 1) == preferredTransition) {
        selectedPreferred++;
      }

      int residue = n * preferredPercent - 100 * selectedPreferred;
      assertEquals(n * preferredPercent, 100 * selectedPreferred + residue);
      assertTrue(residue >= 0 && residue < 100,
          "Residuo fuera de rango para n=" + n + ": " + residue);
    }
  }
}
