package org.concurrent.project;

import java.util.List;

public class Policy {

  public enum Mode {
    BALANCED,
    PRIORITY
  }

  private final Mode mode;

  private int attendedP6 = 0; // T2
  private int attendedP7 = 0; // T3
  private int confirmed = 0; // T6
  private int canceled = 0; // T7

  private static final double TARGET_P6 = 1;
  private static final double TARGET_CONFIRM = 1;

  public Policy(Mode mode) {
    this.mode = mode;
  }

  public int choose(List<Integer> candidates) {

    if (mode == Mode.BALANCED) {
      return chooseBalanced(candidates);
    } else {
      return choosePriority(candidates);
    }
  }

  // ================= BALANCED =================

  private int chooseBalanced(List<Integer> candidates) {

    if (candidates.contains(2) && candidates.contains(3)) {
      return (attendedP6 <= attendedP7) ? 2 : 3;
    }

    if (candidates.contains(6) && candidates.contains(7)) {
      return (confirmed <= canceled) ? 6 : 7;
    }

    return candidates.get(0);
  }

  // ================= PRIORITY =================

  private int choosePriority(List<Integer> candidates) {

    // Prioridad agentes 75%
    if (candidates.contains(2) && candidates.contains(3)) {

      System.out.println("-- ENTRE POR CONFLICTO AGENTES --");
      int total = attendedP6 + attendedP7;

      if (total == 0) {
        return 2; // arrancamos favoreciendo T2
      }

      double currentRatio = (double) attendedP6 / total;

      if (currentRatio < TARGET_P6) {
        return 2;
      } else {
        return 3;
      }
    }

    // Prioridad confirmación 80%
    if (candidates.contains(6) && candidates.contains(7)) {

      System.out.println("-- ENTRE POR CONFLICTO RESERVAS --");
      int total = confirmed + canceled;

      if (total == 0) {
        return 6; // arrancamos favoreciendo confirmación
      }

      double currentRatio = (double) confirmed / total;

      if (currentRatio < TARGET_CONFIRM) {
        return 6;
      } else {
        return 7;
      }
    }

    return candidates.get(0);
  }

  // ================= REGISTRO =================

  public void registerFire(int transition) {

    switch (transition) {
      case 2:
        attendedP6++;
        break;
      case 3:
        attendedP7++;
        break;
      case 6:
        confirmed++;
        break;
      case 7:
        canceled++;
        break;
    }
  }

  // ================= ESTADÍSTICAS =================

  public void printStatistics() {

    int totalAgents = attendedP6 + attendedP7;
    int totalReservations = confirmed + canceled;

    double percentP6 = totalAgents == 0 ? 0 : (attendedP6 * 100.0) / totalAgents;
    double percentP7 = totalAgents == 0 ? 0 : (attendedP7 * 100.0) / totalAgents;

    double percentConfirmed = totalReservations == 0 ? 0 : (confirmed * 100.0) / totalReservations;
    double percentCanceled = totalReservations == 0 ? 0 : (canceled * 100.0) / totalReservations;

    System.out.println("\n================ POLICY " + mode + " RESULTS ================");

    System.out.println("\n--- Agentes de Reserva ---");
    System.out.printf("Total Atenciones: %d%n", totalAgents);
    System.out.printf("Agente Superior (T2): %d (%.2f%%)%n", attendedP6, percentP6);
    System.out.printf("Agente Inferior (T3): %d (%.2f%%)%n", attendedP7, percentP7);

    if (mode == Mode.BALANCED)
      System.out.printf("Desviación respecto a ideal 50%%: %.2f%%%n", Math.abs(percentP6 - 50));
    else
      System.out.printf("Desviación respecto a objetivo 75%%: %.2f%%%n", Math.abs(percentP6 - 75));

    System.out.println("\n--- Reservas ---");
    System.out.printf("Total Reservas Procesadas: %d%n", totalReservations);
    System.out.printf("Confirmaciones (T6): %d (%.2f%%)%n", confirmed, percentConfirmed);
    System.out.printf("Cancelaciones (T7): %d (%.2f%%)%n", canceled, percentCanceled);

    if (mode == Mode.BALANCED)
      System.out.printf("Desviación respecto a ideal 50%%: %.2f%%%n", Math.abs(percentConfirmed - 50));
    else
      System.out.printf("Desviación respecto a objetivo 80%%: %.2f%%%n", Math.abs(percentConfirmed - 80));

    System.out.println("\n==========================================================");
  }
}
