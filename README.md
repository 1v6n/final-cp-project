# Trabajo final de Programación Concurrente

Simulación concurrente de una Red de Petri de 15 plazas y 12 transiciones.
El proyecto implementa un monitor con colas por transición, políticas de
selección, restricciones temporales y verificación de invariantes.

## Requisitos

- Java 21;
- Maven;
- Python 3 para el análisis posterior del log.

La versión de Java está fijada por `pom.xml`.

## Ejecutar

Desde la raíz del repositorio:

```bash
scripts/run.sh
```

El script compila, ejecuta `org.concurrent.project.Main` y escribe la traza en
`logs/run.log`.

Para analizar los T-invariantes:

```bash
python3 regex/InvariantsAnalyzer.py \
  --log logs/run.log \
  --inv regex/TInvariants.yaml
```

Comprobaciones aisladas:

```bash
mvn compile -q
mvn package -q -DskipTests
```

## Configuración vigente

La configuración actual de `Main.java` usa:

- seis workers persistentes, una instancia por path;
- `timed=true`;
- `PolicyMode.PRIORITIZED`;
- objetivo operacional de 186 finalizaciones.

Los paths son:

```text
[T0,T1]       × 1
[T2,T5]       × 1
[T3,T4]       × 1
[T6,T9,T10]   × 1
[T7,T8]       × 1
[T11]         × 1
```

`Policy` selecciona un waiter durante el handoff posterior a un disparo; no veta
los disparos directos que ya están habilitados.

## Criterios de aceptación de una corrida

Para aceptar una corrida como evidencia, verificar:

1. `Invariantes detectados: 186` y `Resultado: OK` en el analizador de
   T-invariantes.
2. Ausencia de `PINV_FAIL`, `pinv=FAIL` y `ok=false` en `logs/run.log`.
3. Relaciones de conteo:

   ```text
   f(T0)=f(T1)=f(T11)=N
   f(T2)+f(T3)=N
   f(T2)=f(T5)
   f(T3)=f(T4)
   f(T6)+f(T7)=N
   f(T6)=f(T9)=f(T10)
   f(T7)=f(T8)
   ```

4. Al reportar política, separar disparos globales de T2/T3 y T6/T7 de las
   decisiones de handoff (oportunidades con ambos waiters presentes). "Sin
   conflicto" no es sinónimo de "disparo no intervenido por la política".

Cada corrida debe registrar configuración, commit, fecha, política, alfas y
resultado del analizador; el log es regenerable con `scripts/run.sh`.

## Documentación

| Necesidad | Documento |
|---|---|
| Entender el paper y su aplicación | `docs/GUIA_ESTUDIO_PAPER_Y_PROYECTO.md` |
| Segmentación, temporización y política | `docs/POLITICA_Y_JUSTIFICACION.md` |
| Historial de cambios | `CHANGELOG.md` |
| Instrucciones para agentes | `AGENTS.md` |

Las fuentes académicas se encuentran en `docs/sources/`.
