# Gradle in Muse's VM — Task 9 result: no working no-daemon recipe

Date: 2026-10-22. Outcome: **failed, with evidence** (Task 9 step 3). Gradle, launched
through the repo's `gradlew`, cannot avoid forking a single-use daemon in this VM, and
the client <-> daemon loopback TCP connection is blocked here, so every build dies
before compiling anything. PRs stay `UNCOMPILED`; `pr-check.yml` remains the compile
check. No repo code, `android/gradle.properties`, `gradlew` or `.github/` file was
changed.

Environment used for every attempt: Temurin JDK 21 and the Android SDK from the local
toolchain, Gradle wrapper 9.8.0, and a Gradle user home whose `gradle.properties`
already carries the egress-proxy and trust-store settings as `systemProp.*` entries.
Each shell first sourced the toolchain env file (sets `JAVA_HOME`, `ANDROID_HOME`,
`GRADLE_USER_HOME`, and the proxy/trust-store JVM flags in `GRADLE_OPTS` / `JAVA_OPTS`).
`android/gradle.properties:1` is `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`
(verified).

## Attempt 1 — in-process via merged `GRADLE_OPTS`

Ran from `android/`, first run without `--offline`, under a 600 s timeout (it exited
on its own; no timeout). `GRADLE_OPTS` was **merged**, not replaced: the in-process
args prepended to the toolchain's existing proxy/trust-store flags, i.e. in shell form
`GRADLE_OPTS="-Xmx4g -Dfile.encoding=UTF-8 $GRADLE_OPTS"` after sourcing the env file.

```
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.daemon.enabled=false --console=plain -q :app:compileReleaseKotlin
```

- Exit code: **1**
- First error line: `The first result from the daemon was empty. The daemon process may have died or a non-daemon process is reusing the same port.`
- Forked? **Yes.** A daemon process log was written under the Gradle user home: the
  daemon started, listened on `[localhost/127.0.0.1]` (ephemeral port), was never
  successfully contacted by the client, and stopped when its 120 s idle timeout
  expired. The wording of the error (a daemon result was expected at all) says the
  same thing.

## Attempt 2 — matching JVM args in the Gradle user home, with `--info`

Added one line to the Gradle user home `gradle.properties` (user home only, NOT the
repo; the existing `systemProp.*` proxy/trust-store lines were preserved):

```
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -Djava.net.preferIPv6Addresses=true -Dhttp.proxyHost=<egress proxy> -Dhttp.proxyPort=3128 -Dhttps.proxyHost=<egress proxy> -Dhttps.proxyPort=3128 -Djavax.net.ssl.trustStore=<toolchain trust store> -Djavax.net.ssl.trustStorePassword=<redacted>
```

Retried attempt 1's command with `--info` instead of `-q`, same merged `GRADLE_OPTS`,
600 s timeout (exited on its own in about a minute).

- Exit code: **1**
- First error line: same as attempt 1 (`The first result from the daemon was empty. ...`),
  preceded in the `--info` output by `Removing daemon from the registry due to communication failure.`
- Forked? **Yes** — `--info` prints `Starting process 'Gradle build daemon'` and
  `To honour the JVM settings for this build a single-use Daemon process will be forked.`
- Why it forked (the `--info` compatibility report, verbatim in substance):
  - Wanted daemon opts: `[-Xmx4g, -Dfile.encoding=UTF-8, -Djavax.net.ssl.trustStore=..., -Djavax.net.ssl.trustStorePassword=..., -Duser.country=US, -Duser.language=en, -Duser.variant]`, `applyInstrumentationAgent=true`
  - Actual client opts: the same list **plus `-Xms64m`**, and `applyInstrumentationAgent=false`
  - `-Xms64m` is hard-coded in `gradlew`'s `DEFAULT_JVM_OPTS`
    (`android/gradlew:203`: `DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'`); `gradlew` is
    forbidden to edit, and `GRADLE_OPTS` cannot remove an `-Xms` that is already on
    the client command line.
  - The instrumentation-agent flag differs because the daemon is forked with a
    `-javaagent:` argument and the `gradlew`-launched client is not.
- The user-home `org.gradle.jvmargs` added for this attempt had **no effect** on the
  Wanted list (none of its extra args appear there): the project's
  `android/gradle.properties` takes precedence for that property, and that file is
  forbidden to edit. The added line was removed from the user home afterwards,
  restoring its original contents.

## Conclusion

Through `gradlew` as shipped, `--no-daemon` always forks a single-use daemon here —
two concrete, launcher-side JVM-arg mismatches guarantee it — and the one loopback
TCP connection that fork needs is exactly what this VM blocks for Java processes
(the daemon listens, the client's first read comes back empty, exit 1). So there is
no working recipe to record: **compile exit code: 1 (both attempts, before any
compilation); unit tests not run; no test count.** This is unchanged from the
pre-Task-9 state, not a regression.

If the Java-loopback block is ever lifted, no special recipe is needed: the checks
in this file's "Before every push" section (`:app:compileReleaseKotlin`,
`:app:testDebugUnitTest`) can be run as written.

## UNVERIFIED

- A non-`gradlew` launch (invoking the Gradle distribution's main class directly
  with exactly the Wanted JVM opts plus the instrumentation agent) was not tried:
  it is outside Task 9's prescribed steps and is not the repo's build entry point.
  Whether it would run in-process, and whether the Kotlin compile daemon's RMI
  loopback would then block it anyway (the `-P` flags above were meant to disable
  that too), is unverified — the build never got far enough to find out.
- That the two mismatches above are the *only* reasons for the fork: `--info`
  reports them as one combined "at least one daemon option is different" verdict,
  so fixing just one might still fork on the other.

## Task 13 update (2026-10-22): the loopback block is IPv4-only; the daemon link is fixed; the build now stops one stage later, at dependency resolution

Task 9's conclusion above stands as written for `gradlew`'s fork behaviour, but
its "loopback is blocked" framing was too broad. Tested directly, without Gradle
(the task's `Loop.java`: A = classic sockets, B = NIO channels, one byte `42`
each way on loopback; 30 s timeout per run):

Environment inventory first (values redacted as in Task 9): `JAVA_TOOL_OPTIONS`,
`_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` are all **unset**. The toolchain env file
sets `JAVA_OPTS` = `-Djava.net.preferIPv6Addresses=true` + trust-store flags, and
`GRADLE_OPTS` = the same plus `-Dhttp.proxyHost=<egress proxy>`,
`-Dhttp.proxyPort=3128` and the `https` pair. The Gradle user home
`gradle.properties` carries these `systemProp.*` key names: `http.proxyHost`,
`http.proxyPort`, `https.proxyHost`, `https.proxyPort`, `http.nonProxyHosts`,
`https.nonProxyHosts`, `java.net.preferIPv6Addresses`,
`javax.net.ssl.trustStore`, `javax.net.ssl.trustStorePassword`. Non-Java loopback
works: a python3 server/client pair on `127.0.0.1` exchanged the byte
(`python read = 42`).

| Variant | A (classic) | B (NIO) |
| --- | --- | --- |
| V1 toolchain env, `127.0.0.1` | `A client: java.net.SocketTimeoutException: Read timed out` | `B read n=-1 value=-1` |
| V2 toolchain env, `localhost` (bound `/127.0.0.1`) | same read timeout | `B read n=-1 value=-1` |
| V3 toolchain env, `localhost`, `-Djava.net.preferIPv6Addresses=true` (bound `/[0:0:0:0:0:0:0:1]`) | `A read=42` | `B read n=1 value=42` |
| V4 `*_JAVA_OPTIONS` unset, `127.0.0.1` | same read timeout | `B read n=-1 value=-1` |
| V5 V1 + `http`/`socksNonProxyHosts` props | same read timeout | `B read n=-1 value=-1` |

Every run printed `socksProxyHost=null`. Readings:

- **IPv4 loopback from Java is blackholed; IPv6 loopback works completely.**
  On `127.0.0.1` the classic client connects and then reads nothing until its
  timeout, and the NIO client gets an immediate EOF (`n=-1`) — the
  accept-then-close shape Claude predicted from "the first result was empty".
  On `[::1]` both halves pass. Python on `127.0.0.1` passing shows the block is
  specific to Java-initiated TCP, not to the address.
- The `*_JAVA_OPTIONS` variables and the proxy properties are exonerated
  (V4/V5 change nothing). One trap: in V1/V2 the header printed
  `preferIPv6=null` even though the toolchain env was sourced — `JAVA_OPTS` is
  a launcher-script variable; a plain `java` invocation never reads it, so the
  flag was never active in those runs.
- Why Task 9's runs still failed with the flag "set": it reached only the
  client (via `GRADLE_OPTS`). Config A below shows the daemon advertising
  `addresses:[/127.0.0.1]` with **no** `preferIPv6Addresses` in its
  `daemonOpts` — `systemProp.*` in `gradle.properties` does not govern the
  daemon's bind address.

### Gradle runs (daemon allowed, from `android/`, 900 s timeout, `:app:compileReleaseKotlin :app:testDebugUnitTest`)

- **Config A** — toolchain env exactly as-is. Exit **1**:
  `A new daemon was started but could not be connected to` /
  `The first result from the daemon was empty`, with
  `DaemonInfo{... address=[... port:37125, addresses:[/127.0.0.1]] ... daemonOpts=-Xmx4g,-Dfile.encoding=UTF-8,-Djavax.net.ssl.trustStore=...,-Duser.country=US,...}`.
- **Config B** — A plus `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true"`
  (picked up at startup by every JVM: client, daemon, workers). **The daemon
  link works.** The daemon log records the build arriving over IPv6 loopback:
  `socket connection from /0:0:0:0:0:0:0:1:35979 to /0:0:0:0:0:0:0:1:43512` and
  `Received command: Build{...}`. The build then fails at a later stage,
  exit **1**: `Plugin [id: 'com.android.application', version: '9.4.1', apply: false] was not found` —
  `could not resolve plugin artifact 'com.android.application:com.android.application.gradle.plugin:9.4.1'`
  after searching Google, MavenRepo and the Gradle Central Plugin Repository.
- **Config C** — B plus `-Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8 -Djava.net.preferIPv6Addresses=true"`
  on the command line. Exit **1**, same plugin failure in 4 s — the missing-module
  result from Config B was served from Gradle's cache. A Config B re-run with
  `--refresh-dependencies --info` still failed the same way; its log shows
  requests only to `repo.maven.apache.org`, answered with a clean
  `Resource missing` (HTTP 404 — expected, the marker is not published there);
  no Google or Plugin Portal request appears at `--info` level.

Supporting probes for the Config B failure: `curl` through the same egress
proxy returns HTTP 200 for the marker POM from Google's repository, so the
artifact and the proxy path exist. A plain Java HTTPS GET of the same URL
(proxy properties + `preferIPv6Addresses` + the toolchain trust store, no
Gradle) gets through the proxy CONNECT but fails TLS:
`javax.net.ssl.SSLHandshakeException: (bad_certificate) PKIX path validation failed: java.security.cert.CertPathValidatorException: signature check failed`.
So Java egress currently dies at certificate validation against the proxy's
intercepted chain when using the install-time trust store.

### Conclusion

The recipe for the client <-> daemon link (copy-paste; the toolchain env file
sourced first, as usual):

```
JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true" ./gradlew --console=plain :app:compileReleaseKotlin :app:testDebugUnitTest
```

That settles Task 13's question: loopback itself is not blocked — **IPv4
loopback is, for Java processes; IPv6 loopback is open**, and forcing every JVM
onto IPv6 makes the daemon reachable (Task 9's fork mismatch is moot once a
normal daemon is allowed: it forks, listens on `[::1]`, and the build runs).
The full compile + test recipe is **not** working yet: the build stops at
plugin/dependency resolution, most likely the trust store versus the egress
proxy's current CA chain (the Java probe above), so the "Before every push"
compile + test steps still do **not** apply and PRs remain `UNCOMPILED`.
Suggested next step, outside this task's 3-configuration budget and not done:
rebuild the toolchain trust store from the proxy's current CA bundle and
re-run Config B.

## Task 13 UNVERIFIED

- That Gradle's plugin-resolution failure *is* the TLS failure from the Java
  probe: no `PKIX` line appears in the daemon log, and the Google / Plugin
  Portal requests never appear at `--info` (possibly consumed by the cached
  miss from Config B's first attempt). The two failures are adjacent and the
  probe proves Java + this trust store + this proxy cannot complete an
  intercepted TLS handshake, but the daemon's own cause is not captured.
- Test workers never ran, so whether worker <-> build loopback also works
  under the recipe is inferred from the daemon link (same JVM flag via
  `JAVA_TOOL_OPTIONS`), not observed. Test count: none; no test ran.
- Whether the Kotlin compile daemon (a separate JVM, RMI over loopback) works
  under the recipe: the build never reached compilation.
