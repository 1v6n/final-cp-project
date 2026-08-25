# Guía de estudio del paper y aplicación al proyecto

> **Propósito.** Estudiar el paper `docs/sources/paper.pdf` sin mezclarlo con las
> decisiones de esta implementación y, después, aplicar sus ideas a la Red de Petri
> y a la arquitectura concurrente del proyecto.
>
> **Lectura recomendada:** estudiar primero la Parte I. Una vez entendido el método
> del artículo, recorrer la Parte II con el código del proyecto abierto. La Parte III
> reúne la verificación y las preguntas para la defensa.

> **Regla de lectura:** la Parte I explica el paper. La Parte II empieza a aplicar el
> método a nuestro proyecto. La Parte III documenta cómo comprobar los resultados y
> qué afirmaciones se pueden defender.

## Cómo leer las afirmaciones

La guía separa la fuente de cada conclusión y limita las etiquetas según la parte
del documento.

En la Parte I se usan solamente:

- **[PAPER]**: está respaldado por el artículo, con sección/página indicada.
- **[INTERPRETACIÓN]**: lectura razonada de una definición o procedimiento del paper.
- **[AMBIGÜEDAD]**: punto que el artículo no formaliza por completo.

En las Partes II y III se agregan las etiquetas de aplicación:

- **[CONSIGNA]**: requisito del TP o de su documentación.
- **[PROYECTO]**: comportamiento observado directamente en el código vigente.
- **[INFERENCIA]**: conclusión deducida del modelo, la alcanzabilidad o una ecuación.
- **[DECISIÓN DEL PROYECTO]**: elección que el paper no impone.
- **[EVIDENCIA]**: resultado de una ejecución o herramienta reproducible.

Cuando una decisión del proyecto no está fijada por el paper, se explica en la Parte
II y no se presenta como una regla del artículo.

---

## Índice

### Parte I. Estudio del paper

1. [Qué problema resuelve el paper](#1-qué-problema-resuelve-el-paper)
2. [Vocabulario mínimo y formalismo](#2-vocabulario-mínimo-y-formalismo)
3. [Invariantes, conflictos y segmentos](#3-invariantes-conflictos-y-segmentos)
4. [Arquitectura propuesta](#4-arquitectura-propuesta)
5. [Los tres algoritmos del paper](#5-los-tres-algoritmos-del-paper)
6. [Ejemplo del paper](#6-ejemplo-del-paper)
7. [Cómo aplicar el método sin cometer errores](#7-cómo-aplicar-el-método-sin-cometer-errores)

### Parte II. Aplicación al proyecto

8. [Aplicación a la agencia de viajes](#8-aplicación-a-la-agencia-de-viajes)
9. [Responsabilidades teóricas de nuestra red](#9-responsabilidades-teóricas-de-nuestra-red)
10. [Auditoría de la implementación actual](#10-auditoría-de-la-implementación-actual)

### Parte III. Verificación y defensa

11. [Cómo verificar y defender el resultado](#11-cómo-verificar-y-defender-el-resultado)
12. [Glosario y preguntas de defensa](#12-glosario-y-preguntas-de-defensa)

---

## Parte I. Estudio del paper

Las secciones 1 a 7 describen el artículo y su método en términos generales. En esta
parte no se usan las clases, las plazas ni los resultados de nuestra implementación.
La aplicación concreta comienza en la Parte II.

## 1. Qué problema resuelve el paper

**[PAPER]** El paper **no dice simplemente “contá los hilos del programa”**. Parte de una
RdP S3PR que ya modela el sistema y busca transformar parte de ese modelo en una
arquitectura concurrente de software.

Su hipótesis es:

> La RdP contiene la lógica, el estado, los recursos, las restricciones y el
> paralelismo del sistema. Por eso se pueden derivar de ella los agentes de
> ejecución (hilos) y sus responsabilidades.

El artículo entrega **tres resultados diferentes**. Es indispensable no
confundirlos.

| Resultado                               | Pregunta que responde                                                                     | Cómo se calcula                                                                 |
| --------------------------------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| Máximo de hilos activos simultáneamente | ¿Cuántos procesos/clientes pueden estar activos a la vez en un marcado?                   | Máxima suma de tokens en plazas de acción.                                      |
| Segmentos o responsabilidades           | ¿Qué secuencia fija debe ejecutar cada tipo de hilo?                                      | Cortando los T-invariantes en forks y joins.                                    |
| Máximo de hilos necesarios              | ¿Cuántas instancias de cada segmento hacen falta para soportar el paralelismo del modelo? | Máximo marcado de las plazas de acción de cada segmento y suma de esos máximos. |

**[INTERPRETACIÓN]** Por lo tanto, estas afirmaciones no son equivalentes:

```text
“La red tiene como máximo 5 procesos activos”
“La arquitectura tiene 7 responsabilidades lógicas”
“El programa crea 7 objetos Thread”
“El sistema necesita exactamente 7 instancias de hilo”
```

Una arquitectura concreta puede crear más unidades de ejecución persistentes que
procesos activos, porque algunas pueden estar bloqueadas o porque puede reutilizarse
un pool. Si se afirma que una cantidad fue **obtenida por el método del paper**, hay
que ejecutar los tres algoritmos y mostrar el cálculo que lleva a ese número.

### 1.1 Alcance del paper

**[PAPER, §3, PDF pp. 3–5]** El trabajo está orientado a sistemas embebidos, reactivos
y dirigidos por eventos, modelados como RdP no autónomas de la clase **S3PR**
(_Simple Sequential Processes with Resources_). Su idea central es:

- los procesos se representan como trayectorias secuenciales;
- las plazas de recurso representan elementos compartidos;
- las plazas de restricción limitan la admisión o el flujo;
- los T-invariantes describen ciclos repetibles;
- el monitor mantiene el modelo como autoridad sobre el marcado y la
  sincronización.

La jerarquía que usa el artículo es relevante:

1. **S2P**: proceso secuencial simple.
2. **S2PR**: proceso secuencial simple con recursos.
3. **S3PR**: composición de procesos S2PR que comparten recursos.

El artículo distingue el modelo de la red de la forma concreta en que un sistema
recibe eventos y ejecuta sus responsabilidades. Esa separación permite adaptar la
arquitectura a distintos entornos sin cambiar la definición de la red ni sus reglas
de disparo. Las decisiones de esa adaptación pertenecen a la implementación, no a
los tres algoritmos matemáticos.

---

## 2. Vocabulario mínimo y formalismo

Esta sección no pretende enseñar RdP desde cero: fija la notación necesaria para
seguir los algoritmos del artículo.

### 2.1 Red, marcado y habilitación

Una RdP ordinaria puede expresarse como:

$$
PN = (P,T,Pre,Post,M_0)
$$

donde:

- `P` es el conjunto de **plazas**;
- `T` es el conjunto de **transiciones**;
- `Pre(p,t)` indica cuántos tokens consume `t` de `p`;
- `Post(p,t)` indica cuántos tokens produce `t` en `p`;
- `M0` es el **marcado inicial**, es decir, la cantidad inicial de tokens por
  plaza.

Un **token** no es necesariamente una unidad de ejecución. En el modelo representa
un cliente o trabajo en tránsito por el sistema. El paper lo usa para inferir
cuántas instancias de ejecución pueden coexistir.

El marcado actual se escribe como vector:

$$
M=(M(P_0),M(P_1),...,M(P_n))
$$

Una transición `t` está **sensibilizada** o habilitada si todas sus plazas de
entrada tienen tokens suficientes:

$$
\forall p\in P: M(p)\ge Pre(p,t)
$$

Cuando `t` dispara, consume tokens de sus entradas y produce tokens en sus
salidas. La matriz de incidencia es:

$$
C=Post-Pre
$$

Si `\sigma` es un vector de disparo unitario —un `1` en la transición disparada y
`0` en las demás—, el siguiente marcado es:

$$
M' = M + C\sigma
$$

Esta ecuación es la base común de los algoritmos del paper y de cualquier
implementación que ejecute la red. La forma concreta de llevarla a código se
analiza recién en la Parte II.

### 2.2 Qué es una plaza de acción

El paper no cuenta indistintamente todos los tokens. Para estimar actividad,
excluye plazas que no representan una tarea en curso:

- plazas **idle**: clientes/trabajos aún fuera del proceso;
- plazas de **recurso**: disponibilidad de un recurso, no trabajo ejecutándose;
- plazas de **restricción**: cupos o controles de admisión.

Las restantes son las **plazas de acción** (`PA`). Si un token está en una de ellas,
el modelo interpreta que existe una actividad/proceso en curso o esperando una
acción del sistema.

Esta es una definición del modelo. No equivale necesariamente a una unidad de
ejecución que esté usando CPU en ese instante. Un proceso puede estar bloqueado y
continuar contando como activo en el sentido de la red.

### 2.3 Recursos y restricciones

Un recurso se representa con una plaza que contiene tokens disponibles. Una
transición toma un token para usar el recurso y una transición posterior lo repone.

Ejemplo abstracto:

```text
P_recurso --(token)--> T_inicio --> acción --> T_fin --> P_recurso
```

Si `P_recurso` tiene un token, sólo una instancia puede estar usando ese recurso a
la vez. Si tiene `k` tokens, pueden usarlo hasta `k` instancias concurrentes.

Una plaza de restricción suele limitar la cantidad de trabajos admitidos en una
zona del proceso. También se excluye de `PA`: representa una capacidad de control,
no una acción de negocio.

---

## 3. Invariantes, conflictos y segmentos

### 3.1 P-invariantes

Un **P-invariante** es un vector `y` tal que:

$$
y^T C = 0
$$

Por lo tanto, para cualquier marcado alcanzable:

$$
y^T M = y^T M_0
$$

En términos prácticos, es una suma de tokens que permanece constante. Sirve para
demostrar conservación de clientes, recursos o cupos y para justificar que la red
es acotada.

Ejemplo:

$$
M(P5)+M(P6)=1
$$

significa que el recurso de la rama representada por `P6` está disponible (`P6`) o
ocupado por una actividad (`P5`), pero nunca desaparece ni se duplica.

### 3.2 T-invariantes

Un **T-invariante** es un vector no nulo `x` tal que:

$$
Cx=0
$$

`x` indica cuántas veces participa cada transición en un ciclo que deja el marcado
neto sin cambios. Un T-invariante es un **multiconjunto** de transiciones; para
convertirlo en una ejecución real hay que encontrar un orden que respete la
sensibilización.

En este trabajo, las secuencias ordenadas de T-invariantes representan los caminos
completos de un cliente desde la entrada hasta su retorno al estado idle.

No confundir:

```text
P-invariante → conserva una cantidad de tokens entre plazas.
T-invariante → conserva el marcado después de ejecutar un ciclo de transiciones.
```

### 3.3 Conflicto estructural y conflicto efectivo

Dos transiciones están en **conflicto estructural** si comparten alguna plaza de
entrada. En un instante concreto el conflicto es **efectivo** sólo si los tokens
disponibles no alcanzan para que ambas disparen.

Ejemplo:

```text
          T_a
         /
      P ---
         \
          T_b
```

- Si `M(P)=1`, disparar una deshabilita a la otra: conflicto efectivo.
- Si `M(P)=2`, ambas podrían disparar una vez: comparten estructura, pero no hay
  exclusión efectiva entre esas dos ejecuciones.

La política tiene sentido cuando hay alternativas efectivas que deben resolverse
con un criterio —balance, prioridad, etc.—.

### 3.4 Fork, merge y join

Estos términos se usan con cierta ambigüedad en el paper y en la consigna. Es
importante distinguir su topología.

| Estructura    | Significado                                                                    | Ejemplo conceptual       |
| ------------- | ------------------------------------------------------------------------------ | ------------------------ |
| Fork/decisión | Una plaza habilita alternativas de transición.                                 | `P -> T2` o `P -> T3`.   |
| Merge OR      | Varias rutas producen tokens en una misma plaza; cada token puede seguir solo. | `T_a -> P_merge <- T_b`. |
| Join AND      | Una transición requiere tokens provenientes de dos o más rutas a la vez.       | `P_a,P_b -> T_join`.     |

Una red concreta puede tener una convergencia OR aunque el paper use _join_ para
referirse, de forma más general, al punto donde vuelven a encontrarse varios
T-invariantes. Antes de asignar responsabilidades hay que mirar la topología real:
si ninguna transición consume simultáneamente tokens de las ramas, no existe un
join AND. En ese caso puede hablarse de una convergencia o merge OR y explicar qué
responsabilidad común se adopta después de ella.

### 3.5 Qué es un segmento de responsabilidad

Un segmento es una secuencia fija de transiciones que un hilo/agente puede intentar
sin contener lógica para elegir una rama.

El objetivo es que el worker sea determinista:

```text
correcto:  mi responsabilidad es [T2, T5]
incorrecto: si ocurre X disparo T2; si no, disparo T3
```

El segundo caso incorpora una decisión de conflicto dentro del hilo. La arquitectura
del paper concentra esa decisión en el mecanismo de coordinación, no en el segmento
que ejecuta la secuencia.

---

## 4. Arquitectura propuesta

La figura 1 del paper separa responsabilidades. No es un detalle decorativo: evita
mezclar la lógica formal con el mecanismo de concurrencia.

| Componente           | Responsabilidad                                                                           | No debería hacer                                         |
| -------------------- | ----------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| Manejador de eventos | Recibir estímulos y encaminar el trabajo.                                                 | Decidir el estado de la RdP.                             |
| RdP                  | Modelar estado, recursos, sensibilización y evolución.                                    | Elegir prioridades de negocio.                           |
| Monitor              | Proteger la RdP, determinar cuándo puede dispararse una transición, bloquear y señalizar. | Ejecutar las acciones de negocio del hilo.               |
| Política             | Resolver entre alternativas elegibles según un objetivo.                                  | Modificar directamente el marcado.                       |
| Cola por transición  | Bloquear a los solicitantes de una transición no habilitada.                              | Decidir a cuál conflicto dar prioridad.                  |
| Hilo/worker          | Ejecutar su secuencia fija y las acciones asociadas.                                      | Inspeccionar o cambiar el marcado por fuera del monitor. |

**[PAPER]** El flujo arquitectónico general separa el evento, el manejador, el
worker, el monitor y las acciones. Una implementación puede omitir o adaptar una
capa, pero debe declarar esa diferencia cuando se compare con la figura del paper.

El flujo abstracto puede resumirse así:

```text
worker solicita el disparo de una transición
               |
               v
monitor toma exclusión mutua
              |
              +-- Ti no sensibilizada --> worker espera en cola de Ti
              |
                                          +-- transición sensibilizada --> monitor dispara
                                          |
                                          v
                               se calculan solicitantes elegibles
                                          |
                        la política selecciona el siguiente solicitante
```

**[INTERPRETACIÓN]** La política resuelve una elección entre alternativas que el
monitor considera elegibles. No modifica directamente el marcado. El momento exacto
en que se consulta —por ejemplo, antes de un disparo o durante una transferencia de
control a un solicitante bloqueado— depende del diseño de la implementación y no
debe confundirse con la definición de la red.

Una cola por transición equivale a una variable de condición por transición. No
significa que haya una cola por cada cliente: varias unidades de ejecución pueden
esperar la misma transición.

---

## 5. Los tres algoritmos del paper

Las secciones 4.1, 4.2 y 4.3 del paper deben leerse como un único procedimiento.

### 5.1 Algoritmo 1: máximo de hilos activos simultáneamente

Para cada T-invariante `IT_i`:

1. Obtener `PL_i`: plazas que son pre o post de las transiciones del invariante.
2. Quitar idle, recursos y restricciones. El resultado es `PA_i`, las plazas de
   acción.
3. Formar `PA`, la unión de las plazas de acción relevantes.
4. Obtener el árbol o grafo de alcanzabilidad de la RdP y proyectar cada marcado
   sobre `PA`.
5. Para cada marcado `M`, sumar sus tokens en esas plazas:

   $$
   A(M)=\sum_{p\in PA} M(p)
   $$

6. El resultado es:

   $$
   H_{activos}=\max_{M\in Reach(M_0)} A(M)
   $$

**Qué demuestra:** el máximo paralelismo expresado por el modelo.

**Qué no demuestra:** cuántas responsabilidades distintas existen ni cómo se
implementará cada una en una plataforma concreta.

### 5.2 Algoritmo 2: responsabilidades de los hilos

El paper revisa la estructura de los T-invariantes.

#### Caso 1: T-invariante lineal

Si un invariante no comparte transiciones/alternativas relevantes con otro, es un
camino lineal. Un único segmento cubre sus transiciones consecutivas.

```text
T1 -> T2 -> T3 -> T4

Segmento S = [T1,T2,T3,T4]
```

#### Caso 2: fork o conflicto

Si hay alternativas:

```text
      /--> rama izquierda
pre --
      \--> rama derecha
```

se forman:

1. un segmento común previo al fork;
2. un segmento para cada rama posterior.

La razón es arquitectónica: el hilo previo no decide cuál rama tomar. El monitor y
la política resuelven cuál transición habilitada podrá progresar.

#### Caso 3: join/convergencia

Cuando las rutas vuelven a converger:

```text
rama izquierda --\
                 --> punto de unión --> camino común
rama derecha ---/
```

hay segmentos separados antes del punto de unión y **un segmento común posterior**.

**[PAPER]** Estos son los casos que el algoritmo 4.2 usa para separar
responsabilidades. **[AMBIGÜEDAD]** El artículo no define si una implementación
concreta debe ejecutar el segmento común con una sola instancia, con un pool o con
varias unidades reutilizables. Esa elección queda fuera de la segmentación
matemática.

### 5.3 Algoritmo 3: máximo de hilos por segmento

**[PAPER, §4.3, PDF p. 7]** Después de definir los segmentos `S_i`, el artículo
indica repetir la idea del algoritmo 1 por segmento: determinar sus plazas de
acción, examinar los marcados posibles y tomar el “marcado máximo”.

**[AMBIGÜEDAD]** El paper no define con una ecuación cómo ordenar marcados
multidimensionales ni explicita cómo resolver plazas frontera compartidas. Para
aplicar el método de manera reproducible a una red concreta, usamos la siguiente
formalización operativa, por analogía con §4.1:

1. Determinar las plazas de acción `PA(S_i)` asociadas a cada segmento.
2. Para cada marcado alcanzable, proyectar el marcado sobre `PA(S_i)`.
3. Calcular:

   $$
   h_i=\max_{M\in Reach(M_0)}\sum_{p\in PA(S_i)} M(p)
   $$

4. Sumar las capacidades:

   $$
   H_{necesarios}=\sum_i h_i
   $$

Esta ecuación es una **[INTERPRETACIÓN]** operativa de la guía, no una fórmula textual
del artículo. El resultado es una capacidad de ejecución por responsabilidades. No
es el número de tokens globales ni determina por sí solo la cantidad de unidades de
ejecución que se crearán al iniciar un programa.

### 5.4 Qué debe fijarse antes de aplicar el algoritmo 4.3

Antes de sumar los `h_i`, documentar estas tres decisiones:

1. Qué plazas son recursos, restricciones e idle.
2. Dónde empieza y termina cada segmento.
3. A qué segmento se asigna cada plaza de frontera.

**[PAPER]** El artículo habla de plazas asociadas a segmentos y de un segmento
posterior común después de una unión, pero no prescribe una partición global de
plazas frontera ni dice literalmente que una plaza no pueda aparecer en más de un
cálculo.

**[INTERPRETACIÓN]** En una red donde un merge es inmediatamente seguido por otro
fork, conviene asignar cada plaza frontera a una única responsabilidad o declarar
una responsabilidad de despacho. Esto evita inflar artificialmente la suma de
capacidades. La convención debe quedar escrita antes de calcular los `h_i`; no se
debe elegir primero un número de unidades de ejecución y ajustar después las plazas
para obtenerlo.

---

## 6. Ejemplo del paper

El paper usa una S3PR de Huang modificada para evitar deadlock. Sus invariantes son:

```text
IT1 = {T1,T2,T4,T6}
IT2 = {T1,T3,T5,T6}
IT3 = {T7,T8,T9,T10}
```

Clasifica:

```text
idle          = {P1,P8}
recursos      = {P6,P7,P12,P13}
restricciones = {P14}
```

y obtiene las plazas de acción globales:

```text
PA = {P2,P3,P4,P5,P9,P10,P11}
```

Al recorrer el árbol de alcanzabilidad, la máxima suma de tokens en `PA` es `3`:

```text
máximo de hilos activos simultáneamente = 3
```

Luego separa responsabilidades:

| Segmento | Motivo                                                       |
| -------- | ------------------------------------------------------------ |
| `SA`     | Tramo común previo al fork de `IT1` e `IT2`.                 |
| `SB`     | Rama izquierda posterior al fork.                            |
| `SC`     | Rama derecha posterior al fork.                              |
| `SD`     | Segmento común después de la convergencia de esas dos ramas. |
| `SE`     | `IT3`, que es lineal e independiente.                        |

Cada segmento tiene máximo marcado `1`; por eso:

$$
H_{necesarios}=1+1+1+1+1=5
$$

El ejemplo enseña la distinción central:

```text
3 procesos pueden estar activos simultáneamente,
pero se asignan 5 responsabilidades/instancias de segmento.
```

No es una contradicción: son métricas distintas.

---

## 7. Cómo aplicar el método sin cometer errores

Usar este orden, no al revés:

```text
1. Validar modelo de la RdP
2. Obtener P-invariantes y T-invariantes
3. Clasificar plazas: idle / recurso / restricción / acción
4. Obtener alcanzabilidad
5. Calcular máximo global de actividad
6. Cortar los T-invariantes en forks y convergencias
7. Asignar plazas de acción a segmentos sin duplicarlas
8. Calcular capacidad por segmento
9. Recién ahora elegir cómo mapear las responsabilidades a unidades de ejecución
10. Verificar el modelo y la ejecución con propiedades observables
```

Errores frecuentes:

| Error                                                             | Por qué es incorrecto                                                                                   |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| Usar la cantidad de tokens iniciales como cantidad de hilos.      | El máximo se obtiene sobre todos los marcados alcanzables.                                              |
| Contar recursos como actividades.                                 | Un token en un recurso disponible no representa trabajo en curso.                                       |
| Hacer que una unidad de ejecución elija entre ramas.              | Duplica la decisión de coordinación dentro de una responsabilidad.                                      |
| Separar transiciones consecutivas sin justificar una frontera.    | No es el algoritmo de segmentación del paper; puede ser una optimización, pero debe demostrarse aparte. |
| Duplicar el tramo común posterior a un join.                      | Pierde la responsabilidad única que busca el caso 3.                                                    |
| Decir que una plaza sólo tendrá un token por un recurso anterior. | Un recurso puede serializar llegadas sin impedir acumulación en una plaza posterior.                    |
| Concluir que una ejecución es correcta sólo porque termina.       | Deben verificarse el marcado, los invariantes, la segmentación y el mecanismo de coordinación.          |

---

## Parte II. Aplicación al proyecto

Desde esta sección dejamos de describir el paper en abstracto. Cada resultado se
relaciona con la Red de Petri, la consigna y el código vigente. Cuando una decisión
no está impuesta por el artículo, se la marca como una decisión del proyecto.

La auditoría de implementación corresponde a la configuración vigente de `Main.java`:
seis workers persistentes, una instancia por path, `timed=true` y
`PolicyMode.PRIORITIZED`, salvo que se indique explícitamente otra cosa.

## 8. Aplicación a la agencia de viajes

Las fuentes de verdad para esta sección son:

- modelo: `src/main/java/org/concurrent/project/RdP.java`;
- invariantes esperados: `regex/TInvariants.yaml`;
- análisis estructural: `analysis/model.yaml`, `analysis/paper-config.yaml` y
  `analysis/analyze_paper.py`;
- consigna: `docs/sources/enunciado.pdf`;
- informe entregado de referencia: `docs/sources/informe-entregado.pdf`.

### 8.1 Marcado inicial y lugares

El orden de plazas es `P0,...,P14`. El marcado inicial implementado es:

$$
M_0=(5,1,0,0,5,0,1,1,0,0,1,0,0,0,0)^T
$$

Es decir:

```text
P0=5  P1=1  P4=5  P6=1  P7=1  P10=1
resto=0
```

| Plaza | Interpretación                                      | Clasificación para el algoritmo                                                                     |
| ----- | --------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `P0`  | Buffer de entrada de clientes.                      | Idle.                                                                                               |
| `P1`  | Recurso de ingreso/atención inicial.                | Recurso.                                                                                            |
| `P2`  | Cliente ingresando a la agencia.                    | Acción.                                                                                             |
| `P3`  | Sala de espera.                                     | Acción.                                                                                             |
| `P4`  | Cupo de admisión que T0 consume y T2/T3 restituyen. | Recurso según la consigna; el informe lo trata como restricción. En ambos casos se excluye de `PA`. |
| `P5`  | Gestión de reserva por la rama T2.                  | Acción.                                                                                             |
| `P6`  | Recurso del agente superior.                        | Recurso.                                                                                            |
| `P7`  | Recurso del agente inferior.                        | Recurso.                                                                                            |
| `P8`  | Gestión de reserva por la rama T3.                  | Acción.                                                                                             |
| `P9`  | Espera para confirmación/cancelación.               | Acción.                                                                                             |
| `P10` | Recurso único de decisión de reserva.               | Recurso.                                                                                            |
| `P11` | Reserva confirmada.                                 | Acción.                                                                                             |
| `P12` | Reserva cancelada.                                  | Acción.                                                                                             |
| `P13` | Pago de reserva.                                    | Acción.                                                                                             |
| `P14` | Instancia previa al retiro.                         | Acción.                                                                                             |

Por lo tanto:

$$
PA=\{P2,P3,P5,P8,P9,P11,P12,P13,P14\}
$$

### 8.2 Tabla de transiciones: la topología real

Esta es la interpretación exacta de `RdP.java`. Cada arco tiene peso uno.

| Transición | Pre: consume de | Post: produce en | Interpretación                                       |
| ---------- | --------------- | ---------------- | ---------------------------------------------------- |
| `T0`       | `P0,P1,P4`      | `P2`             | Un cliente ingresa; toma atención inicial y cupo.    |
| `T1`       | `P2`            | `P1,P3`          | Libera atención inicial y pasa a sala de espera.     |
| `T2`       | `P3,P6`         | `P4,P5`          | Toma agente superior y comienza la gestión.          |
| `T3`       | `P3,P7`         | `P4,P8`          | Toma agente inferior y comienza gestión alternativa. |
| `T4`       | `P8`            | `P7,P9`          | Finaliza rama inferior, libera agente inferior.      |
| `T5`       | `P5`            | `P6,P9`          | Finaliza rama superior, libera agente superior.      |
| `T6`       | `P9,P10`        | `P11`            | Confirma reserva; toma el recurso de decisión.       |
| `T7`       | `P9,P10`        | `P12`            | Cancela reserva; toma el mismo recurso.              |
| `T8`       | `P12`           | `P10,P14`        | Completa cancelación, libera recurso de decisión.    |
| `T9`       | `P11`           | `P13`            | Pasa de confirmación a pago.                         |
| `T10`      | `P13`           | `P10,P14`        | Completa pago, libera recurso de decisión.           |
| `T11`      | `P14`           | `P0`             | El cliente retorna al buffer idle.                   |

La visión de flujo es:

```text
P0 --T0--> P2 --T1--> P3
                         |\
                         | \-- T2 --> P5 --T5--\
                         |                        --> P9
                         \---- T3 --> P8 --T4--/
                                                    |\
                                                    | \-- T6 --> P11 --T9--> P13 --T10--\
                                                    |                                 --> P14 --T11--> P0
                                                    \---- T7 --> P12 --T8------------/
```

Los recursos `P1`, `P4`, `P6`, `P7` y `P10` están omitidos en ese dibujo para no
ocultar el flujo principal; sí participan en la habilitación de las transiciones
indicadas en la tabla.

### 8.3 P-invariantes del proyecto

Los siguientes P-invariantes están definidos en `Invariants.java:67-74` y se
verifican luego de cada disparo dentro del monitor:

$$
\begin{aligned}
P1+P2&=1\\
P10+P11+P12+P13&=1\\
P0+P2+P3+P5+P8+P9+P11+P12+P13+P14&=5\\
P2+P3+P4&=5\\
P5+P6&=1\\
P7+P8&=1
\end{aligned}
$$

Interpretación:

- `P1+P2=1`: sólo hay una atención inicial disponible o un cliente siendo
  ingresado.
- `P5+P6=1` y `P7+P8=1`: cada agente está libre o atendiendo exactamente a un
  cliente.
- `P10+P11+P12+P13=1`: el recurso/estado de decisión final es único; un cliente
  puede estar usando ese tramo crítico a la vez.
- La ecuación global conserva los cinco clientes entre idle y todas las etapas de
  acción.
- `P2+P3+P4=5`: el cupo de admisión se conserva entre cliente entrando, esperando
  y capacidad disponible.

### 8.4 Los cuatro T-invariantes

En `regex/TInvariants.yaml` están definidos los cuatro ciclos válidos:

| Nombre  | Secuencia ordenada          |
| ------- | --------------------------- |
| `inv_1` | `T0,T1,T2,T5,T6,T9,T10,T11` |
| `inv_2` | `T0,T1,T2,T5,T7,T8,T11`     |
| `inv_3` | `T0,T1,T3,T4,T6,T9,T10,T11` |
| `inv_4` | `T0,T1,T3,T4,T7,T8,T11`     |

Se forman combinando dos decisiones:

```text
agente:      superior [T2,T5]   o   inferior [T3,T4]
decisión:    confirmar [T6,T9,T10]  o cancelar [T7,T8]
```

Cada invariante toma una alternativa de cada decisión y vuelve a `P0` mediante
`T11`. Por eso `Cx=0` para cada vector de conteo asociado.

### 8.5 Conflictos de la red

#### Conflicto de agentes: T2 versus T3

```text
P3 -> T2, con recurso P6
P3 -> T3, con recurso P7
```

Comparten `P3`, así que hay conflicto estructural. No siempre es efectivo: `P3`
puede tener varios tokens y `P6` y `P7` son recursos independientes. Ambas ramas
pueden progresar en paralelo si existe trabajo suficiente.

#### Conflicto de reservas: T6 versus T7

```text
T6 consume P9 y P10
T7 consume P9 y P10
```

Comparten `P9` y el recurso único `P10`. Como el P-invariante garantiza que el
tramo `P10,P11,P12,P13` tiene un único token, cuando ambas alternativas están
sensibilizadas sólo una puede avanzar. Es el conflicto efectivo más claro para la
política.

---

## 9. Responsabilidades teóricas de nuestra red

### 9.1 Máximo de hilos activos simultáneamente

Usamos la plaza de acción global:

$$
PA=\{P2,P3,P5,P8,P9,P11,P12,P13,P14\}
$$

El P-invariante global da:

$$
M(P0)+\sum_{p\in PA}M(p)=5
$$

Luego:

$$
\sum_{p\in PA}M(p)=5-M(P0)\le 5
$$

La cota es alcanzable. Basta repetir cinco veces el prefijo `T0,T1`; se llega a un
marcado donde los cinco clientes están en `P3`:

```text
P0=0, P3=5, P1=1, P4=0, P6=1, P7=1, P10=1
```

La suma de tokens en `PA` es `5`. El analizador reproducible de `analysis/` enumera
**618 marcados alcanzables** de la topología implementada y confirma ese máximo.

$$
\boxed{H_{activos}=5}
$$

Este resultado del informe es correcto.

### 9.2 Segmentación lógica sugerida

Al aplicar el caso de fork y el de convergencia del paper, una segmentación
estructural natural es:

| Segmento lógico | Transiciones  | Motivo                                             |
| --------------- | ------------- | -------------------------------------------------- |
| `S_A`           | `[T0,T1]`     | Camino común antes de la primera decisión en `P3`. |
| `S_B`           | `[T2,T5]`     | Rama de agente superior hasta el merge en `P9`.    |
| `S_C`           | `[T3,T4]`     | Rama de agente inferior hasta el merge en `P9`.    |
| `S_D`           | `[T6,T9,T10]` | Rama de reserva confirmada hasta `P14`.            |
| `S_E`           | `[T7,T8]`     | Rama de reserva cancelada hasta `P14`.             |
| `S_F`           | `[T11]`       | Tramo común posterior a la convergencia en `P14`.  |

Representación:

```text
S_A: T0,T1
           |
          P3
        /    \
S_B: T2,T5   S_C: T3,T4
        \    /
          P9
        /    \
S_D: T6,T9,T10  S_E: T7,T8
        \    /
          P14
           |
S_F:       T11
```

**[INFERENCIA]** Los seis segmentos son nuestra aplicación de las reglas del
algoritmo 4.2 a la topología y a los cuatro T-invariantes de esta red. El paper
aporta los criterios para separar caminos comunes, forks y convergencias, pero no
define un procedimiento que reciba esta matriz y devuelva automáticamente una
partición única.

**[EVIDENCIA]** Los segmentos se declaran en `analysis/paper-config.yaml`. La función
`validate_segments()` de `analysis/analyze_paper.py` comprueba que cubran todas las
transiciones exactamente una vez y que cada T-invariante pueda descomponerse en
segmentos completos.

Esta tabla responde a **“qué responsabilidad tiene cada tipo de hilo”**, no todavía
a **“cuántas instancias de cada tipo hacen falta”**.

### 9.3 La dificultad real: fronteras P3 y P9

Nuestra red tiene una particularidad que el ejemplo del paper no desarrolla en
detalle: `P9` es a la vez el merge de las ramas de agentes y la plaza previa al
segundo fork. Además, `P3` es una cola que alimenta dos ramas.

Como `P3` y `P9` son plazas de acción situadas entre responsabilidades, una
aplicación rigurosa del algoritmo 4.3 debe decidir cómo tratarlas en los cálculos
por segmento, sin contarlas dos veces.

**[DECISIÓN DEL PROYECTO]** Para este análisis tratamos `P3` y `P9` como fronteras
de despacho. Es decir:

- `P3` queda entre `S_A` y las ramas `S_B`/`S_C`.
- `P9` queda entre `S_B`/`S_C` y las ramas `S_D`/`S_E`.
- Ambas plazas siguen siendo plazas de acción para calcular el máximo global de
  actividad, pero no se asignan a las plazas internas de ningún segmento.
- `P14` se asigna al segmento común `S_F`, cuya secuencia es `[T11]`.

La decisión está declarada en `analysis/paper-config.yaml` mediante
`dispatch_boundaries: [P3, P9]`. No es una regla explícita del paper. Es la
convención que usamos para convertir su descripción de plazas asociadas a segmentos
en un cálculo reproducible para esta topología. La palabra "despacho" describe su
posición en el análisis. No implica crear un worker o un componente adicional.

Con esta convención, las plazas internas son:

```text
S_A: {P2}       S_B: {P5}       S_C: {P8}
S_D: {P11,P13}  S_E: {P12}      S_F: {P14}
```

Valores máximos obtenidos del grafo de alcanzabilidad:

| Plaza o grupo | Máximo alcanzable |
| ------------- | ----------------: |
| `P2`          |                 1 |
| `P3`          |                 5 |
| `P5`          |                 1 |
| `P8`          |                 1 |
| `P9`          |                 5 |
| `P11+P13`     |                 1 |
| `P12`         |                 1 |
| `P14`         |                 5 |

Dos consecuencias importantes:

1. `P3`, `P9` y `P14` pueden acumular hasta cinco clientes. Una capacidad de
   segmento no puede suponerse igual a uno sólo porque haya una transición o un
   worker asociado.
2. El P-invariante de `P10` no impide que se acumulen clientes en `P14`.
   `P10` serializa la entrada a las alternativas finales; después de `T8` o `T10`
   vuelve a estar disponible. Mientras `T11` no se dispare, los clientes pueden
   acumularse en `P14`.

Por ejemplo, es posible procesar secuencialmente cinco clientes hasta `P14` y no
ejecutar `T11`; entonces:

$$
M(P14)=5
$$

### 9.4 Qué puede y qué no puede concluirse sobre la cantidad final

**[INFERENCIA]** El número `5` de activos simultáneos está demostrado por el
invariante global y por un marcado alcanzable con `P3=5`. Eso no determina por sí
solo cuántos objetos `Thread` debe crear Java.

**[AMBIGÜEDAD]** Para obtener capacidades `h_i` comparables hay que fijar primero una
convención para las plazas frontera. El paper no especifica por completo una
topología en la que un merge sea seguido inmediatamente por otro fork. La convención
concreta adoptada para esta red está explicada arriba y se declara en
`analysis/paper-config.yaml`. Otra convención podría producir otra suma sin que el
paper permita elegirla arbitrariamente.

**[PAPER]** El paper propone un único segmento común después de una convergencia.
**[INFERENCIA]** Al aplicar esa regla a esta red, la responsabilidad lógica común es:

```text
S_F = [T11]
```

**[EVIDENCIA]** El análisis de alcanzabilidad obtiene `M(P14)=5` como máximo. Por
eso la capacidad teórica de cinco para la frontera final se sigue del modelo bajo la
formalización adoptada.
**[DECISIÓN DEL PROYECTO]** Ejecutar esa responsabilidad con un solo worker
persistente es la decisión vigente. Su suficiencia en rendimiento requiere una
medición aparte. No debe presentarse como si fuera el resultado literal de `h_F`.

### 9.5 Segmentos lógicos, capacidad y workers persistentes

| Concepto                 |                                       Resultado actual | Procedencia                               |
| ------------------------ | -----------------------------------------------------: | ----------------------------------------- |
| Clientes activos máximos |                                                      5 | [INFERENCIA] del marcado y alcanzabilidad |
| Segmentos lógicos        |                                                      6 | [INFERENCIA] aplicación del algoritmo 4.2 |
| Workers persistentes     |                                                      6 | [PROYECTO] / [DECISIÓN DEL PROYECTO]      |
| Instancias por segmento  |                                                      1 | [DECISIÓN DEL PROYECTO] pragmática        |
| Capacidades internas     | `[1,1,1,1,1,5]`, suma `10` bajo la convención adoptada | [EVIDENCIA] / [DECISIÓN DEL PROYECTO]     |

Los tokens de una RdP ordinaria no tienen identidad. Un worker que llega a `T11`
puede consumir cualquier token disponible en `P14`; no existe una asociación
implícita entre un objeto Java y un cliente concreto.

El analizador reproducible de `analysis/` recorre los marcados alcanzables y calcula
esas capacidades con la misma convención declarada en `analysis/paper-config.yaml`.
Por eso el total `10` debe leerse como una capacidad teórica de responsabilidades,
no como la cantidad de workers que el programa debe crear.

---

## 10. Auditoría de la implementación actual

### 10.1 Workers configurados

**[PROYECTO]** `Main.java:77-105` configura seis `WorkerSpec`, todos con
`countsCompletion=false` excepto el último. La tabla de responsabilidades es:

| Worker     | Path configurado | Segmento lógico |
| ---------- | ---------------- | --------------- |
| `Thread-1` | `[T0,T1]`        | `S_A`           |
| `Thread-2` | `[T2,T5]`        | `S_B`           |
| `Thread-3` | `[T3,T4]`        | `S_C`           |
| `Thread-4` | `[T6,T9,T10]`    | `S_D`           |
| `Thread-5` | `[T7,T8]`        | `S_E`           |
| `Thread-6` | `[T11]`          | `S_F`           |

La configuración actual coincide con la segmentación lógica sugerida por el paper.
Que use una instancia por path es una decisión pragmática; no equivale a afirmar
que el algoritmo 4.3 haya producido exactamente seis instancias.

### 10.2 Ciclo de los workers y contador de 186

**[PROYECTO]** `Threads.java:60-107` repite cada path mientras `running` sea
verdadero.

- El path que comienza en `T0` incrementa `startedInvariants`.
- El único path con `countsCompletion=true` es `[T11]`, que incrementa
  `completedInvariants` al completar el recorrido.
- Ese contador es una condición operacional de parada: no identifica por sí solo
  una correspondencia atómica entre un `T0` y el `T11` que finalmente consume un
  token.
- La validación end-to-end se hace sobre el log mediante los cuatro T-invariantes.

### 10.3 Monitor, política, tiempo y colas

En términos de la arquitectura del paper, el diseño actual está en buena dirección:

| Criterio                     | Evidencia en el proyecto                                         | Estado                              |
| ---------------------------- | ---------------------------------------------------------------- | ----------------------------------- |
| Acceso centralizado a la RdP | `Monitor.fireTransition()` protege consulta y disparo.           | Cumple en flujo normal.             |
| Exclusión mutua              | `Semaphore(1,true)` en `Monitor.java:45-52`.                     | Cumple en flujo normal.             |
| Cola por transición          | `Queues`, con semáforos y contadores por transición.             | Cumple en flujo normal.             |
| Workers no modifican marcado | Sólo llaman a `MonitorInterface.fireTransition()`.               | Cumple.                             |
| Política                     | `Policy.choose()` selecciona un waiter post-disparo.             | No veta disparos directos.          |
| Tiempo                       | `Main.java:16` fija `timed=true`; alfas en `Monitor.java:20-22`. | Activo en la configuración vigente. |
| Acciones de negocio          | No hay objetos de acción separados de los disparos.              | Simplificación del TP.              |

El código usa **Signal and Exit / handoff**: primero señaliza una cola y luego
cede lógicamente el mutex al waiter. No implementa el “Signal and Continue” que
aparece en algunas explicaciones del informe.

`Policy.choose()` sólo se invoca desde `selectWaiterOrRelease()`, después de un
disparo y cuando hay waiters estructuralmente sensibilizados. Por eso sus
porcentajes describen decisiones de handoff, no todos los disparos globales.

### 10.4 Evidencia de ejecución

**[EVIDENCIA]** Los resultados de una corrida no deben quedar embebidos como hechos
permanentes en esta guía. Cada ejecución debe registrar configuración, commit, fecha,
cantidad de workers, modo de política, alfas y resultado del analizador, según los
criterios del `README.md` (sección "Criterios de aceptación").

---

## Parte III. Verificación y defensa

Esta parte reúne las comprobaciones que permiten respaldar la aplicación anterior.
Separa las propiedades verificadas durante la ejecución, los análisis estructurales
offline y las comprobaciones específicas de una traza.

## 11. Cómo verificar y defender el resultado

### 11.1 Verificación formal mínima

Para poder afirmar que la implementación representa la RdP:

1. **Topología:** comprobar que las matrices `Pre`/`Post` o la matriz de incidencia
   coinciden con la figura de la consigna.
2. **Marcado:** tras cada disparo, comprobar que no haya tokens negativos y que se
   aplique `M'=M+C\sigma`.
3. **P-invariantes:** verificar las seis ecuaciones luego de cada disparo.
4. **T-invariantes:** validar algebraicamente las cuatro secuencias desde `M0` y,
   por separado, analizar la traza para comprobar qué ciclos fueron observados.
   El regex es una comprobación post-ejecución de esa traza, no una demostración
   formal para todas las ejecuciones posibles.
5. **Política:** separar disparos reales, llamadas de handoff, candidatos únicos,
   oportunidades con ambos waiters elegibles y selecciones condicionadas a esas
   oportunidades. No llamar “disparo forzado” a todo lo que no pasó por `Policy`.
6. **Responsabilidades:** demostrar que ningún worker elige una rama por sí mismo
   y que cada segmento está justificado por la estructura.

### 11.2 Relaciones de conteo que deben aparecer

Si el log termina exactamente con ciclos completos y sin ejecuciones parciales,
los conteos de transiciones deben satisfacer:

$$
\begin{aligned}
f(T0)&=f(T1)=f(T11)=N\\
f(T2)+f(T3)&=N\\
f(T2)&=f(T5)\\
f(T3)&=f(T4)\\
f(T6)+f(T7)&=N\\
f(T6)&=f(T9)=f(T10)\\
f(T7)&=f(T8)
\end{aligned}
$$

Si se corta el programa al alcanzar `N=186`, puede haber transiciones parciales
iniciadas. En ese caso estas igualdades pueden presentar residuos al final; el
analizador regex debe explicar y listar los remanentes, no ocultarlos.

### 11.3 Ejecución práctica

Desde la raíz del proyecto:

```bash
python3 -m unittest discover -s analysis/tests -v
python3 analysis/analyze_paper.py
scripts/run.sh
python3 regex/InvariantsAnalyzer.py --log logs/run.log --inv regex/TInvariants.yaml
```

El primer comando comprueba el analizador. El segundo genera el análisis estructural
en `target/paper-analysis/`, incluyendo la alcanzabilidad, los invariantes, la
segmentación y las capacidades. Los dos últimos comandos ejecutan Java y analizan la
traza producida.

Para aceptar una corrida como evidencia, verificar:

```text
Invariantes detectados: 186
Resultado: OK
No hay PINV_FAIL en logs/run.log
Los conteos de política cumplen el modo elegido
El análisis estructural termina con todos sus checks en true
```

El análisis debe repetirse para `BALANCED` y `PRIORITIZED` si se pretende afirmar
que ambos modos cumplen la consigna.

### 11.4 Propuesta de decisión técnica para el TP

Antes de cambiar código, el grupo debe elegir una de estas posiciones y documentar
la elegida con honestidad:

#### Opción A: configuración pragmática vigente

- Seis workers, una instancia por segmento lógico:
  `[T0,T1]`, `[T2,T5]`, `[T3,T4]`, `[T6,T9,T10]`, `[T7,T8]` y `[T11]`.
- `T11` es una responsabilidad común explícita posterior a `P14`.
- La cantidad de workers es una **[DECISIÓN DEL PROYECTO]** de implementación, no una afirmación
  de que el algoritmo 4.3 produzca automáticamente el número seis.
- La capacidad teórica por segmento queda documentada aparte, con la convención de
  fronteras y la evidencia de alcanzabilidad.

Es la configuración más clara para defender la segmentación del paper. Debe
distinguirse la capacidad formal de la cantidad de workers persistentes.

#### Opción B: experimento de multiplicidad

- Comparar `S_D×1/S_E×1`, `S_D×2/S_E×1`, `S_D×1/S_E×2` y `S_D×2/S_E×2`.
- Mantener iguales los alfas, la política, el objetivo de 186, la JVM y la máquina.
- Medir throughput, latencia, espera en P9/P14, utilización de P10, handoffs y
  contención del monitor.
- Presentar `2×2` como optimización experimental sólo si la evidencia muestra una
  mejora reproducible. No atribuirlo al paper.

La configuración histórica de dos instancias por rama no tiene actualmente esa
evidencia; queda registrada como caso histórico en el `CHANGELOG.md`.

### 11.5 Guion breve para una defensa oral

1. “Un token representa un cliente/trabajo del modelo, no necesariamente un
   `Thread` Java.”
2. “Excluimos P0, recursos y P4 del conjunto de plazas de acción.”
3. “El máximo de actividad es 5 porque el invariante global conserva cinco
   clientes y el estado con P3=5 es alcanzable.”
4. “Los cuatro T-invariantes son el producto de dos decisiones binarias: agente y
   confirmación/cancelación.”
5. “Los forks determinan segmentos para que los workers no decidan; el monitor y
   la política arbitran.”
6. “P14 es una convergencia OR: la regla del paper sugiere una responsabilidad
   común para T11.”
7. “Luego verificamos P-invariantes por disparo y T-invariantes sobre el log.”

---

## 12. Glosario y preguntas de defensa

### 12.1 Glosario

| Término               | Definición breve                                                        |
| --------------------- | ----------------------------------------------------------------------- |
| Marcado               | Vector con la cantidad de tokens por plaza.                             |
| Sensibilización       | Condición que permite disparar una transición.                          |
| Disparo               | Actualización atómica del marcado mediante la matriz de incidencia.     |
| Plaza idle            | Estado fuera del procesamiento activo.                                  |
| Plaza de recurso      | Token que representa disponibilidad de un recurso compartido.           |
| Plaza de acción       | Estado asociado a un trabajo/proceso activo para el algoritmo.          |
| P-invariante          | Suma conservada de tokens entre plazas.                                 |
| T-invariante          | Multiconjunto/ciclo de transiciones que deja el marcado neto igual.     |
| Conflicto estructural | Transiciones que comparten entrada.                                     |
| Conflicto efectivo    | Conflicto donde faltan tokens para que todas las alternativas disparen. |
| Fork                  | Punto de decisión hacia alternativas.                                   |
| Merge OR              | Convergencia donde llega una ruta u otra; no sincroniza ambas.          |
| Join AND              | Sincronización que exige tokens de varias rutas simultáneamente.        |
| Segmento              | Responsabilidad fija de ejecución de un hilo/agente.                    |
| Política              | Regla que selecciona alternativas ante un conflicto.                    |
| Monitor               | Exclusión mutua, espera, señalización y control del disparo.            |

### 12.2 Preguntas para comprobar comprensión

1. ¿Por qué el máximo de procesos activos se calcula con plazas de acción y no
   contando todos los tokens de la red?
2. ¿Qué diferencia hay entre que `T2` y `T3` compartan `P3` y que el conflicto sea
   efectivo en un marcado concreto?
3. ¿Por qué `P10=1` no implica `P14<=1`?
4. ¿Qué información entrega un P-invariante que no entrega un T-invariante?
5. ¿Por qué una secuencia de worker no debería contener un `if` que elija entre
   `T6` y `T7`?
6. ¿Qué diferencia hay entre cinco procesos activos del modelo y seis workers
   Java creados al inicio?
7. ¿Cuál es la responsabilidad posterior común a las ramas que llegan a `P14`?
8. ¿Qué datos faltan para afirmar formalmente cuántas instancias necesita cada
   segmento según el algoritmo 4.3?

### 12.3 Referencias locales

- Paper base: `docs/sources/paper.pdf`, secciones 3 a 5, especialmente 4.1–4.3.
- Consigna: `docs/sources/enunciado.pdf`, apartado
  “Implementación”.
- Modelo Java: `src/main/java/org/concurrent/project/RdP.java`.
- Workers: `src/main/java/org/concurrent/project/Main.java` y `Threads.java`.
- Monitor: `src/main/java/org/concurrent/project/Monitor.java`.
- Política: `src/main/java/org/concurrent/project/Policy.java`.
- P-invariantes: `src/main/java/org/concurrent/project/Invariants.java`.
- T-invariantes y analizador: `regex/TInvariants.yaml` y
  `regex/InvariantsAnalyzer.py`.
- Segmentación, temporización y política: `POLITICA_Y_JUSTIFICACION.md`.
- Criterios de aceptación de corridas: `README.md`.
