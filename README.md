# ziphelper

A small, **Genero-BDL-friendly** Java helper for creating and extracting zip
archives, backed by [Zip4j](https://github.com/srikanth-lingala/zip4j).

Published to Maven Central as `io.github.4js-mikefolcher:ziphelper`. It is the
Java side of the Genero
[`zipapi`](https://github.com/4js-mikefolcher/zipapi) library — the BDL layer
(`com.fourjs.zip.ZipAPI`) drives this class through `IMPORT JAVA` so application
developers never write or instantiate Java themselves.

## Design

`com.fourjs.zip.ZipHelper` exposes a deliberately narrow surface so it can be
called directly from BDL:

- Every public method takes and returns only primitives and `String`s — no Java
  collections or arrays cross the boundary. Archive listings are exposed as a
  count plus index accessors (`loadEntries()`, `getEntryName(int)`, …).
- In-memory content is exchanged as Base64 `String`s, which map cleanly to BDL
  `STRING`.
- Compression and encryption options are selected by name, not by passing Java
  enum constants.
- A static `create(Operation, String)` factory is used because BDL constructs
  Java objects through a static method rather than `new`.

It supports whole-archive create/extract, single-entry add/extract, listing
without extraction, Base64 in-memory exchange, password (AES / ZIP standard)
encryption, and selectable compression level/method.

## Build

Requires **JDK 8+** and **Maven**.

```sh
mvn package          # build target/ziphelper-2.0.0.jar
mvn install          # install to the local Maven repo (~/.m2) for local use
```

Bytecode targets Java 8 (`maven.compiler.release=8`) for broad runtime
compatibility; it loads fine on the JDK that Genero ships.

## Publishing

Deploy to Maven Central via the Sonatype Central Publisher Portal:

```sh
mvn -Prelease deploy
```

The `release` profile attaches sources + javadoc, GPG-signs the artifacts, and
publishes through the `central-publishing-maven-plugin`. It requires a verified
`io.github.4js-mikefolcher` namespace, a GPG signing key, and a
`<server id="central">` portal token in `~/.m2/settings.xml`. A plain
`mvn package` needs none of this.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
