![Mak header](https://lh5.googleusercontent.com/m5wiSBcZrS5KGxRELDFWTpaSR6QHeKyc38bPVI0qbQrKmO3tH3PGgYloD58Jui2jUM1zthHIOh2ZwI8x2dlukk3sImpTpENus9qX68PmF2tKcwj0KfU14Mg6lLWT=w768)

# Mak runner
Processes Conto payments for DL Mak.

## Modes
- default – receives payments, updates order sheet
- refund – collects payments from start date and sends them back

See `Main.java` for startup arguments.

## JAR build
To build a standalone JAR run `shadowJar` Gradle task.