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
