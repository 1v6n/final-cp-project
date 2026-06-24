package org.concurrent.project;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/*
 * AutoCloseable permite que el objeto se cierre automaticamente al salir de un
 * bloque principalmente enfocado en manejadores de archivos/sockets (como un
 * logger). El metodo close() se invoca automaticamente.
 */
public class LogService implements AutoCloseable {
  private final BufferedWriter writer;
  private final DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /**
   * Crea una nueva instancia del servicio de logging.
   * <p>
   * Inicializa el archivo de log en la ruta indicada. Si el archivo o sus
   * directorios padres no existen, estos son creados automáticamente. El
   * archivo se abre en modo append, permitiendo registrar múltiples ejecuciones
   * sin sobrescribir información previa.
   *
   * @param logPath Ruta del archivo donde se almacenará el log de ejecución.
   * @throws IOException Si ocurre un error durante la creación o apertura del
   *                     archivo.
   */
  public LogService(Path logPath) throws IOException {
    Path parent = logPath.getParent();
    if (parent != null)
      Files.createDirectories(parent);

    this.writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  /**
   * Registra el disparo efectivo de una transición.
   * <p>
   * Este método genera una línea de log correspondiente a un disparo exitoso de
   * una transición, incluyendo información temporal, el hilo ejecutor, la
   * transición disparada y el marcado actual de la red de Petri.
   * <p>
   * El registro se realiza de forma sincronizada para evitar interleavings en
   * el archivo de log y asegurar que cada línea represente un evento atómico
   * del sistema.
   *
   * @param threadName Nombre del hilo que ejecutó el disparo.
   * @param transition Identificador de la transición disparada.
   * @param ok         Indica si el disparo fue exitoso.
   * @param marking    Marcado actual de la red de Petri al finalizar el
   *                   disparo.
   * @param pinv       Resultado del chequeo de invariantes de plaza (por
   *                   ejemplo, OK, FAIL o NA).
   */
  public synchronized void logFire(String threadName, int transition,
      boolean ok, int[] marking, String pinv) {
    String ts = ZonedDateTime.now().format(tsFmt);
    String line = ts + " | thr=" + threadName + " | tr=T" + transition +
        " | ok=" + ok + " | m=" + formatMarking(marking) +
        " | pinv=" + pinv;
    writeLine(line);
  }

  /**
   * Registra un evento auxiliar del sistema.
   * <p>
   * Este método se utiliza para registrar eventos que no corresponden
   * directamente a disparos de transiciones, como por ejemplo el inicio y fin
   * de la ejecución de un invariante de transición.
   * <p>
   * Estos eventos permiten estructurar el archivo de log y facilitar su
   * posterior análisis mediante expresiones regulares.
   *
   * @param threadName Nombre del hilo que genera el evento.
   * @param event      Identificador del evento (por ejemplo, BEGIN_IT1 o
   *                   END_IT1).
   */
  public synchronized void logEvent(String threadName, String event) {
    String ts = ZonedDateTime.now().format(tsFmt);
    String line = ts + " | thr=" + threadName + " | ev=" + event;
    writeLine(line);
  }

  /**
   * Escribe una línea en el archivo de log de forma sincronizada.
   * <p>
   * Este método se encarga de escribir una línea completa en el archivo de log,
   * asegurando que no haya interleavings entre diferentes hilos que intenten
   * escribir simultáneamente. Cada línea se termina con un salto de línea y se
   * fuerza el vaciado del buffer para garantizar que la información se registre
   * de inmediato.
   * 
   * @param line Línea a escribir en el archivo de log.
   */
  private void writeLine(String line) {
    try {
      writer.write(line);
      writer.newLine();
      writer.flush();
    } catch (IOException e) {
      System.err.println("LOG WRITE ERROR: " + e.getMessage());
    }
  }

  /**
   * Formatea el marcado de la red de Petri como una cadena legible.
   * <p>
   * Este método convierte un arreglo de enteros que representa el marcado actual
   * de la red de Petri en una cadena con formato [m0,m1,m2,...], donde
   * cada mi corresponde al número de tokens en la plaza i.
   * 
   * @param m Arreglo de enteros que representa el marcado de la red de Petri.
   * @return Cadena con el marcado formateado.
   */
  private String formatMarking(int[] m) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');

    for (int i = 0; i < m.length; i++) {
      if (i > 0)
        sb.append(',');
      sb.append(m[i]);
    }
    sb.append(']');

    return sb.toString();
  }

  /**
   * Cierra el archivo de log.
   * <p>
   * Este método cierra el archivo de log y libera los recursos asociados.
   *
   * @throws IOException Si ocurre un error al cerrar el archivo.
   */
  @Override
  public synchronized void close() throws IOException {
    writer.close();
  }
}
