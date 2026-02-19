package org.concurrent.project;

import java.util.List;
import java.util.Random;

public class Policy {

  public enum PolicyMode {
    BALANCED,
    PRIORITIZED
  }

  private final PolicyMode mode;

  public synchronized void recordBatch(
      int t2, int t3, int t6, int t7) {
    agentInferiorCount += t2;
    agentSuperiorCount += t3;
    confirmedReservations += t6;
    cancelledReservations += t7;
  }

  // Balance toggles
  private boolean lastAgentWasSuperior = false;
  private boolean lastReservationWasConfirmed = false;

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

  public void recordConflictDecision(int chosenTransition) {
    switch (chosenTransition) {
      case 2 -> conflictAgentSuperior++;
      case 3 -> conflictAgentInferior++;
      case 6 -> conflictConfirmed++;
      case 7 -> conflictCancelled++;
    }
  }

  public int chooseTransition(List<Integer> availableTransitions) {

    // Conflicto agentes
    if (availableTransitions.contains(2) && availableTransitions.contains(3)) {
      System.out.println("Conflicto agentes detectado");
      return (mode == PolicyMode.BALANCED)
          ? selectBalancedAgent()
          : selectPrioritizedAgent();
    }

    // Conflicto reservas
    if (availableTransitions.contains(6) && availableTransitions.contains(7)) {
      System.out.println("Conflicto reservas detectado");
      return (mode == PolicyMode.BALANCED)
          ? selectBalancedReservation()
          : selectPrioritizedReservation();
    }

    // Casos individuales
    return availableTransitions.isEmpty() ? -1 : availableTransitions.get(0);
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
      return 2; // superior
    } else {
      return 3; // inferior
    }
  }

  // ---------------- REGISTRO REAL ----------------
  public void recordFire(int transition) {
    switch (transition) {
      case 2 -> agentSuperiorCount++;
      case 3 -> agentInferiorCount++;
      case 6 -> confirmedReservations++;
      case 7 -> cancelledReservations++;
    }
  }

  // ---------------- STATS ----------------
  public void printSummary() {

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
