# Changelog

Este archivo registra el rediseño incremental aplicado sobre la arquitectura
existente. Se preservan las clases de producción y los nombres públicos
acordados; los cambios reorganizan solamente responsabilidades internas.

> **Estado de la evidencia.** Las afirmaciones de tests de la sección de
> verificación pertenecen a una rama/commit auxiliar y no están integradas en el
> `HEAD` de esta rama. Para reproducir la configuración vigente seguir los
> criterios del `README.md` (sección "Criterios de aceptación").

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

- La configuración pragmática usa seis workers persistentes, una responsabilidad
  por worker: `[0,1]`, `[2,5]`, `[3,4]`, `[6,9,10]`, `[7,8]` y `[11]`.
- El worker de `[11]` es la responsabilidad común posterior a la convergencia y
  es el único que acredita las 186 finalizaciones.

#### Caso histórico: configuraciones 2×2

El commit `8ca4e86` configuró `2 × [T6,T9,T10,T11]` y `2 × [T7,T8,T11]`,
totalizando nueve threads físicos. Esa duplicación no se deriva del paper y no se
justificó con evidencia de rendimiento; además, duplicaba la responsabilidad
común de `T11`. P10 serializa el tramo crítico, por lo que más workers generan
más espera sin aumentar el paralelismo estructural. Quedó reemplazada por la
configuración de seis workers.

### Verificación automatizada

- La suite de tests descrita a continuación pertenece a una rama/commit auxiliar;
  no forma parte del `HEAD` actual porque Maven no declara JUnit ni Surefire.
- Como referencia histórica, esos tests fijaban la ecuación de Bresenham:

  ```text
  n * p = 100 * S_n + A_n, con 0 <= A_n < 100
  ```

- La evidencia reproducible vigente debe seguir los criterios del `README.md`
  (sección "Criterios de aceptación") y no asumir que aquella suite está
  disponible.

### Validación histórica de la etapa

- `mvn -o test`: 56 tests ejecutados, 56 verdes.
- Simulación completa con `PolicyMode.PRIORITIZED`: 186 invariantes completadas
  y 186 iniciadas; sin `PINV_FAIL` en `logs/run.log`.
- `scripts/run-regex.sh`: 186 T-invariantes detectadas de 186 esperadas.
  Distribución observada: 97 superior-confirmada, 42 superior-cancelada,
  34 inferior-confirmada y 13 inferior-cancelada.

## Cambios - 18/08/2026

### Simplificación de `WorkerSpec` y creación de workers (`Main`)

- Se eliminó el campo `instances` de `WorkerSpec`. Cada especificación
  representa exactamente un hilo, por lo que el loop de creación se redujo
  a iterar directamente sobre la lista sin anidar.
- Se eliminó `timed` como constante y parámetro de `Monitor` y
  `TimeRestrictions`: las transiciones temporizadas ahora se configuran
  siempre.

### Simplificación de restricciones temporales (`TimeRestrictions`)

- Se eliminó el valor `NOT_ENABLED` del enum `FireEvaluation` (quedan solo
  `ALLOWED` y `TOO_EARLY`).
- `evaluateFire()` fue reemplazado por `canFire()`: devuelve `boolean`
  (`true` → permitido, `false` → demasiado temprano) y lanza
  `IllegalStateException` si la ventana LFT expiró.
- Se renombró `getRemainingToEarliest` → `getRemainingToEFT` y
  `awaitUntilEarliestFireTime` → `awaitUntilEFT`.

### Simplificación del monitor (`Monitor`)

- `fireTransition()` reemplazó el `switch` sobre `evaluateFire()` por un
  `if (time.canFire(transition))`: rama verdadera dispara, rama falsa
  libera ownership y delega la espera temporal a `awaitUntilEFT`.
- Se eliminó el método privado `waitUntilEarliestFireTime()`; su lógica
  quedó inline en la rama `else` de `fireTransition()`.

### Diagramas y documentación

- Se actualizó `RedesignClassDiagram.puml` para reflejar la eliminación
  de `instances` en `WorkerSpec` y la aclaración de que la configuración
  final usa una responsabilidad por worker.
