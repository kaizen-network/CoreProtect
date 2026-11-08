"""Compare the schema of two CoreProtect DuckDB files (columns, constraints, sequences).

Usage: .venv/Scripts/python.exe migrate/schema_diff.py <reference.duckdb> <other.duckdb>

Both files are opened read-only. Sequence start and last values are left out on purpose:
a migrated file starts each sequence after its copied rows, a fresh plugin file starts at 1.
Exit code 0 when the schemas match.
"""
import sys

import duckdb

QUERIES = {
    "columns": "SELECT table_name, column_index, column_name, data_type, column_default, is_nullable "
               "FROM duckdb_columns() WHERE schema_name = 'main' ORDER BY ALL",
    "constraints": "SELECT table_name, constraint_type, constraint_text, constraint_column_names "
                   "FROM duckdb_constraints() WHERE schema_name = 'main' ORDER BY ALL",
    "sequences": "SELECT sequence_name, min_value, max_value, increment_by, cycle "
                 "FROM duckdb_sequences() WHERE schema_name = 'main' ORDER BY ALL",
    "tables": "SELECT table_name FROM duckdb_tables() WHERE schema_name = 'main' ORDER BY ALL",
}


def schema(path):
    con = duckdb.connect(path, read_only=True, config={"memory_limit": "256MB", "threads": "1"})
    try:
        return {name: [tuple(map(str, row)) for row in con.execute(sql).fetchall()] for name, sql in QUERIES.items()}
    finally:
        con.close()


def main():
    reference, other = schema(sys.argv[1]), schema(sys.argv[2])
    differences = 0
    for name in QUERIES:
        missing = sorted(set(reference[name]) - set(other[name]))
        extra = sorted(set(other[name]) - set(reference[name]))
        print(f"{name}: {len(reference[name])} in reference, {len(other[name])} in other")
        for row in missing:
            print("  only in reference:", row)
        for row in extra:
            print("  only in other:    ", row)
        differences += len(missing) + len(extra)
    print("schema: SAME" if not differences else f"schema: {differences} difference(s)")
    sys.exit(1 if differences else 0)


if __name__ == "__main__":
    main()
