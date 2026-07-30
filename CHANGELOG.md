# Changelog

Este archivo registra el rediseño incremental aplicado sobre la arquitectura
existente. Se preservan las clases de producción y los nombres públicos
acordados; los cambios reorganizan solamente responsabilidades internas.

## En desarrollo — 2026-07-28

### Política de selección (`Policy`)

- Se eliminaron los toggles de balanceo, los ciclos manuales, las decisiones
  sticky y las transiciones forzadas.
- Se incorporaron dos acumuladores independientes de Bresenham:
  `agentResidue` y `reservationResidue`.
- Ambos residuos comienzan en `0`. Por lo tanto, la primera decisión de un
  conflicto real toma la alternativa: `T3` para agentes y `T7` para reservas.
- `BALANCED` usa 50%; `PRIORITIZED` usa 75% para `T2` y 80% para `T6`.
- `choose(candidates)` ahora trabaja con candidatos efectivos. En `NONE`
  elige aleatoriamente; en ausencia de conflicto devuelve el primer candidato;
  ante conflicto usa Bresenham.
- La detección de conflicto mantiene el orden `AGENTS` antes que
  `RESERVATIONS`.
- La decisión de conflicto se registra al seleccionar un waiter. En cambio,
  `onTransitionFired()` registra exclusivamente los disparos globales.
- Se mantuvieron los contadores requeridos por `printSummary()`.

### Red de Petri y tiempo (`RdP`, `TimeRestrictions`)

- Se agregó `RdP.isSensitized(int)` para encapsular el acceso al vector de
  sensibilización.
- Se agregó `TimeRestrictions.awaitUntilEarliestFireTime(int)` con visibilidad
  de paquete. Calcula el tiempo pendiente, duerme y vuelve a calcular si el
  scheduler despertó el hilo antes de ETF.
- La espera conserva la propagación de `InterruptedException` y el reloj
  inyectable para pruebas determinísticas.

### Monitor y colas (`Monitor`)

- `fireTransition()` ahora muestra el flujo directo:
  adquirir ownership, verificar sensibilización, evaluar tiempo y luego
  esperar/reintentar o disparar.
- Se eliminó `handleSensitizedTransition()`.
- Se eliminó `shouldDeferToPolicySelectedReservation()`; una transición en
  estado `ALLOWED` dispara directamente y nunca consulta la política.
- La única llamada a `Policy.choose()` quedó dentro de
  `selectWaiterOrRelease()`, después de un disparo y sólo si existen waiters
  elegibles.
- Se removió la consulta preventiva de reservas sin waiters.
- `wakingCandidates()` usa exclusivamente
  `rdp.isSensitized(t) && queues.hasWaiters(t)`.
- `Queues` encapsula la señalización con `hasWaiters()` y `signalOne()`.
- El monitor libera ownership antes de delegar la espera temporal a
  `TimeRestrictions`.

### Workers de reservas (`Main`)

- `WorkerSpec` ahora incluye `instances`.
- Se conserva una sola especificación por path y se generan nueve workers:
  cinco workers base, dos para `[6,9,10,11]` y dos para `[7,8,11]`.
- Las instancias duplicadas reciben nombres distinguibles (`Thread-6-1`,
  `Thread-6-2`, etc.).

### Verificación automatizada

- Se incorporó JUnit Jupiter y Surefire en Maven.
- Se agregó una suite de tests para política, tiempo, RdP, monitor, workers,
  configuración, log e integración; su alcance está detallado en `TESTS.md`.
- Los tests de política fijan la ecuación de Bresenham:

  ```text
  n * p = 100 * S_n + A_n, con 0 <= A_n < 100
  ```

- Los tests concurrentes verifican waiters reales, signal-and-exit,
  interrupciones, ausencia de deferral artificial y la liberación del monitor
  durante una espera temporal.

### Validación de esta etapa

- `mvn -o test`: 56 tests ejecutados, 56 verdes.
- Simulación completa con `PolicyMode.PRIORITIZED`: 186 invariantes completadas
  y 186 iniciadas; sin `PINV_FAIL` en `logs/run.log`.
- `scripts/run-regex.sh`: 186 T-invariantes detectadas de 186 esperadas.
  Distribución observada: 97 superior-confirmada, 42 superior-cancelada,
  34 inferior-confirmada y 13 inferior-cancelada.
