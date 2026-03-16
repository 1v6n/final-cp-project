package org.concurrent.project;

import java.util.List;

public class Policy {

  public enum PolicyMode {
    BALANCED,
    PRIORITIZED
  }

  public record PolicyDecision(boolean shouldFire, int selectedTransition, boolean conflictActive) {
    public static PolicyDecision allow(int transition) {
      return new PolicyDecision(true, transition, false);
    }

    public static PolicyDecision resolve(boolean shouldFire, int selectedTransition,
        boolean conflictActive) {
      return new PolicyDecision(shouldFire, selectedTransition, conflictActive);
    }
  }

  private enum ConflictGroup {
    AGENTS,
    RESERVATIONS,
    NONE
  }

  private static final int T2 = 2;
  private static final int T3 = 3;
  private static final int T6 = 6;
  private static final int T7 = 7;

  private final PolicyMode mode;

  // Balance toggles
  private boolean lastAgentWasSuperior = false;
  private boolean lastReservationWasConfirmed = false;

  // Sticky decisions by conflict group
  private int forcedAgentTransition = -1;
  private int forcedReservationTransition = -1;

  // Contadores reales
  private int agentInferiorCount;
  private int agentSuperiorCount;
  private int confirmedReservations;
  private int cancelledReservations;
  private int reservationCycle;
  private int agentCycle;

  // Contadores SOLO en conflictos reales
  private int conflictAgentSuperior;
  private int conflictAgentInferior;

  private int conflictConfirmed;
  private int conflictCancelled;

  public Policy(PolicyMode mode) {
    this.mode = mode;
    this.conflictConfirmed = 0;
    this.conflictCancelled = 0;
    this.cancelledReservations = 0;
    this.confirmedReservations = 0;
    this.agentInferiorCount = 0;
    this.agentSuperiorCount = 0;
    this.reservationCycle = 0;
    this.agentCycle = 0;
  }

  public PolicyDecision evaluate(int transition, List<Integer> currentlyEnabled) {
    ConflictGroup group = groupForTransition(transition);
    if (group == ConflictGroup.NONE) {
      return PolicyDecision.allow(transition);
    }

    int stickySelection = getStickySelection(group);
    if (stickySelection != -1) {
      return PolicyDecision.resolve(transition == stickySelection, stickySelection,
          isConflictActive(group, currentlyEnabled));
    }

    if (!isConflictActive(group, currentlyEnabled)) {
      return PolicyDecision.allow(transition);
    }

    int selectedTransition = selectForGroup(group);
    setStickySelection(group, selectedTransition);

    return PolicyDecision.resolve(transition == selectedTransition, selectedTransition, true);
  }

  // ---------------- BALANCED ----------------
  private int selectBalancedAgent() {
    lastAgentWasSuperior = !lastAgentWasSuperior;
    return lastAgentWasSuperior ? 3 : 2;
  }

  private int selectBalancedReservation() {
    lastReservationWasConfirmed = !lastReservationWasConfirmed;
    return lastReservationWasConfirmed ? 6 : 7;
  }

  private int selectPrioritizedReservation() {

    reservationCycle = (reservationCycle + 1) % 5;

    if (reservationCycle < 4) {
      return 6; // 4 de cada 5
    } else {
      return 7; // 1 de cada 5
    }
  }

  private int selectPrioritizedAgent() {
    agentCycle = (agentCycle + 1) % 4;

    if (agentCycle < 3) {
      return T2; // superior
    } else {
      return T3; // inferior
    }
  }

  public synchronized void onTransitionFired(int transition) {
    recordRealFire(transition);
    recordAndClearStickyConflictDecisionIfNeeded(transition);
  }

  private void recordAndClearStickyConflictDecisionIfNeeded(int transition) {
    if (forcedAgentTransition == transition) {
      recordConflictDecision(transition);
      forcedAgentTransition = -1;
      return;
    }
    if (forcedReservationTransition == transition) {
      recordConflictDecision(transition);
      forcedReservationTransition = -1;
    }
  }

  private void recordConflictDecision(int chosenTransition) {
    switch (chosenTransition) {
      case T2 -> conflictAgentSuperior++;
      case T3 -> conflictAgentInferior++;
      case T6 -> conflictConfirmed++;
      case T7 -> conflictCancelled++;
      default -> {
      }
    }
  }

  // ---------------- REGISTRO REAL ----------------
  private void recordRealFire(int transition) {
    switch (transition) {
      case T2 -> agentSuperiorCount++;
      case T3 -> agentInferiorCount++;
      case T6 -> confirmedReservations++;
      case T7 -> cancelledReservations++;
      default -> {
      }
    }
  }

  private int selectForGroup(ConflictGroup group) {
    return switch (group) {
      case AGENTS -> (mode == PolicyMode.BALANCED) ? selectBalancedAgent() : selectPrioritizedAgent();
      case RESERVATIONS ->
        (mode == PolicyMode.BALANCED) ? selectBalancedReservation() : selectPrioritizedReservation();
      case NONE -> -1;
    };
  }

  private ConflictGroup groupForTransition(int transition) {
    return switch (transition) {
      case T2, T3 -> ConflictGroup.AGENTS;
      case T6, T7 -> ConflictGroup.RESERVATIONS;
      default -> ConflictGroup.NONE;
    };
  }

  private boolean isConflictActive(ConflictGroup group, List<Integer> currentlyEnabled) {
    return switch (group) {
      case AGENTS -> currentlyEnabled.contains(T2) && currentlyEnabled.contains(T3);
      case RESERVATIONS -> currentlyEnabled.contains(T6) && currentlyEnabled.contains(T7);
      case NONE -> false;
    };
  }

  private int getStickySelection(ConflictGroup group) {
    return switch (group) {
      case AGENTS -> forcedAgentTransition;
      case RESERVATIONS -> forcedReservationTransition;
      case NONE -> -1;
    };
  }

  private void setStickySelection(ConflictGroup group, int transition) {
    switch (group) {
      case AGENTS -> forcedAgentTransition = transition;
      case RESERVATIONS -> forcedReservationTransition = transition;
      case NONE -> {
      }
    }
  }

  // ---------------- STATS ----------------
  public synchronized void printSummary() {

    System.out.println("\n================= RESULTADOS =================");

    // ================= AGENTES =================
    int totalAgents = agentInferiorCount + agentSuperiorCount;
    int totalConflictAgents = conflictAgentInferior + conflictAgentSuperior;
    int forcedAgents = totalAgents - totalConflictAgents;

    System.out.println("\n--- AGENTES (T2 vs T3) ---");
    System.out.println("Total disparos: " + totalAgents);

    System.out.println("  En conflicto: " + totalConflictAgents);
    System.out.println("    T2 (superior): " + conflictAgentSuperior);
    System.out.println("    T3 (inferior): " + conflictAgentInferior);

    if (totalConflictAgents > 0) {
      System.out.printf("    %% Superior (conflicto): %.2f%%\n",
          100.0 * conflictAgentSuperior / totalConflictAgents);
    }

    System.out.println("  Sin conflicto: " + forcedAgents);

    System.out.println("  TOTAL GLOBAL:");
    System.out.println("    T2: " + agentSuperiorCount);
    System.out.println("    T3: " + agentInferiorCount);

    if (totalAgents > 0) {
      System.out.printf("    %% Superior (global): %.2f%%\n",
          100.0 * agentSuperiorCount / totalAgents);
    }

    // ================= RESERVAS =================
    int totalReservations = confirmedReservations + cancelledReservations;
    int totalConflictReservations = conflictConfirmed + conflictCancelled;
    int forcedReservations = totalReservations - totalConflictReservations;

    System.out.println("\n--- RESERVAS (T6 vs T7) ---");
    System.out.println("Total disparos: " + totalReservations);

    System.out.println("  En conflicto: " + totalConflictReservations);
    System.out.println("    T6 (confirmadas): " + conflictConfirmed);
    System.out.println("    T7 (canceladas): " + conflictCancelled);

    if (totalConflictReservations > 0) {
      System.out.printf("    %% Confirmadas (conflicto): %.2f%%\n",
          100.0 * conflictConfirmed / totalConflictReservations);
    }

    System.out.println("  Sin conflicto: " + forcedReservations);

    System.out.println("  TOTAL GLOBAL:");
    System.out.println("    T6: " + confirmedReservations);
    System.out.println("    T7: " + cancelledReservations);

    if (totalReservations > 0) {
      System.out.printf("    %% Confirmadas (global): %.2f%%\n",
          100.0 * confirmedReservations / totalReservations);
    }

    System.out.println("\n================================================\n");
    debugTotals();
  }

  public void debugTotals() {
    System.out.println("Agents real total: " +
        (agentInferiorCount + agentSuperiorCount));
    System.out.println("Reservations real total: " +
        (confirmedReservations + cancelledReservations));
  }
}
