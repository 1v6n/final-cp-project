package org.concurrent.project;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Clase que implementa la política de selección de transiciones para el monitor
 * de disparo.
 */
public class Policy {
  public enum PolicyMode {
    NONE, BALANCED, PRIORITIZED
  }

  private enum ConflictGroup {
    AGENTS, RESERVATIONS, NONE
  }

  private static final int AGENT_PREFERRED = 2;
  private static final int AGENT_PREFERRED_PERCENT = 75;
  private static final int RESERVATION_PREFERRED = 6;
  private static final int RESERVATION_PREFERRED_PERCENT = 80;

  private final PolicyMode mode;

  private int agentResidue;
  private int reservationResidue;

  // Contadores reales
  private int agentInferiorCount;
  private int agentSuperiorCount;
  private int confirmedReservations;
  private int cancelledReservations;

  // Contadores SOLO en conflictos reales
  private int conflictAgentSuperior;
  private int conflictAgentInferior;

  private int conflictConfirmed;
  private int conflictCancelled;

  /**
   * Crea una instancia de {@code Policy} con el modo de política especificado.
   *
   * @param mode el modo de política (NONE, BALANCED o PRIORITIZED).
   */
  public Policy(PolicyMode mode) {
    this.mode = mode;
    this.agentResidue = 0;
    this.reservationResidue = 0;
    this.conflictConfirmed = 0;
    this.conflictCancelled = 0;
    this.cancelledReservations = 0;
    this.confirmedReservations = 0;
    this.agentInferiorCount = 0;
    this.agentSuperiorCount = 0;
  }

  /**
   * Indica si la política de selección debe aplicarse.
   * <p>
   * En modo {@code PolicyMode.NONE} no se aplica una prioridad: se elige un
   * candidato efectivo al azar. En los modos restantes, la política interviene
   * sólo ante conflictos reales.
   *
   * @return {@code true} si la política está activa (BALANCED o PRIORITIZED).
   */
  public boolean isEnabled() {
    return mode != PolicyMode.NONE;
  }

  /**
   * Selecciona una transición de la lista de candidatos según la política
   * definida.
   * <p>
   * En modo {@code NONE} se elige un candidato al azar. En los demás modos,
   * Bresenham sólo se aplica cuando las dos ramas de un conflicto son
   * candidatas reales. Si no hay conflicto se devuelve el primer candidato.
   *
   * @param candidates la lista de transiciones habilitadas actualmente.
   * @return la transición seleccionada por la política para disparar.
   * @throws IllegalArgumentException si la lista de candidatos es nula o vacía.
   */
  public int choose(List<Integer> candidates) throws IllegalArgumentException {
    if (candidates == null || candidates.isEmpty()) {
      throw new IllegalArgumentException(
          "Candidates list cannot be null or empty");
    }

    if (!isEnabled()) {
      return selectAny(candidates);
    }

    ConflictGroup group = activeConflictIn(candidates);
    if (group == ConflictGroup.NONE) {
      return candidates.getFirst();
    }

    int preferredPercent = mode == PolicyMode.BALANCED ? 50
        : group == ConflictGroup.AGENTS
            ? AGENT_PREFERRED_PERCENT
            : RESERVATION_PREFERRED_PERCENT;
    return selectByPercentage(group, preferredPercent);
  }

  /**
   * Registra un disparo real en los contadores globales. Las decisiones de
   * conflicto se registran al seleccionar el waiter, no al dispararlo.
   *
   * @param transition la transición que se ha disparado.
   */
  public void onTransitionFired(int transition) {
    recordRealFire(transition);
  }

  /**
   * Detecta el primer conflicto real presente en los candidatos.
   *
   * @param candidates transiciones sensibilizadas con waiter existente.
   * @return {@code AGENTS}, {@code RESERVATIONS} o {@code NONE}. Los agentes
   *         conservan prioridad si ambos conflictos están presentes.
   */
  private ConflictGroup activeConflictIn(List<Integer> candidates) {
    if (candidates.contains(2) && candidates.contains(3)) {
      return ConflictGroup.AGENTS;
    }
    if (candidates.contains(6) && candidates.contains(7)) {
      return ConflictGroup.RESERVATIONS;
    }
    return ConflictGroup.NONE;
  }

  /**
   * Aplica Bresenham al residuo independiente del grupo de conflicto.
   *
   * @param group            grupo con ambas ramas como candidatos efectivos.
   * @param preferredPercent porcentaje de la transición preferida.
   * @return transición preferida si hubo carry de 100; alternativa si no.
   */
  private int selectByPercentage(ConflictGroup group, int preferredPercent) {
    int residue = switch (group) {
      case AGENTS -> agentResidue;
      case RESERVATIONS -> reservationResidue;
      case NONE ->
        throw new IllegalArgumentException("No hay conflicto para seleccionar");
    };

    int accumulated = residue + preferredPercent;
    boolean selectPreferred = accumulated >= 100;
    int newResidue = selectPreferred ? accumulated - 100 : accumulated;
    int selected = switch (group) {
      case AGENTS -> selectPreferred ? AGENT_PREFERRED : 3;
      case RESERVATIONS -> selectPreferred ? RESERVATION_PREFERRED : 7;
      case NONE ->
        throw new IllegalArgumentException("No hay conflicto para seleccionar");
    };

    if (group == ConflictGroup.AGENTS) {
      agentResidue = newResidue;
    } else {
      reservationResidue = newResidue;
    }
    recordConflictDecision(selected);
    return selected;
  }

  private void recordConflictDecision(int selected) {
    switch (selected) {
      case 2 -> conflictAgentSuperior++;
      case 3 -> conflictAgentInferior++;
      case 6 -> conflictConfirmed++;
      case 7 -> conflictCancelled++;
      default -> {
      }
    }
  }

  private void recordRealFire(int transition) {
    switch (transition) {
      case 2 -> agentSuperiorCount++;
      case 3 -> agentInferiorCount++;
      case 6 -> confirmedReservations++;
      case 7 -> cancelledReservations++;
      default -> {
      }
    }
  }

  /**
   * Elige cualquier transición candidata a set disparada usando un generador
   * de número aleatorio.
   *
   * @param candidates Lista de candidatos a elegir
   * @return número de transición a disparar
   */
  public int selectAny(List<Integer> candidates) {
    return candidates.get(
        ThreadLocalRandom.current().nextInt(candidates.size()));
  }

  /**
   * Imprime un resumen detallado de los resultados de la ejecución, incluyendo
   * el total de disparos, la cantidad de disparos en conflicto, la distribución
   * de disparos entre las transiciones superiores e inferiores, y los
   * porcentajes correspondientes tanto para los casos en conflicto como para el
   * total global. También muestra el total real de disparos registrados para
   * agentes y reservas.
   */
  public void printSummary() {
    int totalAgents = agentInferiorCount + agentSuperiorCount;
    int totalConflictAgents = conflictAgentInferior + conflictAgentSuperior;
    int forcedAgents = totalAgents - totalConflictAgents;
    int totalReservations = confirmedReservations + cancelledReservations;
    int totalConflictReservations = conflictConfirmed + conflictCancelled;
    int forcedReservations = totalReservations - totalConflictReservations;
    StringBuilder summary = new StringBuilder();

    summary.append(System.lineSeparator())
        .append("================= RESULTADOS =================")
        .append(System.lineSeparator())
        .append(System.lineSeparator())
        .append("Policy mode: ")
        .append(mode)
        .append(System.lineSeparator())
        .append(System.lineSeparator())
        .append("--- AGENTES (T2 vs T3) ---")
        .append(System.lineSeparator())
        .append("Total disparos: ")
        .append(totalAgents)
        .append(System.lineSeparator())
        .append("  En conflicto: ")
        .append(totalConflictAgents)
        .append(System.lineSeparator())
        .append("    T2 (superior): ")
        .append(conflictAgentSuperior)
        .append(System.lineSeparator())
        .append("    T3 (inferior): ")
        .append(conflictAgentInferior)
        .append(System.lineSeparator());

    if (totalConflictAgents > 0) {
      summary.append(
          String.format("    %% Superior (conflicto): %.2f%%%n",
              100.0 * conflictAgentSuperior / totalConflictAgents));
    }

    summary.append("  Sin conflicto: ")
        .append(forcedAgents)
        .append(System.lineSeparator())
        .append("  TOTAL GLOBAL:")
        .append(System.lineSeparator())
        .append("    T2: ")
        .append(agentSuperiorCount)
        .append(System.lineSeparator())
        .append("    T3: ")
        .append(agentInferiorCount)
        .append(System.lineSeparator());

    if (totalAgents > 0) {
      summary.append(String.format("    %% Superior (global): %.2f%%%n",
          100.0 * agentSuperiorCount / totalAgents));
    }

    summary.append(System.lineSeparator())
        .append("--- RESERVAS (T6 vs T7) ---")
        .append(System.lineSeparator())
        .append("Total disparos: ")
        .append(totalReservations)
        .append(System.lineSeparator())
        .append("  En conflicto: ")
        .append(totalConflictReservations)
        .append(System.lineSeparator())
        .append("    T6 (confirmadas): ")
        .append(conflictConfirmed)
        .append(System.lineSeparator())
        .append("    T7 (canceladas): ")
        .append(conflictCancelled)
        .append(System.lineSeparator());

    if (totalConflictReservations > 0) {
      summary.append(
          String.format("    %% Confirmadas (conflicto): %.2f%%%n",
              100.0 * conflictConfirmed / totalConflictReservations));
    }

    summary.append("  Sin conflicto: ")
        .append(forcedReservations)
        .append(System.lineSeparator())
        .append("  TOTAL GLOBAL:")
        .append(System.lineSeparator())
        .append("    T6: ")
        .append(confirmedReservations)
        .append(System.lineSeparator())
        .append("    T7: ")
        .append(cancelledReservations)
        .append(System.lineSeparator());

    if (totalReservations > 0) {
      summary.append(
          String.format("    %% Confirmadas (global): %.2f%%%n",
              100.0 * confirmedReservations / totalReservations));
    }

    summary.append(System.lineSeparator())
        .append("================================================")
        .append(System.lineSeparator())
        .append(System.lineSeparator())
        .append("Agents real total: ")
        .append(agentInferiorCount + agentSuperiorCount)
        .append(System.lineSeparator())
        .append("Reservations real total: ")
        .append(confirmedReservations + cancelledReservations)
        .append(System.lineSeparator());

    System.out.print(summary);
  }
}
