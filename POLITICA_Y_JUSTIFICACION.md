# Política, exposición y justificación de la segmentación

> **Propósito.** Consolidar en un único documento los razonamientos sobre la
> segmentación en seis workers: cómo interactúa con las restricciones temporales
> (alfa finito, beta infinito), cómo afecta la **cantidad y calidad de
> oportunidades** que tiene `Policy.choose()` para influir en el resultado, y
> por qué no crear un hilo por transición.
>
> **Contexto.** La derivación formal de los seis segmentos desde el paper está
> en `GUIA_ESTUDIO_PAPER_Y_PROYECTO.md` (§9). Los criterios de aceptación
> de corridas están en `README.md`. La configuración vigente está en `README.md`
> y es fuente de verdad en `Main.java`.

## Índice

1. [Pregunta de origen](#1-pregunta-de-origen)
2. [Configuración temporal y segmentación vigente](#2-configuración-temporal-y-segmentación-vigente)
3. [Análisis topológico por segmento](#3-análisis-topológico-por-segmento)
4. [Exposición de la política](#4-exposición-de-la-política)
5. [Por qué NO crear un hilo por transición](#5-por-qué-no-crear-un-hilo-por-transición)
6. [Tabla maestra consolidada](#6-tabla-maestra-consolidada)
7. [Frase de defensa por segmento](#7-frase-de-defensa-por-segmento)
8. [Qué cambia y qué NO cambia](#8-qué-cambia-y-qué-no-cambia)
9. [Cómo medirlo (experimento A/B)](#9-cómo-medirlo-experimento-ab)
10. [Cuándo el criterio cambia](#10-cuándo-el-criterio-cambia)
11. [Referencias](#11-referencias)

---

## 1. Pregunta de origen

Partimos de dos observaciones de diseño:

1. **La segmentación del paper**: cada responsabilidad es una secuencia fija de
   transiciones ejecutada por un worker (p. ej. `[T2,T5]`, `[T7,T8]`,
   `[T6,T9,T10]`).
2. **Las transiciones temporizadas**: alfa finito, beta infinito. Un hilo que
   intenta disparar una transición antes de su EFT entra en `TOO_EARLY`, libera
   el monitor y duerme hasta la próxima oportunidad.

La pregunta concreta es doble:

> ¿Un **hilo por transición** (en lugar de un worker por segmento) aprovecha los
> tiempos muertos de las transiciones temporizadas para acelerar la red?
>
> ¿Y ese mismo desglose aumenta la **exposición** de la política, dándole más
> oportunidades de influir en los porcentajes?

La intuición detrás de la consulta era:

```text
T7 produce P12 → T8 consume P12 → solo 1 cliente en cancelación a la vez.
¿Separar T7 y T8 en hilos distintos lo resuelve?
```

La respuesta corta es **no** para el paralelismo topológico, y **sí (marginal)**
para la exposición de la política. El resto del documento demuestra por qué,
segmento por segmento.

---

## 2. Configuración temporal y segmentación vigente

### 2.1 Transiciones temporizadas

**[PROYECTO]** Fuente: `Monitor.java:20-22`, aplicado con `timed=true`.

| Transición | Alfa (ms) | Beta | Semántica                          |
| ---------- | --------- | ---- | ---------------------------------- |
| T1         | 100       | ∞    | EFT absoluto desde sensibilización |
| T4         | 60        | ∞    | idem                               |
| T5         | 60        | ∞    | idem                               |
| T8         | 80        | ∞    | idem                               |
| T9         | 40        | ∞    | idem                               |
| T10        | 40        | ∞    | idem                               |

Todas las demás transiciones (T0, T2, T3, T6, T7, T11) no están temporizadas.

> **[INFERENCIA]** Con `beta = ∞` no existe expiración: si una transición se
> desensibiliza, la ventana desaparece; si permanece sensibilizada, la ventana
> queda abierta indefinidamente luego de la EFT. No hay "forzado" por vencer.

### 2.2 Segmentos vigentes

**[PROYECTO]** Fuente: `Main.java:78-84`.

| Worker   | Path            | Índices    | Segmento | Rol                 |
| -------- | --------------- | ---------- | -------- | ------------------- |
| Thread-1 | `[T0, T1]`      | `[0,1]`    | `S_A`    | Ingreso del cliente |
| Thread-2 | `[T2, T5]`      | `[2,5]`    | `S_B`    | Agente superior     |
| Thread-3 | `[T3, T4]`      | `[3,4]`    | `S_C`    | Agente inferior     |
| Thread-4 | `[T6, T9, T10]` | `[6,9,10]` | `S_D`    | Confirmación y pago |
| Thread-5 | `[T7, T8]`      | `[7,8]`    | `S_E`    | Cancelación         |
| Thread-6 | `[T11]`         | `[11]`     | `S_F`    | Retorno a idle      |

---

## 3. Análisis topológico por segmento

Cada subsección resume: topología (Pre/Post), plaza acotante (P-invariante),
comportamiento con worker único vs. hilo por transición, e interacción con la
temporización. La conclusión transversal es que **ninguna transición temporizada
libera un recurso durante su alfa**; el límite lo imponen los P-invariantes, no
la cantidad de hilos.

### 3.1 `S_A = [T0, T1]` — Ingreso

| Transición | Pre        | Post   | Timed              |
| ---------- | ---------- | ------ | ------------------ |
| T0         | P0, P1, P4 | P2     | no                 |
| T1         | P2         | P1, P3 | **T1 alfa=100 ms** |

- Invariante: `P1 + P2 = 1` → un único cliente en tránsito (P2) a la vez.
- Durante la espera de T1, `P2=1` retiene P1; ningún otro cliente puede ingresar
  (`T0` requiere P1). Con hilos separados, el hilo de T0 queda bloqueado en
  `waitForSensitization(T0)` por el mismo token: **misma capacidad, mismo
  marcado**.
- **Conclusión:** separar T0/T1 no aporta progreso adicional; el cuello es P1.

**Argumento semántico/conceptual.** T0 y T1 representan el mismo cliente
cruzando una frontera natural del sistema: _ingresó a la agencia_ (T0) y _queda
en espera de atención_ (T1). Operan como una **inversión producción-consumo**
sobre el recurso P1: T0 toma los recursos del ambiente (P0, P1, P4) y T1
devuelve P1 y deposita al cliente en P3. Entre ambos no hay un punto de decisión
ni una frontera de responsabilidad; separar la transición en dos hilos carece de
justificación semántica.

**¿Por qué T0 no se dispara inmediatamente después de T1?**

Topológicamente, T0 **sí** podría dispararse justo después de T1: T1 devuelve
P1 (lo garantiza `P1+P2=1`) y T0 requiere P1. La razón por la que no ocurre es
**contractual**: el worker tiene un path fijo `[T0, T1]` y recorre el par en
serie; nunca intenta T0 dos veces sin pasar por T1. Es una restricción del
worker, no de la red.

**Conclusión sobre velocidad de evolución.** La velocidad del tramo está
dominada por el alfa de T1 (100 ms), no por la cantidad de workers ni por el
acoplamiento del path. Separar T0 y T1 no acelera la evolución de la red: solo
sumaría contención del mutex sin cambiar el marcado posible ni la capacidad.

### 3.2 `S_B = [T2, T5]` — Agente superior

| Transición | Pre    | Post   | Timed             |
| ---------- | ------ | ------ | ----------------- |
| T2         | P3, P6 | P4, P5 | no                |
| T5         | P5     | P6, P9 | **T5 alfa=60 ms** |

- Invariante: `P5 + P6 = 1` → agente unitario; un solo cliente en la rama.
- Durante la espera de T5, `P6=0`; el hilo (único o separado) queda bloqueado al
  intentar T2 de otro cliente. **No hay oportunidad aprovechable** para un
  segundo ingreso a la rama.
- **Conclusión:** la temporización de T5 no crea paralelismo; el recurso del
  agente sigue ocupado toda la espera.

### 3.3 `S_C = [T3, T4]` — Agente inferior

| Transición | Pre    | Post   | Timed             |
| ---------- | ------ | ------ | ----------------- |
| T3         | P3, P7 | P4, P8 | no                |
| T4         | P8     | P7, P9 | **T4 alfa=60 ms** |

- Invariante: `P7 + P8 = 1`. Análogo exacto de `S_B` con P7/P8.
- **Conclusión:** separar T3/T4 no agrega paralelismo.

### 3.4 `S_D = [T6, T9, T10]` — Confirmación y pago

| Transición | Pre     | Post     | Timed              |
| ---------- | ------- | -------- | ------------------ |
| T6         | P9, P10 | P11      | no                 |
| T9         | P11     | P13      | **T9 alfa=40 ms**  |
| T10        | P13     | P10, P14 | **T10 alfa=40 ms** |

- Invariante: `P10 + P11 + P12 + P13 = 1` → un solo cliente en toda la zona de
  decisión/pago.
- Cuello **global**: P10, compartido con `S_E` (cancelación). Durante las esperas
  de T9/T10, P10=0 y el hilo de T6 de otro cliente queda bloqueado.
- **Conclusión:** el cuello es topológico (P10) y ajeno a la cantidad de hilos.

### 3.5 `S_E = [T7, T8]` — Cancelación

| Transición | Pre     | Post     | Timed             |
| ---------- | ------- | -------- | ----------------- |
| T7         | P9, P10 | P12      | no                |
| T8         | P12     | P10, P14 | **T8 alfa=80 ms** |

- Invariante: `P10 + P11 + P12 + P13 = 1` → `P12` ya es de capacidad 1 **por la
  red**, no por el número de workers.
- Durante los 80 ms de T8, P10=0; ningún otro cliente puede iniciar T6/T7.
- **Conclusión:** la duda original (separar T7/T8 para "sacar" a más clientes
  del estado de cancelación) queda resuelta: `P12` es estructuralmente único.

### 3.6 `S_F = [T11]` — Retorno

| Transición | Pre | Post | Timed |
| ---------- | --- | ---- | ----- |
| T11        | P14 | P0   | no    |

- `P14` puede acumular hasta 5 tokens (ver guía §9.3), pero T11 no está
  temporizada: cada disparo es inmediato. Un único worker consume P14 en serie;
  la serialización es por el mutex del monitor, no por alfa.
- **Conclusión:** no aplica el análisis temporal. T11 es responsabilidad común
  posterior a la convergencia (caso 3 del paper).

### 3.7 Síntesis topológica

> **[INFERENCIA]** Cada transición temporizada (T1, T4, T5, T8, T9, T10)
> retiene mientras espera su alfa un recurso único (P1, P7, P6, P10, P10, P10)
> o una plaza de capacidad 1 (P2, P8, P5, P11/P13, P12). Todos los clientes que
> quieren progresar más allá de ese punto quedan bloqueados **por el mismo
> token**. El timing es relevante para observabilidad y determinismo del
> interleaving, pero **no habilita paralelismo topológico adicional**.

| Timing                          | Efecto real                                                                                                        |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `TOO_EARLY` en un worker único  | El worker duerme; nadie más puede avanzar en ese punto (recurso ocupado).                                          |
| `TOO_EARLY` con hilos separados | El hilo "aguas arriba" se bloquea igualmente en `waitForSensitization` por el mismo recurso.                       |
| Resultado                       | **Mismo marcado posible, misma capacidad, overhead mayor** (más threads, más contención en `entry`, más handoffs). |

---

## 4. Exposición de la política

### 4.1 Qué significa "exposición"

**[PROYECTO]** `Policy.choose()` se invoca en un único punto del monitor:
`Monitor.selectWaiterOrRelease()` (tras un disparo exitoso). Cada invocación
recibe la lista de candidatos de `wakingCandidates()`:

```java
rdp.isSensitized(t) && queues.hasWaiters(t)
```

**Exposición** = cantidad de invocaciones a `choose()` en las que el par de
conflicto (`{T2,T3}` o `{T6,T7}`) aparece **completo** en los candidatos.
Mientras mayor la exposición, más incidencia real tiene la política en la
distribución observada.

### 4.2 Dos tipos de espera en el monitor

**[PROYECTO]** El monitor tiene dos mecanismos de bloqueo distintos y es
**crítico** distinguirlos:

| Mecanismo                  | Método                                                                                  | ¿Se registra como waiter en `Queues`?            | ¿La política lo ve?                        |
| -------------------------- | --------------------------------------------------------------------------------------- | ------------------------------------------------ | ------------------------------------------ |
| Espera por sensibilización | `Monitor.waitForSensitization()`                                                        | **Sí** (`incrementWaitingCount` + `sem.acquire`) | Sí, como candidato en `wakingCandidates()` |
| Espera temporal (alfa)     | `Monitor.waitUntilEarliestFireTime()` → `TimeRestrictions.awaitUntilEarliestFireTime()` | **No** (`Thread.sleep` puro)                     | **No**                                     |

```java
// waitForSensitization: registra waiter → visible para Policy
queues.incrementWaitingCount(transition);
ownership.release();
queues.getSemaphoreForTransition(transition).acquire();
ownership.wakeFromQueue();

// waitUntilEarliestFireTime: duerme sin registrarse → invisible para Policy
ownership.release();
time.awaitUntilEarliestFireTime(transition);  // Thread.sleep(restante)
```

> **[INFERENCIA]** El hilo que duerme esperando su alfa está **invisible** para
> la política. Las oportunidades de decisión no nacen de la espera temporal en
> sí, sino de otros hilos que quedan bloqueados por sensibilización mientras esa
> espera tiene lugar.

### 4.3 Un worker por segmento vs. un hilo por transición

**[INFERENCIA]** La diferencia no está en el throughput (la topología no cambia),
sino en **dónde queda dormido cada hilo**:

| Configuración                   | Hilo de T5 duerme en       | Clientes de T2 que llegan mientras tanto                                                                                            |
| ------------------------------- | -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Un worker `[T2,T5]`             | `Thread.sleep` (invisible) | Ninguno puede disparar T2 (P6=0), y el único hilo disponible es el mismo que duerme → **se quedan sin hilo que los ponga en cola**  |
| Hilos separados `[T2]` y `[T5]` | `Thread.sleep` (invisible) | El **hilo de T2** intenta el nuevo cliente, falla por P6=0 y entra en `waitForSensitization(T2)` → **queda registrado como waiter** |

El punto clave: en la configuración separada, **los clientes que no pueden
avanzar quedan explícitamente en la cola de la transición**, y eso es
exactamente lo que `wakingCandidates()` detecta. Cuando el hilo de T5 despierta
y dispara (liberando P6), la política recibe a T2 —ahora sensibilizada— con
waiter → candidato efectivo.

### 4.4 Caso `S_B = [T2, T5]` (T5 alfa=60 ms)

**Un worker `[T2,T5]` (configuración vigente):**

```text
Thread-2:
  T2 (cliente A) → P5=1, P6=0
  T5 → TOO_EARLY → Thread.sleep(60)   ← no queda en cola de T5
  60 ms más tarde → T5 dispara → P6=1, P9+1
  selectWaiterOrRelease(): candidatos dependen de waiters previos
```

Durante los 60 ms, Thread-2 está dormido. Ningún otro hilo puede tomar un
cliente de P3 hacia T2 (P6=0). **No se genera waiter de T2 durante esa ventana**
(salvo que el evento de sensibilización venga de otro disparo).

**Hilos separados `[T2]` y `[T5]`:**

```text
Hilo-T5: entra, ve P5=1, T5 TOO_EARLY → Thread.sleep(60)

Mientras tanto:
Hilo-T2: cliente B en P3 → T2 requiere P6 → P6=0 → waitForSensitization(T2)
         → waitingCount[T2]=1  ← WAITER VISIBLE

Hilo-T5 despierta → T5 dispara → P6=1, P9+1
selectWaiterOrRelease():
  → T2 sensibilizada + waiter en T2 → candidato
  → si además T3 está sensibilizada con waiter → Policy ve {T2,T3}
  → oportunidad AGENTS (75/25 o 50/50 según modo)
```

**[INFERENCIA]** Con la separación, cada ventana alfa de T5 **produce
oportunidades nuevas**: el hilo de T2 queda en cola durante la espera y, al
liberarse P6, la política decide entre T2/T3. Con un solo worker, esa ventana no
genera nada.

### 4.5 Caso `S_E = [T7, T8]` (T8 alfa=80 ms)

Análogo, con un efecto extra: T8 libera **P10**, el recurso global compartido
con la confirmación.

```text
Hilo-T7 (cliente A): T7 → P12=1, P10=0
Hilo-T8: TOO_EARLY → Thread.sleep(80)

Mientras tanto (hilos separados):
Hilo-T7 (cliente B): T7 requiere P9, P10 → P10=0 → waitForSensitization(T7)
         → waitingCount[T7]=1  ← waiter visible

Hilo-T8 despierta → T8 dispara → P10=1, P14+1
selectWaiterOrRelease():
  → T7 sensibilizada + waiter → candidato
  → si T6 también sensibilizada con waiter → Policy ve {T6,T7}
  → oportunidad RESERVATIONS (80/20 o 50/50)
```

**[INFERENCIA]** Al liberarse P10, **dos** alternativas pueden reactivarse (T6 y
T7): la ventana de T8 produce oportunidades tanto en confirmación como en
cancelación según qué colas tengan waiters.

### 4.6 ¿Por qué alfas pequeños potencian el efecto?

**[INFERENCIA]** Con alfas de 40–100 ms el efecto se amplifica por dos razones:

1. **Más ventanas por unidad de tiempo**: cada cliente que pasa por una
   transición temporizada genera una ventana de inactividad del hilo (invisible)
   pero también una ventana en la que clientes aguas arriba quedan bloqueados y
   se registran en sus colas (visibles).
2. **Mayor desincronización entre hilos**: con esperas cortas y múltiples
   clientes, los hilos terminan "fuera de fase"; la probabilidad de que un
   cliente esté esperando en la cola de T2 o T7 mientras otro duerme en T5/T8 es
   alta.

El efecto neto: **más invocaciones a `choose()` con pares completos**, y por
ende mayor incidencia de los porcentajes configurados (Bresenham) sobre la
distribución observada.

### 4.7 Cuándo el efecto es real y cuándo no

**Requiere** (las tres simultáneas):

1. Que exista un cliente _aguas arriba_ esperando (P3 para T2/T3, P9 para T6/T7).
2. Que el recurso acotante esté retenido por el otro ramal (P6=0, P7=0, P10=0).
3. Que el hilo temporizado duerma (TOO_EARLY) liberando el monitor, sin
   registrarse en la cola de la transición temporizada.

**No aplica** cuando:

- No hay cola de espera libre para el otro worker (todos los clientes están en
  otra parte de la red).
- La transición no está temporizada (no hay ventana TOO_EARLY).
- El alfa es tan chico que la espera es menor al costo del cambio de contexto
  (el overhead de la separación supera la ganancia de exposición).

---

## 5. Por qué NO crear un hilo por transición

Argumentos transversales que aplican a todos los segmentos:

1. **[PAPER]** El algoritmo 4.2 define los segmentos **en los límites de forks y
   convergencias**, no en cada transición. Separar sin un punto de decisión
   rompe ese contrato y debe justificarse como optimización aparte.
2. **[INFERENCIA]** Los P-invariantes fijan la capacidad máxima de cada tramo
   (`P1+P2`, `P5+P6`, `P7+P8`, `P10+P11+P12+P13`). **La capacidad no crece con
   la cantidad de hilos**.
3. **[PROYECTO/INFERENCIA]** Las transiciones temporizadas (T1, T4, T5, T8, T9,
   T10) retienen durante su alfa un recurso único o una plaza de capacidad 1.
   La velocidad de la red está dominada por esas alfas, no por el número de
   hilos.
4. **[INFERENCIA]** Dividir una rama en dos workers agrega **contención en el
   mutex del monitor** y más handoffs: costo de scheduling sin contrapartida
   topológica.
5. **[INFERENCIA]** La única ganancia real de separar es la **exposición de la
   política** (más oportunidades con pares completos), que aplica a ramas con
   pares de conflicto (`S_B`, `S_C`, `S_D`, `S_E`), no a tramos lineales como
   `S_A` o `S_F`. Y esa ganancia es **marginal** (ver §4.4–§4.6).

---

## 6. Tabla maestra consolidada

| Segmento          | Criterio paper        | Capacidad máxima | Timed            | Par de conflicto | ¿Separar aporta?      | Justificación de NO separar         |
| ----------------- | --------------------- | ---------------- | ---------------- | ---------------- | --------------------- | ----------------------------------- |
| `S_A [T0,T1]`     | Caso 1 (lineal)       | 1 (`P1+P2`)      | T1 100 ms        | No               | No                    | paper + par atómico + sin conflicto |
| `S_B [T2,T5]`     | Caso 2 (rama fork)    | 1 (`P5+P6`)      | T5 60 ms         | T2/T3 (AGENTS)   | Marginal (exposición) | topología; beneficio < costo        |
| `S_C [T3,T4]`     | Caso 2 (rama fork)    | 1 (`P7+P8`)      | T4 60 ms         | T2/T3 (AGENTS)   | Marginal (exposición) | topología; beneficio < costo        |
| `S_D [T6,T9,T10]` | Caso 2 (rama fork)    | 1 (`P10+..+P13`) | T9 40, T10 40 ms | T6/T7 (RESERV.)  | Marginal (exposición) | cuello es P10, no hilos             |
| `S_E [T7,T8]`     | Caso 2 (rama fork)    | 1 (`P12`)        | T8 80 ms         | T6/T7 (RESERV.)  | Marginal (exposición) | P12 capacidad 1 por red             |
| `S_F [T11]`       | Caso 3 (convergencia) | 5 (`P14`)        | No               | No               | No                    | responsabilidad común + sin alfa    |

---

## 7. Frase de defensa por segmento

- **S_A:** "T0 y T1 son un invariante lineal del paper, sin puntos de decisión
  entre ambos: el mismo cliente cruza la frontera de ingreso y la espera es una
  unidad atómica sobre el recurso P1 (`P1+P2=1`, inversión producción-consumo).
  Topológicamente T0 podría repetirse tras T1, pero la velocidad del tramo la
  domina el alfa de T1 (100 ms), no el path fijo del worker. No forman par de
  conflicto, así que separarlos solo sumaría contención."
- **S_B/S_C:** "Las ramas de agentes son forks del paper; cada una es un
  segmento con agente unitario (`P5+P6=1` / `P7+P8=1`). La espera de T5/T4 no se
  aprovecharía con más hilos: el cuello es el recurso del agente."
- **S_D/S_E:** "El cuello real de la zona de decisión es P10 (único y
  compartido). P12/P11/P13 tienen capacidad 1 por el invariante
  `P10+P11+P12+P13=1`. Separar T7/T8 o T6/T9/T10 no cambia esta serialización
  estructural; a lo sumo incrementa marginalmente la exposición de la política."
- **S_F:** "T11 es el segmento común posterior a la convergencia (caso 3 del
  paper). P14 acumula hasta 5 tokens y T11 no está temporizada: un solo worker
  consumidor es la implementación mínima coherente."

---

## 8. Qué cambia y qué NO cambia

| Dimensión                                                     | Con hilos separados                                    |
| ------------------------------------------------------------- | ------------------------------------------------------ |
| Topología / P-invariantes                                     | NO cambia                                              |
| Capacidad de plazas                                           | NO cambia                                              |
| Throughput máximo                                             | NO cambia (sigue limitado por P10, P5+P6, P7+P8)       |
| **Número de oportunidades de `choose()` con pares completos** | **Aumenta** (efecto principal)                         |
| **Incidencia de la política en la distribución**              | **Aumenta** (más decisiones con ambas ramas presentes) |
| Contención del mutex / overhead de scheduling                 | Aumenta (costo)                                        |

**[INFERENCIA]** La separación es un **intercambio**: se paga contención
incrementada del monitor a cambio de más exposición de la política. Es una
decisión de _observabilidad/alineación con la consigna_, no de rendimiento.

---

## 9. Cómo medirlo (experimento A/B)

Para verificar empíricamente, comparar dos configuraciones con la misma
`timed=true`, mismos alfas y mismo `M0`:

| Configuración | Workers                                                                                     |
| ------------- | ------------------------------------------------------------------------------------------- |
| A (vigente)   | `[T0,T1]`, `[T2,T5]`, `[T3,T4]`, `[T6,T9,T10]`, `[T7,T8]`, `[T11]`                          |
| B (separada)  | `[T0,T1]`, `[T2]`, `[T5]`, `[T3]`, `[T4]`, `[T6]`, `[T9]`, `[T10]`, `[T7]`, `[T8]`, `[T11]` |

Métricas a comparar (según los criterios del `README.md`):

1. Invocaciones totales a `Policy.choose()`.
2. Oportunidades con `{T2,T3}` completos y con `{T6,T7}` completos.
3. Porcentaje de decisiones de conflicto sobre disparos totales.
4. Desvío de la distribución observada vs. el target configurado (75/25, 80/20).
5. Duración total (para cuantificar el costo de la mayor contención).

**[EVIDENCIA]** En la corrida verificada con la configuración A ya se observó
`En conflicto: 186/186` para agentes y reservas (toda la ejecución pasó por
decisiones con ambas ramas presentes), con distribuciones 74.73% y 79.57%, muy
cerca del target. La pregunta pendiente es cuánto mejora ese acercamiento la
configuración B y a qué costo de tiempo.

---

## 10. Cuándo el criterio cambia

La justificación actual depende de:

- **Modelo**: si se modificaran las capacidades (p. ej. `P10=2`, `P6=2`,
  `P1=2`), la segmentación debería recalcularse con el algoritmo del paper (ver
  `GUIA_ESTUDIO_PAPER_Y_PROYECTO.md` §9) y el análisis de capacidad (que
  aquí se resume en §3) quedaría obsoleto.
- **Alphas**: si una transición tuviera alfa mucho mayor, la exposición de
  política de separar podría valer la pena; habría que medirlo con el
  experimento A/B de §9.
- **Consigna**: si el entregable exigiera N hilos por segmento (p. ej. "ejecutar
  con 2 hilos en confirmación/cancelación"), se documentaría como decisión
  experimental con evidencia, nunca como resultado del paper.

---

## 11. Referencias

- Modelo y marcado: `src/main/java/org/concurrent/project/RdP.java`.
- Configuración temporal: `src/main/java/org/concurrent/project/Monitor.java:20-22`.
- Workers: `src/main/java/org/concurrent/project/Main.java:78-84`.
- Monitor y política: `Monitor.java` (`selectWaiterOrRelease`,
  `wakingCandidates`, `waitForSensitization`, `waitUntilEarliestFireTime`),
  `Policy.java` (`choose`, `activeConflictIn`, `selectByPercentage`).
- P-invariantes: `src/main/java/org/concurrent/project/Invariants.java`.
- Derivación de segmentos y capacidades (algoritmo 4.1–4.3):
  `GUIA_ESTUDIO_PAPER_Y_PROYECTO.md`.
- Criterios de aceptación de corridas: `README.md`.
- Comandos: `scripts/run.sh` + `regex/InvariantsAnalyzer.py` (186 invariantes).
