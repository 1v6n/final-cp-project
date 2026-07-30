# Suite de tests del rediseño

## Propósito

Los tests persiguen dos objetivos:

1. conservar el comportamiento válido de la red, los workers y las
   restricciones temporales;
2. dejar en rojo exactamente las decisiones que todavía deben cambiar:
   Bresenham, candidatos efectivos, eliminación del deferral, delegación del
   sueño y multiplicidad de workers.

No se prueban directamente `Monitor.Ownership` ni `Monitor.Queues`. Son detalles
privados y pueden reorganizarse. Sus contratos se verifican a través de
`Monitor.fireTransition()`: exclusión mutua, espera, señalización, handoff,
interrupción y progreso.

## Decisión inicial de Bresenham

Cada grupo empieza con residuo cero:

```text
agentResidue = 0
reservationResidue = 0
```

Para un porcentaje preferido `p`:

```text
A' = A + p

si A' >= 100:
    elegir preferida
    A = A' - 100

si A' < 100:
    elegir alternativa
    A = A'
```

Por lo tanto, la primera decisión real elige la alternativa:

- agentes: `T3`;
- reservas: `T7`.

Las secuencias exigidas son:

| Modo | Grupo | Secuencia desde `A0 = 0` |
|---|---|---|
| BALANCED | agentes | `T3,T2,T3,T2,...` |
| BALANCED | reservas | `T7,T6,T7,T6,...` |
| PRIORITIZED | agentes, 75% T2 | `T3,T2,T2,T2,...` |
| PRIORITIZED | reservas, 80% T6 | `T7,T6,T6,T6,T6,...` |

Además, para cada prefijo de `n` conflictos:

```text
n * p = 100 * S_n + A_n
0 <= A_n < 100
```

`S_n` es la cantidad de selecciones preferidas. `PolicyTest` comprueba la
igualdad para cada prefijo, no sólo el total al terminar un ciclo.

Los porcentajes estándar 75 y 80 quedan cubiertos. El caso de ejemplo 60% debe
sumarse como test parametrizado cuando se defina el punto de configuración
concreto. La suite no impone si esa configuración será un constructor de
paquete, una propiedad o una constante configurable, porque eso no quedó
expresado como API en los diagrams.

## Estructura

### `PolicyTest`

Verifica:

- primera elección alternativa con residuo cero;
- patrones 50%, 75% y 80%;
- igualdad formal de Bresenham en todos los prefijos;
- residuos independientes para agentes y reservas;
- una selección sin conflicto no consume residuo;
- `onTransitionFired()` no consume residuo;
- prioridad del conflicto de agentes si ambos grupos aparecen juntos;
- `NONE` selecciona aleatoriamente dentro de los candidatos recibidos;
- validación de candidatos nulos y vacíos.

No se exige una distribución estadística a `NONE`: sólo se exige que siempre
devuelva un candidato efectivo. Un test de frecuencia sería probabilístico y
podría fallar aun con una implementación correcta.

### `TimeRestrictionsTest`

Usa el `LongSupplier` de paquete como reloj determinístico y verifica:

- configuración desactivada;
- validación de alpha y beta;
- estados `NOT_ENABLED`, `TOO_EARLY` y `ALLOWED`;
- límites exactos de alpha y beta;
- una actualización `true -> true` no reinicia el reloj;
- una nueva instancia `false -> true` sí lo reinicia;
- tiempo restante sin valores negativos ni cero prematuro;
- actualización desde el vector de sensibilizadas;
- reinicio temporal después de un disparo;
- `awaitUntilEarliestFireTime()` duerme hasta ETF;
- una interrupción durante el sueño se propaga.

Los dos últimos tests buscan el método por reflexión para que la suite pueda
compilar antes de que el método sea movido desde `Monitor` hacia
`TimeRestrictions`. Cuando exista, puede reemplazarse la reflexión por una
llamada directa desde el mismo paquete.

### `RdPTest`

Verifica:

- marcado y sensibilización iniciales;
- existencia y consistencia de `isSensitized(int)`;
- formato unitario del vector de disparo;
- rechazo atómico de disparos inválidos;
- ausencia de tokens negativos;
- P-invariantes después de cada transición;
- retorno al marcado inicial para las cuatro T-invariantes:

```text
T0,T1,T2,T5,T6,T9,T10,T11
T0,T1,T2,T5,T7,T8,T11
T0,T1,T3,T4,T6,T9,T10,T11
T0,T1,T3,T4,T7,T8,T11
```

La reflexión usada para `isSensitized(int)` permite que la suite compile antes
de agregar el método acordado al código pendiente.

### `InvariantsTest`

Comprueba que:

- el marcado inicial cumple todos los P-invariantes;
- una alteración controlada informa el invariante roto, el valor obtenido y el
  esperado.

### `LogServiceTest`

Comprueba que:

- un disparo válido produce una única línea con `ok=true`, marcado consistente
  y `pinv=OK`;
- un marcado corrompido genera primero `PINV_FAIL` y luego el disparo con
  `pinv=FAIL(...)`;
- múltiples threads escribiendo eventos no mezclan fragmentos de líneas ni
  pierden registros.

### `ThreadsTest`

Usa implementaciones de `MonitorInterface` exclusivas de test para comprobar:

- orden exacto de un path;
- acreditación de una única finalización;
- registro de inicio sólo para paths que comienzan en T0;
- corte de secuencia si un disparo devuelve `false`;
- los workers intermedios no acreditan finalizaciones;
- dos workers concurrentes no superan el objetivo global.

### `MonitorConcurrencyTest`

Son tests de comportamiento, no de estructura privada.

Cubren:

- validación de índices y liberación tras una excepción;
- ausencia de consultas a `Policy` si no existen waiters reales;
- eliminación del deferral artificial de T6/T7;
- espera por sensibilización y continuación por signal-and-exit;
- `signalOne()` despierta exactamente un waiter;
- primer conflicto real T2/T3 selecciona T3;
- primer conflicto real T6/T7 selecciona T7;
- el waiter no seleccionado permanece esperando;
- limpieza del contador al interrumpir un waiter;
- preservación del flag de interrupción por `Monitor`;
- una espera temporal libera el monitor para otro disparo.

Los tests concurrentes coordinan estados mediante semáforos reales,
`AtomicReference`, espera acotada y timeouts. Los `Thread.sleep()` productivos
sólo aparecen en los escenarios temporales que deben verificar precisamente
ese comportamiento.

### `MainConfigurationTest`

Fija la configuración descrita por los diagrams:

- `WorkerSpec` posee el componente `instances`;
- `createWorkers()` genera nueve hilos distinguibles;
- el total resulta de un worker para cada path base y dos instancias para cada
  rama final de reservas.

### `SystemAcceptanceTest`

Ejecuta el sistema con los nueve workers para los modos:

- `NONE`;
- `BALANCED`;
- `PRIORITIZED`.

Cada ejecución debe:

- completar exactamente 24 invariantes sin deadlock;
- no superar los contadores de iniciadas o completadas;
- detener todos los workers;
- conservar todos los P-invariantes;
- volver exactamente al marcado inicial.

Se usan 24 invariantes para que el test habitual sea rápido. La ejecución
manual de 186 sigue siendo el criterio final de validación prolongada.

## Ejecución

Suite completa:

```bash
mvn test
```

Sólo una clase:

```bash
mvn -Dtest=PolicyTest test
mvn -Dtest=MonitorConcurrencyTest test
```

Sólo un método:

```bash
mvn -Dtest=PolicyTest#prioritizedReservationsUseEightyPercentBresenhamFromZero test
```

En un entorno sin red pero con dependencias ya descargadas:

```bash
mvn -o test
```

Para comprobar únicamente que producción y tests compilan:

```bash
mvn -DskipTests test
```

## Línea base antes del rediseño

Ejecución registrada al crear la suite:

```text
Tests run: 56
Passed:    38
Failures:  13
Errors:     5
```

Los 18 resultados rojos son esperados sobre el código anterior:

- `Policy.choose()` todavía rechaza el modo `NONE`;
- los patrones anteriores no comienzan siempre por la alternativa;
- reservas conserva una decisión sticky;
- agentes y reservas todavía usan selectores manuales;
- el monitor consulta la política con T6/T7 aunque no haya waiters;
- `shouldDeferToPolicySelectedReservation()` todavía puede bloquear un disparo
  `ALLOWED`;
- los conflictos reales despiertan la rama anterior, no la indicada por
  `A0 = 0`;
- `TimeRestrictions.awaitUntilEarliestFireTime(int)` todavía no existe;
- `RdP.isSensitized(int)` todavía no existe;
- `WorkerSpec.instances` todavía no existe;
- `Main.createWorkers()` todavía crea siete workers.

Los tests de RdP, P-invariantes, workers, límites temporales ya existentes y la
aceptación corta pasan. Eso permite distinguir regresiones de la red base de los
cambios intencionales del rediseño.

## Criterio para terminar la implementación

La reimplementación se considera alineada con esta etapa cuando:

1. los 56 tests quedan verdes de forma repetida;
2. los tests concurrentes no dejan hilos vivos después de un fallo;
3. la ejecución de 186 invariantes termina en los tres modos;
4. el log no contiene `PINV_FAIL`;
5. el analizador encuentra 186 T-invariantes sin transiciones remanentes.

Después de estabilizar la implementación conviene agregar una ejecución de
estrés repetida para la carrera entre interrupción, `signalOne()` y `handoff()`.
Ese test necesita un punto de coordinación determinístico; no se agregó una
carrera probabilística que pudiera dar falsos verdes o falsos rojos.
