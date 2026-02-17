#!/usr/bin/env python3

import argparse
import yaml
from pathlib import Path
import re

# CLI args
# python check_invariants.py --log logs/run.log --inv invariants.yaml

def parse_args():
    """
    Lee los argumentos de línea de comandos.
    --log : archivo de log a analizar
    --inv : archivo YAML con invariantes
    """
    parser = argparse.ArgumentParser(
        description="Verificación de invariantes de transición mediante regex"
    )

    parser.add_argument(
        "--log",
        required=True,
        help="Ruta al archivo de log"
    )

    parser.add_argument(
        "--inv",
        required=True,
        help="Ruta al archivo YAML de invariantes"
    )

    return parser.parse_args()

# Carga de invariantes (YAML)

def load_invariants(inv_path):
    """
    Carga el archivo YAML de invariantes de transición.

    Retorna una lista de invariantes, cada uno con:
    - name
    - sequence (lista de transiciones)
    """
    path = Path(inv_path)

    if not path.exists():
        raise FileNotFoundError(f"No existe el archivo de invariantes: {inv_path}")

    with path.open("r", encoding="utf-8") as f:
        data = yaml.safe_load(f)

    if "t_invariants" not in data:
        raise ValueError("El YAML no contiene la clave 't_invariants'")

    return data["t_invariants"]

# Lectura de log

def read_log(log_path):
    """
    Lee el archivo de log completo y lo devuelve como string.
    """
    path = Path(log_path)

    if not path.exists():
        raise FileNotFoundError(f"No existe el archivo de log: {log_path}")

    return path.read_text(encoding="utf-8")

# Extracción de transiciones

def extract_transitions(log_text):
    """
    Extrae las transiciones disparadas del texto del log
    y las devuelve como una única cadena concatenada.

    Ejemplo de retorno:
    'T0T1T2T5T6T9T10T11'
    """

    # ' \| tr= ' fija el campo correcto
    # ' (T\d+) ' captura T0, T10, etc.
    transitions = re.findall(r"\| tr=(T\d+) \|", log_text)

    if not transitions:
        raise ValueError("No se encontraron transiciones en el log")

    return "".join(transitions)

# Construcción de regex y preservacion de interleavings

def build_regex():
    pattern = r"(T0)(.*?)(T1)(.*?)(?:(?:(T2)(.*?)(T5)|(T3)(.*?)(T4))(.*?)(?:(T6)(.*?)(T9)(.*?)(T10)|(T7)(.*?)(T8))(?:(.*?)(T11)(.*?)))"
    replacement = r"\g<2>\g<4>\g<6>\g<9>\g<11>\g<13>\g<15>\g<18>\g<20>\g<22>"
    return re.compile(pattern), replacement

# Verificación / conteo

def count_invariants(sequence):
    pattern, replacement = build_regex()

    counters = {
        "T2-T5 / T6-T9-T10-T11": 0,
        "T2-T5 / T7-T8-T11": 0,
        "T3-T4 / T6-T9-T10-T11": 0,
        "T3-T4 / T7-T8-T11": 0,
    }

    count = 0

    while True:
        m = pattern.search(sequence)
        if not m:
            break

        took_upper = (m.group(5) is not None)   # T2...T5
        took_lower = (m.group(8) is not None)   # T3...T4
        took_left  = (m.group(12) is not None)  # T6...T9...T10
        took_right = (m.group(17) is not None)  # T7...T8

        if took_upper and took_left:
            counters["T2-T5 / T6-T9-T10-T11"] += 1
        elif took_upper and took_right:
            counters["T2-T5 / T7-T8-T11"] += 1
        elif took_lower and took_left:
            counters["T3-T4 / T6-T9-T10-T11"] += 1
        elif took_lower and took_right:
            counters["T3-T4 / T7-T8-T11"] += 1
        else:
            # Caso raro: matcheó pero no pudimos clasificar
            raise RuntimeError("Match encontrado pero no se pudo clasificar la rama del invariante")

        # Consumir 1 invariante
        sequence, n = pattern.subn(replacement, sequence, count=1)
        if n == 0:
            break
        count += 1


    return count, counters, sequence  

# Reporte de resultados

def report_results(count, counters, remainder, expected=None):
    print("\n=== Reporte de invariantes ===")
    print("Invariantes detectados:", count)

    if expected is not None:
        print("Invariantes esperados:", expected)
        print("Resultado:", "OK" if count == expected else "ERROR")

    print("\nDetalle por tipo de invariante:")
    for k, v in counters.items():
        print(f"  {k}: {v}")

    if remainder:
        print("\nTransiciones remanentes:")
        print(remainder)


def main():
    args = parse_args()

    invariants = load_invariants(args.inv)
    log_text = read_log(args.log)

    sequence = extract_transitions(log_text)

    print("Archivo de log cargado:", args.log)
    print("Cantidad de invariantes cargados:", len(invariants))
    print("Primeras transiciones:", sequence[:50])

    # Conteo de invariantes
    count, counters, remainder = count_invariants(sequence)

    EXPECTED_RUNS = 186

    report_results(
        count=count,
        counters=counters,
        remainder=remainder,
        expected=EXPECTED_RUNS
    )
if __name__ == "__main__":
    main()
