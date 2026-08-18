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
   * Imprime un resumen de la ejecución: disparos totales por transición,
   * cantidad de conflictos detectados y resolución de Bresenham cuando
   * la política intervino.
   */
  public void printSummary() {
    int totalAgents = agentInferiorCount + agentSuperiorCount;
    int totalConflictAgents = conflictAgentInferior + conflictAgentSuperior;
    int totalReservations = confirmedReservations + cancelledReservations;
    int totalConflictReservations = conflictConfirmed + conflictCancelled;
    StringBuilder summary = new StringBuilder();

    summary.append(System.lineSeparator())
        .append("================= RESULTADOS =================")
        .append(System.lineSeparator())
        .append(System.lineSeparator())
        .append("Policy mode: ")
        .append(mode)
        .append(System.lineSeparator())
        .append(System.lineSeparator());

    appendAgentSummary(summary, totalAgents, totalConflictAgents);
    appendReservationSummary(summary, totalReservations, totalConflictReservations);

    summary.append(System.lineSeparator())
        .append("================================================")
        .append(System.lineSeparator());

    System.out.print(summary);
  }

  private void appendAgentSummary(StringBuilder summary, int total, int conflictTotal) {
    summary.append("--- AGENTES (T2 vs T3) ---")
        .append(System.lineSeparator())
        .append("Disparos totales: ")
        .append(total)
        .append(System.lineSeparator())
        .append("  T2 (superior): ")
        .append(agentSuperiorCount)
        .append(pct(agentSuperiorCount, total))
        .append(System.lineSeparator())
        .append("  T3 (inferior): ")
        .append(agentInferiorCount)
        .append(pct(agentInferiorCount, total))
        .append(System.lineSeparator())
        .append(System.lineSeparator())
        .append("Conflictos: ")
        .append(conflictTotal)
        .append(pct(conflictTotal, total))
        .append(System.lineSeparator());

    if (conflictTotal > 0) {
      summary.append("  Resolución Bresenham (objetivo: ")
          .append(AGENT_PREFERRED_PERCENT)
          .append("%):")
          .append(System.lineSeparator())
          .append("    T2 elegida: ")
          .append(conflictAgentSuperior)
          .append(pct(conflictAgentSuperior, conflictTotal))
          .append(System.lineSeparator())
          .append("    T3 elegida: ")
          .append(conflictAgentInferior)
          .append(pct(conflictAgentInferior, conflictTotal))
          .append(System.lineSeparator());
    }

    summary.append(System.lineSeparator());
  }

  private void appendReservationSummary(StringBuilder summary, int total, int conflictTotal) {
    summary.append("--- RESERVAS (T6 vs T7) ---")
        .append(System.lineSeparator())
        .append("Disparos totales: ")
        .append(total)
        .append(System.lineSeparator())
        .append("  T6 (confirmadas): ")
        .append(confirmedReservations)
        .append(pct(confirmedReservations, total))
        .append(System.lineSeparator())
        .append("  T7 (canceladas): ")
        .append(cancelledReservations)
        .append(pct(cancelledReservations, total))
        .append(System.lineSeparator())
        .append(System.lineSeparator())
        .append("Conflictos: ")
        .append(conflictTotal)
        .append(pct(conflictTotal, total))
        .append(System.lineSeparator());

    if (conflictTotal > 0) {
      summary.append("  Resolución Bresenham (objetivo: ")
          .append(RESERVATION_PREFERRED_PERCENT)
          .append("%):")
          .append(System.lineSeparator())
          .append("    T6 elegida: ")
          .append(conflictConfirmed)
          .append(pct(conflictConfirmed, conflictTotal))
          .append(System.lineSeparator())
          .append("    T7 elegida: ")
          .append(conflictCancelled)
          .append(pct(conflictCancelled, conflictTotal))
          .append(System.lineSeparator());
    }
  }

  private String pct(int part, int total) {
    if (total == 0) {
      return "";
    }
    return String.format(" (%.1f%%)", 100.0 * part / total);
  }
}
