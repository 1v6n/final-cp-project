package org.concurrent.project;

import java.util.List;

/**
 * Clase que implementa la política de selección de transiciones para el monitor
 * de disparo.
 */
public class Policy {
  public enum PolicyMode {
    BALANCED, PRIORITIZED
  }

  /**
   * Registro que representa la decisión de política sobre si una transición debe
   * dispararse.
   * <p>
   * Incluye información sobre si el disparo fue permitido, cuál transición fue
   * seleccionada
   * (en caso de conflicto) y si la decisión se tomó en un contexto de conflicto
   * activo.
   * 
   * @param shouldFire         indica si la transición solicitada debe dispararse.
   * @param selectedTransition la transición seleccionada por la política
   *                           (relevante solo si hay conflicto).
   * @param conflictActive     indica si la decisión se tomó en un contexto donde
   *                           el conflicto estaba activo (ambas transiciones
   *                           habilitadas).
   */
  public record PolicyDecision(boolean shouldFire, int selectedTransition, boolean conflictActive) {
    /**
     * Crea una decisión de política que permite el disparo de la transición
     * solicitada
     * sin conflicto.
     *
     * @param transition la transición que se permitirá disparar.
     * @return una instancia de {@code PolicyDecision} que indica que el disparo
     *         está permitido
     *         y que no hay conflicto activo.
     */
    public static PolicyDecision allow(int transition) {
      return new PolicyDecision(true, transition, false);
    }

    /**
     * Crea una decisión de política basada en la evaluación de si la transición
     * solicitada debe dispararse, cuál transición fue seleccionada en caso de
     * conflicto, y si el conflicto estaba activo.
     * 
     * @param shouldFire         indica si la transición solicitada debe dispararse.
     * @param selectedTransition la transición seleccionada por la política
     *                           (relevante solo si hay conflicto).
     * @param conflictActive     indica si la decisión se tomó en un contexto donde
     *                           el conflicto estaba activo (ambas transiciones
     *                           habilitadas).
     * @return una instancia de {@code PolicyDecision} que encapsula la decisión de
     *         política basada en los parámetros proporcionados.
     */
    public static PolicyDecision resolve(boolean shouldFire, int selectedTransition, boolean conflictActive) {
      return new PolicyDecision(shouldFire, selectedTransition, conflictActive);
    }
  }

  private enum ConflictGroup {
    AGENTS, RESERVATIONS, NONE
  }

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

  /**
   * Crea una instancia de {@code Policy} con el modo de política especificado.
   *
   * @param mode el modo de política (BALANCED o PRIORITIZED).
   */
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

  /**
   * Evalúa si una transición puede dispararse según la política actual.
   *
   * @param transition       la transición a evaluar.
   * @param currentlyEnabled las transiciones actualmente habilitadas.
   * @return una instancia de {@code PolicyDecision} que indica si la transición
   *         puede dispararse y cómo se resolvió cualquier conflicto.
   */
  public PolicyDecision evaluate(int transition,
      List<Integer> currentlyEnabled) {
    ConflictGroup group = groupForTransition(transition);
    if (group == ConflictGroup.NONE) {
      return PolicyDecision.allow(transition);
    }

    int stickySelection = getStickySelection(group);
    if (stickySelection != -1) {
      return PolicyDecision.resolve(transition == stickySelection,
          stickySelection,
          isConflictActive(group, currentlyEnabled));
    }

    if (!isConflictActive(group, currentlyEnabled)) {
      return PolicyDecision.allow(transition);
    }

    int selectedTransition = selectForGroup(group);
    setStickySelection(group, selectedTransition);

    return PolicyDecision.resolve(transition == selectedTransition,
        selectedTransition, true);
  }

  /**
   * Selecciona una transición de agente (T2 o T3) de manera balanceada,
   * alternando
   * entre la transición superior (T2) e inferior (T3) en cada selección.
   *
   * @return la transición seleccionada (2 para superior, 3 para inferior) según
   *         el patrón de balanceo definido.
   */
  private int selectBalancedAgent() {
    lastAgentWasSuperior = !lastAgentWasSuperior;
    return lastAgentWasSuperior ? 3 : 2;
  }

  /**
   * Selecciona una transición de reserva (T6 o T7) de manera balanceada,
   * alternando
   * entre la transición de reserva confirmada (T6) y cancelada (T7) en cada
   * selección.
   *
   * @return la transición seleccionada (6 para confirmada, 7 para cancelada)
   *         según el patrón de balanceo definido.
   */
  private int selectBalancedReservation() {
    lastReservationWasConfirmed = !lastReservationWasConfirmed;
    return lastReservationWasConfirmed ? 6 : 7;
  }

  /**
   * Selecciona una transición de reserva (T6 o T7) con prioridad, eligiendo la
   * transición de reserva confirmada (T6) en 4 de cada 5 selecciones, y la
   * transición de reserva cancelada (T7) en 1 de cada 5 selecciones.
   *
   * @return la transición seleccionada (6 para confirmada, 7 para cancelada)
   *         según el patrón de prioridad definido.
   */
  private int selectPrioritizedReservation() {
    reservationCycle = (reservationCycle + 1) % 5;

    if (reservationCycle < 4) {
      return 6; // 4 de cada 5
    } else {
      return 7; // 1 de cada 5
    }
  }

  /**
   * Selecciona una transición de agente (T2 o T3) con prioridad, eligiendo la
   * transición superior (T2) en 3 de cada 4 selecciones, y la transición inferior
   * (T3) en 1 de cada 4 selecciones.
   *
   * @return la transición seleccionada (2 para superior, 3 para inferior) según
   *         el patrón de prioridad definido.
   */
  private int selectPrioritizedAgent() {
    agentCycle = (agentCycle + 1) % 4;

    if (agentCycle < 3) {
      return 2; // superior
    } else {
      return 3; // inferior
    }
  }

  /**
   * Registra el disparo real de una transición, actualizando los contadores
   * globales para agentes y reservas según corresponda, y también registra la
   * decisión de política tomada en un contexto de conflicto activo si la
   * transición disparada coincide con una selección sticky activa.
   *
   * @param transition la transición que se ha disparado.
   */
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

  /**
   * Registra la decisión de política tomada en un contexto de conflicto activo,
   * actualizando los contadores correspondientes según la transición que se
   * disparó.
   * <p>
   * Esta función se llama cuando una transición que fue seleccionada como
   * "sticky" se dispara, lo que indica que la política tomó una decisión en un
   * contexto de conflicto activo.
   * 
   * @param chosenTransition la transición que se disparó y que fue seleccionada
   *                         como "sticky" por la política, utilizada para
   *                         actualizar los contadores de acuerdo a si es un
   *                         disparo de agente superior, agente inferior, reserva
   *                         confirmada o reserva cancelada en un contexto de
   *                         conflicto.
   */
  private void recordConflictDecision(int chosenTransition) {
    switch (chosenTransition) {
      case 2 -> conflictAgentSuperior++;
      case 3 -> conflictAgentInferior++;
      case 6 -> conflictConfirmed++;
      case 7 -> conflictCancelled++;
      default -> {
      }
    }
  }

  /**
   * Registra el disparo real de una transición, actualizando los contadores
   * globales para agentes y reservas según corresponda.
   * 
   * @param transition la transición que se ha disparado, utilizada para
   *                   actualizar los contadores de acuerdo a si es un disparo de
   *                   agente superior, agente inferior, reserva confirmada o
   *                   reserva cancelada.
   */
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
   * Selecciona una transición para disparar dentro del grupo de conflicto
   * especificado, según la política de balanceo o prioridad definida.
   * 
   * @param group el grupo de conflicto para el cual se desea seleccionar una
   *              transición (AGENTS o RESERVATIONS).
   * @return la transición seleccionada por la política para el grupo de conflicto
   *         especificado.
   */
  private int selectForGroup(ConflictGroup group) {
    return switch (group) {
      case AGENTS ->
        (mode == PolicyMode.BALANCED) ? selectBalancedAgent()
            : selectPrioritizedAgent();
      case RESERVATIONS ->
        (mode == PolicyMode.BALANCED) ? selectBalancedReservation()
            : selectPrioritizedReservation();
      case NONE -> -1;
    };
  }

  /**
   * Determina a qué grupo de conflicto pertenece la transición dada, si es que
   * pertenece a alguno.
   * 
   * @param transition la transición para la cual se desea determinar el grupo de
   *                   conflicto.
   * @return el grupo de conflicto al que pertenece la transición dada (AGENTS,
   *         RESERVATIONS o NONE).
   */
  private ConflictGroup groupForTransition(int transition) {
    return switch (transition) {
      case 2, 3 -> ConflictGroup.AGENTS;
      case 6, 7 -> ConflictGroup.RESERVATIONS;
      default -> ConflictGroup.NONE;
    };
  }

  /**
   * Determina si el conflicto para el grupo dado está activo, es decir, si ambas
   * transiciones en conflicto están habilitadas en la lista de candidatos actual.
   * 
   * @param group            el grupo de conflicto para el cual se desea verificar
   *                         si el conflicto está activo (AGENTS o RESERVATIONS).
   * @param currentlyEnabled la lista de transiciones actualmente habilitadas
   *                         (candidatos) en el monitor de disparo.
   * @return true si el conflicto para el grupo especificado está activo (ambas
   *         transiciones en conflicto están habilitadas), o false si no hay
   *         conflicto activo para ese grupo.
   */
  private boolean isConflictActive(ConflictGroup group,
      List<Integer> currentlyEnabled) {
    return switch (group) {
      case AGENTS ->
        currentlyEnabled.contains(2) && currentlyEnabled.contains(3);
      case RESERVATIONS ->
        currentlyEnabled.contains(6) && currentlyEnabled.contains(7);
      case NONE -> false;
    };
  }

  /**
   * Obtiene la selección "sticky" actual para el grupo de conflicto dado, si
   * existe.
   * 
   * @param group el grupo de conflicto para el cual se desea obtener la selección
   *              sticky (AGENTS o RESERVATIONS).
   * @return la transición seleccionada actualmente como sticky para el grupo
   *         especificado, o -1 si no hay ninguna selección sticky activa para ese
   *         grupo.
   */
  private int getStickySelection(ConflictGroup group) {
    return switch (group) {
      case AGENTS -> forcedAgentTransition;
      case RESERVATIONS -> forcedReservationTransition;
      case NONE -> -1;
    };
  }

  /**
   * Establece una selección "sticky" para el grupo de conflicto dado.
   * <p>
   * Esta selección se mantendrá hasta que una de las transiciones en conflicto se
   * dispare, momento en el cual se registrará la decisión de política y se
   * limpiará la selección sticky.
   * 
   * @param group      el grupo de conflicto para el cual se establece la
   *                   selección sticky (AGENTS o RESERVATIONS).
   * @param transition la transición seleccionada que se mantendrá como sticky
   *                   para el grupo especificado.
   */
  private void setStickySelection(ConflictGroup group, int transition) {
    switch (group) {
      case AGENTS -> forcedAgentTransition = transition;
      case RESERVATIONS -> forcedReservationTransition = transition;
      case NONE -> {
      }
    }
  }

  /**
   * Selecciona una transición de la lista de candidatos según la política
   * definida.
   * <p>
   * Si hay un conflicto activo (ambas transiciones habilitadas), se selecciona
   * según la política de balanceo o prioridad. Si no hay conflicto, se devuelve
   * el primer candidato.
   * 
   * @param candidates la lista de transiciones habilitadas actualmente.
   * @return la transición seleccionada por la política para disparar.
   * @throws IllegalArgumentException si la lista de candidatos es nula o vacía.
   */
  public synchronized int choose(List<Integer> candidates) throws IllegalArgumentException {
    if (candidates == null || candidates.isEmpty()) {
      throw new IllegalArgumentException("Candidates list cannot be null or empty");
    }

    if (isConflictActive(ConflictGroup.AGENTS, candidates)) {
      int selected = selectForGroup(ConflictGroup.AGENTS);
      setStickySelection(ConflictGroup.AGENTS, selected);
      return selected;
    }

    if (isConflictActive(ConflictGroup.RESERVATIONS, candidates)) {
      int selected = selectForGroup(ConflictGroup.RESERVATIONS);
      setStickySelection(ConflictGroup.RESERVATIONS, selected);
      return selected;
    }
    // si no hay conflicto, simplemente devolvemos el primero
    return candidates.get(0);
  }

  /**
   * Imprime un resumen detallado de los resultados de la ejecución, incluyendo el
   * total de disparos, la cantidad de disparos en conflicto, la distribución de
   * disparos entre las transiciones superiores e inferiores, y los porcentajes
   * correspondientes tanto para los casos en conflicto como para el total global.
   * También muestra el total real de disparos registrados para agentes y
   * reservas.
   */
  public synchronized void printSummary() {
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
        .append("--- AGENTES (T2 vs T3) ---")
        .append(System.lineSeparator())
        .append("Total disparos: ").append(totalAgents).append(System.lineSeparator())
        .append("  En conflicto: ").append(totalConflictAgents)
        .append(System.lineSeparator())
        .append("    T2 (superior): ").append(conflictAgentSuperior)
        .append(System.lineSeparator())
        .append("    T3 (inferior): ").append(conflictAgentInferior)
        .append(System.lineSeparator());

    if (totalConflictAgents > 0) {
      summary.append(String.format("    %% Superior (conflicto): %.2f%%%n",
          100.0 * conflictAgentSuperior / totalConflictAgents));
    }

    summary.append("  Sin conflicto: ").append(forcedAgents)
        .append(System.lineSeparator())
        .append("  TOTAL GLOBAL:")
        .append(System.lineSeparator())
        .append("    T2: ").append(agentSuperiorCount)
        .append(System.lineSeparator())
        .append("    T3: ").append(agentInferiorCount)
        .append(System.lineSeparator());

    if (totalAgents > 0) {
      summary.append(String.format("    %% Superior (global): %.2f%%%n",
          100.0 * agentSuperiorCount / totalAgents));
    }

    summary.append(System.lineSeparator())
        .append("--- RESERVAS (T6 vs T7) ---")
        .append(System.lineSeparator())
        .append("Total disparos: ").append(totalReservations)
        .append(System.lineSeparator())
        .append("  En conflicto: ").append(totalConflictReservations)
        .append(System.lineSeparator())
        .append("    T6 (confirmadas): ").append(conflictConfirmed)
        .append(System.lineSeparator())
        .append("    T7 (canceladas): ").append(conflictCancelled)
        .append(System.lineSeparator());

    if (totalConflictReservations > 0) {
      summary.append(String.format("    %% Confirmadas (conflicto): %.2f%%%n",
          100.0 * conflictConfirmed / totalConflictReservations));
    }

    summary.append("  Sin conflicto: ").append(forcedReservations)
        .append(System.lineSeparator())
        .append("  TOTAL GLOBAL:")
        .append(System.lineSeparator())
        .append("    T6: ").append(confirmedReservations)
        .append(System.lineSeparator())
        .append("    T7: ").append(cancelledReservations)
        .append(System.lineSeparator());

    if (totalReservations > 0) {
      summary.append(String.format("    %% Confirmadas (global): %.2f%%%n",
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

  public void debugTotals() {
    System.out.println("Agents real total: " +
        (agentInferiorCount + agentSuperiorCount));
    System.out.println("Reservations real total: " +
        (confirmedReservations + cancelledReservations));
  }
}
